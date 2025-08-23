package com.rk.terminal.ui.screens.terminal

import org.json.JSONArray
import org.json.JSONObject
import java.io.File
import java.util.regex.Pattern
import kotlinx.coroutines.*

/**
 * Intelligent Reference Resolver for AI Android Agent
 * 
 * This resolver maintains cross-file functionality by intelligently resolving
 * imports, exports, dependencies, and ensuring all references are properly
 * connected across the entire project.
 */
class ReferenceResolver {

    data class ResolutionResult(
        val success: Boolean,
        val resolvedReferences: Map<String, ResolvedReference>,
        val unresolvedReferences: List<UnresolvedReference>,
        val suggestions: List<ResolutionSuggestion>,
        val autoFixedReferences: List<String>
    )

    data class ResolvedReference(
        val sourceFile: String,
        val targetFile: String,
        val referenceType: ReferenceType,
        val symbol: String,
        val confidence: Double,
        val isAutoResolved: Boolean = false
    )

    data class UnresolvedReference(
        val sourceFile: String,
        val symbol: String,
        val referenceType: ReferenceType,
        val context: String,
        val possibleTargets: List<String>,
        val reason: String
    )

    data class ResolutionSuggestion(
        val type: SuggestionType,
        val description: String,
        val action: String,
        val confidence: Double,
        val autoApplicable: Boolean,
        val affectedFiles: List<String>
    )

    enum class ReferenceType {
        IMPORT,
        EXPORT,
        FUNCTION_CALL,
        CLASS_REFERENCE,
        VARIABLE_REFERENCE,
        TYPE_REFERENCE,
        MODULE_REFERENCE,
        DEPENDENCY
    }

    enum class SuggestionType {
        ADD_IMPORT,
        REMOVE_IMPORT,
        UPDATE_IMPORT_PATH,
        ADD_EXPORT,
        CREATE_MISSING_FILE,
        CREATE_MISSING_FUNCTION,
        CREATE_MISSING_CLASS,
        RESOLVE_NAMING_CONFLICT,
        UPDATE_DEPENDENCY
    }

    data class ReferenceIndex(
        val exports: Map<String, List<ExportInfo>>,
        val imports: Map<String, List<ImportInfo>>,
        val functionCalls: Map<String, List<FunctionCallInfo>>,
        val typeReferences: Map<String, List<TypeReferenceInfo>>,
        val dependencies: Map<String, DependencyInfo>
    )

    data class ExportInfo(
        val symbol: String,
        val filePath: String,
        val symbolType: String, // "function", "class", "variable", "type"
        val isDefault: Boolean,
        val signature: String = "",
        val lineNumber: Int = 0
    )

    data class ImportInfo(
        val symbol: String,
        val filePath: String,
        val sourceModule: String,
        val isDefault: Boolean,
        val alias: String? = null,
        val lineNumber: Int = 0
    )

    data class FunctionCallInfo(
        val functionName: String,
        val filePath: String,
        val lineNumber: Int,
        val arguments: List<String> = emptyList(),
        val context: String = ""
    )

    data class TypeReferenceInfo(
        val typeName: String,
        val filePath: String,
        val lineNumber: Int,
        val context: String = ""
    )

    data class DependencyInfo(
        val name: String,
        val version: String,
        val type: String, // "npm", "maven", "gradle", "pip"
        val isDevDependency: Boolean = false
    )

    companion object {
        fun resolveProjectReferences(projectPath: String): ResolutionResult {
            val resolver = ReferenceResolver()
            return resolver.performProjectResolution(projectPath)
        }

        fun resolveFileReferences(
            filePath: String,
            content: String,
            projectAnalysis: CodeStructureAnalyzer.ProjectAnalysis
        ): ResolutionResult {
            val resolver = ReferenceResolver()
            return resolver.performFileResolution(filePath, content, projectAnalysis)
        }

        fun autoFixReferences(
            projectPath: String,
            suggestions: List<ResolutionSuggestion>
        ): List<String> {
            val resolver = ReferenceResolver()
            return resolver.applyAutoFixes(projectPath, suggestions)
        }
    }

