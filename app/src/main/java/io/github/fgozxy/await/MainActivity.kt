package io.github.fgozxy.await

import android.Manifest
import android.content.Intent
import android.net.Uri
import android.os.Build
import android.os.Bundle
import android.provider.Settings
import androidx.activity.ComponentActivity
import androidx.activity.compose.setContent
import androidx.activity.result.contract.ActivityResultContracts
import androidx.compose.runtime.*
import androidx.compose.ui.platform.LocalContext
import io.github.fgozxy.await.notify.NotificationHelper
import io.github.fgozxy.await.ui.HomeScreen
import io.github.fgozxy.await.ui.theme.AwaitTheme
import io.github.fgozxy.await.vm.EventViewModel

class MainActivity : ComponentActivity() {

    private lateinit var viewModel: EventViewModel

    /** Android 13+ 通知运行时权限 */
    private val notifPermission =
        registerForActivityResult(ActivityResultContracts.RequestPermission()) { }

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
                val context = LocalContext.current
                var exactAlarmOk by remember {
                    mutableStateOf(
                        Build.VERSION.SDK_INT < 31 ||
                            (getSystemService(android.app.AlarmManager::class.java))
                                ?.canScheduleExactAlarms() != false
                    )
                }
                HomeScreen(
                    viewModel = viewModel,
                    showExactAlarmBanner = !exactAlarmOk,
                    onRequestExactAlarm = {
                        context.startActivity(Intent(
                            Settings.ACTION_REQUEST_SCHEDULE_EXACT_ALARM,
                            Uri.parse("package:$packageName")
                        ))
                    }
                )
            }
        }
    }

    companion object {
        const val EXTRA_EVENT_ID = "extra_event_id"
    }
}
