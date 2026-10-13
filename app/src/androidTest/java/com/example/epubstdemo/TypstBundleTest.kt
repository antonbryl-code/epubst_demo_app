package com.example.epubstdemo

import androidx.test.ext.junit.runners.AndroidJUnit4
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNotEquals
import org.junit.Assert.assertThrows
import org.junit.Assert.assertTrue
import org.junit.Test
import org.junit.runner.RunWith

@RunWith(AndroidJUnit4::class)
class TypstBundleTest {
    private val contents = EpubstContents(
        rootfile = "src/main.typ",
        files = mapOf(
            "META-INF/container.xml" to ByteArray(10),
            "src/main.typ" to "= Hello".toByteArray(),
            "img/logo.png" to ByteArray(1234),
        ),
    )

    @Test
    fun copiesFilesToNative() {
        TypstBundle.open(contents).use { bundle ->
            assertEquals("src/main.typ", bundle.rootfile)
            assertEquals(3, bundle.fileCount)
            assertEquals(7L, bundle.fileSize("src/main.typ"))
            assertEquals(1234L, bundle.fileSize("img/logo.png"))
            assertEquals(-1L, bundle.fileSize("missing.typ"))
        }
    }

    @Test
    fun handlesManyFiles() {
        // More files than Android's 512 local JNI references per native frame.
        val files = (0 until 2000).associate { "f$it.typ" to ByteArray(it) }
        TypstBundle.open(EpubstContents("f1.typ", files)).use { bundle ->
            assertEquals(2000, bundle.fileCount)
            assertEquals(1999L, bundle.fileSize("f1999.typ"))
        }
    }

    private fun open(vararg files: Pair<String, String>): TypstBundle {
        val map = files.associate { (path, text) -> path to text.toByteArray() }
        return TypstBundle.open(EpubstContents(files.first().first, map))
    }

    private fun compileDocument(vararg files: Pair<String, String>): TypstDocument =
        open(*files).use { it.compile(WIDTH, MARGIN) }

    private fun compile(vararg files: Pair<String, String>): List<TypstPage> =
        compileDocument(*files).pages

    /** Typst code that fails compilation unless the text area is [width] minus both margins. */
    private fun assertTextWidth(width: Float = WIDTH) =
        "#context layout(size => assert(calc.abs((size.width - ${width - 2 * MARGIN}pt) / 1pt) < 0.01, " +
            "message: \"text width \" + repr(size.width)))"

    /** Every page is [WIDTH] wide and, being pageless, only as tall as its short content. */
    private fun assertPageless(pages: List<TypstPage>) {
        for (page in pages) {
            assertEquals(WIDTH, page.widthPt, 0.01f)
            assertTrue("height ${page.heightPt}", page.heightPt < 200f)
        }
    }

    /** A document that opts into reflowing, with its template in a separate file. */
    private fun reflowable(body: String) = arrayOf(
        "main.typ" to "#import \"template.typ\": conf\n#show: conf\n$body",
        "template.typ" to """
            #let conf(doc, paper: "a4", margin: 2.5cm) = {
              let width = sys.inputs.at("epubst-width", default: none)
              if width == none {
                set page(paper: paper, margin: margin)
                doc
              } else {
                set page(width: eval(width), height: auto, margin: eval(sys.inputs.at("epubst-margin")))
                doc
              }
            }
        """.trimIndent(),
    )

    @Test
    fun passesGeometryAsInputs() {
        val text = """
            #assert.eq(sys.inputs.at("epubst-width"), "300.5pt")
            #assert.eq(sys.inputs.at("epubst-margin"), "12pt")
        """.trimIndent()
        open("main.typ" to text).use { it.compile(300.5f, MARGIN) }
    }

    @Test
    fun textWidthCheckDetectsWrongWidth() {
        // Guards the tests below: the in-document assertion must actually run and fail.
        val e = assertThrows(TypstCompileException::class.java) {
            compile(*reflowable("Hello\n${assertTextWidth(WIDTH + 10f)}"))
        }
        assertTrue(e.message, e.message!!.contains("text width 276pt"))
    }

    @Test
    fun reflowsDocumentsThatOptIn() {
        val pages = compile(*reflowable("Hello\n${assertTextWidth()}\n#pagebreak()\nWorld"))
        assertEquals(2, pages.size)
        assertPageless(pages)
    }

    @Test
    fun rendersOtherDocumentsAsAuthored() {
        val a5 = compile("main.typ" to "#set page(paper: \"a5\", flipped: true)\nHello").single()
        assertEquals(595.28f, a5.widthPt, 0.01f)
        assertEquals(419.53f, a5.heightPt, 0.01f)
        // Typst's default page is A4.
        val default = compile("main.typ" to "Hello").single()
        assertEquals(595.28f, default.widthPt, 0.01f)
        assertEquals(841.89f, default.heightPt, 0.01f)
    }

