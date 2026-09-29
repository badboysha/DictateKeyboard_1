/*
 * Copyright (C) 2022-2025 The FlorisBoard Contributors
 *
 * Licensed under the Apache License, Version 2.0 (the "License");
 * you may not use this file except in compliance with the License.
 * You may obtain a copy of the License at
 *
 * http://www.apache.org/licenses/LICENSE-2.0
 *
 * Unless required by applicable law or agreed to in writing, software
 * distributed under the License is distributed on an "AS IS" BASIS,
 * WITHOUT WARRANTIES OR CONDITIONS OF ANY KIND, either express or implied.
 * See the License for the specific language governing permissions and
 * limitations under the License.
 */

import com.android.build.api.dsl.ApplicationExtension
import org.gradle.api.tasks.testing.logging.TestLogEvent
import org.jetbrains.kotlin.gradle.dsl.JvmTarget
import java.util.Properties

plugins {
    alias(libs.plugins.agp.application)
    alias(libs.plugins.kotlin.plugin.compose)
    alias(libs.plugins.kotlin.serialization)
    alias(libs.plugins.ksp)
    alias(libs.plugins.mikepenz.aboutlibraries)
    alias(libs.plugins.kotest)
    alias(libs.plugins.kotlinx.kover)
}

val projectMinSdk: String by project
val projectTargetSdk: String by project
val projectCompileSdk: String by project
val projectVersionCode: String by project
val projectVersionName: String by project

val projectVersionNameSuffix =
    projectVersionName.substringAfter("-", "").let { suffix ->
        if (suffix.isNotEmpty()) {
            "-$suffix"
        } else {
            suffix
        }
    }

/*
 * ABI is selected from the GitHub Actions workflow:
 *
 * -ParmTargetAbi=arm64-v8a
 * -ParmTargetAbi=armeabi-v7a
 *
 * Default: arm64-v8a
 */
val targetAbi = providers
    .gradleProperty("targetAbi")
    .orElse("arm64-v8a")
    .get()

val supportedAbis = setOf(
    "arm64-v8a",
    "armeabi-v7a"
)

require(targetAbi in supportedAbis) {
    "Unsupported targetAbi: $targetAbi. " +
        "Supported values: ${supportedAbis.joinToString(", ")}"
}

kotlin {
    compilerOptions {
        jvmTarget.set(JvmTarget.JVM_11)

        freeCompilerArgs.set(
            listOf(
                "-opt-in=kotlin.contracts.ExperimentalContracts",
                "-jvm-default=enable",
                "-Xwhen-guards",
                "-Xexplicit-backing-fields",
                "-Xcontext-parameters",
                "-XXLanguage:+LocalTypeAliases",
            )
        )
    }
}