    private fun performProjectResolution(projectPath: String): ResolutionResult {
        val analysis = CodeStructureAnalyzer.analyzeProject(projectPath)
        val index = buildReferenceIndex(analysis)
        
        val resolvedReferences = mutableMapOf<String, ResolvedReference>()
        val unresolvedReferences = mutableListOf<UnresolvedReference>()
        val suggestions = mutableListOf<ResolutionSuggestion>()
        val autoFixedReferences = mutableListOf<String>()

        // Resolve imports for each file
        analysis.structure.files.values
            .filter { it.type == "source" }
            .forEach { fileInfo ->
                val fileResult = resolveFileImports(fileInfo, analysis, index)
                resolvedReferences.putAll(fileResult.resolvedReferences)
                unresolvedReferences.addAll(fileResult.unresolvedReferences)
                suggestions.addAll(fileResult.suggestions)
                autoFixedReferences.addAll(fileResult.autoFixedReferences)
            }

        // Resolve function calls
        val functionCallResults = resolveFunctionCalls(analysis, index)
        resolvedReferences.putAll(functionCallResults.resolvedReferences)
        unresolvedReferences.addAll(functionCallResults.unresolvedReferences)
        suggestions.addAll(functionCallResults.suggestions)

        // Resolve type references
        val typeResults = resolveTypeReferences(analysis, index)
        resolvedReferences.putAll(typeResults.resolvedReferences)
        unresolvedReferences.addAll(typeResults.unresolvedReferences)
        suggestions.addAll(typeResults.suggestions)

        return ResolutionResult(
            success = unresolvedReferences.isEmpty(),
            resolvedReferences = resolvedReferences,
            unresolvedReferences = unresolvedReferences,
            suggestions = suggestions.distinctBy { it.description },
            autoFixedReferences = autoFixedReferences
        )
    }

    private fun performFileResolution(
        filePath: String,
        content: String,
        projectAnalysis: CodeStructureAnalyzer.ProjectAnalysis
    ): ResolutionResult {
        val index = buildReferenceIndex(projectAnalysis)
        val language = determineLanguage(File(filePath).extension)

        val resolvedReferences = mutableMapOf<String, ResolvedReference>()
        val unresolvedReferences = mutableListOf<UnresolvedReference>()
        val suggestions = mutableListOf<ResolutionSuggestion>()
        val autoFixedReferences = mutableListOf<String>()

        // Extract and resolve imports
        val imports = extractImports(content, language)
        imports.forEach { import ->
            val resolution = resolveImport(import, filePath, projectAnalysis, index)
            if (resolution.success) {
                resolvedReferences[import] = resolution.resolvedReference!!
            } else {
                unresolvedReferences.add(resolution.unresolvedReference!!)
                suggestions.addAll(resolution.suggestions)
            }
        }

        // Extract and resolve function calls
        val functionCalls = extractFunctionCalls(content, language)
        functionCalls.forEach { call ->
            val resolution = resolveFunctionCall(call, filePath, projectAnalysis, index)
            if (resolution.success) {
                resolvedReferences[call.functionName] = resolution.resolvedReference!!
            } else {
                unresolvedReferences.add(resolution.unresolvedReference!!)
                suggestions.addAll(resolution.suggestions)
            }
        }

        // Generate suggestions for missing exports
        val exports = extractExports(content, language)
        suggestions.addAll(generateExportSuggestions(exports, filePath, projectAnalysis))

        return ResolutionResult(
            success = unresolvedReferences.isEmpty(),
            resolvedReferences = resolvedReferences,
            unresolvedReferences = unresolvedReferences,
            suggestions = suggestions,
            autoFixedReferences = autoFixedReferences
        )
    }

