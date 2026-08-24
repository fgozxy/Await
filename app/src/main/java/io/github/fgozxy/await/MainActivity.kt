package io.github.fgozxy.await

import android.Manifest
import android.app.AlarmManager
import android.app.NotificationManager
import android.content.Intent
import android.net.Uri
import android.os.Build
import android.os.Bundle
import android.os.PowerManager
import android.provider.Settings
import androidx.activity.ComponentActivity
import androidx.activity.compose.setContent
import androidx.activity.result.contract.ActivityResultContracts
import androidx.compose.runtime.mutableStateOf
import androidx.lifecycle.lifecycleScope
import io.github.fgozxy.await.notify.AlarmScheduler
import io.github.fgozxy.await.notify.NotificationHelper
import io.github.fgozxy.await.ui.HomeScreen
import io.github.fgozxy.await.update.AutoUpdate
import io.github.fgozxy.await.update.UpdateManager
import kotlinx.coroutines.launch
import io.github.fgozxy.await.ui.theme.AwaitTheme
import io.github.fgozxy.await.vm.EventViewModel

class MainActivity : ComponentActivity() {

    private lateinit var viewModel: EventViewModel

    // 四项健康状态：可观察，onResume 时刷新，授权返回后横幅自动消失
    private val exactAlarmOk = mutableStateOf(true)
    private val notifOk = mutableStateOf(true)
    private val battOptOk = mutableStateOf(true)
    private val fullScreenOk = mutableStateOf(true)
    private val autoUpdateOn by lazy { mutableStateOf(AutoUpdate.isEnabled(this)) }

    /** Android 13+ 通知运行时权限 */
    private val notifPermission =
        registerForActivityResult(ActivityResultContracts.RequestPermission()) { granted ->
            notifOk.value = granted
        }

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        NotificationHelper.ensureChannels(this)
        viewModel = EventViewModel(application)

        if (Build.VERSION.SDK_INT >= 33 &&
            checkSelfPermission(Manifest.permission.POST_NOTIFICATIONS) !=
            android.content.pm.PackageManager.PERMISSION_GRANTED
        ) {
            notifPermission.launch(Manifest.permission.POST_NOTIFICATIONS)
        }

        // 装完新版后把待装记录和残留安装包清掉，免得反复提示。
        // 注意只在确实已装上时清通知——否则用户随手打开一次应用，
        // 那条「已准备好」的通知就没了，而它是唯一的安装入口。
        AutoUpdate.clearIfInstalled(this)
        if (AutoUpdate.readyApk(this) == null) NotificationHelper.cancelUpdateReady(this)
        handleInstallIntent(intent)

        // 后台静默检查并下载新版；失败无声无息，下次打开再试
        lifecycleScope.launch { runCatching { AutoUpdate.runSilently(this@MainActivity) } }

        setContent {
            AwaitTheme {
                HomeScreen(
                    viewModel = viewModel,
                    showExactAlarmBanner = !exactAlarmOk.value,
                    onRequestExactAlarm = {
                        startActivity(Intent(
                            Settings.ACTION_REQUEST_SCHEDULE_EXACT_ALARM,
                            Uri.parse("package:$packageName")
                        ))
                    },
                    showNotifBanner = !notifOk.value,
                    onRequestNotif = {
                        startActivity(Intent(
                            Settings.ACTION_APP_NOTIFICATION_SETTINGS,
                            Uri.parse("package:$packageName")
                        ))
                    },
                    showBattBanner = !battOptOk.value,
                    onRequestBatt = { requestIgnoreBatteryOptimization() },
                    showFullScreenBanner = !fullScreenOk.value,
                    onRequestFullScreen = { requestFullScreenIntentPermission() },
                    onTestNotification = { NotificationHelper.showTestNotification(this) },
                    autoUpdateEnabled = autoUpdateOn.value,
                    onToggleAutoUpdate = {
                        autoUpdateOn.value = it
                        AutoUpdate.setEnabled(this, it)
                    }
                )
            }
        }
    }

    /** 通知点进来时带着安装包路径：先确认安装权限，再拉起系统安装器 */
    override fun onNewIntent(intent: Intent) {
        super.onNewIntent(intent)
        setIntent(intent)
        handleInstallIntent(intent)
    }

    private fun handleInstallIntent(intent: Intent?) {
        if (intent?.action != ACTION_INSTALL_UPDATE) return
        val path = intent.getStringExtra(EXTRA_APK_PATH) ?: return
        val apk = java.io.File(path)
        if (!apk.isFile) return
        NotificationHelper.cancelUpdateReady(this)
        if (!UpdateManager.canInstall(this)) {
            UpdateManager.gotoInstallPermission(this)
            return
        }
        UpdateManager.installApk(this, apk)
    }

    override fun onResume() {
        super.onResume()
        refreshHealthStates()
        // 权限齐全后重排一次，确保降级闹钟升级为精确闹钟
        if (exactAlarmOk.value) AlarmScheduler.scheduleAll(this)
    }

    private fun refreshHealthStates() {
        val am = getSystemService(AlarmManager::class.java)
        exactAlarmOk.value = Build.VERSION.SDK_INT < 31 || am?.canScheduleExactAlarms() != false

        notifOk.value = Build.VERSION.SDK_INT < 33 ||
            checkSelfPermission(Manifest.permission.POST_NOTIFICATIONS) ==
            android.content.pm.PackageManager.PERMISSION_GRANTED

        battOptOk.value = isIgnoringBatteryOptimizations()
        fullScreenOk.value = canUseFullScreenIntent()
    }

    /**
     * Android 14 起 USE_FULL_SCREEN_INTENT 不再默认授予普通应用，被撤销后
     * 闹钟的全屏提醒页根本弹不出来，只剩一条通知——必须显式引导用户去开。
     */
    private fun canUseFullScreenIntent(): Boolean {
        if (Build.VERSION.SDK_INT < 34) return true
        val nm = getSystemService(NotificationManager::class.java) ?: return true
        return nm.canUseFullScreenIntent()
    }

    private fun requestFullScreenIntentPermission() {
        if (Build.VERSION.SDK_INT < 34) return
        runCatching {
            startActivity(Intent(
                Settings.ACTION_MANAGE_APP_USE_FULL_SCREEN_INTENT,
                Uri.parse("package:$packageName")
            ))
        }.onFailure {
            startActivity(Intent(
                Settings.ACTION_APP_NOTIFICATION_SETTINGS,
                Uri.parse("package:$packageName")
            ))
        }
    }

    private fun isIgnoringBatteryOptimizations(): Boolean {
        val pm = getSystemService(PowerManager::class.java) ?: return true
        return pm.isIgnoringBatteryOptimizations(packageName)
    }

    @android.annotation.SuppressLint("BatteryLife")
    private fun requestIgnoreBatteryOptimization() {
        runCatching {
            startActivity(Intent(
                Settings.ACTION_REQUEST_IGNORE_BATTERY_OPTIMIZATIONS,
                Uri.parse("package:$packageName")
            ))
        }.onFailure {
            // 个别 ROM 不支持该 action 时退回常规设置页
            startActivity(Intent(Settings.ACTION_IGNORE_BATTERY_OPTIMIZATION_SETTINGS))
        }
    }

    companion object {
        const val EXTRA_EVENT_ID = "extra_event_id"
        const val ACTION_INSTALL_UPDATE = "io.github.fgozxy.await.ACTION_INSTALL_UPDATE"
        const val EXTRA_APK_PATH = "extra_apk_path"
    }
}
