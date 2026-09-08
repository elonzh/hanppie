package cn.elonzh.hanppie.ui

import androidx.compose.runtime.*

internal enum class ThemePreset { NATIVE, CHAPPIE, OCEAN, FOREST, CUSTOM }
internal enum class NightMode { SYSTEM, LIGHT, DARK;
    fun isDark(systemDark: Boolean): Boolean = when (this) {
        SYSTEM -> systemDark
        LIGHT -> false
        DARK -> true
    }
}

internal data class CustomPalette(
    val lightAccent: String = "#C45F2C", val lightBackground: String = "#F4F6FA",
    val darkAccent: String = "#ED7B43", val darkBackground: String = "#11151D",
) {
    fun isValid() = listOf(lightAccent, lightBackground, darkAccent, darkBackground).all {
        Regex("#[0-9a-fA-F]{6}").matches(it)
    }
}

internal data class AppearanceSettings(
    val preset: ThemePreset = ThemePreset.NATIVE,
    val nightMode: NightMode = NightMode.SYSTEM,
    val custom: CustomPalette = CustomPalette(),
) {
    // Versioned, fixed fields; only enum names and validated RGB hex values are stored.
    fun encode(): String = listOf("1", preset.name, nightMode.name, custom.lightAccent,
        custom.lightBackground, custom.darkAccent, custom.darkBackground).joinToString("|")

    companion object {
        fun decode(saved: String?): AppearanceSettings {
            val fields = saved?.split('|') ?: return AppearanceSettings()
            if (fields.size != 7 || fields[0] != "1") return AppearanceSettings()
            val preset = ThemePreset.entries.find { it.name == fields[1] } ?: return AppearanceSettings()
            val mode = NightMode.entries.find { it.name == fields[2] } ?: return AppearanceSettings()
            val custom = CustomPalette(fields[3], fields[4], fields[5], fields[6])
            return if (custom.isValid()) AppearanceSettings(preset, mode, custom) else AppearanceSettings()
        }
    }
}

internal class AppearanceController(
    initial: AppearanceSettings = AppearanceSettings(),
    private val persist: (String) -> Unit = {},
) {
    var settings by mutableStateOf(initial); private set
    fun update(value: AppearanceSettings) {
        require(value.custom.isValid())
        persist(value.encode())
        settings = value
    }
}

internal val LocalAppearance = staticCompositionLocalOf<AppearanceController> { error("WorkbenchTheme is required") }