    private fun buildReferenceIndex(analysis: CodeStructureAnalyzer.ProjectAnalysis): ReferenceIndex {
        val exports = mutableMapOf<String, MutableList<ExportInfo>>()
        val imports = mutableMapOf<String, MutableList<ImportInfo>>()
        val functionCalls = mutableMapOf<String, MutableList<FunctionCallInfo>>()
        val typeReferences = mutableMapOf<String, MutableList<TypeReferenceInfo>>()
        val dependencies = mutableMapOf<String, DependencyInfo>()

        // Index exports and imports from each file
        analysis.structure.files.values.forEach { fileInfo ->
            try {
                val content = File(fileInfo.path).readText()
                val language = fileInfo.language

                // Index exports
                fileInfo.exports.forEach { export ->
                    exports.computeIfAbsent(export) { mutableListOf() }.add(
                        ExportInfo(
                            symbol = export,
                            filePath = fileInfo.path,
                            symbolType = detectSymbolType(export, content, language),
                            isDefault = isDefaultExport(export, content, language),
                            signature = extractSignature(export, content, language),
                            lineNumber = findSymbolLineNumber(export, content)
                        )
                    )
                }

                // Index imports
                fileInfo.imports.forEach { import ->
                    imports.computeIfAbsent(import) { mutableListOf() }.add(
                        ImportInfo(
                            symbol = import,
                            filePath = fileInfo.path,
                            sourceModule = import,
                            isDefault = false, // Would need more sophisticated parsing
                            lineNumber = findImportLineNumber(import, content)
                        )
                    )
                }

                // Index function calls
                val calls = extractFunctionCalls(content, language)
                calls.forEach { call ->
                    functionCalls.computeIfAbsent(call.functionName) { mutableListOf() }.add(call)
                }

                // Index type references
                val types = extractTypeReferences(content, language)
                types.forEach { type ->
                    typeReferences.computeIfAbsent(type.typeName) { mutableListOf() }.add(type)
                }

            } catch (e: Exception) {
                // Skip files that can't be read
            }
        }

        // Index dependencies from config files
        analysis.structure.configFiles.forEach { configFile ->
            val deps = extractDependencies(configFile)
            dependencies.putAll(deps)
        }

        return ReferenceIndex(
            exports = exports,
            imports = imports,
            functionCalls = functionCalls,
            typeReferences = typeReferences,
            dependencies = dependencies
        )
    }

    private fun resolveFileImports(
        fileInfo: CodeStructureAnalyzer.FileInfo,
        analysis: CodeStructureAnalyzer.ProjectAnalysis,
        index: ReferenceIndex
    ): ResolutionResult {
        val resolvedReferences = mutableMapOf<String, ResolvedReference>()
        val unresolvedReferences = mutableListOf<UnresolvedReference>()
        val suggestions = mutableListOf<ResolutionSuggestion>()
        val autoFixedReferences = mutableListOf<String>()

        fileInfo.imports.forEach { import ->
            val resolution = resolveImport(import, fileInfo.path, analysis, index)
            if (resolution.success) {
                resolvedReferences[import] = resolution.resolvedReference!!
            } else {
                unresolvedReferences.add(resolution.unresolvedReference!!)
                suggestions.addAll(resolution.suggestions)
            }
        }

        return ResolutionResult(
            success = unresolvedReferences.isEmpty(),
            resolvedReferences = resolvedReferences,
            unresolvedReferences = unresolvedReferences,
            suggestions = suggestions,
            autoFixedReferences = autoFixedReferences
        )
    }

