package io.github.fgozxy.await.notify

import android.content.BroadcastReceiver
import android.content.Context
import android.content.Intent
import androidx.core.content.ContextCompat
import io.github.fgozxy.await.backup.BackupScheduler
import io.github.fgozxy.await.data.Event
import io.github.fgozxy.await.data.EventStore

/** 日程提醒触发入口 */
class AlarmReceiver : BroadcastReceiver() {

    override fun onReceive(context: Context, intent: Intent) {
        when (intent.action) {
            ACTION_REMIND -> {
                val id = intent.getLongExtra(EXTRA_EVENT_ID, -1L)
                if (id == -1L) return
                EventStore.load(context).find { it.id == id }?.let { event ->
                    fire(context, event)
                    // 自动滚动到下一个提醒点（当天提醒触发后继续安排提前 N 天的下一年提醒等）
                    AlarmScheduler.scheduleEvent(context, event)
                }
            }

            // 「稍后提醒」到点：只再响一次，不动该日程的周期闹钟
            ACTION_SNOOZE_FIRE -> {
                val id = intent.getLongExtra(EXTRA_EVENT_ID, -1L)
                if (id == -1L) return
                EventStore.load(context).find { it.id == id }?.let { fire(context, it) }
            }
            Intent.ACTION_MY_PACKAGE_REPLACED -> AlarmScheduler.scheduleAll(context)
        }
    }

    /**
     * 按日程的提醒方式发出提醒。
     *
     * 闹钟模式走前台服务持续响铃；这里的 try/catch 不是可选的：Android 12+ 禁止
     * 后台启动前台服务，我们依赖的是「精确闹钟触发」这条豁免。一旦用户没给精确
     * 闹钟权限、调度降级成了 setWindow，豁免就不成立，
     * ForegroundServiceStartNotAllowedException 会直接把 receiver 崩掉。
     */
    private fun fire(context: Context, event: Event) {
        if (!event.isAlarmMode) {
            NotificationHelper.showEventReminder(context, event)
            return
        }
        runCatching {
            ContextCompat.startForegroundService(
                context,
                AlarmRingService.ringIntent(context, event.id)
            )
        }.onFailure {
            // 降级为普通高优先级通知，至少不会漏提醒
            NotificationHelper.showEventReminder(context, event)
        }
    }

    companion object {
        const val ACTION_REMIND = "io.github.fgozxy.await.ACTION_REMIND"
        const val ACTION_SNOOZE_FIRE = "io.github.fgozxy.await.ACTION_SNOOZE_FIRE"
        const val EXTRA_EVENT_ID = "extra_event_id"
    }
}

/** 开机自启 / 时间变化 / 应用升级：恢复所有提醒闹钟与定时备份 */
class BootReceiver : BroadcastReceiver() {

    override fun onReceive(context: Context, intent: Intent) {
        when (intent.action) {
            Intent.ACTION_BOOT_COMPLETED,
            Intent.ACTION_LOCKED_BOOT_COMPLETED,
            Intent.ACTION_TIME_CHANGED,
            Intent.ACTION_TIMEZONE_CHANGED,
            Intent.ACTION_MY_PACKAGE_REPLACED -> {
                AlarmScheduler.scheduleAll(context)
                BackupScheduler.reschedule(context)
            }
        }
    }
}
