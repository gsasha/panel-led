// versionName is the only input; versionCode is derived (same scheme as portico-app):
// MAJOR*1_000_000 + MINOR*10_000 + PATCH*100 + stage rank (alpha 10, beta 30, rc 50, final 90).
val panelLedVersionName = "0.2.1-alpha"

fun versionCodeOf(name: String): Int {
    val m = Regex("""(\d+)\.(\d{1,2})\.(\d{1,2})(?:-(alpha|beta|rc)(?:\.(\d))?)?""").matchEntire(name)
        ?: throw GradleException("versionName '$name' is not MAJOR.MINOR.PATCH[-alpha|beta|rc[.N]]")
    val (major, minor, patch, stage, n) = m.destructured
    val rank = when (stage) { "alpha" -> 10; "beta" -> 30; "rc" -> 50; else -> 90 }
    return major.toInt() * 1_000_000 + minor.toInt() * 10_000 + patch.toInt() * 100 + rank + (n.toIntOrNull() ?: 0)
}

plugins {
    id("com.android.application")
}

android {
    namespace = "app.panelled"
    compileSdk = 34
    ndkVersion = "26.3.11579264"

    defaultConfig {
        applicationId = "app.panelled"
        minSdk = 26
        targetSdk = 34
        versionName = panelLedVersionName
        versionCode = versionCodeOf(panelLedVersionName)
        // The panels are arm64 (RK3576); one ABI keeps the APK small.
        ndk { abiFilters += "arm64-v8a" }
    }

    externalNativeBuild {
        ndkBuild { path = file("src/main/jni/Android.mk") }
    }

    // Explicit rather than relying on AGP's default: the AIDL interface (IPanelControl, under
    // src/main/aidl) is this app's whole external API, so its build feature must not silently
    // turn off if a future AGP version changes its default.
    buildFeatures {
        aidl = true
    }

    compileOptions {
        sourceCompatibility = JavaVersion.VERSION_17
        targetCompatibility = JavaVersion.VERSION_17
    }
}
