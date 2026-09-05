plugins {
    `java-library`
    alias(libs.plugins.kotlin.jvm)
    alias(libs.plugins.kotlin.serialization)
}

kotlin {
    jvmToolchain(17)
}

sourceSets {
    test {
        resources.srcDir(rootProject.file("../docs/contract-samples"))
    }
}

dependencies {
    api(libs.kotlinx.serialization.json)
    api(libs.tink)
    testImplementation(libs.junit)
}
