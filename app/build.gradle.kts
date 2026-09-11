@file:Suppress("UnstableApiUsage")

plugins {
    alias(libs.plugins.android.application)
    alias(libs.plugins.kotlin.compose)
    alias(libs.plugins.kotlin.ksp)
    alias(libs.plugins.kotlin.serialization)
    alias(libs.plugins.apk.dist)
    alias(libs.plugins.kotlin.parcelize)
}

kotlin {
    jvmToolchain(21)
    compilerOptions {
        optIn.set(
            listOf(
                "androidx.compose.material3.ExperimentalMaterial3Api",
                "androidx.compose.material3.ExperimentalMaterial3ExpressiveApi",
                "androidx.compose.foundation.ExperimentalFoundationApi",
                "androidx.compose.foundation.layout.ExperimentalLayoutApi"
            )
        )
    }
}

ksp {
    // Room needs somewhere to put the schema it exports. Without this, exportSchema = true
    // was inert: nothing described the database, so no migration could be written against
    // it and every version bump had to fall back to wiping the data.
    arg("room.schemaLocation", "$projectDir/schemas")
}

android {
    compileSdk = 37

    defaultConfig {
        applicationId = "org.mlm.batstats"
        minSdk = 26
        targetSdk = 37
        versionCode = 694
        versionName = "6.2.2"

        androidResources {
            localeFilters += setOf("en", "ar", "de", "es-rES", "es-rUS", "fr", "hr", "hu", "in", "it", "ja", "pl", "pt-rBR", "ru-rRU", "sv", "tr", "uk", "zh")
        }
        testInstrumentationRunner = "androidx.test.runner.AndroidJUnitRunner"
        vectorDrawables.useSupportLibrary = true
    }

   
    val enableApkSplits = (providers.gradleProperty("enableApkSplits").orNull ?: "true").toBoolean()
    val includeUniversalApk = (providers.gradleProperty("includeUniversalApk").orNull ?: "true").toBoolean()
    val targetAbi = providers.gradleProperty("targetAbi").orNull

    splits {
        abi {
            isEnable = enableApkSplits
            reset()
            if (enableApkSplits) {
                if (targetAbi != null) {
                    include(targetAbi)
                } else {
                    include("arm64-v8a", "armeabi-v7a", "x86", "x86_64")
                }
            }
            isUniversalApk = includeUniversalApk && enableApkSplits
        }
    }

    // Release signing is opt-in: with no keystore (a fork, or CI without secrets) the
    // release variant still builds, it just comes out unsigned instead of failing.
    // A variable that is *set but empty* reads back as "" rather than null, and file("")
    // throws, so treat blank the same as unset.
    fun signingEnv(name: String): String? =
        providers.environmentVariable(name).orNull?.takeIf { it.isNotBlank() }

    val keystoreFile = file(
        signingEnv("KEYSTORE_PATH") ?: "${rootProject.projectDir}/release.keystore"
    )
    val storePasswordEnv = signingEnv("STORE_PASSWORD")
    val keyAliasEnv = signingEnv("KEY_ALIAS")
    val keyPasswordEnv = signingEnv("KEY_PASSWORD")
    val hasReleaseKeystore = keystoreFile.exists() &&
        !storePasswordEnv.isNullOrBlank() &&
        !keyAliasEnv.isNullOrBlank()

    if (!hasReleaseKeystore) {
        // apk-dist renames the output, so "-unsigned" no longer shows up in the file name.
        tasks.matching { it.name == "packageRelease" }.configureEach {
            doFirst {
                logger.warn(
                    "No release keystore found at ${keystoreFile.path} (or signing env vars " +
                        "unset) - the release APK will be UNSIGNED and cannot be installed as is."
                )
            }
        }
    }

    signingConfigs {
        if (hasReleaseKeystore) {
            create("release") {
                storeFile = keystoreFile
                storePassword = storePasswordEnv
                keyAlias = keyAliasEnv
                keyPassword = keyPasswordEnv
                enableV1Signing = true
                enableV2Signing = true
                enableV3Signing = false
                enableV4Signing = false
            }
        }
    }

    buildTypes {
        getByName("release") {
            signingConfig = if (hasReleaseKeystore) signingConfigs.getByName("release") else null
            isDebuggable = false
            isMinifyEnabled = true
            proguardFiles(getDefaultProguardFile("proguard-android-optimize.txt"), "proguard-rules.pro")
            buildConfigField("Long", "BUILD_TIME", "0L")
            isShrinkResources = true
        }
        getByName("debug") {
            isDebuggable = true
            isMinifyEnabled = false
            applicationIdSuffix = ".debug"
            proguardFiles(getDefaultProguardFile("proguard-android-optimize.txt"), "proguard-rules.pro")
        }
    }

    buildFeatures {
        buildConfig = true
        compose = true
    }

    namespace = "app.batstats"


    dependenciesInfo {
        includeInApk = false
        includeInBundle = false
    }
}