    @Test
    fun recompilesAtNewWidth() {
        open(*reflowable("Hello")).use { bundle ->
            assertEquals(300f, bundle.compile(300f, MARGIN).pages.single().widthPt, 0.01f)
            assertEquals(420f, bundle.compile(420f, MARGIN).pages.single().widthPt, 0.01f)
            assertEquals(300f, bundle.compile(300f, MARGIN).pages.single().widthPt, 0.01f)
        }
    }

    @Test
    fun rendersEachPageToSvg() {
        val pages = compile(*reflowable("Hello\n#pagebreak()\nWorld"))
        assertEquals(2, pages.size)
        for (page in pages) {
            assertTrue(page.svg, page.svg.startsWith("<svg"))
            assertTrue(page.svg, page.svg.trimEnd().endsWith("</svg>"))
            assertTrue(page.svg, page.svg.contains("width=\"300pt\""))
            assertTrue(page.svg, page.svg.contains("viewBox=\"0 0 300 "))
        }
        assertNotEquals(pages[0].svg, pages[1].svg)
    }

    @Test
    fun embedsImagesInSvg() {
        val page = compile(
            "main.typ" to "#image(\"dot.svg\", width: 1cm)",
            "dot.svg" to """<svg xmlns="http://www.w3.org/2000/svg" width="10" height="10"><circle cx="5" cy="5" r="5"/></svg>""",
        ).single()
        assertTrue(page.svg, page.svg.contains("<image"))
    }

    @Test
    fun reportsWarnings() {
        assertEquals("", compileDocument("main.typ" to "Hello").warnings)
        val document = compileDocument("main.typ" to "#set text(font: \"No Such Font\")\nHello")
        assertTrue(document.warnings, document.warnings.contains("main.typ:1:"))
        assertTrue(document.warnings, document.warnings.contains("unknown font family: no such font"))
    }

    @Test
    fun reportsTitle() {
        assertEquals(null, compileDocument("main.typ" to "Hello").title)
        assertEquals(null, compileDocument("main.typ" to "#set document(title: \"  \")\nHello").title)
        // Markup is reduced to plain text.
        val titled = compileDocument("main.typ" to "#set document(title: [The _Typst_ Book])\nHello")
        assertEquals("The Typst Book", titled.title)
    }

    @Test
    fun findsEmbeddedFonts() {
        // The default fonts are embedded compressed (embedded_fonts.rs); each must load.
        val document = compileDocument(
            "main.typ" to """
                #text(font: "Libertinus Serif", fallback: false)[Serif *bold* _italic_]
                #text(font: "New Computer Modern", fallback: false)[Modern *bold* _italic_]
                #text(font: "DejaVu Sans Mono", fallback: false)[Mono *bold* _italic_]
                $ integral_0^1 x^2 dif x = 1/3 $
            """.trimIndent()
        )
        assertEquals("", document.warnings)
        assertTrue(document.pages.single().svg.contains("<path"))
    }

    @Test
    fun findsSystemFonts() {
        // Roboto ships only with Android, so no warning means /system/fonts was scanned.
        val document = compileDocument("main.typ" to "#set text(font: \"Roboto\")\nHello")
        assertEquals("", document.warnings)
    }

    @Test(expected = IllegalArgumentException::class)
    fun rejectsBadWidth() {
        open("main.typ" to "Hello").use { it.compile(0f, MARGIN) }
    }

    private companion object {
        const val WIDTH = 300f
        const val MARGIN = 12f
    }

    @Test
    fun compilesWithImportsAndImages() {
        val pages = compile(
            "src/main.typ" to """
                #import "lib.typ": greet
                #import "/shared/style.typ": accent
                = #greet("Typst")
                #text(fill: accent)[Today is #datetime.today().display().]
                #image("/img/dot.svg", width: 1cm)
                #pagebreak()
                $ sum_(i=1)^n i = (n(n+1))/2 $
            """.trimIndent(),
            "src/lib.typ" to "#let greet(name) = [Hello, #name!]",
            "shared/style.typ" to "#let accent = blue",
            "img/dot.svg" to """<svg xmlns="http://www.w3.org/2000/svg" width="10" height="10"><circle cx="5" cy="5" r="5"/></svg>""",
        )
        assertEquals(2, pages.size)
    }

    @Test
    fun reportsErrorsWithPositions() {
        val e = assertThrows(TypstCompileException::class.java) {
            compile("main.typ" to "= Title\n\n#undefined-function()")
        }
        assertTrue(e.message, e.message!!.contains("main.typ:3:2: unknown variable: undefined-function"))
    }

    @Test
    fun reportsMissingFile() {
        val e = assertThrows(TypstCompileException::class.java) {
            compile("main.typ" to "#include \"chapter.typ\"")
        }
        assertTrue(e.message, e.message!!.contains("chapter.typ"))
    }

    @Test(expected = IllegalArgumentException::class)
    fun rejectsMissingRootfile() {
        TypstBundle.open(EpubstContents("nope.typ", contents.files))
    }

    @Test(expected = IllegalStateException::class)
    fun failsAfterClose() {
        val bundle = TypstBundle.open(contents)
        bundle.close()
        bundle.close() // idempotent
        bundle.fileCount
    }
}
