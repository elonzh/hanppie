package cn.elonzh.hanppie.ui

import com.github.javakeyring.Keyring
import cn.elonzh.hanppie.resources.*
import kotlinx.coroutines.*
import kotlinx.coroutines.flow.first
import org.junit.Assume.assumeTrue
import org.junit.Test
import kotlin.test.*

class SettingsStoreTest {
    @Test fun saveLoadAndFailureStatus() = runBlocking {
        var saved: SavedSettings? = null
        var fail = false
        val store = object : SettingsStore {
            override fun load() = saved
            override fun save(settings: SavedSettings) {
                if (fail) error("secret backend details")
                saved = settings
            }
        }
        val expected = SavedSettings(ModelSettings(apiKey = "test-key"), true)
        val model = ConsoleModel(SystemSpeech(), settingsStore = store)
        try {
            withTimeout(5000) { model.settingsBusy.first { !it } }
            model.modelSettings.value = expected.model
            model.autoReadReplies.value = expected.autoRead
            model.saveSettings()
            withTimeout(5000) { model.settingsBusy.first { !it } }
            assertEquals(expected, saved)
            fail = true
            model.saveSettings()
            withTimeout(5000) { model.settingsBusy.first { !it } }
            assertEquals(cn.elonzh.hanppie.resources.Res.string.settings_save_failed, model.settingsMessage.value?.resource)
        } finally { model.close() }
        val restored = ConsoleModel(SystemSpeech(), settingsStore = store)
        try {
            withTimeout(5000) { restored.settingsBusy.first { !it } }
            assertEquals(expected.model, restored.modelSettings.value)
            assertTrue(restored.autoReadReplies.value)
        } finally { restored.close() }
    }

    @Test fun nativeCredentialStoreRoundTrip() {
        assumeTrue(System.getenv("HANPPIE_TEST_KEYRING") == "1")
        val account = "test-${java.util.UUID.randomUUID()}"
        try {
            val store = DesktopSettingsStore(account)
            assertNull(store.load())
            val expected = SavedSettings(ModelSettings(apiKey = "non-secret-test-value"), true)
            store.save(expected)
            assertEquals(expected, DesktopSettingsStore(account).load())
        } finally {
            Keyring.create().use { it.deletePassword("cn.elonzh.hanppie", account) }
            java.util.prefs.Preferences.userRoot().node("cn/elonzh/hanppie/credentials").apply {
                remove(account); flush()
            }
        }
    }
}
