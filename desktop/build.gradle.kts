import java.security.MessageDigest

plugins {
    id("org.jetbrains.kotlin.jvm")
    id("org.jetbrains.kotlin.plugin.compose")
}

/*
 * vpngramm для Windows 11 x64: тот же код, что и в приложении для Android (app/src/main/java),
 * плюс своя реализация платформенного слоя (desktop/src/main/kotlin).
 */

val appVersion = "4.7"

/** Файлы, которые есть только в Android-версии (службы, Android Keystore, USB и NFC и т. п.). */
val androidOnly = listOf(
    "app/hy2setup/MainActivity.kt", "app/hy2setup/App.kt", "app/hy2setup/JobService.kt",
    "app/hy2setup/Notifications.kt", "app/hy2setup/SecretStore.kt",
    "app/hy2setup/ui/PlatformUiAndroid.kt",
    "app/hy2setup/platform/AndroidPlatform.kt", "app/hy2setup/platform/BaseViewModelAndroid.kt", "app/hy2setup/platform/DeviceAndroid.kt",
    "app/hy2setup/platform/HwKeyAndroid.kt",
)

java {
    sourceCompatibility = JavaVersion.VERSION_17
    targetCompatibility = JavaVersion.VERSION_17
}
kotlin {
    compilerOptions { jvmTarget.set(org.jetbrains.kotlin.gradle.dsl.JvmTarget.JVM_17) }
    sourceSets["main"].kotlin.srcDir("../app/src/main/java")
}
sourceSets["main"].kotlin { exclude(androidOnly) }

// общие тесты из app (без Robolectric) — проверяют тот же код на обычной Java, как он работает в Windows
sourceSets["test"].kotlin.srcDir("../app/src/test/java")
sourceSets["test"].kotlin {
    exclude(
        "app/hy2setup/StableListTest.kt", "app/hy2setup/StartupTest.kt",
        "app/hy2setup/ui/ScreenshotTest.kt", "app/hy2setup/AccessTest.kt",
    )
}
tasks.test {
    // снимки окон: build/screens
    systemProperty("screens.dir", layout.buildDirectory.dir("screens").get().asFile.path)
    systemProperty("java.awt.headless", "true")
}

// версия для «О приложении»
val genVersion = tasks.register("genVersion") {
    val out = layout.buildDirectory.dir("generated/version")
    outputs.dir(out)
    inputs.property("v", appVersion)
    doLast { out.get().file("vpngramm-version.txt").asFile.apply { parentFile.mkdirs(); writeText(appVersion) } }
}
sourceSets["main"].resources.srcDir(genVersion)
// «Ключи для всех серверов»: начальный список из локального default-keys.pub (в репозиторий не выкладывается)
val genDefaultKeys = tasks.register("genDefaultKeys") {
    val src = rootProject.file("default-keys.pub")
    val out = layout.buildDirectory.dir("generated/defaultKeys")
    outputs.dir(out)
    inputs.property("keys", providers.provider { if (src.exists()) src.readText() else "" })
    doLast {
        out.get().file("hy2setup/default-keys.txt").asFile.apply { parentFile.mkdirs(); writeText(if (src.exists()) src.readText() else "") }
    }
}
sourceSets["main"].resources.srcDir(genDefaultKeys)
// скрипты для сервера (live_collect.sh, ключ репозитория AmneziaWG) — общие с Android
sourceSets["main"].resources.srcDir("../app/src/main/resources")

/*
 * Compose Multiplatform 1.6.2: последняя версия, которая целиком лежит в Maven Central
 * (начиная с 1.6.10 Compose для ПК берёт lifecycle и collection из Google Maven).
 */
val cmp = "1.6.2"
/** Нативная часть Skia: windows-x64 — для архива, linux-x64 — для тестов и снимков на сборочной машине. */
val skiaTarget = (findProperty("skia") as String?) ?: "windows-x64"

// androidx.annotation и androidx.collection (их просит Compose) — версии, которые уже есть у сборки Android
configurations.all {
    resolutionStrategy.eachDependency {
        if (requested.group == "androidx.annotation" && requested.name.startsWith("annotation")) useVersion("1.8.1")
        if (requested.group == "androidx.collection" && requested.name.startsWith("collection")) useVersion("1.4.4")
    }
}

