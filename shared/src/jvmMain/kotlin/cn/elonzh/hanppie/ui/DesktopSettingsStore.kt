package cn.elonzh.hanppie.ui

import com.github.javakeyring.Keyring
import kotlinx.serialization.encodeToString

/** One atomic credential item avoids mismatched endpoint/key pairs after interrupted writes. */
internal class DesktopSettingsStore(private val account: String = "model-settings-v1") : SettingsStore {
    private val service = "cn.elonzh.hanppie"
    private val preferences = java.util.prefs.Preferences.userRoot().node("cn/elonzh/hanppie/credentials")
    override fun load(): SavedSettings? {
        if (!preferences.getBoolean(account, false)) return null
        return Keyring.create().use { keyring ->
            settingsJson.decodeFromString<SavedSettings>(keyring.getPassword(service, account))
        }
    }
    override fun save(settings: SavedSettings) = Keyring.create().use { keyring ->
        keyring.setPassword(service, account, settingsJson.encodeToString(settings))
        preferences.putBoolean(account, true)
        preferences.flush()
    }
}
