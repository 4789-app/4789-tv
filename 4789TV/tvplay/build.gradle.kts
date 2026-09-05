import org.jetbrains.kotlin.gradle.dsl.JvmTarget
import org.gradle.api.tasks.Sync

plugins {
    alias(libs.plugins.android.application)
    alias(libs.plugins.kotlin.android)
    alias(libs.plugins.kotlin.serialization)
    alias(libs.plugins.compose.compiler)
}

val generatedPlayLicenseAssets = layout.buildDirectory.dir("generated/playLicenseAssets")
val syncPlayLicenseAssets by tasks.registering(Sync::class) {
    // Ship authoritative receiver and external-font licenses with every APK.
    from(rootProject.file("licenses")) {
        include("OFL-BricolageGrotesque.txt", "OFL-Figtree.txt", "OFL-SpaceMono.txt", "external-fonts/**")
    }
    into(generatedPlayLicenseAssets.map { it.dir("licenses") })
}

android {
    namespace = "com.fourseveneightnine.tv"
    compileSdk = 36

    buildFeatures {
        compose = true
    }

    defaultConfig {
        applicationId = "com.fourseveneightnine.tv.play"
        minSdk = 28
        targetSdk = 34
        versionCode = 1
        versionName = "0.1.0-play-foundation"
        ndk {
            abiFilters += setOf("arm64-v8a", "armeabi-v7a")
        }
    }

    sourceSets {
        getByName("main") {
            java.srcDir(rootProject.file("app/src/main/java"))
            // Google Play forbids an app that downloads and installs other apps. The shared tree
            // above holds no installer; the working one lives in app/src/installer/ and is
            // compiled by :app alone. This product takes the stub, so the download hosts and the
            // install intent are absent from the shipped dex rather than merely unreachable.
            // check-android-foundation.sh greps the built Play dex to prove it.
            java.srcDir(rootProject.file("app/src/installer-stub/java"))
            res.srcDir(rootProject.file("app/src/main/res"))
            assets.srcDir(rootProject.file("app/src/main/assets"))
            assets.srcDir(rootProject.file("../App/FourSevenEightNine/Resources/Fonts"))
            assets.srcDir(generatedPlayLicenseAssets)
        }
        getByName("test") {
            java.srcDir(rootProject.file("app/src/test/java"))
        }
    }

    packaging {
        jniLibs {
            // The shared source tree still contains the disabled mpv implementation, but the Play
            // product cannot package it: libmpv requires FFmpeg 8 symbols while Media3's working
            // decoder extension requires FFmpeg 6 under the same SONAMEs. Play TV is Exo-only, so
            // remove mpv's unique native closure instead of shipping an unselectable broken engine.
            excludes += setOf(
                "lib/*/libmpv.so",
                "lib/*/libplayer.so",
                "lib/*/libavdevice.so",
                "lib/*/libavfilter.so",
                "lib/*/libavformat.so",
                "lib/*/libc++_shared.so",
            )
            pickFirsts += setOf(
                "lib/*/libavcodec.so",
                "lib/*/libavutil.so",
                "lib/*/libswresample.so",
                "lib/*/libswscale.so",
            )
        }
    }

    compileOptions {
        sourceCompatibility = JavaVersion.VERSION_17
        targetCompatibility = JavaVersion.VERSION_17
    }

    val uploadStore = providers.environmentVariable("FOURSEVENEIGHTNINE_PLAY_STORE_FILE").orNull
    val uploadAlias = providers.environmentVariable("FOURSEVENEIGHTNINE_PLAY_KEY_ALIAS").orNull
    val uploadStorePassword = providers.environmentVariable("FOURSEVENEIGHTNINE_PLAY_STORE_PASSWORD").orNull
    val uploadKeyPassword = providers.environmentVariable("FOURSEVENEIGHTNINE_PLAY_KEY_PASSWORD").orNull
    if (listOf(uploadStore, uploadAlias, uploadStorePassword, uploadKeyPassword).all { it != null }) {
        signingConfigs.create("playUpload") {
            storeFile = file(requireNotNull(uploadStore))
            storePassword = requireNotNull(uploadStorePassword)
            keyAlias = requireNotNull(uploadAlias)
            keyPassword = requireNotNull(uploadKeyPassword)
        }
        buildTypes.getByName("release").signingConfig = signingConfigs.getByName("playUpload")
    }
}

tasks.named("preBuild").configure { dependsOn(syncPlayLicenseAssets) }

kotlin {
    jvmToolchain(17)
    compilerOptions {
        jvmTarget.set(JvmTarget.JVM_17)
    }
}

dependencies {
    implementation(project(":contract"))
    implementation(libs.androidx.core.ktx)
    implementation(libs.androidx.appcompat)
    implementation(libs.androidx.lifecycle.service)
    implementation(libs.media3.exoplayer)
    implementation(libs.media3.ui)
    implementation(libs.media3.datasource.okhttp)
    implementation(libs.media3.effect)
    implementation(libs.nextlib.media3ext)
    implementation(libs.ktor.server.cio)
    implementation(libs.ktor.server.websockets)
    implementation(libs.kotlinx.serialization.json)
    implementation(libs.kotlinx.coroutines.android)
    implementation(libs.libmpv)
    implementation(libs.androidx.activity.compose)
    implementation(libs.androidx.lifecycle.runtime.compose)
    implementation(platform(libs.compose.bom))
    implementation(libs.compose.ui)
    implementation(libs.compose.ui.tooling.preview)
    implementation(libs.tv.material)
    implementation(libs.zxing.core)

    debugImplementation(libs.compose.ui.tooling)

    testImplementation(libs.junit)
    testImplementation(libs.kotlinx.coroutines.test)
}
