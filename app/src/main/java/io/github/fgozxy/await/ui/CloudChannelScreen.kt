package io.github.fgozxy.await.ui

import androidx.compose.foundation.layout.*
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.foundation.text.KeyboardOptions
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.automirrored.outlined.Send
import androidx.compose.material.icons.outlined.KeyOff
import androidx.compose.material3.*
import androidx.compose.runtime.*
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.text.input.KeyboardType
import androidx.compose.ui.text.input.PasswordVisualTransformation
import androidx.compose.ui.unit.dp
import com.google.gson.Gson
import io.github.fgozxy.await.notify.NotificationChannel
import io.github.fgozxy.await.sync.*
import io.github.fgozxy.await.ui.components.*
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext

/** Telegram / ntfy 渠道参数：先测试再保存到云端，凭据不回显。 */
@OptIn(ExperimentalMaterial3Api::class)
@Composable
fun CloudChannelScreen(channel: NotificationChannel) {
    val context = LocalContext.current
    val scope = rememberCoroutineScope()
    val toaster = LocalToaster.current
    val haptics = rememberHaptics()
    val config = remember { SyncSettings.load(context) }
    var configured by remember { mutableStateOf(channel.wireName in CloudDeployment.available(context)) }
    var botToken by remember { mutableStateOf("") }
    var chatId by remember { mutableStateOf("") }
    var ntfyUrl by remember { mutableStateOf("") }
    var topic by remember { mutableStateOf("") }
    var token by remember { mutableStateOf("") }
    var clearToken by remember { mutableStateOf(false) }
    var busy by remember { mutableStateOf(false) }
    var result by remember { mutableStateOf<Pair<Boolean, String>?>(null) }

    LaunchedEffect(channel) {
        busy = true
        try {
            val fields = withContext(Dispatchers.IO) {
                ServerClient.request(config, "GET", "/v1/channels")["channels"].asJsonObject[channel.wireName].asJsonObject
            }
            configured = fields["configured"].asBoolean
            if (channel == NotificationChannel.TELEGRAM) chatId = fields["chatId"].asString
            else { ntfyUrl = fields["url"].asString; topic = fields["topic"].asString }
        } catch (e: ServerException) {
            result = false to e.message.orEmpty()
        } catch (_: Exception) {
            result = false to "读取渠道状态失败，请检查服务版本后重试"
        } finally {
            busy = false
        }
    }

    fun runTask(test: Boolean) {
        if (!CloudDeployment.canConfigureChannels(context) || SyncSettings.load(context) != config) {
            result = false to "请先在「云端服务」中验证连接"; return
        }
        val body = if (channel == NotificationChannel.TELEGRAM) {
            if (chatId.isBlank() || (!configured && botToken.isBlank())) {
                haptics.reject(); result = false to "请填写 Bot Token 和接收会话 Chat ID"; return
            }
            mapOf("botToken" to botToken.trim(), "chatId" to chatId.trim())
        } else {
            if (!SyncSettings.validUrl(ntfyUrl.trim()) || !Regex("[a-zA-Z0-9_-]{1,64}").matches(topic.trim())) {
                haptics.reject()
                result = false to "请填写 ntfy 的 HTTPS 根地址和有效 Topic（1–64 位字母、数字、下划线或连字符）"; return
            }
            mapOf("url" to ntfyUrl.trim().trimEnd('/'), "topic" to topic.trim(),
                "token" to token.trim(), "clearToken" to clearToken)
        }
        busy = true
        result = null
        scope.launch {
            try {
                val taskResult = withContext(Dispatchers.IO) {
                    val path = "/v1/channels/${channel.wireName}" + if (test) "/test" else ""
                    ServerClient.request(config, if (test) "POST" else "PUT", path, Gson().toJson(body))
                    if (test) "测试消息已发送，请在 ${channel.label.removeSuffix("通知")} 中确认收到；参数尚未保存" else {
                        val status = ServerClient.request(config, "GET", "/v1/status")
                        CloudDeployment.record(context, config,
                            CloudDeployment.parse(status, SyncSettings.clientId(context)))
                        SyncCoordinator.changed(context)
                        "参数已加密保存到服务器"
                    }
                }
                if (!test) {
                    botToken = ""; token = ""; configured = true
                    haptics.confirm()
                    toaster.show("${channel.label}参数已保存，在「通知」中打开开关即可启用")
                    Nav.back()
                } else result = true to taskResult
            } catch (e: ServerException) {
                result = false to e.message.orEmpty()
            } catch (_: Exception) {
                result = false to "配置请求失败，请检查服务版本后重试"
            } finally {
                busy = false
            }
        }
    }

    PageScaffold(
        title = channel.label,
        actions = {
            Button(onClick = { runTask(false) }, enabled = !busy, modifier = Modifier.padding(end = 8.dp)) { Text("保存") }
        }
    ) { padding ->
        PageColumn(padding, spacing = 16.dp) {
            if (busy) LinearProgressIndicator(Modifier.fillMaxWidth())
            StatusCard(
                if (channel == NotificationChannel.TELEGRAM)
                    "在 Telegram 向 @BotFather 发送 /newbot 创建机器人；向机器人发送 /start，再取得接收会话的 Chat ID。"
                else "在 ntfy 客户端订阅与下方一致的服务器和 Topic；受保护的 Topic 需要客户端有读取权限。"
            )
            val field = Modifier.fillMaxWidth()
            val shape = RoundedCornerShape(16.dp)
            if (channel == NotificationChannel.TELEGRAM) {
                OutlinedTextField(
                    botToken, { botToken = it }, label = { Text("Bot Token") },
                    visualTransformation = PasswordVisualTransformation(), singleLine = true,
                    supportingText = if (configured) { { Text("已保存过 Token，留空即保留") } } else null,
                    enabled = !busy, shape = shape, modifier = field
                )
                OutlinedTextField(
                    chatId, { chatId = it }, label = { Text("Chat ID / 群或频道 @username") },
                    singleLine = true, enabled = !busy, shape = shape, modifier = field
                )
            } else {
                OutlinedTextField(
                    ntfyUrl, { ntfyUrl = it }, label = { Text("ntfy 服务根地址") },
                    placeholder = { Text("https://ntfy.sh") },
                    keyboardOptions = KeyboardOptions(keyboardType = KeyboardType.Uri),
                    singleLine = true, enabled = !busy, shape = shape, modifier = field
                )
                OutlinedTextField(
                    topic, { topic = it }, label = { Text("Topic") },
                    singleLine = true, enabled = !busy, shape = shape, modifier = field
                )
                OutlinedTextField(
                    token, { token = it }, label = { Text("发布 Token（可选）") },
                    visualTransformation = PasswordVisualTransformation(), singleLine = true,
                    supportingText = { Text("留空会保留服务器上已有的 Token") },
                    enabled = !busy && !clearToken, shape = shape, modifier = field
                )
                PrefGroup {
                    SwitchRow(
                        title = "清除已有 Token",
                        subtitle = "改用无认证发布",
                        icon = Icons.Outlined.KeyOff,
                        checked = clearToken,
                        enabled = !busy,
                        onChange = { clearToken = it }
                    )
                }
            }
            OutlinedButton(onClick = { runTask(true) }, enabled = !busy, modifier = Modifier.fillMaxWidth().height(48.dp)) {
                Icon(Icons.AutoMirrored.Outlined.Send, null, Modifier.size(18.dp))
                Spacer(Modifier.width(8.dp))
                Text("发送测试消息")
            }
            result?.let { (ok, text) -> StatusCard(text, error = !ok) }
            Text(
                "参数通过 HTTPS 传输，凭据在云端加密保存，不写入手机备份，也不会回显。",
                style = MaterialTheme.typography.bodySmall,
                color = MaterialTheme.colorScheme.onSurfaceVariant,
                modifier = Modifier.padding(horizontal = 16.dp)
            )
        }
    }
}
