plugins {
    alias(libs.plugins.kotlin.multiplatform)
    alias(libs.plugins.compose.multiplatform)
    alias(libs.plugins.compose.compiler)
    alias(libs.plugins.android.kmp)
    alias(libs.plugins.kotlin.serialization)
    alias(libs.plugins.ksp)
    alias(libs.plugins.room3)
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
                implementation(libs.androidx.core)
            }
        }
        commonMain.dependencies {
            implementation(libs.androidx.datastore.preferences.core)
            implementation(libs.androidx.lifecycle.runtime.compose)
            implementation(libs.androidx.lifecycle.viewmodel)
            implementation(libs.androidx.lifecycle.viewmodel.compose)
            implementation(libs.androidx.room3.runtime)
            implementation(libs.androidx.sqlite.bundled)
            implementation(libs.compose.resources)
            implementation(libs.markdown.core)
            implementation(libs.markdown.coil3)
            implementation(libs.coil.compose)
            implementation(project(":packages:robot-core"))
            implementation(libs.compose.runtime)
            implementation(libs.compose.foundation)
            implementation(libs.miuix.ui)
            implementation(libs.navigation3.ui)
            implementation(libs.compose.icons.lucide)
            implementation(libs.coroutines.core)
            implementation(libs.serialization.json)
            implementation(libs.filekit.core)
            implementation(libs.filekit.dialogs)
            implementation(libs.kotlin.logging)
        }
        jvmMain { dependsOn(androidJvmMain); dependencies {
            implementation(compose.desktop.currentOs) { exclude(group = "org.jetbrains.compose.material") }
            implementation(libs.coroutines.swing)
            runtimeOnly(libs.slf4j.simple)
        } }
        commonTest.dependencies { implementation(kotlin("test")) }
        jvmTest.dependencies { implementation(libs.compose.test) }
    }
}

dependencies {
    add("kspAndroid", libs.androidx.room3.compiler)
    add("kspJvm", libs.androidx.room3.compiler)
}

room3 {
    schemaDirectory("$projectDir/schemas")
}

compose.resources {
    packageOfResClass = "cn.elonzh.hanppie.resources"
    generateResClass = always
}
