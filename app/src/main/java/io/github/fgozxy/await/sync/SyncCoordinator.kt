package io.github.fgozxy.await.sync

import android.content.Context
import androidx.work.*
import io.github.fgozxy.await.data.EventStore
import io.github.fgozxy.await.notify.LocalNotifications
import io.github.fgozxy.await.notify.NotificationChannels
import io.github.fgozxy.await.notify.ReminderSettings
import java.time.ZoneId
import java.util.concurrent.TimeUnit

object SyncCoordinator {
    val lock = Any()
    // Network requests must not hold the lock used by UI edits and local alarms.
    internal val uploadLock = Any()
    private const val PERIODIC_WORK = "await-server-periodic-sync"

    fun start(context: Context) {
        LocalNotifications.reschedule(context)
        schedule(context)
    }

    private fun schedule(context: Context) {
        val manager = WorkManager.getInstance(context)
        if (!SyncSettings.load(context).isValid) {
            manager.cancelUniqueWork(PERIODIC_WORK)
            return
        }
        val work = PeriodicWorkRequestBuilder<SyncWorker>(15, TimeUnit.MINUTES)
            .setInputData(workDataOf("periodic" to true))
            .setConstraints(Constraints.Builder().setRequiredNetworkType(NetworkType.CONNECTED).build())
            .setBackoffCriteria(BackoffPolicy.EXPONENTIAL, 30, TimeUnit.SECONDS).build()
        // Opening the app must preserve the existing interval, without marking data dirty.
        manager.enqueueUniquePeriodicWork(PERIODIC_WORK, ExistingPeriodicWorkPolicy.KEEP, work)
    }

    fun changed(context: Context) = synchronized(lock) {
        val sp = SyncSettings.prefs(context)
        val revision = sp.getLong("revision", 0) + 1
        check(sp.edit().putLong("revision", revision).commit())
        LocalNotifications.reschedule(context)
        schedule(context)
        enqueue(context)
    }

    fun enqueue(context: Context) {
        if (!SyncSettings.load(context).isValid) return
        val work = OneTimeWorkRequestBuilder<SyncWorker>()
            .setConstraints(Constraints.Builder().setRequiredNetworkType(NetworkType.CONNECTED).build())
            .setBackoffCriteria(BackoffPolicy.EXPONENTIAL, 30, TimeUnit.SECONDS).build()
        // Read the latest snapshot when each task runs; serialize uploads to avoid stale overwrites.
        WorkManager.getInstance(context).enqueueUniqueWork(
            "await-server-sync", ExistingWorkPolicy.APPEND_OR_REPLACE, work)
    }

    fun snapshot(context: Context): Pair<Long, String> = synchronized(lock) {
        val sp = SyncSettings.prefs(context)
        val events = EventStore.load(context, failOnUnreadable = true)
        val clientId = SyncSettings.clientId(context)
        val timezone = ZoneId.systemDefault().id
        val channels = NotificationChannels.remote(NotificationChannels.load(context))
        val time = ReminderSettings.load(context).localTime()
        val fingerprint = SyncRevision.fingerprint(SyncPayload.json(clientId, 0, timezone, events, channels, time))
        val currentRevision = sp.getLong("revision", 0)
        val revision = SyncRevision.resolve(currentRevision, sp.getLong("synced_revision", 0),
            sp.getString("payload_fingerprint", null), fingerprint,
            sp.getLong("payload_revision", currentRevision))
        // Detect payload changes after upgrades or timezone changes, including legacy installs.
        check(sp.edit().putLong("revision", revision).putString("payload_fingerprint", fingerprint)
            .putLong("payload_revision", revision).commit())
        revision to SyncPayload.json(clientId, revision, timezone, events, channels, time)
    }
}

class SyncWorker(context: Context, parameters: WorkerParameters) : Worker(context, parameters) {
    override fun doWork(): Result = synchronized(SyncCoordinator.uploadLock) {
        sync()
    }

    private fun sync(): Result {
        if (isStopped) return Result.success()
        val config = SyncSettings.load(applicationContext)
        if (!config.isValid) return Result.success()
        if (CloudDeployment.isReady(applicationContext) && !CloudDeployment.canSync(applicationContext)) return Result.success()
        val sp = SyncSettings.prefs(applicationContext)
        val (revision, payload) = try {
            SyncCoordinator.snapshot(applicationContext)
        } catch (_: Exception) {
            recordError(config, "本地日程无法读取，已暂停同步以保留服务器日程", false)
            return Result.failure()
        }
        return try {
            // Legacy queued tasks and rapid edits all upload the latest snapshot only once.
            if (!inputData.getBoolean("periodic", false) &&
                revision <= sp.getLong("synced_revision", 0) && sp.getString("last_error", "").isNullOrEmpty()) {
                return Result.success()
            }
            val status = ServerClient.checkChannels(config, NotificationChannels.remote(NotificationChannels.load(applicationContext)))
            CloudDeployment.record(applicationContext, config,
                CloudDeployment.parse(status, SyncSettings.clientId(applicationContext)))
            ServerClient.request(config, "PUT", "/v1/schedule", payload)
            synchronized(SyncCoordinator.lock) {
                if (SyncSettings.load(applicationContext) == config) {
                    sp.edit().putLong("synced_revision", revision)
                        .putLong("synced_at", System.currentTimeMillis())
                        .putString("last_error", "").putBoolean("retry_pending", false).apply()
                }
            }
            Result.success()
        } catch (e: ServerException) {
            recordError(config, e.message.orEmpty(), e.retryable)
            if (e.retryable) Result.retry() else Result.failure()
        } catch (_: Exception) {
            recordError(config, "云端同步暂未完成", true)
            Result.retry()
        }
    }

    private fun recordError(config: SyncSettings.Config, message: String, retryable: Boolean) =
        synchronized(SyncCoordinator.lock) {
            if (SyncSettings.load(applicationContext) == config) {
                SyncSettings.prefs(applicationContext).edit().putString("last_error", message)
                    .putBoolean("retry_pending", retryable).apply()
            }
        }
}