apkDist {
    artifactNamePrefix = "batstats"
}

// Configure all tasks that are instances of AbstractArchiveTask
tasks.withType<AbstractArchiveTask>().configureEach {
    isPreserveFileTimestamps = false
    isReproducibleFileOrder = true
}

dependencies {

    implementation(libs.androidx.room.runtime)
    implementation(libs.androidx.room.ktx)
    implementation(libs.androidx.animation)
    implementation(libs.androidx.lifecycle.runtime.compose)
    implementation(libs.androidx.documentfile)
    ksp(libs.androidx.room.compiler)
    implementation(fileTree(mapOf("dir" to "libs", "include" to listOf("*.jar"))))
    implementation(libs.kotlin.stdlib)
    implementation(libs.core.ktx)

    // Android lifecycle
    implementation(libs.lifecycle.viewmodel.ktx)

    // Work Manager
    implementation(libs.work.runtime.ktx)

    implementation(libs.androidx.datastore.preferences)
    implementation(libs.kotlinx.serialization.json)
    implementation(libs.androidx.material.icons.extended)

    implementation(libs.androidx.runtime)

    implementation(libs.kmp.settings.core)
    implementation(libs.kmp.settings.ui.compose)
    ksp(libs.kmp.settings.ksp)

    //Material dependencies
    implementation(libs.material)
    implementation(libs.material3.android)

    // Compose dependencies
    val composeBom = platform(libs.androidx.compose.bom)
    implementation(composeBom)
    implementation(libs.androidx.ui)
    implementation(libs.androidx.ui.tooling.preview)
    implementation(libs.activity.compose)
    implementation(libs.lifecycle.viewmodel.compose)
    implementation(libs.navigation.compose)
    implementation(libs.constraintlayout.compose.android)
    implementation(libs.androidbrowserhelper)
    implementation(libs.androidx.datastore.preferences.core)

    // Shizuku
    implementation(libs.api)
    implementation(libs.provider)

    implementation(libs.androidx.navigation3.runtime)
    implementation(libs.androidx.navigation3.ui)
    implementation(libs.androidx.lifecycle.viewmodel.navigation3)

    // Testing
    testImplementation(libs.junit)
    androidTestImplementation(composeBom)
    androidTestImplementation("androidx.compose.ui:ui-test-junit4")
    androidTestImplementation("androidx.test:runner:1.7.0")
    androidTestImplementation("androidx.test:rules:1.7.0")
    androidTestImplementation("androidx.test.ext:junit:1.3.0")
    androidTestImplementation("androidx.room:room-testing:2.8.4")
    debugImplementation("androidx.compose.ui:ui-test-manifest")
    debugImplementation(libs.androidx.ui.tooling)
    coreLibraryDesugaring(libs.desugar.jdk.libs)

    implementation(libs.androidx.lifecycle.runtime.ktx)

    implementation(platform(libs.koin.bom))
    implementation(libs.koin.android)
    implementation(libs.koin.androidx.compose)

    implementation(libs.kotlinx.coroutines.android)
}
