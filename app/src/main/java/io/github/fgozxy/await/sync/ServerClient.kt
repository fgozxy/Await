package io.github.fgozxy.await.sync

import com.google.gson.JsonObject
import com.google.gson.JsonParser
import java.net.HttpURLConnection
import java.net.URL

class ServerException(val retryable: Boolean, message: String) : Exception(message)

object ServerClient {
    fun checkChannels(config: SyncSettings.Config, channels: List<String>): JsonObject {
        val status = request(config, "GET", "/v1/status")
        if (status["notificationProtocol"]?.asInt != 1) {
            throw ServerException(false, "请先升级通知服务器，再同步渠道设置")
        }
        val available = status["availableChannels"]?.asJsonArray?.map { it.asString }.orEmpty()
        if (!available.containsAll(channels)) {
            throw ServerException(false, "服务器尚未配置所选通知渠道，请先配置 Telegram 或 ntfy")
        }
        return status
    }

    fun request(config: SyncSettings.Config, method: String, path: String, body: String? = null): JsonObject {
        require(config.isValid)
        var connection: HttpURLConnection? = null
        try {
            connection = URL(config.url.trimEnd('/') + path).openConnection() as HttpURLConnection
            connection.apply {
                requestMethod = method
                connectTimeout = 15_000
                readTimeout = 30_000
                instanceFollowRedirects = false
                setRequestProperty("Authorization", "Bearer ${config.apiKey}")
                setRequestProperty("Accept", "application/json")
                if (body != null) {
                    doOutput = true
                    setRequestProperty("Content-Type", "application/json; charset=utf-8")
                    outputStream.use { it.write(body.toByteArray(Charsets.UTF_8)) }
                }
            }
            val code = connection.responseCode
            if (code !in 200..299) {
                val message = when (code) {
                    401, 403 -> "服务器访问密钥不正确"
                    409 -> "服务器已绑定另一台手机，需先迁移服务端数据"
                    400 -> "服务器拒绝日程、备份、渠道设置或同步版本，请检查服务端配置"
                    413 -> "上传内容超过服务器允许的大小"
                    502 -> "服务器无法推送，请检查通知渠道配置和服务器网络"
                    404 -> if (path.startsWith("/v1/backups")) "云端备份不存在，或服务器未开启备份功能" else
                        "服务器不支持此配置功能，请先升级 Await 云端服务"
                    else -> "服务器请求失败（HTTP $code）"
                }
                throw ServerException(code == 429 || code >= 500, message)
            }
            val response = connection.inputStream.bufferedReader().use { it.readText() }
            return JsonParser.parseString(response).asJsonObject
        } catch (e: ServerException) {
            throw e
        } catch (_: Exception) {
            // Raw network exceptions can contain URLs; never display or log credentials.
            throw ServerException(true, "无法连接服务器，请检查地址、证书和网络")
        } finally {
            connection?.disconnect()
        }
    }
}
