package io.github.fgozxy.await.sync

import com.google.gson.JsonParser
import org.junit.Assert.*
import org.junit.Test

class CloudDeploymentTest {
    private val config = SyncSettings.Config("https://await.example.test", "synthetic-test-key-at-least-32-chars")
    private fun status(json: String) = JsonParser.parseString(json).asJsonObject

    @Test
    fun undeployedServersAndChangedAddressesOrKeysStayLocked() {
        assertFalse(CloudDeployment.matches(config, ""))
        val savedId = CloudDeployment.credentialId(config)
        assertTrue(CloudDeployment.matches(config, savedId))
        assertFalse(CloudDeployment.matches(config.copy(url = "https://another.example.test"), savedId))
        assertFalse(CloudDeployment.matches(config.copy(apiKey = "different-test-key-at-least-32-chars"), savedId))
        assertFalse(CloudDeployment.matches(SyncSettings.Config(), savedId))
        assertFalse(savedId.contains(config.apiKey))
    }

    @Test
    fun verifiedServerWithNoChannelsStillAllowsDeploymentBeforeChannelSetup() {
        val result = CloudDeployment.parse(status("""{"notificationProtocol":1,"clientId":"","availableChannels":[],"channelConfiguration":true}"""), "phone")
        assertTrue(result.availableChannels.isEmpty())
        assertTrue(result.managesChannels)
    }

    @Test
    fun existingServersReportReadyChannelsWithoutAdvertisingEditing() {
        val result = CloudDeployment.parse(status("""{"notificationProtocol":1,"clientId":"phone","availableChannels":["telegram"]}"""), "phone")
        assertEquals(setOf("telegram"), result.availableChannels)
        assertFalse(result.managesChannels)
        assertFalse(result.backupsEnabled)
        assertTrue(result.canSync)
    }

    @Test
    fun backupAvailabilityRequiresEnabledFeatureAndSupportedProtocol() {
        for (suffix in listOf("", ",\"backupEnabled\":false,\"backupProtocol\":1",
                ",\"backupEnabled\":true,\"backupProtocol\":2", ",\"backupEnabled\":true")) {
            val result = CloudDeployment.parse(status("""{"notificationProtocol":1,"availableChannels":[]$suffix}"""), "phone")
            assertFalse(result.backupsEnabled)
        }
        val result = CloudDeployment.parse(status("""{"notificationProtocol":1,"availableChannels":[],"backupEnabled":true,"backupProtocol":1}"""), "phone")
        assertTrue(result.backupsEnabled)
        assertTrue(result.canSync)
    }

    @Test
    fun anotherPhoneMayConnectForBackupRecoveryWithoutWriteAccess() {
        val response = status("""{"notificationProtocol":1,"availableChannels":["telegram"],"clientId":"old-phone","backupEnabled":true,"backupProtocol":1}""")
        val result = CloudDeployment.parse(response, "new-phone", allowBackupRecovery = true)
        assertTrue(result.backupsEnabled)
        assertFalse(result.canSync)
    }

    @Test(expected = ServerException::class)
    fun recoveryModeDoesNotAllowForeignBindingWhenBackupIsDisabled() {
        CloudDeployment.parse(status("""{"notificationProtocol":1,"availableChannels":[],"clientId":"other","backupEnabled":false,"backupProtocol":1}"""),
            "phone", allowBackupRecovery = true)
    }

    @Test(expected = ServerException::class)
    fun incompatibleServerIsRejected() {
        CloudDeployment.parse(status("""{"availableChannels":[]}"""), "phone")
    }

    @Test(expected = ServerException::class)
    fun serverBoundToAnotherPhoneIsRejected() {
        CloudDeployment.parse(status("""{"notificationProtocol":1,"clientId":"other","availableChannels":[]}"""), "phone")
    }
}
