package cn.elonzh.hanppie.ui.i18n

import cn.elonzh.hanppie.resources.Res
import cn.elonzh.hanppie.resources.allStringResources
import kotlinx.coroutines.runBlocking
import org.jetbrains.compose.resources.*
import platform.Foundation.NSUserDefaults
import platform.Foundation.NSArgumentDomain
import platform.Foundation.NSDate
import platform.Foundation.NSDateFormatter
import platform.Foundation.NSLocale
import platform.Foundation.dateWithTimeIntervalSince1970

// Build both supported catalogs once, before model workers start. Kotlin's synchronized lazy
// publishes an immutable lookup; changing app language never changes Foundation defaults again.
private val catalogs by lazy {
    listOf("en-US", "zh-CN").associateWith { languageTag ->
        val defaults = NSUserDefaults.standardUserDefaults
        val previous = defaults.volatileDomainForName(NSArgumentDomain)
        val environment = try {
            // The argument domain also takes precedence over a language supplied by Xcode.
            // Keep this catalog lookup transient; the app choice lives in DataStore.
            defaults.setVolatileDomain(previous + ("AppleLanguages" to listOf(languageTag)), NSArgumentDomain)
            getSystemResourceEnvironment()
        } finally {
            defaults.setVolatileDomain(previous, NSArgumentDomain)
        }
        runBlocking { Res.allStringResources.values.associateWith { getString(environment, it) } }
    }
}

internal actual fun applyAppLocale(languageTag: String) { catalogs.getValue(languageTag) }

internal actual fun localizedResource(resource: StringResource, languageTag: String, args: Array<out Any?>): String {
    val template = catalogs.getValue(languageTag).getValue(resource)
    var nextArgument = 0
    return Regex("%(?:(\\d+)\\$)?[sd]").replace(template) { match ->
        val index = match.groupValues[1].toIntOrNull()?.minus(1) ?: nextArgument++
        args.getOrNull(index)?.toString().orEmpty()
    }
}

internal actual fun formatLocalDateTime(epochMillis: Long, style: DateTimeStyle): String = NSDateFormatter().apply {
    locale = NSLocale(Localization.languageTag)
    dateFormat = when (style) {
        DateTimeStyle.SHORT -> "yyyy-MM-dd HH:mm"
        DateTimeStyle.LOG_TIME -> "HH:mm:ss"
        else -> "yyyy-MM-dd HH:mm:ss"
    }
}.stringFromDate(NSDate.dateWithTimeIntervalSince1970(epochMillis / 1000.0))
