//! An .epubst bundle held in memory, serving as the Typst [`World`] it compiles in.

use std::collections::HashMap;
use std::fmt::Write;
use std::path::{Path, PathBuf};
use std::sync::LazyLock;

use typst::diag::{FileError, FileResult, SourceDiagnostic};
use typst::foundations::{Bytes, Datetime, Duration};
use typst::model::Document;
use typst::syntax::{FileId, RootedPath, Source, VirtualPath, VirtualRoot};
use typst::text::{Font, FontBook};
use typst::utils::LazyHash;
use typst::{Library, LibraryExt, World, WorldExt};
use typst_kit::datetime::Time;
use typst_kit::files::{FileLoader, FileStore};
use typst_kit::fonts::{self, FontStore};
use typst_layout::PagedDocument;

use crate::embedded_fonts;
use crate::packages::Packages;
use crate::text_layer;
use crate::viewer_inputs::{self, ViewerGeometry};

/// Typst's embedded fonts first (so its defaults resolve to them), then the device's fonts.
/// Scanning the system fonts takes a moment, so it happens once per process.
static FONTS: LazyLock<FontStore> = LazyLock::new(|| {
    let mut store = FontStore::new();
    store.extend(embedded_fonts::embedded());
    // fontdb's system font discovery is a no-op on Android, so scan the font directory directly.
    store.extend(fonts::scan(Path::new("/system/fonts")));
    store
});

pub struct Bundle {
    rootfile: String,
    main: FileId,
    files: FileStore<BundleFiles>,
    time: Time,
    /// Carries `geometry` in `sys.inputs`; rebuilt when a compile offers different geometry.
    library: LazyHash<Library>,
    geometry: Option<ViewerGeometry>,
}

/// A compiled document: one SVG per page, plus warnings formatted like errors (empty if none).
pub struct Rendered {
    pub pages: Vec<RenderedPage>,
    pub warnings: String,
    /// The title set with `#set document(title: ..)`, as plain text, if any and not blank.
    pub title: Option<String>,
}

/// A page as a standalone SVG, sized in points (`width="..pt"` with a matching viewBox), and
/// its [`text_layer`] as HTML to lay over it.
pub struct RenderedPage {
    pub svg: String,
    pub text_layer: String,
    pub width: f64,
    pub height: f64,
}

/// Why a compile produced no document.
pub enum CompileFailure {
    /// The formatted errors.
    Errors(String),
    /// Packages to download (see [`packages`]) before compiling again, e.g.
    /// `@preview/cetz:0.3.4`. Other errors may well be consequences of their absence.
    MissingPackages(Vec<String>),
}

/// The bundle's files, keyed by normalized path without a leading slash, and its packages.
struct BundleFiles {
    files: HashMap<String, Bytes>,
    packages: Packages,
}

impl FileLoader for BundleFiles {
    fn load(&self, id: FileId) -> FileResult<Bytes> {
        match id.root() {
            VirtualRoot::Project => {
                let path = id.vpath().get_without_slash();
                self.files.get(path).cloned().ok_or_else(|| FileError::NotFound(path.into()))
            }
            VirtualRoot::Package(spec) => {
                self.packages.load(spec, id.vpath(), |path| self.files.get(path).cloned())
            }
        }
    }
}

impl Bundle {
    /// `files` is keyed by the path inside the zip; `rootfile` must be one of those paths.
    /// `package_cache` is where downloaded packages are kept; without it, only packages
    /// bundled under `packages/` are available.
    pub fn new(
        rootfile: String,
        files: HashMap<String, Vec<u8>>,
        package_cache: Option<PathBuf>,
    ) -> Result<Self, String> {
        let mut normalized = HashMap::with_capacity(files.len());
        for (path, data) in files {
            // Typst can't address entries whose paths escape the root or are malformed; skip them.
            if let Ok(vpath) = VirtualPath::new(&path) {
                normalized.insert(vpath.get_without_slash().to_owned(), Bytes::new(data));
            }
        }
        let main_path = VirtualPath::new(&rootfile)
            .map_err(|e| format!("Invalid rootfile path {rootfile}: {e:?}"))?;
        if !normalized.contains_key(main_path.get_without_slash()) {
            return Err(format!("Rootfile {rootfile} not found in bundle"));
        }
        let packages = Packages::new(normalized.keys().map(String::as_str), package_cache);
        Ok(Self {
            rootfile,
            main: RootedPath::new(VirtualRoot::Project, main_path).intern(),
            files: FileStore::new(BundleFiles { files: normalized, packages }),
            time: Time::system(),
            library: LazyHash::new(Library::default()),
            geometry: None,
        })
    }

