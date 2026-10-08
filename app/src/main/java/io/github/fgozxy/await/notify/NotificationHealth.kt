package io.github.fgozxy.await.notify

/** 设置页保留完整状态；首页只在没有任何可用的已选渠道时提示。 */
data class NotificationHealth(val details: String, val homeWarning: String?) {
    companion object {
        fun evaluate(
            channels: Set<NotificationChannel>,
            localAllowed: Boolean,
            exactAllowed: Boolean,
            cloudConfigured: Boolean,
            deploymentReady: Boolean,
            canSync: Boolean,
            availableRemoteChannels: Set<String>,
            lastError: String,
            revision: Long,
            syncedRevision: Long
        ): NotificationHealth {
            val remote = channels.filter { it != NotificationChannel.LOCAL }
            val localReady = NotificationChannel.LOCAL in channels && localAllowed
            val remoteReady = deploymentReady && canSync && lastError.isEmpty() &&
                revision <= syncedRevision && remote.any { it.wireName in availableRemoteChannels }
            val messages = mutableListOf<String>()
            val warnings = mutableListOf<String>()
            if (NotificationChannel.LOCAL in channels) {
                val localStatus = when {
                    !localAllowed -> "软件通知已选择，请允许系统通知权限"
                    !exactAllowed -> "软件通知已开启；未允许精确提醒，通知可能延迟"
                    else -> "软件通知已开启"
                }
                messages += localStatus
                if (!localAllowed) warnings += localStatus
            }
            if (remote.isNotEmpty() || cloudConfigured) {
                val cloudStatus = when {
                    !deploymentReady -> "请先完成云端部署配置并验证连接"
                    !canSync -> "当前连接仅可恢复备份；通知同步需先迁移手机绑定"
                    lastError.isNotEmpty() -> lastError
                    revision > syncedRevision -> "云端设置等待同步；服务器仍按上次设置提醒"
                    remote.isEmpty() -> "云端通知已关闭"
                    remote.any { it.wireName !in availableRemoteChannels } -> "请先配置所选消息渠道，再开启通知"
                    else -> "云端通知已同步，手机关机后服务器仍会推送"
                }
                messages += cloudStatus
                if (remote.isNotEmpty() && !remoteReady) warnings += cloudStatus
            }
            if (channels.isEmpty()) {
                messages += "请至少选择一种通知渠道"
                warnings += "请至少选择一种通知渠道"
            }
            return NotificationHealth(
                details = messages.joinToString("\n"),
                homeWarning = if (localReady || remoteReady) null else warnings.joinToString("\n")
            )
        }
    }
}
