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

android {
    namespace = "se.sensnology.spotnav"
    compileSdk = 36
    buildFeatures { buildConfig = true }
    bundle { language { enableSplit = false } }

    defaultConfig {
        applicationId = "se.sensnology.spotnav"
        minSdk = 26
        targetSdk = 36
        versionCode = 1
        versionName = "1.0.0"
        buildConfigField("String", "STORE", "\"$store\"")
    }

    sourceSets {
        getByName("main") {
            java.srcDir("src/$store/java")
            res.srcDir("src/$store/res")
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

kotlin { jvmToolchain(17) }

dependencies {
    testImplementation(libs.junit)
    testImplementation(libs.org.json)
}
