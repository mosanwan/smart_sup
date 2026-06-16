import java.util.Properties

plugins {
    alias(libs.plugins.android.application)
    alias(libs.plugins.kotlin.android)
    alias(libs.plugins.kotlin.compose)
}

val localProperties = Properties().apply {
    val localPropertiesFile = rootProject.file("local.properties")
    if (localPropertiesFile.isFile) {
        localPropertiesFile.inputStream().use(::load)
    }
}

fun String.asBuildConfigString(): String =
    "\"" + replace("\\", "\\\\").replace("\"", "\\\"") + "\""

android {
    namespace = "com.smartsup.controller"
    compileSdk = 36

    val releaseStoreFile = System.getenv("SMART_SUP_ANDROID_KEYSTORE")
    val releaseStorePassword = System.getenv("SMART_SUP_ANDROID_KEYSTORE_PASSWORD")
    val releaseKeyAlias = System.getenv("SMART_SUP_ANDROID_KEY_ALIAS")
    val releaseKeyPassword = System.getenv("SMART_SUP_ANDROID_KEY_PASSWORD")
    val hasReleaseSigning = listOf(
        releaseStoreFile,
        releaseStorePassword,
        releaseKeyAlias,
        releaseKeyPassword,
    ).all { !it.isNullOrBlank() }

    defaultConfig {
        applicationId = "com.smartsup.controller"
        minSdk = 26
        targetSdk = 36
        versionCode = 8
        versionName = "0.2.6"
        buildConfigField("String", "GITHUB_REPOSITORY", "\"mosanwan/smart_sup\"")
        val maptilerApiKey = providers.gradleProperty("MAPTILER_API_KEY")
            .orElse(providers.environmentVariable("MAPTILER_API_KEY"))
            .orElse("")
            .get()
            .ifBlank { localProperties.getProperty("MAPTILER_API_KEY", "") }
        buildConfigField("String", "MAPTILER_API_KEY", maptilerApiKey.asBuildConfigString())
        buildConfigField("boolean", "MAPTILER_API_KEY_CONFIGURED", maptilerApiKey.isNotBlank().toString())

        ndk {
            abiFilters += listOf("arm64-v8a")
        }
    }

    signingConfigs {
        if (hasReleaseSigning) {
            create("release") {
                storeFile = file(releaseStoreFile!!)
                storePassword = releaseStorePassword
                keyAlias = releaseKeyAlias
                keyPassword = releaseKeyPassword
            }
        }
    }

    buildTypes {
        release {
            if (hasReleaseSigning) {
                signingConfig = signingConfigs.getByName("release")
            }
            isMinifyEnabled = false
            proguardFiles(
                getDefaultProguardFile("proguard-android-optimize.txt"),
                "proguard-rules.pro",
            )
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
        buildConfig = true
        compose = true
    }
}

dependencies {
    implementation(libs.androidx.activity.compose)
    implementation(platform(libs.androidx.compose.bom))
    implementation(libs.androidx.compose.material.icons.extended)
    implementation(libs.androidx.compose.material3)
    implementation(libs.androidx.compose.ui)
    implementation(libs.androidx.compose.ui.graphics)
    implementation(libs.androidx.compose.ui.tooling.preview)
    implementation(libs.androidx.core.ktx)
    implementation(libs.androidx.lifecycle.runtime.ktx)
    implementation(libs.androidx.lifecycle.viewmodel.compose)
    implementation(libs.maplibre.android)
    implementation(files("libs/sherpa-onnx-1.13.2.aar"))

    debugImplementation(libs.androidx.compose.ui.tooling)
}
