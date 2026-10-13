package com.example.epubstdemo

import org.w3c.dom.Element
import java.io.ByteArrayInputStream
import java.io.ByteArrayOutputStream
import java.io.IOException
import java.io.InputStream
import java.util.zip.ZipInputStream
import javax.xml.parsers.DocumentBuilderFactory

class EpubstFormatException(message: String) : IOException(message)

/** Every file of a bundle keyed by its path inside the zip, plus the rootfile to compile. */
class EpubstContents(val rootfile: String, val files: Map<String, ByteArray>)

/**
 * Reader for .epubst bundles: zip archives laid out like EPUB, with a
 * META-INF/container.xml pointing at the root Typst file.
 */
object EpubstBundle {
    const val CONTAINER_PATH = "META-INF/container.xml"

    /** Bundles are untrusted, so cap the total uncompressed size to guard against zip bombs. */
    const val MAX_TOTAL_SIZE = 256L * 1024 * 1024

    /** Reads all files of the bundle and resolves the rootfile from META-INF/container.xml. */
    fun read(input: InputStream, maxTotalSize: Long = MAX_TOTAL_SIZE): EpubstContents {
        val files = readAllEntries(input, maxTotalSize)
        val containerXml = files[CONTAINER_PATH]
            ?: throw EpubstFormatException("$CONTAINER_PATH not found in bundle")
        val rootfile = parseRootfilePath(containerXml)
        if (rootfile !in files) throw EpubstFormatException("Rootfile $rootfile not found in bundle")
        return EpubstContents(rootfile, files)
    }

    private fun readAllEntries(input: InputStream, maxTotalSize: Long): Map<String, ByteArray> {
        val files = LinkedHashMap<String, ByteArray>()
        var remaining = maxTotalSize
        val buffer = ByteArray(64 * 1024)
        ZipInputStream(input).use { zip ->
            while (true) {
                val entry = zip.nextEntry ?: break
                if (entry.isDirectory) continue
                // Copy in chunks rather than readBytes() so an oversized entry fails before exhausting memory.
                val out = ByteArrayOutputStream()
                while (true) {
                    val n = zip.read(buffer)
                    if (n < 0) break
                    remaining -= n
                    if (remaining < 0) {
                        throw EpubstFormatException("Bundle exceeds ${maxTotalSize / (1024 * 1024)} MB uncompressed")
                    }
                    out.write(buffer, 0, n)
                }
                if (files.put(entry.name, out.toByteArray()) != null) {
                    throw EpubstFormatException("Duplicate entry ${entry.name} in bundle")
                }
            }
        }
        return files
    }

    internal fun parseRootfilePath(containerXml: ByteArray): String {
        val factory = DocumentBuilderFactory.newInstance().apply {
            isNamespaceAware = true
            isExpandEntityReferences = false
        }
        val document = try {
            factory.newDocumentBuilder().parse(ByteArrayInputStream(containerXml))
        } catch (e: Exception) {
            throw EpubstFormatException("Malformed $CONTAINER_PATH: ${e.message}")
        }
        val rootfiles = document.getElementsByTagNameNS("*", "rootfile")
        for (i in 0 until rootfiles.length) {
            val path = (rootfiles.item(i) as Element).getAttribute("full-path")
            if (path.isNotBlank()) return path
        }
        throw EpubstFormatException("No rootfile with a full-path in $CONTAINER_PATH")
    }
}
