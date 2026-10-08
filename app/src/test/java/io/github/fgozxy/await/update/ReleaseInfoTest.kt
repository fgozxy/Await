package io.github.fgozxy.await.update

import com.google.gson.JsonObject
import com.google.gson.JsonParser
import org.junit.Assert.*
import org.junit.Test

class ReleaseInfoTest {
    private fun metadata(): JsonObject = JsonParser.parseString("""{
        "tag_name":"v2.4.3", "draft":false, "prerelease":false, "body":"修复提醒",
        "assets":[
            {"name":"Await-v2.4.3.apk","state":"uploaded","size":1234,
             "browser_download_url":"https://github.com/fgozxy/Await/releases/download/v2.4.3/Await-v2.4.3.apk",
             "digest":"sha256:aaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaa"},
            {"name":"SHA256SUMS.txt","state":"uploaded","size":83,
             "browser_download_url":"https://github.com/fgozxy/Await/releases/download/v2.4.3/SHA256SUMS.txt"}
        ]} """).asJsonObject

    @Test
    fun numericVersionComparisonHandlesMultiDigitPartsAndNeverDowngrades() {
        assertTrue(ReleaseInfo.isNewer("2.10.0", "2.9.9"))
        assertTrue(ReleaseInfo.isNewer("3.0.0", "2.99.99"))
        assertFalse(ReleaseInfo.isNewer("2.4.2", "2.4.2"))
        assertFalse(ReleaseInfo.isNewer("2.4.1", "2.4.2"))
        assertThrows(IllegalArgumentException::class.java) { ReleaseInfo.isNewer("2.4.3-beta", "2.4.2") }
    }

    @Test
    fun publishedReleaseSelectsExactApkAndChecksumAsset() {
        val info = ReleaseInfo.parse(metadata())
        assertEquals("2.4.3", info.versionName)
        assertEquals("Await-v2.4.3.apk", info.apkName)
        assertEquals("修复提醒", info.notes)
        assertEquals(1234L, info.sizeBytes)
        assertEquals("a".repeat(64), info.assetDigest)
    }

    @Test
    fun unpublishedAndPreviewReleasesAreRejected() {
        for (key in listOf("draft", "prerelease")) {
            val json = metadata().apply { addProperty(key, true) }
            assertThrows(IllegalArgumentException::class.java) { ReleaseInfo.parse(json) }
        }
    }

    @Test
    fun missingChecksumOrIncorrectApkNameIsRejected() {
        val missing = metadata().apply { getAsJsonArray("assets").remove(1) }
        assertThrows(IllegalStateException::class.java) { ReleaseInfo.parse(missing) }
        val wrong = metadata().apply { getAsJsonArray("assets")[0].asJsonObject.addProperty("name", "Await-v2.4.1.apk") }
        assertThrows(IllegalStateException::class.java) { ReleaseInfo.parse(wrong) }
    }

    @Test
    fun foreignRepoOrInsecureDownloadLinksAreRejected() {
        for (url in listOf("http://github.com/fgozxy/Await/releases/download/v2.4.3/Await-v2.4.3.apk",
            "https://github.com/other/Await/releases/download/v2.4.3/Await-v2.4.3.apk")) {
            val json = metadata().apply { getAsJsonArray("assets")[0].asJsonObject.addProperty("browser_download_url", url) }
            assertThrows(IllegalArgumentException::class.java) { ReleaseInfo.parse(json) }
        }
    }

    @Test
    fun missingDigestIsSupportedButMalformedDigestAndOversizedFilesAreRejected() {
        val older = metadata().apply { getAsJsonArray("assets")[0].asJsonObject.remove("digest") }
        assertNull(ReleaseInfo.parse(older).assetDigest)
        val corrupt = metadata().apply { getAsJsonArray("assets")[0].asJsonObject.addProperty("digest", "sha256:invalid") }
        assertThrows(IllegalArgumentException::class.java) { ReleaseInfo.parse(corrupt) }
        val oversized = metadata().apply { getAsJsonArray("assets")[0].asJsonObject.addProperty("size", ReleaseInfo.MAX_APK_BYTES + 1) }
        assertThrows(IllegalArgumentException::class.java) { ReleaseInfo.parse(oversized) }
    }

    @Test
    fun redirectTrustRejectsCredentialsForeignHostsAndHttp() {
        assertTrue(ReleaseInfo.trustedDownloadUrl("https://release-assets.githubusercontent.com/file?signature=example"))
        for (url in listOf("http://github.com/file", "https://github.com.evil.example/file",
            "https://user:password@github.com/file", "https://github.com:8080/file", "file:///tmp/file")) {
            assertFalse(url, ReleaseInfo.trustedDownloadUrl(url))
        }
    }
}
