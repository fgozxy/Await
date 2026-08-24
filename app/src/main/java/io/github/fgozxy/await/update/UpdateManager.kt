package io.github.fgozxy.await.update

import android.content.Context
import android.content.Intent
import android.content.pm.PackageInfo
import android.content.pm.PackageManager
import android.content.pm.Signature
import android.net.Uri
import android.os.Build
import android.provider.Settings
import android.widget.Toast
import androidx.core.content.FileProvider
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.ensureActive
import kotlinx.coroutines.withContext
import org.json.JSONObject
import java.io.File
import java.io.FileOutputStream
import java.net.HttpURLConnection
import java.net.URL
import java.security.MessageDigest
import kotlin.coroutines.coroutineContext

/**
 * 应用内更新：检查 GitHub 最新 Release → 下载 APK → 拉起系统安装器。
 * 仅使用系统内置能力（HttpURLConnection / org.json），零额外依赖。
 *
 * 实现要点（历史上「卡住无法更新」的根因都在这里）：
 *  - 不再使用 DownloadManager：部分 ROM 会冻结/停用下载服务，enqueue 后状态永远停在 PENDING，
 *    轮询循环没有超时就会无限等待；改为自己走 HttpURLConnection，读写都有超时。
 *  - 全流程可取消：下载循环每轮检查协程状态，用户点「取消」即刻中断。
 *  - 安装 URI 走 FileProvider：旧实现拼的 content://downloads/all_downloads/<id> 需要
 *    ACCESS_ALL_DOWNLOADS 签名权限，普通应用无权授予给安装器，必然安装失败。
 *  - 下载完成后校验 ZIP magic，避免把 HTML 错误页当成 APK 交给安装器。
 *  - 直连不通时自动回退到镜像（见 [SOURCES]）：部分网络环境下 api.github.com 通、
 *    github.com 不通，直连下载会卡在 connect 超时。
 *  - 安装前比对签名（见 [signatureMatches]）：走镜像意味着安装包经过第三方之手，
 *    必须确认它和已安装的应用是同一把钥匙签的，否则一律拒绝。
 */
object UpdateManager {

    private const val API_LATEST = "https://api.github.com/repos/fgozxy/Await/releases/latest"

    /**
     * 取包地址的候选前缀，按顺序尝试，空串代表直连 GitHub。
     *
     * gh-proxy 同时能代理 api.github.com 和 release 资产；ghfast 只能代理资产
     * （对 API 返回 403），所以它排在后面，只在下载阶段真正派上用场。
     */
    private val SOURCES = listOf("", "https://gh-proxy.com/", "https://ghfast.top/")
    private const val UA = "Await-Android-Updater"
    private const val CONNECT_TIMEOUT = 15_000
    private const val READ_TIMEOUT = 30_000
    private const val MAX_REDIRECTS = 5

    /** 进度回调约定：-1 表示服务器未给出总长度（不确定进度），0~100 为百分比 */
    const val PROGRESS_INDETERMINATE = -1

    data class UpdateInfo(
        val tagName: String,     // 如 "v1.0.6"
        val versionName: String, // 如 "1.0.6"
        val notes: String,
        val apkUrl: String,
        val sizeBytes: Long = 0L
    ) {
        /** 「12.3 MB」；未知大小返回空串 */
        fun sizeText(): String =
            if (sizeBytes <= 0) "" else "%.1f MB".format(sizeBytes / 1024.0 / 1024.0)
    }

    /** 当前应用版本名 */
    fun currentVersion(context: Context): String = runCatching {
        context.packageManager.getPackageInfo(context.packageName, 0).versionName ?: ""
    }.getOrDefault("")

    /** 查询最新版本；无更新返回 null。直连失败时依次回退到镜像 */
    suspend fun checkLatest(currentVersion: String): Result<UpdateInfo?> = withContext(Dispatchers.IO) {
        var last: Throwable? = null
        var found: UpdateInfo? = null
        for (prefix in SOURCES) {
            coroutineContext.ensureActive()
            val r = runCatching { fetchLatest(prefix + API_LATEST) }
            r.getOrNull()?.let { found = it; last = null }
            if (found != null) break
            last = r.exceptionOrNull()
            if (last is kotlinx.coroutines.CancellationException) throw last!!
        }
        val info = found ?: return@withContext Result.failure(last ?: IllegalStateException("检查更新失败"))
        Result.success(if (isNewer(info.versionName, currentVersion)) info else null)
    }

