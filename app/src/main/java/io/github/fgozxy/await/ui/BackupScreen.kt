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
import androidx.compose.material.icons.filled.Visibility
import androidx.compose.material.icons.filled.VisibilityOff
import androidx.compose.material3.*
import androidx.compose.runtime.*
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.input.PasswordVisualTransformation
import androidx.compose.ui.text.input.VisualTransformation
import androidx.compose.ui.unit.dp
import androidx.compose.ui.window.Dialog
import androidx.compose.ui.window.DialogProperties
import io.github.fgozxy.await.backup.BackupScheduler
import io.github.fgozxy.await.backup.BackupService
import io.github.fgozxy.await.backup.BackupSettings
import io.github.fgozxy.await.backup.WebDavClient
import io.github.fgozxy.await.data.BackupData
import io.github.fgozxy.await.data.Event
import io.github.fgozxy.await.data.EventStore
import io.github.fgozxy.await.vm.EventViewModel
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext

/**
 * 「备份与恢复」整页对话框：本地导入导出 + WebDAV 云备份 + 定时备份设置。
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
    var showPassword by remember { mutableStateOf(false) }
    var showTimePicker by remember { mutableStateOf(false) }

    // 待确认导入：解析出来的日程 + 来源说明（本地文件 / 云端文件名）
    var pendingImport by remember { mutableStateOf<Pair<List<Event>, String>?>(null) }
    var remoteFiles by remember { mutableStateOf<List<WebDavClient.Entry>?>(null) }

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
        onDismissRequest = onDismiss,
        properties = DialogProperties(usePlatformDefaultWidth = false)
    ) {
        Scaffold(
            topBar = {
                TopAppBar(
                    title = { Text("备份与恢复") },
                    navigationIcon = {
                        IconButton(onClick = onDismiss) { Icon(Icons.Default.Close, "关闭") }
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

                // ── WebDAV ──
                SectionCard("WebDAV 云备份", "支持坚果云、Nextcloud、群晖等任意 WebDAV 服务") {
                    OutlinedTextField(
                        value = prefs.webdav.url,
                        onValueChange = { persist(prefs.copy(webdav = prefs.webdav.copy(url = it))) },
                        label = { Text("服务器地址") },
                        placeholder = { Text("https://dav.jianguoyun.com/dav/") },
                        singleLine = true,
                        modifier = Modifier.fillMaxWidth()
                    )
                    OutlinedTextField(
                        value = prefs.webdav.user,
                        onValueChange = { persist(prefs.copy(webdav = prefs.webdav.copy(user = it))) },
                        label = { Text("账号") },
                        singleLine = true,
                        modifier = Modifier.fillMaxWidth()
                    )
                    OutlinedTextField(
                        value = prefs.webdav.password,
                        onValueChange = { persist(prefs.copy(webdav = prefs.webdav.copy(password = it))) },
                        label = { Text("密码 / 应用授权码") },
                        singleLine = true,
                        visualTransformation = if (showPassword) VisualTransformation.None
                        else PasswordVisualTransformation(),
                        trailingIcon = {
                            IconButton(onClick = { showPassword = !showPassword }) {
                                Icon(
                                    if (showPassword) Icons.Default.VisibilityOff else Icons.Default.Visibility,
                                    if (showPassword) "隐藏密码" else "显示密码"
                                )
                            }
                        },
                        modifier = Modifier.fillMaxWidth()
                    )
                    OutlinedTextField(
                        value = prefs.webdav.dir,
                        onValueChange = { persist(prefs.copy(webdav = prefs.webdav.copy(dir = it))) },
                        label = { Text("备份目录") },
                        placeholder = { Text("Await") },
                        singleLine = true,
                        modifier = Modifier.fillMaxWidth()
                    )

                    Row(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                        OutlinedButton(
                            enabled = !busy,
                            onClick = {
                                runTask("正在测试连接…") { WebDavClient.testConnection(prefs.webdav) }
                            },
                            modifier = Modifier.weight(1f)
                        ) { Text("测试连接") }
                        Button(
                            enabled = !busy,
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
                        enabled = !busy,
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
                    // 少数服务器不支持 PROPFIND 列目录，用固定名的最新副本兜底
                    TextButton(
                        enabled = !busy,
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
                    ) { Text("列不出目录？直接恢复最新备份") }

                    // 保留份数
                    Text("云端保留份数", style = MaterialTheme.typography.labelLarge)
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
                SectionCard("定时备份", "到点在后台自动备份到 WebDAV") {
                    Row(verticalAlignment = Alignment.CenterVertically) {
                        Text("开启自动备份", Modifier.weight(1f))
                        Switch(
                            checked = prefs.autoEnabled,
                            onCheckedChange = { on ->
                                if (on && !prefs.webdav.isValid) {
                                    report(false, "请先填好 WebDAV 服务器、账号和密码")
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
                            "提示：部分国产 ROM 会在后台冻结应用，建议把 Await 加入电池优化白名单，" +
                                "定时备份才不会被系统拦截。",
                            style = MaterialTheme.typography.bodySmall,
                            color = MaterialTheme.colorScheme.onSurfaceVariant
                        )
                    }
                }

                Spacer(Modifier.height(16.dp))
            }
        }
    }

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
                                        BackupService.fetchBackup(context, name)
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
    pendingImport?.let { (events, from) ->
        ImportConfirmDialog(
            events = events,
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
