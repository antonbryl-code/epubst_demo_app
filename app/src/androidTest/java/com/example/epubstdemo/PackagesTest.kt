package com.example.epubstdemo

import androidx.test.ext.junit.runners.AndroidJUnit4
import androidx.test.platform.app.InstrumentationRegistry
import org.junit.After
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertThrows
import org.junit.Assert.assertTrue
import org.junit.Test
import org.junit.runner.RunWith
import java.io.ByteArrayOutputStream
import java.io.File
import java.io.IOException
import java.util.zip.GZIPOutputStream

@RunWith(AndroidJUnit4::class)
class PackagesTest {
    private val context = InstrumentationRegistry.getInstrumentation().targetContext
    private val cache = File(context.cacheDir, "test-packages-${System.nanoTime()}")

    @After
    fun deleteCache() {
        cache.parentFile!!.listFiles { f -> f.name.startsWith("test-packages-") }?.forEach { it.deleteRecursively() }
    }

    /** A package `name` whose `hello(x)` greets in upper case, using a file of its own. */
    private fun greetPackage(name: String = "greet", extraImport: String = "") = mapOf(
        "typst.toml" to "[package]\nname = \"$name\"\nversion = \"0.1.0\"\nentrypoint = \"lib.typ\"\n",
        "lib.typ" to "$extraImport#import \"util.typ\": shout\n#let hello(x) = shout[Hello #x]\n",
        "util.typ" to "#let shout(body) = upper(body)\n",
    )

    private val main = "main.typ" to "#import \"@preview/greet:0.1.0\": hello\n#hello(\"World\")"

    private fun open(vararg files: Pair<String, String>, cache: File? = this.cache): TypstBundle {
        val map = files.associate { (path, text) -> path to text.toByteArray() }
        return TypstBundle.open(EpubstContents(files.first().first, map), cache)
    }

    private fun text(document: TypstDocument) =
        Regex(">([^<]+)</span>").findAll(document.pages.joinToString { it.textLayer }).joinToString("") { it.groupValues[1] }

    /** A minimal `.tar.gz` (ustar format), like Typst Universe serves packages. */
    private fun tarGz(files: Map<String, String>): ByteArray {
        val out = ByteArrayOutputStream()
        GZIPOutputStream(out).use { gz ->
            for ((name, text) in files) {
                val data = text.toByteArray()
                val header = ByteArray(512)
                fun put(offset: Int, s: String) = s.toByteArray().copyInto(header, offset)
                put(0, name)
                put(100, "0000644\u0000") // mode
                put(108, "0000000\u0000") // uid
                put(116, "0000000\u0000") // gid
                put(124, "%011o\u0000".format(data.size))
                put(136, "%011o\u0000".format(0)) // mtime
                put(148, "        ") // checksum placeholder
                header[156] = '0'.code.toByte() // regular file
                put(257, "ustar\u0000")
                put(263, "00")
                put(148, "%06o\u0000 ".format(header.sumOf { it.toInt() and 0xff }))
                gz.write(header)
                gz.write(data)
                gz.write(ByteArray((512 - data.size % 512) % 512))
            }
            gz.write(ByteArray(1024))
        }
        return out.toByteArray()
    }

    @Test
    fun usesBundledPackages() {
        val bundled = greetPackage().map { (path, text) -> "packages/preview/greet/0.1.0/$path" to text }
        // No cache and no fetcher: bundled packages work offline.
        val document = open(main, *bundled.toTypedArray(), cache = null).use { it.compile(300f, 12f) }
        assertEquals("HELLO WORLD", text(document))
    }

    @Test
    fun downloadsMissingPackagesOnceAndCachesThem() {
        val fetched = mutableListOf<String>()
        val fetcher = PackageFetcher { spec -> fetched += spec; tarGz(greetPackage()) }
        assertEquals("HELLO WORLD", text(open(main).use { it.compile(300f, 12f, fetcher) }))
        assertEquals(listOf("@preview/greet:0.1.0"), fetched)

        // Another bundle finds it in the cache, without any fetcher.
        assertEquals("HELLO WORLD", text(open(main).use { it.compile(300f, 12f) }))
    }

    @Test
    fun downloadsPackagesThatPackagesImport() {
        val fetched = mutableListOf<String>()
        val fetcher = PackageFetcher { spec ->
            fetched += spec
            when (spec) {
                "@preview/greet:0.1.0" -> tarGz(greetPackage(extraImport = "#import \"@preview/punct:0.1.0\": bang\n"))
                "@preview/punct:0.1.0" -> tarGz(
                    mapOf(
                        "typst.toml" to "[package]\nname = \"punct\"\nversion = \"0.1.0\"\nentrypoint = \"lib.typ\"\n",
                        "lib.typ" to "#let bang = [!]\n",
                    )
                )
                else -> throw IOException("unexpected $spec")
            }
        }
        val document = open("main.typ" to "#import \"@preview/greet:0.1.0\": hello\n#hello(\"World\")").use {
            it.compile(300f, 12f, fetcher)
        }
        assertEquals("HELLO WORLD", text(document))
        assertEquals(listOf("@preview/greet:0.1.0", "@preview/punct:0.1.0"), fetched)
    }

    @Test
    fun reportsDownloadFailures() {
        val e = assertThrows(TypstCompileException::class.java) {
            open(main).use { it.compile(300f, 12f) { throw IOException("offline") } }
        }
        assertEquals("Couldn't download @preview/greet:0.1.0: offline", e.message)
    }

    @Test
    fun reportsUnavailablePackagesWithoutCache() {
        val e = assertThrows(TypstCompileException::class.java) {
            open(main, cache = null).use { it.compile(300f, 12f) }
        }
        assertTrue(e.message, e.message!!.contains("package not found"))
        assertTrue(e.message, e.message!!.contains("@preview/greet:0.1.0"))
    }

    @Test
    fun keepsArchivesInsideTheCache() {
        val escapee = File(cache.parentFile, "escaped-${System.nanoTime()}.typ")
        val archive = tarGz(greetPackage() + ("../../../../${escapee.name}" to "oops"))
        open(main).use { it.compile(300f, 12f) { archive } }
        assertFalse(escapee.exists())
    }
}
