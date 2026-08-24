package io.github.fgozxy.await.notify

import android.app.Service
import android.content.Context
import android.content.Intent
import android.content.pm.ServiceInfo
import android.media.AudioAttributes
import android.media.AudioFocusRequest
import android.media.AudioManager
import android.media.MediaPlayer
import android.media.RingtoneManager
import android.net.Uri
import android.os.Build
import android.os.Handler
import android.os.IBinder
import android.os.Looper
import android.os.PowerManager
import android.os.VibrationAttributes
import android.os.VibrationEffect
import android.os.Vibrator
import android.os.VibratorManager
import android.provider.Settings
import androidx.core.app.NotificationCompat
import androidx.core.app.ServiceCompat
import io.github.fgozxy.await.R
import io.github.fgozxy.await.data.Event
import io.github.fgozxy.await.data.EventStore
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow

/**
 * 闹钟响铃服务。
 *
 * 到点后由 [AlarmReceiver] 拉起，以前台服务的身份循环播放系统闹钟铃声并持续震动，
 * 直到用户点「关闭」或「稍后提醒」。通知常驻不可划掉，并带 fullScreenIntent 把
 * [io.github.fgozxy.await.ReminderActivity] 弹到屏幕最前（锁屏也弹）。
 *
 * 几个刻意的设计：
 *  - 铃声走 [AudioAttributes.USAGE_ALARM]，因此跟随闹钟音量、静音模式下照响，
 *    这正是「闹钟」区别于「通知」的地方。
 *  - [AUTO_SILENCE_MS] 后自动静音并退出前台，只留一条普通可划掉的通知。
 *    人不在旁边时不能让它响一整天，也不能让前台服务永久挂着耗电。
 *  - 播放链路整体 runCatching：取不到铃声 URI、设备无震动马达等情况一律降级，
 *    绝不能让提醒本身把应用崩掉。
 */
class AlarmRingService : Service() {

    private var player: MediaPlayer? = null
    private var vibrator: Vibrator? = null
    private var wakeLock: PowerManager.WakeLock? = null
    private var focusRequest: AudioFocusRequest? = null

    private val handler = Handler(Looper.getMainLooper())
    private var autoSilence: Runnable? = null

    /** 是否已经进入前台状态；决定 stop/snooze 分支要不要先补一次 startForeground */
    private var foregrounded = false

    override fun onBind(intent: Intent?): IBinder? = null

    override fun onStartCommand(intent: Intent?, flags: Int, startId: Int): Int {
        val eventId = intent?.getLongExtra(EXTRA_EVENT_ID, -1L) ?: -1L
        val event = if (eventId != -1L) EventStore.load(this).find { it.id == eventId } else null

        when (intent?.action) {
            ACTION_RING -> if (event != null) {
                startRinging(event)
            } else {
                // 日程已被删掉（闹钟没来得及取消）：同样要先满足前台约束再退出
                ensureForeground(null)
                shutdown()
            }

            ACTION_SNOOZE -> {
                // 可能是进程被杀后用户才点的按钮，此时服务是刚被拉起来的：
                // 必须先满足「5 秒内 startForeground」的约束再退出，否则系统会判定超时并崩溃。
                ensureForeground(event)
                if (eventId != -1L) AlarmScheduler.scheduleSnooze(this, eventId, SNOOZE_MINUTES)
                shutdown()
            }

            ACTION_STOP -> {
                ensureForeground(event)
                shutdown()
            }

            else -> {
                ensureForeground(event)
                shutdown()
            }
        }
        return START_NOT_STICKY
    }

    // ── 响铃 ──────────────────────────────────────────────────────────

    private fun startRinging(event: Event) {
        goForeground(NotificationHelper.buildAlarmNotification(this, event))
        if (!foregrounded) {
            // 进不了前台就别硬撑：系统会在 5 秒后把服务判死，这里直接降级为普通提醒
            NotificationHelper.showEventReminder(this, event)
            stopSelf()
            return
        }
        _ringingEventId.value = event.id

        acquireWakeLock()
        startSound()
        startVibration()

        // 重新计时：连续两条日程同一分钟触发时，以最后一条为准
        autoSilence?.let { handler.removeCallbacks(it) }
        val task = Runnable {
            // 自动静音：停声停震、退出前台，补一条普通可划掉的通知留档
            stopSound()
            stopVibration()
            NotificationHelper.showEventReminder(this, event)
            shutdown()
        }
        autoSilence = task
        handler.postDelayed(task, AUTO_SILENCE_MS)
    }

    private fun startSound() {
        stopSound()
        val uri = alarmUri() ?: return
        val attrs = AudioAttributes.Builder()
            .setUsage(AudioAttributes.USAGE_ALARM)
            .setContentType(AudioAttributes.CONTENT_TYPE_SONIFICATION)
            .build()

        runCatching {
            val am = getSystemService(Context.AUDIO_SERVICE) as AudioManager
            focusRequest = AudioFocusRequest.Builder(AudioManager.AUDIOFOCUS_GAIN_TRANSIENT)
                .setAudioAttributes(attrs)
                .build()
                .also { am.requestAudioFocus(it) }

            player = MediaPlayer().apply {
                setAudioAttributes(attrs)
                setDataSource(this@AlarmRingService, uri)
                isLooping = true
                prepare()
                start()
            }
        }.onFailure {
            // 铃声放不出来（URI 失效、被 DRM 挡住等）不影响震动和常驻通知
            stopSound()
        }
    }

