package io.github.fgozxy.await.ui

import android.net.Uri
import androidx.activity.compose.rememberLauncherForActivityResult
import androidx.activity.result.contract.ActivityResultContracts
import androidx.compose.foundation.layout.*
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.verticalScroll
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.AutoAwesome
import androidx.compose.material.icons.filled.Close
import androidx.compose.material.icons.filled.ContentCopy
import androidx.compose.material.icons.filled.ContentPaste
import androidx.compose.material.icons.filled.FileDownload
import androidx.compose.material3.*
import androidx.compose.runtime.*
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.LocalClipboardManager
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.text.AnnotatedString
import androidx.compose.ui.text.font.FontFamily
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.dp
import androidx.compose.ui.window.Dialog
import androidx.compose.ui.window.DialogProperties
import io.github.fgozxy.await.data.BackupData
import io.github.fgozxy.await.vm.EventViewModel
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext

/**
 * 「导入日程」整页对话框：粘贴 JSON / 从文件导入 / AI 迁移提示词。
 *
 * 从「备份与恢复」里独立出来的：那一页原本的主线是「把自己的数据存走再拿回来」，
 * 而导入更常见的用途其实是从别的倒数日应用搬家——两件事的读者不是同一批人，
 * 混在一起谁都不好找。备份页只保留导出与云备份（含云端恢复，那是备份自己的闭环）。
 */
