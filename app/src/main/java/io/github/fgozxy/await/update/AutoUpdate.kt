package io.github.fgozxy.await.update

import android.content.Context
import io.github.fgozxy.await.notify.NotificationHelper
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext
import java.io.File

/**
 * 后台静默更新。
 *
 * 关于「静默」的边界，必须说清楚：Android 不允许普通应用无感安装 APK——
 * 只有系统应用（持 INSTALL_PACKAGES 签名级权限）或设备管理员能做到。所以最后
 * 那一步系统安装确认框绕不过去，这里能做的是把它之前的所有步骤都变成无感：
 * 检查、下载全在后台完成，下好了只发一条通知，点一下直接进安装确认页。
 *
 * 触发时机是打开应用时，带 [CHECK_INTERVAL_MS] 冷却，不额外占用闹钟。
 */
object AutoUpdate {

    private const val PREFS = "await_auto_update"
    private const val KEY_ENABLED = "enabled"
    private const val KEY_LAST_CHECK = "last_check_at"
    private const val KEY_READY_VERSION = "ready_version"

    /** 两次检查之间的最小间隔：6 小时 */
    const val CHECK_INTERVAL_MS = 6 * 60 * 60 * 1000L

    fun isEnabled(context: Context): Boolean =
        prefs(context).getBoolean(KEY_ENABLED, true)

    fun setEnabled(context: Context, enabled: Boolean) {
        prefs(context).edit().putBoolean(KEY_ENABLED, enabled).apply()
    }

    /**
     * 后台跑一次「检查 → 下载 → 通知」。
     *
     * 全程不抛异常、不弹任何提示：这是背着用户做的事，失败就悄悄算了，
     * 下次打开应用再试。只有真的下好了才发那一条通知。
     */
    suspend fun runSilently(context: Context) = withContext(Dispatchers.IO) {
        if (!isEnabled(context)) return@withContext
        val sp = prefs(context)
        val now = System.currentTimeMillis()
        val last = sp.getLong(KEY_LAST_CHECK, 0L)
        // 时钟被往回调过（last 在未来）也当作该检查了，否则会一直卡住
        if (last in 1..now && now - last < CHECK_INTERVAL_MS) return@withContext

        val current = UpdateManager.currentVersion(context)
        val result = UpdateManager.checkLatest(current)
        // 请求失败不记时间：网络刚好不通就得等满 6 小时才肯再试，太蠢
        if (result.isFailure) return@withContext
        sp.edit().putLong(KEY_LAST_CHECK, now).apply()
        // 请求成功但没有新版
        val info = result.getOrNull() ?: return@withContext

        // 上次已经下好同一版就别重下了，直接把通知补上
        readyApk(context)?.let { apk ->
            if (sp.getString(KEY_READY_VERSION, null) == info.versionName) {
                NotificationHelper.showUpdateReady(context, info.versionName, apk)
                return@withContext
            }
        }

        val apk = UpdateManager.downloadApk(context, info) { /* 后台下载，不报进度 */ }
            .getOrNull() ?: return@withContext

        sp.edit().putString(KEY_READY_VERSION, info.versionName).apply()
        NotificationHelper.showUpdateReady(context, info.versionName, apk)
    }

    /** 已下载待安装的包；没有则返回 null */
    fun readyApk(context: Context): File? {
        val version = prefs(context).getString(KEY_READY_VERSION, null) ?: return null
        val f = File(File(context.filesDir, "updates"), "Await-$version.apk")
        return f.takeIf { it.isFile && it.length() > 0 }
    }

    /** 装完了（或版本已追平）就把待装记录清掉，免得反复提示 */
    fun clearIfInstalled(context: Context) {
        val ready = prefs(context).getString(KEY_READY_VERSION, null) ?: return
        if (!UpdateManager.isNewer(ready, UpdateManager.currentVersion(context))) {
            prefs(context).edit().remove(KEY_READY_VERSION).apply()
            File(context.filesDir, "updates").listFiles()?.forEach { it.delete() }
        }
    }

    private fun prefs(context: Context) =
        context.getSharedPreferences(PREFS, Context.MODE_PRIVATE)
}
