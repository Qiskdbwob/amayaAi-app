plugins {
    alias(libs.plugins.android.application)
    alias(libs.plugins.kotlin.android)
    alias(libs.plugins.kotlin.compose)
    alias(libs.plugins.ksp)
    alias(libs.plugins.hilt)
    alias(libs.plugins.android.baselineprofile)
}

import java.util.Properties

val keystorePropertiesFile = rootProject.file(".env.local")
val keystoreProperties = Properties()
if (keystorePropertiesFile.exists()) {
    keystoreProperties.load(keystorePropertiesFile.inputStream())
}

android {
    namespace = "com.amaya.intelligence"
    compileSdk = 35

    sourceSets {
        getByName("main") {
            res.srcDirs(
                "src/main/res",
                "src/main/res-agent",
                "src/main/res-remote"
            )
        }
    }

    defaultConfig {
        applicationId = "com.amaya.intelligence"
        minSdk = 26
        targetSdk = 34
        versionCode = 1
        versionName = "1.0.0"

        testInstrumentationRunner = "androidx.test.runner.AndroidJUnitRunner"

        // Room schema export for migrations
        ksp {
            arg("room.schemaLocation", "$projectDir/schemas")
        }
    }

    signingConfigs {
        create("release") {
            if (keystoreProperties.containsKey("AMAYA_KEYSTORE_PASSWORD")) {
                storeFile = file("../release.keystore")
                storePassword = keystoreProperties["AMAYA_KEYSTORE_PASSWORD"] as String
                keyAlias = keystoreProperties["AMAYA_KEY_ALIAS"] as String
                keyPassword = keystoreProperties["AMAYA_KEYSTORE_PASSWORD"] as String
            }
        }
    }

    buildTypes {
        release {
            isMinifyEnabled = true
            isShrinkResources = true
            proguardFiles(
                getDefaultProguardFile("proguard-android-optimize.txt"),
                "proguard-rules.pro"
            )
            if (keystoreProperties.containsKey("AMAYA_KEYSTORE_PASSWORD")) {
                signingConfig = signingConfigs.getByName("release")
            }
        }
        debug {
            isMinifyEnabled = false
            applicationIdSuffix = ".debug"
        }
        // Profileable build for daily testing: AOT-compiled by ART at install and
        // picks up the bundled baseline profile. Fixes the ~5s cold-start JIT jank
        // seen on debug (debuggable builds cannot be AOT-compiled by ART).
        create("benchmark") {
            initWith(buildTypes.getByName("release"))
            isMinifyEnabled = false
            isShrinkResources = false
            signingConfig = signingConfigs.getByName("debug")
            matchingFallbacks += listOf("release")
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
        buildConfig = true
    }

    packaging {
        jniLibs {
            useLegacyPackaging = true
        }
        resources {
            excludes += setOf(
                "META-INF/DEPENDENCIES",
                "META-INF/LICENSE",
                "META-INF/LICENSE.txt",
                "META-INF/NOTICE",
                "META-INF/NOTICE.txt"
            )
        }
    }
}

dependencies {
    // Core Android
    implementation(libs.androidx.core.ktx)
    implementation(libs.androidx.lifecycle.runtime.ktx)
    implementation(libs.androidx.lifecycle.viewmodel.compose)
    implementation(libs.androidx.activity.compose)
    implementation("androidx.appcompat:appcompat:1.7.0")

    // Jetpack Compose
    implementation(platform(libs.androidx.compose.bom))
    implementation(libs.androidx.ui)
    implementation(libs.androidx.ui.graphics)
    implementation(libs.androidx.ui.tooling.preview)
    implementation(libs.androidx.material3)
    implementation(libs.androidx.material.icons.extended)
    implementation(libs.androidx.navigation.compose)

    // Room Database
    implementation(libs.androidx.room.runtime)
    implementation(libs.androidx.room.ktx)
    ksp(libs.androidx.room.compiler)

    // Networking - Retrofit + OkHttp
    implementation(libs.retrofit.core)
    implementation(libs.retrofit.converter.moshi)
    implementation(libs.okhttp.core)
    implementation(libs.okhttp.logging)
    implementation(libs.okhttp.sse)

    // Moshi JSON
    implementation(libs.moshi.core)
    implementation(libs.moshi.kotlin)
    ksp(libs.moshi.codegen)

    // Coroutines
    implementation(libs.kotlinx.coroutines.core)
    implementation(libs.kotlinx.coroutines.android)

    // SVG file-type icons
    implementation(libs.coil.compose)
    implementation(libs.coil.svg)

    // Hilt DI
    implementation(libs.hilt.android)
    implementation(libs.hilt.navigation.compose)
    ksp(libs.hilt.compiler)

    // WorkManager (for background reminder AI reply)
    implementation("androidx.work:work-runtime-ktx:2.9.1")
    implementation("androidx.hilt:hilt-work:1.2.0")
    ksp("androidx.hilt:hilt-compiler:1.2.0")

    // DataStore
    implementation(libs.androidx.datastore.preferences)

    // Security - Encrypted SharedPreferences
    implementation("androidx.security:security-crypto:1.1.0-alpha06")

    // Apache Commons Compress for tar.gz extraction
    implementation("org.apache.commons:commons-compress:1.26.0")
    // XZ compression library for .tar.xz (required by Commons Compress)
    implementation("org.tukaani:xz:1.9")

    // PDF support disabled - requires PDFBox-Android which is not available in public repos
    // Office/OpenDocument formats (DOCX, XLSX, PPTX, ODT, ODS, RTF) fully supported with zero dependencies

    // WebSocket client for Remote Session (Antigravity IDE bridge)
    implementation("org.java-websocket:Java-WebSocket:1.5.7")

    // QR Scanning - CameraX + ML Kit (Unbundled Play Services)
    val cameraxVersion = "1.4.1"
    implementation("androidx.camera:camera-core:$cameraxVersion")
    implementation("androidx.camera:camera-camera2:$cameraxVersion")
    implementation("androidx.camera:camera-lifecycle:$cameraxVersion")
    implementation("androidx.camera:camera-view:$cameraxVersion")
    implementation("com.google.android.gms:play-services-mlkit-barcode-scanning:18.3.1")

    // Custom Tabs for Codex OAuth browser flow
    implementation("androidx.browser:browser:1.8.0")


    // Testing
    testImplementation(libs.junit)
    testImplementation("org.json:json:20240303")
    testImplementation("org.jetbrains.kotlinx:kotlinx-coroutines-test:1.8.1")
    testImplementation("org.jetbrains.kotlin:kotlin-test:2.0.0")
    androidTestImplementation(libs.androidx.junit)
    androidTestImplementation(libs.androidx.espresso.core)
    androidTestImplementation(platform(libs.androidx.compose.bom))
    androidTestImplementation(libs.androidx.ui.test.junit4)
    debugImplementation(libs.androidx.ui.tooling)
    debugImplementation(libs.androidx.ui.test.manifest)

    // Baseline profile producer module; the androidx.baselineprofile plugin wires
    // its generated baseline-prof.txt into assets/dexopt/baseline-prof.
    baselineProfile(project(":baselineprofile"))
}
