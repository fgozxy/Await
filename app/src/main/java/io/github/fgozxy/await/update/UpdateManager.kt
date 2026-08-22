package io.github.fgozxy.await.update

import android.app.DownloadManager
import android.content.Context
import android.content.Intent
import android.net.Uri
import android.os.Build
import android.os.Environment
import android.provider.Settings
import android.widget.Toast
import io.github.fgozxy.await.MainActivity
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.delay
import kotlinx.coroutines.withContext
import org.json.JSONObject
import java.net.HttpURLConnection
import java.net.URL

/**
 * 应用内更新：检查 GitHub 最新 Release → 下载 APK → 拉起系统安装器。
 * 仅使用系统内置能力（HttpURLConnection / org.json / DownloadManager），零额外依赖。
 */
object UpdateManager {

    private const val API_LATEST = "https://api.github.com/repos/fgozxy/Await/releases/latest"

    data class UpdateInfo(
        val tagName: String,     // 如 "v1.0.6"
        val versionName: String, // 如 "1.0.6"
        val notes: String,
        val apkUrl: String
    )

    /** 当前应用版本名 */
    fun currentVersion(context: Context): String = runCatching {
        context.packageManager.getPackageInfo(context.packageName, 0).versionName ?: ""
    }.getOrDefault("")

    /** 查询最新版本；无更新返回 null */
    suspend fun checkLatest(currentVersion: String): Result<UpdateInfo?> = withContext(Dispatchers.IO) {
        runCatching {
            val conn = URL(API_LATEST).openConnection() as HttpURLConnection
            conn.connectTimeout = 10_000
            conn.readTimeout = 15_000
            conn.setRequestProperty("Accept", "application/vnd.github+json")
            try {
                if (conn.responseCode != 200) error("HTTP ${conn.responseCode}")
                val body = conn.inputStream.bufferedReader().readText()
                val json = JSONObject(body)
                val tag = json.optString("tag_name")
                val notes = json.optString("body")
                val apkUrl = json.optJSONArray("assets")?.let { assets ->
                    (0 until assets.length())
                        .map { assets.getJSONObject(it) }
                        .firstOrNull { it.optString("name").endsWith(".apk", true) }
                        ?.optString("browser_download_url")
                } ?: error("Release 中没有 APK 资产")
                UpdateInfo(tag, tag.trimStart('v', 'V'), notes, apkUrl)
            } finally {
                conn.disconnect()
            }
        }.map { info ->
            if (isNewer(info.versionName, currentVersion)) info else null
        }
    }

    /** 语义化比较：latest 是否比 current 新 */
    fun isNewer(latest: String, current: String): Boolean {
        val a = latest.split('.').mapNotNull { it.trim().toIntOrNull() }
        val b = current.split('.').mapNotNull { it.trim().toIntOrNull() }
        for (i in 0 until maxOf(a.size, b.size)) {
            val x = a.getOrElse(i) { 0 }
            val y = b.getOrElse(i) { 0 }
            if (x != y) return x > y
        }
        return false
    }

    /**
     * 用 DownloadManager 下载 APK，轮询进度（0~100），成功返回内容 URI。
     */
    suspend fun downloadApk(
        context: Context,
        info: UpdateInfo,
        onProgress: (Int) -> Unit
    ): Result<Uri> = withContext(Dispatchers.IO) {
        runCatching {
            val dm = context.getSystemService(Context.DOWNLOAD_SERVICE) as DownloadManager
            val fileName = "Await-${info.versionName}.apk"
            val request = DownloadManager.Request(Uri.parse(info.apkUrl))
                .setTitle(fileName)
                .setDescription("Await 更新包下载中…")
                .setMimeType("application/vnd.android.package-archive")
                .setNotificationVisibility(DownloadManager.Request.VISIBILITY_VISIBLE_NOTIFY_COMPLETED)
                .setDestinationInExternalPublicDir(Environment.DIRECTORY_DOWNLOADS, fileName)
            val id = dm.enqueue(request)

            while (true) {
                val cursor = dm.query(DownloadManager.Query().setFilterById(id))
                if (cursor == null || !cursor.moveToFirst()) {
                    delay(1000); continue
                }
                val status = cursor.getInt(cursor.getColumnIndexOrThrow(DownloadManager.COLUMN_STATUS))
                when (status) {
                    DownloadManager.STATUS_SUCCESSFUL -> {
                        cursor.close()
                        onProgress(100)
                        // 等价于 getUriForDownloadedId：content://downloads/all_downloads/<id>
                        return@runCatching Uri.parse("content://downloads/all_downloads/$id")
                    }
                    DownloadManager.STATUS_FAILED -> {
                        val reason = cursor.getInt(cursor.getColumnIndexOrThrow(DownloadManager.COLUMN_REASON))
                        cursor.close()
                        error("下载失败（code=$reason）")
                    }
                    else -> {
                        val total = cursor.getLong(cursor.getColumnIndexOrThrow(DownloadManager.COLUMN_TOTAL_SIZE_BYTES))
                        val done = cursor.getLong(cursor.getColumnIndexOrThrow(DownloadManager.COLUMN_BYTES_DOWNLOADED_SO_FAR))
                        if (total > 0) onProgress((done * 100 / total).toInt().coerceIn(0, 99))
                        cursor.close()
                    }
                }
                delay(800)
            }
            @Suppress("UNREACHABLE_CODE") Uri.EMPTY
        }
    }

    /** 是否允许安装未知来源应用（Android 8+ 需用户逐应用授权） */
    fun canInstall(context: Context): Boolean =
        Build.VERSION.SDK_INT < 26 || context.packageManager.canRequestPackageInstalls()

    /** 跳转到「允许安装未知应用」授权页 */
    fun gotoInstallPermission(context: Context) {
        Toast.makeText(context, "请先允许 Await 安装应用，然后重新点击「安装」", Toast.LENGTH_LONG).show()
        runCatching {
            context.startActivity(Intent(
                Settings.ACTION_MANAGE_UNKNOWN_APP_SOURCES,
                Uri.parse("package:${context.packageName}")
            ))
        }.onFailure {
            context.startActivity(Intent(Settings.ACTION_SECURITY_SETTINGS))
        }
    }

    /** 拉起系统安装器 */
    fun installApk(context: Context, apkUri: Uri) {
        val intent = Intent(Intent.ACTION_VIEW).apply {
            setDataAndType(apkUri, "application/vnd.android.package-archive")
            addFlags(Intent.FLAG_GRANT_READ_URI_PERMISSION or Intent.FLAG_ACTIVITY_NEW_TASK)
        }
        runCatching { context.startActivity(intent) }
            .onFailure { Toast.makeText(context, "无法启动安装器，请手动打开下载的 APK", Toast.LENGTH_LONG).show() }
    }
}
