import java.io.ByteArrayOutputStream

plugins {
    id("com.android.library")
    id("org.jetbrains.kotlin.android")
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
    compileSdk = 34

    defaultConfig {
        minSdk = 26

        testInstrumentationRunner = "androidx.test.runner.AndroidJUnitRunner"
        consumerProguardFiles("consumer-rules.pro")
        
        // Enable native library support
        externalNativeBuild {
            cmake {
                cppFlags += "-std=c++17"
                arguments += listOf(
                    "-DANDROID_STL=c++_shared",
                    "-DANDROID_TOOLCHAIN=clang"
                )
            }
        }
        
        ndk {
            abiFilters += listOf("arm64-v8a", "armeabi-v7a", "x86", "x86_64")
        }
    }

    buildTypes {
        release {
            isMinifyEnabled = false
            proguardFiles(
                getDefaultProguardFile("proguard-android-optimize.txt"),
                "proguard-rules.pro"
            )
        }
        debug {
            isMinifyEnabled = false
            isDebuggable = true
        }
    }
    
    // Configure native build
    externalNativeBuild {
        cmake {
            path = file("src/main/cpp/CMakeLists.txt")
            version = "3.22.1"
        }
    }
    
    // Pack native libraries
    packagingOptions {
        pickFirst("**/libc++_shared.so")
        pickFirst("**/libcrypto.so")
        pickFirst("**/libssl.so")
    }
    
    compileOptions {
        sourceCompatibility = JavaVersion.VERSION_1_8
        targetCompatibility = JavaVersion.VERSION_1_8
    }
    kotlinOptions {
        jvmTarget = "1.8"
    }
    buildFeatures {
        compose = true
    }
    composeOptions {
        kotlinCompilerExtensionVersion = "1.5.4"
    }
}

dependencies {

    implementation("androidx.core:core-ktx:1.9.0")
    implementation("androidx.lifecycle:lifecycle-runtime-ktx:2.6.2")
    implementation("androidx.activity:activity-compose:1.8.0")
    implementation(platform("androidx.compose:compose-bom:2023.03.00"))
    implementation("androidx.compose.ui:ui")
    implementation("androidx.compose.ui:ui-graphics")
    implementation("androidx.compose.ui:ui-tooling-preview")
    implementation("androidx.compose.material3:material3")
    implementation("androidx.navigation:navigation-compose:2.7.4")
    implementation("androidx.compose.material:material-icons-extended")
    implementation("androidx.datastore:datastore-preferences:1.0.0")
    implementation("org.jetbrains.kotlinx:kotlinx-coroutines-android:1.7.3")
    
    // File manager
    implementation("androidx.documentfile:documentfile:1.0.1")
    
    // Terminal components (keep existing Termux components as fallback)
    implementation("com.github.termux.termux-app:terminal-emulator:a2b448c93f")
    implementation("com.github.termux.termux-app:terminal-view:a2b448c93f")
    
    // SSH support (keep JSch as fallback)
    implementation("com.github.mwiede:jsch:0.2.17")
    
    // Native library dependencies
    implementation("androidx.annotation:annotation:1.7.0")
    
    testImplementation("junit:junit:4.13.2")
    androidTestImplementation("androidx.test.ext:junit:1.1.5")
    androidTestImplementation("androidx.test.espresso:espresso-core:3.5.1")
    androidTestImplementation(platform("androidx.compose:compose-bom:2023.03.00"))
    androidTestImplementation("androidx.compose.ui:ui-test-junit4")
    debugImplementation("androidx.compose.ui:ui-tooling")
    debugImplementation("androidx.compose.ui:ui-test-manifest")
}
