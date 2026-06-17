import org.jetbrains.kotlin.gradle.dsl.JvmTarget

plugins {
    alias(libs.plugins.android.application)
    alias(libs.plugins.kotlin.android)
    alias(libs.plugins.kotlin.compose)
}

android {
    namespace = "com.offlineinc.dumbdownlauncher"
    compileSdk = 36
    buildToolsVersion = "35.0.0"

    defaultConfig {
        applicationId = "com.offlineinc.dumbdownlauncher"
        minSdk = 24
        targetSdk = 36
        versionCode = 197
        versionName = "v5.8.0-beta.5"
        testInstrumentationRunner = "androidx.test.runner.AndroidJUnitRunner"

        // Battery diagnostics — compile-time gate for the DiagnosticsService,
        // DiagnosticsActivity, and the long-press-on-quack hook in AllAppsActivity.
        // Production builds set this false so the privileged dumpsys / logcat
        // collection code is excluded entirely; the diag beta build (see
        // battery-diagnostics-plan.md) flips it to true.
        buildConfigField("boolean", "DIAGNOSTICS_ENABLED", "true")
    }

    signingConfigs {
        create("release") {
            val keystorePath = System.getenv("KEYSTORE_PATH")
            if (keystorePath != null) {
                storeFile = file(keystorePath)
                storePassword = System.getenv("KEYSTORE_PASSWORD")
                keyAlias = System.getenv("KEY_ALIAS")
                keyPassword = System.getenv("KEY_PASSWORD")
            }
        }
    }

    buildTypes {
        release {
            isMinifyEnabled = true
            isShrinkResources = true
            proguardFiles(
                getDefaultProguardFile("proguard-android-optimize.txt"),
                "proguard-rules.pro"
            )
            signingConfig = signingConfigs.getByName("release")
        }
    }

    compileOptions {
        // Java 17 to match the dpad-messenger-backend modules (:gmessages and
        // friends all compile at Java 17), mirroring the backend's own demo app.
        sourceCompatibility = JavaVersion.VERSION_17
        targetCompatibility = JavaVersion.VERSION_17
    }

    buildFeatures {
        compose = true
        buildConfig = true
    }
}

kotlin {
    compilerOptions {
        jvmTarget.set(JvmTarget.JVM_17)
    }
}

dependencies {
    implementation(libs.androidx.core.ktx)
    implementation("androidx.browser:browser:1.8.0")
    implementation(libs.androidx.appcompat)
    implementation(libs.material)
    implementation(libs.androidx.activity)
    implementation(libs.androidx.constraintlayout)

    // In-app Google Messages messenger: backend + pairing/chat UI. Lives in
    // the matrix-app repo as dpad-messenger-backend's :gmessages module,
    // pulled in via the composite-build wiring in settings.gradle.kts (the
    // coordinate below is substituted for that project). It api-exposes the
    // dpad-messenger UI library, so MessengerActivity gets DpadMessengerTheme
    // + GoogleMessagesApp transitively. MessengerActivity (in this :app
    // module) is the launcher-specific shell opened when the user picks
    // "android" as their smart-txt platform — replacing the previous "open
    // messages.google.com/web in Chrome" behaviour.
    implementation("com.offline.dpadmessenger.backend:gmessages:0.1.0-SNAPSHOT")

    // Compose
    implementation(platform(libs.androidx.compose.bom))
    implementation("androidx.compose.material:material-icons-extended")
    implementation("androidx.compose.material3:material3")
    implementation(libs.androidx.compose.ui)
    implementation(libs.androidx.compose.ui.tooling.preview)
    implementation(libs.androidx.compose.foundation)
    implementation(libs.androidx.activity.compose)
    implementation(libs.androidx.compose.runtime.livedata)
    implementation(libs.androidx.ui)
    debugImplementation(libs.androidx.compose.ui.tooling)

    implementation("androidx.lifecycle:lifecycle-viewmodel-compose:2.8.7")
    implementation("androidx.work:work-runtime-ktx:2.9.0")

    // OkHttp (WebSocket client for Type Sync)
    implementation("com.squareup.okhttp3:okhttp:4.12.0")

    // libphonenumber — used by DeviceRegistrar.normalizePhone to produce
    // canonical E.164 phone numbers before sending to /api/v1/register.
    // Replaces a NANP-only home-rolled normalizer that stripped digits and
    // assumed US 10/11-digit shapes. Backend (post-phone-normalize migration)
    // expects E.164 at every entry point.
    implementation("com.googlecode.libphonenumber:libphonenumber:8.13.46")

    // ZXing — decodes the small QR that WhatsApp renders on the companion-
    // mode "Link as companion device" page (240x320 phones can't fit the QR
    // at a scannable size), then re-encodes it at max resolution for the
    // QrEnlargeOverlay so another phone's camera can read it. Pure-Java
    // core lib only; no android-specific zxing dep needed because we do our
    // own Bitmap <-> LuminanceSource bridging.
    implementation("com.google.zxing:core:3.5.3")

    testImplementation(libs.junit)
    androidTestImplementation(libs.androidx.junit)
    androidTestImplementation(libs.androidx.espresso.core)
}