configure<ApplicationExtension> {
    namespace = "dev.patrickgold.florisboard"
    compileSdk = projectCompileSdk.toInt()
    buildToolsVersion = tools.versions.buildTools.get()
    ndkVersion = tools.versions.ndk.get()

    compileOptions {
        sourceCompatibility = JavaVersion.VERSION_11
        targetCompatibility = JavaVersion.VERSION_11
    }

    defaultConfig {
        applicationId = "net.devemperor.dictate"
        minSdk = projectMinSdk.toInt()
        targetSdk = projectTargetSdk.toInt()
        versionCode = projectVersionCode.toInt()
        versionName = projectVersionName.substringBefore("-")

        testInstrumentationRunner =
            "androidx.test.runner.AndroidJUnitRunner"

        /*
         * Build only the ABI requested by the workflow.
         *
         * arm64-v8a     -> ARM64 APK
         * armeabi-v7a   -> ARMv7 APK
         */
        ndk {
            abiFilters.clear()
            abiFilters += targetAbi
        }

        buildConfigField(
            "String",
            "BUILD_COMMIT_HASH",
            "\"${getGitCommitHash().get()}\""
        )

        buildConfigField(
            "String",
            "FLADDONS_API_VERSION",
            "\"v~draft2\""
        )

        buildConfigField(
            "String",
            "FLADDONS_STORE_URL",
            "\"beta.addons.florisboard.org\""
        )

        sourceSets {
            maybeCreate("main").apply {
                assets.directories += "src/main/assets"
            }
        }
    }

    bundle {
        language {
            enableSplit = false
        }
    }

    buildFeatures {
        buildConfig = true
        compose = true
    }

    /*
     * Release signing.
     *
     * If keystore.properties exists, Gradle signs the release build.
     * Otherwise the workflow can sign the generated APK afterwards.
     */
    val keystorePropsFile = rootProject.file("keystore.properties")

    val keystoreProps = if (keystorePropsFile.exists()) {
        Properties().apply {
            keystorePropsFile.inputStream().use {
                load(it)
            }
        }
    } else {
        null
    }

    signingConfigs {
        keystoreProps?.let { props ->
            create("release") {
                storeFile = rootProject.file(
                    props.getProperty("storeFile")
                )
                storePassword = props.getProperty("storePassword")
                keyAlias = props.getProperty("keyAlias")
                keyPassword = props.getProperty("keyPassword")
            }
        }
    }

    buildTypes {
        named("debug") {
            applicationIdSuffix = ".debug"
            versionNameSuffix =
                "-debug+${getGitCommitHash(short = true).get()}"

            isDebuggable = true
            isJniDebuggable = false
        }

        create("beta") {
            applicationIdSuffix = ".beta"
            versionNameSuffix = projectVersionNameSuffix

            proguardFiles(
                getDefaultProguardFile(
                    "proguard-android-optimize.txt"
                ),
                "proguard-rules.pro"
            )

            isMinifyEnabled = true
            isShrinkResources = true
        }

        named("release") {
            versionNameSuffix = projectVersionNameSuffix

            if (keystoreProps != null) {
                signingConfig = signingConfigs.getByName("release")
            }

            proguardFiles(
                getDefaultProguardFile(
                    "proguard-android-optimize.txt"
                ),
                "proguard-rules.pro"
            )

            isMinifyEnabled = true
            isShrinkResources = true
        }

        create("benchmark") {
            initWith(getByName("release"))

            applicationIdSuffix = ".bench"
            versionNameSuffix =
                "-bench+${getGitCommitHash(short = true).get()}"

            signingConfig = signingConfigs.getByName("debug")
            matchingFallbacks += listOf("release")
        }
    }

    testOptions {
        unitTests {
            isIncludeAndroidResources = true
        }

        unitTests.all {
            it.useJUnitPlatform()
        }
    }
}

aboutLibraries {
    collect {
        configPath = file("src/main/config")
    }
}

ksp {
    arg(
        "room.schemaLocation",
        "$projectDir/schemas"
    )

    arg(
        "room.incremental",
        "true"
    )

    arg(
        "room.expandProjection",
        "true"
    )
}

tasks.withType<Test> {
    testLogging {
        events = setOf(
            TestLogEvent.FAILED,
            TestLogEvent.PASSED,
            TestLogEvent.SKIPPED
        )
    }

    maxHeapSize = "2g"
    useJUnitPlatform()
}

kover {
    useJacoco()
}

