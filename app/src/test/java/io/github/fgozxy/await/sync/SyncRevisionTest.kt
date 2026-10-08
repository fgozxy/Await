package io.github.fgozxy.await.sync

import org.junit.Assert.*
import org.junit.Test

class SyncRevisionTest {
    @Test
    fun repeatedBackgroundChecksKeepTheSameRevision() {
        val fingerprint = SyncRevision.fingerprint("unchanged payload")
        var revision = 7L
        repeat(100) {
            revision = SyncRevision.resolve(revision, 7, fingerprint, fingerprint)
            assertEquals(7L, revision)
        }
    }

    @Test
    fun pendingEditsAndRetriesKeepTheirAlreadyAllocatedRevision() {
        assertEquals(8L, SyncRevision.resolve(8, 7, "old", "new", previousRevision = 7))
        assertEquals(8L, SyncRevision.resolve(8, 7, "new", "new"))
        assertEquals(9L, SyncRevision.resolve(9, 7, "new", "newer", previousRevision = 8))
    }

    @Test
    fun changedPayloadAfterUpgradeOrTimezoneChangeAdvancesPastTheSyncedCopy() {
        assertEquals(8L, SyncRevision.resolve(7, 7, "old", "changed"))
        assertEquals(8L, SyncRevision.resolve(7, 7, null, "legacy payload"))
        assertEquals(8L, SyncRevision.resolve(7, 6, null, "legacy pending payload"))
    }

    @Test
    fun upgradeAfterLostAcknowledgementDoesNotReuseTheAcceptedRevisionForNewContent() {
        // The server may have accepted revision 8 even though its response never reached the phone.
        assertEquals(8L, SyncRevision.resolve(8, 7, "old", "old", previousRevision = 8))
        assertEquals(9L, SyncRevision.resolve(8, 7, "old", "upgraded payload", previousRevision = 8))
    }

    @Test
    fun newInstallUsesAValidFirstRevision() {
        assertEquals(1L, SyncRevision.resolve(0, 0, null, "new"))
    }

    @Test
    fun fingerprintTracksEffectiveCloudReminderSettings() {
        fun payload(timezone: String = "Asia/Shanghai", channels: List<String> = listOf("ntfy")) =
            SyncPayload.json("client", 0, timezone, emptyList(), channels)
        val base = SyncRevision.fingerprint(payload())
        assertEquals(base, SyncRevision.fingerprint(payload(channels = listOf("ntfy", "ntfy"))))
        assertNotEquals(base, SyncRevision.fingerprint(payload(timezone = "America/Phoenix")))
        assertNotEquals(base, SyncRevision.fingerprint(payload(channels = listOf("telegram"))))
    }
}