dependencies {
    implementation("org.jetbrains.compose.desktop:desktop-jvm:$cmp")
    implementation("org.jetbrains.compose.material3:material3-desktop:$cmp")
    implementation("org.jetbrains.skiko:skiko-awt-runtime-$skiaTarget:0.7.97")
    implementation("org.jetbrains.kotlinx:kotlinx-coroutines-swing:1.8.0")
    implementation("com.github.mwiede:jsch:0.2.20")
    implementation("org.bouncycastle:bcprov-jdk18on:1.78.1")
    implementation("com.google.zxing:core:3.5.3")
    implementation("org.json:json:20240303")
    // Windows DPAPI для паролей и ключей
    implementation("net.java.dev.jna:jna-platform:5.14.0")

    testImplementation("junit:junit:4.13.2")
    testImplementation("org.jetbrains.compose.ui:ui-test-junit4-desktop:$cmp")
    testRuntimeOnly("org.jetbrains.skiko:skiko-awt-runtime-linux-x64:0.7.97")
}

// запуск на сборочной машине: ./gradlew :desktop:run -Pskia=linux-x64
tasks.register<JavaExec>("run") {
    group = "application"
    mainClass.set("app.hy2setup.desktop.MainKt")
    classpath = sourceSets["main"].runtimeClasspath
}

// ---------------------------------------------------------------- архив для Windows 11 x64
// ./gradlew :desktop:windowsZip  →  out/vpngramm-<версия>-windows-x64.zip

/** Java для Windows (Eclipse Temurin JRE 21, x64): скачивается один раз и проверяется по SHA-256. */
val jreVersion = "21.0.12+8"
val jreFile = "OpenJDK21U-jre_x64_windows_hotspot_${jreVersion.replace('+', '_')}.zip"
val jreUrl = "https://github.com/adoptium/temurin21-binaries/releases/download/jdk-${jreVersion.replace("+", "%2B")}/$jreFile"
val jreZip = rootProject.layout.projectDirectory.file(".cache/jre/$jreFile")

val downloadJre = tasks.register("downloadJre") {
    outputs.file(jreZip)
    doLast {
        val zip = jreZip.asFile
        if (zip.exists()) return@doLast
        zip.parentFile.mkdirs()
        val expected = uri("$jreUrl.sha256.txt").toURL().readText().trim().substringBefore(' ').lowercase()
        val tmp = File(zip.parentFile, "$jreFile.part")
        uri(jreUrl).toURL().openStream().use { input -> tmp.outputStream().use { input.copyTo(it) } }
        val actual = MessageDigest.getInstance("SHA-256").digest(tmp.readBytes()).joinToString("") { "%02x".format(it) }
        if (actual != expected) {
            tmp.delete()
            throw GradleException("JRE: контрольная сумма не совпала ($actual ≠ $expected)")
        }
        tmp.renameTo(zip)
    }
}

/** vpngramm.exe — лаунчер на Go (tools/windows-launcher). */
val launcherExe = layout.buildDirectory.file("launcher/vpngramm.exe")
val buildLauncher = tasks.register<Exec>("buildLauncher") {
    val src = rootProject.layout.projectDirectory.dir("tools/windows-launcher")
    inputs.dir(src)
    inputs.file(rootProject.layout.projectDirectory.file("desktop/icon/vpngramm.ico"))
    outputs.file(launcherExe)
    commandLine("bash", src.file("build.sh").asFile.path, launcherExe.get().asFile.path)
}

tasks.jar { archiveBaseName.set("vpngramm") }

tasks.register<Zip>("windowsZip") {
    group = "distribution"
    description = "Архив vpngramm для Windows 11 x64 (распаковать и запустить vpngramm.exe)"
    doFirst {
        check(skiaTarget == "windows-x64") { "Архив собирается с нативной Skia для Windows: запускайте без -Pskia" }
    }
    dependsOn(downloadJre, buildLauncher)
    val root = "vpngramm-$appVersion-windows-x64"
    archiveFileName.set("$root.zip")
    destinationDirectory.set(rootProject.layout.projectDirectory.dir("out"))
    into(root) {
        from(launcherExe)
        from(rootProject.layout.projectDirectory.file("desktop/README-windows.txt")) { rename { "README.txt" }; filteringCharset = "UTF-8" }
        from(rootProject.layout.projectDirectory.file("FONT-LICENSE-OFL.txt"))
        into("app/lib") {
            from(tasks.jar)
            from(configurations.runtimeClasspath)
        }
        into("runtime") {
            from({ zipTree(jreZip) }) {
                // в архиве Temurin всё лежит в папке jdk-…-jre — убираем её
                eachFile { relativePath = RelativePath(true, root, "runtime", *sourcePath.split('/').drop(1).toTypedArray()) }
                includeEmptyDirs = false
            }
        }
    }
}
