import org.jetbrains.kotlin.gradle.dsl.JvmTarget

plugins {
    alias(libs.plugins.android.application)
    alias(libs.plugins.kotlin.android)
    alias(libs.plugins.compose.compiler)
}

android {
    namespace = "com.fourseveneightnine.phone"
    compileSdk = 36

    defaultConfig {
        applicationId = "com.fourseveneightnine.phone"
        minSdk = 26
        targetSdk = 36
        versionCode = 1
        versionName = "0.1.0-foundation"
        testInstrumentationRunner = "androidx.test.runner.AndroidJUnitRunner"
    }

    buildFeatures {
        compose = true
    }

    sourceSets {
        getByName("main") {
            assets.srcDir(rootProject.file("../docs/contract-samples"))
        }
    }

    compileOptions {
        sourceCompatibility = JavaVersion.VERSION_17
        targetCompatibility = JavaVersion.VERSION_17
    }

    lint {
        warningsAsErrors = true
        abortOnError = true
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

kotlin {
    jvmToolchain(17)
    compilerOptions {
        jvmTarget.set(JvmTarget.JVM_17)
    }
}

dependencies {
    implementation(project(":contract"))
    implementation(libs.androidx.activity.compose)
    implementation(libs.androidx.lifecycle.runtime.compose)
    implementation(libs.androidx.navigation.compose)
    implementation(libs.androidx.work.runtime)
    implementation(platform(libs.compose.bom))
    implementation(libs.compose.material3)
    implementation(libs.compose.ui)
    implementation(libs.compose.ui.tooling.preview)
    implementation(libs.coil.compose)
    implementation(libs.media3.exoplayer)
    implementation(libs.media3.ui)
    implementation(libs.okhttp)
    debugImplementation(libs.compose.ui.tooling)
    debugImplementation(libs.compose.ui.test.manifest)
    androidTestImplementation(platform(libs.compose.bom))
    androidTestImplementation(libs.compose.ui.test.junit4)
    androidTestImplementation(libs.androidx.test.runner)
    testImplementation(libs.junit)
    testImplementation(libs.kotlinx.coroutines.test)
}
