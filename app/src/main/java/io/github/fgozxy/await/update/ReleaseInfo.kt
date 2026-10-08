package io.github.fgozxy.await.update

import com.google.gson.JsonObject
import java.net.URI

data class ReleaseInfo(
    val versionName: String,
    val notes: String,
    val apkUrl: String,
    val sizeBytes: Long,
    val checksumUrl: String,
    val assetDigest: String?
) {
    val apkName: String get() = "Await-v$versionName.apk"
    val releaseUrl: String get() = "https://github.com/fgozxy/Await/releases/tag/v$versionName"

    companion object {
        const val LATEST_API = "https://api.github.com/repos/fgozxy/Await/releases/latest"
        const val MAX_APK_BYTES = 64L * 1024 * 1024

        private fun version(value: String): List<Int> {
            require(value.matches(Regex("[0-9]+\\.[0-9]+\\.[0-9]+"))) { "版本信息无效" }
            return value.split('.').map { it.toIntOrNull() ?: error("版本信息无效") }
        }

        fun isNewer(latest: String, current: String): Boolean {
            val a = version(latest)
            val b = version(current)
            for (index in a.indices) if (a[index] != b[index]) return a[index] > b[index]
            return false
        }

        fun parse(json: JsonObject): ReleaseInfo {
            require(json["draft"]?.asBoolean == false && json["prerelease"]?.asBoolean == false) { "暂无正式更新" }
            val tag = json["tag_name"]?.asString.orEmpty()
            require(tag.startsWith('v')) { "版本信息无效" }
            val name = tag.drop(1)
            version(name)
            val assets = json["assets"]?.asJsonArray?.map { it.asJsonObject }.orEmpty()
            fun asset(file: String, maxSize: Long): JsonObject {
                val found = assets.singleOrNull { it["name"]?.asString == file }
                    ?: error("发布版本缺少安装包或校验文件")
                require(found["state"]?.asString == "uploaded" && found["size"]?.asLong in 1..maxSize) { "发布文件无效" }
                require(found["browser_download_url"]?.asString == "https://github.com/fgozxy/Await/releases/download/$tag/$file") {
                    "发布文件地址无效"
                }
                return found
            }
            val apk = asset("Await-v$name.apk", MAX_APK_BYTES)
            val sums = asset("SHA256SUMS.txt", 64 * 1024)
            val digest = apk["digest"]?.takeUnless { it.isJsonNull }?.asString?.let {
                require(it.matches(Regex("sha256:[0-9a-fA-F]{64}"))) { "安装包校验信息无效" }
                it.substringAfter(':').lowercase()
            }
            return ReleaseInfo(name, json["body"]?.takeUnless { it.isJsonNull }?.asString.orEmpty().take(20_000),
                apk["browser_download_url"].asString, apk["size"].asLong, sums["browser_download_url"].asString, digest)
        }

        fun trustedDownloadUrl(url: String): Boolean = runCatching {
            val uri = URI(url)
            uri.scheme == "https" && uri.userInfo == null && uri.port in setOf(-1, 443) && uri.host in setOf(
                "github.com", "api.github.com", "release-assets.githubusercontent.com", "objects.githubusercontent.com"
            )
        }.getOrDefault(false)
    }
}
