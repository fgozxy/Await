package io.github.fgozxy.await

import android.content.Intent
import android.os.Build
import android.os.Bundle
import androidx.activity.ComponentActivity
import androidx.activity.addCallback
import androidx.activity.compose.setContent
import androidx.compose.foundation.background
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.*
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material3.*
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import androidx.core.content.ContextCompat
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import io.github.fgozxy.await.data.Event
import io.github.fgozxy.await.data.EventStore
import io.github.fgozxy.await.notify.AlarmRingService
import io.github.fgozxy.await.ui.theme.EventColors

/**
 * 全屏提醒页：由通知的 fullScreenIntent 拉起，
 * 锁屏 / 亮屏状态下都会直接覆盖展示，确保提醒足够显眼。
 *
 * 闹钟模式（[EXTRA_ALARM_MODE]）下额外做三件事：
 *  - 屏蔽返回键与点击背景关闭——正在响铃时误触关掉是最糟的体验
 *  - 按钮换成「稍后提醒 / 关闭」，直接指挥 [AlarmRingService]
 *  - 观察 [AlarmRingService.ringingEventId]，服务一停（自动静音、或用户从通知栏
 *    点了关闭）本页就自己退出，不会留一个空壳页面在最前面
 */
class ReminderActivity : ComponentActivity() {

    private var eventIdState by mutableStateOf(-1L)
    private var alarmModeState by mutableStateOf(false)

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)

        // 锁屏时也显示并点亮屏幕（API 27+；26 走旧 flag）
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.O_MR1) {
            setShowWhenLocked(true)
            setTurnScreenOn(true)
        } else {
            @Suppress("DEPRECATION")
            window.addFlags(
                android.view.WindowManager.LayoutParams.FLAG_SHOW_WHEN_LOCKED or
                    android.view.WindowManager.LayoutParams.FLAG_TURN_SCREEN_ON
            )
        }

        applyIntent(intent)

        // 闹钟响着的时候吞掉返回键
        onBackPressedDispatcher.addCallback(this) {
            if (!alarmModeState) finish()
        }

        setContent {
            io.github.fgozxy.await.ui.theme.AwaitTheme {
                val eventId = eventIdState
                val alarmMode = alarmModeState
                val event = remember(eventId) {
                    if (eventId != -1L) EventStore.load(this).find { it.id == eventId } else null
                }

                if (alarmMode) {
                    // 服务停了（自动静音 / 通知栏关闭）就跟着退出
                    val ringing by AlarmRingService.ringingEventId.collectAsStateWithLifecycle()
                    LaunchedEffect(ringing) { if (ringing == null) finish() }
                }

                ReminderOverlay(
                    event = event,
                    alarmMode = alarmMode,
                    onDismiss = {
                        if (alarmMode) sendToService(AlarmRingService.ACTION_STOP)
                        finish()
                    },
                    onSnooze = {
                        sendToService(AlarmRingService.ACTION_SNOOZE)
                        finish()
                    },
                    onOpenApp = {
                        if (alarmMode) sendToService(AlarmRingService.ACTION_STOP)
                        startActivity(
                            Intent(this, MainActivity::class.java)
                                .addFlags(Intent.FLAG_ACTIVITY_NEW_TASK)
                        )
                        finish()
                    }
                )
            }
        }
    }

    /** singleTask：响铃期间又来一条提醒时，直接换成新的那条 */
    override fun onNewIntent(intent: Intent) {
        super.onNewIntent(intent)
        setIntent(intent)
        applyIntent(intent)
    }

    private fun applyIntent(intent: Intent?) {
        eventIdState = intent?.getLongExtra(EXTRA_EVENT_ID, -1L) ?: -1L
        alarmModeState = intent?.getBooleanExtra(EXTRA_ALARM_MODE, false) ?: false
    }

    private fun sendToService(action: String) {
        if (eventIdState == -1L) return
        runCatching {
            ContextCompat.startForegroundService(
                this,
                AlarmRingService.controlIntent(this, action, eventIdState)
            )
        }
    }

    companion object {
        const val EXTRA_EVENT_ID = "extra_event_id"
        const val EXTRA_ALARM_MODE = "extra_alarm_mode"
    }
}

