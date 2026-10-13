package com.example.epubstdemo

import java.io.File
import java.io.IOException

/**
 * A compiled document: its pages in order, Typst's warnings (empty if none), and the title set
 * with `#set document(title: ..)` as plain text (null if none).
 */
class TypstDocument(val pages: List<TypstPage>, val warnings: String, val title: String? = null)

/**
 * A page as a standalone SVG, `width="[widthPt]pt"` with a matching viewBox, plus [textLayer]:
 * HTML with the page's text, invisible but selectable, to lay over the SVG (see
 * text_layer.rs and [DocumentHtml]). Documents that reflow have one page per manual page
 * break, each as tall as its content.
 */
class TypstPage(val svg: String, val textLayer: String, val widthPt: Float, val heightPt: Float)

/**
 * A bundle's files held in native memory, ready to compile. Reuse one instance across
 * recompiles (e.g. width changes) and [close] it when the document is no longer shown.
 */
class TypstBundle private constructor(
    private var handle: Long,
    private val packageCache: File?,
) : AutoCloseable {
    val rootfile: String
        @Synchronized get() = TypstNative.bundleRootfile(checkOpen())

    val fileCount: Int
        @Synchronized get() = TypstNative.bundleFileCount(checkOpen())

    /** Size in bytes of the file at [path], or -1 if the bundle has no such file. */
    @Synchronized
    fun fileSize(path: String): Long = TypstNative.bundleFileSize(checkOpen(), path)

    /**
     * Compiles the rootfile and renders each page to SVG. Slow (especially the first call in a
     * process, which scans fonts), so call it off the main thread.
     *
     * The view's geometry is offered to the document as `sys.inputs` strings in Typst length
     * syntax: `epubst-width` ("[widthPt]pt") and `epubst-margin` ("[marginPt]pt"). Documents
     * that support reflowing use them to lay out pageless at that width; others render as
     * authored. See viewer_inputs.rs for an example `conf` function.
     *
     * Packages (`#import "@preview/…"`) come from the bundle's `packages/` directory, else from
     * the package cache given to [open]; `@preview` packages found in neither are obtained with
     * [fetcher], installed into the cache, and the compile is retried. That may need network
     * access, so this can block for a while too.
     * @throws TypstCompileException if the document has errors, or a package can't be had.
     */
    @Synchronized
    fun compile(widthPt: Float, marginPt: Float, fetcher: PackageFetcher? = null): TypstDocument {
        val installed = mutableSetOf<String>()
        while (true) {
            val result = TypstNative.compile(checkOpen(), widthPt, marginPt)
            val (sizes, svgs, layers, warnings, missing) = result
            if (missing == null) {
                sizes as FloatArray
                @Suppress("UNCHECKED_CAST")
                svgs as Array<String>
                @Suppress("UNCHECKED_CAST")
                layers as Array<String>
                val pages = svgs.mapIndexed { i, svg -> TypstPage(svg, layers[i], sizes[2 * i], sizes[2 * i + 1]) }
                return TypstDocument(pages, warnings as String, result[5] as String?)
            }
            @Suppress("UNCHECKED_CAST")
            missing as Array<String>
            // Each round can reveal packages that the packages just installed import in turn.
            val needed = missing.filter { it !in installed }
            if (fetcher == null || packageCache == null || needed.isEmpty() || installed.size >= MAX_PACKAGES) {
                throw TypstCompileException("Packages not available: ${missing.joinToString()}")
            }
            for (spec in needed) {
                val archive = try {
                    fetcher.fetch(spec)
                } catch (e: IOException) {
                    throw TypstCompileException("Couldn't download $spec: ${e.message}")
                }
                TypstNative.installPackage(packageCache.path, spec, archive)
                installed += spec
            }
        }
    }

    @Synchronized
    override fun close() {
        if (handle != 0L) {
            TypstNative.closeBundle(handle)
            handle = 0L
        }
    }

    private fun checkOpen(): Long {
        check(handle != 0L) { "TypstBundle is closed" }
        return handle
    }

    companion object {
        /** Upper bound on packages downloaded for one compile, against runaway dependency chains. */
        private const val MAX_PACKAGES = 50

        /**
         * Copies [contents] into native memory. Packages downloaded during [compile] are kept in
         * [packageCache] (and found there by later bundles); without it, only packages bundled
         * under `packages/<namespace>/<name>/<version>/` are available.
         */
        fun open(contents: EpubstContents, packageCache: File? = null): TypstBundle {
            val paths = contents.files.keys.toTypedArray()
            val data = Array(paths.size) { contents.files.getValue(paths[it]) }
            val handle = TypstNative.openBundle(paths, data, contents.rootfile, packageCache?.path)
            return TypstBundle(handle, packageCache)
        }
    }
}
