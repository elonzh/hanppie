package cn.elonzh.hanppie.ui.chat

import io.ktor.client.HttpClient
import io.ktor.client.engine.darwin.Darwin

internal fun createAgentHttpClient(): HttpClient = HttpClient(Darwin)
