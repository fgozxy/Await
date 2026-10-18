package io.github.fgozxy.await.ui

import android.content.Intent
import android.net.Uri
import androidx.compose.foundation.layout.*
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.outlined.Backup
import androidx.compose.material.icons.outlined.Cloud
import androidx.compose.material.icons.outlined.Code
import androidx.compose.material.icons.outlined.MoveToInbox
import androidx.compose.material.icons.outlined.Notifications
import androidx.compose.material.icons.outlined.SystemUpdate
import androidx.compose.material3.*
import androidx.compose.runtime.*
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.LocalContext
import io.github.fgozxy.await.notify.NotificationChannels
import io.github.fgozxy.await.notify.ReminderSettings
import io.github.fgozxy.await.sync.CloudDeployment
import io.github.fgozxy.await.ui.components.*
import io.github.fgozxy.await.update.UpdateManager

/** 「设置」标签页：通知、云端、数据和关于。 */
@OptIn(ExperimentalMaterial3Api::class)
@Composable
fun SettingsScreen() {
    val context = LocalContext.current
    val toaster = LocalToaster.current
    val health = rememberNotificationHealth()
    // 子页面关闭（栈变化）或同步状态变化时重读摘要
    val refresh = health to Nav.stack.size
    val channels = remember(refresh) { NotificationChannels.load(context) }
    val defaultTime = remember(refresh) { ReminderSettings.load(context) }
    val cloudReady = remember(refresh) { CloudDeployment.isReady(context) }
    val backups = remember(refresh) { CloudDeployment.backupsEnabled(context) }
    val version = remember { UpdateManager.currentVersion(context) }
    val update = UpdateState.available.value

    PageScaffold(title = "设置", navIcon = null, bottomInset = false) { padding ->
        PageColumn(padding) {
            PrefGroup("提醒") {
                PrefRow(
                    title = "通知",
                    subtitle = health.homeWarning
                        ?: (channels.joinToString("、") { it.label.removeSuffix("通知") } + " · 默认 ${defaultTime.display()}"),
                    subtitleIsError = health.homeWarning != null,
                    icon = Icons.Outlined.Notifications,
                    onClick = { Nav.go("notifications") }
                )
                PrefRow(
                    title = "云端服务",
                    subtitle = if (cloudReady) "已连接" else "未配置 · Telegram、ntfy 推送和云端备份需要",
                    icon = Icons.Outlined.Cloud,
                    onClick = { Nav.go("cloud") }
                )
            }
            PrefGroup("数据") {
                PrefRow(
                    title = "备份与恢复",
                    subtitle = if (backups) "云端备份已开启" else "导出文件或使用云端备份",
                    icon = Icons.Outlined.Backup,
                    onClick = { Nav.go("backup") }
                )
                PrefRow(
                    title = "导入日程",
                    subtitle = "粘贴 JSON、从文件导入或从其他应用迁移",
                    icon = Icons.Outlined.MoveToInbox,
                    onClick = { Nav.go("import") }
                )
            }
            PrefGroup("关于") {
                PrefRow(
                    title = "软件更新",
                    subtitle = if (update != null) "发现新版本 ${update.versionName}" else "当前版本 $version",
                    icon = Icons.Outlined.SystemUpdate,
                    onClick = { Nav.go("update") },
                    trailing = if (update != null) { { Badge { Text("新") } } } else null
                )
                PrefRow(
                    title = "项目主页",
                    subtitle = "github.com/fgozxy/Await",
                    icon = Icons.Outlined.Code,
                    onClick = {
                        runCatching {
                            context.startActivity(Intent(Intent.ACTION_VIEW, Uri.parse("https://github.com/fgozxy/Await")))
                        }.onFailure { toaster.show("无法打开浏览器") }
                    }
                )
            }
        }
    }
}
