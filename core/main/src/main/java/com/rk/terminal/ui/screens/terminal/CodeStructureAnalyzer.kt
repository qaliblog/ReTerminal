package com.rk.terminal.ui.screens.terminal

import org.json.JSONArray
import org.json.JSONObject
import java.io.File
import java.util.regex.Pattern

/**
 * Advanced Code Structure Analyzer for AI Android Agent
 * 
 * This analyzer understands project structures across multiple frameworks,
 * maintains code conventions, and provides intelligent insights for
 * better code generation and project organization.
 */
class CodeStructureAnalyzer {

    data class ProjectAnalysis(
        val framework: FrameworkTemplateManager.FrameworkTemplate?,
        val structure: ProjectStructure,
        val conventions: DetectedConventions,
        val patterns: List<UsagePattern>,
        val dependencies: DependencyGraph,
        val qualityMetrics: CodeQualityMetrics,
        val recommendations: List<Recommendation>
    )

    data class ProjectStructure(
        val rootPath: String,
        val directories: Map<String, DirectoryInfo>,
        val files: Map<String, FileInfo>,
        val moduleStructure: List<ModuleInfo>,
        val configFiles: List<ConfigFileInfo>
    )

    data class DirectoryInfo(
        val path: String,
        val type: String, // "source", "test", "resource", "config", "build", "docs"
        val purpose: String,
        val fileCount: Int,
        val subdirectories: List<String>,
        val conventions: List<String>
    )

    data class FileInfo(
        val path: String,
        val name: String,
        val extension: String,
        val type: String, // "source", "test", "config", "resource", "documentation"
        val language: String,
        val size: Long,
        val complexity: Int,
        val dependencies: List<String>,
        val exports: List<String>,
        val imports: List<String>,
        val patterns: List<String>,
        val lastModified: Long
    )

    data class ModuleInfo(
        val name: String,
        val path: String,
        val type: String, // "main", "feature", "library", "plugin"
        val dependencies: List<String>,
        val exports: List<String>,
        val configFiles: List<String>
    )

    data class ConfigFileInfo(
        val path: String,
        val type: String, // "build", "dependency", "environment", "linting", "testing"
        val framework: String?,
        val content: Map<String, Any>
    )

    data class DetectedConventions(
        val fileNaming: String,
        val directoryStructure: String,
        val importStyle: String,
        val functionNaming: String,
        val classNaming: String,
        val variableNaming: String,
        val constantNaming: String,
        val indentation: String,
        val lineEndings: String,
        val commentStyle: String
    )

    data class UsagePattern(
        val name: String,
        val description: String,
        val frequency: Int,
        val locations: List<String>,
        val template: String,
        val confidence: Double
    )

    data class DependencyGraph(
        val nodes: Map<String, DependencyNode>,
        val edges: List<DependencyEdge>,
        val cycles: List<List<String>>,
        val layers: List<List<String>>
    )

    data class DependencyNode(
        val id: String,
        val type: String, // "file", "module", "package", "external"
        val path: String,
        val inDegree: Int,
        val outDegree: Int
    )

    data class DependencyEdge(
        val from: String,
        val to: String,
        val type: String, // "import", "extends", "implements", "calls", "uses"
        val strength: Double
    )

    data class CodeQualityMetrics(
        val complexity: Double,
        val maintainability: Double,
        val testCoverage: Double,
        val duplication: Double,
        val conventionCompliance: Double,
        val documentationCoverage: Double,
        val issuesByType: Map<String, Int>
    )

    data class Recommendation(
        val type: String, // "structure", "naming", "pattern", "dependency", "testing", "documentation"
        val priority: String, // "high", "medium", "low"
        val title: String,
        val description: String,
        val action: String,
        val impact: String,
        val effort: String
    )

