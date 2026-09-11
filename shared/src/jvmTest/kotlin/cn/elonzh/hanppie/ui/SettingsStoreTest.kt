package cn.elonzh.hanppie.ui

import com.github.javakeyring.Keyring
import cn.elonzh.hanppie.resources.*
import kotlinx.coroutines.*
import kotlinx.coroutines.flow.first
import kotlinx.serialization.encodeToString
import org.junit.Assume.assumeTrue
import org.junit.Test
import kotlin.test.*

class SettingsStoreTest {
    @Test fun olderSettingsUseDefaultControlSensitivity() {
        val restored = settingsJson.decodeFromString<SavedSettings>("""{"autoRead":true}""")
        assertEquals(ControlSettings(90), restored.control)
        assertEquals(KeyBinding(ControlKey.G), restored.control.shortcuts[ControlAction.SwitchAmmo])
        assertEquals(KeyBinding(ControlKey.R, shift = true), restored.control.shortcuts[ControlAction.Recording])
        assertEquals(RemoteLedSettings(), restored.control.remoteLeds)
    }

    @Test fun customMotionAndShortcutSettingsRoundTrip() {
        val control = ControlSettings(
            translationSpeeds = listOf(.1, .2, .3, .4, .5),
            rotationSpeeds = listOf(20.0, 40.0, 60.0, 80.0, 100.0),
            creepMultiplier = .5,
            joystickDeadZone = .2,
            shortcuts = ControlShortcuts().bind(ControlAction.Fire, KeyBinding(ControlKey.F)),
            remoteLeds = RemoteLedSettings(active = RobotLedColor(1, 2, 3)),
        )
        val restored = settingsJson.decodeFromString<SavedSettings>(settingsJson.encodeToString(SavedSettings(control = control)))
        assertEquals(control, restored.control)
        assertEquals("#010203", restored.control.remoteLeds.active.hex)
        assertEquals(RobotLedColor(0xab, 0xcd, 0xef), RobotLedColor.parse("#aBcDeF"))
        assertNull(RobotLedColor.parse("abcdef"))
        assertEquals(RemoteLedState.TALKING, remoteLedState(enabled = true, recording = true, talking = true))
        assertEquals(RemoteLedState.RECORDING, remoteLedState(enabled = true, recording = true, talking = false))
        assertEquals(RemoteLedState.ACTIVE, remoteLedState(enabled = true, recording = false, talking = false))
        assertEquals(RemoteLedState.STANDBY, remoteLedState(enabled = false, recording = false, talking = false))
    }

    @Test fun restoreDefaultsClearsPersistedSettings() = runBlocking {
        var saved: SavedSettings? = SavedSettings(ModelSettings(apiKey = "secret"), true,
            ControlSettings(remoteLeds = RemoteLedSettings(active = RobotLedColor(1, 2, 3))))
        val store = object : SettingsStore {
            override fun load() = saved
            override fun save(settings: SavedSettings) { saved = settings }
        }
        val model = ConsoleModel(SystemSpeech(), settingsStore = store)
        try {
            withTimeout(5_000) { model.settingsBusy.first { !it } }
            model.restoreDefaultSettings()
            withTimeout(5_000) { model.settingsBusy.first { !it } }
            assertEquals(SavedSettings(), saved)
            assertFalse(model.autoReadReplies.value)
            assertEquals(ControlSettings(), model.controlSettings.value)
            assertEquals(cn.elonzh.hanppie.resources.Res.string.default_settings_restored,
                model.settingsMessage.value?.resource)
        } finally { model.close() }
    }

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
        val expected = SavedSettings(ModelSettings(apiKey = "test-key"), true, ControlSettings(45))
        val model = ConsoleModel(SystemSpeech(), settingsStore = store)
        try {
            withTimeout(5000) { model.settingsBusy.first { !it } }
            model.modelSettings.value = expected.model
            model.autoReadReplies.value = expected.autoRead
            model.controlSettings.value = expected.control
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
            assertEquals(expected.control, restored.controlSettings.value)
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
