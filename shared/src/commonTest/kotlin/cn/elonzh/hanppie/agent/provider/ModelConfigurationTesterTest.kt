package cn.elonzh.hanppie.agent.provider

import cn.elonzh.hanppie.ui.settings.ModelSettings
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertTrue
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.runBlocking
import kotlinx.coroutines.withTimeout

class ModelConfigurationTesterTest {
    @Test fun theTestOnlyVerifiesAuthenticationAndANormalResponse() {
        // Streaming text and tool calling are deliberately not probed: anything beyond "the endpoint
        // authenticates and answers" would mean shaping model requests for the test's sake.
        assertEquals(listOf(ModelTestStage.LOCAL, ModelTestStage.CATALOG), ModelTestStage.entries.toList())
    }

    @Test fun anIncompleteConfigurationFailsLocallyWithoutAnyRequest() = runBlocking {
        val tester = ModelConfigurationTester(
            scope = this,
            createHttpClient = { throw AssertionError("an invalid configuration must not reach the network") },
        )
        tester.test(ModelSettings(apiKey = ""))
        val state = withTimeout(5_000) {
            tester.state.first { !it.running && it.success != null }
        }
        assertEquals(false, state.success)
        assertEquals(false, state.running)
        assertEquals(listOf(ModelTestStage.LOCAL), state.stages.map { it.stage })
        assertEquals(false, state.stages.single().passed)
        assertTrue(state.stages.none { it.stage == ModelTestStage.CATALOG },
            "no catalog request may be attempted before the configuration is valid")
    }
}
