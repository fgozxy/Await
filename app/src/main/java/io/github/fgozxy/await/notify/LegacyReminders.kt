package io.github.fgozxy.await.notify

import android.app.AlarmManager
import android.app.NotificationManager
import android.app.PendingIntent
import android.content.Context
import android.content.Intent
import io.github.fgozxy.await.data.EventStore

/** 升级后取消旧提醒；不创建本地通知或日程闹钟。 */
object LegacyReminders {
    fun clear(context: Context) {
        val migration = context.getSharedPreferences("await_reminder_migrations", Context.MODE_PRIVATE)
        if (migration.getBoolean("legacy_cleared", false)) return
        val manager = context.getSystemService(AlarmManager::class.java)
        EventStore.load(context).forEach { event ->
            listOf("ACTION_REMIND" to event.id.toInt(), "ACTION_SNOOZE_FIRE" to (event.id.toInt() xor 0x5A5A))
                .forEach { (action, code) ->
                    val intent = Intent().setClassName(context, "io.github.fgozxy.await.notify.AlarmReceiver")
                        .setAction("io.github.fgozxy.await.$action")
                    PendingIntent.getBroadcast(context, code, intent,
                        PendingIntent.FLAG_NO_CREATE or PendingIntent.FLAG_IMMUTABLE)?.let {
                        manager?.cancel(it)
                        it.cancel()
                    }
                }
        }
        context.stopService(Intent().setClassName(context, "io.github.fgozxy.await.notify.AlarmRingService"))
        context.getSystemService(NotificationManager::class.java)?.let { nm ->
            nm.cancelAll()
            listOf("event_reminders", "event_alarm_v1", "alarm_service_v1", "backup", "update_v1")
                .forEach(nm::deleteNotificationChannel)
        }
        check(migration.edit().putBoolean("legacy_cleared", true).commit())
    }
}
