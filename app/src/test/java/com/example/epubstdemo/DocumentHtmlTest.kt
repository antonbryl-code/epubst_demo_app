package com.example.epubstdemo

import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test

class DocumentHtmlTest {
    private fun page(widthPt: Float, heightPt: Float, id: String) =
        TypstPage("<svg id=\"$id\" width=\"${widthPt}pt\"></svg>", "<div class=\"l\">$id</div>", widthPt, heightPt)

    @Test
    fun stacksPagesInOrder() {
        val html = DocumentHtml.build(TypstDocument(listOf(page(300f, 40f, "a"), page(300f, 60f, "b")), ""), dark = false)
        assertEquals(2, Regex("<div class=\"page\"").findAll(html).count())
        assertTrue(html.indexOf("id=\"a\"") < html.indexOf("id=\"b\""))
    }

    @Test
    fun sizesPagesInCssPixels() {
        // 300pt = 400 CSS px; scaled down if the view is narrower, never up.
        val html = DocumentHtml.build(TypstDocument(listOf(page(300f, 41.5f, "a")), ""), dark = false)
        assertTrue(html, html.contains("width: min(100%, 400.000px); aspect-ratio: 300.000 / 41.500"))
    }

    @Test
    fun laysTextLayerOverEachPage() {
        val html = DocumentHtml.build(TypstDocument(listOf(page(300f, 41.5f, "a")), ""), dark = false)
        // After the SVG, inside the page, with the page's point unit for its lengths.
        assertTrue(html, html.contains("--u: calc(100cqw / 300.000)\"><svg id=\"a\""))
        assertTrue(html, html.contains("</svg><div class=\"t\"><div class=\"l\">a</div></div></div>"))
    }

    @Test
    fun followsDarkMode() {
        val empty = TypstDocument(emptyList(), "")
        val light = DocumentHtml.build(empty, dark = false)
        val dark = DocumentHtml.build(empty, dark = true)
        assertTrue(light, light.contains("background: #e8e8e8") && light.contains("content=\"light\""))
        assertTrue(dark, dark.contains("background: #202124") && dark.contains("content=\"dark\""))
    }

    @Test
    fun leavesRoomForToolbar() {
        val empty = TypstDocument(emptyList(), "")
        assertTrue(DocumentHtml.build(empty, dark = false).contains("body { padding-top: 0px; }"))
        assertTrue(DocumentHtml.build(empty, dark = false, topPaddingPx = 64).contains("body { padding-top: 64px; }"))
    }

    @Test
    fun usesDeviceWidthViewport() {
        val html = DocumentHtml.build(TypstDocument(emptyList(), ""), dark = false)
        assertTrue(html.contains("<meta name=\"viewport\" content=\"width=device-width, initial-scale=1\">"))
    }
}
