plugins {
    alias(libs.plugins.kotlin.multiplatform)
    alias(libs.plugins.android.kmp)
}

kotlin {
    jvm("desktop")
    listOf(iosArm64(), iosSimulatorArm64()).forEach { target ->
        target.compilations.getByName("main").cinterops.create("robotPlatform") {
            definitionFile.set(project.file("src/nativeInterop/cinterop/robotPlatform.def"))
        }
    }
    android {
        namespace = "cn.elonzh.hanppie.robot"
        compileSdk { version = release(37) { minorApiLevel = 0 } }
        buildToolsVersion = "36.1.0"
        minSdk = 26
    }
    jvmToolchain(21)
    applyDefaultHierarchyTemplate()
    sourceSets {
        val jvmSharedMain by creating {
            dependsOn(commonMain.get())
            dependencies { implementation(libs.commons.net) }
        }
        named("desktopMain") { dependsOn(jvmSharedMain) }
        named("androidMain") { dependsOn(jvmSharedMain) }
        commonMain.dependencies {
            implementation(libs.coroutines.core)
            implementation(libs.kotlinx.io.core)
        }
        commonTest.dependencies {
            implementation(kotlin("test"))
        }
    }
}
