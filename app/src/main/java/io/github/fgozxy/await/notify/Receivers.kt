package io.github.fgozxy.await.notify

import android.content.BroadcastReceiver
import android.content.Context
import android.content.Intent
import io.github.fgozxy.await.backup.BackupScheduler
import io.github.fgozxy.await.sync.SyncCoordinator

/** 重启、时区变化和授权后恢复提醒与同步计划。 */
class BootReceiver : BroadcastReceiver() {
    override fun onReceive(context: Context, intent: Intent) {
        if (intent.action in listOf(Intent.ACTION_BOOT_COMPLETED, Intent.ACTION_TIME_CHANGED,
                Intent.ACTION_TIMEZONE_CHANGED, Intent.ACTION_MY_PACKAGE_REPLACED,
                android.app.AlarmManager.ACTION_SCHEDULE_EXACT_ALARM_PERMISSION_STATE_CHANGED)) {
            SyncCoordinator.changed(context)
            BackupScheduler.reschedule(context)
        }
    }
}
