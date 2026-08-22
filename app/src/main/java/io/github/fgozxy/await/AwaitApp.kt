package io.github.fgozxy.await

import android.app.Application
import io.github.fgozxy.await.backup.BackupScheduler
import io.github.fgozxy.await.notify.AlarmScheduler
import io.github.fgozxy.await.notify.NotificationHelper

class AwaitApp : Application() {

    override fun onCreate() {
        super.onCreate()
        NotificationHelper.ensureChannels(this)
        // 应用启动 / 升级后统一恢复一次闹钟，保证与数据一致
        AlarmScheduler.scheduleAll(this)
        // 定时备份同样需要在启动/升级后重排；错过的备份会在这里补做
        BackupScheduler.reschedule(this)
    }
}
