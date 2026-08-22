package io.github.fgozxy.await.notify

import android.content.BroadcastReceiver
import android.content.Context
import android.content.Intent
import io.github.fgozxy.await.data.EventStore

/** 日程提醒触发入口 */
class AlarmReceiver : BroadcastReceiver() {

    override fun onReceive(context: Context, intent: Intent) {
        when (intent.action) {
            ACTION_REMIND -> {
                val id = intent.getLongExtra(EXTRA_EVENT_ID, -1L)
                if (id == -1L) return
                EventStore.load(context).find { it.id == id }?.let { event ->
                    NotificationHelper.showEventReminder(context, event)
                    // 自动滚动到下一个提醒点（当天提醒触发后继续安排提前 N 天的下一年提醒等）
                    AlarmScheduler.scheduleEvent(context, event)
                }
            }
            Intent.ACTION_MY_PACKAGE_REPLACED -> AlarmScheduler.scheduleAll(context)
        }
    }

    companion object {
        const val ACTION_REMIND = "io.github.fgozxy.await.ACTION_REMIND"
        const val EXTRA_EVENT_ID = "extra_event_id"
    }
}

/** 开机自启 / 时间变化：恢复所有已注册的提醒闹钟 */
class BootReceiver : BroadcastReceiver() {

    override fun onReceive(context: Context, intent: Intent) {
        when (intent.action) {
            Intent.ACTION_BOOT_COMPLETED,
            Intent.ACTION_LOCKED_BOOT_COMPLETED,
            Intent.ACTION_TIME_CHANGED,
            Intent.ACTION_TIMEZONE_CHANGED -> AlarmScheduler.scheduleAll(context)
        }
    }
}
