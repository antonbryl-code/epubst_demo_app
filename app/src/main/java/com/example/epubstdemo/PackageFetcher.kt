package com.example.epubstdemo

import java.io.ByteArrayOutputStream
import java.io.FileNotFoundException
import java.io.IOException
import java.net.HttpURLConnection
import java.net.URL

/** Supplies Typst packages that are neither bundled nor cached; see [TypstBundle.compile]. */
fun interface PackageFetcher {
    /**
     * Returns the `.tar.gz` archive of the package [spec], e.g. `@preview/cetz:0.3.4`.
     * @throws IOException if it can't be had.
     */
    fun fetch(spec: String): ByteArray
}

/** Downloads `@preview` packages from Typst Universe. Blocks, so call it off the main thread. */
object TypstUniverse : PackageFetcher {
    private const val BASE_URL = "https://packages.typst.org"
    private const val MAX_ARCHIVE_SIZE = 32 * 1024 * 1024
    private val SPEC = Regex("""@preview/([a-z0-9][a-z0-9-]*):(\d+\.\d+\.\d+)""")

    override fun fetch(spec: String): ByteArray {
        val (name, version) = SPEC.matchEntire(spec)?.destructured
            ?: throw IOException("Only @preview packages can be downloaded, not $spec")
        val connection = URL("$BASE_URL/preview/$name-$version.tar.gz").openConnection() as HttpURLConnection
        try {
            connection.connectTimeout = 15_000
            connection.readTimeout = 30_000
            connection.setRequestProperty("User-Agent", "EPUBst-Demo")
            when (val code = connection.responseCode) {
                HttpURLConnection.HTTP_OK -> return connection.inputStream.use { input ->
                    val out = ByteArrayOutputStream()
                    val buffer = ByteArray(64 * 1024)
                    while (true) {
                        val n = input.read(buffer)
                        if (n < 0) break
                        if (out.size() + n > MAX_ARCHIVE_SIZE) throw IOException("$spec is larger than 32 MB")
                        out.write(buffer, 0, n)
                    }
                    out.toByteArray()
                }
                HttpURLConnection.HTTP_NOT_FOUND -> throw FileNotFoundException("$spec doesn't exist on Typst Universe")
                else -> throw IOException("Typst Universe answered HTTP $code for $spec")
            }
        } finally {
            connection.disconnect()
        }
    }
}
