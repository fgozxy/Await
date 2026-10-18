package io.github.fgozxy.await.ui

import android.net.Uri
import androidx.activity.compose.rememberLauncherForActivityResult
import androidx.activity.result.contract.ActivityResultContracts
import androidx.compose.foundation.layout.*
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.foundation.verticalScroll
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.outlined.ContentCopy
import androidx.compose.material.icons.outlined.ContentPaste
import androidx.compose.material.icons.outlined.FileOpen
import androidx.compose.material.icons.automirrored.outlined.MergeType
import androidx.compose.material.icons.outlined.SwapHoriz
import androidx.compose.material3.*
import androidx.compose.runtime.*
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.LocalClipboardManager
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.text.AnnotatedString
import androidx.compose.ui.text.font.FontFamily
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.dp
import io.github.fgozxy.await.data.BackupData
import io.github.fgozxy.await.ui.components.*
import io.github.fgozxy.await.vm.EventViewModel
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext

/**
 * 「导入日程」：粘贴 JSON / 从文件导入 / AI 迁移提示词。
 *
 * 从「备份与恢复」里独立出来的：那一页的主线是「把自己的数据存走再拿回来」，
 * 而导入更常见的用途其实是从别的倒数日应用搬家——两件事的读者不是同一批人。
 */
