package io.github.fgozxy.await.update

import android.content.Context
import android.content.Intent
import android.content.pm.PackageInfo
import android.content.pm.PackageManager
import android.net.Uri
import android.os.Build
import android.provider.Settings
import androidx.core.content.FileProvider
import com.google.gson.JsonParser
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.currentCoroutineContext
import kotlinx.coroutines.ensureActive
import kotlinx.coroutines.withContext
import kotlinx.coroutines.sync.Mutex
import kotlinx.coroutines.sync.withLock
import java.io.File
import java.io.IOException
import java.net.HttpURLConnection
import java.net.URL
import java.security.MessageDigest

class UpdateException(message: String) : Exception(message)

object UpdateManager {
    private val downloadLock = Mutex()
    @Suppress("DEPRECATION")
    fun currentVersion(context: Context): String = context.packageManager.getPackageInfo(context.packageName, 0).versionName.orEmpty()

    suspend fun checkLatest(currentVersion: String): ReleaseInfo? = withContext(Dispatchers.IO) {
        val release = try {
            ReleaseInfo.parse(JsonParser.parseString(readText(ReleaseInfo.LATEST_API, 1024 * 1024)).asJsonObject)
        } catch (e: UpdateException) { throw e }
          catch (_: Exception) { throw UpdateException("发布版本信息不完整，请稍后重试") }
        if (ReleaseInfo.isNewer(release.versionName, currentVersion)) release else null
    }

    suspend fun download(context: Context, release: ReleaseInfo, onProgress: (Int) -> Unit): File = withContext(Dispatchers.IO) {
        downloadLock.withLock {
            val folder = File(context.cacheDir, "updates")
            check(folder.isDirectory || folder.mkdirs()) { "无法创建安装包目录" }
            val target = File(folder, release.apkName)
            val part = File(folder, release.apkName + ".part.apk")
            val cancellation = currentCoroutineContext()
            try {
                val checksum = UpdateIntegrity.checksum(readText(release.checksumUrl, 64 * 1024), release.apkName)
                require(release.assetDigest == null || checksum == release.assetDigest) { "发布校验信息不一致，请稍后重试" }
                val connection = open(release.apkUrl)
                try {
                    connection.inputStream.use { input ->
                        part.outputStream().use { output ->
                            UpdateIntegrity.copyVerified(input, output, release.sizeBytes, checksum, onProgress) { cancellation.ensureActive() }
                            output.fd.sync()
                        }
                    }
                } finally { connection.disconnect() }
                cancellation.ensureActive()
                verifyPackage(context, part, release)
                check(part.renameTo(target)) { "无法保存安装包，请重试" }
                onProgress(100)
                target
            } catch (_: IOException) {
                throw UpdateException("下载失败，请检查网络后重试")
            } finally { part.delete() }
        }
    }

    @Suppress("DEPRECATION")
    private fun verifyPackage(context: Context, apk: File, release: ReleaseInfo) {
        val flags = if (Build.VERSION.SDK_INT >= 28) PackageManager.GET_SIGNING_CERTIFICATES else PackageManager.GET_SIGNATURES
        val manager = context.packageManager
        val installed = manager.getPackageInfo(context.packageName, flags)
        val downloaded = manager.getPackageArchiveInfo(apk.absolutePath, flags) ?: error("无法验证安装包，请重新下载")
        require(downloaded.packageName == context.packageName && downloaded.versionName == release.versionName) { "安装包与应用不匹配" }
        fun code(info: PackageInfo): Long = if (Build.VERSION.SDK_INT >= 28) info.longVersionCode else info.versionCode.toLong()
        require(code(downloaded) > code(installed)) { "安装包版本不高于当前版本" }
        fun signatures(info: PackageInfo): Set<String> {
            val certificates = if (Build.VERSION.SDK_INT >= 28) info.signingInfo?.apkContentsSigners else info.signatures
            return certificates.orEmpty().map {
                MessageDigest.getInstance("SHA-256").digest(it.toByteArray()).joinToString("") { byte -> "%02x".format(byte) }
            }.toSet()
        }
        val current = signatures(installed)
        require(current.isNotEmpty() && current == signatures(downloaded)) { "安装包签名与当前应用不一致，无法覆盖更新" }
    }

    fun canInstall(context: Context): Boolean = context.packageManager.canRequestPackageInstalls()

    fun permissionIntent(context: Context): Intent = Intent(Settings.ACTION_MANAGE_UNKNOWN_APP_SOURCES,
        Uri.parse("package:${context.packageName}"))

    fun install(context: Context, apk: File) {
        val uri = FileProvider.getUriForFile(context, "${context.packageName}.fileprovider", apk)
        context.startActivity(Intent(Intent.ACTION_VIEW).setDataAndType(uri, "application/vnd.android.package-archive")
            .addFlags(Intent.FLAG_GRANT_READ_URI_PERMISSION))
    }

    private fun readText(url: String, limit: Int): String {
        val connection = open(url)
        try {
            return connection.inputStream.use {
                val bytes = it.readBytesLimited(limit)
                bytes.toString(Charsets.UTF_8)
            }
        } catch (_: IOException) { throw UpdateException("无法连接 GitHub，请检查网络后重试") }
          finally { connection.disconnect() }
    }

    private fun java.io.InputStream.readBytesLimited(limit: Int): ByteArray {
        val output = java.io.ByteArrayOutputStream()
        val buffer = ByteArray(8192)
        while (true) {
            val count = read(buffer)
            if (count < 0) break
            if (output.size() + count > limit) throw UpdateException("更新信息超过大小限制")
            output.write(buffer, 0, count)
        }
        return output.toByteArray()
    }

    private fun open(initial: String): HttpURLConnection {
        var url = initial
        repeat(6) { redirect ->
            require(ReleaseInfo.trustedDownloadUrl(url)) { "更新下载地址无效" }
            val connection = (URL(url).openConnection() as HttpURLConnection).apply {
                connectTimeout = 15_000
                readTimeout = 30_000
                instanceFollowRedirects = false
                setRequestProperty("User-Agent", "Await-Android-Updater")
                setRequestProperty("Accept", if (url == ReleaseInfo.LATEST_API) "application/vnd.github+json" else "application/octet-stream")
            }
            try {
                val code = connection.responseCode
                if (code in setOf(301, 302, 303, 307, 308)) {
                    val location = connection.getHeaderField("Location") ?: throw UpdateException("下载重定向无效")
                    if (redirect == 5) throw UpdateException("下载重定向过多，请稍后重试")
                    url = URL(URL(url), location).toString()
                    connection.disconnect()
                } else {
                    when (code) {
                        200 -> return connection
                        403, 429 -> throw UpdateException("GitHub 暂时限制请求，请稍后重试")
                        404 -> throw UpdateException("暂无可用的更新文件，请稍后重试")
                        else -> throw UpdateException("检查或下载失败（HTTP $code）")
                    }
                }
            } catch (e: Exception) {
                connection.disconnect()
                if (e is IOException) throw UpdateException("无法连接 GitHub，请检查网络后重试")
                throw e
            }
        }
        throw UpdateException("下载重定向过多")
    }
}
