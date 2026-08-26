package io.github.fgozxy.await.data

import io.github.fgozxy.await.notify.AlarmScheduler
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Test
import java.time.LocalDate
import java.time.LocalDateTime

class EventTest {

    @Test
    fun monthlyRepeatKeepsOriginalDayAsAnchor() {
        val event = Event(
            title = "月末",
            dateEpochDay = LocalDate.of(2026, 1, 31).toEpochDay(),
            repeatSpec = "MONTH:1"
        )

        assertEquals(
            LocalDate.of(2026, 3, 31),
            event.occurrenceOnOrAfter(LocalDate.of(2026, 3, 1))
        )
    }

    @Test
    fun yearlyRepeatReturnsToLeapDay() {
        val event = Event(
            title = "闰日",
            dateEpochDay = LocalDate.of(2024, 2, 29).toEpochDay(),
            repeatSpec = "YEAR:1",
            remindDaysBefore = listOf(0)
        )

        assertEquals(
            LocalDateTime.of(2028, 2, 29, 9, 0),
            AlarmScheduler.nextTrigger(event, LocalDateTime.of(2027, 3, 1, 0, 0))
        )
    }

    @Test
    fun oldDailyRepeatHasFutureOccurrenceWithoutScanLimit() {
        val event = Event(
            title = "每日",
            dateEpochDay = LocalDate.of(2000, 1, 1).toEpochDay(),
            repeatSpec = "DAY:1",
            remindDaysBefore = listOf(0)
        )
        val from = LocalDateTime.of(2026, 8, 26, 1, 0)

        assertEquals(LocalDateTime.of(2026, 8, 26, 9, 0), AlarmScheduler.nextTrigger(event, from))
    }

    @Test
    fun multipleReminderOffsetsAreScheduledInChronologicalOrder() {
        val event = Event(
            title = "多档提醒",
            dateEpochDay = LocalDate.of(2026, 9, 10).toEpochDay(),
            remindDaysBefore = listOf(1, 7),
            remindHour = 9
        )

        assertEquals(
            LocalDateTime.of(2026, 9, 3, 9, 0),
            AlarmScheduler.nextTrigger(event, LocalDateTime.of(2026, 9, 2, 10, 0))
        )
        assertEquals(
            LocalDateTime.of(2026, 9, 9, 9, 0),
            AlarmScheduler.nextTrigger(event, LocalDateTime.of(2026, 9, 3, 10, 0))
        )
    }

    @Test
    fun nonRepeatingPastEventHasNoFutureTrigger() {
        val event = Event(
            title = "过去",
            dateEpochDay = LocalDate.of(2020, 1, 1).toEpochDay(),
            remindDaysBefore = listOf(0)
        )

        assertNull(AlarmScheduler.nextTrigger(event, LocalDateTime.of(2026, 1, 1, 0, 0)))
    }
}
