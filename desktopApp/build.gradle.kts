import org.jetbrains.compose.desktop.application.dsl.TargetFormat
import org.jetbrains.compose.desktop.application.tasks.AbstractJPackageTask

plugins {
    alias(libs.plugins.kotlin.jvm)
    alias(libs.plugins.compose.multiplatform)
    alias(libs.plugins.compose.compiler)
}

kotlin {
    jvmToolchain(25)
    compilerOptions { jvmTarget = org.jetbrains.kotlin.gradle.dsl.JvmTarget.JVM_25 }
}

// Select only the host's native renderer when building a desktop distribution.
configurations.matching { it.isCanBeResolved }.configureEach {
    attributes {
        val os = when {
            System.getProperty("os.name").startsWith("Mac") -> "macos"
            System.getProperty("os.name").startsWith("Windows") -> "windows"
            else -> "linux"
        }
        val arch = if (System.getProperty("os.arch") in listOf("aarch64", "arm64")) "arm64" else "x86-64"
        attribute(org.gradle.nativeplatform.OperatingSystemFamily.OPERATING_SYSTEM_ATTRIBUTE, objects.named(os))
        attribute(org.gradle.nativeplatform.MachineArchitecture.ARCHITECTURE_ATTRIBUTE, objects.named(arch))
    }
}

dependencies {
    implementation(project(":shared"))
    implementation(compose.desktop.currentOs) { exclude(group = "org.jetbrains.compose.material") }
    implementation(libs.coroutines.swing)
}

compose.desktop {
    application {
        mainClass = "cn.elonzh.hanppie.desktop.MainKt"
        jvmArgs("--enable-native-access=ALL-UNNAMED")
        if (System.getProperty("os.name").startsWith("Mac")) {
            jvmArgs("-Xdock:name=Hanppie")
        }
        nativeDistributions {
            appResourcesRootDir.set(rootProject.layout.buildDirectory.dir("desktop-resources"))
            modules("jdk.unsupported")
            targetFormats(TargetFormat.Dmg, TargetFormat.Msi, TargetFormat.Deb)
            packageName = "Hanppie"
            packageVersion = project.version.toString()
            description = "DJI RoboMaster Console"
            vendor = "Hanppie"
            windows { iconFile.set(project.file("src/main/resources/icons/hanppie.ico")) }
            linux { iconFile.set(project.file("src/main/resources/icons/hanppie.png")) }
            macOS {
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

tasks.matching { it.name == "prepareAppResources" }.configureEach {
    dependsOn(rootProject.tasks.named("prepareDesktopFfmpeg"))
}

// IDE-injected hotRun tasks also extend JavaExec, but do not inherit Compose's run JVM arguments.
tasks.withType<JavaExec>().configureEach {
    dependsOn(rootProject.tasks.named("prepareDesktopFfmpeg"))
    systemProperty(
        "compose.application.resources.dir",
        rootProject.layout.buildDirectory.dir("desktop-resources/common").get().asFile.absolutePath
    )
}

// Compose copies app resources without their Unix executable bits. Fix the application image,
// then build every installer from that verified image (including Linux and Windows).
tasks.withType<AbstractJPackageTask>().configureEach {
    if (targetFormat == TargetFormat.AppImage) {
        doLast {
            val executableName = if (System.getProperty("os.name").startsWith("Windows")) "ffmpeg.exe" else "ffmpeg"
            val executable = destinationDir.get().asFile.walkTopDown().single {
                it.isFile && it.name == executableName && it.parentFile.name == "ffmpeg"
            }
            check(executable.setExecutable(true, false)) { "Cannot make bundled FFmpeg executable: $executable" }
            val builder = ProcessBuilder(executable.absolutePath, "-version")
                .redirectOutput(ProcessBuilder.Redirect.DISCARD).redirectError(ProcessBuilder.Redirect.DISCARD)
            builder.environment()["PATH"] = ""
            val process = builder.start()
            try {
                check(process.waitFor(10, TimeUnit.SECONDS) && process.exitValue() == 0) {
                    "Packaged FFmpeg cannot run without system dependencies: $executable"
                }
            } finally {
                if (process.isAlive) process.destroyForcibly().waitFor()
            }
        }
    } else {
        val imageTaskName = if (name.startsWith("packageRelease")) "createReleaseDistributable" else "createDistributable"
        val imageTask = tasks.named<AbstractJPackageTask>(imageTaskName)
        dependsOn(imageTask)
        appImage.set(imageTask.flatMap { it.destinationDir }.map {
            if (System.getProperty("os.name").startsWith("Mac")) it else it.dir("Hanppie")
        })
    }
}
