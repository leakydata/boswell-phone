import java.io.File
import java.net.URI
import java.security.MessageDigest

plugins {
    alias(libs.plugins.android.application)
    alias(libs.plugins.kotlin.compose)
    alias(libs.plugins.kotlin.serialization)
}

android {
    // Lets a -Dparity.* property reach the parity test (see OpusParityTest).
    testOptions.unitTests.all { t -> System.getProperties().filterKeys { (it as String).startsWith("parity.") || (it as String).startsWith("diar.") || (it as String).startsWith("llm.") }.forEach { (k, v) -> t.systemProperty(k as String, v) } }

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
        versionCode = 6
        versionName = "0.2.2"
        // The only ABI anything ships for. Models run on-device and a 32-bit
        // or x86 build would be a build nobody runs.
        ndk { abiFilters += listOf("arm64-v8a") }
    }

    // Release signing: the keystore and its passwords live outside the repo, in
    // ~/.gradle/gradle.properties (BOSWELL_KEYSTORE, BOSWELL_KEY_ALIAS,
    // BOSWELL_STORE_PASSWORD, BOSWELL_KEY_PASSWORD). Without them a release
    // build is simply unsigned. Keep the keystore safe: an installed app only
    // accepts updates signed with the same key.
    signingConfigs {
        val ks = providers.gradleProperty("BOSWELL_KEYSTORE").orNull
        if (ks != null) create("release") {
            storeFile = File(ks)
            storePassword = providers.gradleProperty("BOSWELL_STORE_PASSWORD").get()
            keyAlias = providers.gradleProperty("BOSWELL_KEY_ALIAS").get()
            keyPassword = providers.gradleProperty("BOSWELL_KEY_PASSWORD").get()
        }
    }

    buildTypes {
        debug {
            applicationIdSuffix = ".debug"
            versionNameSuffix = "-debug"
        }
        release {
            // R8: the app's own code and libraries were 32 MB of uncompressed dex.
            // Keep rules for the native engines' JNI are in proguard-rules.pro.
            isMinifyEnabled = true
            isShrinkResources = true
            proguardFiles(getDefaultProguardFile("proguard-android-optimize.txt"), "proguard-rules.pro")
            signingConfig = signingConfigs.findByName("release")
        }
    }

    compileOptions {
        sourceCompatibility = JavaVersion.VERSION_17
        targetCompatibility = JavaVersion.VERSION_17
    }

    buildFeatures { compose = true }

    // arm64 only (see abiFilters). sherpa's x86 build carries its own
    // libonnxruntime.so, which would collide with onnxruntime-android's.
    packaging {
        jniLibs { excludes += listOf("lib/x86/**", "lib/x86_64/**", "lib/armeabi-v7a/**") }
        // The mail libraries each carry the same license notices.
        resources { pickFirsts += listOf("META-INF/NOTICE.md", "META-INF/LICENSE.md") }
    }

    testOptions { unitTests.isReturnDefaultValues = true }
}

// sherpa-onnx (on-device ASR). The static-link build has ONNX Runtime compiled
// into its own JNI library, so it sits beside onnxruntime-android (used for
// segmentation and voiceprints) without two libonnxruntime.so colliding.
// Fetched on first build and checked against a pinned hash; never committed.
val sherpaVersion = "1.13.8"
val sherpaSha256 = "b22c3fc1b6a45666d28892bb2f7694beeb77a8362d7ebd77c1a5431ec9435471"
val sherpaAar = layout.projectDirectory.file("libs/sherpa-onnx-static-link-onnxruntime-$sherpaVersion.aar").asFile
val fetchSherpa = tasks.register("fetchSherpa") {
    val out = sherpaAar
    val url = "https://github.com/k2-fsa/sherpa-onnx/releases/download/v$sherpaVersion/${out.name}"
    val want = sherpaSha256
    outputs.file(out)
    doLast {
        fun sha(f: File) = MessageDigest.getInstance("SHA-256")
            .digest(f.readBytes()).joinToString("") { "%02x".format(it) }
        if (out.exists() && sha(out) == want) return@doLast
        out.parentFile.mkdirs()
        val tmp = File(out.path + ".part")
        URI(url).toURL().openStream().use { i -> tmp.outputStream().use { i.copyTo(it) } }
        val got = sha(tmp)
        check(got == want) { "sherpa-onnx AAR hash mismatch: $got" }
        tmp.renameTo(out)
    }
}
tasks.named("preBuild") { dependsOn(fetchSherpa) }

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
    implementation(files(sherpaAar))
    implementation(libs.onnxruntime.android)
    implementation(libs.androidx.work.runtime)
    implementation(libs.androidx.navigation.compose)
    implementation(libs.androidx.media3.exoplayer)
    // Email over IMAP/SMTP with an app password (any provider, no Google project needed).
    implementation(libs.android.mail)
    implementation(libs.android.activation)
    // Scanning the home server's pairing QR code (Google's scanner: no camera permission for the app).
    implementation(libs.code.scanner)
    // The scanner pulls an old Fragment that breaks registerForActivityResult; pin a current one.
    implementation(libs.androidx.fragment)

    testImplementation(libs.junit)
    testImplementation(libs.kotlinx.coroutines.test)
    testImplementation(libs.onnxruntime.jvm)
}

// Unit tests run on the desktop JVM: use ONNX Runtime's desktop build there,
// not the Android one, whose native libraries cannot load on x86_64 Linux.
configurations.matching { it.name.endsWith("UnitTestRuntimeClasspath") }.configureEach {
    exclude(group = "com.microsoft.onnxruntime", module = "onnxruntime-android")
}
