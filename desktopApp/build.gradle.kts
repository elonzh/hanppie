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
            modules("jdk.unsupported")
            targetFormats(TargetFormat.Dmg, TargetFormat.Msi, TargetFormat.Deb)
            packageName = "Hanppie"
            packageVersion = "0.1.0"
            description = "DJI RoboMaster Console"
            vendor = "Hanppie"
            windows { iconFile.set(project.file("src/main/resources/icons/hanppie.ico")) }
            linux { iconFile.set(project.file("src/main/resources/icons/hanppie.png")) }
            macOS {
                packageVersion = "1.0.0"
                bundleID = "cn.elonzh.hanppie.desktop"
                iconFile.set(project.file("src/main/resources/icons/hanppie.icns"))
                infoPlist {
                    extraKeysRawXml = """
                        <key>NSLocalNetworkUsageDescription</key>
                        <string>Hanppie uses the local network to discover and connect to RoboMaster robots.</string>
                    """.trimIndent()
                }
            }
        }
    }
}
