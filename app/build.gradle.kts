// ColdTs version — used for versionName and the output APK file name
val coldtsVersionName = "1.0.0"

plugins {
    alias(libs.plugins.android.application)
    alias(libs.plugins.kotlin.android)
    alias(libs.plugins.kotlin.compose)
}

android {
    namespace = "dev.tsdroid.han"
    compileSdk = 35

    defaultConfig {
        applicationId = "com.gamezad.coldts"
        minSdk = 29
        targetSdk = 35
        versionCode = 7
        versionName = coldtsVersionName

        testInstrumentationRunner = "androidx.test.runner.AndroidJUnitRunner"

        ndk {
            // libtslib_jni.so is only prebuilt for these ABIs
            abiFilters += listOf("arm64-v8a", "x86_64")
        }
    }

    signingConfigs {
        if (file("${rootDir}/release.keystore").exists()) {
            create("release") {
                storeFile = file("${rootDir}/release.keystore")
                storePassword = "ts6droid"
                keyAlias = "ts6droid"
                keyPassword = "ts6droid"
            }
        }
    }

    buildTypes {
        debug {
            if (signingConfigs.names.contains("release")) {
                signingConfig = signingConfigs.getByName("release")
            }
        }
        release {
            isMinifyEnabled = false
            proguardFiles(
                getDefaultProguardFile("proguard-android-optimize.txt"),
                "proguard-rules.pro"
            )
            if (signingConfigs.names.contains("release")) {
                signingConfig = signingConfigs.getByName("release")
            }
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
    }

    sourceSets {
        getByName("main") {
            java.srcDirs("src/main/java", "src/main/kotlin")
        }
    }
}

// ColdTs: name the built APK file "ColdTs-Client-v<version>.apk"
androidComponents {
    onVariants { variant ->
        variant.outputs.forEach { output ->
            output.outputFileName.set("ColdTs-Client-v$coldtsVersionName.apk")
        }
    }
}

// Compatibility: older CI workflows upload app/build/outputs/apk/debug/app-debug.apk —
// mirror the renamed APK under the legacy name after packaging so both work.
afterEvaluate {
    tasks.matching { it.name == "packageDebug" || it.name == "packageRelease" }.configureEach {
        doLast {
            val buildType = name.removePrefix("package").lowercase()
            val outDir = File(projectDir, "build/outputs/apk/$buildType")
            val main = outDir.listFiles()?.firstOrNull { it.isFile && it.name.startsWith("ColdTs-Client") } ?: return@doLast
            val legacy = File(outDir, "app-$buildType.apk")
            if (!legacy.exists()) main.copyTo(legacy, overwrite = true)
        }
    }
}

// Task to build Rust native libraries via cargo-ndk
tasks.register<Exec>("buildRustLibs") {
    workingDir = file("${rootDir}/../tslib")
    environment("ANDROID_NDK_HOME", System.getenv("ANDROID_NDK_HOME")
        ?: "${System.getProperty("user.home")}/Android/Sdk/ndk/27.2.12479018")
    environment("ANDROID_NDK", System.getenv("ANDROID_NDK_HOME")
        ?: "${System.getProperty("user.home")}/Android/Sdk/ndk/27.2.12479018")
    environment("CMAKE_POLICY_VERSION_MINIMUM", "3.5")
    commandLine(
        "cargo", "ndk",
        "-t", "arm64-v8a",
        "-t", "x86_64",
        "-o", "${projectDir}/src/main/jniLibs",
        "build", "--release", "-p", "tslib-jni",
        "--features", "vendored-openssl",
        "-j10"
    )
}

dependencies {
    implementation(libs.androidx.core.ktx)
    implementation(libs.androidx.lifecycle.runtime.ktx)
    implementation(libs.androidx.lifecycle.viewmodel.compose)
    implementation(libs.androidx.lifecycle.runtime.compose)
    implementation(libs.androidx.lifecycle.viewmodel.savedstate)
    implementation(libs.androidx.lifecycle.service)
    implementation(libs.androidx.activity.compose)
    implementation(platform(libs.androidx.compose.bom))
    implementation(libs.androidx.ui)
    implementation(libs.androidx.ui.graphics)
    implementation(libs.androidx.ui.tooling.preview)
    implementation(libs.androidx.material3)
    implementation(libs.androidx.material.icons.extended)
    implementation(libs.androidx.navigation.compose)
    implementation(libs.androidx.datastore.preferences)
    implementation(libs.kotlinx.coroutines.android)
    implementation(libs.coil.compose)
    debugImplementation(libs.androidx.ui.tooling)
}
