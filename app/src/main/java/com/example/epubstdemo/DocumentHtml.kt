package com.example.epubstdemo

import java.util.Locale

/**
 * Builds the page shown in the document WebView: the page SVGs inlined and stacked vertically.
 *
 * The viewport is `device-width`, so 1 CSS px = 1 dp = 0.75pt and a page reflowed to the view's
 * width fills it exactly. Pages wider than the view (documents rendered as authored) are scaled
 * down to fit; narrower ones keep their size and are centered.
 */
object DocumentHtml {
    /** Vertical space between pages, in CSS px. */
    private const val PAGE_GAP_PX = 8

    /**
     * [dark] picks the background around the pages (the pages themselves stay as the document
     * paints them). It's passed in rather than left to `prefers-color-scheme`, which the WebView
     * derives from the app's XML theme instead of the system's dark mode. [topPaddingPx] keeps
     * the top of the document clear of a toolbar laid over the view.
     */
    fun build(document: TypstDocument, dark: Boolean, topPaddingPx: Int = 0): String = buildString {
        val scheme = if (dark) "dark" else "light"
        val background = if (dark) "#202124" else "#e8e8e8"
        append(
            """
            <!DOCTYPE html>
            <html><head>
            <meta charset="utf-8">
            <meta name="viewport" content="width=device-width, initial-scale=1">
            <meta name="color-scheme" content="$scheme">
            <style>
              html, body { margin: 0; padding: 0; background: $background; }
              body { padding-top: ${topPaddingPx}px; }
              .page {
                margin: 0 auto ${PAGE_GAP_PX}px; background: white;
                position: relative; container-type: inline-size;
              }
              .page:last-child { margin-bottom: 0; }
              .page > svg { display: block; width: 100%; height: 100%; }
              /* The invisible, selectable text layer over each page (see text_layer.rs). Its
                 lengths are in --u, one Typst point at the page's current size. */
              .t {
                position: absolute; inset: 0; overflow: hidden;
                color: transparent; font-family: monospace; line-height: 1; white-space: pre;
              }
              .t .l { position: absolute; white-space: pre; }
              .t .r { display: inline-block; vertical-align: top; }
              /* Words: letter-spaced to fill their box exactly, so nothing is clipped (Chromium
                 hides selection handles that fall into clipped content). */
              .t .l > span, .t .r > span { display: inline-block; vertical-align: top; height: 1em; }
              .t .k { position: absolute; transform-origin: 0 0; }
              /* Highlight selections, but keep the stand-in font hidden while selected. */
              .t ::selection { color: transparent; background: rgba(66, 133, 244, 0.35); }
            </style>
            </head><body>
            """.trimIndent()
        )
        for (page in document.pages) {
            // Typst sizes pages in pt; the aspect ratio keeps scaled-down pages proportional.
            val widthPx = page.widthPt / 0.75f
            append("<div class=\"page\" style=\"width: min(100%, ")
            append(css(widthPx))
            append("px); aspect-ratio: ")
            append(css(page.widthPt))
            append(" / ")
            append(css(page.heightPt))
            // The text layer's unit: one Typst point at the page's actual width.
            append("; --u: calc(100cqw / ")
            append(css(page.widthPt))
            append(")\">")
            append(page.svg)
            append("<div class=\"t\">")
            append(page.textLayer)
            append("</div></div>\n")
        }
        append("</body></html>")
    }

    private fun css(value: Float) = String.format(Locale.ROOT, "%.3f", value)
}
