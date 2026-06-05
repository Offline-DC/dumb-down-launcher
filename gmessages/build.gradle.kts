plugins {
    alias(libs.plugins.android.library)
    alias(libs.plugins.kotlin.android)
    alias(libs.plugins.kotlin.compose)
}

android {
    namespace = "com.offlineinc.dumbdownlauncher.gmessages"
    compileSdk = 36
    defaultConfig { minSdk = 24 }

    buildFeatures { compose = true }

    compileOptions {
        sourceCompatibility = JavaVersion.VERSION_17
        targetCompatibility = JavaVersion.VERSION_17
    }
    kotlinOptions { jvmTarget = "17" }
}

dependencies {
    // The chat UI (Compose screens, MessageRepository contract). Pulled in
    // via composite build wiring in dumb-down-launcher/settings.gradle.kts.
    //
    // Why composite build instead of a maven dep: the UI lib is in active
    // co-development with this module and with dpad-messenger-backend. A
    // local source dependency means a change in dpad-messenger is picked
    // up immediately by both consumers without anyone publishing.
    api("com.offline.dpadmessenger:library:0.2.0")

    // EncryptedSharedPreferences for persisting the pairing tokens we get
    // back from the QR pairing flow with the user's primary phone.
    implementation("androidx.security:security-crypto:1.1.0-alpha06")

    // OkHttp for the relay WebSocket + RPC calls to Google's instantmessaging
    // backend. Same library Signal backend uses; keeps trust-handling simple.
    implementation("com.squareup.okhttp3:okhttp:4.12.0")

    // Coroutines for the receive loop + the suspending API surface that
    // MessageRepository expects.
    implementation("org.jetbrains.kotlinx:kotlinx-coroutines-android:1.7.3")

    implementation("androidx.core:core-ktx:1.12.0")

    // ZXing (pure-Java core) to ENCODE the pairing QR the user scans with
    // their primary phone. Same artifact :app uses for the WhatsApp QR
    // enlarge overlay.
    implementation("com.google.zxing:core:3.5.3")

    // X25519 for the pairing handshake is implemented in-repo (X25519.kt,
    // a pure-Kotlin TweetNaCl port) — no crypto dependency needed. We
    // previously pulled in org.signal:libsignal-android for this one
    // primitive, but it ships ~4MB of native code, forces core-library
    // desugaring on every consumer, and its Java records broke D8
    // ("Record desugaring without a global-synthetics consumer").

    // RFC 7748 test vectors for X25519.kt (X25519Test).
    testImplementation(libs.junit)
}

// TODO(Phase B.2): add protobuf-gradle-plugin block here once the .proto
// files from mautrix-gmessages have been copied into src/main/proto/. The
// dpad-messenger-backend/signal/build.gradle.kts has a working reference
// configuration for protobuf-javalite that we'll mirror.
