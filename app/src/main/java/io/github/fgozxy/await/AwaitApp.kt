package io.github.fgozxy.await

import android.app.Application
import io.github.fgozxy.await.backup.BackupScheduler
import io.github.fgozxy.await.notify.LegacyReminders
import io.github.fgozxy.await.sync.SyncCoordinator

class AwaitApp : Application() {
    override fun onCreate() {
        super.onCreate()
        LegacyReminders.clear(this)
        SyncCoordinator.changed(this)
        BackupScheduler.reschedule(this)
    }
}
