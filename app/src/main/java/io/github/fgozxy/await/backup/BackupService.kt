package io.github.fgozxy.await.backup

import android.content.Context
import android.net.ConnectivityManager
import android.net.NetworkCapabilities
import io.github.fgozxy.await.data.BackupData
import io.github.fgozxy.await.data.EventStore
import java.time.LocalDateTime
import java.time.format.DateTimeFormatter

/**
 * 备份的执行逻辑：把本地日程打包成 JSON 上传到 WebDAV，或反过来从云端拉回来。
 * 全部是阻塞调用，由调用方（协程 IO 线程 / 广播的后台线程）负责线程切换。
 */
object BackupService {

    /** 固定名字的「最新备份」，恢复时即使无法列目录也总能取到 */
    const val LATEST_NAME = "Await-backup-latest.json"

    private val stampFormatter = DateTimeFormatter.ofPattern("yyyyMMdd-HHmmss")

    /** 带时间戳的历史备份文件名 */
    private fun snapshotName(now: LocalDateTime = LocalDateTime.now()) =
        "Await-backup-${now.format(stampFormatter)}.json"

    /**
     * 执行一次备份。成功返回给用户看的说明文字。
     * @param manual 手动触发时忽略「仅 WLAN」限制
     */
    fun backupNow(context: Context, manual: Boolean): Result<String> {
        val prefs = BackupSettings.load(context)
        val cfg = prefs.webdav
        val result: Result<String> = runCatching {
            if (!cfg.isValid) error("尚未配置 WebDAV 服务器")
            if (!manual && prefs.wifiOnly && !isOnWifi(context)) error("当前不是 WLAN 网络，已跳过")
            if (!isOnline(context)) error("网络不可用")

            val json = BackupData.exportJson(context)
            val bytes = json.toByteArray(Charsets.UTF_8)
            val count = EventStore.load(context).size

            // 目录可能已存在，建目录失败不阻断上传，让 PUT 的报错来说明真正原因
            WebDavClient.ensureDir(cfg)

            val name = snapshotName()
            WebDavClient.put(cfg, name, bytes).getOrThrow()
            // 最新副本失败不算整体失败：历史快照已经传上去了
            WebDavClient.put(cfg, LATEST_NAME, bytes)
            cleanup(cfg, prefs.keepCount)

            "已备份 $count 条日程 · $name"
        }
        BackupSettings.recordResult(
            context,
            ok = result.isSuccess,
            message = result.getOrElse { it.message ?: "备份失败" }
        )
        return result
    }

    /** 云端备份列表（最新在前），不含固定名的最新副本 */
    fun listBackups(context: Context): Result<List<WebDavClient.Entry>> {
        val cfg = BackupSettings.load(context).webdav
        if (!cfg.isValid) return Result.failure(IllegalStateException("尚未配置 WebDAV 服务器"))
        return WebDavClient.list(cfg)
    }

    /**
     * 下载云端某个备份并解析成日程（不落库，交给界面确认导入方式）。
     * name 传 [LATEST_NAME] 即取固定名的最新副本——服务器不支持 PROPFIND 列目录时的兜底路径。
     */
    fun fetchBackup(context: Context, name: String): Result<List<io.github.fgozxy.await.data.Event>> {
        val cfg = BackupSettings.load(context).webdav
        if (!cfg.isValid) return Result.failure(IllegalStateException("尚未配置 WebDAV 服务器"))
        return WebDavClient.get(cfg, name).mapCatching { BackupData.parse(it).getOrThrow() }
    }

    /** 超出保留份数的历史快照从旧到新删除（list 已按时间倒序）；失败静默忽略 */
    private fun cleanup(cfg: WebDavConfig, keep: Int) {
        val all = WebDavClient.list(cfg).getOrNull() ?: return
        all.filter { it.name != LATEST_NAME && it.name.startsWith("Await-backup-") }
            .drop(keep)
            .forEach { WebDavClient.delete(cfg, it.name) }
    }

    private fun isOnline(context: Context): Boolean {
        val cm = context.getSystemService(ConnectivityManager::class.java) ?: return true
        val caps = cm.getNetworkCapabilities(cm.activeNetwork) ?: return false
        return caps.hasCapability(NetworkCapabilities.NET_CAPABILITY_INTERNET)
    }

    private fun isOnWifi(context: Context): Boolean {
        val cm = context.getSystemService(ConnectivityManager::class.java) ?: return true
        val caps = cm.getNetworkCapabilities(cm.activeNetwork) ?: return false
        return caps.hasTransport(NetworkCapabilities.TRANSPORT_WIFI) ||
            caps.hasTransport(NetworkCapabilities.TRANSPORT_ETHERNET)
    }
}
