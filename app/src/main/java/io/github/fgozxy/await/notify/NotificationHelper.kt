package io.github.fgozxy.await.notify

import android.Manifest
import android.app.Notification
import android.app.NotificationChannel
import android.app.NotificationManager
import android.app.PendingIntent
import android.content.Context
import android.content.Intent
import android.content.pm.PackageManager
import androidx.core.app.NotificationCompat
import androidx.core.app.NotificationManagerCompat
import io.github.fgozxy.await.MainActivity
import io.github.fgozxy.await.ReminderActivity
import io.github.fgozxy.await.R
import io.github.fgozxy.await.data.Event

/**
 * 通知中心：负责渠道管理与所有通知的构建展示。
 *
 * 渠道设计：
 *  - event_alarm_v1  : 闹钟式提醒（前台服务的常驻通知；声音与震动由服务自己控制）
 *  - event_reminders : 普通日程提醒（高优先级，系统默认提示音）
 *  - backup          : 定时备份失败提示（低优先级，不打扰）
 */
object NotificationHelper {

    const val CHANNEL_EVENTS = "event_reminders"
    const val CHANNEL_BACKUP = "backup"

    /**
     * 闹钟渠道。带 `_v1` 后缀是必须的：渠道属性一旦创建就不可再改，
     * 复用老的 event_reminders 就没法把系统提示音关掉（我们要自己播放循环铃声）。
     */
    const val CHANNEL_ALARM = "event_alarm_v1"

    fun ensureChannels(context: Context) {
        val nm = context.getSystemService(NotificationManager::class.java) ?: return
        nm.createNotificationChannel(
            NotificationChannel(
                CHANNEL_ALARM,
                "闹钟式提醒",
                NotificationManager.IMPORTANCE_HIGH
            ).apply {
                description = "到点持续响铃震动，直到手动关闭"
                // 声音和震动都由 AlarmRingService 自己控制（走闹钟音量通道），
                // 渠道这边必须关掉，否则会和循环铃声叠在一起。
                setSound(null, null)
                enableVibration(false)
                setBypassDnd(true)
                lockscreenVisibility = Notification.VISIBILITY_PUBLIC
            }
        )
        nm.createNotificationChannel(
            NotificationChannel(
                CHANNEL_EVENTS,
                "日程倒计时提醒",
                NotificationManager.IMPORTANCE_HIGH
            ).apply { description = "在日程临近时发送倒计时提醒" }
        )
        nm.createNotificationChannel(
            NotificationChannel(
                CHANNEL_BACKUP,
                "备份提醒",
                NotificationManager.IMPORTANCE_LOW
            ).apply { description = "定时 WebDAV 备份失败时提示" }
        )
    }

    private fun canNotify(context: Context): Boolean =
        if (android.os.Build.VERSION.SDK_INT >= 33) {
            context.checkSelfPermission(Manifest.permission.POST_NOTIFICATIONS) ==
                PackageManager.PERMISSION_GRANTED
        } else true

    /** 展示单条日程提醒 */
    fun showEventReminder(context: Context, event: Event) {
        if (!canNotify(context)) return
        val days = event.daysFromToday()
        val whenText = when {
            days == 0 -> "就是今天！"
            days > 0 -> "还有 $days 天"
            else -> "已过去 ${-days} 天"
        }
        val text = "${event.dateText()} · $whenText" +
            (if (event.note.isNotBlank()) "\n${event.note}" else "")

        // 全屏意图：触发时直接弹出应用内的全屏提醒页（锁屏也显示）
        val fullScreenPi = PendingIntent.getActivity(
            context,
            (event.id % Int.MAX_VALUE).toInt() + 1_000_000,
            Intent(context, ReminderActivity::class.java)
                .putExtra(ReminderActivity.EXTRA_EVENT_ID, event.id)
                .addFlags(Intent.FLAG_ACTIVITY_NEW_TASK),
            PendingIntent.FLAG_UPDATE_CURRENT or PendingIntent.FLAG_IMMUTABLE
        )

        val notification = NotificationCompat.Builder(context, CHANNEL_EVENTS)
            .setSmallIcon(R.drawable.ic_notification)
            .setContentTitle("⏳ ${event.title}")
            .setContentText("$whenText（${event.dateText()}）")
            .setStyle(NotificationCompat.BigTextStyle().bigText(text))
            .setContentIntent(fullScreenPi)            // 点通知 → 直接进入全屏提醒页
            .setFullScreenIntent(fullScreenPi, true)   // 锁屏/后台 → 全屏弹出
            .setCategory(NotificationCompat.CATEGORY_ALARM)
            .setAutoCancel(true)
            .setPriority(NotificationCompat.PRIORITY_MAX)
            .build()

        NotificationManagerCompat.from(context).notify(event.id.toInt(), notification)
    }

