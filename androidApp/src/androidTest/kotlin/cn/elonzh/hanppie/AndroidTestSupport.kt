package cn.elonzh.hanppie

import android.app.Activity
import android.content.Context
import android.graphics.Bitmap
import android.os.Build
import android.os.Handler
import android.os.Looper
import android.os.ParcelFileDescriptor
import android.view.PixelCopy
import androidx.datastore.preferences.core.PreferenceDataStoreFactory
import androidx.datastore.preferences.core.edit
import androidx.datastore.preferences.core.stringPreferencesKey
import androidx.test.core.app.ActivityScenario
import androidx.test.platform.app.InstrumentationRegistry
import androidx.test.runner.lifecycle.ActivityLifecycleMonitorRegistry
import androidx.test.runner.lifecycle.Stage
import java.io.File
import java.util.concurrent.CountDownLatch
import java.util.concurrent.TimeUnit
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.Job
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.cancel
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.runBlocking
import org.junit.rules.TestRule
import org.junit.runner.Description
import org.junit.runners.model.Statement

internal class TestLocaleRule(private val language: String = "zh") : TestRule {
    override fun apply(base: Statement, description: Description): Statement = object : Statement() {
        override fun evaluate() {
            val context = InstrumentationRegistry.getInstrumentation().targetContext
            val key = stringPreferencesKey("language")
            val original = withSettingsDataStore(context) { it.data.first()[key] }
            withSettingsDataStore(context) { store -> store.edit { it[key] = language } }
            try {
                base.evaluate()
            } finally {
                withSettingsDataStore(context) { store ->
                    store.edit { preferences ->
                        if (original == null) preferences.remove(key) else preferences[key] = original
                    }
                }
            }
        }
    }
}

internal fun settingsDataStoreFile(context: Context) = File(context.filesDir, "settings.preferences_pb")

internal fun <T> withSettingsDataStore(
    context: Context,
    block: suspend (androidx.datastore.core.DataStore<androidx.datastore.preferences.core.Preferences>) -> T,
): T = runBlocking {
    val scope = CoroutineScope(SupervisorJob() + Dispatchers.IO)
    val store = PreferenceDataStoreFactory.create(scope = scope, produceFile = { settingsDataStoreFile(context) })
    try {
        block(store)
    } finally {
        scope.cancel()
        scope.coroutineContext[Job]?.join()
    }
}

/** HyperOS can stall ActivityScenario launches; other platforms use the hermetic test API. */
internal fun launchMainActivityForTest(): AutoCloseable {
    if (!Build.MANUFACTURER.equals("Xiaomi", ignoreCase = true)) {
        return ActivityScenario.launch(MainActivity::class.java)
    }
    val instrumentation = InstrumentationRegistry.getInstrumentation()
    ParcelFileDescriptor.AutoCloseInputStream(instrumentation.uiAutomation.executeShellCommand(
        "am start -W -n cn.elonzh.hanppie/.MainActivity"
    )).use { it.readBytes() }
    return AutoCloseable {
        resumedActivity()?.let { activity -> instrumentation.runOnMainSync { activity.finish() } }
    }
}

internal fun captureActivityScreenshot(name: String) {
    val instrumentation = InstrumentationRegistry.getInstrumentation()
    val activity = requireNotNull(resumedActivity()) { "No resumed Hanppie activity" }
    val width = activity.window.decorView.width
    val height = activity.window.decorView.height
    check(width > 0 && height > 0) { "Activity has no drawable area" }
    val bitmap = Bitmap.createBitmap(width, height, Bitmap.Config.ARGB_8888)
    val copied = CountDownLatch(1)
    var result = PixelCopy.ERROR_UNKNOWN
    PixelCopy.request(activity.window, bitmap, { value -> result = value; copied.countDown() }, Handler(Looper.getMainLooper()))
    check(copied.await(5, TimeUnit.SECONDS) && result == PixelCopy.SUCCESS) { "Activity screenshot failed: $result" }
    val destination = File(instrumentation.targetContext.getExternalFilesDir(null), "ui-$name.png")
    destination.outputStream().use { bitmap.compress(Bitmap.CompressFormat.PNG, 100, it) }
    bitmap.recycle()
}

private fun resumedActivity(): Activity? {
    var activity: Activity? = null
    InstrumentationRegistry.getInstrumentation().runOnMainSync {
        activity = ActivityLifecycleMonitorRegistry.getInstance().getActivitiesInStage(Stage.RESUMED).firstOrNull()
    }
    return activity
}
