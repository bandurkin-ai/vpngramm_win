import org.jetbrains.compose.desktop.application.dsl.TargetFormat

plugins {
    kotlin("jvm") version "2.0.21"
    id("org.jetbrains.kotlin.plugin.compose") version "2.0.21"
    id("org.jetbrains.compose") version "1.7.1"
}

group = "app.hy2setup"
version = "2.0.1"

dependencies {
    implementation(compose.desktop.currentOs)
    implementation(compose.material3)
    implementation("org.jetbrains.kotlinx:kotlinx-coroutines-swing:1.9.0")

    // SSH-клиент (форк JSch с современными алгоритмами)
    implementation("com.github.mwiede:jsch:0.2.20")
    // Ed25519 / X25519 / Argon2 для JSch
    implementation("org.bouncycastle:bcprov-jdk18on:1.78.1")
    // QR-код
    implementation("com.google.zxing:core:3.5.3")
    // JSON (на Android он встроен в систему)
    implementation("org.json:json:20240303")
    // DPAPI: шифрование паролей и ключей средствами Windows
    implementation("net.java.dev.jna:jna-platform:5.14.0")

    testImplementation("junit:junit:4.13.2")
}

compose.desktop {
    application {
        mainClass = "app.hy2setup.MainKt"
        nativeDistributions {
            targetFormats(TargetFormat.Msi, TargetFormat.Exe)
            packageName = "vpngramm"
            packageVersion = "2.0.1"
            description = "vpngramm - own VPN server setup and management"
            vendor = "vpngramm"
            modules("java.naming", "jdk.crypto.ec", "java.sql", "jdk.unsupported")
            windows {
                menuGroup = "vpngramm"
                shortcut = true
                dirChooser = true
                perUserInstall = true
                upgradeUuid = "b6a4d1d0-5a0e-4f3a-9b57-6f1a7a3d2c11"
                iconFile.set(project.file("packaging/vpngramm.ico"))
            }
        }
    }
}
