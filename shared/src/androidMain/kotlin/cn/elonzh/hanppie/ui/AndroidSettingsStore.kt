package cn.elonzh.hanppie.ui

import android.content.Context
import android.security.keystore.KeyGenParameterSpec
import android.security.keystore.KeyProperties
import android.util.Base64
import java.security.KeyStore
import javax.crypto.Cipher
import javax.crypto.KeyGenerator
import javax.crypto.SecretKey
import javax.crypto.spec.GCMParameterSpec
import kotlinx.serialization.encodeToString

/** AndroidKeyStore keeps the AES key non-exportable; preferences contain only IV + ciphertext. */
internal class AndroidSettingsStore(context: Context) : SettingsStore {
    private val preferences = context.getSharedPreferences("model-settings", Context.MODE_PRIVATE)
    private val alias = "cn.elonzh.hanppie.model-settings.v1"
    private fun key(create: Boolean): SecretKey {
        val store = KeyStore.getInstance("AndroidKeyStore").apply { load(null) }
        (store.getKey(alias, null) as? SecretKey)?.let { return it }
        check(create) { "Settings key unavailable" }
        return KeyGenerator.getInstance(KeyProperties.KEY_ALGORITHM_AES, "AndroidKeyStore").apply {
            init(KeyGenParameterSpec.Builder(alias, KeyProperties.PURPOSE_ENCRYPT or KeyProperties.PURPOSE_DECRYPT)
                .setBlockModes(KeyProperties.BLOCK_MODE_GCM).setEncryptionPaddings(KeyProperties.ENCRYPTION_PADDING_NONE)
                .setKeySize(256).build())
        }.generateKey()
    }
    override fun load(): SavedSettings? {
        val saved = preferences.getString("encrypted-v1", null) ?: return null
        val bytes = Base64.decode(saved, Base64.NO_WRAP)
        require(bytes.size >= 28)
        val cipher = Cipher.getInstance("AES/GCM/NoPadding")
        cipher.init(Cipher.DECRYPT_MODE, key(false), GCMParameterSpec(128, bytes.copyOfRange(0, 12)))
        return settingsJson.decodeFromString(String(cipher.doFinal(bytes, 12, bytes.size - 12), Charsets.UTF_8))
    }
    override fun save(settings: SavedSettings) {
        val cipher = Cipher.getInstance("AES/GCM/NoPadding")
        cipher.init(Cipher.ENCRYPT_MODE, key(true))
        check(cipher.iv.size == 12)
        val bytes = cipher.iv + cipher.doFinal(settingsJson.encodeToString(settings).toByteArray(Charsets.UTF_8))
        check(preferences.edit().putString("encrypted-v1", Base64.encodeToString(bytes, Base64.NO_WRAP)).commit())
    }
}
