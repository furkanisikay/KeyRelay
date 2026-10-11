plugins {
    alias(libs.plugins.android.application)
    alias(libs.plugins.kotlin.compose)
}
android {
    namespace = "dev.keyrelay"
    compileSdk = 37
    ndkVersion = "30.0.16248370"
    defaultConfig {
        applicationId = "dev.keyrelay"
        minSdk = 30
        targetSdk = 36
        versionCode = 1
        versionName = "0.1.0"
        ndk { abiFilters += listOf("arm64-v8a") }
    }
    buildFeatures { compose = true; aidl = true }
    externalNativeBuild { cmake { path = file("src/main/cpp/CMakeLists.txt") } }
    // The helper runs under app_process and loads the .so by absolute path, so it must exist on disk.
    packaging {
        jniLibs { useLegacyPackaging = true }
        // bcprov/bcutil/bcpkix each ship the same license file.
        resources { pickFirsts += "META-INF/LICENSE.md" }
    }
    compileOptions {
        sourceCompatibility = JavaVersion.VERSION_21
        targetCompatibility = JavaVersion.VERSION_21
    }
    testOptions { unitTests.all { it.useJUnitPlatform() } }
}
dependencies {
    implementation(project(":core"))
    implementation(platform(libs.compose.bom))
    implementation(libs.compose.material3)
    implementation(libs.compose.icons.core)
    implementation(libs.compose.ui)
    implementation(libs.activity.compose)
    implementation(libs.navigation3.runtime)
    implementation(libs.navigation3.ui)
    implementation(libs.lifecycle.runtime.compose)
    implementation(libs.code.scanner)
    // libadb pulls bcprov-jdk15to18; bcpkix pulls bcprov-jdk18on with the same classes (duplicate class build error).
    implementation(libs.libadb) { exclude(group = "org.bouncycastle", module = "bcprov-jdk15to18") }
    implementation(libs.conscrypt)
    implementation(libs.bcpkix)
    implementation(libs.coroutines.android)
    implementation(libs.datastore.preferences)
    testImplementation(libs.junit.jupiter)
    testRuntimeOnly(libs.junit.platform.launcher)
}
