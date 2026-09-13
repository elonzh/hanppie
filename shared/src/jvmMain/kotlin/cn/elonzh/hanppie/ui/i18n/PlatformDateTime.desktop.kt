package cn.elonzh.hanppie.ui.i18n

internal actual fun formatLocalDateTime(epochMillis: Long, style: DateTimeStyle): String =
    formatJvmLocalDateTime(epochMillis, style)