    private fun resolveFunctionCalls(
        analysis: CodeStructureAnalyzer.ProjectAnalysis,
        index: ReferenceIndex
    ): ResolutionResult {
        val resolvedReferences = mutableMapOf<String, ResolvedReference>()
        val unresolvedReferences = mutableListOf<UnresolvedReference>()
        val suggestions = mutableListOf<ResolutionSuggestion>()

        index.functionCalls.forEach { (functionName, calls) ->
            calls.forEach { call ->
                val exports = index.exports[functionName]
                if (exports != null && exports.isNotEmpty()) {
                    val bestMatch = findBestFunctionMatch(call, exports)
                    if (bestMatch != null) {
                        resolvedReferences["${call.filePath}:$functionName"] = ResolvedReference(
                            sourceFile = call.filePath,
                            targetFile = bestMatch.filePath,
                            referenceType = ReferenceType.FUNCTION_CALL,
                            symbol = functionName,
                            confidence = calculateMatchConfidence(call, bestMatch)
                        )
                    } else {
                        unresolvedReferences.add(UnresolvedReference(
                            sourceFile = call.filePath,
                            symbol = functionName,
                            referenceType = ReferenceType.FUNCTION_CALL,
                            context = call.context,
                            possibleTargets = exports.map { it.filePath },
                            reason = "No suitable function signature match found"
                        ))
                    }
                } else {
                    unresolvedReferences.add(UnresolvedReference(
                        sourceFile = call.filePath,
                        symbol = functionName,
                        referenceType = ReferenceType.FUNCTION_CALL,
                        context = call.context,
                        possibleTargets = emptyList(),
                        reason = "Function not found in any exports"
                    ))

                    // Suggest creating the function
                    suggestions.add(ResolutionSuggestion(
                        type = SuggestionType.CREATE_MISSING_FUNCTION,
                        description = "Create missing function '$functionName'",
                        action = "Create function '$functionName' with appropriate signature",
                        confidence = 0.8,
                        autoApplicable = true,
                        affectedFiles = listOf(call.filePath)
                    ))
                }
            }
        }

        return ResolutionResult(
            success = unresolvedReferences.isEmpty(),
            resolvedReferences = resolvedReferences,
            unresolvedReferences = unresolvedReferences,
            suggestions = suggestions,
            autoFixedReferences = emptyList()
        )
    }

    private fun resolveTypeReferences(
        analysis: CodeStructureAnalyzer.ProjectAnalysis,
        index: ReferenceIndex
    ): ResolutionResult {
        val resolvedReferences = mutableMapOf<String, ResolvedReference>()
        val unresolvedReferences = mutableListOf<UnresolvedReference>()
        val suggestions = mutableListOf<ResolutionSuggestion>()

        index.typeReferences.forEach { (typeName, references) ->
            references.forEach { ref ->
                val exports = index.exports[typeName]?.filter { it.symbolType in setOf("class", "interface", "type") }
                
                if (exports != null && exports.isNotEmpty()) {
                    val bestMatch = exports.first() // Simple resolution for now
                    resolvedReferences["${ref.filePath}:$typeName"] = ResolvedReference(
                        sourceFile = ref.filePath,
                        targetFile = bestMatch.filePath,
                        referenceType = ReferenceType.TYPE_REFERENCE,
                        symbol = typeName,
                        confidence = 0.9
                    )
                } else {
                    unresolvedReferences.add(UnresolvedReference(
                        sourceFile = ref.filePath,
                        symbol = typeName,
                        referenceType = ReferenceType.TYPE_REFERENCE,
                        context = ref.context,
                        possibleTargets = emptyList(),
                        reason = "Type not found in any exports"
                    ))

                    suggestions.add(ResolutionSuggestion(
                        type = SuggestionType.CREATE_MISSING_CLASS,
                        description = "Create missing type '$typeName'",
                        action = "Create type or class '$typeName'",
                        confidence = 0.7,
                        autoApplicable = true,
                        affectedFiles = listOf(ref.filePath)
                    ))
                }
            }
        }

        return ResolutionResult(
            success = unresolvedReferences.isEmpty(),
            resolvedReferences = resolvedReferences,
            unresolvedReferences = unresolvedReferences,
            suggestions = suggestions,
            autoFixedReferences = emptyList()
        )
    }

    private data class ImportResolution(
        val success: Boolean,
        val resolvedReference: ResolvedReference? = null,
        val unresolvedReference: UnresolvedReference? = null,
        val suggestions: List<ResolutionSuggestion> = emptyList()
    )