    private fun fetchLatest(api: String): UpdateInfo {
        return run {
            val conn = open(api, followRedirects = true)
            conn.setRequestProperty("Accept", "application/vnd.github+json")
            try {
                when (val code = conn.responseCode) {
                    200 -> Unit
                    403, 429 -> error("GitHub 接口限流，请稍后再试")
                    404 -> error("仓库暂无发布版本")
                    else -> error("HTTP $code")
                }
                val body = conn.inputStream.bufferedReader().readText()
                val json = JSONObject(body)
                val tag = json.optString("tag_name")
                if (tag.isBlank()) error("返回数据异常")
                val notes = json.optString("body")
                val asset = json.optJSONArray("assets")?.let { assets ->
                    (0 until assets.length())
                        .map { assets.getJSONObject(it) }
                        .firstOrNull { it.optString("name").endsWith(".apk", true) }
                } ?: error("Release 中没有 APK 资产")
                val apkUrl = asset.optString("browser_download_url")
                if (apkUrl.isBlank()) error("APK 下载地址为空")
                UpdateInfo(tag, tag.trimStart('v', 'V'), notes, apkUrl, asset.optLong("size", 0L))
            } finally {
                conn.disconnect()
            }
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
     * 下载 APK 到应用私有目录，边下边回调进度（0~99，或 [PROGRESS_INDETERMINATE]）。
     * 协程被取消时立即停止并清理临时文件。
     */
    suspend fun downloadApk(
        context: Context,
        info: UpdateInfo,
        onProgress: (Int) -> Unit
    ): Result<File> = withContext(Dispatchers.IO) {
        runCatching {
            val dir = File(context.filesDir, "updates")
            if (!dir.exists() && !dir.mkdirs()) error("无法创建下载目录")
            // 清掉历史残留，避免占用空间和装到旧包
            dir.listFiles()?.forEach { it.delete() }

            val target = File(dir, "Await-${info.versionName}.apk")
            val part = File(dir, "${target.name}.part")

            // 直连 → 镜像依次尝试，任一成功即止
            var last: Throwable? = null
            var ok = false
            for (prefix in SOURCES) {
                coroutineContext.ensureActive()
                part.delete()
                val r = runCatching { fetchTo(prefix + info.apkUrl, part, info.sizeBytes, onProgress) }
                if (r.isSuccess) { ok = true; last = null; break }
                last = r.exceptionOrNull()
                if (last is kotlinx.coroutines.CancellationException) throw last
            }
            if (!ok) {
                part.delete()
                throw last ?: IllegalStateException("下载失败")
            }

            target.delete()
            if (!part.renameTo(target)) {
                part.delete()
                error("安装包写入失败")
            }
            // 走过镜像的包必须验签：确认它和已安装的应用是同一把钥匙签的
            if (!signatureMatches(context, target)) {
                target.delete()
                error("安装包签名与当前应用不一致，已阻止安装")
            }
            onProgress(100)
            target
        }.onFailure {
            // runCatching 会吞掉取消异常，这里原样抛出，让调用方的取消语义保持正确
            if (it is kotlinx.coroutines.CancellationException) throw it
        }
    }

    /** 从单个地址下载到 [part]；失败抛异常，由调用方决定要不要换下一个源 */
    private suspend fun fetchTo(
        url0: String,
        part: File,
        expectedSize: Long,
        onProgress: (Int) -> Unit
    ) {
        run {
            var url = url0
            var conn = open(url)
            var redirects = 0
            while (true) {
                val code = conn.responseCode
                if (code in 300..399) {
                    val next = conn.getHeaderField("Location")
                    conn.disconnect()
                    if (next.isNullOrBlank()) error("下载地址重定向异常")
                    if (++redirects > MAX_REDIRECTS) error("下载地址重定向过多")
                    url = URL(URL(url), next).toString()
                    conn = open(url)
                    continue
                }
                if (code != 200) {
                    conn.disconnect()
                    error("下载失败（HTTP $code）")
                }
                break
            }

            val total = conn.contentLengthLong.takeIf { it > 0 } ?: expectedSize
            try {
                conn.inputStream.use { input ->
                    FileOutputStream(part).use { out ->
                        val buf = ByteArray(64 * 1024)
                        var done = 0L
                        var lastPct = -2
                        while (true) {
                            coroutineContext.ensureActive()   // 支持取消
                            val n = input.read(buf)
                            if (n < 0) break
                            out.write(buf, 0, n)
                            done += n
                            val pct = if (total > 0) {
                                (done * 100 / total).toInt().coerceIn(0, 99)
                            } else PROGRESS_INDETERMINATE
                            if (pct != lastPct) {
                                lastPct = pct
                                onProgress(pct)
                            }
                        }
                        out.flush()
                        out.fd.sync()
                    }
                }
            } catch (t: Throwable) {
                part.delete()
                throw t
            } finally {
                conn.disconnect()
            }

            if (total > 0 && part.length() != total) error("下载不完整，请重试")
            if (!isApk(part)) error("下载到的不是有效安装包")
        }
    }

    /**
     * 校验安装包与当前已安装应用的签名是否一致。
     *
     * 这是走镜像换来的必要代价：安装包经过第三方之手，必须确认它是同一把钥匙签的。
     * Android 本身也拒绝用不同签名覆盖安装，但那样只会抛一个没头没尾的
     * INSTALL_FAILED_UPDATE_INCOMPATIBLE，不如在这里拦下并说清楚原因。
     *
     * 失败策略刻意是「宽进严出」：只有在确实读出了签名且与本机不一致时才拦截，
     * 任何一边读不出来都放行。因为最终的强制力本来就在系统手里——Android 一定会
     * 拒绝用不同签名的包覆盖安装，这里只是想把那个没头没尾的
     * INSTALL_FAILED_UPDATE_INCOMPATIBLE 提前变成一句人话。
     *
     * 反过来如果读不出签名就判失败，一旦某个 ROM 的 getPackageArchiveInfo 解析不了
     * 我们这种纯 v2/v3 签名的包（本项目的 APK 没有 v1 JAR 签名），合法更新就会被
     * 自己堵死，而用户还没法在应用内自救——那个代价比这道校验的收益大得多。
     */
    private fun signatureMatches(context: Context, apk: File): Boolean {
        val pm = context.packageManager
        val mine = signingDigests(
            runCatching { pm.getPackageInfo(context.packageName, SIG_FLAGS) }.getOrNull()
        )
        if (mine.isEmpty()) return true
        val theirs = signingDigests(
            runCatching { pm.getPackageArchiveInfo(apk.absolutePath, SIG_FLAGS) }.getOrNull()
        )
        if (theirs.isEmpty()) return true
        return theirs == mine
    }

    private val SIG_FLAGS: Int
        get() = if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.P) {
            PackageManager.GET_SIGNING_CERTIFICATES
        } else {
            @Suppress("DEPRECATION")
            PackageManager.GET_SIGNATURES
        }

    /** 取签名证书的 SHA-256 指纹集合 */
    private fun signingDigests(info: PackageInfo?): Set<String> {
        if (info == null) return emptySet()
        val sigs: Array<Signature> = if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.P) {
            info.signingInfo?.apkContentsSigners ?: return emptySet()
        } else {
            @Suppress("DEPRECATION")
            info.signatures ?: return emptySet()
        }
        return sigs.mapNotNull { sig ->
            runCatching {
                MessageDigest.getInstance("SHA-256")
                    .digest(sig.toByteArray())
                    .joinToString("") { "%02x".format(it) }
            }.getOrNull()
        }.toSet()
    }

