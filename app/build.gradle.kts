import java.util.Properties

plugins {
    id("com.android.application")
    id("org.jetbrains.kotlin.android")
}

/**
 * Release signing credentials, read from `keystore.properties` in the repo root.
 *
 * That file and the keystore itself are gitignored and live outside the repo,
 * because losing the key is unrecoverable: Android identifies an app by its
 * signature, so a replacement key makes it a different app that cannot update
 * the installed one. See README, "Release builds".
 *
 * Absent on a fresh clone, and that is not an error - the file is deliberately
 * not distributed. The build stays configurable and produces an unsigned APK.
 */
val keystoreProperties = Properties().apply {
    val file = rootProject.file("keystore.properties")
    if (file.exists()) file.inputStream().use { load(it) }
}

android {
    // Rename point 2 of 3 (see settings.gradle.kts).
    namespace = "com.redcoralstudios.pocketllm"
    compileSdk = 34

    // r27 is the first NDK line that supports 16 KB memory pages, which Play
    // requires for apps targeting Android 15+. Gradle downloads it on demand.
    ndkVersion = "27.2.12479018"

    defaultConfig {
        applicationId = "com.redcoralstudios.pocketllm"
        minSdk = 29
        targetSdk = 34
        // Versioning rule: a feature is a minor bump (+0.1.0), a fix or a small
        // convenience is a patch (+0.0.1).
        versionCode = 19
        versionName = "0.4.3"

        ndk {
            // Every phone that can hold a 3 GB model in RAM is arm64. Shipping
            // only one ABI keeps the APK from doubling for no benefit.
            abiFilters += "arm64-v8a"
        }

        externalNativeBuild {
            cmake {
                arguments += listOf(
                    "-DANDROID_STL=c++_static",
                    "-DCMAKE_BUILD_TYPE=Release",
                )
                cppFlags += "-O3"
            }
        }
    }

    externalNativeBuild {
        cmake {
            path = file("src/main/cpp/CMakeLists.txt")
            version = "3.22.1"
        }
    }

    packaging {
        jniLibs {
            // llama.cpp is linked statically into libpocketllm.so, so there is
            // nothing to extract at install time.
            useLegacyPackaging = false
        }
    }

    signingConfigs {
        // Registered only when the credentials are present. Creating it
        // unconditionally would fail configuration on a clone with a null
        // storeFile, which reads as a broken build rather than a missing key.
        if (keystoreProperties.getProperty("storeFile") != null) {
            create("release") {
                storeFile = file(keystoreProperties.getProperty("storeFile"))
                storePassword = keystoreProperties.getProperty("storePassword")
                keyAlias = keystoreProperties.getProperty("keyAlias")
                keyPassword = keystoreProperties.getProperty("keyPassword")
                // Signature schemes are left to AGP, which picks them from
                // minSdk: at 29 that is v2 only, and v1 (JAR signing) is dead
                // weight no device on this floor still reads.
            }
        }
    }

    buildTypes {
        release {
            // Null when keystore.properties is absent, which leaves the old
            // behaviour: assembleRelease still runs and emits an unsigned APK.
            signingConfig = signingConfigs.findByName("release")
            isMinifyEnabled = false
            proguardFiles(getDefaultProguardFile("proguard-android-optimize.txt"), "proguard-rules.pro")
        }
        debug {
            // The native build is the slow part; don't also pay for a debug STL.
            isJniDebuggable = false
        }
    }

    // buildConfig stays OFF deliberately: enabling it generates BuildConfig.java,
    // which forces javac to run, which trips AGP 8.1.4's JdkImageTransform under
    // the JDK 21 bundled with Android Studio. The settings screen reads the
    // version from PackageManager instead, so nothing needs generating.
    buildFeatures { compose = true }
    composeOptions { kotlinCompilerExtensionVersion = "1.5.14" }

    compileOptions {
        sourceCompatibility = JavaVersion.VERSION_17
        targetCompatibility = JavaVersion.VERSION_17
    }
    kotlinOptions { jvmTarget = "17" }

    sourceSets {
        getByName("main").java.srcDirs("src/main/kotlin")
        getByName("test").java.srcDirs("src/test/kotlin")
    }

    lint { abortOnError = false }
    testOptions { unitTests.isReturnDefaultValues = true }
}

dependencies {
    implementation(platform("androidx.compose:compose-bom:2024.06.00"))
    implementation("androidx.compose.ui:ui")
    implementation("androidx.compose.ui:ui-tooling-preview")
    implementation("androidx.compose.material3:material3")
    implementation("androidx.compose.material:material-icons-extended")
    implementation("androidx.activity:activity-compose:1.9.0")
    implementation("androidx.lifecycle:lifecycle-runtime-compose:2.8.2")
    implementation("androidx.lifecycle:lifecycle-viewmodel-compose:2.8.2")
    implementation("androidx.datastore:datastore-preferences:1.1.1")
    implementation("org.jetbrains.kotlinx:kotlinx-coroutines-android:1.8.1")
    implementation("com.squareup.okhttp3:okhttp:4.12.0")

    debugImplementation("androidx.compose.ui:ui-tooling")

    testImplementation("junit:junit:4.13.2")
    // The real org.json, so the Hugging Face parser is tested rather than
    // stubbed: android.jar's version is method-stubs only under unit test, and
    // isReturnDefaultValues would make every assertion pass on nulls.
    testImplementation("org.json:json:20240303")
    testImplementation("org.jetbrains.kotlinx:kotlinx-coroutines-test:1.8.1")
}
