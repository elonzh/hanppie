package cn.elonzh.hanppie.ui

import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.setValue
import org.jetbrains.compose.resources.StringResource

/** App language preference; translated content lives only in Compose XML resources. */
internal object Localization {
    var choice by mutableStateOf("system"); private set
    var systemLanguage by mutableStateOf("zh"); private set
    private var persist: (String) -> Unit = {}
    val english get() = (if (choice == "system") systemLanguage else choice) != "zh"
    val languageTag get() = if (english) "en-US" else "zh-CN"

    fun initialize(systemLanguage: String, saved: String?, save: (String) -> Unit = {}) {
        this.systemLanguage = if (systemLanguage.startsWith("zh")) "zh" else "en"
        choice = saved?.takeIf { it in listOf("system", "zh", "en") } ?: "system"
        persist = save
        applyAppLocale(languageTag)
    }

    fun select(value: String) {
        require(value in listOf("system", "zh", "en"))
        persist(value)
        choice = value
        applyAppLocale(languageTag)
    }
}

internal fun tr(resource: StringResource, vararg args: Any?): String =
    localizedResource(resource, Localization.languageTag, args)

/** Keep live state independent of the language selected when the event arrived. */
internal data class UiText(val resource: StringResource, val arguments: List<Any?>) {
    fun resolve(): String = tr(resource, *arguments.toTypedArray())
}

internal fun uiText(resource: StringResource, vararg args: Any?): UiText = UiText(resource, args.toList())

internal expect fun applyAppLocale(languageTag: String)
internal expect fun localizedResource(resource: StringResource, languageTag: String, args: Array<out Any?>): String
