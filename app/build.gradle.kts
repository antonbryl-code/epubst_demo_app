import org.gradle.process.ExecOperations
import javax.inject.Inject

plugins {
    alias(libs.plugins.android.application)
    alias(libs.plugins.kotlin.compose)
}

// The ABIs the native Typst library is built for (see the cargoNdkBuild* tasks below), and so
// the only ones the APK supports. Override for faster local builds, e.g. -PrustAbis=arm64-v8a
val rustAbis = providers.gradleProperty("rustAbis")
    .orElse("arm64-v8a,armeabi-v7a,x86_64")
    .map { abis -> abis.split(',').map(String::trim).filter(String::isNotEmpty) }

// Google Play upload key for release builds, kept out of the project: set these Gradle properties
// in ~/.gradle/gradle.properties (or pass them with -P). Without them, release builds are signed
// with the debug key (see buildTypes below), which Google Play rejects.
val uploadKey = listOf("storeFile", "storePassword", "keyAlias", "keyPassword")
    .associateWith { providers.gradleProperty("epubst.upload.$it").orNull }
    .takeIf { key -> key.values.any { it != null } }
    ?.also { key ->
        val missing = key.filterValues { it.isNullOrEmpty() }.keys
        require(missing.isEmpty()) {
            "Release signing: missing " + missing.joinToString { "epubst.upload.$it" }
        }
    }

android {
    namespace = "com.example.epubstdemo"
    // Used by cargo-ndk (see cargoNdkBuild* tasks) and for stripping native libs.
    ndkVersion = "27.2.12479018"
    compileSdk {
        version = release(37)
    }

    defaultConfig {
        applicationId = "com.example.epubstdemo"
        minSdk = 24
        targetSdk = 37
        versionCode = 1
        versionName = "1.0"

        testInstrumentationRunner = "androidx.test.runner.AndroidJUnitRunner"

        ndk {
            // Without this, libraries from dependencies would add ABIs (e.g. x86) that lack the
            // Typst library, so the app would install there and then crash when loading it.
            abiFilters += rustAbis.get()
        }
    }

    signingConfigs {
        if (uploadKey != null) {
            create("upload") {
                storeFile = file(uploadKey.getValue("storeFile")!!)
                storePassword = uploadKey.getValue("storePassword")
                keyAlias = uploadKey.getValue("keyAlias")
                keyPassword = uploadKey.getValue("keyPassword")
            }
        }
    }

    buildTypes {
        release {
            optimization {
                enable = false
            }
            // Without an upload key, sign with this machine's debug key so the APK can be
            // installed for demos (it then can't update an install built on another machine).
            signingConfig = signingConfigs.findByName("upload") ?: signingConfigs.getByName("debug")
        }
    }
    compileOptions {
        sourceCompatibility = JavaVersion.VERSION_11
        targetCompatibility = JavaVersion.VERSION_11
    }
    buildFeatures {
        compose = true
    }
}

dependencies {
    implementation(platform(libs.androidx.compose.bom))
    implementation(libs.androidx.activity.compose)
    implementation(libs.androidx.compose.material3)
    implementation(libs.androidx.compose.ui)
    implementation(libs.androidx.compose.ui.graphics)
    implementation(libs.androidx.compose.ui.tooling.preview)
    implementation(libs.androidx.core.ktx)
    implementation(libs.androidx.lifecycle.runtime.ktx)
    testImplementation(libs.junit)
    androidTestImplementation(platform(libs.androidx.compose.bom))
    androidTestImplementation(libs.androidx.compose.ui.test.junit4)
    androidTestImplementation(libs.androidx.espresso.core)
    androidTestImplementation(libs.androidx.junit)
    debugImplementation(libs.androidx.compose.ui.test.manifest)
    debugImplementation(libs.androidx.compose.ui.tooling)
}

// Native Typst bridge: builds app/src/main/rust with cargo-ndk and packages the .so files.
val cargoExecutable = providers.systemProperty("user.home").map { home ->
    // Android Studio's Gradle daemon often lacks ~/.cargo/bin on PATH.
    val exe = if (System.getProperty("os.name").startsWith("Windows")) "cargo.exe" else "cargo"
    File(home, ".cargo/bin/$exe").takeIf { it.isFile }?.absolutePath ?: "cargo"
}

androidComponents {
    onVariants { variant ->
        val taskName = "cargoNdkBuild" + variant.name.replaceFirstChar(Char::titlecase)
        val cargoBuild = tasks.register<CargoNdkBuild>(taskName) {
            crateDir.set(layout.projectDirectory.dir("src/main/rust"))
            crateSources.from(fileTree("src/main/rust") { exclude("target/**") })
            cargoTargetDir.set(layout.buildDirectory.dir("rust-target"))
            ndkDir.set(androidComponents.sdkComponents.ndkDirectory)
            cargo.set(cargoExecutable)
            abis.set(rustAbis)
            minSdk.set(variant.minSdk.apiLevel)
            // See Cargo.toml: fast-rebuilding profile for debug builds, full LTO otherwise.
            cargoProfile.set(if (variant.buildType == "debug") "android-dev" else "release")
        }
        variant.sources.jniLibs?.addGeneratedSourceDirectory(cargoBuild, CargoNdkBuild::outputDir)
    }
}

/** Runs `cargo ndk ... build --profile <cargoProfile>`, writing `<outputDir>/<abi>/lib*.so`. */
abstract class CargoNdkBuild : DefaultTask() {
    @get:InputFiles
    @get:PathSensitive(PathSensitivity.RELATIVE)
    abstract val crateSources: ConfigurableFileCollection

    @get:Internal
    abstract val crateDir: DirectoryProperty

    @get:Internal
    abstract val cargoTargetDir: DirectoryProperty

    @get:Internal
    abstract val ndkDir: DirectoryProperty

    @get:Input
    abstract val cargo: Property<String>

    @get:Input
    abstract val abis: ListProperty<String>

    @get:Input
    abstract val minSdk: Property<Int>

    @get:Input
    abstract val cargoProfile: Property<String>

    @get:OutputDirectory
    abstract val outputDir: DirectoryProperty

    @get:Inject
    abstract val execOperations: ExecOperations

    @TaskAction
    fun build() {
        val out = outputDir.get().asFile
        out.deleteRecursively() // drop ABIs that are no longer requested
        execOperations.exec {
            workingDir = crateDir.get().asFile
            environment("ANDROID_NDK_HOME", ndkDir.get().asFile.absolutePath)
            environment("CARGO_TARGET_DIR", cargoTargetDir.get().asFile.absolutePath)
            commandLine(buildList {
                add(cargo.get())
                add("ndk")
                abis.get().forEach { add("-t"); add(it) }
                add("--platform"); add(minSdk.get().toString())
                add("-o"); add(out.absolutePath)
                add("build"); add("--profile"); add(cargoProfile.get())
            })
        }
    }
}