    companion object {
        fun analyzeProject(projectPath: String): ProjectAnalysis {
            val projectDir = File(projectPath)
            if (!projectDir.exists() || !projectDir.isDirectory) {
                throw IllegalArgumentException("Project path does not exist or is not a directory: $projectPath")
            }

            // Detect framework
            val framework = FrameworkTemplateManager.detectFrameworkFromProject(projectPath)

            // Analyze structure
            val structure = analyzeProjectStructure(projectDir)

            // Detect conventions
            val conventions = detectConventions(structure)

            // Identify patterns
            val patterns = identifyUsagePatterns(structure)

            // Build dependency graph
            val dependencies = buildDependencyGraph(structure)

            // Calculate quality metrics
            val qualityMetrics = calculateQualityMetrics(structure, conventions, dependencies)

            // Generate recommendations
            val recommendations = generateRecommendations(structure, conventions, patterns, dependencies, qualityMetrics, framework)

            return ProjectAnalysis(
                framework = framework,
                structure = structure,
                conventions = conventions,
                patterns = patterns,
                dependencies = dependencies,
                qualityMetrics = qualityMetrics,
                recommendations = recommendations
            )
        }

        private fun analyzeProjectStructure(projectDir: File): ProjectStructure {
            val directories = mutableMapOf<String, DirectoryInfo>()
            val files = mutableMapOf<String, FileInfo>()
            val modules = mutableListOf<ModuleInfo>()
            val configFiles = mutableListOf<ConfigFileInfo>()

            fun analyzeDirectory(dir: File, relativePath: String = "") {
                val currentPath = if (relativePath.isEmpty()) "" else "$relativePath/"
                val dirPath = currentPath + dir.name

                val fileList = dir.listFiles() ?: return
                val subdirs = fileList.filter { it.isDirectory }.map { it.name }
                val fileCount = fileList.filter { it.isFile }.size

                directories[dirPath] = DirectoryInfo(
                    path = dirPath,
                    type = determineDirectoryType(dir.name, fileList),
                    purpose = determineDirectoryPurpose(dir.name, fileList),
                    fileCount = fileCount,
                    subdirectories = subdirs,
                    conventions = detectDirectoryConventions(dir.name, fileList)
                )

                fileList.forEach { file ->
                    if (file.isFile) {
                        val filePath = "$dirPath/${file.name}"
                        files[filePath] = analyzeFile(file, filePath)

                        // Check for config files
                        if (isConfigFile(file)) {
                            configFiles.add(analyzeConfigFile(file, filePath))
                        }
                    } else if (file.isDirectory) {
                        analyzeDirectory(file, dirPath)
                    }
                }

                // Check for module definition
                if (isModuleRoot(dir)) {
                    modules.add(analyzeModule(dir, dirPath))
                }
            }

            analyzeDirectory(projectDir)

            return ProjectStructure(
                rootPath = projectDir.absolutePath,
                directories = directories,
                files = files,
                moduleStructure = modules,
                configFiles = configFiles
            )
        }

        private fun analyzeFile(file: File, path: String): FileInfo {
            val extension = file.extension.lowercase()
            val language = determineLanguage(extension)
            val content = try { file.readText() } catch (e: Exception) { "" }

            return FileInfo(
                path = path,
                name = file.name,
                extension = extension,
                type = determineFileType(file.name, content),
                language = language,
                size = file.length(),
                complexity = calculateFileComplexity(content, language),
                dependencies = extractDependencies(content, language),
                exports = extractExports(content, language),
                imports = extractImports(content, language),
                patterns = identifyFilePatterns(content, language),
                lastModified = file.lastModified()
            )
        }

        private fun analyzeConfigFile(file: File, path: String): ConfigFileInfo {
            val content = try { file.readText() } catch (e: Exception) { "" }
            val type = determineConfigType(file.name)
            val framework = determineConfigFramework(file.name, content)
            val parsedContent = parseConfigContent(content, file.extension)

            return ConfigFileInfo(
                path = path,
                type = type,
                framework = framework,
                content = parsedContent
            )
        }

        private fun analyzeModule(dir: File, path: String): ModuleInfo {
            val files = dir.listFiles() ?: emptyArray()
            val configFiles = files.filter { isConfigFile(it) }.map { it.name }
            
            return ModuleInfo(
                name = dir.name,
                path = path,
                type = determineModuleType(dir.name, files.toList()),
                dependencies = extractModuleDependencies(files.toList()),
                exports = extractModuleExports(files.toList()),
                configFiles = configFiles
            )
        }

        private fun detectConventions(structure: ProjectStructure): DetectedConventions {
            val fileNames = structure.files.keys
            val sourceFiles = structure.files.values.filter { it.type == "source" }

            return DetectedConventions(
                fileNaming = detectFileNamingConvention(fileNames),
                directoryStructure = detectDirectoryStructureConvention(structure.directories.keys),
                importStyle = detectImportStyleConvention(sourceFiles),
                functionNaming = detectFunctionNamingConvention(sourceFiles),
                classNaming = detectClassNamingConvention(sourceFiles),
                variableNaming = detectVariableNamingConvention(sourceFiles),
                constantNaming = detectConstantNamingConvention(sourceFiles),
                indentation = detectIndentationConvention(sourceFiles),
                lineEndings = detectLineEndingConvention(sourceFiles),
                commentStyle = detectCommentStyleConvention(sourceFiles)
            )
        }

        private fun identifyUsagePatterns(structure: ProjectStructure): List<UsagePattern> {
            val patterns = mutableListOf<UsagePattern>()

            // Analyze common code patterns
            structure.files.values.filter { it.type == "source" }.forEach { file ->
                val content = try { File(file.path).readText() } catch (e: Exception) { "" }
                patterns.addAll(extractCodePatterns(content, file.language, file.path))
            }

            // Group and rank patterns by frequency
            return patterns.groupBy { it.name }
                .map { (name, instances) ->
                    val locations = instances.map { it.locations }.flatten().distinct()
                    val frequency = instances.size
                    val confidence = calculatePatternConfidence(frequency, locations.size)
                    
                    UsagePattern(
                        name = name,
                        description = instances.first().description,
                        frequency = frequency,
                        locations = locations,
                        template = instances.first().template,
                        confidence = confidence
                    )
                }
                .sortedByDescending { it.frequency }
        }

        private fun buildDependencyGraph(structure: ProjectStructure): DependencyGraph {
            val nodes = mutableMapOf<String, DependencyNode>()
            val edges = mutableListOf<DependencyEdge>()

            // Create nodes for all files
            structure.files.values.forEach { file ->
                nodes[file.path] = DependencyNode(
                    id = file.path,
                    type = "file",
                    path = file.path,
                    inDegree = 0,
                    outDegree = file.dependencies.size
                )
            }

            // Create edges based on dependencies
            structure.files.values.forEach { file ->
                file.dependencies.forEach { dep ->
                    val edge = DependencyEdge(
                        from = file.path,
                        to = dep,
                        type = "import",
                        strength = 1.0
                    )
                    edges.add(edge)
                    
                    // Update in-degree
                    nodes[dep]?.let { node ->
                        nodes[dep] = node.copy(inDegree = node.inDegree + 1)
                    }
                }
            }

            // Detect cycles
            val cycles = detectDependencyCycles(nodes, edges)

            // Calculate layers
            val layers = calculateDependencyLayers(nodes, edges)

            return DependencyGraph(
                nodes = nodes,
                edges = edges,
                cycles = cycles,
                layers = layers
            )
        }

        private fun calculateQualityMetrics(
            structure: ProjectStructure,
            conventions: DetectedConventions,
            dependencies: DependencyGraph
        ): CodeQualityMetrics {
            val sourceFiles = structure.files.values.filter { it.type == "source" }
            val testFiles = structure.files.values.filter { it.type == "test" }

            val complexity = sourceFiles.map { it.complexity }.average()
            val maintainability = calculateMaintainabilityIndex(sourceFiles, dependencies)
            val testCoverage = calculateTestCoverage(sourceFiles, testFiles)
            val duplication = calculateCodeDuplication(sourceFiles)
            val conventionCompliance = calculateConventionCompliance(sourceFiles, conventions)
            val documentationCoverage = calculateDocumentationCoverage(sourceFiles)
            val issues = identifyCodeIssues(sourceFiles, dependencies)

            return CodeQualityMetrics(
                complexity = complexity,
                maintainability = maintainability,
                testCoverage = testCoverage,
                duplication = duplication,
                conventionCompliance = conventionCompliance,
                documentationCoverage = documentationCoverage,
                issuesByType = issues
            )
        }

        private fun generateRecommendations(
            structure: ProjectStructure,
            conventions: DetectedConventions,
            patterns: List<UsagePattern>,
            dependencies: DependencyGraph,
            qualityMetrics: CodeQualityMetrics,
            framework: FrameworkTemplateManager.FrameworkTemplate?
        ): List<Recommendation> {
            val recommendations = mutableListOf<Recommendation>()

            // Structure recommendations
            if (framework != null) {
                recommendations.addAll(generateStructureRecommendations(structure, framework))
            }

            // Convention recommendations
            recommendations.addAll(generateConventionRecommendations(conventions, framework))

            // Pattern recommendations
            recommendations.addAll(generatePatternRecommendations(patterns))

            // Dependency recommendations
            recommendations.addAll(generateDependencyRecommendations(dependencies))

            // Quality recommendations
            recommendations.addAll(generateQualityRecommendations(qualityMetrics))

            return recommendations.sortedBy { 
                when (it.priority) {
                    "high" -> 1
                    "medium" -> 2
                    "low" -> 3
                    else -> 4
                }
            }
        }

        // Helper methods for file analysis
        private fun determineDirectoryType(name: String, files: Array<File>): String {
            return when {
                name.contains("test") -> "test"
                name.contains("src") -> "source"
                name.contains("res") -> "resource"
                name.contains("build") || name.contains("dist") || name.contains("target") -> "build"
                name.contains("doc") -> "docs"
                name.contains("config") || name.contains("conf") -> "config"
                files.any { it.name.endsWith(".java") || it.name.endsWith(".kt") || it.name.endsWith(".ts") } -> "source"
                else -> "other"
            }
        }

        private fun determineDirectoryPurpose(name: String, files: Array<File>): String {
            return when (name.lowercase()) {
                "controller", "controllers" -> "HTTP request handlers"
                "service", "services" -> "Business logic services"
                "repository", "repositories" -> "Data access layer"
                "model", "models" -> "Data models and entities"
                "view", "views" -> "View layer components"
                "component", "components" -> "Reusable UI components"
                "util", "utils", "utilities" -> "Utility functions"
                "config", "configuration" -> "Configuration files"
                "test", "tests" -> "Test files"
                "migration", "migrations" -> "Database migrations"
                else -> "Application code"
            }
        }

        private fun detectDirectoryConventions(name: String, files: Array<File>): List<String> {
            val conventions = mutableListOf<String>()
            
            if (name.matches(Regex("[a-z][a-z0-9]*"))) conventions.add("lowercase")
            if (name.matches(Regex("[a-z][a-zA-Z0-9]*"))) conventions.add("camelCase")
            if (name.matches(Regex("[a-z][a-z0-9_]*"))) conventions.add("snake_case")
            if (name.matches(Regex("[a-z][a-z0-9-]*"))) conventions.add("kebab-case")
            
            return conventions
        }

        private fun determineLanguage(extension: String): String {
            return when (extension) {
                "kt" -> "kotlin"
                "java" -> "java"
                "js" -> "javascript"
                "ts" -> "typescript"
                "py" -> "python"
                "html" -> "html"
                "css" -> "css"
                "scss" -> "scss"
                "json" -> "json"
                "xml" -> "xml"
                "md" -> "markdown"
                "yml", "yaml" -> "yaml"
                "gradle" -> "gradle"
                "properties" -> "properties"
                else -> "unknown"
            }
        }

        private fun determineFileType(name: String, content: String): String {
            return when {
                name.contains("test", ignoreCase = true) || name.contains("spec", ignoreCase = true) -> "test"
                name.startsWith(".") -> "config"
                name.endsWith(".md") -> "documentation"
                name.contains("config", ignoreCase = true) -> "config"
                name.contains("readme", ignoreCase = true) -> "documentation"
                content.contains("@Test") || content.contains("describe(") || content.contains("it(") -> "test"
                else -> "source"
            }
        }

        private fun calculateFileComplexity(content: String, language: String): Int {
            var complexity = 1 // Base complexity
            
            when (language) {
                "kotlin", "java" -> {
                    complexity += content.split(Regex("\\bif\\b|\\bwhile\\b|\\bfor\\b|\\bswitch\\b|\\bcatch\\b|\\bwhen\\b")).size - 1
                    complexity += content.split("&&").size - 1
                    complexity += content.split("\\|\\|").size - 1
                }
                "javascript", "typescript" -> {
                    complexity += content.split(Regex("\\bif\\b|\\bwhile\\b|\\bfor\\b|\\bswitch\\b|\\bcatch\\b")).size - 1
                    complexity += content.split("&&").size - 1
                    complexity += content.split("\\|\\|").size - 1
                }
                "python" -> {
                    complexity += content.split(Regex("\\bif\\b|\\bwhile\\b|\\bfor\\b|\\btry\\b|\\bexcept\\b")).size - 1
                    complexity += content.split(" and ").size - 1
                    complexity += content.split(" or ").size - 1
                }
            }
            
            return complexity
        }

        private fun extractDependencies(content: String, language: String): List<String> {
            val dependencies = mutableListOf<String>()
            
            when (language) {
                "kotlin", "java" -> {
                    val importPattern = Pattern.compile("import\\s+([a-zA-Z0-9._]+)")
                    val matcher = importPattern.matcher(content)
                    while (matcher.find()) {
                        dependencies.add(matcher.group(1))
                    }
                }
                "javascript", "typescript" -> {
                    val importPattern = Pattern.compile("import.*from\\s+['\"]([^'\"]+)['\"]")
                    val requirePattern = Pattern.compile("require\\(['\"]([^'\"]+)['\"]\\)")
                    
                    var matcher = importPattern.matcher(content)
                    while (matcher.find()) {
                        dependencies.add(matcher.group(1))
                    }
                    
                    matcher = requirePattern.matcher(content)
                    while (matcher.find()) {
                        dependencies.add(matcher.group(1))
                    }
                }
                "python" -> {
                    val importPattern = Pattern.compile("(?:from\\s+([a-zA-Z0-9._]+)\\s+)?import\\s+([a-zA-Z0-9._,\\s]+)")
                    val matcher = importPattern.matcher(content)
                    while (matcher.find()) {
                        val module = matcher.group(1) ?: matcher.group(2).split(",")[0].trim()
                        dependencies.add(module)
                    }
                }
            }
            
            return dependencies.distinct()
        }

        private fun extractExports(content: String, language: String): List<String> {
            val exports = mutableListOf<String>()
            
            when (language) {
                "kotlin" -> {
                    val patterns = listOf(
                        "class\\s+([A-Za-z0-9_]+)",
                        "object\\s+([A-Za-z0-9_]+)",
                        "interface\\s+([A-Za-z0-9_]+)",
                        "fun\\s+([A-Za-z0-9_]+)"
                    )
                    patterns.forEach { pattern ->
                        val matcher = Pattern.compile(pattern).matcher(content)
                        while (matcher.find()) {
                            exports.add(matcher.group(1))
                        }
                    }
                }
                "java" -> {
                    val patterns = listOf(
                        "public\\s+class\\s+([A-Za-z0-9_]+)",
                        "public\\s+interface\\s+([A-Za-z0-9_]+)",
                        "public\\s+enum\\s+([A-Za-z0-9_]+)"
                    )
                    patterns.forEach { pattern ->
                        val matcher = Pattern.compile(pattern).matcher(content)
                        while (matcher.find()) {
                            exports.add(matcher.group(1))
                        }
                    }
                }
                "javascript", "typescript" -> {
                    val patterns = listOf(
                        "export\\s+(?:default\\s+)?(?:class|function|const|let|var)\\s+([A-Za-z0-9_]+)",
                        "export\\s+\\{([^}]+)\\}"
                    )
                    patterns.forEach { pattern ->
                        val matcher = Pattern.compile(pattern).matcher(content)
                        while (matcher.find()) {
                            val match = matcher.group(1)
                            if (match.contains(",")) {
                                exports.addAll(match.split(",").map { it.trim() })
                            } else {
                                exports.add(match)
                            }
                        }
                    }
                }
            }
            
            return exports.distinct()
        }

        private fun extractImports(content: String, language: String): List<String> {
            return extractDependencies(content, language) // Same logic for now
        }

        private fun identifyFilePatterns(content: String, language: String): List<String> {
            val patterns = mutableListOf<String>()
            
            when (language) {
                "kotlin" -> {
                    if (content.contains("@Composable")) patterns.add("jetpack_compose")
                    if (content.contains("@HiltViewModel")) patterns.add("hilt_viewmodel")
                    if (content.contains("@Entity")) patterns.add("room_entity")
                    if (content.contains("@Repository")) patterns.add("repository_pattern")
                    if (content.contains("sealed class")) patterns.add("sealed_class")
                    if (content.contains("data class")) patterns.add("data_class")
                }
                "java" -> {
                    if (content.contains("@RestController")) patterns.add("spring_controller")
                    if (content.contains("@Service")) patterns.add("spring_service")
                    if (content.contains("@Repository")) patterns.add("spring_repository")
                    if (content.contains("@Entity")) patterns.add("jpa_entity")
                    if (content.contains("@Component")) patterns.add("spring_component")
                }
                "javascript", "typescript" -> {
                    if (content.contains("React.FC")) patterns.add("react_functional_component")
                    if (content.contains("useState")) patterns.add("react_hooks")
                    if (content.contains("useEffect")) patterns.add("react_effect")
                    if (content.contains("export default function")) patterns.add("nextjs_page")
                    if (content.contains("express.Router")) patterns.add("express_router")
                }
                "python" -> {
                    if (content.contains("@app.route")) patterns.add("flask_route")
                    if (content.contains("@blueprint.route")) patterns.add("flask_blueprint")
                    if (content.contains("class.*Model")) patterns.add("sqlalchemy_model")
                    if (content.contains("def test_")) patterns.add("pytest_test")
                }
            }
            
            return patterns
        }

        private fun isConfigFile(file: File): Boolean {
            val configNames = setOf(
                "package.json", "tsconfig.json", "next.config.js", "tailwind.config.js",
                "pom.xml", "build.gradle", "build.gradle.kts", "settings.gradle.kts",
                "requirements.txt", "setup.py", "pyproject.toml",
                "Dockerfile", "docker-compose.yml", "docker-compose.yaml",
                ".env", ".env.local", ".env.production",
                "config.py", "application.properties", "application.yml"
            )
            return configNames.contains(file.name) || file.name.startsWith(".")
        }

        private fun determineConfigType(fileName: String): String {
            return when {
                fileName.contains("package.json") || fileName.contains("pom.xml") || fileName.contains("build.gradle") -> "dependency"
                fileName.contains("tsconfig") || fileName.contains("next.config") || fileName.contains("tailwind.config") -> "build"
                fileName.contains(".env") || fileName.contains("application.properties") -> "environment"
                fileName.contains("eslint") || fileName.contains("prettier") || fileName.contains("ktlint") -> "linting"
                fileName.contains("jest") || fileName.contains("test") -> "testing"
                else -> "general"
            }
        }

        private fun determineConfigFramework(fileName: String, content: String): String? {
            return when {
                fileName == "package.json" && content.contains("\"next\"") -> "nextjs"
                fileName == "package.json" && content.contains("\"express\"") -> "express"
                fileName == "package.json" && content.contains("\"react\"") -> "react"
                fileName == "pom.xml" && content.contains("spring-boot") -> "spring_boot"
                fileName.contains("build.gradle") && content.contains("android") -> "android"
                fileName == "requirements.txt" || (fileName == "app.py" && content.contains("Flask")) -> "flask"
                else -> null
            }
        }

        private fun parseConfigContent(content: String, extension: String): Map<String, Any> {
            // Simplified parsing - in production, use proper JSON/XML/YAML parsers
            return try {
                when (extension.lowercase()) {
                    "json" -> {
                        val json = JSONObject(content)
                        json.keys().asSequence().associateWith { json.get(it) }
                    }
                    else -> emptyMap()
                }
            } catch (e: Exception) {
                emptyMap()
            }
        }

        private fun isModuleRoot(dir: File): Boolean {
            val files = dir.listFiles() ?: return false
            val fileNames = files.map { it.name }.toSet()
            
            return fileNames.any { 
                it in setOf("build.gradle", "build.gradle.kts", "pom.xml", "package.json", "setup.py")
            }
        }

        private fun determineModuleType(name: String, files: List<File>): String {
            return when {
                name == "app" -> "main"
                name.contains("test") -> "test"
                name.contains("lib") -> "library"
                name.contains("plugin") -> "plugin"
                files.any { it.name == "AndroidManifest.xml" } -> "android"
                else -> "feature"
            }
        }

        private fun extractModuleDependencies(files: List<File>): List<String> {
            val dependencies = mutableListOf<String>()
            
            files.forEach { file ->
                if (file.name == "build.gradle.kts" || file.name == "build.gradle") {
                    val content = try { file.readText() } catch (e: Exception) { "" }
                    val depPattern = Pattern.compile("implementation\\s*\\(?['\"]([^'\"]+)['\"]")
                    val matcher = depPattern.matcher(content)
                    while (matcher.find()) {
                        dependencies.add(matcher.group(1))
                    }
                }
            }
            
            return dependencies.distinct()
        }

        private fun extractModuleExports(files: List<File>): List<String> {
            val exports = mutableListOf<String>()
            
            files.filter { it.extension in setOf("kt", "java", "js", "ts", "py") }.forEach { file ->
                val content = try { file.readText() } catch (e: Exception) { "" }
                exports.addAll(extractExports(content, determineLanguage(file.extension)))
            }
            
            return exports.distinct()
        }

        // Convention detection methods
        private fun detectFileNamingConvention(fileNames: Set<String>): String {
            val camelCaseCount = fileNames.count { it.matches(Regex("[a-z][a-zA-Z0-9]*\\.[a-z]+")) }
            val pascalCaseCount = fileNames.count { it.matches(Regex("[A-Z][a-zA-Z0-9]*\\.[a-z]+")) }
            val snakeCaseCount = fileNames.count { it.matches(Regex("[a-z][a-z0-9_]*\\.[a-z]+")) }
            val kebabCaseCount = fileNames.count { it.matches(Regex("[a-z][a-z0-9-]*\\.[a-z]+")) }
            
            val max = maxOf(camelCaseCount, pascalCaseCount, snakeCaseCount, kebabCaseCount)
            
            return when (max) {
                camelCaseCount -> "camelCase"
                pascalCaseCount -> "PascalCase"
                snakeCaseCount -> "snake_case"
                kebabCaseCount -> "kebab-case"
                else -> "mixed"
            }
        }

        private fun detectDirectoryStructureConvention(directories: Set<String>): String {
            val hasLayeredStructure = directories.any { it.contains("controller") || it.contains("service") || it.contains("repository") }
            val hasFeatureStructure = directories.any { it.contains("feature") || it.contains("module") }
            val hasMvcStructure = directories.any { it.contains("model") && it.contains("view") && it.contains("controller") }
            
            return when {
                hasLayeredStructure -> "layered"
                hasFeatureStructure -> "feature-based"
                hasMvcStructure -> "mvc"
                else -> "custom"
            }
        }

        private fun detectImportStyleConvention(sourceFiles: List<FileInfo>): String {
            // Analyze import patterns in source files
            var absoluteImports = 0
            var relativeImports = 0
            
            sourceFiles.forEach { file ->
                file.imports.forEach { import ->
                    if (import.startsWith("./") || import.startsWith("../")) {
                        relativeImports++
                    } else {
                        absoluteImports++
                    }
                }
            }
            
            return if (absoluteImports > relativeImports) "absolute" else "relative"
        }

        private fun detectFunctionNamingConvention(sourceFiles: List<FileInfo>): String {
            // This would require more sophisticated AST parsing
            // For now, return a reasonable default based on language
            val languages = sourceFiles.map { it.language }.distinct()
            
            return when {
                "kotlin" in languages || "java" in languages -> "camelCase"
                "python" in languages -> "snake_case"
                "javascript" in languages || "typescript" in languages -> "camelCase"
                else -> "camelCase"
            }
        }

        private fun detectClassNamingConvention(sourceFiles: List<FileInfo>): String {
            return "PascalCase" // Standard across most languages
        }

        private fun detectVariableNamingConvention(sourceFiles: List<FileInfo>): String {
            val languages = sourceFiles.map { it.language }.distinct()
            
            return when {
                "python" in languages -> "snake_case"
                else -> "camelCase"
            }
        }

        private fun detectConstantNamingConvention(sourceFiles: List<FileInfo>): String {
            return "SCREAMING_SNAKE_CASE" // Standard across most languages
        }

        private fun detectIndentationConvention(sourceFiles: List<FileInfo>): String {
            // This would require analyzing actual file content
            return "4 spaces" // Common default
        }

        private fun detectLineEndingConvention(sourceFiles: List<FileInfo>): String {
            return "LF" // Unix-style line endings are most common
        }

        private fun detectCommentStyleConvention(sourceFiles: List<FileInfo>): String {
            val languages = sourceFiles.map { it.language }.distinct()
            
            return when {
                "kotlin" in languages || "java" in languages -> "/** JavaDoc */"
                "javascript" in languages || "typescript" in languages -> "/** JSDoc */"
                "python" in languages -> "\"\"\" Docstring \"\"\""
                else -> "/** Block comments */"
            }
        }

        // Pattern analysis methods
        private fun extractCodePatterns(content: String, language: String, path: String): List<UsagePattern> {
            val patterns = mutableListOf<UsagePattern>()
            
            when (language) {
                "kotlin" -> {
                    if (content.contains("@Composable")) {
                        patterns.add(UsagePattern(
                            name = "Jetpack Compose Component",
                            description = "Composable UI component",
                            frequency = 1,
                            locations = listOf(path),
                            template = "@Composable\nfun ComponentName() { }",
                            confidence = 0.9
                        ))
                    }
                }
                "javascript", "typescript" -> {
                    if (content.contains("React.FC")) {
                        patterns.add(UsagePattern(
                            name = "React Functional Component",
                            description = "React functional component with TypeScript",
                            frequency = 1,
                            locations = listOf(path),
                            template = "const Component: React.FC = () => { }",
                            confidence = 0.9
                        ))
                    }
                }
                "python" -> {
                    if (content.contains("@app.route")) {
                        patterns.add(UsagePattern(
                            name = "Flask Route",
                            description = "Flask route handler",
                            frequency = 1,
                            locations = listOf(path),
                            template = "@app.route('/path')\ndef handler(): pass",
                            confidence = 0.9
                        ))
                    }
                }
            }
            
            return patterns
        }

        private fun calculatePatternConfidence(frequency: Int, locationCount: Int): Double {
            val frequencyScore = minOf(frequency / 10.0, 1.0)
            val distributionScore = minOf(locationCount / 5.0, 1.0)
            return (frequencyScore + distributionScore) / 2.0
        }

        // Dependency analysis methods
        private fun detectDependencyCycles(nodes: Map<String, DependencyNode>, edges: List<DependencyEdge>): List<List<String>> {
            // Simplified cycle detection - in production, use proper graph algorithms
            return emptyList()
        }

        private fun calculateDependencyLayers(nodes: Map<String, DependencyNode>, edges: List<DependencyEdge>): List<List<String>> {
            // Simplified layering - in production, use topological sorting
            val layers = mutableListOf<List<String>>()
            
            // Layer 0: nodes with no dependencies
            val layer0 = nodes.values.filter { it.inDegree == 0 }.map { it.id }
            if (layer0.isNotEmpty()) layers.add(layer0)
            
            return layers
        }

        // Quality metrics calculation methods
        private fun calculateMaintainabilityIndex(sourceFiles: List<FileInfo>, dependencies: DependencyGraph): Double {
            val avgComplexity = sourceFiles.map { it.complexity }.average()
            val couplingFactor = dependencies.edges.size.toDouble() / maxOf(sourceFiles.size, 1)
            return maxOf(0.0, 100.0 - avgComplexity - couplingFactor * 10)
        }

        private fun calculateTestCoverage(sourceFiles: List<FileInfo>, testFiles: List<FileInfo>): Double {
            if (sourceFiles.isEmpty()) return 0.0
            return minOf(testFiles.size.toDouble() / sourceFiles.size * 100, 100.0)
        }

        private fun calculateCodeDuplication(sourceFiles: List<FileInfo>): Double {
            // Simplified duplication detection
            return 5.0 // Placeholder
        }

        private fun calculateConventionCompliance(sourceFiles: List<FileInfo>, conventions: DetectedConventions): Double {
            // Simplified compliance calculation
            return 85.0 // Placeholder
        }

        private fun calculateDocumentationCoverage(sourceFiles: List<FileInfo>): Double {
            // Simplified documentation coverage
            return 60.0 // Placeholder
        }

        private fun identifyCodeIssues(sourceFiles: List<FileInfo>, dependencies: DependencyGraph): Map<String, Int> {
            return mapOf(
                "high_complexity" to sourceFiles.count { it.complexity > 10 },
                "long_files" to sourceFiles.count { it.size > 1000 },
                "circular_dependencies" to dependencies.cycles.size,
                "unused_exports" to 0 // Placeholder
            )
        }

        // Recommendation generation methods
        private fun generateStructureRecommendations(
            structure: ProjectStructure,
            framework: FrameworkTemplateManager.FrameworkTemplate
        ): List<Recommendation> {
            val recommendations = mutableListOf<Recommendation>()
            
            // Check if structure matches framework conventions
            val expectedDirs = framework.projectStructure.keys
            val actualDirs = structure.directories.keys
            
            expectedDirs.forEach { expectedDir ->
                if (!actualDirs.any { it.endsWith(expectedDir) }) {
                    recommendations.add(Recommendation(
                        type = "structure",
                        priority = "medium",
                        title = "Missing recommended directory: $expectedDir",
                        description = "The ${framework.name} framework recommends having a '$expectedDir' directory",
                        action = "Create directory structure following ${framework.name} conventions",
                        impact = "Improved project organization and maintainability",
                        effort = "Low"
                    ))
                }
            }
            
            return recommendations
        }

        private fun generateConventionRecommendations(
            conventions: DetectedConventions,
            framework: FrameworkTemplateManager.FrameworkTemplate?
        ): List<Recommendation> {
            val recommendations = mutableListOf<Recommendation>()
            
            if (framework != null && conventions.fileNaming != framework.conventions.fileNaming) {
                recommendations.add(Recommendation(
                    type = "naming",
                    priority = "low",
                    title = "File naming convention mismatch",
                    description = "Current naming: ${conventions.fileNaming}, ${framework.name} recommends: ${framework.conventions.fileNaming}",
                    action = "Standardize file naming to match framework conventions",
                    impact = "Improved consistency and team collaboration",
                    effort = "Medium"
                ))
            }
            
            return recommendations
        }

        private fun generatePatternRecommendations(patterns: List<UsagePattern>): List<Recommendation> {
            val recommendations = mutableListOf<Recommendation>()
            
            val lowConfidencePatterns = patterns.filter { it.confidence < 0.5 }
            if (lowConfidencePatterns.isNotEmpty()) {
                recommendations.add(Recommendation(
                    type = "pattern",
                    priority = "medium",
                    title = "Inconsistent code patterns detected",
                    description = "Found ${lowConfidencePatterns.size} patterns with low confidence scores",
                    action = "Review and standardize code patterns across the project",
                    impact = "Improved code consistency and maintainability",
                    effort = "High"
                ))
            }
            
            return recommendations
        }

        private fun generateDependencyRecommendations(dependencies: DependencyGraph): List<Recommendation> {
            val recommendations = mutableListOf<Recommendation>()
            
            if (dependencies.cycles.isNotEmpty()) {
                recommendations.add(Recommendation(
                    type = "dependency",
                    priority = "high",
                    title = "Circular dependencies detected",
                    description = "Found ${dependencies.cycles.size} circular dependency cycles",
                    action = "Refactor code to eliminate circular dependencies",
                    impact = "Improved modularity and testability",
                    effort = "High"
                ))
            }
            
            return recommendations
        }

        private fun generateQualityRecommendations(qualityMetrics: CodeQualityMetrics): List<Recommendation> {
            val recommendations = mutableListOf<Recommendation>()
            
            if (qualityMetrics.complexity > 20) {
                recommendations.add(Recommendation(
                    type = "complexity",
                    priority = "high",
                    title = "High code complexity detected",
                    description = "Average complexity: ${qualityMetrics.complexity}",
                    action = "Refactor complex methods and classes",
                    impact = "Improved maintainability and reduced bugs",
                    effort = "High"
                ))
            }
            
            if (qualityMetrics.testCoverage < 50) {
                recommendations.add(Recommendation(
                    type = "testing",
                    priority = "high",
                    title = "Low test coverage",
                    description = "Current coverage: ${qualityMetrics.testCoverage}%",
                    action = "Add unit tests for critical components",
                    impact = "Improved code quality and bug detection",
                    effort = "Medium"
                ))
            }
            
            return recommendations
        }
    }
}