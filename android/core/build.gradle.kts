plugins {
    alias(libs.plugins.kotlin.jvm)
    alias(libs.plugins.kotlin.serialization)
    alias(libs.plugins.ktlint)
    alias(libs.plugins.detekt)
    alias(libs.plugins.animalsniffer)
}

// This module is pure JVM by design (fast JVM tests) but ships inside an
// Android app with minSdk 26. Android lint cannot apply API-level checks to
// a pure-JVM module, so Animal Sniffer checks the compiled bytecode against
// the gummy-bears Android API 26 signature (D8-desugaring-aware) instead:
// post-minSdk java.*/android.* calls — like the API 33-only URLDecoder
// Charset overload behind issue #12 — fail the build instead of reaching
// devices as a NoSuchMethodError.

java {
    sourceCompatibility = JavaVersion.VERSION_17
    targetCompatibility = JavaVersion.VERSION_17
}

kotlin {
    jvmToolchain(17)
}

dependencies {
    implementation(libs.kotlinx.serialization.json)

    // Animal Sniffer signature for the app's minSdk runtime (API 26),
    // accounting for D8 desugaring; see the comment above the java block.
    add("signature", "com.toasttab.android:gummy-bears-api-26:0.14.0@signature")

    testImplementation(libs.junit)
}

detekt {
    buildUponDefaultConfig = true
    config.setFrom(files("$rootDir/config/detekt/detekt.yml"))
}
