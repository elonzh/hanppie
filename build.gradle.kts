plugins {
    alias(libs.plugins.android.application) apply false
    alias(libs.plugins.android.kmp) apply false
    alias(libs.plugins.kotlin.multiplatform) apply false
    alias(libs.plugins.kotlin.jvm) apply false
    alias(libs.plugins.compose.multiplatform) apply false
    alias(libs.plugins.compose.compiler) apply false
    alias(libs.plugins.ksp) apply false
    alias(libs.plugins.room3) apply false
}

val releaseVersion = java.util.Properties().apply {
    rootProject.file("version.xcconfig").inputStream().use(::load)
}
allprojects { version = releaseVersion.getProperty("MARKETING_VERSION") }
extra["releaseBuild"] = releaseVersion.getProperty("CURRENT_PROJECT_VERSION").toInt()
