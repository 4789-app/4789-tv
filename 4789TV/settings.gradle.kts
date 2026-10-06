import org.gradle.api.initialization.resolve.RepositoriesMode

pluginManagement {
    repositories {
        google()
        mavenCentral()
    }
}

dependencyResolutionManagement {
    repositoriesMode.set(RepositoriesMode.FAIL_ON_PROJECT_REPOS)
    repositories {
        google()
        mavenCentral()
    }
}

rootProject.name = "FourSevenEightNineTV"
include(":app")
include(":contract")
include(":phone")
include(":client-data")
// Produces the baseline profile plan §9 requires. It is a `com.android.test` module: it ships in
// no APK, it only drives `:app` on a real box while the profile is recorded.
include(":baselineprofile")
