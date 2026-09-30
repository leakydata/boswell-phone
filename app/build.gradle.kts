plugins {
    alias(libs.plugins.android.application)
    alias(libs.plugins.kotlin.compose)
    alias(libs.plugins.kotlin.serialization)
}

android {
    // Lets a -Dparity.* property reach the parity test (see OpusParityTest).
    testOptions.unitTests.all { t -> System.getProperties().filterKeys { (it as String).startsWith("parity.") }.forEach { (k, v) -> t.systemProperty(k as String, v) } }

    namespace = "net.boswell.phone"
    // android-37.1 is what the SDK has installed; AndroidX 2026.09 needs 37.
    compileSdk {
        version = release(37) { minorApiLevel = 1 }
    }

    defaultConfig {
        applicationId = "net.boswell.phone"
        // 33: the Bluetooth permission split and the value-carrying GATT
        // callbacks are both there, so there is one code path, not two.
        minSdk = 33
        targetSdk = 36
        versionCode = 1
        versionName = "0.1.0"
        // The only ABI anything ships for. Models run on-device and a 32-bit
        // or x86 build would be a build nobody runs.
        ndk { abiFilters += listOf("arm64-v8a") }
    }

    buildTypes {
        debug {
            applicationIdSuffix = ".debug"
            versionNameSuffix = "-debug"
        }
        release {
            isMinifyEnabled = false
        }
    }

    compileOptions {
        sourceCompatibility = JavaVersion.VERSION_17
        targetCompatibility = JavaVersion.VERSION_17
    }

    buildFeatures { compose = true }

    testOptions { unitTests.isReturnDefaultValues = true }
}

kotlin {
    jvmToolchain(21)   // installed at /usr/lib/jvm; 17 is not
}

dependencies {
    implementation(libs.androidx.core.ktx)
    implementation(libs.androidx.activity.compose)
    implementation(libs.androidx.lifecycle.runtime.compose)
    implementation(libs.androidx.lifecycle.viewmodel.compose)
    implementation(libs.androidx.lifecycle.service)
    implementation(platform(libs.androidx.compose.bom))
    implementation(libs.androidx.compose.ui)
    implementation(libs.androidx.compose.material3)
    implementation(libs.androidx.compose.material.icons)
    implementation(libs.androidx.compose.ui.tooling.preview)
    debugImplementation(libs.androidx.compose.ui.tooling)
    implementation(libs.kotlinx.coroutines.android)
    implementation(libs.kotlinx.serialization.json)
    implementation(libs.concentus)

    testImplementation(libs.junit)
    testImplementation(libs.kotlinx.coroutines.test)
}
