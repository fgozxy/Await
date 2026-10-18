package io.github.fgozxy.await.data

import com.google.gson.Gson
import io.github.fgozxy.await.notify.ReminderTime
import io.github.fgozxy.await.sync.SyncPayload
import org.junit.Assert.*
import org.junit.Test
import java.time.LocalDate
import java.time.LocalDateTime

class LunarDatesTest {
    private fun event(year: Int, month: Int, day: Int, repeat: String? = null) = Event(
        title = "农历生日", calendarType = "LUNAR",
        dateEpochDay = LunarDates.toSolar(year, month, day).toEpochDay(), repeatSpec = repeat
    )

    @Test fun knownDatesAndLeapMonthRoundTrip() {
        assertEquals(LocalDate.of(2026, 2, 17), LunarDates.toSolar(2026, 1, 1))
        assertEquals(LocalDate.of(2026, 9, 25), LunarDates.toSolar(2026, 8, 15))
        val leap = LocalDate.of(2023, 3, 22)
        assertEquals(leap, LunarDates.toSolar(2023, -2, 1))
        assertEquals(-2, LunarDates.fromSolar(leap).month)
        assertEquals("农历 2023年闰二月初一", LunarDates.text(leap))
        assertEquals(listOf(1, 2, -2, 3, 4, 5, 6, 7, 8, 9, 10, 11, 12), LunarDates.months(2023).map { it.month })
    }

    @Test fun yearlyRepeatFollowsLunarNewYearAndIntervals() {
        val annual = event(2026, 1, 1, "YEAR:1")
        assertEquals(LocalDate.of(2027, 2, 6), annual.occurrenceOnOrAfter(LocalDate.of(2026, 2, 18)))
        assertEquals(LocalDate.of(2027, 2, 6), annual.occurrenceOnOrAfter(LocalDate.of(2027, 2, 6)))
        val biennial = event(2026, 1, 1, "YEAR:2")
        assertEquals(LunarDates.toSolar(2028, 1, 1), biennial.occurrenceOnOrAfter(LocalDate.of(2027, 2, 6)))
        assertEquals("↻农历每2年", biennial.cycleLabel())
    }

    @Test fun monthlyRepeatIncludesLeapMonthAndRestoresDayThirty() {
        val monthly = event(2023, 2, 30, "MONTH:1")
        assertEquals(LunarDates.toSolar(2023, -2, 29), monthly.occurrenceOnOrAfter(LocalDate.of(2023, 3, 22)))
        assertEquals(LunarDates.toSolar(2023, 3, 29), monthly.occurrenceOnOrAfter(LocalDate.of(2023, 4, 20)))
        assertEquals(LunarDates.toSolar(2023, 5, 30), monthly.occurrenceOnOrAfter(LunarDates.toSolar(2023, 5, 1)))
        val everyTwo = event(2023, 2, 30, "MONTH:2")
        assertEquals(LunarDates.toSolar(2023, 3, 29), everyTwo.occurrenceOnOrAfter(LocalDate.of(2023, 3, 22)))
    }

    @Test fun leapBirthdayFallsBackToOrdinaryMonthAndReturnsToLeapMonth() {
        val annual = event(2023, -2, 1, "YEAR:1")
        assertEquals(LunarDates.toSolar(2024, 2, 1), annual.occurrenceOnOrAfter(LocalDate.of(2024, 1, 1)))
        assertEquals(LunarDates.toSolar(2042, -2, 1), annual.occurrenceOnOrAfter(LunarDates.toSolar(2042, 2, 2)))
    }

    @Test fun localReminderUsesConvertedTargetWithOffsets() {
        val birthday = event(2026, 1, 1, "YEAR:1").copy(remindDaysBefore = listOf(0, 7))
        assertEquals(LocalDateTime.of(2027, 1, 30, 9, 0),
            ReminderTime.nextTrigger(birthday, LocalDateTime.of(2027, 1, 1, 0, 0)))
        assertEquals(LocalDateTime.of(2027, 2, 6, 9, 0),
            ReminderTime.nextTrigger(birthday, LocalDateTime.of(2027, 1, 30, 10, 0)))
    }

    @Test fun persistenceBackupAndCloudPayloadRetainCalendar() {
        val birthday = event(2026, 8, 15, "YEAR:1")
        assertTrue(Gson().fromJson(Gson().toJson(birthday), Event::class.java).sanitized().isLunar)
        val json = BackupData.encodePayload(BackupData.Payload(events = listOf(birthday), eventCount = 1))
        assertEquals(birthday, BackupData.parse(json).getOrThrow().single())
        assertTrue(SyncPayload.json("phone", 1, "Asia/Shanghai", listOf(birthday)).contains("\"calendarType\":\"LUNAR\""))
        assertFalse(SyncPayload.json("phone", 1, "Asia/Shanghai", listOf(birthday.copy(calendarType = null))).contains("calendarType"))
        assertFalse(Gson().fromJson("""{"title":"旧日程","dateEpochDay":20000}""", Event::class.java).sanitized().isLunar)
        assertFalse(birthday.copy(calendarType = "UNKNOWN").sanitized().isLunar)
    }

    @Test fun dayAndWeekRepeatsStillUseElapsedDays() {
        val anchor = LunarDates.toSolar(2023, 2, 30)
        assertEquals(anchor.plusDays(7), event(2023, 2, 30, "WEEK:1").occurrenceOnOrAfter(anchor.plusDays(1)))
        assertEquals(anchor.plusDays(3), event(2023, 2, 30, "DAY:3").occurrenceOnOrAfter(anchor.plusDays(1)))
        assertNull(event(2023, 2, 30).occurrenceOnOrAfter(anchor.plusDays(1)))
    }
}
