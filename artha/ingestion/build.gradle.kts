plugins {
    alias(libs.plugins.android.library)
    alias(libs.plugins.kotlin.android)
}

// Reads Jago, GoPay, Shopee and Grab on a rooted phone.
android {
    namespace = "net.wastu.artha.ingestion"
    compileSdk = 36
    buildToolsVersion = "36.0.0"

    defaultConfig {
        minSdk = 26
    }

    compileOptions {
        sourceCompatibility = JavaVersion.VERSION_17
        targetCompatibility = JavaVersion.VERSION_17
    }
}

kotlin {
    compilerOptions {
        jvmTarget.set(org.jetbrains.kotlin.gradle.dsl.JvmTarget.JVM_17)
    }
}

dependencies {
    implementation(libs.androidx.core.ktx)
    api(libs.coroutines.android)
    api(libs.libsu.core)
    implementation(libs.brotli.dec)
    testImplementation(libs.junit)
    testImplementation(libs.json.test)
}
