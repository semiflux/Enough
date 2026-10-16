plugins {
    alias(libs.plugins.android.application)
    alias(libs.plugins.kotlin.compose)
    alias(libs.plugins.kotlin.serialization)
}

import java.util.Properties

android {
    namespace = "io.github.semiflux.limit"
    compileSdk {
        version = release(37)
    }

    defaultConfig {
        applicationId = "io.github.semiflux.limit"
        minSdk = 31
        targetSdk = 37
        versionCode = 1
        versionName = "1.0"

        testInstrumentationRunner = "androidx.test.runner.AndroidJUnitRunner"
    }

    // Release signing: create a key once with
    //   keytool -genkeypair -keystore enough-release.keystore -alias enough \
    //     -keyalg RSA -keysize 2048 -validity 9125
    // then either set ENOUGH_KEYSTORE_* env vars (CI) or put the same values
    // in local.properties (never committed):
    //   enough.keystore=/absolute/path/enough-release.keystore
    //   enough.keystore.password=...
    //   enough.key.alias=enough
    //   enough.key.password=...
    // Without a key, assembleRelease still runs but stays unsigned.
    val localProps = Properties().apply {
        val f = rootProject.file("local.properties")
        if (f.exists()) f.inputStream().use { stream -> load(stream) }
    }
    fun releaseProp(name: String): String? =
        System.getenv("ENOUGH_" + name.uppercase().replace('.', '_'))
            ?: localProps.getProperty("enough.$name")

    signingConfigs {
        create("release") {
            val keystorePath = releaseProp("keystore")
            if (keystorePath != null && file(keystorePath).exists()) {
                storeFile = file(keystorePath)
                storePassword = releaseProp("keystore.password")
                keyAlias = releaseProp("key.alias")
                keyPassword = releaseProp("key.password")
            }
        }
    }

    buildTypes {
        release {
            val ks = signingConfigs.getByName("release")
            if (ks.storeFile?.exists() == true) signingConfig = ks
            isMinifyEnabled = true
            isShrinkResources = true
            proguardFiles(
                getDefaultProguardFile("proguard-android-optimize.txt"),
                "proguard-rules.pro"
            )
        }
    }
    compileOptions {
        sourceCompatibility = JavaVersion.VERSION_11
        targetCompatibility = JavaVersion.VERSION_11
    }
    buildFeatures {
        compose = true
    }
}

dependencies {
    implementation(platform(libs.androidx.compose.bom))
    implementation(libs.androidx.activity.compose)
    implementation(libs.androidx.compose.material3)
    implementation(libs.androidx.compose.foundation)
    implementation(libs.androidx.compose.material.icons.extended)
    implementation(libs.androidx.compose.ui)
    implementation(libs.androidx.compose.ui.graphics)
    implementation(libs.androidx.compose.ui.tooling.preview)
    implementation(libs.androidx.core.ktx)
    implementation(libs.androidx.lifecycle.runtime.ktx)
    implementation(libs.androidx.lifecycle.runtime.compose)
    implementation(libs.androidx.lifecycle.service)
    implementation(libs.androidx.lifecycle.viewmodel.compose)
    implementation(libs.androidx.navigation.compose)
    implementation(libs.androidx.datastore.preferences)
    implementation(libs.kotlinx.serialization.json)
    testImplementation(libs.junit)
    androidTestImplementation(platform(libs.androidx.compose.bom))
    androidTestImplementation(libs.androidx.compose.ui.test.junit4)
    androidTestImplementation(libs.androidx.espresso.core)
    androidTestImplementation(libs.androidx.junit)
    debugImplementation(libs.androidx.compose.ui.test.manifest)
    debugImplementation(libs.androidx.compose.ui.tooling)
}
