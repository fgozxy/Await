package io.github.fgozxy.await.notify

import android.content.BroadcastReceiver
import android.content.Context
import android.content.Intent
import io.github.fgozxy.await.backup.BackupScheduler
import io.github.fgozxy.await.sync.SyncCoordinator

/** 手机只恢复备份计划并同步日程，提醒由服务器执行。 */
class BootReceiver : BroadcastReceiver() {
    override fun onReceive(context: Context, intent: Intent) {
        if (intent.action in listOf(Intent.ACTION_BOOT_COMPLETED, Intent.ACTION_TIME_CHANGED,
                Intent.ACTION_TIMEZONE_CHANGED, Intent.ACTION_MY_PACKAGE_REPLACED)) {
            SyncCoordinator.changed(context)
            BackupScheduler.reschedule(context)
        }
    }
}
