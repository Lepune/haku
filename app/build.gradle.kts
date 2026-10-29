plugins {
    id("com.android.application")
    id("org.jetbrains.kotlin.android")
    id("org.jetbrains.kotlin.plugin.compose")
}

val ciVersionCode = (System.getenv("GITHUB_RUN_NUMBER") ?: "1").toInt()

android {
    namespace = "fi.lepune.haku"
    compileSdk = 36

    defaultConfig {
        applicationId = "fi.lepune.haku"
        minSdk = 31
        targetSdk = 36
        versionCode = ciVersionCode
        versionName = "0.1.$ciVersionCode"
        ndk { abiFilters += listOf("arm64-v8a") }
    }

    signingConfigs {
        create("release") {
            val ks = System.getenv("HAKU_KEYSTORE")
            if (ks != null && file(ks).exists()) {
                storeFile = file(ks)
                storePassword = System.getenv("HAKU_KEYSTORE_PASSWORD")
                keyAlias = System.getenv("HAKU_KEY_ALIAS") ?: "haku"
                keyPassword = System.getenv("HAKU_KEY_PASSWORD") ?: System.getenv("HAKU_KEYSTORE_PASSWORD")
            }
        }
    }

    buildTypes {
        release {
            isMinifyEnabled = false
            val rel = signingConfigs.getByName("release")
            signingConfig = if (rel.storeFile != null) rel else signingConfigs.getByName("debug")
        }
    }

    compileOptions {
        sourceCompatibility = JavaVersion.VERSION_17
        targetCompatibility = JavaVersion.VERSION_17
    }

    buildFeatures { compose = true }

    packaging {
        // Native libs must be extracted to nativeLibraryDir so the NPU dispatch library can be found.
        jniLibs { useLegacyPackaging = true }
        resources { excludes += setOf("/META-INF/{AL2.0,LGPL2.1}", "META-INF/DEPENDENCIES", "META-INF/INDEX.LIST") }
    }
}

kotlin {
    compilerOptions {
        jvmTarget.set(org.jetbrains.kotlin.gradle.dsl.JvmTarget.JVM_17)
        // LiteRT-LM AARs may be built with a newer Kotlin than ours.
        freeCompilerArgs.addAll("-Xskip-metadata-version-check")
    }
}

dependencies {
    implementation("com.google.ai.edge.litertlm:litertlm-android:latest.release")

    val composeBom = platform("androidx.compose:compose-bom:2025.06.01")
    implementation(composeBom)
    implementation("androidx.compose.ui:ui")
    implementation("androidx.compose.material3:material3")
    implementation("androidx.compose.material:material-icons-core")
    implementation("androidx.compose.ui:ui-tooling-preview")
    debugImplementation("androidx.compose.ui:ui-tooling")

    implementation("androidx.activity:activity-compose:1.10.1")
    implementation("androidx.core:core-ktx:1.16.0")
    implementation("androidx.lifecycle:lifecycle-viewmodel-compose:2.9.1")
    implementation("androidx.lifecycle:lifecycle-runtime-compose:2.9.1")
    implementation("org.jetbrains.kotlinx:kotlinx-coroutines-android:1.10.2")

    implementation("com.squareup.okhttp3:okhttp:4.12.0")
    implementation("org.jsoup:jsoup:1.18.3")
    implementation("com.mikepenz:multiplatform-markdown-renderer-m3:0.28.0")
}
