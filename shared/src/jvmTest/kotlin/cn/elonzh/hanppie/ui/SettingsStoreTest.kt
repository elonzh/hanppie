package cn.elonzh.hanppie.ui

import com.github.javakeyring.Keyring
import cn.elonzh.hanppie.resources.*
import kotlinx.coroutines.*
import kotlinx.coroutines.flow.first
import kotlinx.serialization.encodeToString
import org.junit.Assume.assumeTrue
import org.junit.Test
import java.util.concurrent.CountDownLatch
import java.util.concurrent.TimeUnit
import kotlin.test.*

class SettingsStoreTest {
    @Test fun olderSettingsUseDefaultControlSensitivity() {
        val restored = settingsJson.decodeFromString<SavedSettings>("""{"autoRead":true}""")
        assertEquals(ControlSettings(90), restored.control)
        assertEquals(KeyBinding(ControlKey.G), restored.control.shortcuts[ControlAction.SwitchAmmo])
        assertEquals(KeyBinding(ControlKey.R, shift = true), restored.control.shortcuts[ControlAction.Recording])
        assertEquals(RemoteLedSettings(), restored.control.remoteLeds)
    }

    @Test fun removedCreepSettingsPreserveOtherPreferences() {
        val saved = settingsJson.decodeFromString<SavedSettings>("""{"autoRead":true,"control":{"creepMultiplier":0.5,"gimbalSpeed":60,"shortcuts":{"bindings":{"Creep":{"key":"Shift"},"Fire":{"key":"F"}}}}}""")
        assertTrue(saved.autoRead)
        assertEquals(60, saved.control.gimbalSpeed)
        assertEquals(KeyBinding(ControlKey.F), saved.control.shortcuts[ControlAction.Fire])
        assertFalse(settingsJson.encodeToString(saved).contains("Creep"))
        assertFalse(settingsJson.encodeToString(saved).contains("creepMultiplier"))
    }

    @Test fun customMotionAndShortcutSettingsRoundTrip() {
        val control = ControlSettings(
            translationSpeeds = listOf(.1, .2, .3, .4, .5),
            rotationSpeeds = listOf(20.0, 40.0, 60.0, 80.0, 100.0),
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

    @Test fun restoreQueuedDuringLoadWinsAndClearsPersistedSettings() = runBlocking {
        val loadStarted = CountDownLatch(1)
        val continueLoad = CountDownLatch(1)
        var saved = SavedSettings(ModelSettings(apiKey = "old-secret"), true, ControlSettings(45))
        val store = object : SettingsStore {
            override fun load(): SavedSettings {
                loadStarted.countDown()
                check(continueLoad.await(2, TimeUnit.SECONDS))
                return saved
            }

            override fun save(settings: SavedSettings) { saved = settings }
        }
        val model = ConsoleModel(SystemSpeech(), settingsStore = store)
        try {
            assertTrue(loadStarted.await(2, TimeUnit.SECONDS))
            model.restoreDefaultSettings()
            continueLoad.countDown()
            withTimeout(5_000) { model.settingsBusy.first { !it } }

            assertEquals(SavedSettings(), saved)
            assertFalse(model.autoReadReplies.value)
            assertEquals(ControlSettings(), model.controlSettings.value)
            assertEquals(Res.string.default_settings_restored, model.settingsMessage.value?.resource)
        } finally {
            continueLoad.countDown()
            model.close()
        }
    }

    @Test fun saveQueuedDuringAnotherSaveIsNotDropped() = runBlocking {
        val firstSaveStarted = CountDownLatch(1)
        val continueFirstSave = CountDownLatch(1)
        val saved = java.util.Collections.synchronizedList(mutableListOf<SavedSettings>())
        val store = object : SettingsStore {
            override fun load(): SavedSettings? = null
            override fun save(settings: SavedSettings) {
                if (saved.isEmpty()) {
                    firstSaveStarted.countDown()
                    check(continueFirstSave.await(2, TimeUnit.SECONDS))
                }
                saved += settings
            }
        }
        val model = ConsoleModel(SystemSpeech(), settingsStore = store)
        try {
            withTimeout(5_000) { model.settingsBusy.first { !it } }
            val first = SavedSettings(ModelSettings(apiKey = "first"), true, ControlSettings(45))
            model.modelSettings.value = first.model
            model.autoReadReplies.value = first.autoRead
            model.controlSettings.value = first.control
            model.saveSettings()
            assertTrue(firstSaveStarted.await(2, TimeUnit.SECONDS))

            val second = SavedSettings(ModelSettings(apiKey = "second"), false, ControlSettings(60))
            model.modelSettings.value = second.model
            model.autoReadReplies.value = second.autoRead
            model.controlSettings.value = second.control
            model.saveSettings()
            continueFirstSave.countDown()
            withTimeout(5_000) { model.settingsBusy.first { !it } }

            assertEquals(listOf(first, second), saved.toList())
        } finally {
            continueFirstSave.countDown()
            model.close()
        }
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