    /** 依次尝试：用户设定的闹钟铃声 → 通知铃声 → 系统默认闹钟铃声 */
    private fun alarmUri(): Uri? = runCatching {
        RingtoneManager.getActualDefaultRingtoneUri(this, RingtoneManager.TYPE_ALARM)
            ?: RingtoneManager.getActualDefaultRingtoneUri(this, RingtoneManager.TYPE_NOTIFICATION)
            ?: Settings.System.DEFAULT_ALARM_ALERT_URI
    }.getOrNull()

    private fun startVibration() {
        runCatching {
            val v = if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.S) {
                (getSystemService(Context.VIBRATOR_MANAGER_SERVICE) as VibratorManager).defaultVibrator
            } else {
                @Suppress("DEPRECATION")
                getSystemService(Context.VIBRATOR_SERVICE) as Vibrator
            }
            if (!v.hasVibrator()) return
            vibrator = v
            // 震 0.8 秒、停 0.6 秒，从下标 0 无限循环
            val effect = VibrationEffect.createWaveform(longArrayOf(0, 800, 600), 0)
            if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.TIRAMISU) {
                v.vibrate(effect, VibrationAttributes.createForUsage(VibrationAttributes.USAGE_ALARM))
            } else {
                @Suppress("DEPRECATION")
                v.vibrate(
                    effect,
                    AudioAttributes.Builder().setUsage(AudioAttributes.USAGE_ALARM).build()
                )
            }
        }
    }

    private fun acquireWakeLock() {
        if (wakeLock?.isHeld == true) return
        runCatching {
            val pm = getSystemService(Context.POWER_SERVICE) as PowerManager
            wakeLock = pm.newWakeLock(PowerManager.PARTIAL_WAKE_LOCK, WAKE_LOCK_TAG).apply {
                setReferenceCounted(false)
                // 带超时，服务万一没能正常退出也不会把电熬干
                acquire(AUTO_SILENCE_MS + 60_000L)
            }
        }
    }

    // ── 停止 ──────────────────────────────────────────────────────────

    private fun stopSound() {
        runCatching {
            player?.apply {
                if (isPlaying) stop()
                reset()
                release()
            }
        }
        player = null
        runCatching {
            focusRequest?.let {
                (getSystemService(Context.AUDIO_SERVICE) as AudioManager).abandonAudioFocusRequest(it)
            }
        }
        focusRequest = null
    }

    private fun stopVibration() {
        runCatching { vibrator?.cancel() }
        vibrator = null
    }

    /** 停掉一切并结束服务 */
    private fun shutdown() {
        autoSilence?.let { handler.removeCallbacks(it) }
        autoSilence = null
        stopSound()
        stopVibration()
        ServiceCompat.stopForeground(this, ServiceCompat.STOP_FOREGROUND_REMOVE)
        foregrounded = false
        stopSelf()
    }

    override fun onDestroy() {
        autoSilence?.let { handler.removeCallbacks(it) }
        stopSound()
        stopVibration()
        runCatching { wakeLock?.takeIf { it.isHeld }?.release() }
        wakeLock = null
        _ringingEventId.value = null
        super.onDestroy()
    }

    // ── 前台状态 ───────────────────────────────────────────────────────

    private fun goForeground(notification: android.app.Notification) {
        runCatching {
            ServiceCompat.startForeground(
                this, FG_NOTIF_ID, notification,
                if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.Q)
                    ServiceInfo.FOREGROUND_SERVICE_TYPE_MEDIA_PLAYBACK
                else 0
            )
            foregrounded = true
        }
    }

    /** stop / snooze 分支的兜底：确保这次启动至少进过一次前台，再退出 */
    private fun ensureForeground(event: Event?) {
        if (foregrounded) return
        val notification = if (event != null) {
            NotificationHelper.buildAlarmNotification(this, event)
        } else {
            NotificationCompat.Builder(this, NotificationHelper.CHANNEL_ALARM)
                .setSmallIcon(R.drawable.ic_notification)
                .setContentTitle("Await")
                .setSilent(true)
                .build()
        }
        goForeground(notification)
    }

    companion object {
        const val ACTION_RING = "io.github.fgozxy.await.ACTION_RING"
        const val ACTION_SNOOZE = "io.github.fgozxy.await.ACTION_SNOOZE"
        const val ACTION_STOP = "io.github.fgozxy.await.ACTION_STOP"
        const val EXTRA_EVENT_ID = "extra_event_id"

        /** 稍后提醒的间隔（分钟） */
        const val SNOOZE_MINUTES = 10

        /** 无人理会时自动静音的时长 */
        const val AUTO_SILENCE_MS = 5 * 60_000L

        private const val FG_NOTIF_ID = 990001
        private const val WAKE_LOCK_TAG = "Await:alarm"

        /**
         * 当前正在响的日程 id，没有则为 null。
         *
         * [io.github.fgozxy.await.ReminderActivity] 直接观察它：服务一停（自动静音、
         * 或用户从通知栏点了关闭），全屏页就自己退出，不必再走一套广播 IPC。
         */
        private val _ringingEventId = MutableStateFlow<Long?>(null)
        val ringingEventId: StateFlow<Long?> get() = _ringingEventId

        /** 构造一个「开始响铃」的启动 Intent */
        fun ringIntent(context: Context, eventId: Long): Intent =
            Intent(context, AlarmRingService::class.java)
                .setAction(ACTION_RING)
                .putExtra(EXTRA_EVENT_ID, eventId)

        /** 构造一个控制指令（[ACTION_SNOOZE] / [ACTION_STOP]）的 Intent */
        fun controlIntent(context: Context, action: String, eventId: Long): Intent =
            Intent(context, AlarmRingService::class.java)
                .setAction(action)
                .putExtra(EXTRA_EVENT_ID, eventId)
    }
}
