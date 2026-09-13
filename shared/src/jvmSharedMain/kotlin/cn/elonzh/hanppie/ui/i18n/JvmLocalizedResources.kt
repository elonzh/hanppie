package cn.elonzh.hanppie.ui.i18n

import cn.elonzh.hanppie.resources.*
import java.util.Locale
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.runBlocking
import org.jetbrains.compose.resources.ExperimentalResourceApi
import org.jetbrains.compose.resources.StringResource
import org.jetbrains.compose.resources.getString
import org.jetbrains.compose.resources.getSystemResourceEnvironment

/** JVM resource bridge shared by Android and Desktop without retaining a process-wide locale change. */
internal object JvmStringCatalog {
    private val languages = mutableMapOf<String, Map<StringResource, String>>()

    @Synchronized
    fun select(languageTag: String) {
        strings(languageTag)
    }

    @OptIn(ExperimentalResourceApi::class)
    @Synchronized
    fun strings(languageTag: String): Map<StringResource, String> =
        languages.getOrPut(languageTag) {
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

    fun format(resource: StringResource, languageTag: String, args: Array<out Any?>): String =
        String.format(Locale.forLanguageTag(languageTag), strings(languageTag).getValue(resource), *args)
}
