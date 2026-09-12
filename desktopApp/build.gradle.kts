import org.jetbrains.compose.desktop.application.dsl.TargetFormat

plugins {
    alias(libs.plugins.kotlin.jvm)
    alias(libs.plugins.compose.multiplatform)
    alias(libs.plugins.compose.compiler)
}

kotlin { jvmToolchain(21) }

dependencies {
    implementation(project(":shared"))
    implementation(compose.desktop.currentOs) { exclude(group = "org.jetbrains.compose.material") }
    implementation(libs.coroutines.swing)
}

compose.desktop {
    application {
        mainClass = "cn.elonzh.hanppie.desktop.MainKt"
        if (System.getProperty("os.name").startsWith("Mac")) {
            jvmArgs("-Xdock:name=Hanppie")
        }
        nativeDistributions {
            targetFormats(TargetFormat.Dmg, TargetFormat.Msi, TargetFormat.Deb)
            packageName = "Hanppie"
            packageVersion = "0.1.0"
            description = "RoboMaster S1 控制台"
            vendor = "Hanppie"
            windows { iconFile.set(project.file("src/main/resources/icons/hanppie.ico")) }
            linux { iconFile.set(project.file("src/main/resources/icons/hanppie.png")) }
            macOS {
                packageVersion = "1.0.0"
                iconFile.set(project.file("src/main/resources/icons/hanppie.icns"))
            }
        }
    }
}
