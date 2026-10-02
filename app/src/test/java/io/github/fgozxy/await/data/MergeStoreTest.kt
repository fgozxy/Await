package io.github.fgozxy.await.data

import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNotEquals
import org.junit.Assert.assertTrue
import org.junit.Test
import java.time.LocalDate

class MergeStoreTest {

    @Test
    fun compatibilityRequiresSameDateTimeAndOffsets() {
        val base = event(1, LocalDate.of(2026, 10, 1), 9, listOf(0))

        assertTrue(MergeStore.areCompatible(listOf(base, base.copy(id = 2))))
        assertFalse(MergeStore.areCompatible(listOf(base, base.copy(id = 2, remindHour = 10))))
        assertFalse(MergeStore.areCompatible(listOf(base, base.copy(id = 2, remindDaysBefore = listOf(7)))))
        assertFalse(
            MergeStore.areCompatible(
                listOf(base, base.copy(id = 2, dateEpochDay = LocalDate.of(2026, 10, 2).toEpochDay()))
            )
        )
    }

    @Test
    fun invalidGroupIsRemovedAfterMemberDateChanges() {
        val first = event(1, LocalDate.of(2026, 10, 1))
        val moved = event(2, LocalDate.of(2026, 10, 2))

        val groups = MergeStore.sanitizeGroups(
            listOf(MergeGroup(10, listOf(1, 2))),
            listOf(first, moved)
        )

        assertTrue(groups.isEmpty())
    }

    @Test
    fun duplicateGroupIdsAreReassigned() {
        val events = listOf(
            event(1, LocalDate.of(2026, 10, 1)),
            event(2, LocalDate.of(2026, 10, 1)),
            event(3, LocalDate.of(2026, 10, 2)),
            event(4, LocalDate.of(2026, 10, 2))
        )

        val groups = MergeStore.sanitizeGroups(
            listOf(
                MergeGroup(100, listOf(1, 2)),
                MergeGroup(100, listOf(3, 4))
            ),
            events
        )

        assertEquals(2, groups.size)
        assertNotEquals(groups[0].id, groups[1].id)
    }

    @Test
    fun uniqueIdAdvancesPastOccupiedMilliseconds() {
        assertEquals(103L, MergeStore.nextUniqueId(setOf(100L, 101L, 102L), now = 100L))
    }

    private fun event(
        id: Long,
        date: LocalDate,
        hour: Int = 9,
        offsets: List<Int> = listOf(0)
    ) = Event(
        id = id,
        title = "日程$id",
        dateEpochDay = date.toEpochDay(),
        remindDaysBefore = offsets,
        remindHour = hour
    )
}
