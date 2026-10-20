package io.github.fgozxy.await.ui

import android.content.Intent
import android.net.Uri
import androidx.activity.compose.rememberLauncherForActivityResult
import androidx.activity.result.contract.ActivityResultContracts
import androidx.compose.foundation.Image
import androidx.compose.foundation.layout.*
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.automirrored.outlined.OpenInNew
import androidx.compose.material.icons.outlined.Update
import androidx.compose.material3.*
import androidx.compose.runtime.*
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.res.painterResource
import io.github.fgozxy.await.R
import androidx.compose.ui.unit.dp
import io.github.fgozxy.await.ui.components.*
import io.github.fgozxy.await.update.AutoUpdate
import io.github.fgozxy.await.update.ReleaseInfo
import io.github.fgozxy.await.update.UpdateException
import io.github.fgozxy.await.update.UpdateManager
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.Job
import kotlinx.coroutines.launch
import java.io.File

@OptIn(ExperimentalMaterial3Api::class)
@Composable
fun UpdateScreen() {
    val context = LocalContext.current
    val scope = rememberCoroutineScope()
    val toaster = LocalToaster.current
    val current = remember { UpdateManager.currentVersion(context) }
    val initial = remember { UpdateState.available.value }
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
                UpdateState.available.value = release
                ready = null
                result = if (release == null) "当前已是最新版本" else ""
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

    LaunchedEffect(Unit) { if (initial == null) check() }

    // 操作按钮固定在底部：更新说明再长也不用下滑去找
    PageScaffold(title = "软件更新", bottomBar = {
        Surface(color = MaterialTheme.colorScheme.surfaceContainer) {
            Column(
                Modifier.fillMaxWidth().navigationBarsPadding().padding(horizontal = 16.dp, vertical = 12.dp),
                verticalArrangement = Arrangement.spacedBy(8.dp)
            ) {
                progress?.let {
                    LinearProgressIndicator(progress = { it / 100f }, modifier = Modifier.fillMaxWidth())
                }
                if (result.isNotBlank() || progress != null) {
                    Text(
                        progress?.let { "已下载 $it%" } ?: result,
                        style = MaterialTheme.typography.bodyMedium,
                        color = MaterialTheme.colorScheme.onSurfaceVariant
                    )
                }
                Row(horizontalArrangement = Arrangement.spacedBy(8.dp), verticalAlignment = Alignment.CenterVertically) {
                    if (progress != null) {
                        OutlinedButton(
                            onClick = { toaster.show("已取消下载"); downloadJob?.cancel() },
                            modifier = Modifier.weight(1f)
                        ) { Text("取消下载") }
                    }
                    val info = release
                    Button(
                        enabled = !busy,
                        modifier = Modifier.weight(1f).heightIn(min = 48.dp),
                        onClick = {
                            val apk = ready
                            when {
                                info == null -> check()
                                apk != null && apk.isFile -> install(apk)
                                else -> download(info)
                            }
                        }
                    ) {
                        if (busy && progress == null) {
                            CircularProgressIndicator(Modifier.size(18.dp), strokeWidth = 2.dp,
                                color = LocalContentColor.current)
                            Spacer(Modifier.width(8.dp))
                        }
                        Text(when {
                            info == null -> if (busy) "正在检查…" else "检查更新"
                            ready != null -> "安装更新"
                            else -> "下载并安装"
                        })
                    }
                }
            }
        }
    }) { padding ->
        PageColumn(padding) {
            Column(Modifier.fillMaxWidth().padding(vertical = 8.dp), horizontalAlignment = Alignment.CenterHorizontally) {
                // 与桌面图标同一组图层，按 108dp 画布裁出中间 72dp 可见区
                Box(Modifier.size(80.dp).clip(RoundedCornerShape(24.dp))) {
                    listOf(R.drawable.ic_launcher_background, R.drawable.ic_launcher_foreground).forEach {
                        Image(
                            painterResource(it), null,
                            Modifier.requiredSize(120.dp).align(Alignment.Center)
                        )
                    }
                }
                Spacer(Modifier.height(12.dp))
                Text("Await", style = MaterialTheme.typography.titleLarge)
                Text("版本 $current", style = MaterialTheme.typography.bodyMedium,
                    color = MaterialTheme.colorScheme.onSurfaceVariant)
            }

            release?.let { info ->
                Surface(
                    shape = RoundedCornerShape(20.dp),
                    color = MaterialTheme.colorScheme.primaryContainer,
                    contentColor = MaterialTheme.colorScheme.onPrimaryContainer,
                    modifier = Modifier.fillMaxWidth()
                ) {
                    Column(Modifier.padding(20.dp), verticalArrangement = Arrangement.spacedBy(10.dp)) {
                        Text("新版本 ${info.versionName}", style = MaterialTheme.typography.titleLarge)
                        Text("安装包 %.1f MB".format(info.sizeBytes / 1024.0 / 1024.0),
                            style = MaterialTheme.typography.labelLarge)
                        Text(info.notes.ifBlank { "此版本没有更新说明" }, style = MaterialTheme.typography.bodyMedium)
                    }
                }
            }

            PrefGroup(footer = "更新来自 GitHub Releases，优先经 HubProxy 加速；下载后校验 SHA-256、包名和签名。") {
                SwitchRow(
                    title = "自动检查更新",
                    subtitle = "打开软件时每天检查一次",
                    icon = Icons.Outlined.Update,
                    checked = automatic,
                    onChange = { automatic = it; AutoUpdate.setEnabled(context, it) }
                )
                PrefRow(
                    title = "在 GitHub 查看发布版本",
                    icon = Icons.AutoMirrored.Outlined.OpenInNew,
                    onClick = {
                        runCatching { context.startActivity(Intent(Intent.ACTION_VIEW, Uri.parse(
                            release?.releaseUrl ?: "https://github.com/fgozxy/Await/releases/latest"))) }
                            .onFailure { toaster.show("无法打开浏览器") }
                    }
                )
            }
        }
    }
}
