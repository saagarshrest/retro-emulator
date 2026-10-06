import java.util.Properties

plugins {
    id("com.android.application")
}

// Release signing is read from keystore.properties (git-ignored). When it is
// absent the release build falls back to the debug key so it still installs.
val keystoreProps = Properties().apply {
    val f = rootProject.file("keystore.properties")
    if (f.exists()) f.inputStream().use { load(it) }
}

android {
    namespace = "com.retroemulator.gb"
    compileSdk = 36

    defaultConfig {
        applicationId = "com.retroemulator.gb"
        minSdk = 26
        targetSdk = 36
        versionCode = 2
        versionName = "1.1.0"
    }

    signingConfigs {
        create("release") {
            if (keystoreProps.isNotEmpty()) {
                storeFile = rootProject.file(keystoreProps.getProperty("storeFile"))
                storePassword = keystoreProps.getProperty("storePassword")
                keyAlias = keystoreProps.getProperty("keyAlias")
                keyPassword = keystoreProps.getProperty("keyPassword")
            }
        }
    }

    buildTypes {
        release {
            isMinifyEnabled = true
            isShrinkResources = true
            proguardFiles(getDefaultProguardFile("proguard-android-optimize.txt"), "proguard-rules.pro")
            signingConfig = if (keystoreProps.isNotEmpty()) {
                signingConfigs.getByName("release")
            } else {
                signingConfigs.getByName("debug")
            }
        }
    }

    sourceSets {
        getByName("main") {
            // ROMs dropped into <project>/games are packaged into the APK and show up in the
            // game list as "Included" games (see games/README.md).
            assets.directories.add(rootProject.file("games").path)
        }
    }

    compileOptions {
        sourceCompatibility = JavaVersion.VERSION_17
        targetCompatibility = JavaVersion.VERSION_17
    }

    testOptions {
        unitTests.all {
            it.maxHeapSize = "1g"
            // Directory holding the public-domain test ROM suites (see README).
            it.systemProperty("testroms.dir", rootProject.file("testroms").absolutePath)
            it.systemProperty("testout.dir", layout.buildDirectory.dir("test-screens").get().asFile.absolutePath)
            it.testLogging { events("passed", "skipped", "failed"); showStandardStreams = false }
        }
    }
}

dependencies {
    testImplementation("junit:junit:4.13.2")
}
