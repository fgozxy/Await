package io.github.fgozxy.await.backup

import android.app.AlarmManager
import android.app.PendingIntent
import android.content.BroadcastReceiver
import android.content.Context
import android.content.Intent
import io.github.fgozxy.await.notify.NotificationHelper
import java.time.Instant
import java.time.LocalDateTime
import java.time.ZoneId

/**
 * 定时备份的调度器。
 *
 * 触发时间 = 每天设定时刻中，第一个「晚于现在」且「距上次成功备份 ≥ 周期」的时刻。
 * 用 setAndAllowWhileIdle：备份不需要秒级准时，省电且不依赖精确闹钟权限。
 */
object BackupScheduler {

    private const val REQUEST_CODE = 20260822
    private const val ACTION_BACKUP = "io.github.fgozxy.await.ACTION_AUTO_BACKUP"

    /** 错过的备份（关机、长时间没开机等）在应用启动时补做，延迟 2 分钟避开启动高峰 */
    private const val CATCH_UP_DELAY_MS = 2 * 60_000L

    /** 两次「补做」之间至少间隔 6 小时，避免服务器配置有问题时每次启动都重试 */
    private const val CATCH_UP_COOLDOWN_MS = 6 * 3600_000L

    /**
     * 按当前设置重排定时备份；未开启或未配置时取消闹钟。
     * @param allowCatchUp 是否允许「已错过」的备份尽快补做（闹钟自身触发后重排时须为 false，避免失败后反复重试）
     */
    fun reschedule(context: Context, allowCatchUp: Boolean = true) {
        val am = context.getSystemService(AlarmManager::class.java) ?: return
        val prefs = BackupSettings.load(context)
        val pi = pendingIntent(context)

        if (!prefs.autoEnabled || !prefs.webdav.isValid) {
            am.cancel(pi)
            return
        }
        val now = LocalDateTime.now()
        val triggerAt = if (allowCatchUp && isOverdue(prefs, now)) {
            System.currentTimeMillis() + CATCH_UP_DELAY_MS
        } else {
            nextTriggerMillis(prefs, now)
        }
        am.setAndAllowWhileIdle(AlarmManager.RTC_WAKEUP, triggerAt, pi)
    }

    fun cancel(context: Context) {
        context.getSystemService(AlarmManager::class.java)?.cancel(pendingIntent(context))
    }

    /** 下一次自动备份的时间戳 */
    fun nextTriggerMillis(prefs: BackupSettings.Prefs, now: LocalDateTime = LocalDateTime.now()): Long {
        val interval = prefs.intervalDays.coerceAtLeast(1).toLong()
        var dt = now.toLocalDate().atTime(prefs.hour, prefs.minute)
        if (!dt.isAfter(now)) dt = dt.plusDays(1)
        if (prefs.lastBackupAt > 0) {
            val due = toLocal(prefs.lastBackupAt).plusDays(interval)
            while (dt.isBefore(due)) dt = dt.plusDays(1)
        }
        return dt.atZone(ZoneId.systemDefault()).toInstant().toEpochMilli()
    }

    /** 下一次自动备份的展示文本；未开启返回 null */
    fun nextTriggerText(prefs: BackupSettings.Prefs): String? {
        if (!prefs.autoEnabled || !prefs.webdav.isValid) return null
        val t = toLocal(nextTriggerMillis(prefs))
        return "%04d-%02d-%02d %02d:%02d".format(t.year, t.monthValue, t.dayOfMonth, t.hour, t.minute)
    }

    /** 上次成功备份已超出一个周期 + 1 天宽限，且距上次尝试已过冷却期 */
    private fun isOverdue(prefs: BackupSettings.Prefs, now: LocalDateTime): Boolean {
        if (prefs.lastBackupAt <= 0L) return false
        if (System.currentTimeMillis() - prefs.lastAttemptAt < CATCH_UP_COOLDOWN_MS) return false
        val deadline = toLocal(prefs.lastBackupAt).plusDays(prefs.intervalDays + 1L)
        return now.isAfter(deadline)
    }

    private fun toLocal(millis: Long): LocalDateTime =
        LocalDateTime.ofInstant(Instant.ofEpochMilli(millis), ZoneId.systemDefault())

    private fun pendingIntent(context: Context): PendingIntent = PendingIntent.getBroadcast(
        context,
        REQUEST_CODE,
        Intent(context, BackupAlarmReceiver::class.java).setAction(ACTION_BACKUP),
        PendingIntent.FLAG_UPDATE_CURRENT or PendingIntent.FLAG_IMMUTABLE
    )
}

/** 定时备份闹钟入口：后台执行一次备份，然后排下一次 */
class BackupAlarmReceiver : BroadcastReceiver() {

    override fun onReceive(context: Context, intent: Intent) {
        val app = context.applicationContext
        val pending = goAsync()
        Thread {
            try {
                val result = BackupService.backupNow(app, manual = false)
                result.onFailure { NotificationHelper.showBackupFailed(app, it.message ?: "未知原因") }
            } finally {
                // 失败也照常排下一次，但不走「补做」分支，避免短周期重试
                BackupScheduler.reschedule(app, allowCatchUp = false)
                pending.finish()
            }
        }.start()
    }
}
