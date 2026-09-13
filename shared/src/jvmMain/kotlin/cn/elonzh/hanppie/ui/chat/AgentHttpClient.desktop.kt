package cn.elonzh.hanppie.ui.chat

import io.ktor.client.HttpClient
import io.ktor.client.engine.okhttp.OkHttp

internal fun createAgentHttpClient(): HttpClient = HttpClient(OkHttp) {
    engine {
        config {
            retryOnConnectionFailure(false)
        }
    }
}
