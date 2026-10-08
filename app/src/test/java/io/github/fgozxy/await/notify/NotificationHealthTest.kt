package io.github.fgozxy.await.notify

import org.junit.Assert.*
import org.junit.Test

class NotificationHealthTest {
    private fun status(
        channels: Set<NotificationChannel> = setOf(NotificationChannel.NTFY),
        localAllowed: Boolean = true,
        exactAllowed: Boolean = true,
        cloudConfigured: Boolean = true,
        deploymentReady: Boolean = true,
        canSync: Boolean = true,
        available: Set<String> = setOf("telegram", "ntfy"),
        error: String = "",
        revision: Long = 7,
        syncedRevision: Long = 7
    ) = NotificationHealth.evaluate(channels, localAllowed, exactAllowed, cloudConfigured,
        deploymentReady, canSync, available, error, revision, syncedRevision)

    @Test
    fun everyHealthySingleOrMultipleChannelSelectionHidesHomeWarning() {
        for (mask in 1..7) {
            val selected = NotificationChannel.entries.filterIndexed { index, _ -> mask and (1 shl index) != 0 }.toSet()
            assertNull("Selection: $selected", status(channels = selected).homeWarning)
            assertTrue(status(channels = selected).details.isNotBlank())
        }
    }

    @Test
    fun oneConfiguredRemoteChannelDoesNotRequireTheOtherChannel() {
        for (channel in listOf(NotificationChannel.TELEGRAM, NotificationChannel.NTFY)) {
            val health = status(channels = setOf(channel), available = setOf(channel.wireName))
            assertNull(health.homeWarning)
            assertEquals("云端通知已同步，手机关机后服务器仍会推送", health.details)
        }
    }

    @Test
    fun localNotificationNeedsNeitherCloudNorExactAlarmPermission() {
        val health = status(channels = setOf(NotificationChannel.LOCAL), exactAllowed = false,
            cloudConfigured = false, deploymentReady = false, canSync = false, available = emptySet())
        assertNull(health.homeWarning)
        assertTrue(health.details.contains("通知可能延迟"))
    }

    @Test
    fun workingLocalChannelHidesCloudFailureButKeepsItsDetails() {
        val health = status(channels = setOf(NotificationChannel.LOCAL, NotificationChannel.NTFY), error = "同步失败")
        assertNull(health.homeWarning)
        assertTrue(health.details.contains("同步失败"))
    }

    @Test
    fun workingRemoteChannelHidesLocalPermissionWarningButKeepsItsDetails() {
        val health = status(channels = setOf(NotificationChannel.LOCAL, NotificationChannel.TELEGRAM), localAllowed = false)
        assertNull(health.homeWarning)
        assertTrue(health.details.contains("请允许系统通知权限"))
    }

    @Test
    fun soleLocalChannelWithDeniedNotificationPermissionNeedsAttention() {
        assertEquals("软件通知已选择，请允许系统通知权限",
            status(channels = setOf(NotificationChannel.LOCAL), localAllowed = false).homeWarning)
    }

    @Test
    fun configuredButUnselectedChannelsDoNotCountAsWorkingChannels() {
        assertNotNull(status(channels = setOf(NotificationChannel.NTFY), available = setOf("telegram")).homeWarning)
        assertNotNull(status(channels = setOf(NotificationChannel.LOCAL), localAllowed = false).homeWarning)
    }

    @Test
    fun remoteFailuresAndPendingSyncStillNeedAttentionWhenNoChannelWorks() {
        assertEquals("同步失败", status(error = "同步失败").homeWarning)
        assertTrue(status(revision = 8).homeWarning!!.contains("等待同步"))
        assertTrue(status(deploymentReady = false).homeWarning!!.contains("验证连接"))
        assertTrue(status(canSync = false).homeWarning!!.contains("迁移手机绑定"))
    }

    @Test
    fun atLeastOneAvailableSelectedRemoteChannelIsEnough() {
        val health = status(channels = setOf(NotificationChannel.TELEGRAM, NotificationChannel.NTFY), available = setOf("ntfy"))
        assertNull(health.homeWarning)
        assertTrue(health.details.contains("配置所选消息渠道"))
    }

    @Test
    fun noSelectedChannelsShowsAnActionableConfigurationWarning() {
        assertEquals("请至少选择一种通知渠道", status(channels = emptySet()).homeWarning)
    }
}
