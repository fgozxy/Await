package io.github.fgozxy.await.update

import org.junit.Assert.*
import org.junit.Test
import java.io.ByteArrayInputStream
import java.io.ByteArrayOutputStream
import java.security.MessageDigest
import java.util.concurrent.CancellationException

class UpdateIntegrityTest {
    private val bytes = ByteArray(180_000) { (it % 253).toByte() }
    private fun hash(value: ByteArray) = MessageDigest.getInstance("SHA-256").digest(value).joinToString("") { "%02x".format(it) }

    @Test
    fun checksumSelectsExactFilenameAndSupportsBinaryManifestFormat() {
        assertEquals(hash(bytes), UpdateIntegrity.checksum("${"b".repeat(64)}  other.apk\n${hash(bytes)} *Await-v2.4.3.apk\n", "Await-v2.4.3.apk"))
    }

    @Test
    fun missingAndDuplicateChecksumEntriesAreRejected() {
        assertThrows(IllegalArgumentException::class.java) { UpdateIntegrity.checksum("${hash(bytes)}  other.apk", "Await-v2.4.3.apk") }
        val entry = "${hash(bytes)}  Await-v2.4.3.apk\n"
        assertThrows(IllegalArgumentException::class.java) { UpdateIntegrity.checksum(entry + entry, "Await-v2.4.3.apk") }
    }

    @Test
    fun fullDownloadPreservesBytesAndReportsBoundedProgress() {
        val output = ByteArrayOutputStream()
        val progress = mutableListOf<Int>()
        UpdateIntegrity.copyVerified(ByteArrayInputStream(bytes), output, bytes.size.toLong(), hash(bytes), { progress += it })
        assertArrayEquals(bytes, output.toByteArray())
        assertTrue(progress.all { it in 0..99 })
        assertEquals(progress.sorted(), progress)
    }

    @Test
    fun truncatedAndOversizedDownloadsAreRejected() {
        for (size in listOf(bytes.size - 1L, bytes.size + 1L)) {
            assertThrows(IllegalArgumentException::class.java) {
                UpdateIntegrity.copyVerified(ByteArrayInputStream(bytes), ByteArrayOutputStream(), size, hash(bytes), {})
            }
        }
    }

    @Test
    fun tamperedDownloadIsRejectedDespiteMatchingFileSize() {
        val changed = bytes.copyOf().apply { this[100] = (this[100] + 1).toByte() }
        assertThrows(IllegalArgumentException::class.java) {
            UpdateIntegrity.copyVerified(ByteArrayInputStream(changed), ByteArrayOutputStream(), bytes.size.toLong(), hash(bytes), {})
        }
    }

    @Test
    fun cancellationStopsBeforeWritingAnyMoreBytes() {
        val output = ByteArrayOutputStream()
        assertThrows(CancellationException::class.java) {
            UpdateIntegrity.copyVerified(ByteArrayInputStream(bytes), output, bytes.size.toLong(), hash(bytes), {}) { throw CancellationException() }
        }
        assertEquals(0, output.size())
    }
}
