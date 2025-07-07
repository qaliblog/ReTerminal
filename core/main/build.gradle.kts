import java.io.ByteArrayOutputStream

plugins {
    alias(libs.plugins.androidLibrary)
    alias(libs.plugins.kotlinAndroid)
    alias(libs.plugins.compose.compiler)
}

fun getGitCommitHash(): String {
    val stdout = ByteArrayOutputStream()
    exec {
        commandLine("git", "rev-parse", "--short=8", "HEAD")
        standardOutput = stdout
    }
    return stdout.toString().trim()
}

fun getGitCommitDate(): String {
    val stdout = ByteArrayOutputStream()
    exec {
        commandLine("git", "show", "-s", "--format=%cI", "HEAD")
        standardOutput = stdout
    }
    return stdout.toString().trim()
}

fun getFullGitCommitHash(): String {
    val stdout = ByteArrayOutputStream()
    exec {
        commandLine("git", "rev-parse", "HEAD")
        standardOutput = stdout
    }
    return stdout.toString().trim()
}


android {
    namespace = "com.rk.terminal"
    android.buildFeatures.buildConfig = true
    compileSdk = 34

    defaultConfig {
        minSdk = 24
        targetSdk = 34 // Added for consistency
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

    api(project(":core:resources"))
    api(project(":core:components"))
}
