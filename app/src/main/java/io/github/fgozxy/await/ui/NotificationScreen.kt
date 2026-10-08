package io.github.fgozxy.await.ui

import android.Manifest
import android.content.Intent
import android.content.pm.PackageManager
import android.net.Uri
import android.os.Build
import android.provider.Settings
import androidx.activity.compose.rememberLauncherForActivityResult
import androidx.activity.result.contract.ActivityResultContracts
import androidx.compose.foundation.layout.*
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.selection.toggleable
import androidx.compose.foundation.text.selection.SelectionContainer
import androidx.compose.foundation.verticalScroll
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.Close
import androidx.compose.material3.*
import androidx.compose.runtime.*
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.semantics.Role
import androidx.compose.ui.unit.dp
import androidx.compose.ui.window.Dialog
import androidx.compose.ui.window.DialogProperties
import androidx.core.content.ContextCompat
import androidx.lifecycle.Lifecycle
import androidx.lifecycle.LifecycleEventObserver
import androidx.lifecycle.compose.LocalLifecycleOwner
import com.google.gson.Gson
import io.github.fgozxy.await.notify.LocalNotifications
import io.github.fgozxy.await.notify.NotificationChannel
import io.github.fgozxy.await.notify.NotificationChannels
import io.github.fgozxy.await.notify.NotificationHealth
import io.github.fgozxy.await.notify.ReminderSettings
import io.github.fgozxy.await.sync.*
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext

@Composable
fun rememberSyncStatus(): String = rememberNotificationHealth().details

@Composable
fun rememberNotificationHealth(): NotificationHealth {
    val context = LocalContext.current
    val owner = LocalLifecycleOwner.current
    val sp = remember { SyncSettings.prefs(context) }
    var revision by remember { mutableIntStateOf(0) }
    DisposableEffect(sp, owner) {
        val listener = android.content.SharedPreferences.OnSharedPreferenceChangeListener { _, _ -> revision++ }
        val observer = LifecycleEventObserver { _, event -> if (event == Lifecycle.Event.ON_RESUME) revision++ }
        sp.registerOnSharedPreferenceChangeListener(listener)
        owner.lifecycle.addObserver(observer)
        onDispose { sp.unregisterOnSharedPreferenceChangeListener(listener); owner.lifecycle.removeObserver(observer) }
    }
    val localAllowed = LocalNotifications.allowed(context)
    val exactAllowed = LocalNotifications.exactAllowed(context)
    return remember(revision, localAllowed, exactAllowed) {
        NotificationHealth.evaluate(
            channels = NotificationChannels.load(context),
            localAllowed = localAllowed,
            exactAllowed = exactAllowed,
            cloudConfigured = SyncSettings.load(context).isValid,
            deploymentReady = CloudDeployment.isReady(context),
            canSync = CloudDeployment.canSync(context),
            availableRemoteChannels = CloudDeployment.available(context),
            lastError = sp.getString("last_error", "").orEmpty(),
            revision = sp.getLong("revision", 0),
            syncedRevision = sp.getLong("synced_revision", 0),
            retryPending = sp.getBoolean("retry_pending", false)
        )
    }
}

