import java.util.Properties

plugins {
    alias(libs.plugins.android.application)
}

// Номер версии = число коммитов в git: растёт сам с каждым изменением, и приложение по нему
// понимает, что на сервере версия новее (обновление со своего сервера, см. UpdateActivity).
val gitCommitCount: Int = try {
    providers.exec { commandLine("git", "rev-list", "--count", "HEAD") }
        .standardOutput.asText.get().trim().toInt()
} catch (e: Exception) {
    1
}

// Общий ключ подписи проекта: обновление ставится поверх старой версии, только если обе
// подписаны одним ключом. Ключ — НЕ в git: keystore.properties рядом с проектом
// (создаёт scripts/new-signing-key.ps1) или переменные окружения RC_KEYSTORE_* (CI).
val signing = Properties().apply {
    val file = rootProject.file("keystore.properties")
    if (file.exists()) file.inputStream().use { load(it) }
}
fun signingValue(key: String, env: String): String? =
    signing.getProperty(key) ?: System.getenv(env)?.takeIf { it.isNotBlank() }
val releaseStoreFile = signingValue("storeFile", "RC_KEYSTORE_FILE")?.let { rootProject.file(it) }

android {
    namespace = "com.example.roomclimate"
    compileSdk {
        version = release(37)
    }

    defaultConfig {
        applicationId = "com.example.roomclimate"
        minSdk = 24
        targetSdk = 37
        versionCode = gitCommitCount
        versionName = "1.$gitCommitCount"

        testInstrumentationRunner = "androidx.test.runner.AndroidJUnitRunner"
    }

    signingConfigs {
        if (releaseStoreFile != null && releaseStoreFile.exists()) {
            create("release") {
                storeFile = releaseStoreFile
                storePassword = signingValue("storePassword", "RC_KEYSTORE_PASSWORD")
                keyAlias = signingValue("keyAlias", "RC_KEY_ALIAS")
                keyPassword = signingValue("keyPassword", "RC_KEY_PASSWORD")
            }
        }
    }

    buildTypes {
        release {
            signingConfigs.findByName("release")?.let { signingConfig = it }
            // R8 выключен: с ним (packageScope kotlin.**) OkHttp падал при первом же запросе —
            // IllegalAccessError в okhttp3.internal.Util. Размер APK для учебного проекта не важен.
            optimization {
                enable = false
            }
        }
    }
    compileOptions {
        sourceCompatibility = JavaVersion.VERSION_11
        targetCompatibility = JavaVersion.VERSION_11
    }
}

dependencies {
    implementation(libs.activity.ktx)
    implementation(libs.appcompat)
    implementation(libs.constraintlayout)
    implementation(libs.material)
    implementation(libs.retrofit)
    implementation(libs.retrofit.gson)
    implementation(libs.okhttp.logging)
    implementation(libs.gson)
    testImplementation(libs.junit)
    testImplementation(libs.gson)
    androidTestImplementation(libs.espresso.core)
    androidTestImplementation(libs.ext.junit)
}