package cn.elonzh.hanppie.ui.settings

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
    fun encode(): String = nightMode.name

    companion object {
        fun decode(saved: String?): AppearanceSettings =
            if (saved == null) AppearanceSettings() else AppearanceSettings(NightMode.valueOf(saved))
    }
}

internal class AppearanceController(
    initial: AppearanceSettings = AppearanceSettings(),
    private val persist: (String) -> Unit = {},
) {
    var settings by mutableStateOf(initial); private set
    fun load(value: AppearanceSettings) {
        settings = value
    }
    fun update(value: AppearanceSettings) {
        persist(value.encode())
        settings = value
    }
}

internal val LocalAppearance = staticCompositionLocalOf<AppearanceController> { error("WorkbenchTheme is required") }
