package cn.elonzh.hanppie

import android.content.Context
import androidx.compose.ui.semantics.SemanticsProperties
import androidx.compose.ui.semantics.getOrNull
import androidx.compose.ui.test.*
import androidx.compose.ui.test.junit4.v2.createEmptyComposeRule
import androidx.test.core.app.ActivityScenario
import androidx.test.platform.app.InstrumentationRegistry
import java.io.File
import org.junit.Rule
import org.junit.Test

/** No network or robot use. Optional private input file is consumed without printing the key. */
class PhoneSettingsUiTest {
    @get:Rule val rule = createEmptyComposeRule()

    @Test fun saveAndRestoreAfterActivityIsDestroyed() {
        val context = InstrumentationRegistry.getInstrumentation().targetContext
        val preferences = context.getSharedPreferences("model-settings", Context.MODE_PRIVATE)
        val original = preferences.getString("encrypted-v1", null)
        val importRequested = InstrumentationRegistry.getArguments().getString("importProvidedKey") == "1"
        val key = if (importRequested) File(context.filesDir, "live-test-key").let {
            try { it.readText().trim().also { value -> check(value.isNotBlank()) } } finally { it.delete() }
        } else "non-secret-persistence-fixture"
        var scenario: ActivityScenario<MainActivity>? = null
        try {
            scenario = ActivityScenario.launch(MainActivity::class.java)
            rule.onNodeWithContentDescription("设置").performClick()
            rule.waitUntil(5000) {
                rule.onNodeWithText("API Key").fetchSemanticsNode().config.getOrNull(SemanticsProperties.Disabled) == null
            }
            rule.onNodeWithText("API Key").performTextReplacement(key)
            rule.onNodeWithText("保存设置").performScrollTo().performClick()
            rule.waitUntil(10000) { rule.onAllNodesWithText("已保存").fetchSemanticsNodes().isNotEmpty() }
            check(!requireNotNull(preferences.getString("encrypted-v1", null)).contains(key)) { "Plaintext key on disk" }
            scenario.close()
            scenario = ActivityScenario.launch(MainActivity::class.java)
            rule.onNodeWithContentDescription("设置").performClick()
            rule.waitUntil(5000) {
                rule.onNodeWithText("API Key").fetchSemanticsNode().config.getOrNull(SemanticsProperties.Disabled) == null
            }
            check(rule.onNodeWithText("API Key").fetchSemanticsNode().config[SemanticsProperties.InputText].text == key) {
                "Saved key not restored"
            }
        } finally {
            scenario?.close()
            if (!importRequested) check(preferences.edit().apply {
                if (original == null) remove("encrypted-v1") else putString("encrypted-v1", original)
            }.commit())
        }
    }
}
