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
        versionCode = 3
        versionName = "1.2.0"
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

/**
 * Copies the Game Boy / Game Boy Color ROMs from <project>/games into a generated assets folder, so
 * they ship in the APK and show up as "Included" games (see games/README.md). Other files, such as
 * Game Boy Advance ROMs, are left out.
 */
abstract class BundleGamesTask : DefaultTask() {
    @get:InputFiles
    @get:PathSensitive(PathSensitivity.NAME_ONLY)
    abstract val roms: ConfigurableFileCollection

    @get:OutputDirectory
    abstract val outputDir: DirectoryProperty

    @TaskAction
    fun copyRoms() {
        val out = outputDir.get().asFile
        out.deleteRecursively()
        out.mkdirs()
        roms.files.forEach { it.copyTo(File(out, it.name), overwrite = true) }
    }
}

val bundleGames = tasks.register<BundleGamesTask>("bundleGames") {
    roms.from(fileTree(rootProject.file("games")) { include("*.gb", "*.gbc", "*.cgb", "*.sgb", "*.zip") })
}

androidComponents {
    onVariants { variant ->
        variant.sources.assets?.addGeneratedSourceDirectory(bundleGames, BundleGamesTask::outputDir)
    }
}

dependencies {
    testImplementation("junit:junit:4.13.2")
}