    private fun resolveImport(
        import: String,
        sourceFile: String,
        analysis: CodeStructureAnalyzer.ProjectAnalysis,
        index: ReferenceIndex
    ): ImportResolution {
        // Try to find the imported symbol in exports
        val exports = index.exports[import] ?: index.exports[import.substringAfterLast(".")]
        
        if (exports != null && exports.isNotEmpty()) {
            val bestMatch = exports.first() // Simple resolution for now
            return ImportResolution(
                success = true,
                resolvedReference = ResolvedReference(
                    sourceFile = sourceFile,
                    targetFile = bestMatch.filePath,
                    referenceType = ReferenceType.IMPORT,
                    symbol = import,
                    confidence = 0.9
                )
            )
        }

        // Try to resolve as file path
        val possiblePaths = generatePossibleImportPaths(import, sourceFile, analysis)
        val existingFile = possiblePaths.find { File(it).exists() }
        
        if (existingFile != null) {
            return ImportResolution(
                success = true,
                resolvedReference = ResolvedReference(
                    sourceFile = sourceFile,
                    targetFile = existingFile,
                    referenceType = ReferenceType.IMPORT,
                    symbol = import,
                    confidence = 0.8,
                    isAutoResolved = true
                )
            )
        }

        // Generate suggestions
        val suggestions = mutableListOf<ResolutionSuggestion>()
        
        if (possiblePaths.isNotEmpty()) {
            suggestions.add(ResolutionSuggestion(
                type = SuggestionType.CREATE_MISSING_FILE,
                description = "Create missing file for import '$import'",
                action = "Create file at one of: ${possiblePaths.take(3).joinToString(", ")}",
                confidence = 0.6,
                autoApplicable = true,
                affectedFiles = possiblePaths
            ))
        }

        return ImportResolution(
            success = false,
            unresolvedReference = UnresolvedReference(
                sourceFile = sourceFile,
                symbol = import,
                referenceType = ReferenceType.IMPORT,
                context = "import statement",
                possibleTargets = possiblePaths,
                reason = "Import target not found"
            ),
            suggestions = suggestions
        )
    }

    private data class FunctionCallResolution(
        val success: Boolean,
        val resolvedReference: ResolvedReference? = null,
        val unresolvedReference: UnresolvedReference? = null,
        val suggestions: List<ResolutionSuggestion> = emptyList()
    )

    private fun resolveFunctionCall(
        call: FunctionCallInfo,
        sourceFile: String,
        analysis: CodeStructureAnalyzer.ProjectAnalysis,
        index: ReferenceIndex
    ): FunctionCallResolution {
        val exports = index.exports[call.functionName]?.filter { it.symbolType == "function" }
        
        if (exports != null && exports.isNotEmpty()) {
            val bestMatch = findBestFunctionMatch(call, exports)
            if (bestMatch != null) {
                return FunctionCallResolution(
                    success = true,
                    resolvedReference = ResolvedReference(
                        sourceFile = sourceFile,
                        targetFile = bestMatch.filePath,
                        referenceType = ReferenceType.FUNCTION_CALL,
                        symbol = call.functionName,
                        confidence = calculateMatchConfidence(call, bestMatch)
                    )
                )
            }
        }

        return FunctionCallResolution(
            success = false,
            unresolvedReference = UnresolvedReference(
                sourceFile = sourceFile,
                symbol = call.functionName,
                referenceType = ReferenceType.FUNCTION_CALL,
                context = call.context,
                possibleTargets = exports?.map { it.filePath } ?: emptyList(),
                reason = "Function not found or signature mismatch"
            ),
            suggestions = listOf(
                ResolutionSuggestion(
                    type = SuggestionType.CREATE_MISSING_FUNCTION,
                    description = "Create missing function '${call.functionName}'",
                    action = "Create function with signature matching the call",
                    confidence = 0.8,
                    autoApplicable = true,
                    affectedFiles = listOf(sourceFile)
                )
            )
        )
    }

