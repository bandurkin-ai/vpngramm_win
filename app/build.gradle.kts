plugins {
    id("com.android.application")
    id("org.jetbrains.kotlin.android")
    id("org.jetbrains.kotlin.plugin.compose")
}

android {
    namespace = "app.hy2setup"
    compileSdk = 35

    defaultConfig {
        applicationId = "app.hy2setup"
        minSdk = 26
        targetSdk = 35
        versionCode = 65
        versionName = "4.7.5"
    }

    buildTypes {
        release {
            isMinifyEnabled = false
        }
    }
    compileOptions {
        sourceCompatibility = JavaVersion.VERSION_17
        targetCompatibility = JavaVersion.VERSION_17
    }
    kotlinOptions {
        jvmTarget = "17"
    }
    buildFeatures {
        compose = true
    }
    testOptions {
        unitTests.isIncludeAndroidResources = true
        unitTests.all {
            // Robolectric сам скачивает android-all; берём его с того же зеркала Maven Central, что и Gradle
            it.systemProperty("robolectric.dependency.repo.url", "https://maven-central.storage-download.googleapis.com/maven2/")
            it.systemProperty("roborazzi.test.record", "true")
        }
    }
    packaging {
        resources.excludes += setOf("/META-INF/{AL2.0,LGPL2.1}", "META-INF/versions/**")
        // движок vpncore — исполняемый файл в виде .so: его нужно распаковать на телефоне, чтобы запускать
        jniLibs.useLegacyPackaging = true
    }
}

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
android.sourceSets["main"].resources.srcDir(layout.buildDirectory.dir("generated/defaultKeys"))
tasks.named("preBuild") { dependsOn(genDefaultKeys) }

dependencies {
    val composeBom = platform("androidx.compose:compose-bom:2024.10.01")
    implementation(composeBom)
    implementation("androidx.compose.material3:material3")
    implementation("androidx.compose.ui:ui")
    implementation("androidx.activity:activity-compose:1.9.3")
    implementation("androidx.lifecycle:lifecycle-viewmodel-compose:2.8.7")
    implementation("androidx.lifecycle:lifecycle-runtime-compose:2.8.7")
    implementation("org.jetbrains.kotlinx:kotlinx-coroutines-android:1.9.0")
    // фоновый контроль серверов
    implementation("androidx.work:work-runtime-ktx:2.9.1")
    implementation("androidx.core:core-ktx:1.13.1")

    // SSH-клиент (форк JSch с современными алгоритмами)
    implementation("com.github.mwiede:jsch:0.2.20")
    // Ed25519 / X25519 / Argon2 для JSch на Android (во встроенной криптографии Android их нет)
    implementation("org.bouncycastle:bcprov-jdk18on:1.78.1")
    // QR-код
    implementation("com.google.zxing:core:3.5.3")

    testImplementation("junit:junit:4.13.2")
    testImplementation("org.json:json:20240303")
    // скриншоты интерфейса без эмулятора (Robolectric + Roborazzi)
    testImplementation("org.robolectric:robolectric:4.13")
    testImplementation("io.github.takahirom.roborazzi:roborazzi:1.32.2")
    testImplementation("io.github.takahirom.roborazzi:roborazzi-compose:1.32.2")
    testImplementation("androidx.compose.ui:ui-test-junit4")
    debugImplementation("androidx.compose.ui:ui-test-manifest")
}