    /**
     * 构建闹钟式提醒的常驻通知（由 [AlarmRingService] 作为前台服务通知使用）。
     *
     * 与普通提醒的区别：不可划掉、不自动消失、带「稍后提醒 / 关闭」两个按钮，
     * 且声音震动一律由服务自己播，渠道层面是静音的。
     */
    fun buildAlarmNotification(context: Context, event: Event): Notification {
        val days = event.daysFromToday()
        val whenText = when {
            days == 0 -> "就是今天！"
            days > 0 -> "还有 $days 天"
            else -> "已过去 ${-days} 天"
        }

        val fullScreenPi = PendingIntent.getActivity(
            context,
            requestCode(event.id, RC_FULLSCREEN),
            Intent(context, ReminderActivity::class.java)
                .putExtra(ReminderActivity.EXTRA_EVENT_ID, event.id)
                .putExtra(ReminderActivity.EXTRA_ALARM_MODE, true)
                .addFlags(Intent.FLAG_ACTIVITY_NEW_TASK),
            PendingIntent.FLAG_UPDATE_CURRENT or PendingIntent.FLAG_IMMUTABLE
        )

        fun servicePi(action: String, rcSalt: Int) = PendingIntent.getForegroundService(
            context,
            requestCode(event.id, rcSalt),
            Intent(context, AlarmRingService::class.java)
                .setAction(action)
                .putExtra(AlarmRingService.EXTRA_EVENT_ID, event.id),
            PendingIntent.FLAG_UPDATE_CURRENT or PendingIntent.FLAG_IMMUTABLE
        )

        return NotificationCompat.Builder(context, CHANNEL_ALARM)
            .setSmallIcon(R.drawable.ic_notification)
            .setContentTitle("⏳ ${event.title}")
            .setContentText("$whenText（${event.dateText()}）")
            .setStyle(NotificationCompat.BigTextStyle().bigText(
                "${event.dateText()} · $whenText" +
                    (if (event.note.isNotBlank()) "\n${event.note}" else "")
            ))
            .setContentIntent(fullScreenPi)
            .setFullScreenIntent(fullScreenPi, true)
            .setCategory(NotificationCompat.CATEGORY_ALARM)
            .setPriority(NotificationCompat.PRIORITY_MAX)
            .setVisibility(NotificationCompat.VISIBILITY_PUBLIC)
            .setOngoing(true)          // 不可划掉：必须点「关闭」或「稍后提醒」
            .setAutoCancel(false)
            .setSilent(true)           // 出声的是服务里的 MediaPlayer，不是这条通知
            .addAction(
                0, "稍后提醒",
                servicePi(AlarmRingService.ACTION_SNOOZE, RC_SNOOZE)
            )
            .addAction(
                0, "关闭",
                servicePi(AlarmRingService.ACTION_STOP, RC_STOP)
            )
            .build()
    }

    /** 定时备份失败提示：只在失败时打扰一次，成功静默 */
    fun showBackupFailed(context: Context, reason: String) {
        if (!canNotify(context)) return
        val contentIntent = PendingIntent.getActivity(
            context, 10087,
            Intent(context, MainActivity::class.java)
                .setFlags(Intent.FLAG_ACTIVITY_NEW_TASK or Intent.FLAG_ACTIVITY_CLEAR_TOP),
            PendingIntent.FLAG_UPDATE_CURRENT or PendingIntent.FLAG_IMMUTABLE
        )
        val notification = NotificationCompat.Builder(context, CHANNEL_BACKUP)
            .setSmallIcon(R.drawable.ic_notification)
            .setContentTitle("Await 自动备份未成功")
            .setContentText(reason)
            .setStyle(NotificationCompat.BigTextStyle().bigText("$reason\n可进入「备份与恢复」检查 WebDAV 配置。"))
            .setContentIntent(contentIntent)
            .setAutoCancel(true)
            .setPriority(NotificationCompat.PRIORITY_LOW)
            .build()
        NotificationManagerCompat.from(context).notify(10087, notification)
    }

    // 同一条日程会同时存在多个 PendingIntent（全屏页 / 稍后提醒 / 关闭），
    // request code 必须互不相同，否则 FLAG_UPDATE_CURRENT 会让它们互相覆盖。
    private const val RC_FULLSCREEN = 0x0F0F
    private const val RC_SNOOZE = 0x5A5A
    private const val RC_STOP = 0x3C3C

    private fun requestCode(eventId: Long, salt: Int): Int = eventId.toInt() xor salt

    /**
     * 立即发送一条测试通知，用于验证通知链路（权限 / 渠道 / 省电策略）是否正常。
     * 返回是否成功发出。
     */
    fun showTestNotification(context: Context): Boolean {
        if (!canNotify(context)) return false
        val notification = NotificationCompat.Builder(context, CHANNEL_EVENTS)
            .setSmallIcon(R.drawable.ic_notification)
            .setContentTitle("✅ Await 测试通知")
            .setContentText("通知链路正常！到期的日程提醒会以同样方式送达。")
            .setStyle(NotificationCompat.BigTextStyle().bigText(
                "如果你能看到这条通知，说明通知权限和渠道都正常。\n" +
                "如日程提醒仍未送达，请检查：\n" +
                "1. 是否已授予「闹钟和提醒」权限\n" +
                "2. 系统设置中是否允许本应用后台运行/自启动\n" +
                "3. 提醒时刻是否已过去（已过去的时刻不会补发）"
            ))
            .setAutoCancel(true)
            .setPriority(NotificationCompat.PRIORITY_HIGH)
            .build()
        NotificationManagerCompat.from(context).notify(999999, notification)
        return true
    }
}