    /** 校验 ZIP magic（APK 本质是 ZIP），拦住 HTML 错误页之类的假包 */
    private fun isApk(file: File): Boolean = runCatching {
        file.inputStream().use { it.read() == 0x50 && it.read() == 0x4B }
    }.getOrDefault(false)

    /** 是否允许安装未知来源应用（Android 8+ 需用户逐应用授权） */
    fun canInstall(context: Context): Boolean =
        Build.VERSION.SDK_INT < 26 || context.packageManager.canRequestPackageInstalls()

    /** 跳转到「允许安装未知应用」授权页 */
    fun gotoInstallPermission(context: Context) {
        Toast.makeText(context, "请先允许 Await 安装应用，然后重新点击「下载并安装」", Toast.LENGTH_LONG).show()
        runCatching {
            context.startActivity(
                Intent(
                    Settings.ACTION_MANAGE_UNKNOWN_APP_SOURCES,
                    Uri.parse("package:${context.packageName}")
                ).addFlags(Intent.FLAG_ACTIVITY_NEW_TASK)
            )
        }.onFailure {
            runCatching {
                context.startActivity(
                    Intent(Settings.ACTION_SECURITY_SETTINGS).addFlags(Intent.FLAG_ACTIVITY_NEW_TASK)
                )
            }
        }
    }

    /** 拉起系统安装器 */
    fun installApk(context: Context, apk: File): Boolean {
        val uri = runCatching {
            FileProvider.getUriForFile(context, "${context.packageName}.fileprovider", apk)
        }.getOrElse {
            Toast.makeText(context, "无法共享安装包：${it.message}", Toast.LENGTH_LONG).show()
            return false
        }
        val intent = Intent(Intent.ACTION_VIEW).apply {
            setDataAndType(uri, "application/vnd.android.package-archive")
            addFlags(Intent.FLAG_GRANT_READ_URI_PERMISSION or Intent.FLAG_ACTIVITY_NEW_TASK)
        }
        return runCatching { context.startActivity(intent); true }
            .getOrElse {
                Toast.makeText(context, "无法启动安装器：${it.message}", Toast.LENGTH_LONG).show()
                false
            }
    }

    private fun open(url: String, followRedirects: Boolean = false): HttpURLConnection =
        (URL(url).openConnection() as HttpURLConnection).apply {
            connectTimeout = CONNECT_TIMEOUT
            readTimeout = READ_TIMEOUT
            // 下载走手动跟随：跨协议跳转（http→https）系统不会自动处理
            instanceFollowRedirects = followRedirects
            setRequestProperty("User-Agent", UA)
            setRequestProperty("Connection", "close")
        }
}
