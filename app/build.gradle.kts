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

    // 配置测试选项（可选但推荐）
    testOptions {
        unitTests {
            isIncludeAndroidResources = true // Robolectric 需要
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
    // Compose BOM (管理所有 Compose 版本)
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

    // ========== JSON (新增 - 关键!) ==========
    // 注意：虽然 Android SDK 包含 org.json，但单元测试需要纯 Java 版本
    implementation("org.json:json:20230227")

    // ========== Unit Testing ==========
    testImplementation("junit:junit:4.13.2")

    // Kotlin Test
    testImplementation("org.jetbrains.kotlin:kotlin-test")

    // Coroutines Test (新增)
    testImplementation("org.jetbrains.kotlinx:kotlinx-coroutines-test:1.7.3")

    // JSON for Unit Tests (新增 - 关键！没有这个会报 Stub! 错误)
    testImplementation("org.json:json:20230227")

    // Robolectric (新增 - 推荐用于测试 Android API)
    testImplementation("org.robolectric:robolectric:4.11.1")

    // MockK (新增 - 推荐用于 mock 测试)
    testImplementation("io.mockk:mockk:1.13.8")

    // Truth (新增 - 推荐用于更好的断言)
    testImplementation("com.google.truth:truth:1.1.5")

    // Room Testing
    testImplementation("androidx.room:room-testing:$roomVersion")

    // ========== Android Instrumentation Testing ==========
    androidTestImplementation("androidx.test.ext:junit:1.2.1")
    androidTestImplementation("androidx.test.espresso:espresso-core:3.6.1")
    androidTestImplementation(platform("androidx.compose:compose-bom:2024.11.00"))
    androidTestImplementation("androidx.compose.ui:ui-test-junit4")

    // Coroutines Test for Android Tests (新增)
    androidTestImplementation("org.jetbrains.kotlinx:kotlinx-coroutines-test:1.7.3")

    // ========== Debug Dependencies ==========
    debugImplementation("androidx.compose.ui:ui-tooling")
    debugImplementation("androidx.compose.ui:ui-test-manifest")
}