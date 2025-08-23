package com.rk.terminal.ui.screens.terminal

import org.json.JSONArray
import org.json.JSONObject
import java.io.File

/**
 * Comprehensive Framework Template Manager for AI Android Agent
 * 
 * This manager provides the AI agent with deep knowledge of popular framework
 * templates, project structures, and best practices to generate functional,
 * well-structured code across multiple technologies.
 */
class FrameworkTemplateManager {

    data class FrameworkTemplate(
        val name: String,
        val type: String, // "backend", "frontend", "mobile", "fullstack"
        val language: String,
        val description: String,
        val projectStructure: Map<String, String>, // path -> description
        val coreFiles: Map<String, String>, // filename -> template content
        val dependencies: List<String>,
        val buildConfig: String,
        val conventions: FrameworkConventions,
        val commonPatterns: List<CodePattern>
    )

    data class FrameworkConventions(
        val fileNaming: String, // e.g., "camelCase", "snake_case", "kebab-case"
        val directoryStructure: List<String>,
        val importStyle: String,
        val functionNaming: String,
        val classNaming: String,
        val configFiles: List<String>,
        val testingFramework: String?,
        val linting: String?,
        val formatting: String?
    )

    data class CodePattern(
        val name: String,
        val description: String,
        val template: String,
        val variables: List<String> = emptyList()
    )

