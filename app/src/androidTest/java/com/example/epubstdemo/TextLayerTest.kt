package com.example.epubstdemo

import androidx.test.ext.junit.runners.AndroidJUnit4
import androidx.test.platform.app.InstrumentationRegistry
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test
import org.junit.runner.RunWith
import java.io.File

@RunWith(AndroidJUnit4::class)
class TextLayerTest {
    private fun compile(text: String): TypstDocument {
        val files = mapOf("main.typ" to text.toByteArray())
        return TypstBundle.open(EpubstContents("main.typ", files)).use { it.compile(300f, 12f) }
    }

    /** Word or whitespace boxes: `<span style="width:calc(W*var(--u));letter-spacing:…">TEXT</span>`. */
    private val segment =
        Regex("""<span style="width:calc\(([\d.]+)\*var\(--u\)\);letter-spacing:calc\(-?[\d.]+\*var\(--u\)\)">([^<]*)</span>""")

    /** The layer's lines, each as the concatenated text of its segments. */
    private fun lines(layer: String): List<String> =
        layer.split("<div class=\"l\"").drop(1).map { line ->
            segment.findAll(line.substringBefore("</div>")).joinToString("") { it.groupValues[2] }
        }

    private val page = """
        #set page(width: auto, height: auto, margin: 10pt)
        #set text(size: 10pt)
        #block(width: 280pt)[Plain *bold* and _italic_ & \<markup\>.]
        Second paragraph with a #link("https://typst.app/?a=1&b=2")[link].
    """.trimIndent()

    @Test
    fun keepsRunsOfALineTogether() {
        val layer = compile(page).pages.single().textLayer
        assertEquals(listOf("Plain bold and italic &amp; &lt;markup&gt;.", "Second paragraph with a link."), lines(layer))
        // Each line ends in a <br>: that's what makes copied text break lines.
        assertEquals(2, Regex("<br></div>").findAll(layer).count())
    }

    @Test
    fun sizesEachWordToItsLaidOutWidth() {
        val layer = compile(page).pages.single().textLayer
        val segments = segment.findAll(layer).toList()
        assertTrue(layer, segments.size > 10)
        for (s in segments) assertTrue(s.value, s.groupValues[1].toDouble() > 0)
        // Words and the whitespace between them are separate boxes.
        assertTrue(layer, segments.any { it.groupValues[2] == "Plain" } && segments.any { it.groupValues[2] == " " })
    }

    @Test
    fun repeatsLinksAboveTheText() {
        val layer = compile(page).pages.single().textLayer
        assertTrue(layer, layer.contains("<a class=\"k\" href=\"https://typst.app/?a=1&amp;b=2\" style=\"left:calc("))
        // Links come after all lines, so they're on top.
        assertTrue(layer, layer.indexOf("<a class=\"k\"") > layer.lastIndexOf("<div class=\"l\""))
    }

    @Test
    fun keepsTheSvgFreeOfTheLayer() {
        val page = compile(page).pages.single()
        assertFalse(page.svg.contains("class=\"l\""))
    }

    @Test
    fun writesSampleForDesktopInspection() {
        // Not an assertion: leaves the viewer's HTML for a sample in the app's cache, so the text
        // layer can be checked in a desktop Chromium (selection, copy) after pulling it.
        val context = InstrumentationRegistry.getInstrumentation().targetContext
        File(context.cacheDir, "text-layer-sample.html").writeText(DocumentHtml.build(compile(page), dark = false))
    }
}
