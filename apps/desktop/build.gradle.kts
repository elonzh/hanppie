import org.jetbrains.compose.desktop.application.dsl.TargetFormat

plugins {
    alias(libs.plugins.kotlin.multiplatform)
    alias(libs.plugins.compose.multiplatform)
    alias(libs.plugins.compose.compiler)
    alias(libs.plugins.android.kmp)
    kotlin("plugin.serialization") version "2.4.10"
}

kotlin {
    jvm("desktop")
    android {
        namespace = "cn.elonzh.hanppie.ui"
        compileSdk = 37
        buildToolsVersion = "36.1.0"
        minSdk = 26
    }
    jvmToolchain(21)
    sourceSets {
        val jvmSharedMain by creating { dependsOn(commonMain.get()) }
        jvmSharedMain.dependencies {
            implementation("ai.koog:agents-core:1.2.0")
            implementation("ai.koog:prompt-executor-openai-client:1.2.0")
            implementation("ai.koog:http-client-ktor:1.2.0")
            implementation("io.ktor:ktor-client-okhttp:3.3.3")
        }
        named("androidMain") {
            dependsOn(jvmSharedMain)
            dependencies {
                implementation("androidx.activity:activity-compose:1.12.4")
                implementation("androidx.lifecycle:lifecycle-viewmodel-compose:2.10.0")
                implementation("androidx.lifecycle:lifecycle-runtime-compose:2.10.0")
            }
        }
        commonMain.dependencies {
            implementation(libs.miuix.ui)
            implementation(project(":packages:robot-core"))
            implementation("org.jetbrains.compose.runtime:runtime:1.12.0")
            implementation("org.jetbrains.compose.foundation:foundation:1.12.0")
            implementation("org.jetbrains.compose.material:material:1.12.0")
            implementation(libs.coroutines.core)
        }
        named("desktopMain") { dependsOn(jvmSharedMain); dependencies {
            implementation(compose.desktop.currentOs)
            implementation(libs.coroutines.swing)
        } }
        commonTest.dependencies { implementation(kotlin("test")) }
        named("desktopTest") { dependencies { implementation(compose.desktop.uiTestJUnit4) } }
    }
}

compose.desktop {
    application {
        mainClass = "cn.elonzh.hanppie.desktop.MainKt"
        nativeDistributions {
            targetFormats(TargetFormat.Dmg, TargetFormat.Msi, TargetFormat.Deb)
            packageName = "Hanppie"
            packageVersion = "0.1.0"
            macOS { packageVersion = "1.0.0" }
            description = "RoboMaster S1 控制台"
            vendor = "Hanppie"
        }
    }
}
