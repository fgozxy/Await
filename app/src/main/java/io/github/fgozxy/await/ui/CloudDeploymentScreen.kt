package io.github.fgozxy.await.ui

import android.content.ClipData
import android.content.ClipboardManager
import android.content.Context
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
import io.github.fgozxy.await.sync.*
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext

@OptIn(ExperimentalMaterial3Api::class)
@Composable
fun CloudDeploymentScreen(onDismiss: () -> Unit) {
    val context = LocalContext.current
    val scope = rememberCoroutineScope()
    val saved = remember { SyncSettings.load(context) }
    var url by remember { mutableStateOf(saved.url) }
    var apiKey by remember { mutableStateOf(saved.apiKey) }
    var busy by remember { mutableStateOf(false) }
    var result by remember { mutableStateOf("") }
    var guide by remember { mutableStateOf(!saved.isValid) }
    val status = rememberSyncStatus()

    fun connect() {
        val draft = SyncSettings.Config(url.trim().trimEnd('/'), apiKey.trim())
        if (!draft.isValid) { result = "请填写 HTTPS 地址和至少 32 位的服务器访问密钥"; return }
        busy = true
        scope.launch {
            try {
                val checked = withContext(Dispatchers.IO) {
                    val response = ServerClient.checkChannels(draft, emptyList())
                    val deployment = CloudDeployment.parse(response, SyncSettings.clientId(context))
                    synchronized(SyncCoordinator.lock) {
                        SyncSettings.save(context, draft)
                        CloudDeployment.record(context, draft, deployment)
                        SyncSettings.prefs(context).edit().putString("last_error", "").apply()
                        SyncCoordinator.changed(context)
                    }
                    deployment
                }
                result = if (checked.managesChannels) "云端部署已验证并保存，可返回配置 Telegram 和 ntfy" else
                    "服务器连接已验证。此服务器尚不支持手机配置渠道，请升级 Await 云端服务"
            } catch (e: ServerException) {
                result = e.message.orEmpty()
            } catch (_: Exception) {
                result = "验证或保存失败，请检查 Await 服务版本并重试"
            } finally {
                busy = false
            }
        }
    }

    Dialog(onDismissRequest = { if (!busy) onDismiss() }, properties = DialogProperties(usePlatformDefaultWidth = false)) {
        Scaffold(topBar = {
            TopAppBar(title = { Text("云端部署配置") }, navigationIcon = {
                IconButton(onClick = onDismiss, enabled = !busy) { Icon(Icons.Default.Close, "关闭") }
            })
        }) { padding ->
            Column(Modifier.padding(padding).fillMaxSize().verticalScroll(rememberScrollState()).padding(16.dp),
                verticalArrangement = Arrangement.spacedBy(16.dp)) {
                Text("先部署并连接 Await 云端服务，验证通过后即可在手机配置 Telegram 和 ntfy。软件通知可独立使用。")
                OutlinedButton(onClick = { guide = !guide }) { Text(if (guide) "收起部署指引" else "查看部署指引") }
                if (guide) {
                    Text("1. 在服务器安装 Docker，将 Await 项目的 server 目录上传到服务器。")
                    Text("2. 从凭据库向进程环境注入 AWAIT_API_KEY（至少 32 位访问密钥），启动服务。初次部署无需配置 Telegram 或 ntfy。")
                    Text("cd server\ndocker compose up -d --build", style = MaterialTheme.typography.bodySmall)
                    OutlinedButton(onClick = {
                        context.getSystemService(ClipboardManager::class.java).setPrimaryClip(
                            ClipData.newPlainText("Await 部署命令", "cd server\ndocker compose up -d --build"))
                        result = "部署命令已复制"
                    }) { Text("复制部署命令") }
                    Text("3. 通过反向代理提供 HTTPS，将请求转发到 127.0.0.1:8091。")
                    Text("4. 在下方填写 Await 服务的 HTTPS 地址及同一访问密钥，验证连接；返回通知设置填写消息渠道参数。")
                }
                OutlinedTextField(url, { url = it }, label = { Text("Await 云端地址（HTTPS）") },
                    singleLine = true, enabled = !busy, modifier = Modifier.fillMaxWidth())
                OutlinedTextField(apiKey, { apiKey = it }, label = { Text("云端访问密钥") }, singleLine = true,
                    visualTransformation = PasswordVisualTransformation(), enabled = !busy, modifier = Modifier.fillMaxWidth())
                Button(onClick = ::connect, enabled = !busy) { Text("验证连接并保存") }
                Text("会检查服务版本、访问权限和手机绑定。修改地址或密钥后，需要重新验证才会生效。")
                Text(status, color = MaterialTheme.colorScheme.primary)
                if (busy) LinearProgressIndicator(Modifier.fillMaxWidth())
                if (result.isNotBlank()) Text(result)
                Text("手机访问密钥加密保存。更换服务器时需停用旧服务，避免收到旧日程提醒。",
                    style = MaterialTheme.typography.bodySmall)
            }
        }
    }
}