@OptIn(ExperimentalMaterial3Api::class)
@Composable
fun NotificationScreen(onDismiss: () -> Unit) {
    val context = LocalContext.current
    val scope = rememberCoroutineScope()
    var channels by remember { mutableStateOf(NotificationChannels.load(context)) }
    var defaultTime by remember { mutableStateOf(ReminderSettings.load(context)) }
    var showDefaultTimePicker by remember { mutableStateOf(false) }
    var showCloud by remember { mutableStateOf(false) }
    var configuringChannel by remember { mutableStateOf<NotificationChannel?>(null) }
    var busy by remember { mutableStateOf(false) }
    var result by remember { mutableStateOf("") }
    val syncStatus = rememberSyncStatus()
    val permission = rememberLauncherForActivityResult(ActivityResultContracts.RequestPermission()) {
        LocalNotifications.reschedule(context)
        result = if (it) "软件通知权限已开启" else "软件通知权限未开启，可在系统设置中允许"
    }

    fun requestLocalPermission() {
        if (Build.VERSION.SDK_INT >= 33 && ContextCompat.checkSelfPermission(context,
                Manifest.permission.POST_NOTIFICATIONS) != PackageManager.PERMISSION_GRANTED) {
            permission.launch(Manifest.permission.POST_NOTIFICATIONS)
        } else {
            runCatching { context.startActivity(Intent(Settings.ACTION_APP_NOTIFICATION_SETTINGS)
                .putExtra(Settings.EXTRA_APP_PACKAGE, context.packageName)) }
                .onFailure { result = "无法打开系统设置，请手动允许 Await 通知" }
        }
    }

    fun save() {
        if (channels.isEmpty()) { result = "请至少选择一种通知渠道"; return }
        val remote = NotificationChannels.remote(channels)
        if (remote.isNotEmpty() && !CloudDeployment.canSync(context)) {
            result = "请先完成云端部署配置并验证连接"
            return
        }
        if (!CloudDeployment.available(context).containsAll(remote)) {
            result = "请先配置所选消息渠道，再开启通知"
            return
        }
        busy = true
        scope.launch {
            val successful = withContext(Dispatchers.IO) {
                try {
                    synchronized(SyncCoordinator.lock) {
                        NotificationChannels.save(context, channels)
                        SyncSettings.prefs(context).edit().putString("last_error", "").apply()
                        SyncCoordinator.changed(context)
                    }
                    true
                } catch (_: Exception) { false }
            }
            result = if (successful) "通知渠道已保存" +
                (if (SyncSettings.load(context).isValid) "；云端设置联网后自动同步" else "")
                else "保存失败，请稍后重试"
            busy = false
            if (successful && NotificationChannel.LOCAL in channels && !LocalNotifications.allowed(context)) {
                requestLocalPermission()
            }
        }
    }

    fun remoteTask(test: Boolean) {
        val draft = SyncSettings.load(context)
        if (!CloudDeployment.isReady(context)) { result = "请先验证云端部署配置"; return }
        val remote = NotificationChannels.remote(channels)
        if (test && remote.isEmpty()) { result = "请先选择 Telegram 或 ntfy"; return }
        busy = true
        scope.launch {
            try {
                val taskResult = withContext(Dispatchers.IO) {
                    val status = ServerClient.checkChannels(draft, remote)
                    CloudDeployment.record(context, draft, CloudDeployment.parse(status, SyncSettings.clientId(context)))
                    if (test) {
                        val response = ServerClient.request(draft, "POST", "/v1/test",
                            Gson().toJson(mapOf("notificationChannels" to remote)))
                        response["results"].asJsonObject.entrySet().joinToString("\n") { (name, value) ->
                            "$name：${value.asString}"
                        }
                    } else {
                        "已配置渠道：${status["availableChannels"].asJsonArray.joinToString { it.asString }}\n" +
                            "服务器日程：${status["eventCount"].asInt} 条\n" +
                            "待发送：${status["pending"].asInt} 条，失败：${status["failed"].asInt} 条" +
                            status["lastError"].asString.takeIf { it.isNotBlank() }?.let { "\n$it" }.orEmpty()
                    }
                }
                result = taskResult
            } catch (e: ServerException) {
                result = e.message.orEmpty()
            } catch (_: Exception) {
                result = "服务器返回的状态格式不正确"
            } finally {
                busy = false
            }
        }
    }

    Dialog(onDismissRequest = { if (!busy) onDismiss() }, properties = DialogProperties(usePlatformDefaultWidth = false)) {
        Scaffold(topBar = {
            TopAppBar(title = { Text("通知设置") }, navigationIcon = {
                IconButton(onClick = onDismiss, enabled = !busy) { Icon(Icons.Default.Close, "关闭") }
            })
        }) { padding ->
            Column(Modifier.padding(padding).fillMaxSize().verticalScroll(rememberScrollState()).padding(16.dp),
                verticalArrangement = Arrangement.spacedBy(16.dp)) {
                Text("可选择一个或多个通知渠道。日程默认使用下方时刻，开启日程的「精准时间」后可单独设置。")
                Text("默认通知时间", style = MaterialTheme.typography.titleMedium)
                Text(defaultTime.display(), style = MaterialTheme.typography.headlineMedium)
                Text("修改后，所有未开启精准时间的日程都会跟随；已开启的日程保留自定义时刻。")
                OutlinedButton(onClick = { showDefaultTimePicker = true }, enabled = !busy) { Text("修改默认时间") }
                HorizontalDivider()
                Row(Modifier.fillMaxWidth().toggleable(value = NotificationChannel.LOCAL in channels, enabled = !busy,
                    role = Role.Checkbox, onValueChange = { enabled ->
                        channels = if (enabled) channels + NotificationChannel.LOCAL else channels - NotificationChannel.LOCAL
                    }), verticalAlignment = Alignment.CenterVertically) {
                    Checkbox(checked = NotificationChannel.LOCAL in channels, onCheckedChange = null, enabled = !busy)
                    Text("软件通知")
                }
                if (NotificationChannel.LOCAL in channels) {
                    Text("软件通知是常规消息通知，可划走或点击查看，不持续响铃。在本机发送，无需服务器或网络。手机关机时无法通知，重新开机后补发最近 24 小时内已安排的提醒。")
                    OutlinedButton(onClick = ::requestLocalPermission, enabled = !busy) { Text("允许软件通知") }
                    if (Build.VERSION.SDK_INT >= 31) {
                        OutlinedButton(onClick = {
                            runCatching { context.startActivity(Intent(Settings.ACTION_REQUEST_SCHEDULE_EXACT_ALARM,
                                Uri.parse("package:${context.packageName}"))) }
                                .onFailure { result = "无法打开系统设置，请手动允许 Await 闹钟和提醒" }
                        }, enabled = !busy) { Text("允许精确提醒") }
                    }
                    OutlinedButton(onClick = {
                        result = if (LocalNotifications.show(context, "✅ Await 软件通知测试", "test"))
                            "软件测试通知已发送" else "请先允许软件通知权限"
                    }, enabled = !busy) { Text("测试软件通知") }
                }
                HorizontalDivider()
                Text("第一步：云端部署配置", style = MaterialTheme.typography.titleMedium)
                val deployed = CloudDeployment.canSync(context)
                Text(if (deployed) "云端连接已验证。可继续配置消息渠道。" else
                    "先部署 Await 服务并验证连接，再配置 Telegram 和 ntfy。软件通知可独立使用。")
                Button(onClick = { showCloud = true }, enabled = !busy) {
                    Text(if (deployed) "查看或修改云端部署" else "配置云端部署")
                }
                HorizontalDivider()
                Text("第二步：消息渠道", style = MaterialTheme.typography.titleMedium)
                listOf(NotificationChannel.TELEGRAM, NotificationChannel.NTFY).forEach { channel ->
                    val ready = deployed && channel.wireName in CloudDeployment.available(context)
                    val selected = channel in channels
                    val canToggle = !busy && (ready || selected)
                    Row(Modifier.fillMaxWidth().toggleable(value = selected, enabled = canToggle,
                        role = Role.Checkbox, onValueChange = { enabled ->
                            channels = if (enabled) channels + channel else channels - channel
                        }), verticalAlignment = Alignment.CenterVertically) {
                        Checkbox(checked = selected, onCheckedChange = null, enabled = canToggle)
                        Text(channel.label)
                    }
                    Text(when {
                        !deployed -> "完成云端部署配置后可设置此渠道"
                        ready -> "渠道已配置，可以勾选开启"
                        else -> "渠道尚未配置，请先填写参数"
                    }, style = MaterialTheme.typography.bodySmall)
                    OutlinedButton(onClick = { configuringChannel = channel },
                        enabled = !busy && CloudDeployment.canConfigureChannels(context)) {
                        Text("配置 ${channel.label}")
                    }
                }
                if (deployed && !CloudDeployment.canConfigureChannels(context)) {
                    Text("当前服务只支持服务器端配置渠道；升级 Await 云端服务后可直接在手机填写参数。")
                }
                Text("第三步：勾选渠道并保存", style = MaterialTheme.typography.titleMedium)
                Button(onClick = ::save, enabled = !busy) { Text("保存通知设置") }
                OutlinedButton(onClick = { remoteTask(true) }, enabled = !busy && deployed) { Text("测试所选云端渠道") }
                OutlinedButton(onClick = { remoteTask(false) }, enabled = !busy && deployed) { Text("查看服务器状态") }
                Text(syncStatus, color = MaterialTheme.colorScheme.primary)
                if (SyncSettings.load(context).isValid) {
                    Text("云端每 15 分钟自动同步；修改日程或设置后及时上传。后台省电限制可能延迟同步。",
                        style = MaterialTheme.typography.bodySmall)
                }
                if (busy) LinearProgressIndicator(Modifier.fillMaxWidth())
                if (result.isNotBlank()) Text(result)
                Text("关闭云端渠道后需完成一次同步，服务器才会取消待发提醒。同一服务器绑定一台手机；更换服务器后需停用旧服务器。",
                    style = MaterialTheme.typography.bodySmall)
                SelectionContainer { Text("本机安装 ID：${SyncSettings.clientId(context)}", style = MaterialTheme.typography.bodySmall) }
            }
        }
    }
    if (showDefaultTimePicker) {
        val timeState = rememberTimePickerState(defaultTime.hour, defaultTime.minute, true)
        AlertDialog(
            onDismissRequest = { if (!busy) showDefaultTimePicker = false },
            title = { Text("默认通知时间") },
            text = { TimePicker(timeState) },
            confirmButton = {
                TextButton(enabled = !busy, onClick = {
                    val selectedTime = ReminderSettings.Time(timeState.hour, timeState.minute)
                    busy = true
                    scope.launch {
                        try {
                            withContext(Dispatchers.IO) { ReminderSettings.save(context, selectedTime) }
                            defaultTime = selectedTime
                            showDefaultTimePicker = false
                            result = "默认时间已保存，本机提醒已重排；云端联网同步后生效"
                        } catch (_: Exception) {
                            result = "保存默认时间失败，请重试"
                        } finally {
                            busy = false
                        }
                    }
                }) { Text("保存") }
            },
            dismissButton = { TextButton(enabled = !busy, onClick = { showDefaultTimePicker = false }) { Text("取消") } }
        )
    }
    if (showCloud) CloudDeploymentScreen(onDismiss = { showCloud = false })
    configuringChannel?.let { channel ->
        CloudChannelScreen(channel, onDismiss = { configuringChannel = null })
    }

}
