package io.github.fgozxy.await.ui

import androidx.compose.animation.AnimatedContent
import androidx.compose.animation.core.tween
import androidx.compose.animation.fadeIn
import androidx.compose.animation.fadeOut
import androidx.compose.animation.togetherWith
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.Event
import androidx.compose.material.icons.filled.Folder
import androidx.compose.material.icons.filled.Settings
import androidx.compose.material.icons.outlined.Event
import androidx.compose.material.icons.outlined.Folder
import androidx.compose.material.icons.outlined.Settings
import androidx.compose.material3.*
import androidx.compose.runtime.*
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.vector.ImageVector
import androidx.compose.ui.platform.LocalContext
import androidx.lifecycle.Lifecycle
import androidx.lifecycle.LifecycleEventObserver
import androidx.lifecycle.compose.LocalLifecycleOwner
import io.github.fgozxy.await.notify.NotificationChannel
import io.github.fgozxy.await.ui.components.LocalToaster
import io.github.fgozxy.await.ui.components.Toaster
import io.github.fgozxy.await.ui.components.rememberHaptics
import io.github.fgozxy.await.update.AutoUpdate
import io.github.fgozxy.await.update.ReleaseInfo
import io.github.fgozxy.await.vm.EventViewModel
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.launch

/** 自动检查到的新版本：设置页显示角标，更新页直接展示。 */
object UpdateState {
    val available = mutableStateOf<ReleaseInfo?>(null)
}

private data class Tab(val key: String, val label: String, val icon: ImageVector, val selectedIcon: ImageVector)

private val TABS = listOf(
    Tab(Nav.EVENTS, "日程", Icons.Outlined.Event, Icons.Filled.Event),
    Tab(Nav.GROUPS, "分组", Icons.Outlined.Folder, Icons.Filled.Folder),
    Tab(Nav.SETTINGS, "设置", Icons.Outlined.Settings, Icons.Filled.Settings)
)

/** 应用骨架：底部导航三个标签页，其余页面压栈打开。 */
@Composable
fun AwaitRoot(viewModel: EventViewModel) {
    val context = LocalContext.current
    val scope = rememberCoroutineScope()
    val snackbar = remember { SnackbarHostState() }
    val toaster = remember(snackbar, scope) { Toaster(snackbar, scope) }
    var promptUpdate by remember { mutableStateOf<ReleaseInfo?>(null) }

    val lifecycleOwner = LocalLifecycleOwner.current
    DisposableEffect(lifecycleOwner) {
        val observer = LifecycleEventObserver { _, event ->
            if (event == Lifecycle.Event.ON_RESUME) scope.launch {
                try {
                    AutoUpdate.check(context)?.let { UpdateState.available.value = it; promptUpdate = it }
                } catch (e: CancellationException) { throw e }
                catch (_: Exception) { /* 自动检查失败时，仍可在设置中手动检查。 */ }
            }
        }
        lifecycleOwner.lifecycle.addObserver(observer)
        onDispose { lifecycleOwner.lifecycle.removeObserver(observer) }
    }

    CompositionLocalProvider(LocalToaster provides toaster) {
        StackHost(
            root = { RootTabs(viewModel) },
            page = { route ->
                when (route.key) {
                    "edit" -> EditEventScreen(viewModel, route.arg.toLongOrNull())
                    "notifications" -> NotificationScreen()
                    "cloud" -> CloudDeploymentScreen()
                    "channel" -> NotificationChannel.entries.firstOrNull { it.wireName == route.arg }
                        ?.let { CloudChannelScreen(it) }
                    "backup" -> BackupScreen(viewModel)
                    "import" -> ImportScreen(viewModel)
                    "update" -> UpdateScreen()
                }
            }
        )
    }

    promptUpdate?.let { update ->
        AlertDialog(
            onDismissRequest = { promptUpdate = null },
            title = { Text("发现新版本 ${update.versionName}") },
            text = { Text("可在软件内下载并安装更新。") },
            confirmButton = {
                TextButton(onClick = { promptUpdate = null; Nav.go("update") }) { Text("查看更新") }
            },
            dismissButton = { TextButton(onClick = { promptUpdate = null }) { Text("稍后") } }
        )
    }
}

@Composable
private fun RootTabs(viewModel: EventViewModel) {
    val haptics = rememberHaptics()
    val tab = Nav.tab.value
    val hasUpdate = UpdateState.available.value != null
    Column(Modifier.fillMaxSize()) {
        Box(Modifier.weight(1f)) {
            AnimatedContent(
                targetState = tab,
                transitionSpec = { fadeIn(tween(220, delayMillis = 60)) togetherWith fadeOut(tween(90)) },
                label = "tab"
            ) { key ->
                when (key) {
                    Nav.GROUPS -> GroupsScreen(viewModel)
                    Nav.SETTINGS -> SettingsScreen()
                    else -> HomeScreen(viewModel)
                }
            }
        }
        NavigationBar {
            TABS.forEach { t ->
                val selected = tab == t.key
                NavigationBarItem(
                    selected = selected,
                    onClick = { if (!selected) haptics.tick(); Nav.go(t.key) },
                    label = { Text(t.label) },
                    icon = {
                        BadgedBox(badge = { if (t.key == Nav.SETTINGS && hasUpdate) Badge() }) {
                            Icon(if (selected) t.selectedIcon else t.icon, t.label)
                        }
                    }
                )
            }
        }
    }
}
