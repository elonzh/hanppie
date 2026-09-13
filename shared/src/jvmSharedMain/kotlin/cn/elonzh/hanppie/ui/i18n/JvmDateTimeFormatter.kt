package cn.elonzh.hanppie.ui.i18n

import java.time.Instant
import java.time.ZoneId
import java.time.format.DateTimeFormatter
import java.time.format.FormatStyle
import java.util.Locale

internal fun formatJvmLocalDateTime(epochMillis: Long, style: DateTimeStyle): String {
    val locale = Locale.forLanguageTag(Localization.languageTag)
    val formatter = when (style) {
        DateTimeStyle.SHORT -> DateTimeFormatter.ofLocalizedDateTime(FormatStyle.SHORT).withLocale(locale)
        DateTimeStyle.SCRIPT_UPDATED -> DateTimeFormatter.ofPattern(
            if (Localization.english) "MMM d, yyyy HH:mm" else "yyyy-MM-dd HH:mm",
            locale,
        )
        DateTimeStyle.LOG_TIME -> DateTimeFormatter.ofPattern("HH:mm:ss.SSS", locale)
    }
    return formatter.format(Instant.ofEpochMilli(epochMillis).atZone(ZoneId.systemDefault()))
}
