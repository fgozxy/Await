package io.github.fgozxy.await.notify

import android.content.Context
import io.github.fgozxy.await.sync.SyncCoordinator
import java.time.LocalTime

/** 所有未开启精准时间的日程共用的提醒时刻，不含连接凭据。 */
object ReminderSettings {
    data class Time(val hour: Int = 9, val minute: Int = 0) {
        val isValid: Boolean get() = hour in 0..23 && minute in 0..59
        fun localTime(): LocalTime = LocalTime.of(hour, minute)
        fun display(): String = "%02d:%02d".format(hour, minute)
    }

    fun load(context: Context): Time {
        val prefs = context.getSharedPreferences("await_notification_settings", Context.MODE_PRIVATE)
        return Time(prefs.getInt("default_hour", 9).coerceIn(0, 23),
            prefs.getInt("default_minute", 0).coerceIn(0, 59))
    }

    fun save(context: Context, time: Time) = synchronized(SyncCoordinator.lock) {
        require(time.isValid) { "默认通知时间无效" }
        check(context.getSharedPreferences("await_notification_settings", Context.MODE_PRIVATE).edit()
            .putInt("default_hour", time.hour).putInt("default_minute", time.minute).commit())
        SyncCoordinator.changed(context)
    }
}
