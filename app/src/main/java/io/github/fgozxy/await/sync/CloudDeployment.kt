package io.github.fgozxy.await.sync

import android.content.Context
import com.google.gson.JsonObject
import java.security.MessageDigest

/** Connection validation and saved deployment state; credentials remain in SyncSettings. */
object CloudDeployment {
    data class Status(val availableChannels: Set<String>, val managesChannels: Boolean,
        val backupsEnabled: Boolean = false, val canSync: Boolean = true)

    fun parse(status: JsonObject, clientId: String, allowBackupRecovery: Boolean = false): Status {
        if (status["notificationProtocol"]?.asInt != 1) {
            throw ServerException(false, "服务器版本不支持云端通知，请先升级 Await 服务")
        }
        val bound = status["clientId"]?.asString.orEmpty()
        val canSync = bound.isBlank() || bound == clientId
        val backups = status["backupEnabled"]?.asBoolean == true && status["backupProtocol"]?.asInt == 1
        if (!canSync && !(allowBackupRecovery && backups)) {
            throw ServerException(false, "服务器已绑定另一台手机，请先迁移服务端绑定")
        }
        val available = status["availableChannels"]?.asJsonArray?.map { it.asString }?.toSet()
            ?: throw ServerException(false, "服务器未返回通知渠道状态，请升级 Await 服务")
        return Status(available, status["channelConfiguration"]?.asBoolean == true, backups, canSync)
    }

    fun credentialId(config: SyncSettings.Config): String = MessageDigest.getInstance("SHA-256")
        .digest((config.url.trimEnd('/') + "\n" + config.apiKey).toByteArray(Charsets.UTF_8))
        .joinToString("") { "%02x".format(it) }

    fun matches(config: SyncSettings.Config, savedId: String): Boolean =
        config.isValid && savedId.isNotBlank() && credentialId(config) == savedId

    fun isReady(context: Context): Boolean = matches(SyncSettings.load(context),
        SyncSettings.prefs(context).getString("deployment_verified", "").orEmpty())

    fun canSync(context: Context): Boolean = isReady(context) &&
        SyncSettings.prefs(context).getBoolean("deployment_can_sync", true)

    fun backupsEnabled(context: Context): Boolean = isReady(context) &&
        SyncSettings.prefs(context).getBoolean("deployment_backups", false)

    fun canWriteBackup(context: Context): Boolean = backupsEnabled(context) && canSync(context)

    fun available(context: Context): Set<String> = if (canSync(context))
        SyncSettings.prefs(context).getStringSet("deployment_channels", emptySet()).orEmpty().toSet()
        else emptySet()

    fun canConfigureChannels(context: Context): Boolean = canSync(context) &&
        SyncSettings.prefs(context).getBoolean("deployment_channel_config", false)

    fun record(context: Context, config: SyncSettings.Config, status: Status) {
        synchronized(SyncCoordinator.lock) {
            if (SyncSettings.load(context) != config) return
            check(SyncSettings.prefs(context).edit()
                .putString("deployment_verified", credentialId(config))
                .putStringSet("deployment_channels", status.availableChannels)
                .putBoolean("deployment_channel_config", status.managesChannels)
                .putBoolean("deployment_backups", status.backupsEnabled)
                .putBoolean("deployment_can_sync", status.canSync)
                .putLong("deployment_checked_at", System.currentTimeMillis()).commit())
        }
    }
}
