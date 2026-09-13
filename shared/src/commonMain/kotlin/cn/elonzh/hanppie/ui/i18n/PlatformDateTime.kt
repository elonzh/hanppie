package cn.elonzh.hanppie.ui.i18n

internal enum class DateTimeStyle { SHORT, SCRIPT_UPDATED, LOG_TIME }

internal expect fun formatLocalDateTime(epochMillis: Long, style: DateTimeStyle): String
