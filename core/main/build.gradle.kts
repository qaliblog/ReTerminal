import java.io.ByteArrayOutputStream

plugins {
    alias(libs.plugins.androidLibrary)
    alias(libs.plugins.kotlinAndroid)
    alias(libs.plugins.compose.compiler)
    kotlin("plugin.serialization") version "1.8.22"
}

fun safeGit(vararg args: String): String {
    val stdout = ByteArrayOutputStream()
    return try {
        exec {
            commandLine("git", *args)
            isIgnoreExitValue = true
            standardOutput = stdout
            errorOutput = ByteArrayOutputStream()
        }
        val out = stdout.toString().trim()
        if (out.isBlank()) "unknown" else out
    } catch (e: Exception) {
        "unknown"
    }
}

fun getGitCommitHash(): String {
    return safeGit("rev-parse", "--short=8", "HEAD")
}

fun getGitCommitDate(): String {
    return safeGit("show", "-s", "--format=%cI", "HEAD")
}

fun getFullGitCommitHash(): String {
    return safeGit("rev-parse", "HEAD")
}


android {
    namespace = "com.rk.terminal"
    android.buildFeatures.buildConfig = true
    compileSdk = 34

    defaultConfig {
        minSdk = 24
        testInstrumentationRunner = "androidx.test.runner.AndroidJUnitRunner"
        consumerProguardFiles("consumer-rules.pro")
    }

    buildTypes {
        release {
            buildConfigField("String", "GIT_COMMIT_HASH", "\"${getFullGitCommitHash()}\"")
            buildConfigField("String", "GIT_SHORT_COMMIT_HASH", "\"${getGitCommitHash()}\"")
            buildConfigField("String", "GIT_COMMIT_DATE", "\"${getGitCommitDate()}\"")
            isMinifyEnabled = false
            isShrinkResources = false
            proguardFiles(
                getDefaultProguardFile("proguard-android-optimize.txt"), "proguard-rules.pro"
            )
        }
        debug{
            buildConfigField("String", "GIT_COMMIT_HASH", "\"${getFullGitCommitHash()}\"")
            buildConfigField("String", "GIT_SHORT_COMMIT_HASH", "\"${getGitCommitHash()}\"")
            buildConfigField("String", "GIT_COMMIT_DATE", "\"${getGitCommitDate()}\"")
        }
    }


    compileOptions {
        sourceCompatibility = JavaVersion.VERSION_17
        targetCompatibility = JavaVersion.VERSION_17
    }
    kotlinOptions {
        jvmTarget = "17"
    }

    buildFeatures {
        viewBinding = true
        compose = true
    }

    composeOptions {
        kotlinCompilerExtensionVersion = "1.5.15"
    }


}

dependencies {
    api(libs.appcompat)
    api(libs.material)
    api(libs.constraintlayout)
    api(libs.navigation.fragment)
    api(libs.navigation.ui)
    api(libs.asynclayoutinflater)
    api(libs.navigation.fragment.ktx)
    api(libs.navigation.ui.ktx)
    api(libs.activity)
    api(libs.lifecycle.livedata.ktx)
    api(libs.lifecycle.viewmodel.ktx)
    api(libs.lifecycle.runtime.ktx)
    api(libs.activity.compose)
    api(platform(libs.compose.bom))
    api(libs.ui)
    api(libs.ui.graphics)
    api(libs.material3)
    api(libs.navigation.compose)
    api(libs.terminal.view)
    api(libs.terminal.emulator)
    api(libs.utilcode)
    //api(libs.commons.net)
    api(libs.okhttp)
    api(libs.anrwatchdog)
    api(libs.androidx.palette)
    api(libs.accompanist.systemuicontroller)
    api(libs.material.icons.extended)

    api(project(":core:resources"))
    api(project(":core:components"))
    // For extracting tar archives from Hugging Face bundles
    api("org.apache.commons:commons-compress:1.26.2")
    api(libs.security.crypto)
    // SSH library for sophisticated SSH connections
    api("com.jcraft:jsch:0.1.55")
    api("org.apache.sshd:sshd-core:2.11.0")
    api("org.apache.sshd:sshd-sftp:2.11.0")
    // Serialization for SSH config storage
    api("org.jetbrains.kotlinx:kotlinx-serialization-json:1.6.0")
}