@OptIn(ExperimentalMaterial3Api::class)
@Composable
fun ImportScreen(viewModel: EventViewModel, onDismiss: () -> Unit) {
    val context = LocalContext.current
    val scope = rememberCoroutineScope()
    val clipboard = LocalClipboardManager.current

    var status by remember { mutableStateOf<String?>(null) }
    var statusOk by remember { mutableStateOf(true) }

    // 粘贴导入：输入框内容 + 上一次解析失败的原因
    var pasteText by remember { mutableStateOf("") }
    var pasteError by remember { mutableStateOf<String?>(null) }

    // 待确认导入：解析出来的日程 + 来源说明
    var pendingImport by remember { mutableStateOf<Pair<BackupData.ImportBundle, String>?>(null) }

    fun report(ok: Boolean, msg: String) {
        statusOk = ok
        status = msg
    }

    val importLauncher = rememberLauncherForActivityResult(
        ActivityResultContracts.OpenDocument()
    ) { uri: Uri? ->
        if (uri == null) return@rememberLauncherForActivityResult
        scope.launch {
            val r = withContext(Dispatchers.IO) {
                runCatching {
                    val text = context.contentResolver.openInputStream(uri)?.use {
                        it.bufferedReader().readText()
                    } ?: error("无法读取所选文件")
                    BackupData.parseBundle(text).getOrThrow()
                }
            }
            r.onSuccess { pendingImport = it to "所选文件" }
                .onFailure { report(false, "导入失败：${it.message}") }
        }
    }

    Dialog(
        onDismissRequest = onDismiss,
        properties = DialogProperties(usePlatformDefaultWidth = false)
    ) {
        Scaffold(
            topBar = {
                TopAppBar(
                    title = { Text("导入日程") },
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
                    .padding(16.dp),
                verticalArrangement = Arrangement.spacedBy(12.dp)
            ) {
                status?.let {
                    Surface(
                        color = if (statusOk) MaterialTheme.colorScheme.secondaryContainer
                        else MaterialTheme.colorScheme.errorContainer
                    ) {
                        Text(it, Modifier.padding(12.dp), style = MaterialTheme.typography.bodyMedium)
                    }
                }

                // ── 粘贴 JSON ──
                SectionCard("粘贴 JSON", "把 AI 或别处给的 JSON 直接粘进来，不必先存成文件") {
                    OutlinedTextField(
                        value = pasteText,
                        onValueChange = { pasteText = it; pasteError = null },
                        label = { Text("JSON 内容") },
                        placeholder = { Text("[{\"title\": \"房租\", \"date\": \"2026-10-01\"}]") },
                        isError = pasteError != null,
                        supportingText = pasteError?.let { { Text(it) } }
                            ?: { Text("外面裹着的 ``` 代码块、前后多余的说明文字都不用删") },
                        textStyle = MaterialTheme.typography.bodySmall
                            .copy(fontFamily = FontFamily.Monospace),
                        modifier = Modifier
                            .fillMaxWidth()
                            .heightIn(min = 150.dp, max = 280.dp)
                    )
                    Row(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                        OutlinedButton(
                            onClick = {
                                // 剪贴板里通常正是刚从 AI 那边复制回来的内容，省一次长按粘贴
                                clipboard.getText()?.text
                                    ?.let { pasteText = it; pasteError = null }
                                    ?: report(false, "剪贴板是空的")
                            },
                            modifier = Modifier.weight(1f)
                        ) {
                            Icon(Icons.Default.ContentPaste, null, Modifier.size(18.dp))
                            Spacer(Modifier.width(6.dp))
                            Text("从剪贴板粘贴")
                        }
                        Button(
                            enabled = pasteText.isNotBlank(),
                            onClick = {
                                BackupData.parseBundle(pasteText)
                                    .onSuccess { pendingImport = it to "粘贴的内容" }
                                    // 失败就地报错、内容不清空，改两个字就能重试
                                    .onFailure { pasteError = it.message ?: "解析失败" }
                            },
                            modifier = Modifier.weight(1f)
                        ) { Text("解析并导入") }
                    }
                }

                // ── 从文件导入 ──
                SectionCard("从文件导入", "读取 Await 导出的备份文件，或任何符合格式的 JSON") {
                    OutlinedButton(
                        onClick = { importLauncher.launch(arrayOf("*/*")) },
                        modifier = Modifier.fillMaxWidth()
                    ) {
                        Icon(Icons.Default.FileDownload, null, Modifier.size(18.dp))
                        Spacer(Modifier.width(6.dp))
                        Text("选择文件")
                    }
                }

                // ── AI 迁移 ──
                SectionCard(
                    "从其他应用迁移",
                    "把提示词连同旧应用的截图发给 AI，它会返回可直接粘贴的 JSON"
                ) {
                    Surface(
                        color = MaterialTheme.colorScheme.surfaceVariant,
                        shape = MaterialTheme.shapes.small
                    ) {
                        Text(
                            BackupData.AI_PROMPT,
                            style = MaterialTheme.typography.bodySmall,
                            fontFamily = FontFamily.Monospace,
                            modifier = Modifier
                                // 提示词较长，限高后自己滚动，免得把下面的按钮顶下去
                                .heightIn(max = 220.dp)
                                .padding(10.dp)
                                .verticalScroll(rememberScrollState())
                        )
                    }
                    Button(
                        onClick = {
                            clipboard.setText(AnnotatedString(BackupData.AI_PROMPT))
                            // Android 13+ 系统自己会弹复制提示，这里再给一条应用内回执，
                            // 低版本上它就是唯一的反馈
                            report(true, "提示词已复制，粘贴给 AI 即可")
                        },
                        modifier = Modifier.fillMaxWidth()
                    ) {
                        Icon(Icons.Default.ContentCopy, null, Modifier.size(18.dp))
                        Spacer(Modifier.width(6.dp))
                        Text("复制提示词")
                    }
                    Text(
                        "导入前建议先到「备份与恢复」导出一份，结果不理想可以覆盖导入还原。",
                        style = MaterialTheme.typography.labelSmall,
                        color = MaterialTheme.colorScheme.onSurfaceVariant
                    )
                }
            }
        }
    }

    pendingImport?.let { (bundle, from) ->
        ImportConfirmDialog(
            bundle = bundle,
            from = from,
            viewModel = viewModel,
            onDismiss = { pendingImport = null },
            onDone = { ok, msg ->
                pendingImport = null
                // 导入成功后把粘贴框清空，免得再点一次「解析并导入」重复导一遍
                if (ok) { pasteText = ""; pasteError = null }
                report(ok, msg)
            }
        )
    }
}

/**
 * 导入方式二次确认（合并 / 覆盖）。
 *
 * 「导入日程」和「备份与恢复」（云端恢复那条路）都要用同一个确认框，抽出来共用。
 */
@Composable
internal fun ImportConfirmDialog(
    bundle: BackupData.ImportBundle,
    from: String,
    viewModel: EventViewModel,
    onDismiss: () -> Unit,
    onDone: (Boolean, String) -> Unit
) {
    val context = LocalContext.current
    val scope = rememberCoroutineScope()

    fun apply(mode: BackupData.Mode) {
        scope.launch {
            // 导入要写存储并重排全部闹钟，放到 IO 线程避免卡界面
            val r = withContext(Dispatchers.IO) {
                BackupData.applyImport(context, bundle.events, mode, bundle.groups)
            }
            viewModel.reload()
            onDone(
                true,
                if (mode == BackupData.Mode.MERGE)
                    "已合并导入：新增 ${r.added} 条，覆盖 ${r.updated} 条，共 ${r.total} 条"
                else
                    "已覆盖导入：当前共 ${r.total} 条日程"
            )
        }
    }

    AlertDialog(
        onDismissRequest = onDismiss,
        title = { Text("导入 ${bundle.events.size} 条日程") },
        text = {
            Column(verticalArrangement = Arrangement.spacedBy(8.dp)) {
                Text("来源：$from", style = MaterialTheme.typography.bodySmall)
                BackupData.Mode.entries.forEach { mode ->
                    Column {
                        Text(mode.label, fontWeight = FontWeight.SemiBold)
                        Text(
                            mode.desc,
                            style = MaterialTheme.typography.bodySmall,
                            color = MaterialTheme.colorScheme.onSurfaceVariant
                        )
                    }
                }
            }
        },
        confirmButton = {
            TextButton(onClick = { apply(BackupData.Mode.MERGE) }) { Text("合并导入") }
        },
        dismissButton = {
            TextButton(onClick = { apply(BackupData.Mode.REPLACE) }) {
                Text("覆盖导入", color = MaterialTheme.colorScheme.error)
            }
        }
    )
}