dependencies {
    val composeBom =
        platform(libs.androidx.compose.bom)

    implementation(composeBom)
    implementation(libs.android.billing.ktx)
    implementation(libs.androidx.activity.compose)
    implementation(libs.androidx.activity.ktx)
    implementation(libs.androidx.autofill)
    implementation(libs.androidx.collection.ktx)
    implementation(libs.androidx.compose.material.icons)
    implementation(libs.androidx.compose.material3)
    implementation(libs.androidx.compose.runtime.livedata)
    implementation(libs.androidx.compose.ui)
    implementation(libs.androidx.compose.ui.tooling.preview)
    implementation(libs.androidx.core.ktx)
    implementation(libs.androidx.core.splashscreen)
    implementation(libs.androidx.emoji2)
    implementation(libs.androidx.emoji2.views)
    implementation(libs.androidx.exifinterface)
    implementation(libs.androidx.navigation.compose)
    implementation(libs.androidx.profileinstaller)
    ksp(libs.androidx.room.compiler)
    implementation(libs.androidx.room.runtime)
    implementation(libs.androidx.window.core)
    implementation(libs.cache4k)
    implementation(libs.coil.compose)
    implementation(libs.coil.gif)
    implementation(libs.coil.network.okhttp)
    implementation(libs.kotlin.reflect)
    implementation(libs.kotlinx.coroutines)
    implementation(libs.kotlinx.serialization.json)
    implementation(libs.mikepenz.aboutlibraries.core)
    implementation(libs.mikepenz.aboutlibraries.compose)
    implementation(libs.mlkit.text.recognition)
    implementation(libs.okhttp)
    implementation(libs.patrickgold.compose.tooltip)
    implementation(libs.patrickgold.jetpref.datastore.model)
    ksp(libs.patrickgold.jetpref.datastore.model.processor)
    implementation(libs.patrickgold.jetpref.datastore.ui)
    implementation(libs.patrickgold.jetpref.material.ui)

    /*
     * On-device speech recognition.
     */
    implementation(
        files("libs/sherpa-onnx-1.13.3.jar")
    )

    implementation(
        files("libs/onnxruntime-android-1.24.3.jar")
    )

    implementation(projects.lib.android)
    implementation(projects.lib.color)
    implementation(projects.lib.dictateCore)
    implementation(projects.lib.compose)

    implementation(libs.play.services.wearable)
    implementation(libs.kotlinx.coroutines.play.services)
    implementation(projects.lib.kotlin)
    implementation(projects.lib.snygg)

    testImplementation(libs.kotest.assertions.core)
    testImplementation(libs.kotest.property)
    testImplementation(libs.kotest.runner.junit5)
    testImplementation(libs.kotlin.test.junit5)
    testImplementation(libs.kotlinx.coroutines.test)
    testImplementation(libs.turbine)
    testImplementation(libs.kotlinx.serialization.json)

    androidTestImplementation(
        libs.androidx.test.ext
    )

    androidTestImplementation(
        libs.androidx.test.espresso.core
    )
}

/*
 * Verify only the native libraries required by the
 * currently selected ABI.
 *
 * This keeps ARM64 and ARMv7 builds separate.
 */
val verifySherpaOnnxLibs by tasks.registering {
    val projectDir = layout.projectDirectory

    val required = listOf(
        projectDir.file(
            "libs/sherpa-onnx-1.13.3.jar"
        ).asFile,

        projectDir.file(
            "libs/onnxruntime-android-1.24.3.jar"
        ).asFile,

        projectDir.file(
            "src/main/jniLibs/$targetAbi/libonnxruntime.so"
        ).asFile,

        projectDir.file(
            "src/main/jniLibs/$targetAbi/libonnxruntime4j_jni.so"
        ).asFile,

        projectDir.file(
            "src/main/jniLibs/$targetAbi/libsherpa-onnx-jni.so"
        ).asFile
    )

    doLast {
        val missing = required.filterNot {
            it.exists()
        }

        if (missing.isNotEmpty()) {
            throw GradleException(
                "Missing Sherpa-ONNX files for ABI: $targetAbi\n\n" +
                    missing.joinToString("\n") {
                        "  - ${it.path}"
                    } +
                    "\n\nRun: tools/fetch-sherpa-onnx.sh"
            )
        }
    }
}

tasks.named("preBuild").configure {
    dependsOn(verifySherpaOnnxLibs)
}

/*
 * Returns the current Git commit hash.
 */
fun getGitCommitHash(
    short: Boolean = false
): Provider<String> {

    if (!File(".git").exists()) {
        return providers.provider {
            "null"
        }
    }

    val execProvider = providers.exec {
        if (short) {
            commandLine(
                "git",
                "rev-parse",
                "--short",
                "HEAD"
            )
        } else {
            commandLine(
                "git",
                "rev-parse",
                "HEAD"
            )
        }
    }

    return execProvider.standardOutput.asText.map {
        it.trim()
    }
}
