import org.jetbrains.kotlin.gradle.dsl.JvmTarget

plugins {
    alias(libs.plugins.android.application)
    alias(libs.plugins.kotlin.android)
    alias(libs.plugins.kotlin.serialization)
}

android {
    namespace = "com.fourseveneightnine.tv"
    compileSdk = libs.versions.compileSdk.get().toInt()

    // TEMPORARY, for the ffmpeg-decoder bring-up only. libmpv (FFmpeg n8.1) and
    // nextlib-media3ext (FFmpeg 6.0) ship the same SONAMEs with incompatible ABIs, so exactly one
    // set can be packaged. nextlib wins here because the Exo path is the one under test; the mpv
    // engine MUST NOT be selected while this is in place — it would link against the wrong
    // FFmpeg. Resolved for good by removing libmpv once the ffmpeg audio path is proven.
    packaging {
        jniLibs {
            pickFirsts += setOf(
                "lib/*/libavcodec.so",
                "lib/*/libavutil.so",
                "lib/*/libswresample.so",
                "lib/*/libswscale.so",
            )
        }
    }

    defaultConfig {
        applicationId = "com.fourseveneightnine.tv"
        minSdk = 28
        // Fire OS 7 (the Android 9 base used by the Insignia Fire TV family) expects TV
        // applications to target API 28. A newer target changes framework behavior on this
        // device even though the APK still installs successfully, and the Activity crashes while
        // opening. Keep compileSdk current while matching the Fire TV runtime contract.
        targetSdk = 28
        versionCode = 35
        versionName = "0.1.34"
        ndk {
            abiFilters += setOf("arm64-v8a", "armeabi-v7a")
        }
    }

    // This receiver is sideload-only. Fire OS 7 requires target API 28 for runtime compatibility,
    // so the Play Store-only target-age warning is intentionally not a release blocker here.
    lint {
        disable += "ExpiredTargetSdkVersion"
    }

    sourceSets.getByName("main").assets.srcDir(
        rootProject.file("../App/FourSevenEightNine/Resources/Fonts"),
    )

    compileOptions {
        sourceCompatibility = JavaVersion.VERSION_17
        targetCompatibility = JavaVersion.VERSION_17
    }
}

kotlin {
    jvmToolchain(17)
    compilerOptions {
        jvmTarget.set(JvmTarget.JVM_17)
    }
}

dependencies {
    implementation(libs.androidx.core.ktx)
    implementation(libs.androidx.appcompat)
    implementation(libs.androidx.lifecycle.service)
    implementation(libs.media3.exoplayer)
    implementation(libs.media3.ui)
    implementation(libs.media3.datasource.okhttp)
    // ExoPlayer.setVideoEffects reflects into this module; it is a runtime requirement for the
    // SGSR upscaler, not a compile-time one, and must stay version-locked to the other media3 deps.
    implementation(libs.media3.effect)
    implementation(libs.nextlib.media3ext)
    implementation(libs.ktor.server.cio)
    implementation(libs.ktor.server.websockets)
    implementation(libs.kotlinx.serialization.json)
    implementation(libs.kotlinx.coroutines.android)
    implementation(libs.libmpv)

    testImplementation(libs.junit)
    testImplementation(libs.kotlinx.coroutines.test)
}
