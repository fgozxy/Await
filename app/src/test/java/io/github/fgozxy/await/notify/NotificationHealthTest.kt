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
        syncedRevision: Long = 7,
        retryPending: Boolean = false
    ) = NotificationHealth.evaluate(channels, localAllowed, exactAllowed, cloudConfigured,
        deploymentReady, canSync, available, error, revision, syncedRevision, retryPending)

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
    fun remoteConfigurationFailuresStillNeedAttentionWhenNoChannelWorks() {
        assertEquals("同步失败", status(error = "同步失败").homeWarning)
        assertTrue(status(deploymentReady = false).homeWarning!!.contains("验证连接"))
        assertTrue(status(canSync = false).homeWarning!!.contains("迁移手机绑定"))
    }

    @Test
    fun pendingUpdatesAreInformationalForEverySelectedRemoteChannel() {
        for (channel in listOf(NotificationChannel.TELEGRAM, NotificationChannel.NTFY)) {
            val health = status(channels = setOf(channel), revision = 8)
            assertNull(health.homeWarning)
            assertTrue(health.details.contains("等待同步"))
            assertTrue(health.details.contains("上次同步设置"))
        }
    }

    @Test
    fun firstUploadIsInformationalWithoutClaimingTheServerHasTheSchedule() {
        val health = status(revision = 1, syncedRevision = 0)
        assertNull(health.homeWarning)
        assertTrue(health.details.contains("等待首次同步"))
        assertFalse(health.details.contains("上次"))
    }

    @Test
    fun pendingUploadMustNotHideAnUnconfiguredSelectedChannel() {
        val health = status(revision = 8, available = setOf("telegram"))
        assertEquals("请先配置所选消息渠道，再开启通知", health.homeWarning)
    }

    @Test
    fun retryableConnectionErrorsStayInSettingsWhileAuthenticationErrorsNeedAttention() {
        val network = status(error = "无法连接服务器", retryPending = true)
        assertNull(network.homeWarning)
        assertTrue(network.details.contains("无法连接服务器"))
        assertTrue(network.details.contains("自动重试"))
        val firstUpload = status(error = "无法连接服务器", retryPending = true, syncedRevision = 0)
        assertNull(firstUpload.homeWarning)
        assertTrue(firstUpload.details.contains("等待首次上传"))
        assertFalse(firstUpload.details.contains("上次"))
        assertEquals("服务器访问密钥不正确", status(error = "服务器访问密钥不正确").homeWarning)
        assertNotNull(status(error = "无法连接服务器", retryPending = true, deploymentReady = false).homeWarning)
        assertNotNull(status(error = "无法连接服务器", retryPending = true, available = emptySet()).homeWarning)
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
