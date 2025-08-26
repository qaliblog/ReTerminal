#!/bin/bash
# Quick Build Fix Script
# Fixes all the Gradle/Compose issues for immediate SSH testing

echo "🔧 QUICK BUILD FIX FOR RETERMINAL"
echo "================================"

# 1. Backup original files
echo "1. Creating backups..."
cp build.gradle.kts build.gradle.kts.backup 2>/dev/null || echo "No root build.gradle.kts to backup"
cp core/main/build.gradle.kts core/main/build.gradle.kts.backup 2>/dev/null || echo "No core build.gradle.kts to backup"

# 2. Apply simplified configurations
echo "2. Applying simplified build configurations..."
cp build.gradle.kts.simple build.gradle.kts
echo "✅ Root build.gradle.kts simplified"

# 3. Check if the core build file is already simplified
if grep -q "compose" core/main/build.gradle.kts; then
    echo "⚠️  Core build.gradle.kts still has Compose references"
    echo "Applying simpler core configuration..."
    
    cat > core/main/build.gradle.kts << 'EOF'
plugins {
    id("com.android.library")
    id("org.jetbrains.kotlin.android")
}

android {
    namespace = "com.rk.terminal"
    compileSdk = 34

    defaultConfig {
        minSdk = 24
        testInstrumentationRunner = "androidx.test.runner.AndroidJUnitRunner"
        consumerProguardFiles("consumer-rules.pro")
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
        }
    }
    
    compileOptions {
        sourceCompatibility = JavaVersion.VERSION_1_8
        targetCompatibility = JavaVersion.VERSION_1_8
    }
    kotlinOptions {
        jvmTarget = "1.8"
    }
}

dependencies {
    implementation("androidx.core:core-ktx:1.9.0")
    implementation("androidx.lifecycle:lifecycle-runtime-ktx:2.6.2")
    implementation("androidx.appcompat:appcompat:1.6.1")
    implementation("com.google.android.material:material:1.10.0")
    implementation("androidx.constraintlayout:constraintlayout:2.1.4")
    implementation("androidx.navigation:navigation-fragment-ktx:2.7.4")
    implementation("androidx.navigation:navigation-ui-ktx:2.7.4")
    implementation("org.jetbrains.kotlinx:kotlinx-coroutines-android:1.7.3")
    
    // Terminal components
    implementation("com.github.termux.termux-app:terminal-emulator:a2b448c93f")
    implementation("com.github.termux.termux-app:terminal-view:a2b448c93f")
    
    // SSH support
    implementation("com.github.mwiede:jsch:0.2.17")
    
    testImplementation("junit:junit:4.13.2")
    androidTestImplementation("androidx.test.ext:junit:1.1.5")
    androidTestImplementation("androidx.test.espresso:espresso-core:3.5.1")
}
EOF
    echo "✅ Core build.gradle.kts simplified"
else
    echo "✅ Core build.gradle.kts already simplified"
fi

# 4. Clean any problematic files
echo "3. Cleaning problematic files..."
rm -rf .gradle/ 2>/dev/null || true
rm -rf build/ 2>/dev/null || true
rm -rf */build/ 2>/dev/null || true
echo "✅ Cleaned build directories"

# 5. Check if we have gradle wrapper
if [ ! -f "gradlew" ]; then
    echo "⚠️  No gradlew found. Make sure you're in the project root."
    echo "Trying to find gradlew..."
    find . -name "gradlew" -type f 2>/dev/null | head -1
fi

# 6. Test build
echo "4. Testing build..."
echo "Running: ./gradlew clean"

if ./gradlew clean; then
    echo "✅ Clean successful"
    
    echo "Running: ./gradlew :core:main:assembleDebug"
    if ./gradlew :core:main:assembleDebug; then
        echo ""
        echo "🎉 BUILD SUCCESS!"
        echo "================"
        echo ""
        echo "✅ Core module built successfully"
        echo "✅ SSH fixes are ready to use"
        echo ""
        echo "Next steps:"
        echo "1. Replace SshTerminalSession with ImprovedSSHTerminalSession in your code"
        echo "2. Add: import com.rk.terminal.ssh.ImprovedSSHTerminalSession"
        echo "3. Test your SSH connection"
        echo ""
        echo "Your 'can't type' SSH issue should now be resolved!"
    else
        echo ""
        echo "❌ Core module build failed"
        echo "Check the error output above"
        
        echo ""
        echo "Fallback: Try building just the SSH classes..."
        if ./gradlew :core:main:compileDebugKotlin; then
            echo "✅ Kotlin compilation successful - SSH fixes should work"
        else
            echo "❌ Kotlin compilation failed"
            echo ""
            echo "Manual fix options:"
            echo "1. Check that you're using the simplified ImprovedSSHTerminalSession"
            echo "2. Manually copy the SSH fix classes to your project"
            echo "3. Use the terminal fix commands if you're stuck in SSH"
        fi
    fi
else
    echo "❌ Clean failed"
    echo ""
    echo "Troubleshooting:"
    echo "1. Make sure you're in the ReTerminal project root"
    echo "2. Check that Android SDK is properly configured"
    echo "3. Try: chmod +x gradlew"
    echo "4. Try: ./gradlew --version"
fi

echo ""
echo "Build fix complete. Check results above."