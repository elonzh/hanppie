package cn.elonzh.hanppie.ui.settings

import androidx.compose.runtime.*
import androidx.compose.ui.platform.LocalDensity
import androidx.compose.ui.semantics.SemanticsActions
import androidx.compose.ui.test.*
import androidx.compose.ui.test.v2.runDesktopComposeUiTest
import androidx.compose.ui.unit.Density
import androidx.compose.ui.unit.dp
import cn.elonzh.hanppie.resources.*
import cn.elonzh.hanppie.ui.app.Console
import cn.elonzh.hanppie.ui.app.testConsoleModel
import cn.elonzh.hanppie.ui.design.TestWorkbenchTheme
import cn.elonzh.hanppie.ui.i18n.*
import cn.elonzh.hanppie.ui.scripts.EditorDocument
import io.ktor.client.HttpClient
import io.ktor.client.engine.mock.*
import io.ktor.http.*
import java.awt.image.BufferedImage
import java.io.File
import javax.imageio.ImageIO
import kotlin.test.*

/** Actual settings controls, localized feedback, service identity and explicit mock-engine retry. */
@OptIn(ExperimentalTestApi::class)
class ModelFeedbackUiTest {
    @Test fun compactLandscape() = feedback(844, 390)
    @Test fun tabletDark() = feedback(1024, 768, dark = true)
    @Test fun desktopLandscape() = feedback(1280, 720)
    @Test fun largeSupportedLanguages() = feedback(844, 390, 1.4f, dark = true)

    private fun feedback(width: Int, height: Int, scale: Float = 1f, dark: Boolean = false) =
        runDesktopComposeUiTest(width = width, height = height) {
            Localization.initialize("zh", "zh")
            val config = ModelSettings(ModelProviderPreset.CUSTOM, "https://offline-model.example.test/v1",
                "offline-model", apiKey = "UI_ONLY_MODEL_TEST_KEY")
            val failure = IllegalStateException(("OFFLINE EIO / ${config.apiKey} / 原始诊断 / détails\n").repeat(40))
            var rejected = true
            var clients = 0
            val model = testConsoleModel(createAgentHttpClient = {
                clients++
                if (rejected) throw failure
                HttpClient(MockEngine) { engine { addHandler {
                    respond("""{"data":[{"id":"offline-model"}]}""",
                        headers = headersOf(HttpHeaders.ContentType, ContentType.Application.Json.toString()))
                } } }
            })
            val appearance = AppearanceController(AppearanceSettings(if (dark) NightMode.DARK else NightMode.LIGHT))
            fun picture(name: String) {
                val image = onNode(isRoot() and hasAnyDescendant(hasTestTag("settings-detail-model"))).captureToImage()
                val pixels = IntArray(image.width * image.height); image.readPixels(pixels)
                val out = BufferedImage(image.width, image.height, BufferedImage.TYPE_INT_ARGB)
                out.setRGB(0, 0, image.width, image.height, pixels, 0, image.width)
                val file = File("build/reports/ui/model-feedback-$width-$height-$scale-$name.png")
                file.parentFile.mkdirs(); ImageIO.write(out, "png", file)
            }
            fun pickModel() {
                onNodeWithTag("model-picker").performScrollTo().performClick()
                waitUntil(timeoutMillis = 5_000) { !model.modelCatalogState.value.loading }
                onNodeWithTag("model-option-offline-model").performClick()
            }
            try {
                waitUntil(timeoutMillis = 5_000) { !model.settingsBusy.value }
                runOnIdle { model.modelSettings.value = config }
                setContent { TestWorkbenchTheme(appearance) {
                    CompositionLocalProvider(LocalDensity provides Density(1f, scale)) {
                        Console(model, remember { mutableStateOf(EditorDocument()) })
                    }
                } }
                onNodeWithContentDescription("设置").performClick()
                onNodeWithTag("settings-category-model").performScrollTo().performClick()
                onNodeWithTag("model-test").performScrollTo().assertHeightIsAtLeast(48.dp).performClick()
                waitUntil(timeoutMillis = 5_000) { !model.modelTestState.value.running && model.modelTestState.value.success == false }
                pickModel()
                val testError = assertNotNull(model.modelTestState.value.message)
                val catalogError = assertNotNull(model.modelCatalogState.value.message)
                val languages = AppLanguage.entries
                for (language in languages) {
                    runOnIdle { Localization.select(language.preference) }
                    assertSame(testError, model.modelTestState.value.message)
                    assertSame(catalogError, model.modelCatalogState.value.message)
                    onNodeWithTag("model-catalog-message").performScrollTo().assertTextContains(catalogError.resolve())
                    assertTrue(onNodeWithTag("model-catalog-message").fetchSemanticsNode().boundsInRoot.height <= 96.5f)
                    onNodeWithTag("model-test-message").performScrollTo().assertTextContains(testError.resolve(), substring = true)
                    assertTrue(onNodeWithTag("model-test-message").fetchSemanticsNode().boundsInRoot.height <= 96.5f)
                    onNodeWithTag("model-test").assertIsDisplayed().assertIsEnabled()
                    assertFalse(testError.resolve().contains(config.apiKey))
                    picture("test-${language.preference}")
                }
                onNodeWithTag("model-catalog-message").performScrollTo()
                onNodeWithTag("model-catalog-message").performSemanticsAction(SemanticsActions.ScrollBy) { it(0f, 500f) }
                picture("catalog")
                assertEquals(2, clients, "Changing language must not repeat either operation")
                onNodeWithTag("model-api-key").performScrollTo().performTextReplacement("changed-key")
                onNodeWithTag("model-test-message").assertDoesNotExist()
                onNodeWithTag("model-catalog-message").assertDoesNotExist()
                onNodeWithTag("model-api-key").performTextReplacement(config.apiKey)
                onNodeWithTag("model-id").performScrollTo().performTextReplacement("different-model")
                onNodeWithTag("model-test-message").assertDoesNotExist()
                onNodeWithTag("model-catalog-message").assertExists()
                onNodeWithTag("model-id").performTextReplacement(config.model)
                runOnIdle { rejected = false }
                pickModel()
                onNodeWithTag("model-catalog-message").assertDoesNotExist()
                onNodeWithTag("model-test").performScrollTo().performClick()
                waitUntil(timeoutMillis = 5_000) { !model.modelTestState.value.running && model.modelTestState.value.success == true }
                onNodeWithTag("model-test-message").performScrollTo().assertTextContains(tr(Res.string.model_test_complete), substring = true)
                onNodeWithTag("model-test").assertIsEnabled()
                assertEquals(4, clients)
                picture("recovered")
            } finally { model.close(); Localization.initialize("zh", null) }
        }
}
