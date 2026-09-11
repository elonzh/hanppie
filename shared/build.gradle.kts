plugins {
    alias(libs.plugins.kotlin.multiplatform)
    alias(libs.plugins.compose.multiplatform)
    alias(libs.plugins.compose.compiler)
    alias(libs.plugins.android.kmp)
    alias(libs.plugins.kotlin.serialization)
}

kotlin {
    jvm()
    android {
        namespace = "cn.elonzh.hanppie.ui"
        compileSdk { version = release(37) { minorApiLevel = 0 } }
        buildToolsVersion = "36.1.0"
        minSdk = 26
        androidResources.enable = true
    }
    jvmToolchain(21)
    sourceSets {
        val androidJvmMain by creating { dependsOn(commonMain.get()) }
        androidJvmMain.dependencies {
            implementation(libs.koog.agents)
            implementation(libs.koog.openai)
            implementation(libs.koog.ktor)
            implementation(libs.ktor.okhttp)
            implementation(libs.coil.network)
        }
        named("androidMain") {
            dependsOn(androidJvmMain)
            dependencies {
                implementation(libs.androidx.activity.compose)
                implementation(libs.androidx.lifecycle.viewmodel.compose)
                implementation(libs.androidx.lifecycle.runtime.compose)
            }
        }
        commonMain.dependencies {
            implementation(libs.compose.resources)
            implementation(libs.markdown.core)
            implementation(libs.markdown.coil3)
            implementation(libs.coil.compose)
            implementation(project(":packages:robot-core"))
            implementation(libs.compose.runtime)
            implementation(libs.compose.foundation)
            implementation(libs.miuix.ui)
            implementation(libs.coroutines.core)
            implementation(libs.serialization.json)
        }
        jvmMain { dependsOn(androidJvmMain); dependencies {
            implementation(libs.java.keyring)
            implementation(compose.desktop.currentOs) { exclude(group = "org.jetbrains.compose.material") }
            implementation(libs.coroutines.swing)
        } }
        commonTest.dependencies { implementation(kotlin("test")) }
        jvmTest.dependencies { implementation(libs.compose.test) }
    }
}

compose.resources {
    packageOfResClass = "cn.elonzh.hanppie.resources"
    generateResClass = always
}