@Composable
private fun ReminderOverlay(
    event: Event?,
    alarmMode: Boolean,
    onDismiss: () -> Unit,
    onSnooze: () -> Unit,
    onOpenApp: () -> Unit
) {
    val accent = EventColors[(event?.colorIndex ?: 0) % EventColors.size]
    val days = event?.daysFromToday() ?: 0

    Box(
        Modifier
            .fillMaxSize()
            .background(Color.Black.copy(alpha = 0.65f))
            // 闹钟模式下点背景不关闭，必须明确选一个按钮
            .let { if (alarmMode) it else it.clickableNoOp(onDismiss) },
        contentAlignment = Alignment.Center
    ) {
        ElevatedCard(
            shape = RoundedCornerShape(24.dp),
            modifier = Modifier
                .padding(24.dp)
                .fillMaxWidth()
                .clickableNoOp { /* 吞掉点击，避免误触关闭 */ }
        ) {
            Column(
                Modifier.padding(24.dp),
                horizontalAlignment = Alignment.CenterHorizontally,
                verticalArrangement = Arrangement.spacedBy(10.dp)
            ) {
                Text(
                    if (alarmMode) "⏰ 闹钟提醒" else "⏳ 倒数日提醒",
                    style = MaterialTheme.typography.labelMedium,
                    color = MaterialTheme.colorScheme.primary
                )

                Text(
                    event?.title ?: "日程提醒",
                    style = MaterialTheme.typography.headlineSmall,
                    fontWeight = FontWeight.Bold,
                    textAlign = androidx.compose.ui.text.style.TextAlign.Center
                )

                // 大字倒计时
                Row(verticalAlignment = Alignment.Bottom) {
                    Text(
                        when {
                            days == 0 -> "今天"
                            days > 0 -> "$days"
                            else -> "${-days}"
                        },
                        color = accent,
                        fontSize = 64.sp,
                        fontWeight = FontWeight.Black
                    )
                    if (days != 0) {
                        Text(
                            if (days > 0) " 天" else " 天前",
                            color = accent,
                            fontSize = 20.sp,
                            modifier = Modifier.padding(bottom = 12.dp)
                        )
                    }
                }

                Text(
                    event?.dateText() ?: "",
                    style = MaterialTheme.typography.bodyMedium,
                    color = MaterialTheme.colorScheme.onSurfaceVariant
                )
                if (!event?.note.isNullOrBlank()) {
                    Text(
                        event!!.note,
                        style = MaterialTheme.typography.bodyMedium,
                        textAlign = androidx.compose.ui.text.style.TextAlign.Center
                    )
                }

                Spacer(Modifier.height(4.dp))
                Row(
                    Modifier.fillMaxWidth(),
                    horizontalArrangement = Arrangement.spacedBy(12.dp)
                ) {
                    if (alarmMode) {
                        OutlinedButton(onClick = onSnooze, modifier = Modifier.weight(1f)) {
                            Text("稍后提醒")
                        }
                        Button(onClick = onDismiss, modifier = Modifier.weight(1f)) {
                            Text("关闭")
                        }
                    } else {
                        OutlinedButton(onClick = onDismiss, modifier = Modifier.weight(1f)) {
                            Text("知道了")
                        }
                        Button(onClick = onOpenApp, modifier = Modifier.weight(1f)) {
                            Text("打开应用")
                        }
                    }
                }
                if (alarmMode) {
                    Text(
                        "${AlarmRingService.SNOOZE_MINUTES} 分钟后再响一次",
                        style = MaterialTheme.typography.bodySmall,
                        color = MaterialTheme.colorScheme.onSurfaceVariant
                    )
                    TextButton(onClick = onOpenApp) { Text("打开应用") }
                }
            }
        }
    }
}

private fun Modifier.clickableNoOp(onClick: () -> Unit): Modifier =
    this.then(
        Modifier.clickable(
            interactionSource = androidx.compose.foundation.interaction.MutableInteractionSource(),
            indication = null,
            onClick = onClick
        )
    )