    companion object {
        private val templates = mutableMapOf<String, FrameworkTemplate>()

        init {
            initializeTemplates()
        }

        private fun initializeTemplates() {
            // Kotlin Android Template
            templates["kotlin_android"] = FrameworkTemplate(
                name = "Kotlin Android",
                type = "mobile",
                language = "kotlin",
                description = "Modern Android app with Kotlin, Jetpack Compose, and MVVM architecture",
                projectStructure = mapOf(
                    "app/src/main/java/com/company/app" to "Main application package",
                    "app/src/main/java/com/company/app/ui" to "UI components and screens",
                    "app/src/main/java/com/company/app/data" to "Data layer (repositories, models)",
                    "app/src/main/java/com/company/app/domain" to "Business logic and use cases",
                    "app/src/main/java/com/company/app/di" to "Dependency injection modules",
                    "app/src/main/res" to "Android resources",
                    "app/src/test/java" to "Unit tests",
                    "app/src/androidTest/java" to "Instrumented tests"
                ),
                coreFiles = mapOf(
                    "MainActivity.kt" to getKotlinMainActivityTemplate(),
                    "Application.kt" to getKotlinApplicationTemplate(),
                    "build.gradle.kts" to getKotlinBuildGradleTemplate(),
                    "AndroidManifest.xml" to getAndroidManifestTemplate()
                ),
                dependencies = listOf(
                    "androidx.core:core-ktx",
                    "androidx.lifecycle:lifecycle-runtime-ktx",
                    "androidx.activity:activity-compose",
                    "androidx.compose.ui:ui",
                    "androidx.compose.ui:ui-tooling-preview",
                    "androidx.compose.material3:material3"
                ),
                buildConfig = getKotlinBuildGradleTemplate(),
                conventions = FrameworkConventions(
                    fileNaming = "PascalCase",
                    directoryStructure = listOf("ui", "data", "domain", "di"),
                    importStyle = "organized_imports",
                    functionNaming = "camelCase",
                    classNaming = "PascalCase",
                    configFiles = listOf("build.gradle.kts", "AndroidManifest.xml"),
                    testingFramework = "JUnit",
                    linting = "ktlint",
                    formatting = "ktfmt"
                ),
                commonPatterns = getKotlinPatterns()
            )

            // Java Spring Boot Template
            templates["java_spring_boot"] = FrameworkTemplate(
                name = "Java Spring Boot",
                type = "backend",
                language = "java",
                description = "RESTful API with Spring Boot, JPA, and Maven",
                projectStructure = mapOf(
                    "src/main/java/com/company/app" to "Main application package",
                    "src/main/java/com/company/app/controller" to "REST controllers",
                    "src/main/java/com/company/app/service" to "Business logic services",
                    "src/main/java/com/company/app/repository" to "Data access layer",
                    "src/main/java/com/company/app/model" to "Entity models",
                    "src/main/java/com/company/app/config" to "Configuration classes",
                    "src/main/resources" to "Application resources",
                    "src/test/java" to "Unit and integration tests"
                ),
                coreFiles = mapOf(
                    "Application.java" to getSpringBootApplicationTemplate(),
                    "ApplicationController.java" to getSpringBootControllerTemplate(),
                    "ApplicationService.java" to getSpringBootServiceTemplate(),
                    "pom.xml" to getSpringBootPomTemplate(),
                    "application.properties" to getSpringBootPropertiesTemplate()
                ),
                dependencies = listOf(
                    "org.springframework.boot:spring-boot-starter-web",
                    "org.springframework.boot:spring-boot-starter-data-jpa",
                    "org.springframework.boot:spring-boot-starter-validation",
                    "org.springframework.boot:spring-boot-starter-test"
                ),
                buildConfig = getSpringBootPomTemplate(),
                conventions = FrameworkConventions(
                    fileNaming = "PascalCase",
                    directoryStructure = listOf("controller", "service", "repository", "model", "config"),
                    importStyle = "organized_imports",
                    functionNaming = "camelCase",
                    classNaming = "PascalCase",
                    configFiles = listOf("pom.xml", "application.properties"),
                    testingFramework = "JUnit 5",
                    linting = "checkstyle",
                    formatting = "google-java-format"
                ),
                commonPatterns = getSpringBootPatterns()
            )

            // Next.js Template
            templates["nextjs"] = FrameworkTemplate(
                name = "Next.js",
                type = "frontend",
                language = "typescript",
                description = "Full-stack React framework with TypeScript and App Router",
                projectStructure = mapOf(
                    "app" to "App Router directory (Next.js 13+)",
                    "app/api" to "API route handlers",
                    "components" to "Reusable React components",
                    "lib" to "Utility functions and configurations",
                    "public" to "Static assets",
                    "styles" to "CSS and styling files",
                    "types" to "TypeScript type definitions",
                    "__tests__" to "Test files"
                ),
                coreFiles = mapOf(
                    "package.json" to getNextJsPackageJsonTemplate(),
                    "next.config.js" to getNextJsConfigTemplate(),
                    "tsconfig.json" to getNextJsTsConfigTemplate(),
                    "tailwind.config.js" to getNextJsTailwindConfigTemplate(),
                    "app/layout.tsx" to getNextJsLayoutTemplate(),
                    "app/page.tsx" to getNextJsPageTemplate()
                ),
                dependencies = listOf(
                    "next",
                    "react",
                    "react-dom",
                    "@types/node",
                    "@types/react",
                    "@types/react-dom",
                    "typescript",
                    "tailwindcss"
                ),
                buildConfig = getNextJsPackageJsonTemplate(),
                conventions = FrameworkConventions(
                    fileNaming = "kebab-case",
                    directoryStructure = listOf("app", "components", "lib", "types"),
                    importStyle = "es6_modules",
                    functionNaming = "camelCase",
                    classNaming = "PascalCase",
                    configFiles = listOf("package.json", "next.config.js", "tsconfig.json"),
                    testingFramework = "Jest + React Testing Library",
                    linting = "eslint",
                    formatting = "prettier"
                ),
                commonPatterns = getNextJsPatterns()
            )

            // Flask Template
            templates["flask"] = FrameworkTemplate(
                name = "Flask",
                type = "backend",
                language = "python",
                description = "Lightweight Python web framework with blueprints and SQLAlchemy",
                projectStructure = mapOf(
                    "app" to "Main application package",
                    "app/models" to "Database models",
                    "app/views" to "Route handlers and views",
                    "app/services" to "Business logic services",
                    "app/utils" to "Utility functions",
                    "app/templates" to "Jinja2 templates",
                    "app/static" to "Static files (CSS, JS, images)",
                    "migrations" to "Database migration files",
                    "tests" to "Test files",
                    "config" to "Configuration files"
                ),
                coreFiles = mapOf(
                    "app.py" to getFlaskAppTemplate(),
                    "requirements.txt" to getFlaskRequirementsTemplate(),
                    "config.py" to getFlaskConfigTemplate(),
                    "app/__init__.py" to getFlaskInitTemplate(),
                    "app/models/__init__.py" to getFlaskModelsInitTemplate(),
                    "app/views/__init__.py" to getFlaskViewsInitTemplate()
                ),
                dependencies = listOf(
                    "Flask",
                    "Flask-SQLAlchemy",
                    "Flask-Migrate",
                    "Flask-WTF",
                    "python-dotenv",
                    "gunicorn"
                ),
                buildConfig = getFlaskRequirementsTemplate(),
                conventions = FrameworkConventions(
                    fileNaming = "snake_case",
                    directoryStructure = listOf("models", "views", "services", "utils", "templates", "static"),
                    importStyle = "absolute_imports",
                    functionNaming = "snake_case",
                    classNaming = "PascalCase",
                    configFiles = listOf("requirements.txt", "config.py", ".env"),
                    testingFramework = "pytest",
                    linting = "flake8",
                    formatting = "black"
                ),
                commonPatterns = getFlaskPatterns()
            )

            // React Template
            templates["react"] = FrameworkTemplate(
                name = "React",
                type = "frontend",
                language = "typescript",
                description = "Modern React app with TypeScript, hooks, and component-based architecture",
                projectStructure = mapOf(
                    "src" to "Source code directory",
                    "src/components" to "Reusable React components",
                    "src/pages" to "Page components",
                    "src/hooks" to "Custom React hooks",
                    "src/services" to "API services and external integrations",
                    "src/utils" to "Utility functions",
                    "src/types" to "TypeScript type definitions",
                    "src/styles" to "CSS and styling files",
                    "public" to "Static assets",
                    "src/__tests__" to "Test files"
                ),
                coreFiles = mapOf(
                    "package.json" to getReactPackageJsonTemplate(),
                    "tsconfig.json" to getReactTsConfigTemplate(),
                    "src/App.tsx" to getReactAppTemplate(),
                    "src/index.tsx" to getReactIndexTemplate(),
                    "src/components/Layout.tsx" to getReactLayoutTemplate()
                ),
                dependencies = listOf(
                    "react",
                    "react-dom",
                    "react-router-dom",
                    "@types/react",
                    "@types/react-dom",
                    "typescript",
                    "axios"
                ),
                buildConfig = getReactPackageJsonTemplate(),
                conventions = FrameworkConventions(
                    fileNaming = "PascalCase",
                    directoryStructure = listOf("components", "pages", "hooks", "services", "utils", "types"),
                    importStyle = "es6_modules",
                    functionNaming = "camelCase",
                    classNaming = "PascalCase",
                    configFiles = listOf("package.json", "tsconfig.json"),
                    testingFramework = "Jest + React Testing Library",
                    linting = "eslint",
                    formatting = "prettier"
                ),
                commonPatterns = getReactPatterns()
            )

            // Express.js Template
            templates["express"] = FrameworkTemplate(
                name = "Express.js",
                type = "backend",
                language = "typescript",
                description = "Node.js backend with Express, TypeScript, and modular architecture",
                projectStructure = mapOf(
                    "src" to "Source code directory",
                    "src/controllers" to "Request handlers",
                    "src/services" to "Business logic",
                    "src/models" to "Data models",
                    "src/routes" to "API route definitions",
                    "src/middleware" to "Custom middleware",
                    "src/utils" to "Utility functions",
                    "src/config" to "Configuration files",
                    "src/types" to "TypeScript type definitions",
                    "tests" to "Test files"
                ),
                coreFiles = mapOf(
                    "package.json" to getExpressPackageJsonTemplate(),
                    "tsconfig.json" to getExpressTsConfigTemplate(),
                    "src/app.ts" to getExpressAppTemplate(),
                    "src/server.ts" to getExpressServerTemplate(),
                    "src/routes/index.ts" to getExpressRoutesTemplate()
                ),
                dependencies = listOf(
                    "express",
                    "@types/express",
                    "typescript",
                    "ts-node",
                    "nodemon",
                    "cors",
                    "helmet",
                    "dotenv"
                ),
                buildConfig = getExpressPackageJsonTemplate(),
                conventions = FrameworkConventions(
                    fileNaming = "camelCase",
                    directoryStructure = listOf("controllers", "services", "models", "routes", "middleware", "utils"),
                    importStyle = "es6_modules",
                    functionNaming = "camelCase",
                    classNaming = "PascalCase",
                    configFiles = listOf("package.json", "tsconfig.json", ".env"),
                    testingFramework = "Jest + Supertest",
                    linting = "eslint",
                    formatting = "prettier"
                ),
                commonPatterns = getExpressPatterns()
            )
        }

        fun getTemplate(name: String): FrameworkTemplate? = templates[name]
        
        fun getAllTemplates(): Map<String, FrameworkTemplate> = templates.toMap()
        
        fun getTemplatesByType(type: String): List<FrameworkTemplate> = 
            templates.values.filter { it.type == type }
            
        fun getTemplatesByLanguage(language: String): List<FrameworkTemplate> = 
            templates.values.filter { it.language == language }

        fun detectFrameworkFromProject(projectPath: String): FrameworkTemplate? {
            val projectDir = File(projectPath)
            if (!projectDir.exists() || !projectDir.isDirectory) return null

            val files = projectDir.listFiles()?.map { it.name } ?: emptyList()
            
            return when {
                files.contains("package.json") -> {
                    val packageJson = File(projectDir, "package.json")
                    if (packageJson.exists()) {
                        val content = packageJson.readText()
                        when {
                            content.contains("\"next\"") -> templates["nextjs"]
                            content.contains("\"express\"") -> templates["express"]
                            content.contains("\"react\"") && !content.contains("\"next\"") -> templates["react"]
                            else -> null
                        }
                    } else null
                }
                files.contains("requirements.txt") || files.contains("app.py") -> templates["flask"]
                files.contains("pom.xml") && files.any { it.endsWith(".java") } -> templates["java_spring_boot"]
                files.contains("build.gradle.kts") && files.contains("AndroidManifest.xml") -> templates["kotlin_android"]
                else -> null
            }
        }

        fun generateProjectStructure(template: FrameworkTemplate, basePath: String, projectName: String): List<String> {
            val commands = mutableListOf<String>()
            
            // Create base directories
            template.projectStructure.keys.forEach { path ->
                val fullPath = "$basePath/$projectName/$path"
                commands.add("mkdir -p \"$fullPath\"")
            }
            
            // Create core files
            template.coreFiles.forEach { (filename, content) ->
                val fullPath = "$basePath/$projectName/$filename"
                commands.add("create_file: $fullPath")
            }
            
            return commands
        }

        // Template content generators
        private fun getKotlinMainActivityTemplate(): String = """
package com.company.app

import android.os.Bundle
import androidx.activity.ComponentActivity
import androidx.activity.compose.setContent
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Surface
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.ui.Modifier
import androidx.compose.ui.tooling.preview.Preview
import com.company.app.ui.theme.AppTheme

class MainActivity : ComponentActivity() {
    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        setContent {
            AppTheme {
                Surface(
                    modifier = Modifier.fillMaxSize(),
                    color = MaterialTheme.colorScheme.background
                ) {
                    Greeting("Android")
                }
            }
        }
    }
}

@Composable
fun Greeting(name: String, modifier: Modifier = Modifier) {
    Text(
        text = "Hello ${'$'}name!",
        modifier = modifier
    )
}

@Preview(showBackground = true)
@Composable
fun GreetingPreview() {
    AppTheme {
        Greeting("Android")
    }
}
        """.trimIndent()

        private fun getKotlinApplicationTemplate(): String = """
package com.company.app

import android.app.Application
import dagger.hilt.android.HiltAndroidApp

@HiltAndroidApp
class App : Application() {
    override fun onCreate() {
        super.onCreate()
        // Initialize app-wide dependencies
    }
}
        """.trimIndent()

        private fun getKotlinBuildGradleTemplate(): String = """
plugins {
    id("com.android.application")
    id("org.jetbrains.kotlin.android")
    id("dagger.hilt.android.plugin")
    id("kotlin-kapt")
}

android {
    namespace = "com.company.app"
    compileSdk = 34

    defaultConfig {
        applicationId = "com.company.app"
        minSdk = 24
        targetSdk = 34
        versionCode = 1
        versionName = "1.0"

        testInstrumentationRunner = "androidx.test.runner.AndroidJUnitRunner"
        vectorDrawables {
            useSupportLibrary = true
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
    
    packaging {
        resources {
            excludes += "/META-INF/{AL2.0,LGPL2.1}"
        }
    }
}

dependencies {
    implementation("androidx.core:core-ktx:1.12.0")
    implementation("androidx.lifecycle:lifecycle-runtime-ktx:2.7.0")
    implementation("androidx.activity:activity-compose:1.8.2")
    implementation(platform("androidx.compose:compose-bom:2023.10.01"))
    implementation("androidx.compose.ui:ui")
    implementation("androidx.compose.ui:ui-graphics")
    implementation("androidx.compose.ui:ui-tooling-preview")
    implementation("androidx.compose.material3:material3")
    
    // Hilt
    implementation("com.google.dagger:hilt-android:2.48")
    kapt("com.google.dagger:hilt-compiler:2.48")
    
    // Testing
    testImplementation("junit:junit:4.13.2")
    androidTestImplementation("androidx.test.ext:junit:1.1.5")
    androidTestImplementation("androidx.test.espresso:espresso-core:3.5.1")
    androidTestImplementation(platform("androidx.compose:compose-bom:2023.10.01"))
    androidTestImplementation("androidx.compose.ui:ui-test-junit4")
    debugImplementation("androidx.compose.ui:ui-tooling")
    debugImplementation("androidx.compose.ui:ui-test-manifest")
}
        """.trimIndent()

        private fun getAndroidManifestTemplate(): String = """
<?xml version="1.0" encoding="utf-8"?>
<manifest xmlns:android="http://schemas.android.com/apk/res/android"
    xmlns:tools="http://schemas.android.com/tools">

    <application
        android:name=".App"
        android:allowBackup="true"
        android:dataExtractionRules="@xml/data_extraction_rules"
        android:fullBackupContent="@xml/backup_rules"
        android:icon="@mipmap/ic_launcher"
        android:label="@string/app_name"
        android:roundIcon="@mipmap/ic_launcher_round"
        android:supportsRtl="true"
        android:theme="@style/Theme.App"
        tools:targetApi="31">
        <activity
            android:name=".MainActivity"
            android:exported="true"
            android:label="@string/app_name"
            android:theme="@style/Theme.App">
            <intent-filter>
                <action android:name="android.intent.action.MAIN" />
                <category android:name="android.intent.category.LAUNCHER" />
            </intent-filter>
        </activity>
    </application>

</manifest>
        """.trimIndent()

        private fun getSpringBootApplicationTemplate(): String = """
package com.company.app;

import org.springframework.boot.SpringApplication;
import org.springframework.boot.autoconfigure.SpringBootApplication;

@SpringBootApplication
public class Application {
    public static void main(String[] args) {
        SpringApplication.run(Application.class, args);
    }
}
        """.trimIndent()

        private fun getSpringBootControllerTemplate(): String = """
package com.company.app.controller;

import com.company.app.service.ApplicationService;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.http.ResponseEntity;
import org.springframework.web.bind.annotation.*;

@RestController
@RequestMapping("/api")
@CrossOrigin(origins = "*")
public class ApplicationController {

    @Autowired
    private ApplicationService applicationService;

    @GetMapping("/health")
    public ResponseEntity<String> health() {
        return ResponseEntity.ok("Application is running");
    }

    @GetMapping("/data")
    public ResponseEntity<?> getData() {
        return ResponseEntity.ok(applicationService.getData());
    }

    @PostMapping("/data")
    public ResponseEntity<?> createData(@RequestBody Object data) {
        return ResponseEntity.ok(applicationService.createData(data));
    }
}
        """.trimIndent()

        private fun getSpringBootServiceTemplate(): String = """
package com.company.app.service;

import org.springframework.stereotype.Service;
import java.util.List;
import java.util.ArrayList;

@Service
public class ApplicationService {

    public List<Object> getData() {
        // Implement your business logic here
        return new ArrayList<>();
    }

    public Object createData(Object data) {
        // Implement your business logic here
        return data;
    }
}
        """.trimIndent()

        private fun getSpringBootPomTemplate(): String = """
<?xml version="1.0" encoding="UTF-8"?>
<project xmlns="http://maven.apache.org/POM/4.0.0"
         xmlns:xsi="http://www.w3.org/2001/XMLSchema-instance"
         xsi:schemaLocation="http://maven.apache.org/POM/4.0.0 
         http://maven.apache.org/xsd/maven-4.0.0.xsd">
    <modelVersion>4.0.0</modelVersion>

    <parent>
        <groupId>org.springframework.boot</groupId>
        <artifactId>spring-boot-starter-parent</artifactId>
        <version>3.2.0</version>
        <relativePath/>
    </parent>

    <groupId>com.company</groupId>
    <artifactId>app</artifactId>
    <version>1.0.0</version>
    <name>app</name>
    <description>Spring Boot Application</description>

    <properties>
        <java.version>17</java.version>
    </properties>

    <dependencies>
        <dependency>
            <groupId>org.springframework.boot</groupId>
            <artifactId>spring-boot-starter-web</artifactId>
        </dependency>
        <dependency>
            <groupId>org.springframework.boot</groupId>
            <artifactId>spring-boot-starter-data-jpa</artifactId>
        </dependency>
        <dependency>
            <groupId>org.springframework.boot</groupId>
            <artifactId>spring-boot-starter-validation</artifactId>
        </dependency>
        <dependency>
            <groupId>com.h2database</groupId>
            <artifactId>h2</artifactId>
            <scope>runtime</scope>
        </dependency>
        <dependency>
            <groupId>org.springframework.boot</groupId>
            <artifactId>spring-boot-starter-test</artifactId>
            <scope>test</scope>
        </dependency>
    </dependencies>

    <build>
        <plugins>
            <plugin>
                <groupId>org.springframework.boot</groupId>
                <artifactId>spring-boot-maven-plugin</artifactId>
            </plugin>
        </plugins>
    </build>
</project>
        """.trimIndent()

        private fun getSpringBootPropertiesTemplate(): String = """
spring.application.name=app
server.port=8080

# Database Configuration
spring.datasource.url=jdbc:h2:mem:testdb
spring.datasource.driver-class-name=org.h2.Driver
spring.datasource.username=sa
spring.datasource.password=

# JPA Configuration
spring.jpa.database-platform=org.hibernate.dialect.H2Dialect
spring.jpa.hibernate.ddl-auto=create-drop
spring.jpa.show-sql=true

# H2 Console (for development)
spring.h2.console.enabled=true
spring.h2.console.path=/h2-console
        """.trimIndent()

        private fun getNextJsPackageJsonTemplate(): String = """
{
  "name": "nextjs-app",
  "version": "0.1.0",
  "private": true,
  "scripts": {
    "dev": "next dev",
    "build": "next build",
    "start": "next start",
    "lint": "next lint",
    "type-check": "tsc --noEmit"
  },
  "dependencies": {
    "next": "14.0.4",
    "react": "^18",
    "react-dom": "^18"
  },
  "devDependencies": {
    "@types/node": "^20",
    "@types/react": "^18",
    "@types/react-dom": "^18",
    "autoprefixer": "^10.0.1",
    "eslint": "^8",
    "eslint-config-next": "14.0.4",
    "postcss": "^8",
    "tailwindcss": "^3.3.0",
    "typescript": "^5"
  }
}
        """.trimIndent()

        private fun getNextJsConfigTemplate(): String = """
/** @type {import('next').NextConfig} */
const nextConfig = {
  experimental: {
    appDir: true,
  },
  images: {
    domains: [],
  },
}

module.exports = nextConfig
        """.trimIndent()

        private fun getNextJsTsConfigTemplate(): String = """
{
  "compilerOptions": {
    "target": "es5",
    "lib": ["dom", "dom.iterable", "es6"],
    "allowJs": true,
    "skipLibCheck": true,
    "strict": true,
    "noEmit": true,
    "esModuleInterop": true,
    "module": "esnext",
    "moduleResolution": "bundler",
    "resolveJsonModule": true,
    "isolatedModules": true,
    "jsx": "preserve",
    "incremental": true,
    "plugins": [
      {
        "name": "next"
      }
    ],
    "baseUrl": ".",
    "paths": {
      "@/*": ["./*"]
    }
  },
  "include": ["next-env.d.ts", "**/*.ts", "**/*.tsx", ".next/types/**/*.ts"],
  "exclude": ["node_modules"]
}
        """.trimIndent()

        private fun getNextJsTailwindConfigTemplate(): String = """
/** @type {import('tailwindcss').Config} */
module.exports = {
  content: [
    './pages/**/*.{js,ts,jsx,tsx,mdx}',
    './components/**/*.{js,ts,jsx,tsx,mdx}',
    './app/**/*.{js,ts,jsx,tsx,mdx}',
  ],
  theme: {
    extend: {},
  },
  plugins: [],
}
        """.trimIndent()

        private fun getNextJsLayoutTemplate(): String = """
import './globals.css'
import type { Metadata } from 'next'
import { Inter } from 'next/font/google'

const inter = Inter({ subsets: ['latin'] })

export const metadata: Metadata = {
  title: 'Next.js App',
  description: 'Generated by create next app',
}

export default function RootLayout({
  children,
}: {
  children: React.ReactNode
}) {
  return (
    <html lang="en">
      <body className={inter.className}>{children}</body>
    </html>
  )
}
        """.trimIndent()

        private fun getNextJsPageTemplate(): String = """
export default function Home() {
  return (
    <main className="flex min-h-screen flex-col items-center justify-between p-24">
      <div className="z-10 max-w-5xl w-full items-center justify-between font-mono text-sm lg:flex">
        <h1 className="text-4xl font-bold">Welcome to Next.js!</h1>
      </div>
    </main>
  )
}
        """.trimIndent()

        private fun getFlaskAppTemplate(): String = """
from flask import Flask
from flask_sqlalchemy import SQLAlchemy
from flask_migrate import Migrate
from config import Config

db = SQLAlchemy()
migrate = Migrate()

def create_app(config_class=Config):
    app = Flask(__name__)
    app.config.from_object(config_class)

    db.init_app(app)
    migrate.init_app(app, db)

    from app.views import bp as main_bp
    app.register_blueprint(main_bp)

    return app

if __name__ == '__main__':
    app = create_app()
    app.run(debug=True)
        """.trimIndent()

        private fun getFlaskRequirementsTemplate(): String = """
Flask==3.0.0
Flask-SQLAlchemy==3.1.1
Flask-Migrate==4.0.5
Flask-WTF==1.2.1
python-dotenv==1.0.0
gunicorn==21.2.0
Werkzeug==3.0.1
        """.trimIndent()

        private fun getFlaskConfigTemplate(): String = """
import os
from dotenv import load_dotenv

basedir = os.path.abspath(os.path.dirname(__file__))
load_dotenv(os.path.join(basedir, '.env'))

class Config:
    SECRET_KEY = os.environ.get('SECRET_KEY') or 'dev-secret-key'
    SQLALCHEMY_DATABASE_URI = os.environ.get('DATABASE_URL') or \
        'sqlite:///' + os.path.join(basedir, 'app.db')
    SQLALCHEMY_TRACK_MODIFICATIONS = False
        """.trimIndent()

        private fun getFlaskInitTemplate(): String = """
from flask import Flask
from flask_sqlalchemy import SQLAlchemy
from flask_migrate import Migrate
from config import Config

db = SQLAlchemy()
migrate = Migrate()

def create_app(config_class=Config):
    app = Flask(__name__)
    app.config.from_object(config_class)

    db.init_app(app)
    migrate.init_app(app, db)

    from app.views import bp as main_bp
    app.register_blueprint(main_bp)

    return app
        """.trimIndent()

        private fun getFlaskModelsInitTemplate(): String = """
from app import db

class User(db.Model):
    id = db.Column(db.Integer, primary_key=True)
    username = db.Column(db.String(80), unique=True, nullable=False)
    email = db.Column(db.String(120), unique=True, nullable=False)

    def __repr__(self):
        return f'<User {self.username}>'
        """.trimIndent()

        private fun getFlaskViewsInitTemplate(): String = """
from flask import Blueprint, render_template, jsonify
from app.models import User

bp = Blueprint('main', __name__)

@bp.route('/')
def index():
    return render_template('index.html')

@bp.route('/api/users')
def users():
    users = User.query.all()
    return jsonify([{'id': u.id, 'username': u.username, 'email': u.email} for u in users])
        """.trimIndent()

        private fun getReactPackageJsonTemplate(): String = """
{
  "name": "react-app",
  "version": "0.1.0",
  "private": true,
  "dependencies": {
    "react": "^18.2.0",
    "react-dom": "^18.2.0",
    "react-router-dom": "^6.8.0",
    "axios": "^1.3.0",
    "web-vitals": "^3.1.0"
  },
  "devDependencies": {
    "@types/react": "^18.0.0",
    "@types/react-dom": "^18.0.0",
    "@typescript-eslint/eslint-plugin": "^5.0.0",
    "@typescript-eslint/parser": "^5.0.0",
    "eslint": "^8.0.0",
    "eslint-plugin-react": "^7.0.0",
    "eslint-plugin-react-hooks": "^4.0.0",
    "prettier": "^2.8.0",
    "typescript": "^4.9.0",
    "vite": "^4.0.0",
    "@vitejs/plugin-react": "^3.0.0"
  },
  "scripts": {
    "dev": "vite",
    "build": "tsc && vite build",
    "preview": "vite preview",
    "lint": "eslint src --ext ts,tsx --report-unused-disable-directives --max-warnings 0",
    "format": "prettier --write src/**/*.{ts,tsx,css,md}"
  }
}
        """.trimIndent()

        private fun getReactTsConfigTemplate(): String = """
{
  "compilerOptions": {
    "target": "ES2020",
    "useDefineForClassFields": true,
    "lib": ["ES2020", "DOM", "DOM.Iterable"],
    "module": "ESNext",
    "skipLibCheck": true,
    "moduleResolution": "bundler",
    "allowImportingTsExtensions": true,
    "resolveJsonModule": true,
    "isolatedModules": true,
    "noEmit": true,
    "jsx": "react-jsx",
    "strict": true,
    "noUnusedLocals": true,
    "noUnusedParameters": true,
    "noFallthroughCasesInSwitch": true,
    "baseUrl": ".",
    "paths": {
      "@/*": ["./src/*"]
    }
  },
  "include": ["src"],
  "references": [{ "path": "./tsconfig.node.json" }]
}
        """.trimIndent()

        private fun getReactAppTemplate(): String = """
import React from 'react';
import { BrowserRouter as Router, Routes, Route } from 'react-router-dom';
import Layout from './components/Layout';
import Home from './pages/Home';
import './App.css';

function App() {
  return (
    <Router>
      <Layout>
        <Routes>
          <Route path="/" element={<Home />} />
        </Routes>
      </Layout>
    </Router>
  );
}

export default App;
        """.trimIndent()

        private fun getReactIndexTemplate(): String = """
import React from 'react';
import ReactDOM from 'react-dom/client';
import App from './App';
import './index.css';

const root = ReactDOM.createRoot(
  document.getElementById('root') as HTMLElement
);

root.render(
  <React.StrictMode>
    <App />
  </React.StrictMode>
);
        """.trimIndent()

        private fun getReactLayoutTemplate(): String = """
import React, { ReactNode } from 'react';

interface LayoutProps {
  children: ReactNode;
}

const Layout: React.FC<LayoutProps> = ({ children }) => {
  return (
    <div className="min-h-screen bg-gray-50">
      <header className="bg-white shadow">
        <div className="max-w-7xl mx-auto py-6 px-4 sm:px-6 lg:px-8">
          <h1 className="text-3xl font-bold text-gray-900">React App</h1>
        </div>
      </header>
      <main className="max-w-7xl mx-auto py-6 sm:px-6 lg:px-8">
        {children}
      </main>
    </div>
  );
};

export default Layout;
        """.trimIndent()

        private fun getExpressPackageJsonTemplate(): String = """
{
  "name": "express-app",
  "version": "1.0.0",
  "description": "Express.js application with TypeScript",
  "main": "dist/server.js",
  "scripts": {
    "start": "node dist/server.js",
    "dev": "nodemon src/server.ts",
    "build": "tsc",
    "lint": "eslint src/**/*.ts",
    "test": "jest"
  },
  "dependencies": {
    "express": "^4.18.0",
    "cors": "^2.8.5",
    "helmet": "^6.0.0",
    "dotenv": "^16.0.0",
    "morgan": "^1.10.0"
  },
  "devDependencies": {
    "@types/express": "^4.17.0",
    "@types/cors": "^2.8.0",
    "@types/morgan": "^1.9.0",
    "@types/node": "^18.0.0",
    "@typescript-eslint/eslint-plugin": "^5.0.0",
    "@typescript-eslint/parser": "^5.0.0",
    "eslint": "^8.0.0",
    "jest": "^28.0.0",
    "nodemon": "^2.0.0",
    "supertest": "^6.2.0",
    "ts-node": "^10.0.0",
    "typescript": "^4.9.0"
  }
}
        """.trimIndent()

        private fun getExpressTsConfigTemplate(): String = """
{
  "compilerOptions": {
    "target": "ES2020",
    "module": "commonjs",
    "outDir": "./dist",
    "rootDir": "./src",
    "strict": true,
    "esModuleInterop": true,
    "skipLibCheck": true,
    "forceConsistentCasingInFileNames": true,
    "resolveJsonModule": true,
    "declaration": true,
    "declarationMap": true,
    "sourceMap": true,
    "baseUrl": ".",
    "paths": {
      "@/*": ["./src/*"]
    }
  },
  "include": ["src/**/*"],
  "exclude": ["node_modules", "dist"]
}
        """.trimIndent()

        private fun getExpressAppTemplate(): String = """
import express from 'express';
import cors from 'cors';
import helmet from 'helmet';
import morgan from 'morgan';
import dotenv from 'dotenv';
import routes from './routes';

dotenv.config();

const app = express();

// Middleware
app.use(helmet());
app.use(cors());
app.use(morgan('combined'));
app.use(express.json());
app.use(express.urlencoded({ extended: true }));

// Routes
app.use('/api', routes);

// Health check
app.get('/health', (req, res) => {
  res.status(200).json({ status: 'OK', timestamp: new Date().toISOString() });
});

// Error handling middleware
app.use((err: any, req: express.Request, res: express.Response, next: express.NextFunction) => {
  console.error(err.stack);
  res.status(500).json({ error: 'Something went wrong!' });
});

export default app;
        """.trimIndent()

        private fun getExpressServerTemplate(): String = """
import app from './app';

const PORT = process.env.PORT || 3000;

app.listen(PORT, () => {
  console.log(`Server is running on port ${'$'}{PORT}`);
});
        """.trimIndent()

        private fun getExpressRoutesTemplate(): String = """
import { Router } from 'express';

const router = Router();

router.get('/', (req, res) => {
  res.json({ message: 'Express API is running!' });
});

router.get('/users', (req, res) => {
  // Example route - implement your logic here
  res.json({ users: [] });
});

export default router;
        """.trimIndent()

        // Pattern generators
        private fun getKotlinPatterns(): List<CodePattern> = listOf(
            CodePattern(
                name = "Composable Component",
                description = "Jetpack Compose UI component",
                template = """
@Composable
fun {{ComponentName}}(
    {{parameters}},
    modifier: Modifier = Modifier
) {
    {{content}}
}
                """.trimIndent(),
                variables = listOf("ComponentName", "parameters", "content")
            ),
            CodePattern(
                name = "ViewModel",
                description = "MVVM ViewModel with StateFlow",
                template = """
@HiltViewModel
class {{ViewModelName}} @Inject constructor(
    {{dependencies}}
) : ViewModel() {
    
    private val _state = MutableStateFlow({{InitialState}})
    val state: StateFlow<{{StateType}}> = _state.asStateFlow()
    
    {{functions}}
}
                """.trimIndent(),
                variables = listOf("ViewModelName", "dependencies", "InitialState", "StateType", "functions")
            ),
            CodePattern(
                name = "Repository",
                description = "Data repository pattern",
                template = """
@Singleton
class {{RepositoryName}} @Inject constructor(
    {{dependencies}}
) {
    
    suspend fun {{functionName}}({{parameters}}): Result<{{ReturnType}}> {
        return try {
            {{implementation}}
            Result.success({{result}})
        } catch (e: Exception) {
            Result.failure(e)
        }
    }
}
                """.trimIndent(),
                variables = listOf("RepositoryName", "dependencies", "functionName", "parameters", "ReturnType", "implementation", "result")
            )
        )

        private fun getSpringBootPatterns(): List<CodePattern> = listOf(
            CodePattern(
                name = "REST Controller",
                description = "Spring Boot REST controller",
                template = """
@RestController
@RequestMapping("/api/{{endpoint}}")
@CrossOrigin(origins = "*")
public class {{ControllerName}} {

    @Autowired
    private {{ServiceName}} {{serviceName}};

    @GetMapping
    public ResponseEntity<List<{{EntityName}}>> getAll() {
        return ResponseEntity.ok({{serviceName}}.findAll());
    }

    @GetMapping("/{id}")
    public ResponseEntity<{{EntityName}}> getById(@PathVariable {{IdType}} id) {
        return ResponseEntity.ok({{serviceName}}.findById(id));
    }

    @PostMapping
    public ResponseEntity<{{EntityName}}> create(@RequestBody {{EntityName}} entity) {
        return ResponseEntity.ok({{serviceName}}.save(entity));
    }
}
                """.trimIndent(),
                variables = listOf("endpoint", "ControllerName", "ServiceName", "serviceName", "EntityName", "IdType")
            ),
            CodePattern(
                name = "JPA Entity",
                description = "JPA entity with validation",
                template = """
@Entity
@Table(name = "{{tableName}}")
public class {{EntityName}} {

    @Id
    @GeneratedValue(strategy = GenerationType.IDENTITY)
    private {{IdType}} id;

    {{fields}}

    // Constructors
    public {{EntityName}}() {}

    {{gettersAndSetters}}
}
                """.trimIndent(),
                variables = listOf("tableName", "EntityName", "IdType", "fields", "gettersAndSetters")
            )
        )

        private fun getNextJsPatterns(): List<CodePattern> = listOf(
            CodePattern(
                name = "Server Component",
                description = "Next.js 13+ server component",
                template = """
import { {{imports}} } from '{{importPath}}';

interface {{ComponentName}}Props {
  {{props}}
}

export default function {{ComponentName}}({ {{propNames}} }: {{ComponentName}}Props) {
  {{content}}

  return (
    {{jsx}}
  );
}
                """.trimIndent(),
                variables = listOf("imports", "importPath", "ComponentName", "props", "propNames", "content", "jsx")
            ),
            CodePattern(
                name = "API Route",
                description = "Next.js API route handler",
                template = """
import { NextRequest, NextResponse } from 'next/server';

export async function {{method}}(request: NextRequest) {
  try {
    {{implementation}}
    
    return NextResponse.json({{successResponse}});
  } catch (error) {
    console.error('{{errorContext}}:', error);
    return NextResponse.json(
      { error: '{{errorMessage}}' },
      { status: {{errorStatus}} }
    );
  }
}
                """.trimIndent(),
                variables = listOf("method", "implementation", "successResponse", "errorContext", "errorMessage", "errorStatus")
            )
        )

        private fun getFlaskPatterns(): List<CodePattern> = listOf(
            CodePattern(
                name = "Blueprint Route",
                description = "Flask blueprint route with error handling",
                template = """
from flask import request, jsonify
from {{blueprint}} import {{blueprintName}}

@{{blueprintName}}.route('/{{endpoint}}', methods=['{{methods}}'])
def {{functionName}}({{parameters}}):
    try:
        {{implementation}}
        return jsonify({{successResponse}}), {{successStatus}}
    except Exception as e:
        return jsonify({'error': str(e)}), {{errorStatus}}
                """.trimIndent(),
                variables = listOf("blueprint", "blueprintName", "endpoint", "methods", "functionName", "parameters", "implementation", "successResponse", "successStatus", "errorStatus")
            ),
            CodePattern(
                name = "SQLAlchemy Model",
                description = "Flask SQLAlchemy model",
                template = """
from app import db
from datetime import datetime

class {{ModelName}}(db.Model):
    __tablename__ = '{{tableName}}'
    
    id = db.Column(db.Integer, primary_key=True)
    {{fields}}
    created_at = db.Column(db.DateTime, default=datetime.utcnow)
    updated_at = db.Column(db.DateTime, default=datetime.utcnow, onupdate=datetime.utcnow)

    def __repr__(self):
        return f'<{{ModelName}} {self.id}>'

    def to_dict(self):
        return {
            'id': self.id,
            {{dictFields}}
            'created_at': self.created_at.isoformat(),
            'updated_at': self.updated_at.isoformat()
        }
                """.trimIndent(),
                variables = listOf("ModelName", "tableName", "fields", "dictFields")
            )
        )

        private fun getReactPatterns(): List<CodePattern> = listOf(
            CodePattern(
                name = "Functional Component",
                description = "React functional component with hooks",
                template = """
import React, { {{hooks}} } from 'react';
{{additionalImports}}

interface {{ComponentName}}Props {
  {{props}}
}

const {{ComponentName}}: React.FC<{{ComponentName}}Props> = ({ {{propNames}} }) => {
  {{stateAndEffects}}

  {{functions}}

  return (
    {{jsx}}
  );
};

export default {{ComponentName}};
                """.trimIndent(),
                variables = listOf("hooks", "additionalImports", "ComponentName", "props", "propNames", "stateAndEffects", "functions", "jsx")
            ),
            CodePattern(
                name = "Custom Hook",
                description = "Reusable React custom hook",
                template = """
import { {{hooks}} } from 'react';
{{additionalImports}}

interface {{HookName}}Return {
  {{returnType}}
}

export const {{hookName}} = ({{parameters}}): {{HookName}}Return => {
  {{implementation}}

  return {
    {{returnObject}}
  };
};
                """.trimIndent(),
                variables = listOf("hooks", "additionalImports", "HookName", "returnType", "hookName", "parameters", "implementation", "returnObject")
            )
        )

        private fun getExpressPatterns(): List<CodePattern> = listOf(
            CodePattern(
                name = "Controller",
                description = "Express controller with error handling",
                template = """
import { Request, Response, NextFunction } from 'express';
import { {{ServiceName}} } from '../services/{{serviceName}}';

export class {{ControllerName}} {
  private {{serviceName}} = new {{ServiceName}}();

  {{methodName}} = async (req: Request, res: Response, next: NextFunction) => {
    try {
      {{implementation}}
      res.status({{successStatus}}).json({{successResponse}});
    } catch (error) {
      next(error);
    }
  };
}
                """.trimIndent(),
                variables = listOf("ServiceName", "serviceName", "ControllerName", "methodName", "implementation", "successStatus", "successResponse")
            ),
            CodePattern(
                name = "Middleware",
                description = "Express middleware function",
                template = """
import { Request, Response, NextFunction } from 'express';

export const {{middlewareName}} = ({{parameters}}) => {
  return (req: Request, res: Response, next: NextFunction) => {
    try {
      {{implementation}}
      next();
    } catch (error) {
      res.status({{errorStatus}}).json({ error: '{{errorMessage}}' });
    }
  };
};
                """.trimIndent(),
                variables = listOf("middlewareName", "parameters", "implementation", "errorStatus", "errorMessage")
            )
        )
    }
}