package io.github.fgozxy.await.sync

import com.google.gson.JsonObject
import com.google.gson.JsonParser
import java.net.HttpURLConnection
import java.net.URL

class ServerException(val retryable: Boolean, message: String) : Exception(message)

object ServerClient {
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
                    400 -> "日程数据或同步版本被服务器拒绝"
                    502 -> "服务器无法推送 Telegram，请检查 Bot 配置和服务器网络"
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
