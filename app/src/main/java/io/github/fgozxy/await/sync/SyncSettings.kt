package io.github.fgozxy.await.sync

import android.content.Context
import android.security.keystore.KeyGenParameterSpec
import android.security.keystore.KeyProperties
import android.util.Base64
import java.net.URI
import java.security.KeyStore
import java.util.UUID
import javax.crypto.Cipher
import javax.crypto.KeyGenerator
import javax.crypto.SecretKey
import javax.crypto.spec.GCMParameterSpec

object SyncSettings {
    const val PREFS = "await_server_sync"
    private const val ALIAS = "await_server_api_key"
    data class Config(val url: String = "", val apiKey: String = "") {
        val isValid: Boolean get() = validUrl(url) && apiKey.length >= 32 &&
            apiKey.all { it.code in 33..126 }
    }

    fun validUrl(url: String): Boolean = runCatching {
        val uri = URI(url)
        uri.scheme == "https" && !uri.host.isNullOrBlank() && uri.rawUserInfo == null &&
            uri.rawQuery == null && uri.rawFragment == null
    }.getOrDefault(false)

    fun prefs(context: Context) = context.getSharedPreferences(PREFS, Context.MODE_PRIVATE)

    fun load(context: Context): Config {
        val sp = prefs(context)
        val encrypted = sp.getString("encrypted_key", "").orEmpty()
        val secret = runCatching {
            if (encrypted.isEmpty()) "" else {
                val parts = encrypted.split(':')
                val cipher = Cipher.getInstance("AES/GCM/NoPadding")
                cipher.init(Cipher.DECRYPT_MODE, key(), GCMParameterSpec(128, decode(parts[0])))
                String(cipher.doFinal(decode(parts[1])), Charsets.UTF_8)
            }
        }.getOrDefault("")
        return Config(sp.getString("url", "").orEmpty(), secret)
    }

    fun save(context: Context, config: Config) {
        require(config.isValid)
        val cipher = Cipher.getInstance("AES/GCM/NoPadding")
        cipher.init(Cipher.ENCRYPT_MODE, key())
        val encrypted = encode(cipher.iv) + ":" + encode(cipher.doFinal(config.apiKey.toByteArray()))
        check(prefs(context).edit().putString("url", config.url.trimEnd('/'))
            .putString("encrypted_key", encrypted).putLong("synced_revision", 0).commit())
    }

    fun clientId(context: Context): String {
        val sp = prefs(context)
        return sp.getString("client_id", null) ?: UUID.randomUUID().toString().also {
            check(sp.edit().putString("client_id", it).commit())
        }
    }

    @Synchronized
    private fun key(): SecretKey {
        val store = KeyStore.getInstance("AndroidKeyStore").apply { load(null) }
        (store.getKey(ALIAS, null) as? SecretKey)?.let { return it }
        return KeyGenerator.getInstance(KeyProperties.KEY_ALGORITHM_AES, "AndroidKeyStore").apply {
            init(KeyGenParameterSpec.Builder(ALIAS, KeyProperties.PURPOSE_ENCRYPT or KeyProperties.PURPOSE_DECRYPT)
                .setBlockModes(KeyProperties.BLOCK_MODE_GCM)
                .setEncryptionPaddings(KeyProperties.ENCRYPTION_PADDING_NONE).build())
        }.generateKey()
    }

    private fun encode(bytes: ByteArray) = Base64.encodeToString(bytes, Base64.NO_WRAP)
    private fun decode(value: String) = Base64.decode(value, Base64.NO_WRAP)
}
