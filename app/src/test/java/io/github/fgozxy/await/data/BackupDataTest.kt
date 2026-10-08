package io.github.fgozxy.await.data

import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test

class BackupDataTest {

    @Test
    fun malformedOptionalFieldsAreSanitized() {
        val json = """[
            {
              "title":"异常字段",
              "date":"2026-10-01",
              "note":null,
              "colorIndex":-1,
              "remindDaysBefore":null,
              "remindHour":99,
              "remindMinute":-2
            }
        ]""".trimIndent()

        val event = BackupData.parse(json).getOrThrow().single()

        assertEquals("", event.note)
        assertEquals(0, event.colorIndex)
        assertEquals(listOf(1), event.remindDaysBefore)
        assertEquals(9, event.remindHour)
        assertEquals(0, event.remindMinute)
    }

    @Test
    fun backupEnvelopeRestoresExplicitGroups() {
        val json = """{
          "app":"Await",
          "formatVersion":1,
          "events":[
            {"id":1,"title":"一条日程","date":"2026-10-01","remindDaysBefore":[0]},
            {"id":2,"title":"另一条日程","date":"2026-10-01","remindDaysBefore":[0]}
          ],
          "groups":[" 空分组 ","工作","工作",null],
          "mergeGroups":[{"id":10,"eventIds":[1,2]}]
        }""".trimIndent()

        val bundle = BackupData.parseBundle(json).getOrThrow()

        assertEquals(listOf("空分组", "工作"), bundle.groups)
        assertEquals(2, bundle.events.size)
    }

    @Test
    fun oldBareArrayHasNoGroupMetadata() {
        val bundle = BackupData.parseBundle(
            """[{"title":"旧备份","date":"2026-10-01"}]"""
        ).getOrThrow()

        assertNull(bundle.groups)
    }

    @Test
    fun legacyAlarmAndMergeSettingsAreIgnoredWhenRestoringEvents() {
        val bundle = BackupData.parseBundle(
            """{
              "events":[
                {"id":1,"title":"普通通知","date":"2026-10-01","remindDaysBefore":[0],"alarmMode":false},
                {"id":2,"title":"闹钟提醒","date":"2026-10-01","remindDaysBefore":[0],"alarmMode":true}
              ],
              "mergeGroups":[{"id":10,"eventIds":[1,2]}]
            }""".trimIndent()
        ).getOrThrow()

        assertEquals(listOf("普通通知", "闹钟提醒"), bundle.events.map { it.title })
        assertEquals(2, bundle.events.size)
    }

    @Test
    fun obsoleteMergeMetadataCannotPreventRestoringCalendar() {
        for (obsolete in listOf("null", "{\"broken\":true}", "[{\"eventIds\":[999]}]")) {
            val bundle = BackupData.parseBundle("""{"app":"Await","formatVersion":1,"events":[{"title":"保留日程","date":"2026-10-08"}],"groups":["空分组"],"mergeGroups":$obsolete}""").getOrThrow()
            assertEquals("保留日程", bundle.events.single().title)
            assertEquals(listOf("空分组"), bundle.groups)
        }
    }

    @Test
    fun backupWithOnlyEmptyGroupsIsValid() {
        val bundle = BackupData.parseBundle(
            """{"app":"Await","formatVersion":1,"events":[],"groups":["稍后再用"]}"""
        ).getOrThrow()

        assertEquals(emptyList<Event>(), bundle.events)
        assertEquals(listOf("稍后再用"), bundle.groups)
    }

    @Test
    fun fullCloudExportRoundtripPreservesScheduleAndDisplayFields() {
        val events = listOf(
            Event(id = 1, title = "生日", dateEpochDay = java.time.LocalDate.of(2026, 10, 7).toEpochDay(),
                note = "保留备注", pinned = true, colorIndex = 3, groupName = "朋友",
                repeatSpec = "YEAR:1", remindDaysBefore = listOf(0, 1, 7), remindHour = 8, remindMinute = 30),
            Event(id = 2, title = "纪念日", dateEpochDay = java.time.LocalDate.of(2026, 10, 7).toEpochDay(),
                remindDaysBefore = listOf(0, 1, 7), remindHour = 8, remindMinute = 30)
        )
        val payload = BackupData.Payload(events = events, eventCount = events.size,
            groups = listOf("朋友", "空分组"))
        val json = BackupData.encodePayload(payload)
        val restored = BackupData.parseBundle(json).getOrThrow()
        assertEquals(events, restored.events)
        assertEquals(payload.groups, restored.groups)
        assertEquals(0, com.google.gson.JsonParser.parseString(json).asJsonObject["mergeGroups"].asJsonArray.size())
        val fields = com.google.gson.JsonParser.parseString(json).asJsonObject.keySet()
        assertEquals(setOf("app", "formatVersion", "exportedAt", "appVersion", "eventCount", "events", "groups", "mergeGroups"), fields)
    }
}
