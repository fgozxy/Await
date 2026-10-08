package io.github.fgozxy.await.backup

import android.content.Context
import android.net.ConnectivityManager
import android.net.NetworkCapabilities
import com.google.gson.JsonObject
import com.google.gson.JsonParser
import io.github.fgozxy.await.data.BackupData
import io.github.fgozxy.await.sync.CloudDeployment
import io.github.fgozxy.await.sync.ServerClient
import io.github.fgozxy.await.sync.SyncCoordinator
import io.github.fgozxy.await.sync.SyncSettings
import java.time.Instant
import java.time.ZoneId
import java.time.format.DateTimeFormatter

/** 复用 Await 云端连接，备份完整日程与分组；调用方负责切换到 IO 线程。 */
object BackupService {
    const val LATEST_NAME = "latest"
    private val uploadLock = Any()

    data class Entry(val id: String, val name: String, val createdAt: Long, val size: Long, val eventCount: Int) {
        fun modifiedText(): String = Instant.ofEpochMilli(createdAt).atZone(ZoneId.systemDefault())
            .format(DateTimeFormatter.ofPattern("yyyy-MM-dd HH:mm:ss"))
        fun sizeText(): String = if (size < 1024) "$size B" else "%.1f KB".format(size / 1024.0)
    }

    internal fun parseEntries(response: JsonObject): List<Entry> = response["backups"].asJsonArray.map {
        val value = it.asJsonObject
        val id = value["id"].asString
        require(Regex("[a-f0-9]{32}").matches(id)) { "备份编号无效" }
        Entry(id, value["name"].asString, value["createdAt"].asLong, value["size"].asLong,
            value["eventCount"].asInt)
    }

    private fun verifiedConfig(context: Context, write: Boolean = false): SyncSettings.Config {
        check(CloudDeployment.isReady(context)) { "请先在云端部署配置中验证连接" }
        val config = SyncSettings.load(context)
        val status = CloudDeployment.parse(ServerClient.request(config, "GET", "/v1/status"),
            SyncSettings.clientId(context), allowBackupRecovery = true)
        CloudDeployment.record(context, config, status)
        check(status.backupsEnabled) { "服务器未开启云端备份，请在 Docker 部署时设置 AWAIT_BACKUP_ENABLED=true" }
        check(!write || status.canSync) { "服务器绑定另一台手机，当前仅可恢复备份；上传需先迁移绑定" }
        return config
    }

    fun backupNow(context: Context, manual: Boolean): Result<String> = synchronized(uploadLock) {
        val prefs = BackupSettings.load(context)
        val result = runCatching {
            if (!manual && prefs.wifiOnly && !isOnWifi(context)) error("当前不是 WLAN 网络，已跳过")
            if (!isOnline(context)) error("网络不可用")
            val config = verifiedConfig(context, write = true)
            val bundle = synchronized(SyncCoordinator.lock) {
                JsonParser.parseString(BackupData.exportJson(context)).asJsonObject
            }
            val body = JsonObject().apply {
                addProperty("clientId", SyncSettings.clientId(context))
                addProperty("keepCount", prefs.keepCount)
                add("backup", bundle)
            }
            val saved = ServerClient.request(config, "POST", "/v1/backups", body.toString())
            "已备份 ${bundle["eventCount"].asInt} 条日程 · ${saved["name"].asString}"
        }
        BackupSettings.recordResult(context, result.isSuccess,
            result.getOrElse { it.message ?: "备份失败" })
        result
    }

    fun listBackups(context: Context): Result<List<Entry>> = runCatching {
        parseEntries(ServerClient.request(verifiedConfig(context), "GET", "/v1/backups"))
    }

    /** 只下载和解析，由界面确认合并或覆盖后才修改本地数据。 */
    fun fetchBackup(context: Context, id: String): Result<BackupData.ImportBundle> = runCatching {
        require(id == LATEST_NAME || Regex("[a-f0-9]{32}").matches(id)) { "备份编号无效" }
        val bundle = ServerClient.request(verifiedConfig(context), "GET", "/v1/backups/$id")
        BackupData.parseBundle(bundle.toString()).getOrThrow()
    }

    private fun isOnline(context: Context): Boolean {
        val cm = context.getSystemService(ConnectivityManager::class.java) ?: return false
        val caps = cm.getNetworkCapabilities(cm.activeNetwork) ?: return false
        return caps.hasCapability(NetworkCapabilities.NET_CAPABILITY_INTERNET)
    }

    private fun isOnWifi(context: Context): Boolean {
        val cm = context.getSystemService(ConnectivityManager::class.java) ?: return false
        val caps = cm.getNetworkCapabilities(cm.activeNetwork) ?: return false
        return caps.hasTransport(NetworkCapabilities.TRANSPORT_WIFI) ||
            caps.hasTransport(NetworkCapabilities.TRANSPORT_ETHERNET)
    }
}
