package io.github.fgozxy.await.ui

import android.net.Uri
import androidx.activity.compose.rememberLauncherForActivityResult
import androidx.activity.result.contract.ActivityResultContracts
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.*
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.items
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.outlined.Cloud
import androidx.compose.material.icons.outlined.CloudDownload
import androidx.compose.material.icons.outlined.CloudUpload
import androidx.compose.material.icons.outlined.Description
import androidx.compose.material.icons.outlined.EventRepeat
import androidx.compose.material.icons.outlined.FileUpload
import androidx.compose.material.icons.outlined.History
import androidx.compose.material.icons.outlined.Inventory2
import androidx.compose.material.icons.outlined.MoveToInbox
import androidx.compose.material.icons.outlined.Restore
import androidx.compose.material.icons.outlined.Schedule
import androidx.compose.material.icons.outlined.Wifi
import androidx.compose.material3.*
import androidx.compose.runtime.*
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.dp
import io.github.fgozxy.await.backup.BackupScheduler
import io.github.fgozxy.await.backup.BackupService
import io.github.fgozxy.await.backup.BackupSettings
import io.github.fgozxy.await.data.BackupData
import io.github.fgozxy.await.data.EventStore
import io.github.fgozxy.await.sync.CloudDeployment
import io.github.fgozxy.await.ui.components.*
import io.github.fgozxy.await.vm.EventViewModel
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext

/**
 * 「备份与恢复」：本地导出 + Await 云端备份 + 定时备份设置。
 * 所有设置改一次存一次，改完即生效，无需额外点保存。
 */