    private fun extractImports(content: String, language: String): List<String> {
        val imports = mutableListOf<String>()

        when (language) {
            "kotlin", "java" -> {
                val pattern = Pattern.compile("import\\s+([a-zA-Z0-9._]+)")
                val matcher = pattern.matcher(content)
                while (matcher.find()) {
                    imports.add(matcher.group(1))
                }
            }
            "javascript", "typescript" -> {
                val importPattern = Pattern.compile("import.*from\\s+['\"]([^'\"]+)['\"]")
                val requirePattern = Pattern.compile("require\\(['\"]([^'\"]+)['\"]\\)")
                
                var matcher = importPattern.matcher(content)
                while (matcher.find()) {
                    imports.add(matcher.group(1))
                }
                
                matcher = requirePattern.matcher(content)
                while (matcher.find()) {
                    imports.add(matcher.group(1))
                }
            }
            "python" -> {
                val pattern = Pattern.compile("(?:from\\s+([a-zA-Z0-9._]+)\\s+)?import\\s+([a-zA-Z0-9._,\\s]+)")
                val matcher = pattern.matcher(content)
                while (matcher.find()) {
                    val module = matcher.group(1) ?: matcher.group(2).split(",")[0].trim()
                    imports.add(module)
                }
            }
        }

        return imports
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

        return exports
    }

    private fun extractFunctionCalls(content: String, language: String): List<FunctionCallInfo> {
        val calls = mutableListOf<FunctionCallInfo>()

        when (language) {
            "kotlin", "java" -> {
                val pattern = Pattern.compile("([a-zA-Z_][a-zA-Z0-9_]*)\\s*\\(([^)]*)\\)")
                val matcher = pattern.matcher(content)
                val lines = content.lines()
                
                while (matcher.find()) {
                    val functionName = matcher.group(1)
                    val args = matcher.group(2)
                    val lineNumber = findLineNumber(content, matcher.start())
                    val context = if (lineNumber <= lines.size) lines[lineNumber - 1].trim() else ""
                    
                    calls.add(FunctionCallInfo(
                        functionName = functionName,
                        filePath = "", // Will be set by caller
                        lineNumber = lineNumber,
                        arguments = args.split(",").map { it.trim() }.filter { it.isNotEmpty() },
                        context = context
                    ))
                }
            }
            "javascript", "typescript" -> {
                val pattern = Pattern.compile("([a-zA-Z_$][a-zA-Z0-9_$]*)\\s*\\(([^)]*)\\)")
                val matcher = pattern.matcher(content)
                val lines = content.lines()
                
                while (matcher.find()) {
                    val functionName = matcher.group(1)
                    val args = matcher.group(2)
                    val lineNumber = findLineNumber(content, matcher.start())
                    val context = if (lineNumber <= lines.size) lines[lineNumber - 1].trim() else ""
                    
                    calls.add(FunctionCallInfo(
                        functionName = functionName,
                        filePath = "",
                        lineNumber = lineNumber,
                        arguments = args.split(",").map { it.trim() }.filter { it.isNotEmpty() },
                        context = context
                    ))
                }
            }
            "python" -> {
                val pattern = Pattern.compile("([a-zA-Z_][a-zA-Z0-9_]*)\\s*\\(([^)]*)\\)")
                val matcher = pattern.matcher(content)
                val lines = content.lines()
                
                while (matcher.find()) {
                    val functionName = matcher.group(1)
                    val args = matcher.group(2)
                    val lineNumber = findLineNumber(content, matcher.start())
                    val context = if (lineNumber <= lines.size) lines[lineNumber - 1].trim() else ""
                    
                    calls.add(FunctionCallInfo(
                        functionName = functionName,
                        filePath = "",
                        lineNumber = lineNumber,
                        arguments = args.split(",").map { it.trim() }.filter { it.isNotEmpty() },
                        context = context
                    ))
                }
            }
        }

        return calls
    }

