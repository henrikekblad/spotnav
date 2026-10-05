plugins {
    alias(libs.plugins.android.application)
    alias(libs.plugins.kotlin.android)
}

val releaseSigningValues = mapOf(
    "storePath" to System.getenv("SIGNING_STORE_PATH"),
    "storePassword" to System.getenv("SIGNING_STORE_PASSWORD"),
    "keyAlias" to System.getenv("SIGNING_KEY_ALIAS"),
    "keyPassword" to System.getenv("SIGNING_KEY_PASSWORD")
)
val hasReleaseSigning = releaseSigningValues.values.all { !it.isNullOrBlank() }

// The store this build targets: -Pstore=github (default) or -Pstore=play. It picks the extra
// source directory app/src/<store> (code, resources and tests that differ per store) and sets
// BuildConfig.STORE. A property rather than product flavors keeps the task names (assembleRelease,
// testDebugUnitTest, ...) and output paths unchanged.
val store = (findProperty("store") as String?) ?: "github"
require(store in setOf("github", "play")) { "-Pstore must be github or play, was '$store'" }

// The version comes from the release tag: CI passes -PreleaseVersion=1.2.3 (the tag without its
// "v"). versionCode is derived from it as major*10000 + minor*100 + patch, so it always grows with
// the version and never has to be edited by hand. Local builds are 1.0.0-dev / 1.
val releaseVersion = findProperty("releaseVersion") as String?
val versionParts = releaseVersion?.let { version ->
    val match = Regex("""(\d+)\.(\d+)\.(\d+)""").matchEntire(version)
        ?: error("-PreleaseVersion must be major.minor.patch, was '$version'")
    match.groupValues.drop(1).map { it.toInt() }.also { (_, minor, patch) ->
        require(minor < 100 && patch < 100) { "minor and patch must stay below 100: $version" }
    }
}
// Instant notifications (a content-free Firebase Cloud Messaging wake-up through SpotNav Relay) are
// in a build only when app/google-services.json is there. That file is never committed, so CI and
// every other build without it contain no Firebase at all: the sources come from app/src/nopush
// instead of app/src/push, and neither the google-services plugin nor firebase-messaging is applied.
// -Ppush=false leaves push out even with the file; -Ppush=true insists on it. BuildConfig.PUSH_AVAILABLE
// says which one this is.
val pushConfig = file("google-services.json")
val push = when (val flag = findProperty("push") as String?) {
    null -> pushConfig.exists()
    "true" -> true.also { require(pushConfig.exists()) { "-Ppush=true needs app/google-services.json" } }
    "false" -> false
    else -> error("-Ppush must be true or false, was '$flag'")
}
if (push) apply(plugin = "com.google.gms.google-services")

val appVersionName = releaseVersion ?: "1.0.0-dev"
val appVersionCode = versionParts?.let { (major, minor, patch) -> major * 10000 + minor * 100 + patch } ?: 1

android {
    namespace = "se.sensnology.spotnav"
    compileSdk = 36
    buildFeatures { buildConfig = true }
    bundle { language { enableSplit = false } }

    defaultConfig {
        applicationId = "se.sensnology.spotnav"
        minSdk = 26
        targetSdk = 36
        versionCode = appVersionCode
        versionName = appVersionName
        buildConfigField("String", "STORE", "\"$store\"")
        buildConfigField("boolean", "PUSH_AVAILABLE", push.toString())
    }

    sourceSets {
        getByName("main") {
            java.srcDir("src/$store/java")
            res.srcDir("src/$store/res")
            java.srcDir(if (push) "src/push/java" else "src/nopush/java")
        }
        getByName("test") { java.srcDir("src/$store/test/java") }
    }

    if (hasReleaseSigning) {
        signingConfigs {
            create("release") {
                storeFile = file(releaseSigningValues.getValue("storePath")!!)
                storePassword = releaseSigningValues.getValue("storePassword")
                keyAlias = releaseSigningValues.getValue("keyAlias")
                keyPassword = releaseSigningValues.getValue("keyPassword")
            }
        }
    }

    lint {
        abortOnError = true
        // Tool-version advisories change without any change here, and would fail CI by the calendar.
        disable += setOf("AndroidGradlePluginVersion", "NewerVersionAvailable", "GradleDependency")
        // core-ktx is on the classpath only through the push build's AndroidX; the app does not use it.
        disable += "UseKtx"
    }

    buildTypes {
        getByName("release") {
            isMinifyEnabled = true
            isShrinkResources = true
            proguardFiles(getDefaultProguardFile("proguard-android-optimize.txt"), "proguard-rules.pro")
            if (hasReleaseSigning) signingConfig = signingConfigs.getByName("release")
        }
    }
}

// The Firebase messaging service is declared only in a build with push.
androidComponents {
    onVariants { variant ->
        if (push) variant.sources.manifests.addStaticManifestFile("src/push/AndroidManifest.xml")
    }
}

kotlin { jvmToolchain(17) }

dependencies {
    if (push) {
        implementation(platform(libs.firebase.bom))
        implementation(libs.firebase.messaging)
        constraints {
            implementation(libs.androidx.fragment) { because("Play flags Firebase's fragment 1.1.0 as outdated") }
            implementation(libs.androidx.activity) { because("Play flags Firebase's activity 1.0.0 as outdated") }
        }
    }
    testImplementation(libs.junit)
    testImplementation(libs.org.json)
}
