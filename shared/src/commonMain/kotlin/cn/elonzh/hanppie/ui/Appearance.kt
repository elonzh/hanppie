package cn.elonzh.hanppie.ui

import androidx.compose.runtime.*

internal enum class NightMode { SYSTEM, LIGHT, DARK;
    fun isDark(systemDark: Boolean): Boolean = when (this) {
        SYSTEM -> systemDark
        LIGHT -> false
        DARK -> true
    }
}

internal data class AppearanceSettings(
    val nightMode: NightMode = NightMode.SYSTEM,
) {
    fun encode(): String = "2|${nightMode.name}"

    companion object {
        fun decode(saved: String?): AppearanceSettings {
            val fields = saved?.split('|') ?: return AppearanceSettings()
            val modeName = when {
                fields.size == 2 && fields[0] == "2" -> fields[1]
                fields.size == 7 && fields[0] == "1" -> fields[2]
                else -> return AppearanceSettings()
            }
            return AppearanceSettings(NightMode.entries.find { it.name == modeName } ?: NightMode.SYSTEM)
        }
    }
}

internal class AppearanceController(
    initial: AppearanceSettings = AppearanceSettings(),
    private val persist: (String) -> Unit = {},
) {
    var settings by mutableStateOf(initial); private set
    fun update(value: AppearanceSettings) {
        persist(value.encode())
        settings = value
    }
}

internal val LocalAppearance = staticCompositionLocalOf<AppearanceController> { error("WorkbenchTheme is required") }
