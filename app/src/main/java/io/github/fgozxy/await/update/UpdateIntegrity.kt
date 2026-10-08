package io.github.fgozxy.await.update

import java.io.InputStream
import java.io.OutputStream
import java.security.MessageDigest

object UpdateIntegrity {
    fun checksum(manifest: String, fileName: String): String {
        val matches = manifest.lineSequence().mapNotNull { line ->
            Regex("^([0-9a-fA-F]{64})[ \\t]+\\*?(.+)$").matchEntire(line.trim())
        }.filter { it.groupValues[2] == fileName }.toList()
        require(matches.size == 1) { "安装包校验文件无效" }
        return matches.single().groupValues[1].lowercase()
    }

    /** 验证大小和 SHA-256；在写入下一块数据前检查取消与大小上限。 */
    fun copyVerified(
        input: InputStream, output: OutputStream, expectedSize: Long, expectedHash: String,
        onProgress: (Int) -> Unit, checkCancelled: () -> Unit = {}
    ) {
        require(expectedSize in 1..ReleaseInfo.MAX_APK_BYTES) { "安装包大小无效" }
        val digest = MessageDigest.getInstance("SHA-256")
        val buffer = ByteArray(64 * 1024)
        var done = 0L
        var lastProgress = -1
        while (true) {
            checkCancelled()
            val length = input.read(buffer)
            if (length < 0) break
            done += length
            require(done <= expectedSize) { "安装包大小不匹配，请重试" }
            output.write(buffer, 0, length)
            digest.update(buffer, 0, length)
            val progress = (done * 100 / expectedSize).toInt().coerceAtMost(99)
            if (progress != lastProgress) { onProgress(progress); lastProgress = progress }
        }
        checkCancelled()
        require(done == expectedSize) { "下载不完整，请重试" }
        require(digest.digest().joinToString("") { "%02x".format(it) } == expectedHash) { "安装包校验失败，请重新下载" }
    }
}
