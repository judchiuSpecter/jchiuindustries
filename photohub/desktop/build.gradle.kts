import org.jetbrains.compose.desktop.application.dsl.TargetFormat
import java.io.File

plugins {
    alias(libs.plugins.kotlinMultiplatform)
    alias(libs.plugins.composeMultiplatform)
    alias(libs.plugins.kotlinComposeCompiler)
}

kotlin {
    jvmToolchain(21)
    jvm()

    sourceSets {
        jvmMain.dependencies {
            implementation(project(":shared"))
            implementation(compose.desktop.currentOs)
            implementation(compose.runtime)
            implementation(compose.foundation)
            implementation(compose.material3)
            implementation(libs.koin.core)
        }
    }
}

compose.desktop {
    application {
        mainClass = "dev.photohub.desktop.MainKt"

        nativeDistributions {
            targetFormats(TargetFormat.Dmg, TargetFormat.Msi, TargetFormat.Deb)
            packageName = "PhotoHub"
            packageVersion = "1.0.0"
            description = "Offline-first photo timeline across local and cloud sources"
            vendor = "PhotoHub"

            macOS {
                iconFile.set(File(project.projectDir, "icons/icon.icns"))
            }
            windows {
                iconFile.set(File(project.projectDir, "icons/icon.ico"))
            }
            linux {
                iconFile.set(File(project.projectDir, "icons/icon.png"))
            }
        }
    }
}
