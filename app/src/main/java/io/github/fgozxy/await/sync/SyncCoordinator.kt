package io.github.fgozxy.await.sync

import android.content.Context
import androidx.work.*
import io.github.fgozxy.await.data.EventStore
import io.github.fgozxy.await.data.MergeStore
import io.github.fgozxy.await.notify.LocalNotifications
import io.github.fgozxy.await.notify.NotificationChannels
import java.time.ZoneId
import java.util.concurrent.TimeUnit

object SyncCoordinator {
    val lock = Any()

    fun changed(context: Context) = synchronized(lock) {
        val sp = SyncSettings.prefs(context)
        val revision = sp.getLong("revision", 0) + 1
        check(sp.edit().putLong("revision", revision).commit())
        LocalNotifications.reschedule(context)
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
        val revision = SyncSettings.prefs(context).getLong("revision", 1).coerceAtLeast(1)
        val events = EventStore.load(context, failOnUnreadable = true)
        revision to SyncPayload.json(SyncSettings.clientId(context), revision,
            ZoneId.systemDefault().id, events, MergeStore.prune(context, events),
            NotificationChannels.remote(NotificationChannels.load(context)))
    }
}

class SyncWorker(context: Context, parameters: WorkerParameters) : Worker(context, parameters) {
    override fun doWork(): Result {
        val config = SyncSettings.load(applicationContext)
        if (!config.isValid) return Result.success()
        if (CloudDeployment.isReady(applicationContext) && !CloudDeployment.canSync(applicationContext)) return Result.success()
        val sp = SyncSettings.prefs(applicationContext)
        return try {
            val (revision, payload) = SyncCoordinator.snapshot(applicationContext)
            val status = ServerClient.checkChannels(config, NotificationChannels.remote(NotificationChannels.load(applicationContext)))
            CloudDeployment.record(applicationContext, config,
                CloudDeployment.parse(status, SyncSettings.clientId(applicationContext)))
            ServerClient.request(config, "PUT", "/v1/schedule", payload)
            synchronized(SyncCoordinator.lock) {
                if (SyncSettings.load(applicationContext) == config) {
                    sp.edit().putLong("synced_revision", revision)
                        .putLong("synced_at", System.currentTimeMillis())
                        .putString("last_error", "").apply()
                }
            }
            Result.success()
        } catch (e: ServerException) {
            sp.edit().putString("last_error", e.message).apply()
            if (e.retryable) Result.retry() else Result.failure()
        } catch (_: Exception) {
            sp.edit().putString("last_error", "本地日程无法读取，已暂停同步以保留服务器日程").apply()
            Result.failure()
        }
    }
}
