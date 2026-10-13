//! JNI bridge between the app and the Typst compiler.
//!
//! Bundles live on the native side and are handed to Kotlin as opaque `jlong` handles
//! (a leaked `Box<Bundle>`), so they can be compiled repeatedly without re-copying files.

mod bundle;
mod embedded_fonts;
mod packages;
mod text_layer;
mod viewer_inputs;

use std::collections::HashMap;
use std::path::PathBuf;
use std::panic::{catch_unwind, AssertUnwindSafe};

use jni::objects::{JByteArray, JObject, JObjectArray, JString};
use jni::sys::{jfloat, jint, jlong, jobjectArray, jsize, jstring};
use jni::JNIEnv;

use bundle::{Bundle, CompileFailure};
use viewer_inputs::ViewerGeometry;

const ILLEGAL_ARGUMENT: &str = "java/lang/IllegalArgumentException";
const COMPILE_EXCEPTION: &str = "com/example/epubstdemo/TypstCompileException";

/// A failure to report to Java as an exception of the given class.
struct Error {
    class: &'static str,
    message: String,
}

impl Error {
    fn new(class: &'static str, message: impl Into<String>) -> Self {
        Self { class, message: message.into() }
    }
}

impl From<jni::errors::Error> for Error {
    fn from(e: jni::errors::Error) -> Self {
        Error::new(ILLEGAL_ARGUMENT, e.to_string())
    }
}

impl From<String> for Error {
    fn from(message: String) -> Self {
        Error::new(ILLEGAL_ARGUMENT, message)
    }
}

/// Runs `body`, turning errors into Java exceptions and panics into RuntimeException.
/// Returns `fallback` (ignored by Java) whenever an exception is thrown.
fn guard<'local, T>(
    env: &mut JNIEnv<'local>,
    fallback: T,
    body: impl FnOnce(&mut JNIEnv<'local>) -> Result<T, Error>,
) -> T {
    match catch_unwind(AssertUnwindSafe(|| body(&mut *env))) {
        Ok(Ok(value)) => value,
        Ok(Err(Error { class, message })) => {
            throw(env, class, &message);
            fallback
        }
        Err(panic) => {
            let message = panic
                .downcast_ref::<&str>()
                .map(|s| s.to_string())
                .or_else(|| panic.downcast_ref::<String>().cloned())
                .unwrap_or_else(|| "unknown panic".into());
            throw(env, "java/lang/RuntimeException", &format!("Native panic: {message}"));
            fallback
        }
    }
}

fn throw(env: &mut JNIEnv, class: &str, message: &str) {
    // A JNI call that failed may already have left a Java exception pending; keep that one.
    if !env.exception_check().unwrap_or(true) {
        let _ = env.throw_new(class, message);
    }
}

fn bundle<'a>(handle: jlong) -> Result<&'a Bundle, Error> {
    if handle == 0 {
        return Err(Error::new(ILLEGAL_ARGUMENT, "Bundle handle is null"));
    }
    // SAFETY: non-zero handles come from openBundle and Kotlin stops using them after closeBundle.
    Ok(unsafe { &*(handle as *const Bundle) })
}

fn bundle_mut<'a>(handle: jlong) -> Result<&'a mut Bundle, Error> {
    if handle == 0 {
        return Err(Error::new(ILLEGAL_ARGUMENT, "Bundle handle is null"));
    }
    // SAFETY: as for `bundle`; additionally, TypstBundle serializes all calls on a handle,
    // so no other reference to this bundle is live.
    Ok(unsafe { &mut *(handle as *mut Bundle) })
}

#[no_mangle]
pub extern "system" fn Java_com_example_epubstdemo_TypstNative_openBundle<'local>(
    mut env: JNIEnv<'local>,
    _this: JObject<'local>,
    paths: JObjectArray<'local>,
    contents: JObjectArray<'local>,
    rootfile: JString<'local>,
    package_cache: JString<'local>,
) -> jlong {
    guard(&mut env, 0, |env| {
        let count = env.get_array_length(&paths)?;
        if env.get_array_length(&contents)? != count {
            return Err(Error::new(ILLEGAL_ARGUMENT, "paths and contents differ in length"));
        }
        let mut files = HashMap::with_capacity(count as usize);
        for i in 0..count {
            // Delete local refs as we go: Android caps a native frame at 512 of them.
            let path_ref = JString::from(env.get_object_array_element(&paths, i)?);
            let path: String = env.get_string(&path_ref)?.into();
            env.delete_local_ref(path_ref)?;
            let data_ref = JByteArray::from(env.get_object_array_element(&contents, i)?);
            let data = env.convert_byte_array(&data_ref)?;
            env.delete_local_ref(data_ref)?;
            files.insert(path, data);
        }
        let rootfile: String = env.get_string(&rootfile)?.into();
        let package_cache = optional_string(env, &package_cache)?.map(PathBuf::from);
        let bundle = Bundle::new(rootfile, files, package_cache)?;
        Ok(Box::into_raw(Box::new(bundle)) as jlong)
    })
}

fn optional_string(env: &mut JNIEnv, string: &JString) -> Result<Option<String>, Error> {
    if string.is_null() {
        return Ok(None);
    }
    Ok(Some(env.get_string(string)?.into()))
}

/// `TypstNative.installPackage(cacheDir, spec, archive)`: unpacks a package's `.tar.gz`, as
/// downloaded from Typst Universe, into the package cache. `spec` is e.g. `@preview/cetz:0.3.4`.
#[no_mangle]
pub extern "system" fn Java_com_example_epubstdemo_TypstNative_installPackage<'local>(
    mut env: JNIEnv<'local>,
    _this: JObject<'local>,
    cache_dir: JString<'local>,
    spec: JString<'local>,
    archive: JByteArray<'local>,
) {
    guard(&mut env, (), |env| {
        let cache_dir: String = env.get_string(&cache_dir)?.into();
        let spec: String = env.get_string(&spec)?.into();
        let archive = env.convert_byte_array(&archive)?;
        packages::install(PathBuf::from(cache_dir), &spec, &archive)?;
        Ok(())
    })
}

