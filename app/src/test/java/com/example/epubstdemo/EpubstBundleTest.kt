package com.example.epubstdemo

import org.junit.Assert.assertArrayEquals
import org.junit.Assert.assertEquals
import org.junit.Test
import java.io.ByteArrayInputStream
import java.io.ByteArrayOutputStream
import java.util.zip.ZipEntry
import java.util.zip.ZipOutputStream

class EpubstBundleTest {
    private fun zip(vararg entries: Pair<String, String>): ByteArrayInputStream {
        val out = ByteArrayOutputStream()
        ZipOutputStream(out).use { zip ->
            for ((name, content) in entries) {
                zip.putNextEntry(ZipEntry(name))
                zip.write(content.toByteArray())
                zip.closeEntry()
            }
        }
        return ByteArrayInputStream(out.toByteArray())
    }

    private val containerXml = """
        <?xml version="1.0" encoding="UTF-8"?>
        <container version="1.0" xmlns="urn:oasis:names:tc:opendocument:xmlns:container">
          <rootfiles>
            <rootfile full-path="src/main.typ" media-type="text/vnd.typst"/>
          </rootfiles>
        </container>
    """.trimIndent()

    @Test
    fun readsRootfileAndAllFiles() {
        val contents = EpubstBundle.read(
            zip(
                "mimetype" to "application/epubst+zip",
                "src/" to "",
                "src/main.typ" to "= Hello",
                "META-INF/container.xml" to containerXml,
            )
        )
        assertEquals("src/main.typ", contents.rootfile)
        assertEquals(setOf("mimetype", "src/main.typ", "META-INF/container.xml"), contents.files.keys)
        assertArrayEquals("= Hello".toByteArray(), contents.files["src/main.typ"])
    }

    @Test(expected = EpubstFormatException::class)
    fun failsWithoutContainer() {
        EpubstBundle.read(zip("main.typ" to "= Hello"))
    }

    @Test(expected = EpubstFormatException::class)
    fun failsWithoutRootfile() {
        EpubstBundle.read(zip("META-INF/container.xml" to "<container/>"))
    }

    @Test(expected = EpubstFormatException::class)
    fun failsWhenRootfileIsMissingFromZip() {
        EpubstBundle.read(zip("META-INF/container.xml" to containerXml))
    }

    @Test(expected = EpubstFormatException::class)
    fun failsWhenTooLarge() {
        EpubstBundle.read(
            zip("META-INF/container.xml" to containerXml, "src/main.typ" to "x".repeat(2000)),
            maxTotalSize = 1000,
        )
    }
}
