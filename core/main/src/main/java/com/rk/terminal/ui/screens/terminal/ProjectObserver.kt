package com.rk.terminal.ui.screens.terminal

import org.json.JSONArray
import org.json.JSONObject
import java.io.File
import java.nio.file.*
import java.util.concurrent.ConcurrentHashMap
import java.util.concurrent.Executors
import java.util.concurrent.ScheduledExecutorService
import java.util.concurrent.TimeUnit
import kotlinx.coroutines.*

/**
 * Enhanced Project Observer for AI Android Agent
 * 
 * This observer monitors projects of any type, adapts to different frameworks
 * and structures, and provides intelligent insights for code generation and
 * project management beyond predefined templates.
 */
class ProjectObserver(
    private val projectPath: String,
    private val onProjectChange: (ProjectChangeEvent) -> Unit = {},
    private val onAnalysisUpdate: (CodeStructureAnalyzer.ProjectAnalysis) -> Unit = {}
) {

    data class ProjectChangeEvent(
        val type: ChangeType,
        val path: String,
        val fileName: String,
        val timestamp: Long,
        val details: String = "",
        val impact: ChangeImpact = ChangeImpact.LOW,
        val suggestedActions: List<String> = emptyList()
    )

    enum class ChangeType {
        FILE_CREATED,
        FILE_MODIFIED,
        FILE_DELETED,
        FILE_MOVED,
        DIRECTORY_CREATED,
        DIRECTORY_DELETED,
        DEPENDENCY_ADDED,
        DEPENDENCY_REMOVED,
        CONFIG_CHANGED,
        BUILD_FILE_MODIFIED,
        TEST_ADDED,
        TEST_MODIFIED
    }

    enum class ChangeImpact {
        LOW,    // Minor changes like comments, formatting
        MEDIUM, // Code changes that don't affect public API
        HIGH,   // API changes, architectural modifications
        CRITICAL // Breaking changes, major refactoring needed
    }

    data class ProjectState(
        val analysis: CodeStructureAnalyzer.ProjectAnalysis,
        val watchedFiles: Set<String>,
        val lastModified: Map<String, Long>,
        val fileHashes: Map<String, String>,
        val activeTasks: List<String>,
        val pendingChanges: List<ProjectChangeEvent>,
        val adaptations: List<ProjectAdaptation>
    )

    data class ProjectAdaptation(
        val trigger: String,
        val description: String,
        val adaptationType: AdaptationType,
        val confidence: Double,
        val suggestions: List<String>,
        val autoApplicable: Boolean
    )

    enum class AdaptationType {
        FRAMEWORK_MIGRATION,    // Detected migration between frameworks
        PATTERN_EVOLUTION,      // New patterns emerging in the codebase
        STRUCTURE_OPTIMIZATION, // Better organization suggestions
        DEPENDENCY_MANAGEMENT,  // Dependency updates or conflicts
        TESTING_STRATEGY,      // Testing approach improvements
        DOCUMENTATION_GAPS,    // Missing or outdated documentation
        PERFORMANCE_ISSUES,    // Performance bottlenecks detected
        SECURITY_CONCERNS      // Security issues identified
    }

    private var currentState: ProjectState? = null
    private val watchService: WatchService = FileSystems.getDefault().newWatchService()
    private val watchKeys = ConcurrentHashMap<WatchKey, String>()
    private val scheduler: ScheduledExecutorService = Executors.newScheduledThreadPool(2)
    private var isObserving = false
    private val observerScope = CoroutineScope(Dispatchers.IO + SupervisorJob())

    // Intelligence modules
    private val patternDetector = PatternDetector()
    private val adaptationEngine = AdaptationEngine()
    private val changeAnalyzer = ChangeAnalyzer()
    private val smartSuggestionEngine = SmartSuggestionEngine()

    companion object {
        private const val ANALYSIS_INTERVAL_SECONDS = 30L
        private const val CHANGE_DEBOUNCE_MS = 1000L
        private const val MAX_WATCHED_FILES = 10000
    }

    fun startObserving() {
        if (isObserving) return
        
        isObserving = true
        
        observerScope.launch {
            try {
                // Initial analysis
                performInitialAnalysis()
                
                // Setup file watching
                setupFileWatching()
                
                // Start periodic analysis
                startPeriodicAnalysis()
                
                // Monitor file system changes
                monitorFileChanges()
                
            } catch (e: Exception) {
                e.printStackTrace()
            }
        }
    }

    fun stopObserving() {
        isObserving = false
        observerScope.cancel()
        scheduler.shutdown()
        watchService.close()
    }

    fun getCurrentState(): ProjectState? = currentState

    fun forceAnalysis() {
        observerScope.launch {
            performFullAnalysis()
        }
    }

    fun getAdaptationSuggestions(): List<ProjectAdaptation> {
        return currentState?.adaptations ?: emptyList()
    }

    fun applyAdaptation(adaptation: ProjectAdaptation): Boolean {
        return if (adaptation.autoApplicable) {
            adaptationEngine.applyAdaptation(projectPath, adaptation)
        } else {
            false
        }
    }

    private suspend fun performInitialAnalysis() {
        val analysis = CodeStructureAnalyzer.analyzeProject(projectPath)
        val watchedFiles = identifyImportantFiles(analysis)
        val fileHashes = calculateFileHashes(watchedFiles)
        val lastModified = getLastModifiedTimes(watchedFiles)

        currentState = ProjectState(
            analysis = analysis,
            watchedFiles = watchedFiles,
            lastModified = lastModified,
            fileHashes = fileHashes,
            activeTasks = emptyList(),
            pendingChanges = emptyList(),
            adaptations = emptyList()
        )

        onAnalysisUpdate(analysis)
    }

    private suspend fun performFullAnalysis() {
        val analysis = CodeStructureAnalyzer.analyzeProject(projectPath)
        val state = currentState ?: return

        // Detect adaptations based on changes
        val adaptations = adaptationEngine.detectAdaptations(state.analysis, analysis)

        currentState = state.copy(
            analysis = analysis,
            adaptations = adaptations
        )

        onAnalysisUpdate(analysis)
    }

    private fun identifyImportantFiles(analysis: CodeStructureAnalyzer.ProjectAnalysis): Set<String> {
        val importantFiles = mutableSetOf<String>()

        // Add all source files
        analysis.structure.files.values
            .filter { it.type in setOf("source", "config", "test") }
            .forEach { importantFiles.add(it.path) }

        // Add config files
        analysis.structure.configFiles.forEach { importantFiles.add(it.path) }

        // Add critical framework files
        analysis.framework?.conventions?.configFiles?.forEach { configFile ->
            val fullPath = "${analysis.structure.rootPath}/$configFile"
            if (File(fullPath).exists()) {
                importantFiles.add(fullPath)
            }
        }

        return importantFiles
    }

    private fun calculateFileHashes(files: Set<String>): Map<String, String> {
        return files.associateWith { path ->
            try {
                val file = File(path)
                if (file.exists()) {
                    file.readText().hashCode().toString()
                } else {
                    ""
                }
            } catch (e: Exception) {
                ""
            }
        }
    }

    private fun getLastModifiedTimes(files: Set<String>): Map<String, Long> {
        return files.associateWith { path ->
            try {
                File(path).lastModified()
            } catch (e: Exception) {
                0L
            }
        }
    }

    private suspend fun setupFileWatching() {
        val projectDir = File(projectPath)
        registerDirectoryRecursively(projectDir)
    }

    private fun registerDirectoryRecursively(dir: File) {
        if (!dir.isDirectory) return

        try {
            val path = dir.toPath()
            val watchKey = path.register(
                watchService,
                StandardWatchEventKinds.ENTRY_CREATE,
                StandardWatchEventKinds.ENTRY_DELETE,
                StandardWatchEventKinds.ENTRY_MODIFY
            )
            watchKeys[watchKey] = dir.absolutePath

            // Register subdirectories
            dir.listFiles()?.forEach { file ->
                if (file.isDirectory && !file.name.startsWith(".") && 
                    file.name !in setOf("node_modules", "build", "dist", "target", ".git")) {
                    registerDirectoryRecursively(file)
                }
            }
        } catch (e: Exception) {
            // Ignore directories that can't be watched
        }
    }

    private fun startPeriodicAnalysis() {
        scheduler.scheduleAtFixedRate({
            observerScope.launch {
                performPeriodicAnalysis()
            }
        }, ANALYSIS_INTERVAL_SECONDS, ANALYSIS_INTERVAL_SECONDS, TimeUnit.SECONDS)
    }

    private suspend fun performPeriodicAnalysis() {
        val state = currentState ?: return

        // Check for file changes
        val changedFiles = detectFileChanges(state)
        
        if (changedFiles.isNotEmpty()) {
            // Perform incremental analysis
            val updatedAnalysis = CodeStructureAnalyzer.analyzeProject(projectPath)
            
            // Detect new patterns and adaptations
            val newAdaptations = adaptationEngine.detectAdaptations(state.analysis, updatedAnalysis)
            
            currentState = state.copy(
                analysis = updatedAnalysis,
                lastModified = getLastModifiedTimes(state.watchedFiles),
                fileHashes = calculateFileHashes(state.watchedFiles),
                adaptations = newAdaptations
            )

            onAnalysisUpdate(updatedAnalysis)
        }
    }

    private fun detectFileChanges(state: ProjectState): List<String> {
        val changedFiles = mutableListOf<String>()

        state.watchedFiles.forEach { filePath ->
            val file = File(filePath)
            if (file.exists()) {
                val currentModified = file.lastModified()
                val lastModified = state.lastModified[filePath] ?: 0L
                
                if (currentModified > lastModified) {
                    changedFiles.add(filePath)
                }
            }
        }

        return changedFiles
    }

    private suspend fun monitorFileChanges() {
        while (isObserving) {
            try {
                val watchKey = watchService.take()
                val dirPath = watchKeys[watchKey] ?: continue

                for (event in watchKey.pollEvents()) {
                    val kind = event.kind()
                    val fileName = event.context().toString()
                    val fullPath = "$dirPath/$fileName"

                    val changeEvent = createChangeEvent(kind, fullPath, fileName)
                    if (changeEvent != null) {
                        processChangeEvent(changeEvent)
                    }
                }

                watchKey.reset()
            } catch (e: InterruptedException) {
                break
            } catch (e: Exception) {
                e.printStackTrace()
            }
        }
    }

    private fun createChangeEvent(
        kind: WatchEvent.Kind<*>,
        fullPath: String,
        fileName: String
    ): ProjectChangeEvent? {
        val timestamp = System.currentTimeMillis()
        
        return when (kind) {
            StandardWatchEventKinds.ENTRY_CREATE -> {
                val changeType = if (File(fullPath).isDirectory) {
                    ChangeType.DIRECTORY_CREATED
                } else {
                    determineFileChangeType(fileName, ChangeType.FILE_CREATED)
                }
                
                ProjectChangeEvent(
                    type = changeType,
                    path = fullPath,
                    fileName = fileName,
                    timestamp = timestamp,
                    impact = assessChangeImpact(changeType, fileName),
                    suggestedActions = generateSuggestions(changeType, fileName, fullPath)
                )
            }
            
            StandardWatchEventKinds.ENTRY_DELETE -> {
                val changeType = if (fileName.contains(".")) {
                    determineFileChangeType(fileName, ChangeType.FILE_DELETED)
                } else {
                    ChangeType.DIRECTORY_DELETED
                }
                
                ProjectChangeEvent(
                    type = changeType,
                    path = fullPath,
                    fileName = fileName,
                    timestamp = timestamp,
                    impact = assessChangeImpact(changeType, fileName),
                    suggestedActions = generateSuggestions(changeType, fileName, fullPath)
                )
            }
            
            StandardWatchEventKinds.ENTRY_MODIFY -> {
                val changeType = determineFileChangeType(fileName, ChangeType.FILE_MODIFIED)
                
                ProjectChangeEvent(
                    type = changeType,
                    path = fullPath,
                    fileName = fileName,
                    timestamp = timestamp,
                    impact = assessChangeImpact(changeType, fileName),
                    suggestedActions = generateSuggestions(changeType, fileName, fullPath)
                )
            }
            
            else -> null
        }
    }

    private fun determineFileChangeType(fileName: String, defaultType: ChangeType): ChangeType {
        return when {
            isConfigFile(fileName) -> ChangeType.CONFIG_CHANGED
            isBuildFile(fileName) -> ChangeType.BUILD_FILE_MODIFIED
            isTestFile(fileName) -> if (defaultType == ChangeType.FILE_CREATED) ChangeType.TEST_ADDED else ChangeType.TEST_MODIFIED
            isDependencyFile(fileName) -> if (defaultType == ChangeType.FILE_CREATED) ChangeType.DEPENDENCY_ADDED else ChangeType.DEPENDENCY_REMOVED
            else -> defaultType
        }
    }

    private fun isConfigFile(fileName: String): Boolean {
        val configExtensions = setOf("json", "yml", "yaml", "properties", "conf", "config", "env")
        val configNames = setOf("package.json", "tsconfig.json", "next.config.js", "tailwind.config.js", 
                               "pom.xml", "build.gradle", "build.gradle.kts", "application.properties")
        
        return fileName.substringAfterLast('.') in configExtensions || fileName in configNames
    }

    private fun isBuildFile(fileName: String): Boolean {
        val buildFiles = setOf("build.gradle", "build.gradle.kts", "pom.xml", "package.json", 
                              "Dockerfile", "docker-compose.yml", "webpack.config.js")
        return fileName in buildFiles
    }

    private fun isTestFile(fileName: String): Boolean {
        return fileName.contains("test", ignoreCase = true) || 
               fileName.contains("spec", ignoreCase = true) ||
               fileName.endsWith("Test.kt") ||
               fileName.endsWith("Test.java") ||
               fileName.endsWith(".test.ts") ||
               fileName.endsWith(".test.js") ||
               fileName.endsWith("_test.py")
    }

    private fun isDependencyFile(fileName: String): Boolean {
        return fileName in setOf("package.json", "requirements.txt", "pom.xml", "build.gradle", "build.gradle.kts")
    }

    private fun assessChangeImpact(changeType: ChangeType, fileName: String): ChangeImpact {
        return when (changeType) {
            ChangeType.CONFIG_CHANGED, ChangeType.BUILD_FILE_MODIFIED -> ChangeImpact.HIGH
            ChangeType.DEPENDENCY_ADDED, ChangeType.DEPENDENCY_REMOVED -> ChangeImpact.HIGH
            ChangeType.FILE_DELETED -> ChangeImpact.MEDIUM
            ChangeType.DIRECTORY_DELETED -> ChangeImpact.HIGH
            ChangeType.FILE_CREATED -> {
                when {
                    isTestFile(fileName) -> ChangeImpact.LOW
                    fileName.endsWith(".md") -> ChangeImpact.LOW
                    else -> ChangeImpact.MEDIUM
                }
            }
            ChangeType.FILE_MODIFIED -> {
                when {
                    fileName.endsWith(".md") -> ChangeImpact.LOW
                    isTestFile(fileName) -> ChangeImpact.LOW
                    else -> ChangeImpact.MEDIUM
                }
            }
            else -> ChangeImpact.LOW
        }
    }

    private fun generateSuggestions(changeType: ChangeType, fileName: String, fullPath: String): List<String> {
        return smartSuggestionEngine.generateSuggestions(changeType, fileName, fullPath, currentState?.analysis)
    }

    private fun processChangeEvent(event: ProjectChangeEvent) {
        observerScope.launch {
            // Debounce rapid changes
            delay(CHANGE_DEBOUNCE_MS)
            
            val state = currentState ?: return@launch
            
            // Add to pending changes
            val updatedPendingChanges = state.pendingChanges + event
            
            currentState = state.copy(pendingChanges = updatedPendingChanges)
            
            // Notify observers
            onProjectChange(event)
            
            // Process high-impact changes immediately
            if (event.impact in setOf(ChangeImpact.HIGH, ChangeImpact.CRITICAL)) {
                performFullAnalysis()
            }
        }
    }

    // Pattern Detection Module
    private class PatternDetector {
        fun detectEmergingPatterns(
            oldAnalysis: CodeStructureAnalyzer.ProjectAnalysis,
            newAnalysis: CodeStructureAnalyzer.ProjectAnalysis
        ): List<CodeStructureAnalyzer.UsagePattern> {
            val newPatterns = mutableListOf<CodeStructureAnalyzer.UsagePattern>()
            
            // Compare pattern frequencies
            val oldPatternMap = oldAnalysis.patterns.associateBy { it.name }
            
            newAnalysis.patterns.forEach { newPattern ->
                val oldPattern = oldPatternMap[newPattern.name]
                
                if (oldPattern == null) {
                    // Completely new pattern
                    newPatterns.add(newPattern)
                } else if (newPattern.frequency > oldPattern.frequency * 1.5) {
                    // Pattern usage is growing significantly
                    newPatterns.add(newPattern.copy(
                        description = "${newPattern.description} (Growing usage detected)"
                    ))
                }
            }
            
            return newPatterns
        }
    }

    // Adaptation Engine Module
    private class AdaptationEngine {
        fun detectAdaptations(
            oldAnalysis: CodeStructureAnalyzer.ProjectAnalysis,
            newAnalysis: CodeStructureAnalyzer.ProjectAnalysis
        ): List<ProjectAdaptation> {
            val adaptations = mutableListOf<ProjectAdaptation>()
            
            // Framework migration detection
            if (oldAnalysis.framework?.name != newAnalysis.framework?.name) {
                adaptations.add(ProjectAdaptation(
                    trigger = "Framework change detected",
                    description = "Project appears to be migrating from ${oldAnalysis.framework?.name} to ${newAnalysis.framework?.name}",
                    adaptationType = AdaptationType.FRAMEWORK_MIGRATION,
                    confidence = 0.8,
                    suggestions = listOf(
                        "Update build configuration",
                        "Migrate existing components",
                        "Update dependencies",
                        "Adapt project structure"
                    ),
                    autoApplicable = false
                ))
            }
            
            // Structure optimization
            if (newAnalysis.recommendations.any { it.type == "structure" }) {
                adaptations.add(ProjectAdaptation(
                    trigger = "Structure improvements available",
                    description = "Better project organization patterns detected",
                    adaptationType = AdaptationType.STRUCTURE_OPTIMIZATION,
                    confidence = 0.9,
                    suggestions = newAnalysis.recommendations
                        .filter { it.type == "structure" }
                        .map { it.action },
                    autoApplicable = true
                ))
            }
            
            // Testing strategy improvements
            if (newAnalysis.qualityMetrics.testCoverage < 50) {
                adaptations.add(ProjectAdaptation(
                    trigger = "Low test coverage detected",
                    description = "Current test coverage is ${newAnalysis.qualityMetrics.testCoverage}%, recommend improving testing strategy",
                    adaptationType = AdaptationType.TESTING_STRATEGY,
                    confidence = 0.95,
                    suggestions = listOf(
                        "Add unit tests for core components",
                        "Implement integration tests",
                        "Set up automated testing pipeline",
                        "Add test coverage reporting"
                    ),
                    autoApplicable = false
                ))
            }
            
            return adaptations
        }
        
        fun applyAdaptation(projectPath: String, adaptation: ProjectAdaptation): Boolean {
            return when (adaptation.adaptationType) {
                AdaptationType.STRUCTURE_OPTIMIZATION -> applyStructureOptimization(projectPath, adaptation)
                AdaptationType.DOCUMENTATION_GAPS -> applyDocumentationImprovements(projectPath, adaptation)
                else -> false // Not auto-applicable
            }
        }
        
        private fun applyStructureOptimization(projectPath: String, adaptation: ProjectAdaptation): Boolean {
            return try {
                // Auto-create missing directories based on framework conventions
                adaptation.suggestions.forEach { suggestion ->
                    if (suggestion.startsWith("Create directory")) {
                        val dirName = suggestion.substringAfter("Create directory: ")
                        val dir = File("$projectPath/$dirName")
                        dir.mkdirs()
                    }
                }
                true
            } catch (e: Exception) {
                false
            }
        }
        
        private fun applyDocumentationImprovements(projectPath: String, adaptation: ProjectAdaptation): Boolean {
            return try {
                // Auto-generate basic README if missing
                val readmeFile = File("$projectPath/README.md")
                if (!readmeFile.exists()) {
                    val basicReadme = generateBasicReadme(projectPath)
                    readmeFile.writeText(basicReadme)
                }
                true
            } catch (e: Exception) {
                false
            }
        }
        
        private fun generateBasicReadme(projectPath: String): String {
            val projectName = File(projectPath).name
            return """
                # $projectName
                
                ## Description
                This project was automatically analyzed and documented.
                
                ## Setup
                [Setup instructions will be added based on detected framework]
                
                ## Usage
                [Usage instructions will be added based on project structure]
                
                ## Contributing
                [Contributing guidelines will be added]
                
                ## License
                [License information will be added]
            """.trimIndent()
        }
    }

    // Change Analyzer Module
    private class ChangeAnalyzer {
        fun analyzeChangeImpact(
            change: ProjectChangeEvent,
            analysis: CodeStructureAnalyzer.ProjectAnalysis?
        ): ChangeImpact {
            analysis ?: return change.impact
            
            return when {
                isBreakingChange(change, analysis) -> ChangeImpact.CRITICAL
                isArchitecturalChange(change, analysis) -> ChangeImpact.HIGH
                isAPIChange(change, analysis) -> ChangeImpact.HIGH
                isImplementationChange(change, analysis) -> ChangeImpact.MEDIUM
                else -> ChangeImpact.LOW
            }
        }
        
        private fun isBreakingChange(change: ProjectChangeEvent, analysis: CodeStructureAnalyzer.ProjectAnalysis): Boolean {
            // Check if deleted file was a public API
            if (change.type == ChangeType.FILE_DELETED) {
                val deletedFile = analysis.structure.files.values.find { it.path.endsWith(change.fileName) }
                return deletedFile?.exports?.isNotEmpty() == true
            }
            
            return false
        }
        
        private fun isArchitecturalChange(change: ProjectChangeEvent, analysis: CodeStructureAnalyzer.ProjectAnalysis): Boolean {
            // Check if core directories were affected
            val coreDirectories = setOf("src", "lib", "app", "core", "main")
            return coreDirectories.any { change.path.contains("/$it/") }
        }
        
        private fun isAPIChange(change: ProjectChangeEvent, analysis: CodeStructureAnalyzer.ProjectAnalysis): Boolean {
            // Check if public interfaces were modified
            return change.fileName.contains("api", ignoreCase = true) ||
                   change.fileName.contains("interface", ignoreCase = true) ||
                   change.fileName.contains("contract", ignoreCase = true)
        }
        
        private fun isImplementationChange(change: ProjectChangeEvent, analysis: CodeStructureAnalyzer.ProjectAnalysis): Boolean {
            // Most code changes are implementation changes
            return change.type in setOf(ChangeType.FILE_MODIFIED, ChangeType.FILE_CREATED) &&
                   !isAPIChange(change, analysis)
        }
    }

    // Smart Suggestion Engine Module
    private class SmartSuggestionEngine {
        fun generateSuggestions(
            changeType: ChangeType,
            fileName: String,
            fullPath: String,
            analysis: CodeStructureAnalyzer.ProjectAnalysis?
        ): List<String> {
            val suggestions = mutableListOf<String>()
            
            when (changeType) {
                ChangeType.FILE_CREATED -> {
                    suggestions.addAll(generateCreationSuggestions(fileName, fullPath, analysis))
                }
                ChangeType.FILE_MODIFIED -> {
                    suggestions.addAll(generateModificationSuggestions(fileName, fullPath, analysis))
                }
                ChangeType.FILE_DELETED -> {
                    suggestions.addAll(generateDeletionSuggestions(fileName, fullPath, analysis))
                }
                ChangeType.CONFIG_CHANGED -> {
                    suggestions.addAll(generateConfigChangeSuggestions(fileName, fullPath, analysis))
                }
                ChangeType.DEPENDENCY_ADDED -> {
                    suggestions.addAll(generateDependencyAddedSuggestions(fileName, fullPath, analysis))
                }
                ChangeType.TEST_ADDED -> {
                    suggestions.addAll(generateTestAddedSuggestions(fileName, fullPath, analysis))
                }
                else -> {
                    suggestions.add("Monitor for related changes")
                }
            }
            
            return suggestions
        }
        
        private fun generateCreationSuggestions(
            fileName: String,
            fullPath: String,
            analysis: CodeStructureAnalyzer.ProjectAnalysis?
        ): List<String> {
            val suggestions = mutableListOf<String>()
            
            when {
                fileName.endsWith(".kt") || fileName.endsWith(".java") -> {
                    suggestions.add("Consider adding unit tests for new class")
                    suggestions.add("Update documentation if this is a public API")
                    suggestions.add("Check if imports need to be added to other files")
                }
                fileName.endsWith(".ts") || fileName.endsWith(".js") -> {
                    suggestions.add("Add TypeScript types if not present")
                    suggestions.add("Consider adding tests for new functionality")
                    suggestions.add("Update export/import statements in related files")
                }
                fileName.endsWith(".py") -> {
                    suggestions.add("Add docstrings for new functions/classes")
                    suggestions.add("Consider adding type hints")
                    suggestions.add("Add corresponding test file if missing")
                }
                fileName.endsWith(".md") -> {
                    suggestions.add("Link to relevant documentation")
                    suggestions.add("Update table of contents if applicable")
                }
            }
            
            return suggestions
        }
        
        private fun generateModificationSuggestions(
            fileName: String,
            fullPath: String,
            analysis: CodeStructureAnalyzer.ProjectAnalysis?
        ): List<String> {
            val suggestions = mutableListOf<String>()
            
            suggestions.add("Review changes for breaking modifications")
            suggestions.add("Update related tests if functionality changed")
            suggestions.add("Check if documentation needs updates")
            
            if (analysis?.framework != null) {
                when (analysis.framework.language) {
                    "kotlin", "java" -> {
                        suggestions.add("Run static analysis tools")
                        suggestions.add("Check for compile errors")
                    }
                    "javascript", "typescript" -> {
                        suggestions.add("Run linter and type checker")
                        suggestions.add("Check for runtime errors")
                    }
                    "python" -> {
                        suggestions.add("Run flake8 or pylint")
                        suggestions.add("Check for syntax errors")
                    }
                }
            }
            
            return suggestions
        }
        
        private fun generateDeletionSuggestions(
            fileName: String,
            fullPath: String,
            analysis: CodeStructureAnalyzer.ProjectAnalysis?
        ): List<String> {
            return listOf(
                "Check for broken imports in other files",
                "Update references to deleted file",
                "Remove corresponding test files if they exist",
                "Update documentation that referenced this file"
            )
        }
        
        private fun generateConfigChangeSuggestions(
            fileName: String,
            fullPath: String,
            analysis: CodeStructureAnalyzer.ProjectAnalysis?
        ): List<String> {
            val suggestions = mutableListOf<String>()
            
            when (fileName) {
                "package.json" -> {
                    suggestions.add("Run npm install to update dependencies")
                    suggestions.add("Check for security vulnerabilities in new dependencies")
                    suggestions.add("Update lock file if needed")
                }
                "build.gradle", "build.gradle.kts" -> {
                    suggestions.add("Sync Gradle project")
                    suggestions.add("Check for dependency conflicts")
                    suggestions.add("Update Android SDK if applicable")
                }
                "pom.xml" -> {
                    suggestions.add("Update Maven dependencies")
                    suggestions.add("Check for version conflicts")
                }
                "requirements.txt" -> {
                    suggestions.add("Update Python virtual environment")
                    suggestions.add("Check for package compatibility")
                }
            }
            
            return suggestions
        }
        
        private fun generateDependencyAddedSuggestions(
            fileName: String,
            fullPath: String,
            analysis: CodeStructureAnalyzer.ProjectAnalysis?
        ): List<String> {
            return listOf(
                "Update dependency lock files",
                "Check for security vulnerabilities",
                "Verify compatibility with existing dependencies",
                "Update documentation with new dependency information",
                "Consider impact on build size and performance"
            )
        }
        
        private fun generateTestAddedSuggestions(
            fileName: String,
            fullPath: String,
            analysis: CodeStructureAnalyzer.ProjectAnalysis?
        ): List<String> {
            return listOf(
                "Run test suite to ensure new test passes",
                "Check test coverage improvement",
                "Ensure test follows project testing conventions",
                "Add test to CI/CD pipeline if not automated",
                "Update test documentation if needed"
            )
        }
    }
}