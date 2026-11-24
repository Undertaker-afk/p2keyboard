// HeliBoard (https://github.com/HeliBorg/HeliBoard, GPL-3.0) vendored as an Android library.
// Upstream's app/build.gradle.kts, changed only as needed to build as a library inside
// p2keyboard: library plugin, release/debug build types only, app fields added to BuildConfig.
plugins {
    id("com.android.library")
    kotlin("plugin.serialization") version "2.4.0"
    kotlin("plugin.compose") version "2.4.0"
}

android {
    compileSdk = 37

    defaultConfig {
        minSdk = 21
        ndk {
            abiFilters.clear()
            abiFilters.addAll(listOf("armeabi-v7a", "arm64-v8a", "x86", "x86_64"))
        }
        consumerProguardFiles("proguard-rules.pro")
        // Library BuildConfig lacks these app fields; HeliBoard uses them for its data dir and upgrades.
        buildConfigField("String", "APPLICATION_ID", "\"app.p2keyboard\"")
        buildConfigField("int", "VERSION_CODE", "4301")
        buildConfigField("String", "VERSION_NAME", "\"4.3-beta2\"")
    }

    buildTypes {
        release {
            isMinifyEnabled = false // the app module minifies
        }
    }

    buildFeatures {
        viewBinding = true
        buildConfig = true
        compose = true
    }

    externalNativeBuild {
        ndkBuild {
            path = File("src/main/jni/Android.mk")
        }
    }
    ndkVersion = "28.0.13004108"

    compileOptions {
        isCoreLibraryDesugaringEnabled = true
        sourceCompatibility = JavaVersion.VERSION_17
        targetCompatibility = JavaVersion.VERSION_17
    }

    namespace = "helium314.keyboard.latin"
}

dependencies {
    // androidx
    implementation("androidx.core:core-ktx:1.17.0")
    implementation("androidx.recyclerview:recyclerview:1.4.0")
    implementation("androidx.autofill:autofill:1.3.0")
    implementation("androidx.viewpager2:viewpager2:1.1.0")

    // kotlin
    implementation("org.jetbrains.kotlinx:kotlinx-serialization-json:1.11.0")

    // compose
    coreLibraryDesugaring("com.android.tools:desugar_jdk_libs:2.1.5")
    implementation(platform("androidx.compose:compose-bom:2025.11.01"))
    implementation("androidx.compose.material3:material3")
    implementation("androidx.compose.ui:ui-tooling-preview")
    debugImplementation("androidx.compose.ui:ui-tooling")
    implementation("androidx.navigation:navigation-compose:2.9.8")
    implementation("sh.calvin.reorderable:reorderable:3.1.0")
    implementation("com.github.skydoves:colorpicker-compose:1.1.3")
}
