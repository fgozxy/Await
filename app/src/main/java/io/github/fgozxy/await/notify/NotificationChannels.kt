package io.github.fgozxy.await.notify

import android.content.Context
import io.github.fgozxy.await.sync.SyncSettings

enum class NotificationChannel(val wireName: String, val label: String) {
    LOCAL("local", "软件通知"), TELEGRAM("telegram", "Telegram 通知"), NTFY("ntfy", "ntfy 通知")
}

object NotificationChannels {
    fun load(context: Context): Set<NotificationChannel> {
        val sp = SyncSettings.prefs(context)
        // Keep existing Telegram installations unchanged; fresh installs use software notifications.
        val saved = sp.getStringSet("notification_channels", null)
            ?: return setOf(if (sp.getString("url", "").isNullOrEmpty()) NotificationChannel.LOCAL
                else NotificationChannel.TELEGRAM)
        return NotificationChannel.entries.filter { it.wireName in saved }.toSet()
    }

    fun save(context: Context, channels: Set<NotificationChannel>) {
        require(channels.isNotEmpty())
        check(SyncSettings.prefs(context).edit()
            .putStringSet("notification_channels", channels.map { it.wireName }.toSet()).commit())
    }

    fun remote(channels: Set<NotificationChannel>): List<String> =
        channels.filter { it != NotificationChannel.LOCAL }.map { it.wireName }.sorted()
}
