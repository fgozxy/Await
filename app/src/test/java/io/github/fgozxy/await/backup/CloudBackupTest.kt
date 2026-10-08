package io.github.fgozxy.await.backup

import com.google.gson.JsonParser
import org.junit.Assert.*
import org.junit.Test
import java.time.LocalDateTime
import java.time.ZoneId

class CloudBackupTest {
    private fun millis(date: LocalDateTime) = date.atZone(ZoneId.systemDefault()).toInstant().toEpochMilli()

    @Test
    fun historyUsesServerIdsAndReceptionTimesForRestore() {
        val result = BackupService.parseEntries(JsonParser.parseString("""{"backups":[
            {"id":"0123456789abcdef0123456789abcdef","name":"Await-backup-test.json","createdAt":1791388800123,"size":2048,"eventCount":2}
        ]}""").asJsonObject).single()
        assertEquals("0123456789abcdef0123456789abcdef", result.id)
        assertEquals(1791388800123, result.createdAt)
        assertEquals(2, result.eventCount)
        assertEquals(2048, result.size)
    }

    @Test(expected = IllegalArgumentException::class)
    fun historyRejectsIdsThatCouldEscapeBackupRoute() {
        BackupService.parseEntries(JsonParser.parseString("""{"backups":[
            {"id":"../channels/ntfy","name":"x","createdAt":1,"size":1,"eventCount":1}
        ]}""").asJsonObject)
    }

    @Test
    fun dailyBackupBeforeChosenHourRunsToday() {
        val now = LocalDateTime.of(2026, 10, 7, 12, 0)
        assertEquals(millis(now.toLocalDate().atTime(22, 0)), BackupScheduler.nextTriggerMillis(
            BackupSettings.Prefs(autoEnabled = true, hour = 22), now))
    }

    @Test
    fun dailyBackupAtChosenHourRunsNextDay() {
        val now = LocalDateTime.of(2026, 10, 7, 22, 0)
        assertEquals(millis(now.plusDays(1)), BackupScheduler.nextTriggerMillis(
            BackupSettings.Prefs(autoEnabled = true, hour = 22), now))
    }

    @Test
    fun weeklyBackupRespectsIntervalAfterManualUpload() {
        val last = LocalDateTime.of(2026, 10, 1, 23, 0)
        val next = BackupScheduler.nextTriggerMillis(BackupSettings.Prefs(autoEnabled = true,
            intervalDays = 7, hour = 22, lastBackupAt = millis(last)), LocalDateTime.of(2026, 10, 7, 12, 0))
        assertEquals(millis(LocalDateTime.of(2026, 10, 9, 22, 0)), next)
    }

    @Test
    fun disabledAutomaticBackupHasNoNextTime() {
        assertNull(BackupScheduler.nextTriggerText(BackupSettings.Prefs(autoEnabled = false)))
    }
}
