package io.github.fgozxy.await.notify

import com.google.gson.Gson
import io.github.fgozxy.await.data.Event
import java.security.MessageDigest
import java.time.Instant
import java.time.LocalDateTime
import java.time.LocalTime
import java.time.ZoneId

data class PlannedReminder(val eventId: Long, val due: Long, val fingerprint: String)

/** Pure planning logic shared by scheduling and delivery validation. */
object LocalReminderPlan {
    fun nextAlarm(plan: List<PlannedReminder>, now: Long, notificationsAllowed: Boolean): Long? =
        plan.filter { notificationsAllowed || it.due > now }.minOfOrNull { it.due }?.coerceAtLeast(now + 1000)

    fun notificationTag(entry: PlannedReminder): String = "reminder:${entry.due}:${entry.eventId}"

    fun fingerprint(event: Event, zone: ZoneId, defaultTime: LocalTime = LocalTime.of(9, 0)): String {
        val safe = event.sanitized()
        val time = ReminderTime.timeOf(safe, defaultTime)
        val json = Gson().toJson(listOf(safe.id, safe.title, safe.note, safe.dateEpochDay,
            safe.cycle.name, safe.repeatN, safe.remindDaysBefore, time.hour, time.minute, zone.id))
        return MessageDigest.getInstance("SHA-256").digest(json.toByteArray(Charsets.UTF_8))
            .joinToString("") { "%02x".format(it) }
    }

    fun build(events: List<Event>, previous: List<PlannedReminder>, now: Long,
              zone: ZoneId = ZoneId.systemDefault(), defaultTime: LocalTime = LocalTime.of(9, 0)): List<PlannedReminder> {
        val byId = events.associateBy { it.id }
        val overdue = previous.filter { entry ->
            entry.due in (now - 86_400_000L)..now && byId[entry.eventId]?.let {
                fingerprint(it, zone, defaultTime) == entry.fingerprint
            } == true
        }
        val from = LocalDateTime.ofInstant(Instant.ofEpochMilli(now), zone)
        val upcoming = events.mapNotNull { event ->
            ReminderTime.nextTrigger(event, from, defaultTime)?.let {
                PlannedReminder(event.id, it.atZone(zone).toInstant().toEpochMilli(), fingerprint(event, zone, defaultTime))
            }
        }
        return (overdue + upcoming).distinct().sortedBy { it.due }
    }
}
