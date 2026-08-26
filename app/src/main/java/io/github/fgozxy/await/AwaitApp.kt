package io.github.fgozxy.await

import android.app.Application
import io.github.fgozxy.await.backup.BackupScheduler
import io.github.fgozxy.await.notify.AlarmScheduler
import io.github.fgozxy.await.notify.NotificationHelper
import java.io.File

class AwaitApp : Application() {

    override fun onCreate() {
        super.onCreate()
        NotificationHelper.ensureChannels(this)
        // 应用启动 / 升级后统一恢复一次闹钟，保证与数据一致
        AlarmScheduler.scheduleAll(this)
        // 定时备份同样需要在启动/升级后重排；错过的备份会在这里补做
        BackupScheduler.reschedule(this)
        purgeLegacyUpdateFiles()
    }

    /**
     * 清理 1.5.x 及更早版本的应用内更新残留：下载好的安装包（可能有十几 MB）、
     * 自动更新开关，以及可能还挂在通知栏上的「更新已准备好」。
     * 都是幂等操作，跑空也无所谓。
     */
    private fun purgeLegacyUpdateFiles() {
        runCatching {
            File(filesDir, "updates").takeIf { it.isDirectory }?.let { dir ->
                dir.listFiles()?.forEach { it.delete() }
                dir.delete()
            }
            deleteSharedPreferences("await_auto_update")
            NotificationHelper.cancelLegacyUpdateNotification(this)
        }
    }
}
