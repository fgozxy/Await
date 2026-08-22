package io.github.fgozxy.await

import android.Manifest
import android.app.AlarmManager
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
import io.github.fgozxy.await.notify.AlarmScheduler
import io.github.fgozxy.await.notify.NotificationHelper
import io.github.fgozxy.await.ui.HomeScreen
import io.github.fgozxy.await.ui.theme.AwaitTheme
import io.github.fgozxy.await.vm.EventViewModel

class MainActivity : ComponentActivity() {

    private lateinit var viewModel: EventViewModel

    // 三项健康状态：可观察，onResume 时刷新，授权返回后横幅自动消失
    private val exactAlarmOk = mutableStateOf(true)
    private val notifOk = mutableStateOf(true)
    private val battOptOk = mutableStateOf(true)

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
                    onTestNotification = { NotificationHelper.showTestNotification(this) }
                )
            }
        }
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
    }
}