    pub fn rootfile(&self) -> &str {
        &self.rootfile
    }

    pub fn file_count(&self) -> usize {
        self.files.loader().files.len()
    }

    pub fn file_data(&self, path: &str) -> Option<&[u8]> {
        let vpath = VirtualPath::new(path).ok()?;
        self.files.loader().files.get(vpath.get_without_slash()).map(Bytes::as_slice)
    }

    /// Compiles the rootfile, offering `geometry` to it through `sys.inputs` (see
    /// [`viewer_inputs`]), and renders each page to SVG.
    pub fn compile(&mut self, geometry: ViewerGeometry) -> Result<Rendered, CompileFailure> {
        if self.geometry != Some(geometry) {
            self.library = LazyHash::new(viewer_inputs::library(geometry));
            self.geometry = Some(geometry);
        }
        // Forget cached load results, so packages installed since the last compile are found.
        // (Sources are kept for reuse, so this doesn't cost incremental compilation.)
        self.files.reset();
        self.files.loader().packages.clear_missing();
        let result = typst::compile::<PagedDocument>(&*self);
        let document = result.output.map_err(|errors| {
            let missing = self.files.loader().packages.take_missing();
            if missing.is_empty() {
                CompileFailure::Errors(self.format_diagnostics(&errors))
            } else {
                CompileFailure::MissingPackages(missing)
            }
        })?;
        let options = typst_svg::SvgOptions::default();
        let pages = document
            .pages()
            .iter()
            .map(|page| {
                let size = page.frame.size();
                RenderedPage {
                    svg: typst_svg::svg(page, &options),
                    text_layer: text_layer::render(&page.frame),
                    width: size.x.to_pt(),
                    height: size.y.to_pt(),
                }
            })
            .collect();
        let title = document
            .info()
            .title
            .as_deref()
            .map(str::trim)
            .filter(|title| !title.is_empty())
            .map(str::to_owned);
        Ok(Rendered { pages, warnings: self.format_diagnostics(&result.warnings), title })
    }

    /// Renders diagnostics as `path:line:col: message` lines, followed by any hints.
    fn format_diagnostics(&self, diagnostics: &[SourceDiagnostic]) -> String {
        let mut out = String::new();
        for diagnostic in diagnostics {
            if !out.is_empty() {
                out.push('\n');
            }
            if let Some(id) = diagnostic.span.id() {
                match id.root() {
                    VirtualRoot::Package(spec) => {
                        write!(out, "{spec}{}", id.vpath().get_with_slash())
                    }
                    VirtualRoot::Project => write!(out, "{}", id.vpath().get_without_slash()),
                }
                .ok();
                let position = self.range(diagnostic.span).zip(self.source(id).ok());
                if let Some((range, source)) = position {
                    let lines = source.lines();
                    if let Some((line, column)) =
                        lines.byte_to_line(range.start).zip(lines.byte_to_column(range.start))
                    {
                        write!(out, ":{}:{}", line + 1, column + 1).ok();
                    }
                }
                out.push_str(": ");
            }
            out.push_str(&diagnostic.message);
            for hint in &diagnostic.hints {
                write!(out, "\n  hint: {}", hint.v).ok();
            }
        }
        out
    }
}

impl World for Bundle {
    fn library(&self) -> &LazyHash<Library> {
        &self.library
    }

    fn book(&self) -> &LazyHash<FontBook> {
        FONTS.book()
    }

    fn main(&self) -> FileId {
        self.main
    }

    fn source(&self, id: FileId) -> FileResult<Source> {
        self.files.source(id)
    }

    fn file(&self, id: FileId) -> FileResult<Bytes> {
        self.files.file(id)
    }

    fn font(&self, index: usize) -> Option<Font> {
        FONTS.font(index)
    }

    fn today(&self, offset: Option<Duration>) -> Option<Datetime> {
        self.time.today(offset)
    }
}
