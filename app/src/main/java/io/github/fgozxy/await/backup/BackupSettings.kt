package io.github.fgozxy.await.backup

import android.content.Context
import java.time.Instant
import java.time.LocalDateTime
import java.time.ZoneId
import java.time.format.DateTimeFormatter

/**
 * 备份相关配置，存在应用私有的 SharedPreferences 中（其他应用读不到，
 * 但 root 或备份提取仍可读，属于「网盘应用密码」的常规安全级别）。
 */
object BackupSettings {

    private const val PREFS = "await_backup"

    private const val K_URL = "webdav_url"
    private const val K_USER = "webdav_user"
    private const val K_PWD = "webdav_password"
    private const val K_DIR = "webdav_dir"
    private const val K_AUTO = "auto_enabled"
    private const val K_INTERVAL = "interval_days"
    private const val K_HOUR = "hour"
    private const val K_MINUTE = "minute"
    private const val K_KEEP = "keep_count"
    private const val K_WIFI = "wifi_only"
    private const val K_LAST_AT = "last_backup_at"
    private const val K_LAST_TRY = "last_attempt_at"
    private const val K_LAST_MSG = "last_result_msg"
    private const val K_LAST_OK = "last_result_ok"

    /** 可选的自动备份周期（天） */
    val INTERVAL_OPTIONS = listOf(1 to "每天", 3 to "每 3 天", 7 to "每周", 30 to "每月")

    /** 云端保留的备份份数上限（超出的按时间从旧到新删除） */
    val KEEP_OPTIONS = listOf(5, 10, 20, 50)

    data class Prefs(
        val webdav: WebDavConfig = WebDavConfig(),
        val autoEnabled: Boolean = false,
        val intervalDays: Int = 1,
        val hour: Int = 22,
        val minute: Int = 0,
        val keepCount: Int = 10,
        val wifiOnly: Boolean = false,
        val lastBackupAt: Long = 0L,
        /** 上一次尝试备份的时间（含失败），用来给「补做」限流 */
        val lastAttemptAt: Long = 0L,
        val lastResultMsg: String = "",
        val lastResultOk: Boolean = true
    ) {
        /** 上次成功备份时间「2026-08-22 22:00」；从未备份返回 null */
        fun lastBackupText(): String? {
            if (lastBackupAt <= 0L) return null
            val t = LocalDateTime.ofInstant(Instant.ofEpochMilli(lastBackupAt), ZoneId.systemDefault())
            return t.format(DateTimeFormatter.ofPattern("yyyy-MM-dd HH:mm"))
        }
    }

    fun load(context: Context): Prefs {
        val sp = context.getSharedPreferences(PREFS, Context.MODE_PRIVATE)
        return Prefs(
            webdav = WebDavConfig(
                url = sp.getString(K_URL, "").orEmpty(),
                user = sp.getString(K_USER, "").orEmpty(),
                password = sp.getString(K_PWD, "").orEmpty(),
                dir = sp.getString(K_DIR, "Await").orEmpty()
            ),
            autoEnabled = sp.getBoolean(K_AUTO, false),
            intervalDays = sp.getInt(K_INTERVAL, 1).coerceIn(1, 30),
            hour = sp.getInt(K_HOUR, 22).coerceIn(0, 23),
            minute = sp.getInt(K_MINUTE, 0).coerceIn(0, 59),
            keepCount = sp.getInt(K_KEEP, 10).coerceIn(1, 100),
            wifiOnly = sp.getBoolean(K_WIFI, false),
            lastBackupAt = sp.getLong(K_LAST_AT, 0L),
            lastAttemptAt = sp.getLong(K_LAST_TRY, 0L),
            lastResultMsg = sp.getString(K_LAST_MSG, "").orEmpty(),
            lastResultOk = sp.getBoolean(K_LAST_OK, true)
        )
    }

    /** 保存用户可编辑的部分（不动「上次备份结果」） */
    fun save(context: Context, prefs: Prefs) {
        context.getSharedPreferences(PREFS, Context.MODE_PRIVATE).edit()
            .putString(K_URL, prefs.webdav.url.trim())
            .putString(K_USER, prefs.webdav.user.trim())
            .putString(K_PWD, prefs.webdav.password)
            .putString(K_DIR, prefs.webdav.dir.trim())
            .putBoolean(K_AUTO, prefs.autoEnabled)
            .putInt(K_INTERVAL, prefs.intervalDays)
            .putInt(K_HOUR, prefs.hour)
            .putInt(K_MINUTE, prefs.minute)
            .putInt(K_KEEP, prefs.keepCount)
            .putBoolean(K_WIFI, prefs.wifiOnly)
            .apply()
    }

    /** 记录一次备份结果，供设置页展示 */
    fun recordResult(context: Context, ok: Boolean, message: String, at: Long = System.currentTimeMillis()) {
        val edit = context.getSharedPreferences(PREFS, Context.MODE_PRIVATE).edit()
            .putString(K_LAST_MSG, message)
            .putBoolean(K_LAST_OK, ok)
            .putLong(K_LAST_TRY, at)
        if (ok) edit.putLong(K_LAST_AT, at)
        edit.apply()
    }
}
