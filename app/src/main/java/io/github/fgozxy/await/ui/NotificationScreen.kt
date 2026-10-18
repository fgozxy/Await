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
import androidx.compose.foundation.text.selection.SelectionContainer
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.automirrored.outlined.Send
import androidx.compose.material.icons.outlined.Alarm
import androidx.compose.material.icons.outlined.Campaign
import androidx.compose.material.icons.outlined.Cloud
import androidx.compose.material.icons.outlined.Dns
import androidx.compose.material.icons.outlined.Fingerprint
import androidx.compose.material.icons.outlined.NotificationsActive
import androidx.compose.material.icons.outlined.PhoneAndroid
import androidx.compose.material.icons.outlined.Schedule
import androidx.compose.material.icons.outlined.Forum
import androidx.compose.material3.*
import androidx.compose.runtime.*
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.LocalClipboardManager
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.text.AnnotatedString
import androidx.compose.ui.unit.dp
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
import io.github.fgozxy.await.ui.components.*
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
fun NotificationScreen() {
    val context = LocalContext.current
    val scope = rememberCoroutineScope()
    val toaster = LocalToaster.current
    val haptics = rememberHaptics()
    var channels by remember { mutableStateOf(NotificationChannels.load(context)) }
    var defaultTime by remember { mutableStateOf(ReminderSettings.load(context)) }
    var showDefaultTimePicker by remember { mutableStateOf(false) }
    var busy by remember { mutableStateOf(false) }
    var report by remember { mutableStateOf<Pair<String, String>?>(null) }
    val health = rememberNotificationHealth()
    // 健康状态随回到前台刷新，权限读数跟着这次重组一起更新
    val localAllowed = LocalNotifications.allowed(context)
    val exactAllowed = LocalNotifications.exactAllowed(context)
    val permission = rememberLauncherForActivityResult(ActivityResultContracts.RequestPermission()) {
        LocalNotifications.reschedule(context)
        toaster.show(if (it) "软件通知权限已开启" else "软件通知权限未开启，可在系统设置中允许")
    }

    fun requestLocalPermission() {
        if (Build.VERSION.SDK_INT >= 33 && ContextCompat.checkSelfPermission(context,
                Manifest.permission.POST_NOTIFICATIONS) != PackageManager.PERMISSION_GRANTED) {
            permission.launch(Manifest.permission.POST_NOTIFICATIONS)
        } else {
            runCatching { context.startActivity(Intent(Settings.ACTION_APP_NOTIFICATION_SETTINGS)
                .putExtra(Settings.EXTRA_APP_PACKAGE, context.packageName)) }
                .onFailure { toaster.show("无法打开系统设置，请手动允许 Await 通知") }
        }
    }

    /** 勾选即保存；校验规则与原「保存通知设置」一致，关闭渠道不受云端状态限制。 */
    fun toggle(channel: NotificationChannel, enabled: Boolean) {
        val next = if (enabled) channels + channel else channels - channel
        if (next.isEmpty()) { haptics.reject(); toaster.show("请至少保留一种通知渠道"); return }
        if (enabled && channel != NotificationChannel.LOCAL) {
            if (!CloudDeployment.canSync(context)) {
                haptics.reject(); toaster.show("请先在「云端服务」中验证连接", "去配置") { Nav.go("cloud") }; return
            }
            if (channel.wireName !in CloudDeployment.available(context)) {
                haptics.reject()
                toaster.show("请先配置 ${channel.label}", "去配置") { Nav.go("channel", channel.wireName) }
                return
            }
        }
        val previous = channels
        channels = next
        busy = true
        scope.launch {
            val successful = withContext(Dispatchers.IO) {
                try {
                    synchronized(SyncCoordinator.lock) {
                        NotificationChannels.save(context, next)
                        SyncSettings.prefs(context).edit().putString("last_error", "").apply()
                        SyncCoordinator.changed(context)
                    }
                    true
                } catch (_: Exception) { false }
            }
            busy = false
            if (!successful) {
                channels = previous
                toaster.show("保存失败，请稍后重试")
            } else if (enabled && channel == NotificationChannel.LOCAL && !LocalNotifications.allowed(context)) {
                requestLocalPermission()
            }
        }
    }

    fun remoteTask(test: Boolean) {
        val draft = SyncSettings.load(context)
        if (!CloudDeployment.isReady(context)) { toaster.show("请先在「云端服务」中验证连接"); return }
        val remote = NotificationChannels.remote(channels)
        if (test && remote.isEmpty()) { toaster.show("请先开启 Telegram 或 ntfy 通知"); return }
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
                report = (if (test) "测试结果" else "服务器状态") to taskResult
            } catch (e: ServerException) {
                report = "请求失败" to e.message.orEmpty()
            } catch (_: Exception) {
                report = "请求失败" to "服务器返回的状态格式不正确"
            } finally {
                busy = false
            }
        }
    }

    val deployed = CloudDeployment.canSync(context)
    val available = CloudDeployment.available(context)
    PageScaffold(title = "通知") { padding ->
        PageColumn(padding) {
            if (busy) LinearProgressIndicator(Modifier.fillMaxWidth())
            StatusCard(health.details, error = health.homeWarning != null)

            PrefGroup("默认时间", footer = "未开启「精准时间」的日程都在这个时刻提醒") {
                PrefRow(
                    title = "默认通知时间",
                    icon = Icons.Outlined.Schedule,
                    value = defaultTime.display(),
                    enabled = !busy,
                    onClick = { showDefaultTimePicker = true }
                )
            }

            PrefGroup("软件通知", footer = "在本机发送，无需服务器或网络。关机时无法通知，开机后补发最近 24 小时内的提醒。") {
                SwitchRow(
                    title = "软件通知",
                    subtitle = "可划走的普通通知，不持续响铃",
                    icon = Icons.Outlined.PhoneAndroid,
                    checked = NotificationChannel.LOCAL in channels,
                    enabled = !busy,
                    onChange = { toggle(NotificationChannel.LOCAL, it) }
                )
                if (NotificationChannel.LOCAL in channels) {
                    PrefRow(
                        title = "通知权限",
                        icon = Icons.Outlined.NotificationsActive,
                        value = if (localAllowed) "已允许" else "去允许",
                        subtitle = if (localAllowed) null else "未允许时收不到软件通知",
                        subtitleIsError = !localAllowed,
                        onClick = ::requestLocalPermission
                    )
                    if (Build.VERSION.SDK_INT >= 31) {
                        PrefRow(
                            title = "精确提醒",
                            icon = Icons.Outlined.Alarm,
                            value = if (exactAllowed) "已允许" else "去允许",
                            subtitle = if (exactAllowed) null else "未允许时提醒可能延迟",
                            onClick = {
                                runCatching { context.startActivity(Intent(Settings.ACTION_REQUEST_SCHEDULE_EXACT_ALARM,
                                    Uri.parse("package:${context.packageName}"))) }
                                    .onFailure { toaster.show("无法打开系统设置，请手动允许 Await 闹钟和提醒") }
                            }
                        )
                    }
                    PrefRow(
                        title = "发送测试通知",
                        icon = Icons.AutoMirrored.Outlined.Send,
                        onClick = {
                            toaster.show(if (LocalNotifications.show(context, "✅ Await 软件通知测试", "test"))
                                "软件测试通知已发送" else "请先允许软件通知权限")
                        }
                    )
                }
            }

            PrefGroup("云端推送", footer = "由服务器发送，手机关机后仍会推送已同步的日程。关闭渠道需同步成功才会取消服务器待发提醒。") {
                PrefRow(
                    title = "云端服务",
                    icon = Icons.Outlined.Cloud,
                    value = if (deployed) "已连接" else "未配置",
                    onClick = { Nav.go("cloud") }
                )
                listOf(NotificationChannel.TELEGRAM, NotificationChannel.NTFY).forEach { channel ->
                    val ready = deployed && channel.wireName in available
                    val selected = channel in channels
                    val canToggle = !busy && (ready || selected)
                    PrefRow(
                        title = channel.label,
                        icon = if (channel == NotificationChannel.TELEGRAM) Icons.Outlined.Forum else Icons.Outlined.Campaign,
                        subtitle = when {
                            !deployed -> "需先连接云端服务"
                            ready -> "已配置，点按修改参数"
                            else -> "未配置，点按填写参数"
                        },
                        onClick = {
                            if (CloudDeployment.canConfigureChannels(context)) Nav.go("channel", channel.wireName)
                            else if (!deployed) toaster.show("请先连接云端服务", "去配置") { Nav.go("cloud") }
                            else toaster.show("当前服务只支持服务器端配置渠道，请升级 Await 云端服务")
                        },
                        trailing = {
                            VerticalDivider(Modifier.height(32.dp).padding(end = 12.dp))
                            Switch(checked = selected, enabled = canToggle, onCheckedChange = {
                                haptics.tick(); toggle(channel, it)
                            })
                        }
                    )
                }
                PrefRow(
                    title = "测试云端推送",
                    icon = Icons.AutoMirrored.Outlined.Send,
                    enabled = !busy && deployed && NotificationChannels.remote(channels).isNotEmpty(),
                    onClick = { remoteTask(true) }
                )
                PrefRow(
                    title = "服务器状态",
                    icon = Icons.Outlined.Dns,
                    enabled = !busy && deployed,
                    onClick = { remoteTask(false) }
                )
            }

            val clipboard = LocalClipboardManager.current
            PrefGroup(footer = "同一服务器绑定一台手机；更换服务器后需停用旧服务器。") {
                PrefRow(
                    title = "本机安装 ID",
                    subtitle = SyncSettings.clientId(context),
                    icon = Icons.Outlined.Fingerprint,
                    onClick = {
                        clipboard.setText(AnnotatedString(SyncSettings.clientId(context)))
                        toaster.show("安装 ID 已复制")
                    }
                )
            }
        }
    }

    if (showDefaultTimePicker) {
        TimePickerDialog(
            title = "默认通知时间",
            hour = defaultTime.hour,
            minute = defaultTime.minute,
            confirm = "保存",
            enabled = !busy,
            onDismiss = { showDefaultTimePicker = false }
        ) { hour, minute ->
            val selectedTime = ReminderSettings.Time(hour, minute)
            busy = true
            scope.launch {
                try {
                    withContext(Dispatchers.IO) { ReminderSettings.save(context, selectedTime) }
                    defaultTime = selectedTime
                    showDefaultTimePicker = false
                    toaster.show("默认时间已保存，本机提醒已重排；云端联网同步后生效")
                } catch (_: Exception) {
                    toaster.show("保存默认时间失败，请重试")
                } finally {
                    busy = false
                }
            }
        }
    }
    report?.let { (title, text) ->
        AlertDialog(
            onDismissRequest = { report = null },
            title = { Text(title) },
            text = { SelectionContainer { Text(text) } },
            confirmButton = { TextButton(onClick = { report = null }) { Text("好") } }
        )
    }
}
