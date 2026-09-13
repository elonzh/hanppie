package cn.elonzh.hanppie.ui.i18n

import org.jetbrains.compose.resources.StringResource

internal actual fun applyAppLocale(languageTag: String) {
    JvmStringCatalog.select(languageTag)
}

internal actual fun localizedResource(
    resource: StringResource,
    languageTag: String,
    args: Array<out Any?>,
): String = JvmStringCatalog.format(resource, languageTag, args)
