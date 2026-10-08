package io.github.fgozxy.await.ui

import androidx.compose.foundation.layout.*
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.verticalScroll
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.Close
import androidx.compose.material3.*
import androidx.compose.runtime.*
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.text.input.PasswordVisualTransformation
import androidx.compose.ui.unit.dp
import androidx.compose.ui.window.Dialog
import androidx.compose.ui.window.DialogProperties
import com.google.gson.Gson
import io.github.fgozxy.await.notify.NotificationChannel
import io.github.fgozxy.await.sync.*
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext

@OptIn(ExperimentalMaterial3Api::class)
@Composable
fun CloudChannelScreen(channel: NotificationChannel, onDismiss: () -> Unit) {
    val context = LocalContext.current
    val scope = rememberCoroutineScope()
    val config = remember { SyncSettings.load(context) }
    var configured by remember { mutableStateOf(channel.wireName in CloudDeployment.available(context)) }
    var botToken by remember { mutableStateOf("") }
    var chatId by remember { mutableStateOf("") }
    var ntfyUrl by remember { mutableStateOf("") }
    var topic by remember { mutableStateOf("") }
    var token by remember { mutableStateOf("") }
    var clearToken by remember { mutableStateOf(false) }
    var busy by remember { mutableStateOf(false) }
    var result by remember { mutableStateOf("") }

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
            result = e.message.orEmpty()
        } catch (_: Exception) {
            result = "读取渠道状态失败，请检查服务版本后重试"
        } finally {
            busy = false
        }
    }

    fun runTask(test: Boolean) {
        if (!CloudDeployment.canConfigureChannels(context) || SyncSettings.load(context) != config) {
            result = "请先验证云端部署配置"; return
        }
        val body = if (channel == NotificationChannel.TELEGRAM) {
            if (chatId.isBlank() || (!configured && botToken.isBlank())) {
                result = "请填写 Bot Token 和接收会话 Chat ID"; return
            }
            mapOf("botToken" to botToken.trim(), "chatId" to chatId.trim())
        } else {
            if (!SyncSettings.validUrl(ntfyUrl.trim()) || !Regex("[a-zA-Z0-9_-]{1,64}").matches(topic.trim())) {
                result = "请填写 ntfy 的 HTTPS 根地址和有效 Topic（1–64 位字母、数字、下划线或连字符）"; return
            }
            mapOf("url" to ntfyUrl.trim().trimEnd('/'), "topic" to topic.trim(),
                "token" to token.trim(), "clearToken" to clearToken)
        }
        busy = true
        scope.launch {
            try {
                val taskResult = withContext(Dispatchers.IO) {
                    val path = "/v1/channels/${channel.wireName}" + if (test) "/test" else ""
                    val response = ServerClient.request(config, if (test) "POST" else "PUT", path, Gson().toJson(body))
                    if (test) "测试消息已发送，请在 ${channel.label} 中确认收到；填写的参数尚未保存" else {
                        val available = response["channels"].asJsonObject.entrySet()
                            .filter { it.value.asJsonObject["configured"].asBoolean }.map { it.key }.toSet()
                        CloudDeployment.record(context, config, CloudDeployment.Status(available, true))
                        SyncCoordinator.changed(context)
                        "渠道参数已加密保存到服务器。返回通知设置勾选渠道并保存即可开启"
                    }
                }
                if (!test) { botToken = ""; token = ""; configured = true }
                result = taskResult
            } catch (e: ServerException) {
                result = e.message.orEmpty()
            } catch (_: Exception) {
                result = "配置请求失败，请检查服务版本后重试"
            } finally {
                busy = false
            }
        }
    }

    Dialog(onDismissRequest = { if (!busy) onDismiss() }, properties = DialogProperties(usePlatformDefaultWidth = false)) {
        Scaffold(topBar = {
            TopAppBar(title = { Text("配置 ${channel.label}") }, navigationIcon = {
                IconButton(onClick = onDismiss, enabled = !busy) { Icon(Icons.Default.Close, "关闭") }
            })
        }) { padding ->
            Column(Modifier.padding(padding).fillMaxSize().verticalScroll(rememberScrollState()).padding(16.dp),
                verticalArrangement = Arrangement.spacedBy(16.dp)) {
                Text("先测试参数，再保存到云端。保存参数后，返回通知设置勾选渠道即可开启。")
                if (channel == NotificationChannel.TELEGRAM) {
                    Text("在 Telegram 向 @BotFather 发送 /newbot 创建机器人；向机器人发送 /start，然后取得接收会话的 Chat ID。")
                    OutlinedTextField(botToken, { botToken = it }, label = { Text("Bot Token") },
                        visualTransformation = PasswordVisualTransformation(), singleLine = true,
                        enabled = !busy, modifier = Modifier.fillMaxWidth())
                    OutlinedTextField(chatId, { chatId = it }, label = { Text("Chat ID / 群或频道 @username") },
                        singleLine = true, enabled = !busy, modifier = Modifier.fillMaxWidth())
                    if (configured) Text("已有 Token 可以留空以保留；Chat ID 需填写目标会话。服务器不会返回已保存的 Token。")
                } else {
                    Text("在 ntfy 客户端订阅与下方一致的服务器和 Topic；受保护的 Topic 需要客户端有读取权限。")
                    OutlinedTextField(ntfyUrl, { ntfyUrl = it }, label = { Text("ntfy 服务根地址（HTTPS）") },
                        singleLine = true, enabled = !busy, modifier = Modifier.fillMaxWidth())
                    OutlinedTextField(topic, { topic = it }, label = { Text("Topic") },
                        singleLine = true, enabled = !busy, modifier = Modifier.fillMaxWidth())
                    OutlinedTextField(token, { token = it }, label = { Text("发布 Token（可选）") },
                        visualTransformation = PasswordVisualTransformation(), singleLine = true,
                        enabled = !busy && !clearToken, modifier = Modifier.fillMaxWidth())
                    Text("Token 留空会保留服务器上已有的 Token；首次配置且无需认证时可留空。")
                    Row {
                        Checkbox(clearToken, { clearToken = it }, enabled = !busy)
                        Text("清除已有 Token，使用无认证发布")
                    }
                }
                OutlinedButton(onClick = { runTask(true) }, enabled = !busy) { Text("测试填写的参数") }
                Button(onClick = { runTask(false) }, enabled = !busy) { Text("保存到云端") }
                Text("参数通过 HTTPS 传输，凭据在云端加密保存，不写入手机日程备份，也不会回显。",
                    style = MaterialTheme.typography.bodySmall)
                if (busy) LinearProgressIndicator(Modifier.fillMaxWidth())
                if (result.isNotBlank()) Text(result)
            }
        }
    }
}