@OptIn(ExperimentalMaterial3Api::class)
@Composable
fun ImportScreen(viewModel: EventViewModel) {
    val context = LocalContext.current
    val scope = rememberCoroutineScope()
    val clipboard = LocalClipboardManager.current
    val toaster = LocalToaster.current

    // 粘贴导入：输入框内容 + 上一次解析失败的原因
    var pasteText by remember { mutableStateOf("") }
    var pasteError by remember { mutableStateOf<String?>(null) }
    var fileError by remember { mutableStateOf<String?>(null) }

    // 待确认导入：解析出来的日程 + 来源说明
    var pendingImport by remember { mutableStateOf<Pair<BackupData.ImportBundle, String>?>(null) }

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
            r.onSuccess { fileError = null; pendingImport = it to "所选文件" }
                .onFailure { fileError = "导入失败：${it.message}" }
        }
    }

    PageScaffold(title = "导入日程") { padding ->
        PageColumn(padding) {
            PrefGroup("粘贴 JSON", footer = "外面裹着的 ``` 代码块、前后多余的说明文字都不用删") {
                Column(Modifier.padding(horizontal = 16.dp, vertical = 12.dp), verticalArrangement = Arrangement.spacedBy(12.dp)) {
                    OutlinedTextField(
                        value = pasteText,
                        onValueChange = { pasteText = it; pasteError = null },
                        placeholder = { Text("[{\"title\": \"房租\", \"date\": \"2026-10-01\"}]") },
                        isError = pasteError != null,
                        supportingText = pasteError?.let { { Text(it) } },
                        textStyle = MaterialTheme.typography.bodySmall.copy(fontFamily = FontFamily.Monospace),
                        shape = RoundedCornerShape(12.dp),
                        modifier = Modifier.fillMaxWidth().heightIn(min = 140.dp, max = 260.dp)
                    )
                    Row(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                        OutlinedButton(
                            onClick = {
                                // 剪贴板里通常正是刚从 AI 那边复制回来的内容，省一次长按粘贴
                                clipboard.getText()?.text
                                    ?.let { pasteText = it; pasteError = null }
                                    ?: toaster.show("剪贴板是空的")
                            },
                            modifier = Modifier.weight(1f)
                        ) {
                            Icon(Icons.Outlined.ContentPaste, null, Modifier.size(18.dp))
                            Spacer(Modifier.width(6.dp))
                            Text("粘贴")
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
            }

            PrefGroup("从文件导入") {
                PrefRow(
                    title = "选择文件",
                    subtitle = fileError ?: "Await 导出的备份文件，或任何符合格式的 JSON",
                    subtitleIsError = fileError != null,
                    icon = Icons.Outlined.FileOpen,
                    onClick = { importLauncher.launch(arrayOf("*/*")) }
                )
            }

            PrefGroup(
                "从其他应用迁移",
                footer = "把提示词连同旧应用的截图发给 AI，再把返回的 JSON 粘贴到上方。导入前建议先在「备份与恢复」导出一份。"
            ) {
                Surface(
                    color = MaterialTheme.colorScheme.surfaceContainerHighest,
                    shape = RoundedCornerShape(12.dp),
                    modifier = Modifier.padding(horizontal = 16.dp, vertical = 8.dp)
                ) {
                    Text(
                        BackupData.AI_PROMPT,
                        style = MaterialTheme.typography.bodySmall,
                        fontFamily = FontFamily.Monospace,
                        modifier = Modifier
                            // 提示词较长，限高后自己滚动
                            .heightIn(max = 200.dp)
                            .verticalScroll(rememberScrollState())
                            .padding(12.dp)
                    )
                }
                PrefRow(
                    title = "复制提示词",
                    icon = Icons.Outlined.ContentCopy,
                    onClick = {
                        clipboard.setText(AnnotatedString(BackupData.AI_PROMPT))
                        // Android 13+ 系统自己会弹复制提示，低版本上这条就是唯一的反馈
                        toaster.show("提示词已复制，粘贴给 AI 即可")
                    }
                )
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
                toaster.show(msg)
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
    var confirmReplace by remember { mutableStateOf(false) }

    fun apply(mode: BackupData.Mode) {
        scope.launch {
            // 导入要写存储并重排全部闹钟，放到 IO 线程避免卡界面
            val r = withContext(Dispatchers.IO) {
                BackupData.applyImport(
                    context,
                    bundle.events,
                    mode,
                    bundle.groups,
                    bundle.defaultReminderTime
                )
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

    if (confirmReplace) {
        ConfirmDialog(
            title = "覆盖现有日程？",
            text = "现有日程会被清空，只保留「$from」中的 ${bundle.events.size} 条。",
            confirm = "覆盖",
            destructive = true,
            onDismiss = { confirmReplace = false }
        ) { apply(BackupData.Mode.REPLACE) }
        return
    }

    AlertDialog(
        onDismissRequest = onDismiss,
        title = { Text("导入 ${bundle.events.size} 条日程") },
        text = {
            Column(verticalArrangement = Arrangement.spacedBy(12.dp)) {
                Text("来源：$from", style = MaterialTheme.typography.bodyMedium)
                bundle.defaultReminderTime?.let {
                    Text("备份默认通知时间：${it.display()}。覆盖导入会恢复此时间；合并导入沿用本机默认时间。",
                        style = MaterialTheme.typography.bodySmall)
                }
                BackupData.Mode.entries.forEach { mode ->
                    Surface(
                        onClick = { if (mode == BackupData.Mode.MERGE) apply(mode) else confirmReplace = true },
                        shape = RoundedCornerShape(16.dp),
                        color = MaterialTheme.colorScheme.surfaceContainerHighest,
                        modifier = Modifier.fillMaxWidth()
                    ) {
                        Row(Modifier.padding(14.dp)) {
                            Icon(
                                if (mode == BackupData.Mode.MERGE) Icons.AutoMirrored.Outlined.MergeType else Icons.Outlined.SwapHoriz,
                                null,
                                tint = if (mode == BackupData.Mode.MERGE) MaterialTheme.colorScheme.primary
                                else MaterialTheme.colorScheme.error
                            )
                            Spacer(Modifier.width(12.dp))
                            Column {
                                Text(mode.label + "导入", fontWeight = FontWeight.SemiBold)
                                Text(
                                    mode.desc,
                                    style = MaterialTheme.typography.bodySmall,
                                    color = MaterialTheme.colorScheme.onSurfaceVariant
                                )
                            }
                        }
                    }
                }
            }
        },
        confirmButton = { TextButton(onClick = onDismiss) { Text("取消") } }
    )
}
