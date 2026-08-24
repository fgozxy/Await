package io.github.fgozxy.await.notify

import android.app.AlarmManager
import android.app.PendingIntent
import android.content.Context
import android.content.Intent
import android.os.Build
import io.github.fgozxy.await.MainActivity
import io.github.fgozxy.await.data.Cycle
import io.github.fgozxy.await.data.Event
import io.github.fgozxy.await.data.EventStore
import java.time.LocalDate
import java.time.LocalDateTime
import java.time.ZoneId

/**
 * 精确闹钟调度器。
 *
 * 策略：
 *  - 闹钟式提醒的日程走 setAlarmClock：完全豁免低电耗模式（比 setExactAndAllowWhileIdle
 *    的 Doze 配额更可靠），并且会在状态栏显示闹钟图标
 *  - 普通提醒走 setExactAndAllowWhileIdle 保证准时
 *  - Android 12+ 若用户未授予「闹钟和提醒」权限，则降级为窗口闹钟，并在 UI 上引导授权
 *  - 每次触发后自动滚动到该日程的下一个提醒点（支持每天/每周/每月/每年循环）
 *  - 设备重启后由 BootReceiver 调用 [scheduleAll] 恢复全部闹钟
 */
object AlarmScheduler {

    /** 计算某日程下一次提醒的触发时间；没有未来提醒时返回 null */
    fun nextTrigger(event: Event, from: LocalDateTime = LocalDateTime.now()): LocalDateTime? {
        // 倒序检查：同一候选日中「提前天数大」的触发点更早到来，需优先命中
        val offsets = event.remindDaysBefore.distinct().sortedDescending()
        if (offsets.isEmpty()) return null

        var candidate = event.date
        // 循环事件逐周期向后扫描；上限保护防止异常数据死循环
        repeat(3650) {
            for (offset in offsets) {
                val trigger = candidate.minusDays(offset.toLong())
                    .atTime(event.remindHour, event.remindMinute)
                if (trigger.isAfter(from)) return trigger
            }
            if (event.cycle == Cycle.NONE) return null
            candidate = event.advanceDate(candidate)
        }
        return null
    }

    fun scheduleEvent(context: Context, event: Event) {
        cancel(context, event.id)
        val trigger = nextTrigger(event) ?: return

        val am = context.getSystemService(Context.ALARM_SERVICE) as AlarmManager
        val pi = pendingIntent(context, event.id)

        val millis = trigger.atZone(ZoneId.systemDefault()).toInstant().toEpochMilli()
        val canExact = Build.VERSION.SDK_INT < 31 || am.canScheduleExactAlarms()
        when {
            canExact && event.isAlarmMode ->
                am.setAlarmClock(AlarmManager.AlarmClockInfo(millis, showIntent(context)), pi)
            canExact ->
                am.setExactAndAllowWhileIdle(AlarmManager.RTC_WAKEUP, millis, pi)
            else ->
                // 降级：非精确但省电，仍可正常提醒（可能有分钟级误差）
                am.setWindow(AlarmManager.RTC_WAKEUP, millis, 10 * 60_000L, pi)
        }
    }

    /**
     * 「稍后提醒」：从现在起 [minutes] 分钟后再响一次。
     *
     * 用独立的 action 和 request code，避免覆盖掉该日程正常的周期闹钟。
     */
    fun scheduleSnooze(context: Context, eventId: Long, minutes: Int) {
        val am = context.getSystemService(Context.ALARM_SERVICE) as AlarmManager
        val pi = snoozePendingIntent(context, eventId)
        val millis = System.currentTimeMillis() + minutes * 60_000L
        val canExact = Build.VERSION.SDK_INT < 31 || am.canScheduleExactAlarms()
        if (canExact) {
            am.setAlarmClock(AlarmManager.AlarmClockInfo(millis, showIntent(context)), pi)
        } else {
            am.setWindow(AlarmManager.RTC_WAKEUP, millis, 60_000L, pi)
        }
    }

    fun cancelSnooze(context: Context, eventId: Long) {
        val am = context.getSystemService(Context.ALARM_SERVICE) as AlarmManager
        am.cancel(snoozePendingIntent(context, eventId))
    }

    fun cancel(context: Context, eventId: Long) {
        val am = context.getSystemService(Context.ALARM_SERVICE) as AlarmManager
        am.cancel(pendingIntent(context, eventId))
    }

    /** （重新）调度所有日程的提醒。开机 / 数据变更后调用 */
    fun scheduleAll(context: Context) {
        EventStore.load(context).forEach { scheduleEvent(context, it) }
    }

    /** setAlarmClock 要求的「点状态栏闹钟图标去哪」的意图 */
    private fun showIntent(context: Context): PendingIntent = PendingIntent.getActivity(
        context, 0,
        Intent(context, MainActivity::class.java)
            .setFlags(Intent.FLAG_ACTIVITY_NEW_TASK or Intent.FLAG_ACTIVITY_CLEAR_TOP),
        PendingIntent.FLAG_UPDATE_CURRENT or PendingIntent.FLAG_IMMUTABLE
    )

    private fun snoozePendingIntent(context: Context, eventId: Long): PendingIntent {
        val intent = Intent(context, AlarmReceiver::class.java)
            .setAction(AlarmReceiver.ACTION_SNOOZE_FIRE)
            .putExtra(AlarmReceiver.EXTRA_EVENT_ID, eventId)
        return PendingIntent.getBroadcast(
            context,
            eventId.toInt() xor SNOOZE_RC_SALT,
            intent,
            PendingIntent.FLAG_UPDATE_CURRENT or PendingIntent.FLAG_IMMUTABLE
        )
    }

    private fun pendingIntent(context: Context, eventId: Long): PendingIntent {
        val intent = Intent(context, AlarmReceiver::class.java)
            .setAction(AlarmReceiver.ACTION_REMIND)
            .putExtra(AlarmReceiver.EXTRA_EVENT_ID, eventId)
        return PendingIntent.getBroadcast(
            context,
            eventId.toInt(),
            intent,
            PendingIntent.FLAG_UPDATE_CURRENT or PendingIntent.FLAG_IMMUTABLE
        )
    }

    /** 稍后提醒的 request code 偏移，保证与该日程的周期闹钟不撞车 */
    private const val SNOOZE_RC_SALT = 0x5A5A
}
