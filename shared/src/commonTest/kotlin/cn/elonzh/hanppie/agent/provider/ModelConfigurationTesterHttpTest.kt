package cn.elonzh.hanppie.agent.provider

import cn.elonzh.hanppie.resources.*
import cn.elonzh.hanppie.ui.i18n.*
import cn.elonzh.hanppie.ui.settings.*
import io.ktor.client.HttpClient
import io.ktor.client.engine.mock.*
import io.ktor.client.request.HttpRequestData
import io.ktor.http.*
import kotlin.test.*
import kotlinx.coroutines.*
import kotlinx.coroutines.flow.first

class ModelConfigurationTesterHttpTest {
    private val config = ModelSettings(ModelProviderPreset.CUSTOM, "https://models.example.test/v1",
        "offline-model", apiKey = "offline-model-key")
    private val catalog = """{"data":[{"id":"z-model"},{"id":"offline-model"},{"id":"offline-model"}]}"""

    @Test fun catalogWorksBeforeModelSelectionAndResultsMatchTheirConfiguration(): Unit = runBlocking {
        val requests = mutableListOf<HttpRequestData>()
        val tester = ModelConfigurationTester(this, {
            HttpClient(MockEngine) { engine { addHandler { request ->
                requests += request
                respond(catalog, headers = headersOf(HttpHeaders.ContentType, ContentType.Application.Json.toString()))
            } } }
        })
        Localization.initialize("zh", "zh")
        try {
            tester.loadCatalog(config.copy(model = ""))
            val listed = withTimeout(5_000) { tester.catalogState.first { !it.loading && it.models.isNotEmpty() } }
            assertEquals(listOf("offline-model", "z-model"), listed.models)
            assertTrue(listed.matches(config))
            assertTrue(listed.matches(config.copy(model = "another-model")))
            assertFalse(listed.matches(config.copy(apiKey = "new-key")))
            tester.test(config)
            val checked = withTimeout(5_000) { tester.state.first { !it.running && it.success != null } }
            assertEquals(true, checked.success)
            assertEquals(listOf(ModelTestStage.LOCAL, ModelTestStage.CATALOG), checked.stages.map { it.stage })
            assertTrue(checked.stages.all { it.passed })
            assertTrue(checked.matches(config))
            assertTrue(checked.matches(config.copy(thinkingDepth = ThinkingDepth.HIGH)))
            assertFalse(checked.matches(config.copy(model = "another-model")))
            assertFalse(checked.matches(config.copy(apiKey = "new-key")))
            assertFalse(checked.matches(config.copy(endpoint = "https://another.example.test/v1")))
            val feedback = assertNotNull(checked.message)
            for (language in AppLanguage.entries) {
                Localization.select(language.preference)
                assertEquals(tr(Res.string.model_test_complete), feedback.resolve())
                assertSame(feedback, tester.state.value.message)
            }
            assertEquals(2, requests.size)
            for (request in requests) {
                assertEquals("https://models.example.test/v1/models", request.url.toString())
                assertEquals("Bearer ${config.apiKey}", request.headers[HttpHeaders.Authorization])
            }
            assertFalse(checked.toString().contains(config.apiKey))
            assertFalse(listed.toString().contains(config.apiKey))
        } finally { tester.close(); Localization.initialize("zh", null) }
    }

    @Test fun catalogFailuresKeepLocalizedIdentityAndRawDiagnosticArguments(): Unit = runBlocking {
        val cases = listOf(
            Triple("""{"data":[{"id":"other-model"}]}""", HttpStatusCode.OK, Res.string.model_test_model_missing_value),
            Triple("""{"data":[]}""", HttpStatusCode.OK, Res.string.model_test_catalog_failed_value),
            Triple("not used", HttpStatusCode.Unauthorized, Res.string.model_catalog_load_failed_value),
        )
        for ((body, status, resource) in cases) {
            var requests = 0
            val tester = ModelConfigurationTester(this, {
                HttpClient(MockEngine) { engine { addHandler {
                    requests++
                    respond(body, status, headersOf(HttpHeaders.ContentType, ContentType.Application.Json.toString()))
                } } }
            })
            try {
                tester.test(config)
                val failed = withTimeout(5_000) { tester.state.first { !it.running && it.success != null } }
                assertEquals(false, failed.success)
                assertEquals(resource, failed.message?.resource)
                assertEquals(listOf(true, false), failed.stages.map { it.passed })
                assertEquals(1, requests)
            } finally { tester.close() }
        }
    }

