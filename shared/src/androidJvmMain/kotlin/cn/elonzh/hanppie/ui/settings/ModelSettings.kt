package cn.elonzh.hanppie.ui.settings

import cn.elonzh.hanppie.resources.Res
import cn.elonzh.hanppie.resources.enter_a_model_and_api_key
import cn.elonzh.hanppie.resources.enter_an_https_api_endpoint
import cn.elonzh.hanppie.ui.i18n.tr
import java.net.URI
import kotlinx.serialization.Serializable

@Serializable
internal data class ModelSettings(
    val endpoint: String = "https://dashscope.aliyuncs.com/compatible-mode/v1",
    val model: String = "deepseek-v4-flash-0731",
    val apiKey: String = "",
) {
    fun validate() {
        val uri = URI(endpoint)
        require(
            uri.scheme == "https" &&
                !uri.host.isNullOrBlank() &&
                uri.userInfo == null &&
                uri.query == null &&
                uri.fragment == null,
        ) { tr(Res.string.enter_an_https_api_endpoint) }
        require(model.isNotBlank() && apiKey.isNotBlank()) { tr(Res.string.enter_a_model_and_api_key) }
    }

    override fun toString() = "ModelSettings(endpoint=$endpoint, model=$model, apiKey=[redacted])"
}
