package io.github.fgozxy.await.ui

import android.content.ClipData
import android.content.ClipboardManager
import androidx.compose.animation.AnimatedVisibility
import androidx.compose.foundation.layout.*
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.foundation.text.KeyboardOptions
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.outlined.Backup
import androidx.compose.material.icons.outlined.ContentCopy
import androidx.compose.material.icons.outlined.ExpandLess
import androidx.compose.material.icons.outlined.ExpandMore
import androidx.compose.material.icons.automirrored.outlined.MenuBook
import androidx.compose.material.icons.outlined.Visibility
import androidx.compose.material.icons.outlined.VisibilityOff
import androidx.compose.material3.*
import androidx.compose.runtime.*
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.text.font.FontFamily
import androidx.compose.ui.text.input.KeyboardType
import androidx.compose.ui.text.input.PasswordVisualTransformation
import androidx.compose.ui.text.input.VisualTransformation
import androidx.compose.ui.unit.dp
import io.github.fgozxy.await.backup.BackupScheduler
import io.github.fgozxy.await.sync.*
import io.github.fgozxy.await.ui.components.*
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext

/** 「云端服务」：连接 Await 服务端，验证后可配置 Telegram、ntfy 并使用云端备份。 */
@OptIn(ExperimentalMaterial3Api::class)
@Composable
fun CloudDeploymentScreen() {
    val context = LocalContext.current
    val scope = rememberCoroutineScope()
    val toaster = LocalToaster.current
    val haptics = rememberHaptics()
    val saved = remember { SyncSettings.load(context) }
    var url by remember { mutableStateOf(saved.url) }
    var apiKey by remember { mutableStateOf(saved.apiKey) }
    var showKey by remember { mutableStateOf(false) }
    var busy by remember { mutableStateOf(false) }
    var result by remember { mutableStateOf<Pair<Boolean, String>?>(null) }
    var guide by remember { mutableStateOf(!saved.isValid) }
    var enableBackups by remember { mutableStateOf(CloudDeployment.backupsEnabled(context)) }
    val deployCommand = "cd server\nAWAIT_BACKUP_ENABLED=$enableBackups docker compose up -d --build"
    val status = rememberSyncStatus()

    fun connect() {
        val draft = SyncSettings.Config(url.trim().trimEnd('/'), apiKey.trim())
        if (!draft.isValid) {
            haptics.reject()
            result = false to "请填写 HTTPS 地址和至少 32 位的服务器访问密钥"
            return
        }
        busy = true
        result = null
        scope.launch {
            try {
                val checked = withContext(Dispatchers.IO) {
                    val response = ServerClient.checkChannels(draft, emptyList())
                    val deployment = CloudDeployment.parse(response, SyncSettings.clientId(context), allowBackupRecovery = true)
                    synchronized(SyncCoordinator.lock) {
                        SyncSettings.save(context, draft)
                        CloudDeployment.record(context, draft, deployment)
                        BackupScheduler.reschedule(context)
                        SyncSettings.prefs(context).edit().putString("last_error", "").apply()
                        SyncCoordinator.changed(context)
                    }
                    deployment
                }
                var text = if (!checked.canSync) "服务器已绑定另一台手机，可在「备份与恢复」恢复历史数据；通知同步需先迁移绑定"
                    else if (checked.managesChannels) "云端服务已验证并保存，可在「通知」中配置 Telegram 和 ntfy"
                    else "服务器连接已验证。此服务器尚不支持手机配置渠道，请升级 Await 云端服务"
                enableBackups = checked.backupsEnabled
                text += if (checked.backupsEnabled) "\n云端备份已开启，可进入「备份与恢复」使用"
                    else "\n云端备份未开启，可按部署指引开启后重新验证"
                result = true to text
                haptics.confirm()
            } catch (e: ServerException) {
                result = false to e.message.orEmpty()
            } catch (_: Exception) {
                result = false to "验证或保存失败，请检查 Await 服务版本并重试"
            } finally {
                busy = false
            }
        }
    }

    PageScaffold(title = "云端服务") { padding ->
        PageColumn(padding, spacing = 16.dp) {
            // 只看云端相关的状态行；软件通知的状态在「通知」页
            StatusCard(status.lines().filterNot { it.startsWith("软件通知") }.joinToString("\n")
                .ifBlank { "仅使用软件通知时无需云端服务" })

            OutlinedTextField(
                url, { url = it },
                label = { Text("Await 云端地址") },
                placeholder = { Text("https://await.example.com") },
                singleLine = true,
                enabled = !busy,
                keyboardOptions = KeyboardOptions(keyboardType = KeyboardType.Uri),
                shape = RoundedCornerShape(16.dp),
                modifier = Modifier.fillMaxWidth()
            )
            OutlinedTextField(
                apiKey, { apiKey = it },
                label = { Text("访问密钥") },
                singleLine = true,
                enabled = !busy,
                visualTransformation = if (showKey) VisualTransformation.None else PasswordVisualTransformation(),
                keyboardOptions = KeyboardOptions(keyboardType = KeyboardType.Password),
                trailingIcon = {
                    IconButton(onClick = { showKey = !showKey }) {
                        Icon(if (showKey) Icons.Outlined.VisibilityOff else Icons.Outlined.Visibility,
                            if (showKey) "隐藏密钥" else "显示密钥")
                    }
                },
                supportingText = { Text("与服务器 AWAIT_API_KEY 一致，至少 32 位；加密保存在手机") },
                shape = RoundedCornerShape(16.dp),
                modifier = Modifier.fillMaxWidth()
            )
            Button(onClick = ::connect, enabled = !busy, modifier = Modifier.fillMaxWidth().height(52.dp)) {
                if (busy) {
                    CircularProgressIndicator(Modifier.size(20.dp), strokeWidth = 2.dp,
                        color = MaterialTheme.colorScheme.onPrimary)
                    Spacer(Modifier.width(12.dp))
                    Text("正在验证…")
                } else Text("验证连接并保存")
            }
            result?.let { (ok, text) -> StatusCard(text, error = !ok) }

            PrefGroup("状态") {
                PrefRow(
                    title = "云端备份",
                    icon = Icons.Outlined.Backup,
                    value = if (CloudDeployment.backupsEnabled(context)) "服务器已开启" else "未开启或未验证"
                )
            }

            PrefGroup("部署") {
                PrefRow(
                    title = "部署指引",
                    icon = Icons.AutoMirrored.Outlined.MenuBook,
                    onClick = { guide = !guide },
                    trailing = { Icon(if (guide) Icons.Outlined.ExpandLess else Icons.Outlined.ExpandMore, null) }
                )
                AnimatedVisibility(guide) {
                    Column(
                        Modifier.padding(start = 16.dp, end = 16.dp, bottom = 12.dp),
                        verticalArrangement = Arrangement.spacedBy(10.dp)
                    ) {
                        val step = MaterialTheme.typography.bodyMedium
                        Text("1. 在服务器安装 Docker，将 Await 项目的 server 目录上传到服务器。", style = step)
                        Text("2. 向进程环境注入 AWAIT_API_KEY（至少 32 位访问密钥），启动服务。初次部署无需配置 Telegram 或 ntfy。", style = step)
                        Text("3. 通过反向代理提供 HTTPS，将请求转发到 127.0.0.1:8091。", style = step)
                        Text("4. 在上方填写 HTTPS 地址及同一访问密钥并验证。", style = step)
                    }
                }
                if (guide) {
                    SwitchRow(
                        title = "部署时开启云端备份",
                        subtitle = "影响下方命令；重建服务时保持相同的值",
                        checked = enableBackups,
                        onChange = { enableBackups = it }
                    )
                    Surface(
                        shape = RoundedCornerShape(12.dp),
                        color = MaterialTheme.colorScheme.surfaceContainerHighest,
                        modifier = Modifier.fillMaxWidth().padding(horizontal = 16.dp, vertical = 4.dp)
                    ) {
                        Text(
                            deployCommand,
                            style = MaterialTheme.typography.bodySmall,
                            fontFamily = FontFamily.Monospace,
                            modifier = Modifier.padding(12.dp)
                        )
                    }
                    PrefRow(
                        title = "复制部署命令",
                        icon = Icons.Outlined.ContentCopy,
                        onClick = {
                            context.getSystemService(ClipboardManager::class.java).setPrimaryClip(
                                ClipData.newPlainText("Await 部署命令", deployCommand))
                            toaster.show("部署命令已复制")
                        }
                    )
                }
            }
            Text(
                "更换服务器时请停用旧服务，避免收到旧日程提醒。",
                style = MaterialTheme.typography.bodySmall,
                color = MaterialTheme.colorScheme.onSurfaceVariant,
                modifier = Modifier.padding(horizontal = 16.dp)
            )
        }
    }
}
