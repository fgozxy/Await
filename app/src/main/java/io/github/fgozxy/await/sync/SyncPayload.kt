package io.github.fgozxy.await.sync

import com.google.gson.Gson
import io.github.fgozxy.await.data.Event

/** Explicit wire fields keep old Android alarm fields and secrets out of uploads. */
object SyncPayload {
    fun json(clientId: String, revision: Long, timezone: String, events: List<Event>,
             notificationChannels: List<String> = listOf("telegram")): String =
        Gson().toJson(mapOf(
            "clientId" to clientId, "revision" to revision, "timezone" to timezone,
            "notificationChannels" to notificationChannels.distinct().sorted(),
            "events" to events.map { raw ->
                val event = raw.sanitized()
                mapOf("id" to event.id, "title" to event.title, "note" to event.note,
                    "date" to event.date.toString(), "cycle" to event.cycle.name,
                    "repeatN" to event.repeatN, "remindDaysBefore" to event.remindDaysBefore,
                    "remindHour" to event.remindHour, "remindMinute" to event.remindMinute)
            }, "mergeGroups" to emptyList<Long>()))
}
