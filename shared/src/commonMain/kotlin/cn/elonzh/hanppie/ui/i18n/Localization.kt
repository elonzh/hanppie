package cn.elonzh.hanppie.ui.i18n

import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.setValue
import androidx.compose.ui.text.intl.Locale
import org.jetbrains.compose.resources.StringResource

/** App language preference; translated content lives only in Compose XML resources. */
internal object Localization {
    var choice by mutableStateOf("system"); private set
    var systemLanguageTag by mutableStateOf("zh-CN"); private set
    private var persist: (String) -> Unit = {}
    val english get() = (if (choice == "system") resolveSupportedLanguage(systemLanguageTag) else choice) != "zh"
    val languageTag get() = if (english) "en-US" else "zh-CN"

    fun initialize(systemLanguage: String, saved: String?, save: (String) -> Unit = {}) {
        systemLanguageTag = systemLanguage
        resolveSupportedLanguage(systemLanguage)
        choice = saved ?: "system"
        require(choice in listOf("system", "zh", "en")) { "Unsupported language preference: $choice" }
        persist = save
        applyAppLocale(languageTag)
    }

    private fun resolveSupportedLanguage(languageTag: String): String {
        require(languageTag.isNotBlank()) { "System language tag must not be blank" }
        return if (Locale(languageTag).language.equals("zh", ignoreCase = true)) "zh" else "en"
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
