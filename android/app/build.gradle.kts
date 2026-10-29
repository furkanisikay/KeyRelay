plugins {
    alias(libs.plugins.android.application)
    alias(libs.plugins.kotlin.compose)
}
// version.txt (repo root) is the single version source; release-please bumps it.
val appVersion = providers.fileContents(rootProject.layout.projectDirectory.file("../version.txt")).asText.get().trim()
val (vMajor, vMinor, vPatch) = appVersion.split(".").map(String::toInt)
require(vMinor < 100 && vPatch < 100) { "versionCode = major*10000 + minor*100 + patch needs minor and patch < 100" }
// Android only installs an update signed with the same key (else INSTALL_FAILED_UPDATE_INCOMPATIBLE), so shipped APKs
// use one shared key from these variables; without them the build falls back to this machine's own debug key.
val signingKeystore = providers.environmentVariable("KEYRELAY_KEYSTORE").orNull
val signingPassword = providers.environmentVariable("KEYRELAY_KEYSTORE_PASSWORD").orNull
require(signingKeystore == null || !signingPassword.isNullOrEmpty()) { "KEYRELAY_KEYSTORE is set but KEYRELAY_KEYSTORE_PASSWORD is not" }
android {
    namespace = "dev.keyrelay"
    compileSdk = 37
    ndkVersion = "30.0.16248370"
    defaultConfig {
        applicationId = "dev.keyrelay"
        minSdk = 30
        targetSdk = 36
        versionCode = vMajor * 10000 + vMinor * 100 + vPatch
        versionName = appVersion
        ndk { abiFilters += listOf("arm64-v8a") }
    }
    if (signingKeystore != null) {
        // PKCS12 keystores use the store password for the key as well.
        val shared = signingConfigs.create("shared") {
            storeFile = file(signingKeystore)
            storePassword = signingPassword
            keyAlias = "keyrelay"
            keyPassword = signingPassword
        }
        // Releases ship the debug build, so this is the build type that has to carry the shared key.
        buildTypes.getByName("debug").signingConfig = shared
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
