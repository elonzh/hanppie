package cn.elonzh.hanppie.ui.i18n

import cn.elonzh.hanppie.resources.*
import java.util.Locale
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.runBlocking
import org.jetbrains.compose.resources.*

internal actual fun applyAppLocale(languageTag: String) {
    StringCatalog.select(languageTag)
}

/**
 * Synchronous platform callbacks cannot call suspend getString. Load each language
 * once through Compose Resources; subsequent rendering/callbacks only format memory.
 * No source-text keys, reverse translation, or unknown-key fallback.
 */
private object StringCatalog {
    private val languages = mutableMapOf<String, Map<StringResource, String>>()

    @Synchronized
    fun select(languageTag: String) {
        strings(languageTag)
        Locale.setDefault(Locale.forLanguageTag(languageTag))
    }

    @OptIn(ExperimentalResourceApi::class)
    @Synchronized
    fun strings(languageTag: String): Map<StringResource, String> =
        languages.getOrPut(languageTag) {
            // Capture qualifiers without letting a late callback change the selected locale.
            val previous = Locale.getDefault()
            val environment = try {
                Locale.setDefault(Locale.forLanguageTag(languageTag))
                getSystemResourceEnvironment()
            } finally {
                Locale.setDefault(previous)
            }
            runBlocking(Dispatchers.IO) {
                Res.allStringResources.values.associateWith { getString(environment, it) }
            }
        }
}

internal actual fun localizedResource(resource: StringResource, languageTag: String, args: Array<out Any?>): String =
    String.format(Locale.forLanguageTag(languageTag), StringCatalog.strings(languageTag).getValue(resource), *args)
