package cn.elonzh.hanppie.ui.i18n

import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.setValue
import androidx.compose.ui.text.intl.Locale
import org.jetbrains.compose.resources.StringResource

/** One catalog list for selection, preference validation and native resource loading. */
internal enum class AppLanguage(val preference: String, val languageTag: String, val nativeName: String) {
    SIMPLIFIED_CHINESE("zh", "zh-CN", "简体中文"),
    TRADITIONAL_CHINESE("zh-Hant", "zh-Hant-TW", "繁體中文"),
    ENGLISH("en", "en-US", "English"),
    JAPANESE("ja", "ja-JP", "日本語"),
    GERMAN("de", "de-DE", "Deutsch"),
    SPANISH("es", "es-ES", "Español"),
    FRENCH("fr", "fr-FR", "Français"),
    KOREAN("ko", "ko-KR", "한국어"),
    RUSSIAN("ru", "ru-RU", "Русский");

    companion object {
        fun isPreference(value: String) = value == "system" || entries.any { it.preference == value }

        fun forSystem(languageTag: String): AppLanguage {
            require(languageTag.isNotBlank()) { "System language tag must not be blank" }
            val parts = languageTag.replace('_', '-').lowercase().split('-')
            val locale = Locale(languageTag.replace('_', '-'))
            val language = locale.language.lowercase()
            if (language != "zh") return entries.firstOrNull { it.preference == language } ?: ENGLISH
            return when {
                parts.getOrNull(1) == "hant" -> TRADITIONAL_CHINESE
                parts.getOrNull(1) == "hans" -> SIMPLIFIED_CHINESE
                locale.region.lowercase() in listOf("tw", "hk", "mo") -> TRADITIONAL_CHINESE
                else -> SIMPLIFIED_CHINESE
            }
        }
    }
}

/** App language preference; translated content lives only in Compose XML resources. */
internal object Localization {
    var choice by mutableStateOf("system"); private set
    var systemLanguageTag by mutableStateOf("zh-CN"); private set
    private var persist: (String) -> Unit = {}
    private val language get() = if (choice == "system") AppLanguage.forSystem(systemLanguageTag)
        else AppLanguage.entries.first { it.preference == choice }
    val english get() = language == AppLanguage.ENGLISH
    val languageTag get() = language.languageTag

    fun initialize(systemLanguage: String, saved: String?, save: (String) -> Unit = {}) {
        AppLanguage.forSystem(systemLanguage)
        val selected = saved ?: "system"
        require(AppLanguage.isPreference(selected)) { "Unsupported language preference: $selected" }
        systemLanguageTag = systemLanguage
        choice = selected
        persist = save
        applyAppLocale(languageTag)
    }

    fun select(value: String) {
        require(AppLanguage.isPreference(value))
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
