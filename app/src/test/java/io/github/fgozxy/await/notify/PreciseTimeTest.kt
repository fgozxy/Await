package io.github.fgozxy.await.notify

import com.google.gson.Gson
import com.google.gson.JsonParser
import io.github.fgozxy.await.data.Event
import io.github.fgozxy.await.sync.SyncPayload
import org.junit.Assert.*
import org.junit.Test
import java.time.LocalDate
import java.time.LocalDateTime
import java.time.LocalTime
import java.time.ZoneId

class PreciseTimeTest {
    private val zone = ZoneId.of("Asia/Shanghai")
    private val defaultTime = LocalTime.of(11, 45)
    private val inherited = Event(id = 1, title = "默认", dateEpochDay = LocalDate.of(2026, 10, 8).toEpochDay(),
        remindDaysBefore = listOf(0, 1), remindHour = 14, remindMinute = 25)
    private val precise = inherited.copy(id = 2, title = "精准", preciseTime = true)
    private fun millis(time: String) = LocalDateTime.parse(time).atZone(zone).toInstant().toEpochMilli()

    @Test
    fun oldStoredEventsAndNewEventsStartWithPrecisionOff() {
        val old = Gson().fromJson("""{"id":1,"title":"旧日程","remindHour":14,"remindMinute":25}""", Event::class.java)
        assertFalse(old.preciseTime)
        assertFalse(Event().preciseTime)
        assertEquals(LocalTime.of(9, 0), ReminderTime.timeOf(old))
        assertEquals(defaultTime, ReminderTime.timeOf(old, defaultTime))
    }

    @Test
    fun customTimeOnlyAppliesWhileEnabledAndSurvivesToggling() {
        assertEquals(LocalTime.of(14, 25), ReminderTime.timeOf(precise, defaultTime))
        val disabled = precise.copy(preciseTime = false)
        assertEquals(defaultTime, ReminderTime.timeOf(disabled, defaultTime))
        assertEquals(LocalTime.of(14, 25), ReminderTime.timeOf(disabled.copy(preciseTime = true), defaultTime))
    }

    @Test
    fun defaultChangeMovesOnlyInheritedRemindersAndInvalidatesTheirOldPlans() {
        val before = LocalReminderPlan.build(listOf(inherited, precise), emptyList(), millis("2026-10-07T08:00"), zone)
        val after = LocalReminderPlan.build(listOf(inherited, precise), before, millis("2026-10-07T10:00"), zone, defaultTime)
        assertEquals(millis("2026-10-07T11:45"), after.single { it.eventId == 1L }.due)
        assertEquals(before.single { it.eventId == 2L }, after.single { it.eventId == 2L })
        assertNotEquals(LocalReminderPlan.fingerprint(inherited, zone), LocalReminderPlan.fingerprint(inherited, zone, defaultTime))
        assertEquals(LocalReminderPlan.fingerprint(precise, zone), LocalReminderPlan.fingerprint(precise, zone, defaultTime))
    }

    @Test
    fun changingDefaultRetainsOverduePreciseReminderButDiscardsOldInheritedReminder() {
        val fixed = precise.copy(remindHour = 9, remindMinute = 30)
        val before = LocalReminderPlan.build(listOf(inherited, fixed), emptyList(), millis("2026-10-07T08:00"), zone)
        val now = millis("2026-10-07T10:00")
        val after = LocalReminderPlan.build(listOf(inherited, fixed), before, now, zone, defaultTime)
        assertEquals(listOf(2L), after.filter { it.due < now }.map { it.eventId })
    }

    @Test
    fun serverPayloadAndLocalPlansUseSameEffectiveTimeForEveryChannel() {
        val events = listOf(inherited, precise)
        val wire = JsonParser.parseString(SyncPayload.json("test-phone", 1, zone.id, events,
            listOf("telegram", "ntfy"), defaultTime)).asJsonObject["events"].asJsonArray
        val plans = LocalReminderPlan.build(events, emptyList(), millis("2026-10-07T08:00"), zone, defaultTime)
        wire.forEach { element ->
            val remote = element.asJsonObject
            val local = LocalDateTime.ofInstant(java.time.Instant.ofEpochMilli(plans.single {
                it.eventId == remote["id"].asLong }.due), zone)
            assertEquals(local.hour, remote["remindHour"].asInt)
            assertEquals(local.minute, remote["remindMinute"].asInt)
        }
    }

    @Test
    fun monthEndAndAdvanceRemindersUseDefaultTime() {
        val monthly = inherited.copy(dateEpochDay = LocalDate.of(2026, 1, 31).toEpochDay(), repeatSpec = "MONTH:1")
        assertEquals(LocalDateTime.parse("2026-03-30T11:45"), ReminderTime.nextTrigger(monthly,
            LocalDateTime.parse("2026-03-01T08:00"), defaultTime))
    }
}