    @Test fun credentialEchoesAreRemovedFromBothFailureStatesAndStageDiagnostics(): Unit = runBlocking {
        val secret = "OFFLINE_MODEL_KEY_ONLY_FOR_REDACTION_TEST"
        val selected = config.copy(apiKey = secret)
        var clients = 0
        val tester = ModelConfigurationTester(this, {
            clients++
            throw IllegalStateException("synthetic failure / Authorization: Bearer $secret / 原始诊断")
        })
        try {
            tester.loadCatalog(selected)
            val listed = withTimeout(5_000) { tester.catalogState.first { !it.loading && it.message != null } }
            val catalogError = assertNotNull(listed.message)
            assertFalse(catalogError.resolve().contains(secret))
            assertContains(catalogError.resolve(), "[redacted]")
            assertContains(catalogError.resolve(), "原始诊断")
            tester.test(selected)
            val checked = withTimeout(5_000) { tester.state.first { !it.running && it.success != null } }
            assertFalse(assertNotNull(checked.message).resolve().contains(secret))
            assertTrue(checked.stages.none { it.message.contains(secret) })
            assertEquals(2, clients)
        } finally { tester.close() }
    }

    @Test fun retiredCatalogCannotClearTheBusyStateAfterReturningToTheSameConfiguration(): Unit = runBlocking {
        val started = List(3) { CompletableDeferred<Unit>() }
        val release = List(3) { CompletableDeferred<Unit>() }
        val clientsClosed = List(3) { CompletableDeferred<Unit>() }
        var clients = 0
        val tester = ModelConfigurationTester(this, {
            val index = clients++
            HttpClient(MockEngine) { engine { addHandler {
                started[index].complete(Unit)
                withContext(NonCancellable) { release[index].await() }
                respond("""{"data":[{"id":"request-$index"}]}""",
                    headers = headersOf(HttpHeaders.ContentType, ContentType.Application.Json.toString()))
            } } }.also { http -> http.coroutineContext[Job]!!.invokeOnCompletion { clientsClosed[index].complete(Unit) } }
        })
        try {
            tester.loadCatalog(config)
            withTimeout(5_000) { started[0].await() }
            tester.loadCatalog(config.copy(apiKey = "second-key"))
            withTimeout(5_000) { started[1].await() }
            tester.loadCatalog(config)
            withTimeout(5_000) { started[2].await() }
            release[0].complete(Unit)
            withTimeout(5_000) { clientsClosed[0].await() }
            assertTrue(tester.catalogState.value.loading)
            assertTrue(tester.catalogState.value.models.isEmpty())
            release[1].complete(Unit)
            withTimeout(5_000) { clientsClosed[1].await() }
            assertTrue(tester.catalogState.value.loading)
            release[2].complete(Unit)
            val current = withTimeout(5_000) { tester.catalogState.first { !it.loading && it.models.isNotEmpty() } }
            assertEquals(listOf("request-2"), current.models)
            assertTrue(current.matches(config))
        } finally { release.forEach { it.complete(Unit) }; tester.close() }
    }

    @Test fun closingCatalogRetiresLateResponsesAndClearsLoading(): Unit = runBlocking {
        val started = CompletableDeferred<Unit>(); val release = CompletableDeferred<Unit>()
        val closed = CompletableDeferred<Unit>()
        val tester = ModelConfigurationTester(this, {
            HttpClient(MockEngine) { engine { addHandler {
                started.complete(Unit)
                withContext(NonCancellable) { release.await() }
                respond(catalog, headers = headersOf(HttpHeaders.ContentType, ContentType.Application.Json.toString()))
            } } }.also { http -> http.coroutineContext[Job]!!.invokeOnCompletion { closed.complete(Unit) } }
        })
        try {
            tester.loadCatalog(config)
            withTimeout(5_000) { started.await() }
            tester.close()
            release.complete(Unit)
            withTimeout(5_000) { closed.await() }
            assertFalse(tester.catalogState.value.loading)
            assertTrue(tester.catalogState.value.models.isEmpty())
        } finally { release.complete(Unit); tester.close() }
    }

    @Test fun explicitRetryReplacesFailureWithTheLatestResult(): Unit = runBlocking {
        var rejected = true
        var requests = 0
        val tester = ModelConfigurationTester(this, {
            HttpClient(MockEngine) { engine { addHandler {
                requests++
                respond(if (rejected) "unavailable" else catalog,
                    if (rejected) HttpStatusCode.ServiceUnavailable else HttpStatusCode.OK,
                    headersOf(HttpHeaders.ContentType, ContentType.Application.Json.toString()))
            } } }
        })
        try {
            tester.test(config)
            withTimeout(5_000) { tester.state.first { !it.running && it.success == false } }
            rejected = false
            tester.test(config)
            val latest = withTimeout(5_000) { tester.state.first { !it.running && it.success == true } }
            assertEquals(Res.string.model_test_complete, latest.message?.resource)
            assertEquals(2, requests)
        } finally { tester.close() }
    }
}
