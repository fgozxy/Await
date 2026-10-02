package io.github.fgozxy.await.ui

import androidx.compose.foundation.layout.*
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.verticalScroll
import androidx.compose.foundation.text.selection.SelectionContainer
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

@Composable
fun rememberSyncStatus(): String {
    val context = LocalContext.current
    val sp = remember { SyncSettings.prefs(context) }
    var revision by remember { mutableIntStateOf(0) }
    DisposableEffect(sp) {
        val listener = android.content.SharedPreferences.OnSharedPreferenceChangeListener { _, _ -> revision++ }
        sp.registerOnSharedPreferenceChangeListener(listener)
        onDispose { sp.unregisterOnSharedPreferenceChangeListener(listener) }
    }
    return remember(revision) {
        when {
            sp.getString("url", "").isNullOrEmpty() -> "尚未配置 Telegram 服务器"
            !sp.getString("last_error", "").isNullOrEmpty() -> sp.getString("last_error", "").orEmpty()
            sp.getLong("revision", 0) > sp.getLong("synced_revision", 0) -> "日程等待同步；服务器仍按上次同步的日程提醒"
            else -> "日程已同步，手机关机后服务器仍会推送"
        }
    }
}

@OptIn(ExperimentalMaterial3Api::class)
@Composable
fun TelegramScreen(onDismiss: () -> Unit) {
    val context = LocalContext.current
    val scope = rememberCoroutineScope()
    val saved = remember { SyncSettings.load(context) }
    var url by remember { mutableStateOf(saved.url) }
    var apiKey by remember { mutableStateOf(saved.apiKey) }
    var busy by remember { mutableStateOf(false) }
    var result by remember { mutableStateOf("") }
    val syncStatus = rememberSyncStatus()

    fun runTask(test: Boolean) {
        if (busy) return
        val config = SyncSettings.Config(url.trim().trimEnd('/'), apiKey.trim())
        if (!config.isValid) {
            result = "请填写 HTTPS 服务器地址和至少 32 位的访问密钥"
            return
        }
        busy = true
        scope.launch {
            result = withContext(Dispatchers.IO) {
                try {
                    if (test) {
                        ServerClient.request(config, "POST", "/v1/test", "{}")
                        "测试推送已发送，请在 Telegram 中确认收到"
                    } else {
                        synchronized(SyncCoordinator.lock) {
                            SyncSettings.save(context, config)
                            SyncCoordinator.changed(context)
                        }
                        "配置已保存，日程将自动同步"
                    }
                } catch (e: ServerException) {
                    e.message.orEmpty()
                } catch (_: Exception) {
                    "保存失败，请稍后重试"
                }
            }
            busy = false
        }
    }

    Dialog(onDismissRequest = { if (!busy) onDismiss() }, properties = DialogProperties(usePlatformDefaultWidth = false)) {
        Scaffold(topBar = {
            TopAppBar(title = { Text("Telegram 服务器") }, navigationIcon = {
                IconButton(onClick = onDismiss, enabled = !busy) { Icon(Icons.Default.Close, "关闭") }
            })
        }) { padding ->
            Column(Modifier.padding(padding).fillMaxSize().verticalScroll(rememberScrollState()).padding(16.dp),
                verticalArrangement = Arrangement.spacedBy(16.dp)) {
                Text("服务器定时推送到 Telegram，手机关机也能提醒。新增、修改、删除和导入的日程需同步成功后才会在服务器生效。")
                OutlinedTextField(url, { url = it }, label = { Text("服务器地址（HTTPS）") },
                    singleLine = true, enabled = !busy, modifier = Modifier.fillMaxWidth())
                OutlinedTextField(apiKey, { apiKey = it }, label = { Text("服务器访问密钥") },
                    singleLine = true, visualTransformation = PasswordVisualTransformation(),
                    enabled = !busy, modifier = Modifier.fillMaxWidth())
                Text("Bot Token 和 Chat ID 在服务器配置；手机只保存加密的服务器访问密钥。")
                Button(onClick = { runTask(false) }, enabled = !busy) { Text("保存并同步日程") }
                OutlinedButton(onClick = { runTask(true) }, enabled = !busy) { Text("发送 Telegram 测试推送") }
                OutlinedButton(onClick = {
                    if (busy) return@OutlinedButton
                    val config = SyncSettings.Config(url.trim().trimEnd('/'), apiKey.trim())
                    if (!config.isValid) { result = "请先填写有效的服务器地址和访问密钥"; return@OutlinedButton }
                    busy = true
                    scope.launch {
                        result = withContext(Dispatchers.IO) {
                            try {
                                val status = ServerClient.request(config, "GET", "/v1/status")
                                "服务器日程：${status["eventCount"].asInt} 条\n待发送：${status["pending"].asInt} 条，失败：${status["failed"].asInt} 条" +
                                    status["lastError"].asString.takeIf { it.isNotBlank() }?.let { "\n$it" }.orEmpty()
                            } catch (e: ServerException) { e.message.orEmpty() }
                            catch (_: Exception) { "服务器返回的状态格式不正确" }
                        }
                        busy = false
                    }
                }, enabled = !busy) { Text("查看服务器状态") }
                Text(syncStatus, color = MaterialTheme.colorScheme.primary)
                if (busy) LinearProgressIndicator(Modifier.fillMaxWidth())
                if (result.isNotBlank()) Text(result)
                Text("同一服务器绑定一台手机。更换手机需先迁移服务端绑定；更换服务器后，应停用旧服务器以免继续收到旧日程提醒。",
                    style = MaterialTheme.typography.bodySmall)
                SelectionContainer {
                    Text("本机安装 ID：${SyncSettings.clientId(context)}", style = MaterialTheme.typography.bodySmall)
                }
            }
        }
    }
}
