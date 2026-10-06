import com.android.build.api.variant.ApplicationAndroidComponentsExtension
import org.jetbrains.kotlin.gradle.dsl.JvmTarget
import org.gradle.api.tasks.Sync

plugins {
    alias(libs.plugins.android.application)
    alias(libs.plugins.kotlin.android)
    alias(libs.plugins.kotlin.serialization)
    alias(libs.plugins.compose.compiler)
    alias(libs.plugins.androidx.baselineprofile)
}

val generatedReceiverLicenseAssets = layout.buildDirectory.dir("generated/receiverLicenseAssets")
val syncReceiverLicenseAssets by tasks.registering(Sync::class) {
    // Ship authoritative receiver, external-font and Material Symbols licenses with every APK. Inter replaced
    // Bricolage Grotesque and Figtree in 0.2.0, so only its OFL text and Space Mono's ship now.
    from(rootProject.file("licenses")) {
        include("OFL-Inter.txt", "OFL-SpaceMono.txt", "external-fonts/**", "material-symbols/**")
    }
    into(generatedReceiverLicenseAssets.map { it.dir("licenses") })
}

android {
    namespace = "com.fourseveneightnine.tv"
    compileSdk = libs.versions.compileSdk.get().toInt()

    buildFeatures {
        compose = true
    }

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
        minSdk = 28
        versionCode = 45
        versionName = "0.2.1"
        ndk {
            abiFilters += setOf("arm64-v8a", "armeabi-v7a")
        }
    }

    // Three channels out of one module: legacy Fire TV direct distribution, modern Google TV
    // direct distribution, and Google Play. The two direct variants keep the same app identity and
    // signing key, so a receiver can move between them without losing its paired settings.
    flavorDimensions += "channel"
    productFlavors {
        create("sideload") {
            dimension = "channel"
            applicationId = "com.fourseveneightnine.tv"
            resValue("string", "search_suggest_authority", "com.fourseveneightnine.tv.search")
            // Fire OS 7 (the Android 9 base used by the Insignia Fire TV family) expects TV
            // applications to target API 28. A newer target changes framework behavior on this
            // device even though the APK still installs successfully, and the Activity crashes
            // while opening. Keep compileSdk current while matching the Fire TV runtime contract.
            targetSdk = 28
        }
        create("googleTv") {
            dimension = "channel"
            applicationId = "com.fourseveneightnine.tv"
            resValue("string", "search_suggest_authority", "com.fourseveneightnine.tv.search")
            targetSdk = 34
        }
        create("play") {
            dimension = "channel"
            applicationId = "com.fourseveneightnine.tv.play"
            resValue("string", "search_suggest_authority", "com.fourseveneightnine.tv.play.search")
            targetSdk = 34
        }
    }

    // The sideload receiver is allowed to fetch and install an external player, and it declares
    // REQUEST_INSTALL_PACKAGES to do so. It therefore compiles the working PlayerInstaller. The
    // Play channel compiles the stub from app/src/installer-stub/ instead, so the download hosts
    // never reach its APK. See app/src/installer/java/.../PlayerInstaller.kt.
    sourceSets.getByName("sideload").java.srcDir(file("src/installer/java"))
    sourceSets.getByName("googleTv").apply {
        java.srcDir(file("src/installer/java"))
        manifest.srcFile(file("src/googleTv/AndroidManifest.xml"))
    }
    sourceSets.getByName("play").java.srcDir(file("src/installer-stub/java"))

    // This receiver is sideload-only. Fire OS 7 requires target API 28 for runtime compatibility,
    // so the Play Store-only target-age warning is intentionally not a release blocker here.
    lint {
        disable += "ExpiredTargetSdkVersion"
    }

    // CI provides the established compatibility keystore through this variable;
    // hosted runners relocate the default .android directory, so the debug
    // signing config takes an explicit path when one is supplied.
    providers.environmentVariable("TV_COMPAT_KEYSTORE_FILE").orNull?.let { path ->
        signingConfigs.getByName("debug").storeFile = file(path)
    }

    sourceSets.getByName("main").assets.srcDir(
        rootProject.file("../App/FourSevenEightNine/Resources/Fonts"),
    )
    sourceSets.getByName("main").assets.srcDir(generatedReceiverLicenseAssets)

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
    } else {
        // No upload key on this machine: a release build is still useful for measuring, and the
        // sideload channel ships under the compatibility (debug) certificate anyway.
        buildTypes.getByName("release").signingConfig = signingConfigs.getByName("debug")
    }

    buildTypes {
        getByName("release") {
            // Debuggable Compose runs with JIT-only code and extra tracing; on the onn 4K Pro a
            // debug build spent 13 ms of GPU and up to 45 ms of composition per frame in a row
            // sweep. R8 is on with the rules below; the media stack keeps its symbols.
            isMinifyEnabled = true
            isShrinkResources = true
            proguardFiles(getDefaultProguardFile("proguard-android-optimize.txt"), "proguard-rules.pro")
        }
    }
}

composeCompiler {
    // Data classes from `:client-data` and the contract module are immutable in practice; the
    // config marks them stable so a row does not recompose because its list parameter is a
    // new-but-equal object.
    stabilityConfigurationFiles.add(rootProject.layout.projectDirectory.file("compose_stability_config.conf"))
}

// The Play channel is Exo-only: libmpv needs FFmpeg 8 symbols while Media3's working decoder
// extension needs FFmpeg 6 under the same SONAMEs, so shipping mpv's native closure would ship an
// unselectable broken engine. Per-flavour packaging has no DSL block, so it is set per variant.
extensions.configure<ApplicationAndroidComponentsExtension> {
    // Sideload used to keep libmpv as a codec fallback. Those .so files need FFmpeg 8, and the
    // audio extension in this APK is FFmpeg 6 under the same names, so the fallback cannot load.
    // Both channels ship Exo only. That drops about 20 MB of native code from the download.
    onVariants(selector().all()) { variant ->
        variant.packaging.jniLibs.excludes.addAll(
            "lib/*/libmpv.so",
            "lib/*/libplayer.so",
            "lib/*/libavdevice.so",
            "lib/*/libavfilter.so",
            "lib/*/libavformat.so",
            "lib/*/libc++_shared.so",
        )
    }
}

tasks.named("preBuild").configure { dependsOn(syncReceiverLicenseAssets) }

kotlin {
    jvmToolchain(17)
    compilerOptions {
        jvmTarget.set(JvmTarget.JVM_17)
    }
}

dependencies {
    implementation(project(":contract"))
    implementation(project(":client-data"))
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
    implementation(libs.androidx.activity.compose)
    implementation(libs.androidx.lifecycle.runtime.compose)
    implementation(libs.androidx.lifecycle.viewmodel.compose)
    implementation(libs.androidx.navigation.compose)
    implementation(platform(libs.compose.bom))
    implementation(libs.compose.ui)
    implementation(libs.compose.foundation)
    implementation(libs.compose.animation)
    implementation(libs.compose.ui.tooling.preview)
    implementation(libs.tv.material)
    implementation(libs.androidx.tvprovider)
    implementation(libs.zxing.core)
    // Installs the baseline profile below at first run (plan §9). Without this the profile ships
    // in the APK and is never applied.
    implementation(libs.androidx.profileinstaller)
    baselineProfile(project(":baselineprofile"))

    debugImplementation(libs.compose.ui.tooling)

    testImplementation(libs.junit)
    testImplementation(libs.kotlinx.coroutines.test)
}
