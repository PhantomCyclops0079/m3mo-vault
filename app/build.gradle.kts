plugins {
    id("com.android.application")
    id("org.jetbrains.kotlin.android")
    id("org.jetbrains.kotlin.plugin.compose")
    id("com.google.devtools.ksp")
}

android {
    namespace = "com.m3mo.vault"
    compileSdk = 35

    defaultConfig {
        applicationId = "com.m3mo.vault"
        minSdk = 26
        targetSdk = 35
        versionCode = 1
        versionName = "1.0.0"

        testInstrumentationRunner = "androidx.test.runner.AndroidJUnitRunner"
    }

    buildTypes {
        release {
            isMinifyEnabled = false
            proguardFiles(
                getDefaultProguardFile("proguard-android-optimize.txt"),
                "proguard-rules.pro"
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
        compose = true
        buildConfig = true
    }

    // Configure unit-test options.
    testOptions {
        unitTests {
            isIncludeAndroidResources = true // Required by Robolectric.
            isReturnDefaultValues = true
        }
    }
}

dependencies {
    // ========== Core Android ==========
    implementation("androidx.core:core-ktx:1.15.0")
    implementation("androidx.lifecycle:lifecycle-runtime-ktx:2.8.7")
    implementation("androidx.activity:activity-compose:1.9.3")

    // ========== Compose ==========
    // Compose BOM manages Compose dependency versions.
    implementation(platform("androidx.compose:compose-bom:2024.11.00"))
    implementation("androidx.compose.ui:ui")
    implementation("androidx.compose.ui:ui-graphics")
    implementation("androidx.compose.ui:ui-tooling-preview")
    implementation("androidx.compose.material3:material3")
    implementation("androidx.startup:startup-runtime:1.1.1")

    // ========== Navigation ==========
    implementation("androidx.navigation:navigation-compose:2.8.4")

    // ========== Room Database ==========
    val roomVersion = "2.6.1"
    implementation("androidx.room:room-runtime:$roomVersion")
    implementation("androidx.room:room-ktx:$roomVersion")
    ksp("androidx.room:room-compiler:$roomVersion")

    // ========== WorkManager ==========
    implementation("androidx.work:work-runtime-ktx:2.9.1")

    // ========== Coroutines ==========
    implementation("org.jetbrains.kotlinx:kotlinx-coroutines-android:1.7.3")
    implementation("org.jetbrains.kotlinx:kotlinx-coroutines-core:1.7.3")

    // ========== ViewModel ==========
    implementation("androidx.lifecycle:lifecycle-viewmodel-compose:2.8.7")
    implementation("androidx.lifecycle:lifecycle-runtime-compose:2.8.7")

    // ========== JSON ==========
    // Unit tests need a JVM JSON implementation instead of Android's stub.
    implementation("org.json:json:20230227")

    // ========== Unit Testing ==========
    testImplementation("junit:junit:4.13.2")

    // Kotlin Test
    testImplementation("org.jetbrains.kotlin:kotlin-test")

    // Coroutines test utilities
    testImplementation("org.jetbrains.kotlinx:kotlinx-coroutines-test:1.7.3")

    // JVM JSON support for unit tests
    testImplementation("org.json:json:20230227")

    // Robolectric for tests that use Android APIs
    testImplementation("org.robolectric:robolectric:4.11.1")

    // MockK test doubles
    testImplementation("io.mockk:mockk:1.13.8")

    // Truth assertions
    testImplementation("com.google.truth:truth:1.1.5")

    // Room Testing
    testImplementation("androidx.room:room-testing:$roomVersion")

    // ========== Android Instrumentation Testing ==========
    androidTestImplementation("androidx.test.ext:junit:1.2.1")
    androidTestImplementation("androidx.test.espresso:espresso-core:3.6.1")
    androidTestImplementation(platform("androidx.compose:compose-bom:2024.11.00"))
    androidTestImplementation("androidx.compose.ui:ui-test-junit4")

    // Coroutines utilities for instrumented tests
    androidTestImplementation("org.jetbrains.kotlinx:kotlinx-coroutines-test:1.7.3")

    // ========== Debug Dependencies ==========
    debugImplementation("androidx.compose.ui:ui-tooling")
    debugImplementation("androidx.compose.ui:ui-test-manifest")
}