    private fun extractTypeReferences(content: String, language: String): List<TypeReferenceInfo> {
        val types = mutableListOf<TypeReferenceInfo>()

        when (language) {
            "kotlin" -> {
                val pattern = Pattern.compile(": ([A-Z][a-zA-Z0-9_]*)")
                val matcher = pattern.matcher(content)
                
                while (matcher.find()) {
                    val typeName = matcher.group(1)
                    val lineNumber = findLineNumber(content, matcher.start())
                    
                    types.add(TypeReferenceInfo(
                        typeName = typeName,
                        filePath = "",
                        lineNumber = lineNumber,
                        context = "type annotation"
                    ))
                }
            }
            "typescript" -> {
                val pattern = Pattern.compile(": ([A-Z][a-zA-Z0-9_]*)")
                val matcher = pattern.matcher(content)
                
                while (matcher.find()) {
                    val typeName = matcher.group(1)
                    val lineNumber = findLineNumber(content, matcher.start())
                    
                    types.add(TypeReferenceInfo(
                        typeName = typeName,
                        filePath = "",
                        lineNumber = lineNumber,
                        context = "type annotation"
                    ))
                }
            }
        }

        return types
    }

    private fun extractDependencies(configFile: CodeStructureAnalyzer.ConfigFileInfo): Map<String, DependencyInfo> {
        val dependencies = mutableMapOf<String, DependencyInfo>()

        when (configFile.type) {
            "dependency" -> {
                when {
                    configFile.path.endsWith("package.json") -> {
                        // Parse npm dependencies
                        configFile.content["dependencies"]?.let { deps ->
                            if (deps is Map<*, *>) {
                                deps.forEach { (name, version) ->
                                    dependencies[name.toString()] = DependencyInfo(
                                        name = name.toString(),
                                        version = version.toString(),
                                        type = "npm",
                                        isDevDependency = false
                                    )
                                }
                            }
                        }
                    }
                    configFile.path.endsWith("pom.xml") -> {
                        // Would need XML parsing for Maven dependencies
                    }
                    configFile.path.contains("build.gradle") -> {
                        // Would need Gradle parsing
                    }
                }
            }
        }

        return dependencies
    }

    private fun generatePossibleImportPaths(
        import: String,
        sourceFile: String,
        analysis: CodeStructureAnalyzer.ProjectAnalysis
    ): List<String> {
        val paths = mutableListOf<String>()
        val sourceDir = File(sourceFile).parent
        val projectRoot = analysis.structure.rootPath

        // Relative paths
        if (import.startsWith("./") || import.startsWith("../")) {
            val relativePath = File(sourceDir, import).absolutePath
            paths.add(relativePath)
            paths.add("$relativePath.js")
            paths.add("$relativePath.ts")
            paths.add("$relativePath.jsx")
            paths.add("$relativePath.tsx")
            paths.add("$relativePath/index.js")
            paths.add("$relativePath/index.ts")
        } else {
            // Absolute paths from project root
            val absolutePath = File(projectRoot, import).absolutePath
            paths.add(absolutePath)
            paths.add("$absolutePath.js")
            paths.add("$absolutePath.ts")
            paths.add("$absolutePath.jsx")
            paths.add("$absolutePath.tsx")
            
            // Module-style imports
            val modulePath = import.replace(".", "/")
            paths.add(File(projectRoot, "src/$modulePath").absolutePath)
            paths.add(File(projectRoot, "lib/$modulePath").absolutePath)
        }

        return paths
    }

    private fun generateExportSuggestions(
        exports: List<String>,
        filePath: String,
        analysis: CodeStructureAnalyzer.ProjectAnalysis
    ): List<ResolutionSuggestion> {
        val suggestions = mutableListOf<ResolutionSuggestion>()

        // Check if exports are used elsewhere
        exports.forEach { export ->
            val usages = findExportUsages(export, analysis, filePath)
            if (usages.isEmpty()) {
                suggestions.add(ResolutionSuggestion(
                    type = SuggestionType.REMOVE_IMPORT,
                    description = "Export '$export' is not used anywhere",
                    action = "Consider removing unused export '$export'",
                    confidence = 0.7,
                    autoApplicable = false,
                    affectedFiles = listOf(filePath)
                ))
            }
        }

        return suggestions
    }

