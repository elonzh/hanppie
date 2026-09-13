package cn.elonzh.hanppie.ui.settings

import androidx.datastore.preferences.core.PreferenceDataStoreFactory
import cn.elonzh.hanppie.resources.*
import cn.elonzh.hanppie.ui.app.testConsoleModel
import cn.elonzh.hanppie.ui.robot.remote.RemoteLedState
import cn.elonzh.hanppie.ui.robot.remote.remoteLedState
import java.util.concurrent.CountDownLatch
import java.util.concurrent.TimeUnit
import kotlin.test.*
import kotlinx.coroutines.*
import kotlinx.coroutines.flow.first
import kotlinx.serialization.encodeToString
import org.junit.Test

class SettingsStoreTest {
    private abstract class TestSettingsStore : SettingsStore {
        override suspend fun loadUi() = UiPreferences()
        override suspend fun saveLanguage(language: String) = Unit
        override suspend fun saveAppearance(appearance: AppearanceSettings) = Unit
        override suspend fun saveSpeechService(service: String) = Unit
    }

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

    @Test fun dataStorePersistsSettingsIncludingApiKeyAndUiPreferences() = runBlocking {
        val directory = java.nio.file.Files.createTempDirectory("hanppie-settings-")
        val file = directory.resolve("settings.preferences_pb").toFile()
        val scope = CoroutineScope(SupervisorJob() + Dispatchers.IO)
        val store = DataStoreSettingsStore(PreferenceDataStoreFactory.create(scope = scope, produceFile = { file }))
        try {
            val expected = SavedSettings(ModelSettings(endpoint = "https://example.test/v1", model = "test", apiKey = "secret-value"))
            store.save(expected)
            store.saveLanguage("en")
            store.saveAppearance(AppearanceSettings(NightMode.DARK))
            store.saveSpeechService("test.service/.Recognizer")
            val connection = ConnectionPreferences(
                appId = "ABCDEF12",
                routerSsid = "test-network",
                routerPassword = "network-secret",
                robots = listOf(RememberedRobot("192.0.2.10", "ABCDEF12", "AA:BB:CC:DD:EE:FF", ConnectionMode.ROUTER)),
            )
            store.saveConnection(connection)

            assertEquals(expected, store.load())
            assertEquals(UiPreferences("en", AppearanceSettings(NightMode.DARK), "test.service/.Recognizer"), store.loadUi())
            assertEquals(connection.copy(appId = "abcdef12", robots = listOf(connection.robots.single().copy(appId = "abcdef12"))), store.loadConnection())
            assertTrue(file.readBytes().toString(Charsets.ISO_8859_1).contains("secret-value"))

            store.save(expected.copy(model = expected.model.copy(apiKey = "")))
            assertEquals("", store.load().model.apiKey)
        } finally {
            scope.cancel()
            directory.toFile().deleteRecursively()
        }
    }

    @Test fun connectionPreferencesKeepStableIdentityAndMostRecentRobots() {
        val initial = ConnectionPreferences(appId = "ABCDEF12")
            .remember(RememberedRobot("192.168.2.1", "ABCDEF12", "00:11:22:33:44:55", ConnectionMode.DIRECT))
            .remember(RememberedRobot("192.0.2.20", "ABCDEF12", "00:11:22:33:44:55", ConnectionMode.ROUTER))
            .normalized()

        assertEquals("abcdef12", initial.appId)
        assertEquals(1, initial.robots.size)
        assertEquals("192.0.2.20", initial.robots.single().ip)
        assertEquals(ConnectionMode.ROUTER, initial.robots.single().mode)
    }

