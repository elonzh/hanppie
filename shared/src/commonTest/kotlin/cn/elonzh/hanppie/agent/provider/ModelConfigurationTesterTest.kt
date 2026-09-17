package cn.elonzh.hanppie.agent.provider

import cn.elonzh.hanppie.ui.settings.ModelSettings
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertTrue
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.delay
import kotlinx.coroutines.runBlocking
import kotlinx.coroutines.withTimeout

class ModelConfigurationTesterTest {
    private fun tester() = ModelConfigurationTester(
        scope = CoroutineScope(SupervisorJob()),
        createHttpClient = { throw AssertionError("an invalid configuration must not reach the network") },
    )

    @Test fun theTestOnlyVerifiesAuthenticationAndANormalResponse() {
        // Streaming text and tool calling are deliberately not probed: anything beyond "the endpoint
        // authenticates and answers" would mean shaping model requests for the test's sake.
        assertEquals(listOf(ModelTestStage.LOCAL, ModelTestStage.CATALOG), ModelTestStage.entries.toList())
    }

    @Test fun anIncompleteConfigurationFailsLocallyWithoutAnyRequest() = runBlocking {
        val tester = tester()
        tester.test(ModelSettings(apiKey = ""))
        withTimeout(5_000) {
            while (tester.state.value.success == null) delay(10)
        }
        val state = tester.state.value
        assertEquals(false, state.success)
        assertEquals(false, state.running)
        assertTrue(state.stages.none { it.stage == ModelTestStage.CATALOG },
            "no catalog request may be attempted before the configuration is valid")
    }
}
