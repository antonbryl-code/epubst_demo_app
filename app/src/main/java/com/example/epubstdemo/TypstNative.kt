package com.example.epubstdemo

/**
 * Kotlin side of the Rust library built from app/src/main/rust.
 * Native failures surface as IllegalArgumentException (bad input) or RuntimeException (panics).
 */
internal object TypstNative {
    init {
        System.loadLibrary("epubst_typst")
    }

    /**
     * Copies the files into a native bundle and returns its handle; free it with [closeBundle].
     * Downloaded packages are kept in [packageCache]; null allows only bundled packages.
     */
    external fun openBundle(
        paths: Array<String>,
        contents: Array<ByteArray>,
        rootfile: String,
        packageCache: String?,
    ): Long
    external fun closeBundle(handle: Long)
    external fun bundleRootfile(handle: Long): String
    external fun bundleFileCount(handle: Long): Int
    /** Size in bytes of the file at [path], or -1 if the bundle has no such file. */
    external fun bundleFileSize(handle: Long, path: String): Long
    /**
     * Compiles the bundle, offering [widthPt] and [marginPt] through `sys.inputs`, and renders it.
     * Returns `[sizes: FloatArray, svgs: Array<String>, textLayers: Array<String>, warnings: String,
     * null, title: String?]`, sizes being `width0, height0, width1, height1, ..` in points. If
     * packages must be downloaded first, returns `[null, null, null, null, missing: Array<String>,
     * null]` with specs like `@preview/cetz:0.3.4` instead. Throws [TypstCompileException] on errors.
     */
    external fun compile(handle: Long, widthPt: Float, marginPt: Float): Array<Any?>

    /** Unpacks a package's `.tar.gz` (as served by Typst Universe) into [cacheDir]. */
    external fun installPackage(cacheDir: String, spec: String, archive: ByteArray)
}
