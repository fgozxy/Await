package io.github.fgozxy.await.notify

import android.Manifest
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
 *  - event_reminders : 单条日程的倒计时提醒（高优先级）
 *  - daily_summary   : 每日日程汇总提醒（默认优先级）
 */
object NotificationHelper {

    const val CHANNEL_EVENTS = "event_reminders"
    const val CHANNEL_DAILY = "daily_summary"

    fun ensureChannels(context: Context) {
        val nm = context.getSystemService(NotificationManager::class.java) ?: return
        nm.createNotificationChannel(
            NotificationChannel(
                CHANNEL_EVENTS,
                "日程倒计时提醒",
                NotificationManager.IMPORTANCE_HIGH
            ).apply { description = "在日程临近时发送倒计时提醒" }
        )
        nm.createNotificationChannel(
            NotificationChannel(
                CHANNEL_DAILY,
                "每日汇总提醒",
                NotificationManager.IMPORTANCE_DEFAULT
            ).apply { description = "每天固定时间汇总今日与即将到来的日程" }
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

    /** 每日汇总通知 */
    fun showDailySummary(context: Context, todayCount: Int, upcomingCount: Int, nearestTitle: String?, nearestDays: Int?) {
        if (!canNotify(context)) return
        if (todayCount == 0 && upcomingCount == 0) return
        val text = buildString {
            if (todayCount > 0) append("今天有 $todayCount 个日程")
            if (nearestTitle != null && nearestDays != null && nearestDays > 0) {
                if (isNotEmpty()) append("；")
                append("最近的「$nearestTitle」还有 $nearestDays 天")
            }
        }
        val contentIntent = PendingIntent.getActivity(
            context, 10086,
            Intent(context, MainActivity::class.java)
                .setFlags(Intent.FLAG_ACTIVITY_NEW_TASK or Intent.FLAG_ACTIVITY_CLEAR_TOP),
            PendingIntent.FLAG_UPDATE_CURRENT or PendingIntent.FLAG_IMMUTABLE
        )
        val notification = NotificationCompat.Builder(context, CHANNEL_DAILY)
            .setSmallIcon(R.drawable.ic_notification)
            .setContentTitle("Await · 今日日程速览")
            .setContentText(text)
            .setStyle(NotificationCompat.BigTextStyle().bigText(text))
            .setContentIntent(contentIntent)
            .setAutoCancel(true)
            .build()
        NotificationManagerCompat.from(context).notify(10086, notification)
    }

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
