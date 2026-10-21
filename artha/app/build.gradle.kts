plugins {
    alias(libs.plugins.android.application)
    alias(libs.plugins.kotlin.android)
    alias(libs.plugins.kotlin.compose)
    alias(libs.plugins.ksp)
    alias(libs.plugins.room)
}

android {
    namespace = "net.wastu.artha"
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
        applicationId = "net.wastu.artha"
        minSdk = 26
        targetSdk = 36
        versionCode = providers.gradleProperty("arthaVersionCode").orElse("1").get().toInt()
        versionName = providers.gradleProperty("arthaVersionName").orElse("0.1.0").get()
        testInstrumentationRunner = "androidx.test.runner.AndroidJUnitRunner"
    }

    buildTypes {
        release {
            isMinifyEnabled = true
            isShrinkResources = true
            signingConfig = signingConfigs.findByName("release")
            proguardFiles(getDefaultProguardFile("proguard-android-optimize.txt"), "proguard-rules.pro")
        }
        debug {
            signingConfigs.findByName("release")?.let { signingConfig = it }
        }
    }

    compileOptions {
        sourceCompatibility = JavaVersion.VERSION_17
        targetCompatibility = JavaVersion.VERSION_17
    }

    buildFeatures { compose = true }
    packaging {
        resources.excludes += "/META-INF/{AL2.0,LGPL2.1}"
        resources.merges += "/META-INF/services/**"
    }
    lint {
        warningsAsErrors = true
        // The scaffold deliberately pins a verified API 36 toolchain. Version notices
        // are upgrade suggestions; correctness and accessibility checks remain strict.
        disable += listOf("OldTargetApi", "AndroidGradlePluginVersion", "GradleDependency", "NewerVersionAvailable")
    }
    testOptions {
        unitTests.isReturnDefaultValues = true
    }
}

kotlin { compilerOptions { jvmTarget.set(org.jetbrains.kotlin.gradle.dsl.JvmTarget.JVM_17) } }
room { schemaDirectory("$projectDir/schemas") }

dependencies {
    implementation(libs.androidx.core.ktx)
    implementation(libs.androidx.activity.compose)
    implementation(libs.androidx.lifecycle.runtime.compose)
    implementation(libs.androidx.lifecycle.viewmodel.compose)
    implementation(platform(libs.compose.bom))
    implementation(libs.compose.ui)
    implementation(libs.compose.foundation)
    implementation(libs.compose.ui.tooling.preview)
    implementation(libs.compose.material3)
    implementation(libs.compose.material.icons)
    implementation(libs.room.runtime)
    implementation(libs.room.ktx)
    ksp(libs.room.compiler)
    implementation(libs.coroutines.android)
    implementation(libs.libsu.core)
    implementation("org.brotli:dec:0.1.2")

    testImplementation(libs.junit)
    testImplementation(libs.coroutines.test)
    testImplementation("org.json:json:20240303")
    androidTestImplementation(libs.androidx.test.junit)
    androidTestImplementation(libs.androidx.test.core)
    androidTestImplementation(libs.androidx.test.runner)
    androidTestImplementation(libs.espresso.core)
    androidTestImplementation(libs.coroutines.test)
    androidTestImplementation(libs.coroutines.android)
    androidTestImplementation(platform(libs.compose.bom))
    androidTestImplementation(libs.compose.ui.test.junit4)
    debugImplementation(libs.compose.ui.tooling)
    debugImplementation(libs.compose.ui.test.manifest)
    debugImplementation(libs.coroutines.test)
}
