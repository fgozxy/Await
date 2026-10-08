package io.github.fgozxy.await.ui

import android.net.Uri
import androidx.activity.compose.rememberLauncherForActivityResult
import androidx.activity.result.contract.ActivityResultContracts
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.*
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.verticalScroll
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.Close
import androidx.compose.material.icons.filled.CloudDownload
import androidx.compose.material.icons.filled.CloudUpload
import androidx.compose.material.icons.filled.FileUpload
import androidx.compose.material3.*
import androidx.compose.runtime.*
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.dp
import androidx.compose.ui.window.Dialog
import androidx.compose.ui.window.DialogProperties
import io.github.fgozxy.await.backup.BackupScheduler
import io.github.fgozxy.await.backup.BackupService
import io.github.fgozxy.await.backup.BackupSettings
import io.github.fgozxy.await.sync.CloudDeployment
import io.github.fgozxy.await.data.BackupData
import io.github.fgozxy.await.data.EventStore
import io.github.fgozxy.await.vm.EventViewModel
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext

/**
 * 「备份与恢复」整页对话框：本地导入导出 + Await 云端备份 + 定时备份设置。
 * 所有设置改一次存一次，改完即生效，无需额外点保存。
 */
@OptIn(ExperimentalMaterial3Api::class)
@Composable
fun BackupScreen(viewModel: EventViewModel, onDismiss: () -> Unit) {
    val context = LocalContext.current
    val scope = rememberCoroutineScope()

    var prefs by remember { mutableStateOf(BackupSettings.load(context)) }
    var busy by remember { mutableStateOf(false) }
    var busyText by remember { mutableStateOf("") }
    var status by remember { mutableStateOf<String?>(null) }
    var statusOk by remember { mutableStateOf(true) }
    var showCloud by remember { mutableStateOf(false) }
    val cloudStatus = rememberSyncStatus()
    var showTimePicker by remember { mutableStateOf(false) }

    // 待确认导入：解析出来的日程 + 来源说明（本地文件 / 云端文件名）
    var pendingImport by remember { mutableStateOf<Pair<BackupData.ImportBundle, String>?>(null) }
    var remoteFiles by remember { mutableStateOf<List<BackupService.Entry>?>(null) }

    fun report(ok: Boolean, msg: String) {
        statusOk = ok
        status = msg
    }

    /** 改设置：立刻落盘并重排定时备份 */
    fun persist(next: BackupSettings.Prefs) {
        prefs = next
        BackupSettings.save(context, next)
        BackupScheduler.reschedule(context)
    }

    /** 统一的「跑一个网络任务」包装：置忙、跑在 IO、回填状态 */
    fun runTask(label: String, task: suspend () -> Result<String>) {
        if (busy) return
        busy = true
        busyText = label
        status = null
        scope.launch {
            val r = withContext(Dispatchers.IO) { task() }
            busy = false
            prefs = BackupSettings.load(context)
            r.onSuccess { report(true, it) }
                .onFailure { report(false, it.message ?: "操作失败") }
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

    Dialog(
        onDismissRequest = { if (!busy) onDismiss() },
        properties = DialogProperties(usePlatformDefaultWidth = false)
    ) {
        Scaffold(
            topBar = {
                TopAppBar(
                    title = { Text("备份与恢复") },
                    navigationIcon = {
                        IconButton(onClick = onDismiss, enabled = !busy) { Icon(Icons.Default.Close, "关闭") }
                    }
                )
            }
        ) { padding ->
            Column(
                Modifier
                    .padding(padding)
                    .fillMaxSize()
                    .verticalScroll(rememberScrollState())
                    .padding(horizontal = 16.dp),
                verticalArrangement = Arrangement.spacedBy(12.dp)
            ) {
                if (busy) {
                    Row(verticalAlignment = Alignment.CenterVertically) {
                        CircularProgressIndicator(Modifier.size(18.dp), strokeWidth = 2.dp)
                        Spacer(Modifier.width(10.dp))
                        Text(busyText, style = MaterialTheme.typography.bodyMedium)
                    }
                }
                status?.let {
                    Card(
                        colors = CardDefaults.cardColors(
                            containerColor = if (statusOk) MaterialTheme.colorScheme.secondaryContainer
                            else MaterialTheme.colorScheme.errorContainer
                        )
                    ) {
                        Text(it, Modifier.padding(12.dp), style = MaterialTheme.typography.bodyMedium)
                    }
                }

                // ── 本地备份 ──
                SectionCard("本地备份", "导出为 JSON 文件保存到手机或发给自己") {
                    Button(
                        onClick = { exportLauncher.launch(BackupData.defaultFileName()) },
                        modifier = Modifier.fillMaxWidth()
                    ) {
                        Icon(Icons.Default.FileUpload, null, Modifier.size(18.dp))
                        Spacer(Modifier.width(6.dp))
                        Text("导出到文件")
                    }
                    Text(
                        "导入日程（含从其他应用迁移）已挪到菜单里的「导入日程」。",
                        style = MaterialTheme.typography.labelSmall,
                        color = MaterialTheme.colorScheme.onSurfaceVariant
                    )
                }

                SectionCard("云端备份", "复用 Await 云端连接，备份完整日程和空分组") {
                    Text(cloudStatus, style = MaterialTheme.typography.bodySmall)
                    val ready = CloudDeployment.backupsEnabled(context)
                    val writable = CloudDeployment.canWriteBackup(context)
                    Text(when {
                        !CloudDeployment.isReady(context) -> "请先配置并验证 Await 云端连接。"
                        !ready -> "服务器未开启备份，请在部署指引中选择开启云端备份，执行命令后重新验证。"
                        !writable -> "当前连接仅可恢复备份；上传与通知同步需先迁移手机绑定。"
                        else -> "云端备份已开启，数据加密保存在服务器持久化数据卷。"
                    })
                    OutlinedButton(onClick = { showCloud = true }, enabled = !busy) { Text("云端部署配置") }
                    Row(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                        Button(
                            enabled = !busy && writable,
                            onClick = {
                                runTask("正在备份到云端…") { BackupService.backupNow(context, manual = true) }
                            },
                            modifier = Modifier.weight(1f)
                        ) {
                            Icon(Icons.Default.CloudUpload, null, Modifier.size(18.dp))
                            Spacer(Modifier.width(6.dp))
                            Text("立即备份")
                        }
                    }
                    OutlinedButton(
                        enabled = !busy && ready,
                        onClick = {
                            if (busy) return@OutlinedButton
                            busy = true
                            busyText = "正在读取云端备份…"
                            status = null
                            scope.launch {
                                val r = withContext(Dispatchers.IO) { BackupService.listBackups(context) }
                                busy = false
                                r.onSuccess { list ->
                                    if (list.isEmpty()) report(false, "云端还没有备份文件")
                                    else remoteFiles = list
                                }.onFailure { report(false, it.message ?: "读取失败") }
                            }
                        },
                        modifier = Modifier.fillMaxWidth()
                    ) {
                        Icon(Icons.Default.CloudDownload, null, Modifier.size(18.dp))
                        Spacer(Modifier.width(6.dp))
                        Text("从云端恢复")
                    }
                    // 最新备份与历史版本均由 Await 服务提供。
                    TextButton(
                        enabled = !busy && ready,
                        onClick = {
                            if (busy) return@TextButton
                            busy = true
                            busyText = "正在下载最新备份…"
                            status = null
                            scope.launch {
                                val r = withContext(Dispatchers.IO) {
                                    BackupService.fetchBackup(context, BackupService.LATEST_NAME)
                                }
                                busy = false
                                r.onSuccess { pendingImport = it to "云端最新备份" }
                                    .onFailure { report(false, it.message ?: "下载失败") }
                            }
                        },
                        modifier = Modifier.align(Alignment.CenterHorizontally)
                    ) { Text("恢复最新备份") }

                    // 保留份数
                    Text("云端保留份数（下次备份生效）", style = MaterialTheme.typography.labelLarge)
                    Row(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                        BackupSettings.KEEP_OPTIONS.forEach { n ->
                            FilterChip(
                                selected = prefs.keepCount == n,
                                onClick = { persist(prefs.copy(keepCount = n)) },
                                label = { Text("$n 份") }
                            )
                        }
                    }

                    prefs.lastBackupText()?.let {
                        Text(
                            "上次成功备份：$it",
                            style = MaterialTheme.typography.bodySmall,
                            color = MaterialTheme.colorScheme.onSurfaceVariant
                        )
                    }
                    if (prefs.lastResultMsg.isNotBlank()) {
                        Text(
                            (if (prefs.lastResultOk) "最近一次：" else "最近一次失败：") + prefs.lastResultMsg,
                            style = MaterialTheme.typography.bodySmall,
                            color = if (prefs.lastResultOk) MaterialTheme.colorScheme.onSurfaceVariant
                            else MaterialTheme.colorScheme.error
                        )
                    }
                }

                // ── 定时备份 ──
                SectionCard("定时备份", "手机按指定周期自动上传到 Await 云端") {
                    Row(verticalAlignment = Alignment.CenterVertically) {
                        Text("开启自动备份", Modifier.weight(1f))
                        Switch(
                            checked = prefs.autoEnabled,
                            onCheckedChange = { on ->
                                if (on && !CloudDeployment.canWriteBackup(context)) {
                                    report(false, "请先验证云端连接，并在服务器开启备份功能")
                                } else {
                                    persist(prefs.copy(autoEnabled = on))
                                }
                            }
                        )
                    }
                    if (prefs.autoEnabled) {
                        Text("备份频率", style = MaterialTheme.typography.labelLarge)
                        Row(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                            BackupSettings.INTERVAL_OPTIONS.forEach { (days, label) ->
                                FilterChip(
                                    selected = prefs.intervalDays == days,
                                    onClick = { persist(prefs.copy(intervalDays = days)) },
                                    label = { Text(label) }
                                )
                            }
                        }
                        Row(verticalAlignment = Alignment.CenterVertically) {
                            Text("备份时刻", Modifier.weight(1f))
                            TextButton(onClick = { showTimePicker = true }) {
                                Text("%02d:%02d".format(prefs.hour, prefs.minute))
                            }
                        }
                        Row(verticalAlignment = Alignment.CenterVertically) {
                            Column(Modifier.weight(1f)) {
                                Text("仅在 WLAN 下备份")
                                Text(
                                    "移动数据下跳过本次自动备份",
                                    style = MaterialTheme.typography.bodySmall,
                                    color = MaterialTheme.colorScheme.onSurfaceVariant
                                )
                            }
                            Switch(
                                checked = prefs.wifiOnly,
                                onCheckedChange = { persist(prefs.copy(wifiOnly = it)) }
                            )
                        }
                        BackupScheduler.nextTriggerText(prefs)?.let {
                            Text(
                                "下次备份：$it",
                                style = MaterialTheme.typography.bodySmall,
                                color = MaterialTheme.colorScheme.primary
                            )
                        }
                        Text(
                            "自动备份由手机上传；关机、断网或后台受限时可能延迟，重新打开应用后会补做逾期备份。",
                            style = MaterialTheme.typography.bodySmall,
                            color = MaterialTheme.colorScheme.onSurfaceVariant
                        )
                    }
                }

                Spacer(Modifier.height(16.dp))
            }
        }
    }

    if (showCloud) CloudDeploymentScreen(onDismiss = { showCloud = false; prefs = BackupSettings.load(context) })

    // ── 备份时刻选择 ──
    if (showTimePicker) {
        val timeState = rememberTimePickerState(prefs.hour, prefs.minute, true)
        AlertDialog(
            onDismissRequest = { showTimePicker = false },
            title = { Text("选择备份时刻") },
            text = { TimePicker(timeState) },
            confirmButton = {
                TextButton(onClick = {
                    persist(prefs.copy(hour = timeState.hour, minute = timeState.minute))
                    showTimePicker = false
                }) { Text("确定") }
            },
            dismissButton = { TextButton({ showTimePicker = false }) { Text("取消") } }
        )
    }

    // ── 云端备份列表 ──
    remoteFiles?.let { files ->
        AlertDialog(
            onDismissRequest = { remoteFiles = null },
            title = { Text("选择要恢复的备份") },
            text = {
                Column(
                    Modifier
                        .heightIn(max = 380.dp)
                        .verticalScroll(rememberScrollState()),
                    verticalArrangement = Arrangement.spacedBy(2.dp)
                ) {
                    files.forEach { entry ->
                        ListItem(
                            headlineContent = { Text(entry.name, style = MaterialTheme.typography.bodyMedium) },
                            supportingContent = {
                                val detail = listOf(entry.modifiedText(), entry.sizeText())
                                    .filter { it.isNotBlank() }.joinToString(" · ")
                                if (detail.isNotBlank()) Text(detail, style = MaterialTheme.typography.bodySmall)
                            },
                            modifier = Modifier.clickable {
                                val name = entry.name
                                remoteFiles = null
                                busy = true
                                busyText = "正在下载 $name…"
                                status = null
                                scope.launch {
                                    val r = withContext(Dispatchers.IO) {
                                        BackupService.fetchBackup(context, entry.id)
                                    }
                                    busy = false
                                    r.onSuccess { pendingImport = it to "云端「$name」" }
                                        .onFailure { report(false, it.message ?: "下载失败") }
                                }
                            }
                        )
                    }
                }
            },
            confirmButton = { TextButton({ remoteFiles = null }) { Text("取消") } }
        )
    }

    // ── 导入方式确认（云端恢复用；本地导入已挪到「导入日程」）──
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

/** 带标题与说明的分区卡片 */
@Composable
internal fun SectionCard(
    title: String,
    subtitle: String,
    content: @Composable ColumnScope.() -> Unit
) {
    Card(Modifier.fillMaxWidth()) {
        Column(
            Modifier.padding(16.dp),
            verticalArrangement = Arrangement.spacedBy(10.dp)
        ) {
            Text(title, style = MaterialTheme.typography.titleMedium, fontWeight = FontWeight.SemiBold)
            Text(
                subtitle,
                style = MaterialTheme.typography.bodySmall,
                color = MaterialTheme.colorScheme.onSurfaceVariant
            )
            content()
        }
    }
}
