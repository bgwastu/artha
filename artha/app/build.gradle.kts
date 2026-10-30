import java.security.MessageDigest

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
        ndk { abiFilters += "arm64-v8a" }
    }

    buildTypes {
        release {
            isMinifyEnabled = true
            isShrinkResources = true
            signingConfig = signingConfigs.findByName("release")
            proguardFiles(getDefaultProguardFile("proguard-android-optimize.txt"), "proguard-rules.pro")
        }
        all { if (name != "demo") buildConfigField("boolean", "DEMO", "false") }
        debug {
            signingConfigs.findByName("release")?.let { signingConfig = it }
        }
        // Installs beside the real app with its own data, filled with invented transactions and with sync off.
        create("demo") {
            initWith(getByName("debug"))
            applicationIdSuffix = ".demo"
            versionNameSuffix = "-demo"
            buildConfigField("boolean", "DEMO", "true")
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
    packaging {
        // cloudflared must be extracted to nativeLibraryDir so Android 10+ lets us exec it.
        jniLibs.useLegacyPackaging = true
        resources.excludes += "/META-INF/{AL2.0,LGPL2.1}"
        resources.merges += "/META-INF/services/**"
    }
    lint {
        warningsAsErrors = true
        // The scaffold deliberately pins a verified API 36 toolchain. Version notices
        // are upgrade suggestions; correctness and accessibility checks remain strict.
        disable += listOf("OldTargetApi", "AndroidGradlePluginVersion", "GradleDependency", "NewerVersionAvailable")
        // Artha ships an arm64 cloudflared and needs root, so Chrome OS (x86) support is intentionally absent.
        disable += "ChromeOsAbiSupport"
    }
    testOptions {
        unitTests.isReturnDefaultValues = true
    }
}

val cloudflaredVersion = "2026.10.3"
// SHA-256 of cloudflared-linux-arm64 for cloudflaredVersion; the build refuses anything else.
val cloudflaredSha256 = "89297ebfb9e8c99c0dc013a1140601453cf3798d8306c5591651f4a3f5210be8"
val cloudflaredLib = layout.projectDirectory.file("src/main/jniLibs/arm64-v8a/libcloudflared.so").asFile
val fetchCloudflared by tasks.registering {
    outputs.file(cloudflaredLib)
    onlyIf { !cloudflaredLib.exists() }
    doLast {
        val partial = cloudflaredLib.parentFile.resolve("${cloudflaredLib.name}.part")
        cloudflaredLib.parentFile.mkdirs()
        partial.delete()
        try {
            uri("https://github.com/cloudflare/cloudflared/releases/download/$cloudflaredVersion/cloudflared-linux-arm64")
                .toURL().openStream().use { input -> partial.outputStream().use { input.copyTo(it) } }
            val actual = sha256(partial)
            if (actual != cloudflaredSha256) {
                cloudflaredLib.delete()
                throw GradleException(
                    "cloudflared $cloudflaredVersion checksum mismatch: expected $cloudflaredSha256 but got $actual",
                )
            }
            partial.copyTo(cloudflaredLib, overwrite = true)
        } finally {
            partial.delete()
        }
    }
}

fun sha256(file: java.io.File): String {
    val digest = MessageDigest.getInstance("SHA-256")
    file.inputStream().use { input ->
        val buffer = ByteArray(DEFAULT_BUFFER_SIZE)
        while (true) {
            val read = input.read(buffer)
            if (read <= 0) break
            digest.update(buffer, 0, read)
        }
    }
    return digest.digest().joinToString("") { "%02x".format(it) }
}
tasks.matching { it.name == "preBuild" }.configureEach { dependsOn(fetchCloudflared) }

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
    implementation(libs.brotli.dec)
    implementation(libs.ktor.server.core)
    implementation(libs.ktor.server.cio)
    implementation(libs.koalaplot.core)

    testImplementation(libs.junit)
    testImplementation(libs.coroutines.test)
    testImplementation(libs.json.test)
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