    @Test fun restoreDefaultsClearsPersistedSettings() = runBlocking {
        var saved: SavedSettings? = SavedSettings(ModelSettings(apiKey = "secret"),
            ControlSettings(remoteLeds = RemoteLedSettings(active = RobotLedColor(1, 2, 3))))
        val store = object : TestSettingsStore() {
            override suspend fun load() = checkNotNull(saved)
            override suspend fun save(settings: SavedSettings) { saved = settings }
        }
        val model = testConsoleModel(settingsStore = store)
        try {
            withTimeout(5_000) { model.settingsBusy.first { !it } }
            model.restoreDefaultSettings()
            withTimeout(5_000) { model.settingsBusy.first { !it } }
            assertEquals(SavedSettings(), saved)
            assertEquals(ControlSettings(), model.controlSettings.value)
            assertEquals(cn.elonzh.hanppie.resources.Res.string.default_settings_restored,
                model.settingsMessage.value?.resource)
        } finally { model.close() }
    }

    @Test fun saveLoadAndFailureStatus() = runBlocking {
        var saved: SavedSettings? = null
        var fail = false
        val store = object : TestSettingsStore() {
            override suspend fun load() = saved ?: SavedSettings()
            override suspend fun save(settings: SavedSettings) {
                if (fail) error("secret backend details")
                saved = settings
            }
        }
        val expected = SavedSettings(ModelSettings(apiKey = "test-key"), ControlSettings(45))
        val model = testConsoleModel(settingsStore = store)
        try {
            withTimeout(5000) { model.settingsBusy.first { !it } }
            model.modelSettings.value = expected.model
            model.controlSettings.value = expected.control
            model.saveSettings()
            withTimeout(5000) { model.settingsBusy.first { !it } }
            assertEquals(expected, saved)
            fail = true
            model.saveSettings()
            withTimeout(5000) { model.settingsBusy.first { !it } }
            assertEquals(cn.elonzh.hanppie.resources.Res.string.settings_save_failed, model.settingsMessage.value?.resource)
        } finally { model.close() }
        val restored = testConsoleModel(settingsStore = store)
        try {
            withTimeout(5000) { restored.settingsBusy.first { !it } }
            assertEquals(expected.model, restored.modelSettings.value)
            assertEquals(expected.control, restored.controlSettings.value)
        } finally { restored.close() }
    }

    @Test fun restoreQueuedDuringLoadWinsAndClearsPersistedSettings() = runBlocking {
        val loadStarted = CountDownLatch(1)
        val continueLoad = CountDownLatch(1)
        var saved = SavedSettings(ModelSettings(apiKey = "old-secret"), ControlSettings(45))
        val store = object : TestSettingsStore() {
            override suspend fun load(): SavedSettings {
                loadStarted.countDown()
                check(continueLoad.await(2, TimeUnit.SECONDS))
                return saved
            }

            override suspend fun save(settings: SavedSettings) { saved = settings }
        }
        val model = testConsoleModel(settingsStore = store)
        try {
            assertTrue(loadStarted.await(2, TimeUnit.SECONDS))
            model.restoreDefaultSettings()
            continueLoad.countDown()
            withTimeout(5_000) { model.settingsBusy.first { !it } }

            assertEquals(SavedSettings(), saved)
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
        val store = object : TestSettingsStore() {
            override suspend fun load(): SavedSettings = SavedSettings()
            override suspend fun save(settings: SavedSettings) {
                if (saved.isEmpty()) {
                    firstSaveStarted.countDown()
                    check(continueFirstSave.await(2, TimeUnit.SECONDS))
                }
                saved += settings
            }
        }
        val model = testConsoleModel(settingsStore = store)
        try {
            withTimeout(5_000) { model.settingsBusy.first { !it } }
            val first = SavedSettings(ModelSettings(apiKey = "first"), ControlSettings(45))
            model.modelSettings.value = first.model
            model.controlSettings.value = first.control
            model.saveSettings()
            assertTrue(firstSaveStarted.await(2, TimeUnit.SECONDS))

            val second = SavedSettings(ModelSettings(apiKey = "second"), ControlSettings(60))
            model.modelSettings.value = second.model
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

}
