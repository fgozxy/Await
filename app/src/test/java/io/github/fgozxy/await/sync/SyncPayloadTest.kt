package io.github.fgozxy.await.sync

import com.google.gson.JsonParser
import io.github.fgozxy.await.data.Event
import org.junit.Assert.*
import org.junit.Test
import java.time.LocalDate

class SyncPayloadTest {
    @Test
    fun legacyRepeatsAreNormalizedForServerAndOnlyScheduleFieldsAreSent() {
        val event = Event(id = 7, title = "生日", dateEpochDay = LocalDate.of(2024, 2, 29).toEpochDay(),
            repeatYearly = true, remindDaysBefore = listOf(0, 7), groupName = "私有分组")
        val json = SyncPayload.json("test-phone", 5, "Asia/Shanghai", listOf(event))
        val body = JsonParser.parseString(json).asJsonObject
        assertEquals(5L, body["revision"].asLong)
        assertEquals("Asia/Shanghai", body["timezone"].asString)
        val remote = body["events"].asJsonArray[0].asJsonObject
        assertEquals("2024-02-29", remote["date"].asString)
        assertEquals("YEAR", remote["cycle"].asString)
        assertEquals(1, remote["repeatN"].asInt)
        assertFalse(remote.has("groupName"))
        assertFalse(remote.has("alarmMode"))
        assertFalse(json.contains("apiKey"))
    }

    @Test
    fun emptyRemindersRemainDisabledAndLegacyMergeSettingsAreCleared() {
        val event = Event(id = 1, title = "不提醒", remindDaysBefore = emptyList())
        val body = JsonParser.parseString(SyncPayload.json("test-phone", 1, "UTC",
            listOf(event))).asJsonObject
        assertEquals(0, body["events"].asJsonArray[0].asJsonObject["remindDaysBefore"].asJsonArray.size())
        assertEquals(0, body["mergeGroups"].asJsonArray.size())
    }

    @Test
    fun localOnlyDisablesServerChannelsAndMultipleChannelsAreExplicit() {
        val local = JsonParser.parseString(SyncPayload.json("test-phone", 1, "UTC",
            emptyList(), emptyList())).asJsonObject
        assertEquals(0, local["notificationChannels"].asJsonArray.size())
        val both = JsonParser.parseString(SyncPayload.json("test-phone", 2, "UTC",
            emptyList(), listOf("telegram", "ntfy"))).asJsonObject
        assertEquals(listOf("ntfy", "telegram"), both["notificationChannels"].asJsonArray.map { it.asString })
    }

    @Test
    fun serverUrlRequiresHttpsAndCannotCarryCredentialsOrQuery() {
        assertTrue(SyncSettings.validUrl("https://await.example.com"))
        assertTrue(SyncSettings.validUrl("https://example.com/await"))
        assertFalse(SyncSettings.validUrl("http://await.example.com"))
        assertFalse(SyncSettings.validUrl("https://secret@example.com"))
        assertFalse(SyncSettings.validUrl("https://example.com?key=secret"))
        assertFalse(SyncSettings.validUrl("https://example.com#fragment"))
    }
}
