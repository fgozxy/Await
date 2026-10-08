package io.github.fgozxy.await.update

import android.content.Context

/** 仅检查版本；下载与安装由用户在更新页面发起。 */
object AutoUpdate {
    private const val INTERVAL_MS = 24 * 60 * 60 * 1000L
    private fun prefs(context: Context) = context.getSharedPreferences("await_updates", Context.MODE_PRIVATE)

    fun enabled(context: Context): Boolean = prefs(context).getBoolean("enabled", true)
    fun setEnabled(context: Context, enabled: Boolean) { prefs(context).edit().putBoolean("enabled", enabled).apply() }

    suspend fun check(context: Context): ReleaseInfo? {
        if (!enabled(context)) return null
        val preferences = prefs(context)
        val now = System.currentTimeMillis()
        val last = preferences.getLong("last_check", 0)
        val version = UpdateManager.currentVersion(context)
        if (preferences.getString("checked_version", "") == version && last in 1..now && now - last < INTERVAL_MS) return null
        val release = UpdateManager.checkLatest(version)
        preferences.edit().putLong("last_check", now).putString("checked_version", version).apply()
        return release
    }
}
