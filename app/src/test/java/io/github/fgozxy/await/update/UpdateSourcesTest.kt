package io.github.fgozxy.await.update

import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.runBlocking
import org.junit.Assert.*
import org.junit.Test
import java.io.ByteArrayInputStream
import java.io.ByteArrayOutputStream
import java.io.IOException
import java.security.MessageDigest

class UpdateSourcesTest {
    private val apk = "https://github.com/fgozxy/Await/releases/download/v2.4.3/Await-v2.4.3.apk"

    @Test
    fun checksChecksumsAndDownloadsPreferTheAccelerator() {
        for (url in listOf(ReleaseInfo.LATEST_API, apk, apk.substringBeforeLast('/') + "/SHA256SUMS.txt")) {
            val urls = UpdateSources.urls(url)
            assertEquals("https://hubproxy.zlibza.com/" + url.removePrefix("https://"), urls.first())
            assertEquals(url, urls.last())
            assertTrue(ReleaseInfo.trustedDownloadUrl(urls.first()))
        }
    }

    @Test
    fun proxyCannotBeUsedForForeignOrMalformedUpdateUrls() {
        for (url in listOf("http://github.com/fgozxy/Await/releases/download/v2.4.3/Await-v2.4.3.apk",
            apk.replace("fgozxy", "other"), apk.replace("Await-v2.4.3", "Await-v2.4.2"), apk + "?redirect=other")) {
            assertThrows(IllegalArgumentException::class.java) { UpdateSources.urls(url) }
        }
        for (url in listOf("https://hubproxy.zlibza.com.evil.example/github.com/file", "https://hubproxy.zlibza.com/http://github.com/file",
            "https://hubproxy.zlibza.com/github.com/other/Await/releases/download/v2.4.3/Await-v2.4.3.apk",
            "https://hubproxy.zlibza.com:8080/api.github.com/repos/fgozxy/Await/releases/latest")) {
            assertFalse(url, ReleaseInfo.trustedDownloadUrl(url))
        }
    }

    @Test
    fun successfulAcceleratorDoesNotContactGithub() {
        runBlocking {
            val visited = mutableListOf<String>()
            assertEquals("content", UpdateSources.withFallback(apk) { visited += it; "content" })
            assertEquals(listOf(UpdateSources.urls(apk).first()), visited)
        }
    }

    @Test
    fun unavailableAcceleratorFallsBackToGithub() {
        runBlocking {
            val visited = mutableListOf<String>()
            val result = UpdateSources.withFallback(apk) {
                visited += it
                if (visited.size == 1) throw IOException("accelerator unavailable")
                "direct content"
            }
            assertEquals("direct content", result)
            assertEquals(UpdateSources.urls(apk), visited)
        }
    }

    @Test
    fun invalidAcceleratedReleaseMetadataFallsBackToGithub() {
        runBlocking {
            var requests = 0
            val result = UpdateSources.withFallback(ReleaseInfo.LATEST_API) {
                if (++requests == 1) throw UpdateException("发布版本信息不完整")
                "valid release"
            }
            assertEquals("valid release", result)
            assertEquals(2, requests)
        }
    }

    @Test
    fun failedChecksumRetriesFromTheBeginningUsingDirectContent() {
        runBlocking {
            val original = "valid APK bytes".toByteArray()
            val damaged = "wrong APK bytes".toByteArray()
            val hash = MessageDigest.getInstance("SHA-256").digest(original).joinToString("") { "%02x".format(it) }
            val output = ByteArrayOutputStream()
            var requests = 0
            UpdateSources.withFallback(apk) {
                output.reset()
                val bytes = if (++requests == 1) damaged else original
                UpdateIntegrity.copyVerified(ByteArrayInputStream(bytes), output, original.size.toLong(), hash, {})
            }
            assertEquals(2, requests)
            assertArrayEquals(original, output.toByteArray())
        }
    }

    @Test
    fun cancellationDoesNotStartAFallbackDownload() {
        var requests = 0
        assertThrows(CancellationException::class.java) {
            runBlocking { UpdateSources.withFallback(apk) { requests++; throw CancellationException() } }
        }
        assertEquals(1, requests)
    }

    @Test
    fun bothUnavailableSourcesReturnTheLastFailure() {
        val failure = IOException("direct source unavailable")
        var requests = 0
        val actual = assertThrows(IOException::class.java) {
            runBlocking { UpdateSources.withFallback(apk) {
                if (++requests == 1) throw IOException("accelerator unavailable")
                throw failure
            } }
        }
        assertSame(failure, actual)
        assertEquals(2, requests)
    }
}
