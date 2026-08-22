package io.github.fgozxy.await

import android.os.Build
import android.os.Bundle
import androidx.activity.ComponentActivity
import androidx.activity.compose.setContent
import androidx.compose.foundation.background
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.*
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material3.*
import androidx.compose.runtime.Composable
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import io.github.fgozxy.await.data.Event
import io.github.fgozxy.await.data.EventStore
import io.github.fgozxy.await.ui.theme.EventColors

/**
 * 全屏提醒页：由通知的 fullScreenIntent 拉起，
 * 锁屏 / 亮屏状态下都会直接覆盖展示，确保提醒足够显眼。
 */
class ReminderActivity : ComponentActivity() {

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

        val eventId = intent.getLongExtra(EXTRA_EVENT_ID, -1L)
        val event = if (eventId != -1L) EventStore.load(this).find { it.id == eventId } else null

        setContent {
            io.github.fgozxy.await.ui.theme.AwaitTheme {
                ReminderOverlay(
                    event = event,
                    onDismiss = { finish() },
                    onOpenApp = {
                        startActivity(
                            android.content.Intent(this, MainActivity::class.java)
                                .addFlags(android.content.Intent.FLAG_ACTIVITY_NEW_TASK)
                        )
                        finish()
                    }
                )
            }
        }
    }

    companion object {
        const val EXTRA_EVENT_ID = "extra_event_id"
    }
}

@Composable
private fun ReminderOverlay(event: Event?, onDismiss: () -> Unit, onOpenApp: () -> Unit) {
    val accent = EventColors[(event?.colorIndex ?: 0) % EventColors.size]
    val days = event?.daysFromToday() ?: 0

    Box(
        Modifier
            .fillMaxSize()
            .background(Color.Black.copy(alpha = 0.65f))
            .clickableNoOp(onDismiss),
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
                Text("⏳ 倒数日提醒", style = MaterialTheme.typography.labelMedium,
                    color = MaterialTheme.colorScheme.primary)

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
                    OutlinedButton(onClick = onDismiss, modifier = Modifier.weight(1f)) {
                        Text("知道了")
                    }
                    Button(onClick = onOpenApp, modifier = Modifier.weight(1f)) {
                        Text("打开应用")
                    }
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