#[no_mangle]
pub extern "system" fn Java_com_example_epubstdemo_TypstNative_closeBundle<'local>(
    _env: JNIEnv<'local>,
    _this: JObject<'local>,
    handle: jlong,
) {
    if handle != 0 {
        // SAFETY: the handle came from openBundle and Kotlin closes it at most once.
        drop(unsafe { Box::from_raw(handle as *mut Bundle) });
    }
}

#[no_mangle]
pub extern "system" fn Java_com_example_epubstdemo_TypstNative_bundleRootfile<'local>(
    mut env: JNIEnv<'local>,
    _this: JObject<'local>,
    handle: jlong,
) -> jstring {
    guard(&mut env, std::ptr::null_mut(), |env| {
        let rootfile = bundle(handle)?.rootfile();
        Ok(env.new_string(rootfile)?.into_raw())
    })
}

#[no_mangle]
pub extern "system" fn Java_com_example_epubstdemo_TypstNative_bundleFileCount<'local>(
    mut env: JNIEnv<'local>,
    _this: JObject<'local>,
    handle: jlong,
) -> jint {
    guard(&mut env, 0, |_| Ok(bundle(handle)?.file_count() as jint))
}

#[no_mangle]
pub extern "system" fn Java_com_example_epubstdemo_TypstNative_bundleFileSize<'local>(
    mut env: JNIEnv<'local>,
    _this: JObject<'local>,
    handle: jlong,
    path: JString<'local>,
) -> jlong {
    guard(&mut env, -1, |env| {
        let path: String = env.get_string(&path)?.into();
        Ok(bundle(handle)?.file_data(&path).map_or(-1, |data| data.len() as jlong))
    })
}

/// Compiles the bundle, offering the view's width and a margin (in points) through `sys.inputs`,
/// and renders it to one SVG per page. Returns `[sizes, svgs, textLayers, warnings, missing,
/// title]`: a `float[]` of `width0, height0, width1, height1, ..` in points, a `String[]` of page
/// SVGs, a `String[]` of the pages' HTML text layers, and the formatted warnings (empty if none);
/// `missing` is null, and `title` the document's title or null. Or, if packages need downloading
/// first, only `missing`: a `String[]` of package specs like `@preview/cetz:0.3.4`. Throws
/// TypstCompileException on errors.
#[no_mangle]
pub extern "system" fn Java_com_example_epubstdemo_TypstNative_compile<'local>(
    mut env: JNIEnv<'local>,
    _this: JObject<'local>,
    handle: jlong,
    width_pt: jfloat,
    margin_pt: jfloat,
) -> jobjectArray {
    guard(&mut env, std::ptr::null_mut(), |env| {
        if !(width_pt.is_finite() && width_pt > 0.0 && margin_pt.is_finite() && margin_pt >= 0.0) {
            return Err(Error::new(ILLEGAL_ARGUMENT, format!("Bad page geometry: width {width_pt}, margin {margin_pt}")));
        }
        let geometry = ViewerGeometry { width: width_pt, margin: margin_pt };
        let rendered = match bundle_mut(handle)?.compile(geometry) {
            Ok(rendered) => rendered,
            Err(CompileFailure::Errors(message)) => return Err(Error::new(COMPILE_EXCEPTION, message)),
            Err(CompileFailure::MissingPackages(specs)) => {
                let missing = env.new_object_array(specs.len() as jsize, "java/lang/String", JObject::null())?;
                for (i, spec) in specs.iter().enumerate() {
                    let spec = env.new_string(spec)?;
                    env.set_object_array_element(&missing, i as jsize, &spec)?;
                    env.delete_local_ref(spec)?;
                }
                let result = env.new_object_array(6, "java/lang/Object", JObject::null())?;
                env.set_object_array_element(&result, 4, &missing)?;
                return Ok(result.into_raw());
            }
        };

        let sizes: Vec<jfloat> = rendered
            .pages
            .iter()
            .flat_map(|page| [page.width as jfloat, page.height as jfloat])
            .collect();
        let sizes_array = env.new_float_array(sizes.len() as jsize)?;
        env.set_float_array_region(&sizes_array, 0, &sizes)?;

        let pages = rendered.pages.len() as jsize;
        let svgs = env.new_object_array(pages, "java/lang/String", JObject::null())?;
        let layers = env.new_object_array(pages, "java/lang/String", JObject::null())?;
        for (i, page) in rendered.pages.iter().enumerate() {
            let svg = env.new_string(&page.svg)?;
            env.set_object_array_element(&svgs, i as jsize, &svg)?;
            env.delete_local_ref(svg)?;
            let layer = env.new_string(&page.text_layer)?;
            env.set_object_array_element(&layers, i as jsize, &layer)?;
            env.delete_local_ref(layer)?;
        }

        let warnings = env.new_string(&rendered.warnings)?;
        let result = env.new_object_array(6, "java/lang/Object", JObject::null())?;
        env.set_object_array_element(&result, 0, &sizes_array)?;
        env.set_object_array_element(&result, 1, &svgs)?;
        env.set_object_array_element(&result, 2, &layers)?;
        env.set_object_array_element(&result, 3, &warnings)?;
        if let Some(title) = &rendered.title {
            let title = env.new_string(title)?;
            env.set_object_array_element(&result, 5, &title)?;
        }
        Ok(result.into_raw())
    })
}
