import org.jetbrains.kotlin.gradle.dsl.JvmTarget

plugins {
    alias(libs.plugins.android.test)
    alias(libs.plugins.kotlin.android)
    alias(libs.plugins.androidx.baselineprofile)
}

android {
    namespace = "com.fourseveneightnine.tv.baselineprofile"
    compileSdk = libs.versions.compileSdk.get().toInt()

    compileOptions {
        sourceCompatibility = JavaVersion.VERSION_17
        targetCompatibility = JavaVersion.VERSION_17
    }

    defaultConfig {
        // 28 is the Fire OS 7 floor `:app` targets, and it is also the oldest release on which a
        // baseline profile is installed at all.
        minSdk = 28
        targetSdk = 34
        testInstrumentationRunner = "androidx.test.runner.AndroidJUnitRunner"
    }

    // `:app` splits on a channel dimension, so this module has to answer with the same dimension
    // or Gradle cannot resolve which app variant a generator run belongs to.
    flavorDimensions += "channel"
    productFlavors {
        create("sideload") {
            dimension = "channel"
            testInstrumentationRunnerArguments["targetAppId"] = "com.fourseveneightnine.tv"
        }
        create("googleTv") {
            dimension = "channel"
            testInstrumentationRunnerArguments["targetAppId"] = "com.fourseveneightnine.tv"
        }
        create("play") {
            dimension = "channel"
            testInstrumentationRunnerArguments["targetAppId"] = "com.fourseveneightnine.tv.play"
        }
    }

    targetProjectPath = ":app"
}

kotlin {
    jvmToolchain(17)
    compilerOptions {
        jvmTarget.set(JvmTarget.JVM_17)
    }
}

baselineProfile {
    // The profile is recorded on a real box — the onn 4K Pro, per plan §9 — never on an emulator.
    useConnectedDevices = true
}

tasks.matching { it.name.startsWith("connected") && it.name.endsWith("AndroidTest") }.configureEach {
    doFirst {
        check(providers.gradleProperty("tvProfileDisposableDevice").orNull == "true") {
            "Baseline profile collection clears target app data. Run only on a disposable TV with -PtvProfileDisposableDevice=true."
        }
    }
}

dependencies {
    implementation(libs.androidx.test.ext.junit)
    implementation(libs.androidx.test.uiautomator)
    implementation(libs.androidx.benchmark.macro.junit4)
}
