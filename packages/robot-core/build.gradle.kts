plugins {
    alias(libs.plugins.kotlin.multiplatform)
    alias(libs.plugins.android.kmp)
}

kotlin {
    jvm("desktop")
    android {
        namespace = "cn.elonzh.hanppie.robot"
        compileSdk = 37
        buildToolsVersion = "36.1.0"
        minSdk = 26
    }
    jvmToolchain(21)
    sourceSets {
        val jvmSharedMain by creating {
            dependsOn(commonMain.get())
            dependencies { implementation(libs.commons.net) }
        }
        named("desktopMain") { dependsOn(jvmSharedMain) }
        named("androidMain") { dependsOn(jvmSharedMain) }
        commonMain.dependencies {
            implementation(libs.coroutines.core)
        }
        commonTest.dependencies {
            implementation(kotlin("test"))
        }
    }
}
