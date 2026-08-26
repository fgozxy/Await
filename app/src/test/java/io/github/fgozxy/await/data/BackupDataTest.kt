package io.github.fgozxy.await.data

import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
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
          "events":[{"title":"一条日程","date":"2026-10-01"}],
          "groups":[" 空分组 ","工作","工作",null]
        }""".trimIndent()

        val bundle = BackupData.parseBundle(json).getOrThrow()

        assertEquals(listOf("空分组", "工作"), bundle.groups)
        assertEquals(1, bundle.events.size)
    }

    @Test
    fun oldBareArrayHasNoGroupMetadata() {
        val bundle = BackupData.parseBundle(
            """[{"title":"旧备份","date":"2026-10-01"}]"""
        ).getOrThrow()

        assertNull(bundle.groups)
    }

    @Test
    fun backupWithOnlyEmptyGroupsIsValid() {
        val bundle = BackupData.parseBundle(
            """{"app":"Await","formatVersion":1,"events":[],"groups":["稍后再用"]}"""
        ).getOrThrow()

        assertEquals(emptyList<Event>(), bundle.events)
        assertEquals(listOf("稍后再用"), bundle.groups)
    }
}
