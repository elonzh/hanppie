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

    @Test fun invalidCatalogAddressesNeverCreateAnHttpClient(): Unit = runBlocking {
        var clients = 0
        val tester = ModelConfigurationTester(scope = this, createHttpClient = {
            clients++
            throw IllegalStateException("A rejected catalog address must not create a client")
        })
        try {
            for (endpoint in listOf("http://example.test/v1", "https://user:password@example.test/v1",
                "https://example.test/v1?token=value", "https://example.test/v1#fragment", "not-an-address")) {
                tester.loadCatalog(ModelSettings(endpoint = endpoint, apiKey = "offline-test-key"))
                withTimeout(5_000) { tester.catalogState.first { !it.loading && it.message != null } }
                assertEquals(0, clients, "Invalid endpoint must be rejected before creating the HTTP client")
            }
        } finally { tester.close() }
    }
}
