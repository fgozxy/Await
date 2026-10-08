package io.github.fgozxy.await.notify

import android.Manifest
import android.app.AlarmManager
import android.app.NotificationChannel as AndroidNotificationChannel
import android.app.NotificationManager
import android.app.PendingIntent
import android.content.BroadcastReceiver
import android.content.Context
import android.content.Intent
import android.content.pm.PackageManager
import android.os.Build
import androidx.core.app.NotificationCompat
import androidx.core.app.NotificationManagerCompat
import androidx.core.content.ContextCompat
import com.google.gson.Gson
import com.google.gson.JsonParser
import io.github.fgozxy.await.MainActivity
import io.github.fgozxy.await.R
import io.github.fgozxy.await.data.EventStore
import io.github.fgozxy.await.data.MergeStore
import io.github.fgozxy.await.sync.SyncCoordinator
import java.time.Instant
import java.time.ZoneId

object LocalNotifications {
    private const val PREFS = "await_local_reminders"
    private const val CHANNEL = "await_software_reminders"
    private const val ACTION = "io.github.fgozxy.await.LOCAL_REMINDER"

    fun allowed(context: Context): Boolean =
        (Build.VERSION.SDK_INT < 33 || ContextCompat.checkSelfPermission(context,
            Manifest.permission.POST_NOTIFICATIONS) == PackageManager.PERMISSION_GRANTED) &&
            NotificationManagerCompat.from(context).areNotificationsEnabled() &&
            context.getSystemService(NotificationManager::class.java)
                .getNotificationChannel(CHANNEL)?.importance != NotificationManager.IMPORTANCE_NONE

    fun exactAllowed(context: Context): Boolean = Build.VERSION.SDK_INT < 31 ||
        context.getSystemService(AlarmManager::class.java).canScheduleExactAlarms()

    private fun prefs(context: Context) = context.getSharedPreferences(PREFS, Context.MODE_PRIVATE)

    private fun readPlan(context: Context): List<PlannedReminder> = runCatching {
        JsonParser.parseString(prefs(context).getString("plan", "[]")).asJsonArray.map {
            val row = it.asJsonObject
            PlannedReminder(row["eventId"].asLong, row["due"].asLong, row["fingerprint"].asString)
        }
    }.getOrDefault(emptyList())

    private fun writePlan(context: Context, plan: List<PlannedReminder>) {
        check(prefs(context).edit().putString("plan", Gson().toJson(plan.map {
            mapOf("eventId" to it.eventId, "due" to it.due, "fingerprint" to it.fingerprint)
        })).commit())
    }

    private fun pending(context: Context) = PendingIntent.getBroadcast(context, 0,
        Intent(context, LocalReminderReceiver::class.java).setAction(ACTION),
        PendingIntent.FLAG_UPDATE_CURRENT or PendingIntent.FLAG_IMMUTABLE)

    fun reschedule(context: Context) = synchronized(SyncCoordinator.lock) {
        val enabled = NotificationChannel.LOCAL in NotificationChannels.load(context)
        // Preserve the existing alarm if local data cannot be read.
        val events = if (enabled) runCatching { EventStore.load(context, failOnUnreadable = true) }
            .getOrElse { return@synchronized } else emptyList()
        val manager = context.getSystemService(AlarmManager::class.java)
        val intent = pending(context)
        manager.cancel(intent)
        if (!enabled) {
            writePlan(context, emptyList())
            context.getSystemService(NotificationManager::class.java).activeNotifications
                .filter { it.notification.channelId == CHANNEL }.forEach {
                    context.getSystemService(NotificationManager::class.java).cancel(it.tag, it.id)
                }
            return@synchronized
        }
        val now = System.currentTimeMillis()
        val plan = LocalReminderPlan.build(events, readPlan(context), now)
        writePlan(context, plan)
        val due = LocalReminderPlan.nextAlarm(plan, now, allowed(context)) ?: return@synchronized
        try {
            if (exactAllowed(context)) manager.setExactAndAllowWhileIdle(AlarmManager.RTC_WAKEUP, due, intent)
            else manager.setAndAllowWhileIdle(AlarmManager.RTC_WAKEUP, due, intent)
        } catch (_: SecurityException) {
            manager.setAndAllowWhileIdle(AlarmManager.RTC_WAKEUP, due, intent)
        }
    }

    fun show(context: Context, text: String, tag: String): Boolean {
        val manager = context.getSystemService(NotificationManager::class.java)
        manager.createNotificationChannel(AndroidNotificationChannel(CHANNEL, "日程提醒", NotificationManager.IMPORTANCE_HIGH))
        if (!allowed(context)) return false
        val open = PendingIntent.getActivity(context, 0, Intent(context, MainActivity::class.java),
            PendingIntent.FLAG_UPDATE_CURRENT or PendingIntent.FLAG_IMMUTABLE)
        val displayText = if (text.length <= 4096) text else text.take(
            if (Character.isHighSurrogate(text[4094])) 4094 else 4095) + "…"
        val notification = NotificationCompat.Builder(context, CHANNEL)
            .setSmallIcon(R.drawable.ic_notification).setContentTitle("Await 日程提醒")
            .setContentText(displayText).setStyle(NotificationCompat.BigTextStyle().bigText(displayText))
            .setContentIntent(open).setAutoCancel(true).setOnlyAlertOnce(true).build()
        return try { manager.notify(tag, 1, notification); true } catch (_: SecurityException) { false }
    }

    fun deliver(context: Context) = synchronized(SyncCoordinator.lock) {
        if (NotificationChannel.LOCAL !in NotificationChannels.load(context)) {
            reschedule(context)
            return@synchronized
        }
        val now = System.currentTimeMillis()
        val events = runCatching { EventStore.load(context, failOnUnreadable = true) }
            .getOrElse { return@synchronized }.associateBy { it.id }
        val zone = ZoneId.systemDefault()
        val plan = readPlan(context)
        val due = plan.filter { entry -> entry.due in (now - 86_400_000L)..now &&
            events[entry.eventId]?.let { LocalReminderPlan.fingerprint(it, zone) == entry.fingerprint } == true }
        val groups = MergeStore.prune(context, events.values.toList())
        val buckets = LocalReminderPlan.batches(due, groups)
        val consumed = mutableSetOf<PlannedReminder>()
        buckets.forEach { (key, entries) ->
            val date = Instant.ofEpochMilli(key.first).atZone(zone).toLocalDate()
            val text = entries.joinToString("\n\n") { entry ->
                val event = events.getValue(entry.eventId)
                val target = event.occurrenceOnOrAfter(date) ?: event.date
                val days = java.time.temporal.ChronoUnit.DAYS.between(date, target)
                event.title + "\n$target · " + (if (days == 0L) "就是今天！" else "还有 $days 天") +
                    event.note.takeIf { it.isNotBlank() }?.let { "\n$it" }.orEmpty()
            }
            val tag = "reminder:${key.first}:${entries.map { it.eventId }.sorted().joinToString(",")}"
            if (show(context, text, tag)) consumed.addAll(entries)
        }
        // Denied notifications remain available for catch-up after permission is granted.
        writePlan(context, plan.filterNot { it in consumed })
        reschedule(context)
    }
}

class LocalReminderReceiver : BroadcastReceiver() {
    override fun onReceive(context: Context, intent: Intent) {
        LocalNotifications.deliver(context)
    }
}
