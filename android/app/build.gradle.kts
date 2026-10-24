plugins {
    id("com.android.application")
    kotlin("android")
    id("org.jetbrains.kotlin.plugin.compose")
}

// Release signing comes from the environment (CI): a permanent key from repository secrets when the owner has
// approved one, otherwise a throwaway key made for that build. Nothing secret is ever stored in the repository.
val signingFile: String? = System.getenv("AZ_SIGNING_STORE_FILE")?.takeIf { it.isNotEmpty() }

android {
    namespace = "com.airzelo.app"
    compileSdk = 35

    defaultConfig {
        applicationId = "com.airzelo.app"
        // the desktop's SQL uses upserts (ON CONFLICT ... DO UPDATE), which need SQLite 3.24+: Android 11 (API 30) ships 3.28
        minSdk = 30
        targetSdk = 35
        versionCode = 3
        versionName = "0.3.0"
    }

    signingConfigs {
        if (signingFile != null) {
            create("release") {
                storeFile = file(signingFile)
                storePassword = System.getenv("AZ_SIGNING_STORE_PASSWORD")
                keyAlias = System.getenv("AZ_SIGNING_KEY_ALIAS")
                keyPassword = System.getenv("AZ_SIGNING_KEY_PASSWORD")
            }
        }
    }

    buildTypes {
        release {
            // no code shrinking: the engine is small, and an unshrunk build is what the tests exercise
            isMinifyEnabled = false
            if (signingFile != null) signingConfig = signingConfigs.getByName("release")
        }
    }

    compileOptions {
        sourceCompatibility = JavaVersion.VERSION_17
        targetCompatibility = JavaVersion.VERSION_17
    }
    kotlinOptions { jvmTarget = "17" }
    buildFeatures { compose = true }

    packaging { resources.excludes += setOf("META-INF/{AL2.0,LGPL2.1}", "META-INF/versions/9/previous-compilation-data.bin") }

    testOptions {
        unitTests.isIncludeAndroidResources = true
        unitTests.all {
            it.maxHeapSize = "2g"
            it.testLogging { events("passed", "skipped", "failed"); showStandardStreams = true
                exceptionFormat = org.gradle.api.tasks.testing.logging.TestExceptionFormat.FULL }
        }
    }
}

kotlin { jvmToolchain(17) }

dependencies {
    implementation(project(":engine"))
    val composeBom = platform("androidx.compose:compose-bom:2024.12.01")
    implementation(composeBom)
    implementation("androidx.compose.ui:ui")
    implementation("androidx.compose.material3:material3")
    implementation("androidx.compose.material:material-icons-core")
    implementation("androidx.activity:activity-compose:1.9.3")
    implementation("androidx.fragment:fragment-ktx:1.8.5")
    implementation("androidx.lifecycle:lifecycle-viewmodel-compose:2.8.7")
    implementation("androidx.lifecycle:lifecycle-process:2.8.7")
    implementation("androidx.navigation:navigation-compose:2.8.5")
    implementation("androidx.biometric:biometric:1.1.0")
    implementation("androidx.core:core-ktx:1.15.0")

    testImplementation(composeBom)
    testImplementation("junit:junit:4.13.2")
    testImplementation("org.robolectric:robolectric:4.14.1")
    testImplementation("androidx.test:core-ktx:1.6.1")
    testImplementation("androidx.test.ext:junit:1.2.1")
    testImplementation("androidx.compose.ui:ui-test-junit4")
}
