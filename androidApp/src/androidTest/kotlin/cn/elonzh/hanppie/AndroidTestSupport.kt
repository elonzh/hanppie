package cn.elonzh.hanppie

import android.app.Activity
import android.content.Context
import android.graphics.Bitmap
import android.os.Build
import android.os.Handler
import android.os.Looper
import android.os.ParcelFileDescriptor
import android.view.PixelCopy
import androidx.test.core.app.ActivityScenario
import androidx.test.platform.app.InstrumentationRegistry
import androidx.test.runner.lifecycle.ActivityLifecycleMonitorRegistry
import androidx.test.runner.lifecycle.Stage
import java.io.File
import java.util.concurrent.CountDownLatch
import java.util.concurrent.TimeUnit
import org.junit.rules.TestRule
import org.junit.runner.Description
import org.junit.runners.model.Statement

internal class TestLocaleRule(private val language: String = "zh") : TestRule {
    override fun apply(base: Statement, description: Description): Statement = object : Statement() {
        override fun evaluate() {
            val context = InstrumentationRegistry.getInstrumentation().targetContext
            val preferences = context.getSharedPreferences("hanppie-ui", Context.MODE_PRIVATE)
            val existed = preferences.contains("language")
            val original = preferences.getString("language", null)
            check(preferences.edit().putString("language", language).commit())
            try {
                base.evaluate()
            } finally {
                val editor = preferences.edit()
                if (existed) editor.putString("language", original) else editor.remove("language")
                check(editor.commit())
            }
        }
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