@OptIn(ExperimentalMaterial3Api::class)
@Composable
fun BackupScreen(viewModel: EventViewModel) {
    val context = LocalContext.current
    val scope = rememberCoroutineScope()
    val toaster = LocalToaster.current

    var prefs by remember { mutableStateOf(BackupSettings.load(context)) }
    var busy by remember { mutableStateOf(false) }
    var busyText by remember { mutableStateOf("") }
    var status by remember { mutableStateOf<Pair<Boolean, String>?>(null) }
    var sheet by remember { mutableStateOf<String?>(null) }
    // 云端改了连接（从这里点进「云端服务」再回来）后，备份能力跟着刷新
    val cloudStatus = rememberSyncStatus()
    val ready = remember(cloudStatus, Nav.stack.size) { CloudDeployment.backupsEnabled(context) }
    val writable = remember(cloudStatus, Nav.stack.size) { CloudDeployment.canWriteBackup(context) }
    val connected = remember(cloudStatus, Nav.stack.size) { CloudDeployment.isReady(context) }

    // 待确认导入：解析出来的日程 + 来源说明（云端文件名）
    var pendingImport by remember { mutableStateOf<Pair<BackupData.ImportBundle, String>?>(null) }
    var remoteFiles by remember { mutableStateOf<List<BackupService.Entry>?>(null) }

    fun report(ok: Boolean, msg: String) {
        if (ok) { status = null; toaster.show(msg) } else status = false to msg
    }

    /** 改设置：立刻落盘并重排定时备份 */
    fun persist(next: BackupSettings.Prefs) {
        prefs = next
        BackupSettings.save(context, next)
        BackupScheduler.reschedule(context)
    }

    /** 统一的「跑一个网络任务」包装：置忙、跑在 IO、回填状态 */
    fun <T> runTask(label: String, task: suspend () -> Result<T>, onSuccess: (T) -> Unit) {
        if (busy) return
        busy = true
        busyText = label
        status = null
        scope.launch {
            val r = withContext(Dispatchers.IO) { task() }
            busy = false
            prefs = BackupSettings.load(context)
            r.onSuccess(onSuccess).onFailure { report(false, it.message ?: "操作失败") }
        }
    }

    val exportLauncher = rememberLauncherForActivityResult(
        ActivityResultContracts.CreateDocument("application/json")
    ) { uri: Uri? ->
        if (uri == null) return@rememberLauncherForActivityResult
        scope.launch {
            val r = withContext(Dispatchers.IO) {
                runCatching {
                    val json = BackupData.exportJson(context)
                    context.contentResolver.openOutputStream(uri)?.use {
                        it.write(json.toByteArray(Charsets.UTF_8))
                    } ?: error("无法写入所选位置")
                    EventStore.load(context).size
                }
            }
            r.onSuccess { report(true, "已导出 $it 条日程到所选文件") }
                .onFailure { report(false, "导出失败：${it.message}") }
        }
    }

    PageScaffold(title = "备份与恢复") { padding ->
        PageColumn(padding) {
            if (busy) {
                Column(verticalArrangement = Arrangement.spacedBy(8.dp)) {
                    LinearProgressIndicator(Modifier.fillMaxWidth())
                    Text(busyText, style = MaterialTheme.typography.bodyMedium)
                }
            }
            status?.let { (ok, text) -> StatusCard(text, error = !ok) }

            PrefGroup(
                "云端备份",
                footer = when {
                    !connected -> "复用「云端服务」的连接，请先配置并验证。"
                    !ready -> "服务器未开启备份：在部署指引中选择开启云端备份，执行命令后重新验证。"
                    !writable -> "当前连接仅可恢复备份；上传与通知同步需先迁移手机绑定。"
                    else -> "保存完整日程、备注、颜色、置顶和空分组，在服务器加密存储。"
                }
            ) {
                PrefRow(
                    title = "云端服务",
                    icon = Icons.Outlined.Cloud,
                    value = when {
                        !connected -> "未配置"
                        ready -> "备份已开启"
                        else -> "备份未开启"
                    },
                    onClick = { Nav.go("cloud") }
                )
                PrefRow(
                    title = "立即备份",
                    subtitle = prefs.lastBackupText()?.let { "上次成功：$it" },
                    icon = Icons.Outlined.CloudUpload,
                    enabled = !busy && writable,
                    onClick = {
                        runTask("正在备份到云端…", { BackupService.backupNow(context, manual = true) }) {
                            report(true, it)
                        }
                    }
                )
                PrefRow(
                    title = "恢复最新备份",
                    icon = Icons.Outlined.Restore,
                    enabled = !busy && ready,
                    onClick = {
                        runTask("正在下载最新备份…", { BackupService.fetchBackup(context, BackupService.LATEST_NAME) }) {
                            pendingImport = it to "云端最新备份"
                        }
                    }
                )
                PrefRow(
                    title = "从历史备份恢复",
                    icon = Icons.Outlined.History,
                    enabled = !busy && ready,
                    onClick = {
                        runTask("正在读取云端备份…", { BackupService.listBackups(context) }) { list ->
                            if (list.isEmpty()) report(false, "云端还没有备份文件") else remoteFiles = list
                        }
                    }
                )
                PrefRow(
                    title = "保留份数",
                    subtitle = "下次备份时清理超出的旧版本",
                    icon = Icons.Outlined.Inventory2,
                    value = "${prefs.keepCount} 份",
                    onClick = { sheet = "keep" }
                )
            }
            if (prefs.lastResultMsg.isNotBlank() && !prefs.lastResultOk) {
                StatusCard("最近一次失败：${prefs.lastResultMsg}", error = true)
            }

            PrefGroup(
                "定时备份",
                footer = if (prefs.autoEnabled)
                    (BackupScheduler.nextTriggerText(prefs)?.let { "下次备份：$it。" } ?: "") +
                        "关机、断网或后台受限时可能延迟，重新打开应用后会补做逾期备份。"
                else null
            ) {
                SwitchRow(
                    title = "自动备份",
                    subtitle = "按周期自动上传到 Await 云端",
                    icon = Icons.Outlined.EventRepeat,
                    checked = prefs.autoEnabled,
                    onChange = { on ->
                        if (on && !CloudDeployment.canWriteBackup(context)) {
                            toaster.show("请先验证云端连接，并在服务器开启备份功能")
                        } else persist(prefs.copy(autoEnabled = on))
                    }
                )
                if (prefs.autoEnabled) {
                    PrefRow(
                        title = "频率",
                        icon = Icons.Outlined.Description,
                        value = BackupSettings.INTERVAL_OPTIONS.firstOrNull { it.first == prefs.intervalDays }?.second
                            ?: "每 ${prefs.intervalDays} 天",
                        onClick = { sheet = "interval" }
                    )
                    PrefRow(
                        title = "备份时刻",
                        icon = Icons.Outlined.Schedule,
                        value = "%02d:%02d".format(prefs.hour, prefs.minute),
                        onClick = { sheet = "time" }
                    )
                    SwitchRow(
                        title = "仅在 WLAN 下备份",
                        subtitle = "移动数据下跳过本次自动备份",
                        icon = Icons.Outlined.Wifi,
                        checked = prefs.wifiOnly,
                        onChange = { persist(prefs.copy(wifiOnly = it)) }
                    )
                }
            }

            PrefGroup("本地", footer = "旧 WebDAV 备份的 JSON 文件可下载后通过「导入日程」恢复。") {
                PrefRow(
                    title = "导出到文件",
                    subtitle = "保存为 JSON，可发给自己留存",
                    icon = Icons.Outlined.FileUpload,
                    onClick = { exportLauncher.launch(BackupData.defaultFileName()) }
                )
                PrefRow(
                    title = "导入日程",
                    icon = Icons.Outlined.MoveToInbox,
                    onClick = { Nav.go("import") }
                )
            }
        }
    }

    when (sheet) {
        "keep" -> OptionSheet(
            title = "云端保留份数",
            options = BackupSettings.KEEP_OPTIONS.map { it to "$it 份" },
            selected = prefs.keepCount,
            onDismiss = { sheet = null }
        ) { persist(prefs.copy(keepCount = it)) }
        "interval" -> OptionSheet(
            title = "备份频率",
            options = BackupSettings.INTERVAL_OPTIONS,
            selected = prefs.intervalDays,
            onDismiss = { sheet = null }
        ) { persist(prefs.copy(intervalDays = it)) }
        "time" -> TimePickerDialog(
            title = "备份时刻",
            hour = prefs.hour,
            minute = prefs.minute,
            onDismiss = { sheet = null }
        ) { h, m -> persist(prefs.copy(hour = h, minute = m)); sheet = null }
    }

    // ── 云端备份列表 ──
    remoteFiles?.let { files ->
        ModalBottomSheet(onDismissRequest = { remoteFiles = null }, sheetState = rememberModalBottomSheetState(true)) {
            SheetTitle("选择要恢复的备份", "共 ${files.size} 份")
            LazyColumn(Modifier.navigationBarsPadding().padding(bottom = 12.dp)) {
                items(files, key = { it.id }) { entry ->
                    Row(
                        Modifier
                            .fillMaxWidth()
                            .clickable {
                                val name = entry.name
                                remoteFiles = null
                                runTask("正在下载 $name…", { BackupService.fetchBackup(context, entry.id) }) {
                                    pendingImport = it to "云端「$name」"
                                }
                            }
                            .padding(horizontal = 24.dp, vertical = 14.dp),
                        verticalAlignment = Alignment.CenterVertically
                    ) {
                        Icon(Icons.Outlined.CloudDownload, null, tint = MaterialTheme.colorScheme.onSurfaceVariant)
                        Spacer(Modifier.width(16.dp))
                        Column(Modifier.weight(1f)) {
                            Text(entry.name, style = MaterialTheme.typography.bodyLarge, fontWeight = FontWeight.Medium)
                            val detail = listOf(entry.modifiedText(), entry.sizeText())
                                .filter { it.isNotBlank() }.joinToString(" · ")
                            if (detail.isNotBlank()) {
                                Text(detail, style = MaterialTheme.typography.bodySmall,
                                    color = MaterialTheme.colorScheme.onSurfaceVariant)
                            }
                        }
                    }
                }
            }
        }
    }

    // ── 导入方式确认（云端恢复用）──
    pendingImport?.let { (bundle, from) ->
        ImportConfirmDialog(
            bundle = bundle,
            from = from,
            viewModel = viewModel,
            onDismiss = { pendingImport = null },
            onDone = { ok, msg -> pendingImport = null; report(ok, msg) }
        )
    }
}
