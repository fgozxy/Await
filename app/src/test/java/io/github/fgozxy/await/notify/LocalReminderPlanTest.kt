package io.github.fgozxy.await.notify

import io.github.fgozxy.await.data.Event
import org.junit.Assert.*
import org.junit.Test
import java.time.LocalDate
import java.time.LocalDateTime
import java.time.ZoneId

class LocalReminderPlanTest {
    private val zone = ZoneId.of("Asia/Shanghai")
    private val event = Event(id = 42, title = "生日", dateEpochDay = LocalDate.of(2026, 10, 8).toEpochDay(),
        remindDaysBefore = listOf(0, 1), remindHour = 9, remindMinute = 30)
    private fun millis(time: String) = LocalDateTime.parse(time).atZone(zone).toInstant().toEpochMilli()

    @Test
    fun advanceReminderThenDayOfReminderRemainScheduled() {
        val first = LocalReminderPlan.build(listOf(event), emptyList(), millis("2026-10-07T08:00"), zone)
        assertEquals(millis("2026-10-07T09:30"), first.single().due)
        val next = LocalReminderPlan.build(listOf(event), emptyList(), millis("2026-10-07T09:31"), zone)
        assertEquals(millis("2026-10-08T09:30"), next.single().due)
    }

    @Test
    fun restartPreservesRecentOverdueAndNextReminder() {
        val pending = LocalReminderPlan.build(listOf(event), emptyList(), millis("2026-10-07T08:00"), zone)
        val restored = LocalReminderPlan.build(listOf(event), pending, millis("2026-10-07T10:00"), zone)
        assertEquals(listOf(millis("2026-10-07T09:30"), millis("2026-10-08T09:30")), restored.map { it.due })
    }

    @Test
    fun changedDeletedOrDisabledEventsDoNotKeepStaleOverdueReminders() {
        val pending = LocalReminderPlan.build(listOf(event), emptyList(), millis("2026-10-07T08:00"), zone)
        val now = millis("2026-10-07T10:00")
        for (events in listOf(emptyList(), listOf(event.copy(remindDaysBefore = emptyList())),
            listOf(event.copy(title = "改名")), listOf(event.copy(remindHour = 12)))) {
            val plan = LocalReminderPlan.build(events, pending, now, zone)
            assertTrue(plan.none { it.due < now })
        }
    }

    @Test
    fun pinningAndChangingDisplayGroupDoNotInvalidatePendingReminders() {
        assertEquals(LocalReminderPlan.fingerprint(event, zone),
            LocalReminderPlan.fingerprint(event.copy(pinned = true, groupName = "生日"), zone))
    }

    @Test
    fun timezoneChangesAndOverdueBeyond24HoursDiscardOldPlan() {
        val pending = LocalReminderPlan.build(listOf(event), emptyList(), millis("2026-10-07T08:00"), zone)
        val now = millis("2026-10-08T10:00")
        assertTrue(LocalReminderPlan.build(listOf(event), pending, now, zone).isEmpty())
        assertTrue(LocalReminderPlan.build(listOf(event), pending, millis("2026-10-07T10:00"),
            ZoneId.of("America/Phoenix")).none { it in pending })
    }

    @Test
    fun monthlyRemindersKeepMonthEndAnchorAndMultipleEventsShareDueTime() {
        val repeat = event.copy(dateEpochDay = LocalDate.of(2026, 1, 31).toEpochDay(),
            repeatSpec = "MONTH:1", remindDaysBefore = listOf(0))
        val plans = LocalReminderPlan.build(listOf(repeat, repeat.copy(id = 43)), emptyList(),
            millis("2026-03-01T08:00"), zone)
        assertEquals(2, plans.size)
        assertEquals(setOf(millis("2026-03-31T09:30")), plans.map { it.due }.toSet())
    }

    @Test
    fun eventsAtTheSameInstantHaveSeparateStableNotificationTags() {
        val entries = listOf(PlannedReminder(1, 1000, "a"), PlannedReminder(2, 1000, "b"),
            PlannedReminder(3, 1000, "c"), PlannedReminder(2, 2000, "d"))
        assertEquals(4, entries.map(LocalReminderPlan::notificationTag).toSet().size)
        assertEquals("reminder:1000:1", LocalReminderPlan.notificationTag(entries.first()))
        assertEquals(LocalReminderPlan.notificationTag(entries[1]),
            LocalReminderPlan.notificationTag(entries[1].copy(fingerprint = "updated")))
    }

    @Test
    fun deniedPermissionKeepsOverdueWithoutSchedulingAnImmediateRetryLoop() {
        val now = millis("2026-10-07T10:00")
        val missed = PlannedReminder(42, now - 60_000, "missed")
        val next = PlannedReminder(42, now + 86_400_000, "next")
        assertEquals(next.due, LocalReminderPlan.nextAlarm(listOf(missed, next), now, false))
        assertNull(LocalReminderPlan.nextAlarm(listOf(missed), now, false))
        assertEquals(now + 1000, LocalReminderPlan.nextAlarm(listOf(missed, next), now, true))
    }

    @Test
    fun allSevenSelectionsMapOnlyCloudChannelsToServer() {
        val entries = NotificationChannel.entries
        for (mask in 1..7) {
            val selected = entries.filterIndexed { index, _ -> mask and (1 shl index) != 0 }.toSet()
            val remote = NotificationChannels.remote(selected)
            assertEquals(selected.count { it != NotificationChannel.LOCAL }, remote.size)
            assertFalse("local" in remote)
            assertEquals(NotificationChannel.TELEGRAM in selected, "telegram" in remote)
            assertEquals(NotificationChannel.NTFY in selected, "ntfy" in remote)
        }
    }
}
