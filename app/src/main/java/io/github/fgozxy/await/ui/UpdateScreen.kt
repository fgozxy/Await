package io.github.fgozxy.await.ui

import android.content.Intent
import android.net.Uri
import androidx.activity.compose.rememberLauncherForActivityResult
import androidx.activity.result.contract.ActivityResultContracts
import androidx.compose.foundation.layout.*
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.verticalScroll
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.Close
import androidx.compose.material3.*
import androidx.compose.runtime.*
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.unit.dp
import androidx.compose.ui.window.Dialog
import androidx.compose.ui.window.DialogProperties
import io.github.fgozxy.await.update.AutoUpdate
import io.github.fgozxy.await.update.ReleaseInfo
import io.github.fgozxy.await.update.UpdateException
import io.github.fgozxy.await.update.UpdateManager
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.Job
import kotlinx.coroutines.launch
import kotlinx.coroutines.Dispatchers
import java.io.File

@OptIn(ExperimentalMaterial3Api::class)
@Composable
fun UpdateScreen(initial: ReleaseInfo? = null, onDismiss: () -> Unit) {
    val context = LocalContext.current
    val scope = rememberCoroutineScope()
    val current = remember { UpdateManager.currentVersion(context) }
    var release by remember { mutableStateOf(initial) }
    var result by remember { mutableStateOf("") }
    var busy by remember { mutableStateOf(false) }
    var progress by remember { mutableStateOf<Int?>(null) }
    var downloadJob by remember { mutableStateOf<Job?>(null) }
    var ready by remember { mutableStateOf<File?>(null) }
    var automatic by remember { mutableStateOf(AutoUpdate.enabled(context)) }
    val permission = rememberLauncherForActivityResult(ActivityResultContracts.StartActivityForResult()) {
        val apk = ready
        if (apk != null && UpdateManager.canInstall(context)) {
            try { UpdateManager.install(context, apk); result = "请在系统安装界面确认更新" }
            catch (_: Exception) { result = "无法打开系统安装界面，请从 GitHub 下载后安装" }
        } else result = "未允许安装应用；开启权限后可点击安装更新"
    }

    fun check() {
        busy = true
        result = "正在检查更新…"
        scope.launch {
            try {
                release = UpdateManager.checkLatest(current)
                ready = null
                result = if (release == null) "当前已是最新版本" else "发现新版本 ${release!!.versionName}"
            } catch (e: CancellationException) { throw e }
              catch (e: UpdateException) { result = e.message.orEmpty() }
              catch (_: Exception) { result = "检查更新失败，请稍后重试" }
              finally { busy = false }
        }
    }

    fun install(apk: File) {
        try {
            if (UpdateManager.canInstall(context)) {
                UpdateManager.install(context, apk)
                result = "请在系统安装界面确认更新"
            } else {
                result = "请允许 Await 安装应用，返回后继续安装"
                permission.launch(UpdateManager.permissionIntent(context))
            }
        } catch (_: Exception) { result = "无法打开安装或权限界面，请从 GitHub 下载后安装" }
    }

    fun download(info: ReleaseInfo) {
        busy = true
        progress = 0
        result = "正在下载更新…"
        downloadJob = scope.launch {
            try {
                val apk = UpdateManager.download(context, info) { value ->
                    // 下载在 IO 线程；Compose 状态回到主线程更新。
                    scope.launch(Dispatchers.Main) { if (downloadJob?.isActive == true) progress = value }
                }
                ready = apk
                result = "下载完成，安装包已验证"
                install(apk)
            } catch (e: CancellationException) { throw e }
              catch (e: UpdateException) { result = e.message.orEmpty() }
              catch (e: IllegalArgumentException) { result = e.message ?: "安装包验证失败，请重试" }
              catch (_: Exception) { result = "下载或验证失败，请重试" }
              finally { busy = false; progress = null; downloadJob = null }
        }
    }

    LaunchedEffect(Unit) { if (initial == null) check() else result = "发现新版本 ${initial.versionName}" }

    Dialog(onDismissRequest = onDismiss, properties = DialogProperties(usePlatformDefaultWidth = false)) {
        Scaffold(
            modifier = Modifier.fillMaxSize(),
            topBar = { TopAppBar(title = { Text("软件更新") }, navigationIcon = {
                IconButton(onClick = onDismiss) { Icon(Icons.Default.Close, "关闭") }
            }) }
        ) { padding ->
            Column(Modifier.padding(padding).padding(20.dp).fillMaxSize().verticalScroll(rememberScrollState()),
                verticalArrangement = Arrangement.spacedBy(16.dp)) {
                Text("当前版本 $current", style = MaterialTheme.typography.titleMedium)
                Row {
                    Checkbox(checked = automatic, onCheckedChange = { automatic = it; AutoUpdate.setEnabled(context, it) })
                    Text("打开软件时自动检查更新（每天一次）", modifier = Modifier.padding(top = 12.dp))
                }
                Text(result)
                if (busy) {
                    progress?.let { LinearProgressIndicator(progress = { it / 100f }, modifier = Modifier.fillMaxWidth()) }
                        ?: LinearProgressIndicator(modifier = Modifier.fillMaxWidth())
                    progress?.let { Text("已下载 $it%") }
                }
                Button(onClick = { check() }, enabled = !busy) { Text("检查更新") }
                release?.let { info ->
                    HorizontalDivider()
                    Text("新版本 ${info.versionName}", style = MaterialTheme.typography.titleLarge)
                    Text("安装包 %.1f MB".format(info.sizeBytes / 1024.0 / 1024.0))
                    Text(info.notes.ifBlank { "此版本没有更新说明" })
                    Button(enabled = !busy, onClick = {
                        val apk = ready
                        if (apk != null && apk.isFile) install(apk) else download(info)
                    }) { Text(if (ready == null) "下载并安装" else "安装更新") }
                }
                if (progress != null) {
                    TextButton(onClick = { result = "已取消下载"; downloadJob?.cancel() }) { Text("取消下载") }
                }
                TextButton(onClick = {
                    runCatching { context.startActivity(Intent(Intent.ACTION_VIEW, Uri.parse(
                        release?.releaseUrl ?: "https://github.com/fgozxy/Await/releases/latest"))) }
                        .onFailure { result = "无法打开浏览器" }
                }) { Text("在 GitHub 查看发布版本") }
            }
        }
    }
}
