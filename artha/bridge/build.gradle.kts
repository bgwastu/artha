plugins {
    alias(libs.plugins.android.application)
    alias(libs.plugins.kotlin.android)
    alias(libs.plugins.kotlin.compose)
}

android {
    namespace = "net.wastu.artha.bridge"
    compileSdk = 36
    buildToolsVersion = "36.0.0"

    signingConfigs {
        val keystorePath = providers.environmentVariable("ARTHA_ANDROID_KEYSTORE").orNull
            ?: (System.getProperty("user.home") + "/.android/release.keystore")
        val keystoreFile = file(keystorePath)
        val pass = providers.environmentVariable("ARTHA_ANDROID_KEYSTORE_PASSWORD").orNull
            ?: providers.gradleProperty("arthaStorePassword").orNull
        val alias = providers.environmentVariable("ARTHA_ANDROID_KEY_ALIAS").orNull
            ?: providers.gradleProperty("arthaKeyAlias").orNull
        if (keystoreFile.exists() && pass != null && alias != null) {
            create("release") {
                storeFile = keystoreFile
                storePassword = pass
                keyAlias = alias
                keyPassword = pass
            }
        }
    }

    defaultConfig {
        applicationId = "net.wastu.artha.bridge"
        minSdk = 26
        targetSdk = 36
        versionCode = providers.gradleProperty("arthaVersionCode").orElse("1").get().toInt()
        versionName = providers.gradleProperty("arthaVersionName").orElse("0.1.0").get()
        testInstrumentationRunner = "androidx.test.runner.AndroidJUnitRunner"
        ndk { abiFilters += "arm64-v8a" }
    }

    buildTypes {
        release {
            isMinifyEnabled = false
            signingConfig = signingConfigs.findByName("release")
            proguardFiles(getDefaultProguardFile("proguard-android-optimize.txt"), "proguard-rules.pro")
        }
        debug {
            applicationIdSuffix = ""
            signingConfigs.findByName("release")?.let { signingConfig = it }
        }
    }

    compileOptions {
        sourceCompatibility = JavaVersion.VERSION_17
        targetCompatibility = JavaVersion.VERSION_17
    }

    buildFeatures {
        compose = true
        buildConfig = true
    }
}

kotlin {
    compilerOptions {
        jvmTarget.set(org.jetbrains.kotlin.gradle.dsl.JvmTarget.JVM_17)
        optIn.add("androidx.compose.material3.ExperimentalMaterial3ExpressiveApi")
    }
}

dependencies {
    implementation(project(":ingestion"))
    implementation(libs.androidx.core.ktx)
    implementation(libs.androidx.activity.compose)
    implementation(libs.androidx.lifecycle.runtime.compose)
    implementation(platform(libs.compose.bom))
    implementation(libs.compose.ui)
    implementation(libs.compose.foundation)
    implementation(libs.compose.material3)
    implementation(libs.compose.material.icons)
    implementation(libs.coroutines.android)
    implementation(libs.libsu.core)
    implementation(libs.brotli.dec)
    implementation(libs.ktor.server.core)
    implementation(libs.ktor.server.cio)
    implementation(libs.ktor.client.core)
    implementation(libs.ktor.client.cio)
    implementation(libs.ktor.client.websockets)
    implementation(libs.zxing.core)

    testImplementation(libs.junit)
    testImplementation(libs.coroutines.test)
    testImplementation(libs.json.test)
}