    private fun applyAutoFixes(
        projectPath: String,
        suggestions: List<ResolutionSuggestion>
    ): List<String> {
        val appliedFixes = mutableListOf<String>()

        suggestions.filter { it.autoApplicable }.forEach { suggestion ->
            try {
                when (suggestion.type) {
                    SuggestionType.CREATE_MISSING_FILE -> {
                        if (suggestion.affectedFiles.isNotEmpty()) {
                            val filePath = suggestion.affectedFiles.first()
                            val file = File(filePath)
                            file.parentFile?.mkdirs()
                            if (!file.exists()) {
                                file.createNewFile()
                                appliedFixes.add("Created file: $filePath")
                            }
                        }
                    }
                    SuggestionType.CREATE_MISSING_FUNCTION -> {
                        // Would implement function generation
                        appliedFixes.add("Auto-fix for missing function not implemented yet")
                    }
                    SuggestionType.CREATE_MISSING_CLASS -> {
                        // Would implement class generation
                        appliedFixes.add("Auto-fix for missing class not implemented yet")
                    }
                    else -> {
                        // Other auto-fixes would be implemented here
                    }
                }
            } catch (e: Exception) {
                // Log error but continue with other fixes
            }
        }

        return appliedFixes
    }

    // Helper methods
    private fun determineLanguage(extension: String): String {
        return when (extension.lowercase()) {
            "kt" -> "kotlin"
            "java" -> "java"
            "js" -> "javascript"
            "ts" -> "typescript"
            "jsx" -> "javascript"
            "tsx" -> "typescript"
            "py" -> "python"
            else -> "unknown"
        }
    }

    private fun detectSymbolType(symbol: String, content: String, language: String): String {
        return when {
            content.contains("class $symbol") -> "class"
            content.contains("interface $symbol") -> "interface"
            content.contains("fun $symbol") || content.contains("function $symbol") -> "function"
            content.contains("val $symbol") || content.contains("var $symbol") || content.contains("const $symbol") -> "variable"
            else -> "unknown"
        }
    }

    private fun isDefaultExport(symbol: String, content: String, language: String): Boolean {
        return when (language) {
            "javascript", "typescript" -> content.contains("export default $symbol")
            else -> false
        }
    }

    private fun extractSignature(symbol: String, content: String, language: String): String {
        val lines = content.lines()
        val symbolLine = lines.find { it.contains(symbol) } ?: return ""
        return symbolLine.trim()
    }

    private fun findSymbolLineNumber(symbol: String, content: String): Int {
        val lines = content.lines()
        return lines.indexOfFirst { it.contains(symbol) } + 1
    }

    private fun findImportLineNumber(import: String, content: String): Int {
        val lines = content.lines()
        return lines.indexOfFirst { it.contains("import") && it.contains(import) } + 1
    }

    private fun findLineNumber(content: String, position: Int): Int {
        return content.substring(0, position).count { it == '\n' } + 1
    }

    private fun findBestFunctionMatch(call: FunctionCallInfo, exports: List<ExportInfo>): ExportInfo? {
        // Simple matching - in production, would use more sophisticated signature matching
        return exports.find { it.symbolType == "function" }
    }

    private fun calculateMatchConfidence(call: FunctionCallInfo, export: ExportInfo): Double {
        // Simple confidence calculation - in production, would analyze signatures
        return if (call.functionName == export.symbol) 0.9 else 0.5
    }

    private fun findExportUsages(export: String, analysis: CodeStructureAnalyzer.ProjectAnalysis, sourceFile: String): List<String> {
        val usages = mutableListOf<String>()
        
        analysis.structure.files.values
            .filter { it.path != sourceFile }
            .forEach { fileInfo ->
                if (fileInfo.imports.any { it.contains(export) }) {
                    usages.add(fileInfo.path)
                }
            }
        
        return usages
    }
}