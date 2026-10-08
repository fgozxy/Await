package io.github.fgozxy.await.notify

import io.github.fgozxy.await.data.Event
import java.time.LocalDateTime
import java.time.LocalTime

/** 提醒时间计算，供软件通知调度和日期验证共用。 */
object ReminderTime {
    fun timeOf(event: Event, defaultTime: LocalTime = LocalTime.of(9, 0)): LocalTime {
        val safe = event.sanitized()
        return if (safe.preciseTime) LocalTime.of(safe.remindHour, safe.remindMinute) else defaultTime
    }

    /** 计算某日程下一次提醒的触发时间；没有未来提醒时返回 null */
    fun nextTrigger(event: Event, from: LocalDateTime = LocalDateTime.now(),
                    defaultTime: LocalTime = LocalTime.of(9, 0)): LocalDateTime? {
        val safe = event.sanitized()
        val offsets = safe.remindDaysBefore.distinct()
        if (offsets.isEmpty()) return null
        val remindTime = timeOf(safe, defaultTime)

        // 分别求每个「提前 N 天」规则的下一个周期，再取最早者。这样无需从多年前逐周期扫描，
        // 同时仍能在一次提醒触发后继续安排同一日程的下一档提前提醒。
        return offsets.mapNotNull { offset ->
            val firstCandidateDate = from.toLocalDate().plusDays(offset.toLong()).let {
                if (remindTime.isAfter(from.toLocalTime())) it else it.plusDays(1)
            }
            safe.occurrenceOnOrAfter(firstCandidateDate)
                ?.minusDays(offset.toLong())
                ?.atTime(remindTime)
                ?.takeIf { it.isAfter(from) }
        }.minOrNull()
    }

}
