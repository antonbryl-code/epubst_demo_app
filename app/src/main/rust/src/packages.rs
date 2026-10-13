//! Typst packages (`#import "@preview/name:1.0.0"`), served from, in order:
//! 1. the bundle itself, under `packages/<namespace>/<name>/<version>/`, so books can be
//!    self-contained and work offline;
//! 2. the on-device cache of downloaded packages;
//! 3. nowhere yet: `@preview` packages are then reported as missing, and the app downloads them
//!    from Typst Universe (with Android's HTTPS stack), [`install`]s them into the cache and
//!    compiles again.

use std::collections::{BTreeSet, HashSet};
use std::io::Read;
use std::path::PathBuf;
use std::str::FromStr;
use std::sync::Mutex;

use typst::diag::{FileError, FileResult, PackageError};
use typst::ecow::eco_format;
use typst::foundations::Bytes;
use typst::syntax::package::PackageSpec;
use typst::syntax::VirtualPath;
use typst_kit::packages::FsPackages;

/// Typst Universe's namespace, the only one the app can download from.
const UNIVERSE_NAMESPACE: &str = "preview";

/// Upper bound on a package's unpacked size, against decompression bombs.
const MAX_UNPACKED_SIZE: u64 = 256 * 1024 * 1024;

pub struct Packages {
    /// Bundled packages, as their directories (`packages/<namespace>/<name>/<version>`).
    bundled: HashSet<String>,
    cache: Option<FsPackages>,
    /// Downloadable packages requested during the current compile but not available.
    missing: Mutex<BTreeSet<String>>,
}

impl Packages {
    /// `bundle_paths` are the bundle's normalized file paths; `cache` the download cache, if
    /// downloads are enabled.
    pub fn new<'a>(bundle_paths: impl Iterator<Item = &'a str>, cache: Option<PathBuf>) -> Self {
        let bundled = bundle_paths
            .filter_map(|path| {
                let mut parts = path.splitn(5, '/');
                let (root, namespace, name, version) =
                    (parts.next()?, parts.next()?, parts.next()?, parts.next()?);
                // Only files inside a version directory count (not e.g. `packages/README`).
                parts.next()?;
                (root == "packages").then(|| format!("packages/{namespace}/{name}/{version}"))
            })
            .collect();
        Self { bundled, cache: cache.map(FsPackages::new), missing: Mutex::new(BTreeSet::new()) }
    }

    /// Loads a file of a package; `bundle_file` looks up a file in the bundle by normalized path.
    pub fn load(
        &self,
        spec: &PackageSpec,
        vpath: &VirtualPath,
        bundle_file: impl Fn(&str) -> Option<Bytes>,
    ) -> FileResult<Bytes> {
        let dir = format!("packages/{}/{}/{}", spec.namespace, spec.name, spec.version);
        if self.bundled.contains(&dir) {
            let path = format!("{dir}/{}", vpath.get_without_slash());
            return bundle_file(&path).ok_or_else(|| FileError::NotFound(path.into()));
        }
        if let Some(root) = self.cache.as_ref().and_then(|cache| cache.obtain(spec)) {
            return root.load(vpath);
        }
        if self.cache.is_some() && spec.namespace == UNIVERSE_NAMESPACE {
            self.missing.lock().unwrap().insert(spec.to_string());
            return Err(FileError::Package(PackageError::Other(Some(eco_format!(
                "{spec} is not downloaded yet"
            )))));
        }
        Err(FileError::Package(PackageError::NotFound(spec.clone())))
    }

    /// Forgets which packages were missing, before a new compile.
    pub fn clear_missing(&self) {
        self.missing.lock().unwrap().clear();
    }

    /// The downloadable packages the last compile asked for but couldn't get, e.g.
    /// `@preview/cetz:0.3.4`.
    pub fn take_missing(&self) -> Vec<String> {
        std::mem::take(&mut *self.missing.lock().unwrap()).into_iter().collect()
    }
}

/// Unpacks a package's `.tar.gz` (as served by Typst Universe) into the download cache.
pub fn install(cache: PathBuf, spec: &str, archive: &[u8]) -> Result<(), String> {
    let spec = PackageSpec::from_str(spec).map_err(|e| format!("Invalid package {spec}: {e}"))?;
    FsPackages::new(cache)
        .store(&spec, |dir| {
            let unpacked = flate2::read::GzDecoder::new(archive).take(MAX_UNPACKED_SIZE);
            // tar refuses entries that would escape `dir`.
            tar::Archive::new(unpacked)
                .unpack(dir)
                .map_err(|e| PackageError::MalformedArchive(Some(eco_format!("{e}"))))
        })
        .map_err(|e| format!("Couldn't install {spec}: {e}"))
}
