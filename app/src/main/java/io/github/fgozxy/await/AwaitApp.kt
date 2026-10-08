package io.github.fgozxy.await

import android.app.Application
import io.github.fgozxy.await.backup.BackupScheduler
import io.github.fgozxy.await.notify.LegacyReminders
import io.github.fgozxy.await.sync.SyncCoordinator

class AwaitApp : Application() {
    override fun onCreate() {
        super.onCreate()
        // 移除已下线的通知合并设置；普通日程分组继续保留。
        getSharedPreferences("await_merge", MODE_PRIVATE).edit().remove("groups").apply()
        LegacyReminders.clear(this)
        SyncCoordinator.changed(this)
        BackupScheduler.reschedule(this)
    }
}
