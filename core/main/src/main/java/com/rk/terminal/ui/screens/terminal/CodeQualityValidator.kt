package com.rk.terminal.ui.screens.terminal

import org.json.JSONArray
import org.json.JSONObject
import java.io.File
import java.util.regex.Pattern
import kotlinx.coroutines.*

/**
 * Comprehensive Code Quality Validator for AI Android Agent
 * 
 * This validator ensures that all generated code is functional, syntactically correct,
 * follows best practices, and maintains proper cross-file functionality references.
 */
class CodeQualityValidator {

    data class ValidationResult(
        val isValid: Boolean,
        val score: Double, // 0.0 to 1.0
        val issues: List<ValidationIssue>,
        val suggestions: List<String>,
        val functionalityRisk: FunctionalityRisk,
        val crossFileIssues: List<CrossFileIssue>
    )

    data class ValidationIssue(
        val severity: IssueSeverity,
        val type: IssueType,
        val message: String,
        val filePath: String,
        val lineNumber: Int = 0,
        val column: Int = 0,
        val suggestion: String = "",
        val autoFixable: Boolean = false
    )

    data class CrossFileIssue(
        val type: CrossFileIssueType,
        val sourceFile: String,
        val targetFile: String,
        val reference: String,
        val message: String,
        val impact: ImpactLevel
    )

    enum class IssueSeverity {
        ERROR,   // Code won't compile/run
        WARNING, // Potential issues, bad practices
        INFO,    // Style issues, minor improvements
        SUGGESTION // Optional improvements
    }

    enum class IssueType {
        SYNTAX_ERROR,
        COMPILATION_ERROR,
        TYPE_ERROR,
        IMPORT_ERROR,
        UNUSED_IMPORT,
        MISSING_DEPENDENCY,
        CIRCULAR_DEPENDENCY,
        NAMING_CONVENTION,
        CODE_STYLE,
        PERFORMANCE,
        SECURITY,
        DOCUMENTATION,
        TESTING,
        ACCESSIBILITY,
        LOGIC_ERROR,
        RESOURCE_LEAK,
        NULL_SAFETY,
        DEPRECATED_API
    }

    enum class CrossFileIssueType {
        BROKEN_IMPORT,
        MISSING_EXPORT,
        INCOMPATIBLE_INTERFACE,
        VERSION_MISMATCH,
        CIRCULAR_REFERENCE,
        ORPHANED_REFERENCE,
        INCONSISTENT_API
    }

    enum class FunctionalityRisk {
        NONE,    // Code should work as expected
        LOW,     // Minor issues that might cause problems
        MEDIUM,  // Significant issues likely to cause problems
        HIGH,    // Code likely won't work properly
        CRITICAL // Code definitely won't work
    }

    enum class ImpactLevel {
        LOW,
        MEDIUM,
        HIGH,
        CRITICAL
    }

    companion object {
        fun validateCode(
            filePath: String,
            content: String,
            projectAnalysis: CodeStructureAnalyzer.ProjectAnalysis? = null
        ): ValidationResult {
            val validator = CodeQualityValidator()
            return validator.performValidation(filePath, content, projectAnalysis)
        }

        fun validateProject(projectPath: String): ValidationResult {
            val validator = CodeQualityValidator()
            return validator.performProjectValidation(projectPath)
        }

        fun validateCrossFileReferences(
            analysis: CodeStructureAnalyzer.ProjectAnalysis
        ): List<CrossFileIssue> {
            val validator = CodeQualityValidator()
            return validator.validateCrossFileReferences(analysis)
        }
    }

    private fun performValidation(
        filePath: String,
        content: String,
        projectAnalysis: CodeStructureAnalyzer.ProjectAnalysis?
    ): ValidationResult {
        val issues = mutableListOf<ValidationIssue>()
        val suggestions = mutableListOf<String>()

        val language = determineLanguage(File(filePath).extension)
        
        // Syntax validation
        issues.addAll(validateSyntax(content, language, filePath))
        
        // Style validation
        issues.addAll(validateStyle(content, language, filePath))
        
        // Best practices validation
        issues.addAll(validateBestPractices(content, language, filePath))
        
        // Framework-specific validation
        if (projectAnalysis?.framework != null) {
            issues.addAll(validateFrameworkCompliance(content, projectAnalysis.framework, filePath))
        }
        
        // Security validation
        issues.addAll(validateSecurity(content, language, filePath))
        
        // Performance validation
        issues.addAll(validatePerformance(content, language, filePath))
        
        // Cross-file reference validation
        val crossFileIssues = if (projectAnalysis != null) {
            validateFileReferences(filePath, content, projectAnalysis)
        } else {
            emptyList()
        }

        // Calculate overall score
        val score = calculateQualityScore(issues, crossFileIssues)
        
        // Determine functionality risk
        val functionalityRisk = assessFunctionalityRisk(issues, crossFileIssues)
        
        // Generate suggestions
        suggestions.addAll(generateImprovementSuggestions(issues, crossFileIssues, language))

        return ValidationResult(
            isValid = issues.none { it.severity == IssueSeverity.ERROR },
            score = score,
            issues = issues,
            suggestions = suggestions,
            functionalityRisk = functionalityRisk,
            crossFileIssues = crossFileIssues
        )
    }

    private fun performProjectValidation(projectPath: String): ValidationResult {
        val analysis = CodeStructureAnalyzer.analyzeProject(projectPath)
        val allIssues = mutableListOf<ValidationIssue>()
        val allCrossFileIssues = mutableListOf<CrossFileIssue>()
        val allSuggestions = mutableListOf<String>()

        // Validate each source file
        analysis.structure.files.values
            .filter { it.type == "source" }
            .forEach { fileInfo ->
                try {
                    val content = File(fileInfo.path).readText()
                    val result = performValidation(fileInfo.path, content, analysis)
                    allIssues.addAll(result.issues)
                    allCrossFileIssues.addAll(result.crossFileIssues)
                    allSuggestions.addAll(result.suggestions)
                } catch (e: Exception) {
                    allIssues.add(ValidationIssue(
                        severity = IssueSeverity.ERROR,
                        type = IssueType.SYNTAX_ERROR,
                        message = "Could not read or parse file: ${e.message}",
                        filePath = fileInfo.path,
                        autoFixable = false
                    ))
                }
            }

        // Validate cross-file references
        allCrossFileIssues.addAll(validateCrossFileReferences(analysis))

        // Calculate overall project score
        val score = calculateQualityScore(allIssues, allCrossFileIssues)
        val functionalityRisk = assessFunctionalityRisk(allIssues, allCrossFileIssues)

        return ValidationResult(
            isValid = allIssues.none { it.severity == IssueSeverity.ERROR },
            score = score,
            issues = allIssues,
            suggestions = allSuggestions.distinct(),
            functionalityRisk = functionalityRisk,
            crossFileIssues = allCrossFileIssues
        )
    }

    private fun validateSyntax(content: String, language: String, filePath: String): List<ValidationIssue> {
        val issues = mutableListOf<ValidationIssue>()

        when (language) {
            "kotlin" -> issues.addAll(validateKotlinSyntax(content, filePath))
            "java" -> issues.addAll(validateJavaSyntax(content, filePath))
            "javascript", "typescript" -> issues.addAll(validateJavaScriptSyntax(content, filePath))
            "python" -> issues.addAll(validatePythonSyntax(content, filePath))
            "json" -> issues.addAll(validateJsonSyntax(content, filePath))
            "xml" -> issues.addAll(validateXmlSyntax(content, filePath))
        }

        return issues
    }

    private fun validateKotlinSyntax(content: String, filePath: String): List<ValidationIssue> {
        val issues = mutableListOf<ValidationIssue>()

        // Check for basic syntax errors
        val lines = content.lines()
        lines.forEachIndexed { index, line ->
            val lineNumber = index + 1
            
            // Check for unmatched brackets
            if (countChar(line, '{') != countChar(line, '}')) {
                if (line.trim().endsWith("{") || line.trim().startsWith("}")) {
                    // Acceptable - likely multi-line structure
                } else {
                    issues.add(ValidationIssue(
                        severity = IssueSeverity.ERROR,
                        type = IssueType.SYNTAX_ERROR,
                        message = "Unmatched brackets in line",
                        filePath = filePath,
                        lineNumber = lineNumber,
                        suggestion = "Check bracket pairing",
                        autoFixable = false
                    ))
                }
            }

            // Check for incomplete statements
            if (line.trim().endsWith(",") && !line.contains("(") && !line.contains("[")) {
                issues.add(ValidationIssue(
                    severity = IssueSeverity.WARNING,
                    type = IssueType.SYNTAX_ERROR,
                    message = "Potentially incomplete statement",
                    filePath = filePath,
                    lineNumber = lineNumber,
                    suggestion = "Verify statement completion",
                    autoFixable = false
                ))
            }
        }

        // Check for proper class declarations
        val classPattern = Pattern.compile("class\\s+([A-Z][a-zA-Z0-9_]*)")
        val classMatcher = classPattern.matcher(content)
        if (classMatcher.find()) {
            val className = classMatcher.group(1)
            val fileName = File(filePath).nameWithoutExtension
            if (className != fileName) {
                issues.add(ValidationIssue(
                    severity = IssueSeverity.WARNING,
                    type = IssueType.NAMING_CONVENTION,
                    message = "Class name '$className' should match file name '$fileName'",
                    filePath = filePath,
                    suggestion = "Rename class to '$fileName' or file to '$className.kt'",
                    autoFixable = true
                ))
            }
        }

        return issues
    }

    private fun validateJavaSyntax(content: String, filePath: String): List<ValidationIssue> {
        val issues = mutableListOf<ValidationIssue>()

        // Check for basic Java syntax patterns
        val lines = content.lines()
        lines.forEachIndexed { index, line ->
            val lineNumber = index + 1
            val trimmedLine = line.trim()

            // Check for missing semicolons
            if (trimmedLine.isNotEmpty() && 
                !trimmedLine.startsWith("//") && 
                !trimmedLine.startsWith("/*") &&
                !trimmedLine.endsWith(";") && 
                !trimmedLine.endsWith("{") && 
                !trimmedLine.endsWith("}") &&
                !trimmedLine.contains("@") &&
                (trimmedLine.contains("=") || trimmedLine.startsWith("return") || trimmedLine.startsWith("throw"))) {
                
                issues.add(ValidationIssue(
                    severity = IssueSeverity.ERROR,
                    type = IssueType.SYNTAX_ERROR,
                    message = "Missing semicolon",
                    filePath = filePath,
                    lineNumber = lineNumber,
                    suggestion = "Add semicolon at end of statement",
                    autoFixable = true
                ))
            }
        }

        // Check for proper package declaration
        if (!content.contains("package ") && !File(filePath).name.startsWith("package-info")) {
            issues.add(ValidationIssue(
                severity = IssueSeverity.WARNING,
                type = IssueType.CODE_STYLE,
                message = "Missing package declaration",
                filePath = filePath,
                suggestion = "Add package declaration at the top of the file",
                autoFixable = true
            ))
        }

        return issues
    }

    private fun validateJavaScriptSyntax(content: String, filePath: String): List<ValidationIssue> {
        val issues = mutableListOf<ValidationIssue>()

        // Check for common JavaScript/TypeScript issues
        val lines = content.lines()
        lines.forEachIndexed { index, line ->
            val lineNumber = index + 1
            val trimmedLine = line.trim()

            // Check for var usage (prefer let/const)
            if (trimmedLine.startsWith("var ")) {
                issues.add(ValidationIssue(
                    severity = IssueSeverity.WARNING,
                    type = IssueType.CODE_STYLE,
                    message = "Use 'let' or 'const' instead of 'var'",
                    filePath = filePath,
                    lineNumber = lineNumber,
                    suggestion = "Replace 'var' with 'let' or 'const'",
                    autoFixable = true
                ))
            }

            // Check for == instead of ===
            if (trimmedLine.contains("==") && !trimmedLine.contains("===") && !trimmedLine.contains("!==")) {
                issues.add(ValidationIssue(
                    severity = IssueSeverity.WARNING,
                    type = IssueType.CODE_STYLE,
                    message = "Use strict equality (===) instead of loose equality (==)",
                    filePath = filePath,
                    lineNumber = lineNumber,
                    suggestion = "Replace '==' with '==='",
                    autoFixable = true
                ))
            }
        }

        return issues
    }

    private fun validatePythonSyntax(content: String, filePath: String): List<ValidationIssue> {
        val issues = mutableListOf<ValidationIssue>()

        val lines = content.lines()
        lines.forEachIndexed { index, line ->
            val lineNumber = index + 1
            val trimmedLine = line.trim()

            // Check for proper indentation (assuming 4 spaces)
            if (line.isNotEmpty() && !line.startsWith(" ") && !line.startsWith("\t") && 
                (trimmedLine.startsWith("def ") || trimmedLine.startsWith("class ") || 
                 trimmedLine.startsWith("if ") || trimmedLine.startsWith("for ") ||
                 trimmedLine.startsWith("while ") || trimmedLine.startsWith("try:"))) {
                // Top-level statements are OK
            } else if (line.startsWith(" ") && line.length - line.trimStart().length % 4 != 0) {
                issues.add(ValidationIssue(
                    severity = IssueSeverity.WARNING,
                    type = IssueType.CODE_STYLE,
                    message = "Inconsistent indentation (use 4 spaces)",
                    filePath = filePath,
                    lineNumber = lineNumber,
                    suggestion = "Use 4 spaces for indentation",
                    autoFixable = true
                ))
            }

            // Check for missing colons
            if ((trimmedLine.startsWith("if ") || trimmedLine.startsWith("for ") || 
                 trimmedLine.startsWith("while ") || trimmedLine.startsWith("def ") ||
                 trimmedLine.startsWith("class ")) && !trimmedLine.endsWith(":")) {
                issues.add(ValidationIssue(
                    severity = IssueSeverity.ERROR,
                    type = IssueType.SYNTAX_ERROR,
                    message = "Missing colon at end of statement",
                    filePath = filePath,
                    lineNumber = lineNumber,
                    suggestion = "Add colon at end of line",
                    autoFixable = true
                ))
            }
        }

        return issues
    }

    private fun validateJsonSyntax(content: String, filePath: String): List<ValidationIssue> {
        val issues = mutableListOf<ValidationIssue>()

        try {
            JSONObject(content)
        } catch (e: Exception) {
            try {
                JSONArray(content)
            } catch (e2: Exception) {
                issues.add(ValidationIssue(
                    severity = IssueSeverity.ERROR,
                    type = IssueType.SYNTAX_ERROR,
                    message = "Invalid JSON syntax: ${e.message}",
                    filePath = filePath,
                    suggestion = "Fix JSON syntax errors",
                    autoFixable = false
                ))
            }
        }

        return issues
    }

    private fun validateXmlSyntax(content: String, filePath: String): List<ValidationIssue> {
        val issues = mutableListOf<ValidationIssue>()

        // Basic XML validation
        val openTags = mutableListOf<String>()
        val lines = content.lines()
        
        lines.forEachIndexed { index, line ->
            val lineNumber = index + 1
            val tagPattern = Pattern.compile("<(/?)([a-zA-Z][a-zA-Z0-9]*)")
            val matcher = tagPattern.matcher(line)
            
            while (matcher.find()) {
                val isClosing = matcher.group(1) == "/"
                val tagName = matcher.group(2)
                
                if (isClosing) {
                    if (openTags.isEmpty() || openTags.last() != tagName) {
                        issues.add(ValidationIssue(
                            severity = IssueSeverity.ERROR,
                            type = IssueType.SYNTAX_ERROR,
                            message = "Mismatched closing tag: $tagName",
                            filePath = filePath,
                            lineNumber = lineNumber,
                            suggestion = "Check tag pairing",
                            autoFixable = false
                        ))
                    } else {
                        openTags.removeLastOrNull()
                    }
                } else if (!line.contains("/>")) {
                    openTags.add(tagName)
                }
            }
        }

        return issues
    }

    private fun validateStyle(content: String, language: String, filePath: String): List<ValidationIssue> {
        val issues = mutableListOf<ValidationIssue>()

        when (language) {
            "kotlin" -> issues.addAll(validateKotlinStyle(content, filePath))
            "java" -> issues.addAll(validateJavaStyle(content, filePath))
            "javascript", "typescript" -> issues.addAll(validateJavaScriptStyle(content, filePath))
            "python" -> issues.addAll(validatePythonStyle(content, filePath))
        }

        return issues
    }

    private fun validateKotlinStyle(content: String, filePath: String): List<ValidationIssue> {
        val issues = mutableListOf<ValidationIssue>()

        val lines = content.lines()
        lines.forEachIndexed { index, line ->
            val lineNumber = index + 1

            // Check line length
            if (line.length > 120) {
                issues.add(ValidationIssue(
                    severity = IssueSeverity.INFO,
                    type = IssueType.CODE_STYLE,
                    message = "Line too long (${line.length} > 120 characters)",
                    filePath = filePath,
                    lineNumber = lineNumber,
                    suggestion = "Break long line into multiple lines",
                    autoFixable = true
                ))
            }

            // Check for trailing whitespace
            if (line.endsWith(" ") || line.endsWith("\t")) {
                issues.add(ValidationIssue(
                    severity = IssueSeverity.INFO,
                    type = IssueType.CODE_STYLE,
                    message = "Trailing whitespace",
                    filePath = filePath,
                    lineNumber = lineNumber,
                    suggestion = "Remove trailing whitespace",
                    autoFixable = true
                ))
            }
        }

        return issues
    }

    private fun validateJavaStyle(content: String, filePath: String): List<ValidationIssue> {
        val issues = mutableListOf<ValidationIssue>()

        // Check for Google Java Style compliance
        val lines = content.lines()
        lines.forEachIndexed { index, line ->
            val lineNumber = index + 1

            // Check indentation (2 spaces for Java)
            if (line.startsWith("    ")) {
                issues.add(ValidationIssue(
                    severity = IssueSeverity.INFO,
                    type = IssueType.CODE_STYLE,
                    message = "Use 2 spaces for indentation instead of 4",
                    filePath = filePath,
                    lineNumber = lineNumber,
                    suggestion = "Use 2-space indentation",
                    autoFixable = true
                ))
            }
        }

        return issues
    }

    private fun validateJavaScriptStyle(content: String, filePath: String): List<ValidationIssue> {
        val issues = mutableListOf<ValidationIssue>()

        val lines = content.lines()
        lines.forEachIndexed { index, line ->
            val lineNumber = index + 1

            // Check for semicolon usage consistency
            if (line.trim().let { it.isNotEmpty() && it != "{" && it != "}" && !it.startsWith("//") && !it.endsWith(";") && (it.contains("=") || it.startsWith("return")) }) {
                issues.add(ValidationIssue(
                    severity = IssueSeverity.INFO,
                    type = IssueType.CODE_STYLE,
                    message = "Consider using semicolons consistently",
                    filePath = filePath,
                    lineNumber = lineNumber,
                    suggestion = "Add semicolon for consistency",
                    autoFixable = true
                ))
            }
        }

        return issues
    }

    private fun validatePythonStyle(content: String, filePath: String): List<ValidationIssue> {
        val issues = mutableListOf<ValidationIssue>()

        val lines = content.lines()
        lines.forEachIndexed { index, line ->
            val lineNumber = index + 1

            // Check for PEP 8 line length
            if (line.length > 79) {
                issues.add(ValidationIssue(
                    severity = IssueSeverity.INFO,
                    type = IssueType.CODE_STYLE,
                    message = "Line too long (${line.length} > 79 characters) - PEP 8",
                    filePath = filePath,
                    lineNumber = lineNumber,
                    suggestion = "Break long line according to PEP 8",
                    autoFixable = true
                ))
            }
        }

        return issues
    }

    private fun validateBestPractices(content: String, language: String, filePath: String): List<ValidationIssue> {
        val issues = mutableListOf<ValidationIssue>()

        when (language) {
            "kotlin" -> issues.addAll(validateKotlinBestPractices(content, filePath))
            "java" -> issues.addAll(validateJavaBestPractices(content, filePath))
            "javascript", "typescript" -> issues.addAll(validateJavaScriptBestPractices(content, filePath))
            "python" -> issues.addAll(validatePythonBestPractices(content, filePath))
        }

        return issues
    }

    private fun validateKotlinBestPractices(content: String, filePath: String): List<ValidationIssue> {
        val issues = mutableListOf<ValidationIssue>()

        // Check for nullable safety
        if (content.contains("!!")) {
            issues.add(ValidationIssue(
                severity = IssueSeverity.WARNING,
                type = IssueType.NULL_SAFETY,
                message = "Avoid using !! operator, prefer safe calls",
                filePath = filePath,
                suggestion = "Use safe call operator ?. or proper null checks",
                autoFixable = false
            ))
        }

        // Check for data class usage
        if (content.contains("class ") && !content.contains("data class") && 
            content.contains("val ") && content.contains("constructor")) {
            issues.add(ValidationIssue(
                severity = IssueSeverity.SUGGESTION,
                type = IssueType.CODE_STYLE,
                message = "Consider using data class for simple data holders",
                filePath = filePath,
                suggestion = "Use data class if this is a simple data holder",
                autoFixable = false
            ))
        }

        return issues
    }

    private fun validateJavaBestPractices(content: String, filePath: String): List<ValidationIssue> {
        val issues = mutableListOf<ValidationIssue>()

        // Check for proper exception handling
        if (content.contains("catch (Exception e)") && !content.contains("catch (")) {
            issues.add(ValidationIssue(
                severity = IssueSeverity.WARNING,
                type = IssueType.LOGIC_ERROR,
                message = "Catching generic Exception is not recommended",
                filePath = filePath,
                suggestion = "Catch specific exception types",
                autoFixable = false
            ))
        }

        // Check for resource management
        if (content.contains("new FileInputStream") && !content.contains("try-with-resources")) {
            issues.add(ValidationIssue(
                severity = IssueSeverity.WARNING,
                type = IssueType.RESOURCE_LEAK,
                message = "Use try-with-resources for resource management",
                filePath = filePath,
                suggestion = "Use try-with-resources statement",
                autoFixable = false
            ))
        }

        return issues
    }

    private fun validateJavaScriptBestPractices(content: String, filePath: String): List<ValidationIssue> {
        val issues = mutableListOf<ValidationIssue>()

        // Check for async/await best practices
        if (content.contains("async ") && content.contains(".then(")) {
            issues.add(ValidationIssue(
                severity = IssueSeverity.WARNING,
                type = IssueType.CODE_STYLE,
                message = "Mixing async/await with .then() is not recommended",
                filePath = filePath,
                suggestion = "Use either async/await or Promises consistently",
                autoFixable = false
            ))
        }

        return issues
    }

    private fun validatePythonBestPractices(content: String, filePath: String): List<ValidationIssue> {
        val issues = mutableListOf<ValidationIssue>()

        // Check for list comprehensions vs loops
        if (content.contains("for ") && content.contains("append(")) {
            issues.add(ValidationIssue(
                severity = IssueSeverity.SUGGESTION,
                type = IssueType.PERFORMANCE,
                message = "Consider using list comprehension instead of loop with append",
                filePath = filePath,
                suggestion = "Use list comprehension for better performance",
                autoFixable = false
            ))
        }

        return issues
    }

    private fun validateFrameworkCompliance(
        content: String, 
        framework: FrameworkTemplateManager.FrameworkTemplate, 
        filePath: String
    ): List<ValidationIssue> {
        val issues = mutableListOf<ValidationIssue>()

        when (framework.name) {
            "Kotlin Android" -> issues.addAll(validateAndroidCompliance(content, filePath))
            "Java Spring Boot" -> issues.addAll(validateSpringBootCompliance(content, filePath))
            "Next.js" -> issues.addAll(validateNextJsCompliance(content, filePath))
            "Flask" -> issues.addAll(validateFlaskCompliance(content, filePath))
            "React" -> issues.addAll(validateReactCompliance(content, filePath))
            "Express.js" -> issues.addAll(validateExpressCompliance(content, filePath))
        }

        return issues
    }

    private fun validateAndroidCompliance(content: String, filePath: String): List<ValidationIssue> {
        val issues = mutableListOf<ValidationIssue>()

        // Check for proper Android lifecycle
        if (content.contains("class ") && content.contains("Activity") && !content.contains("onCreate")) {
            issues.add(ValidationIssue(
                severity = IssueSeverity.WARNING,
                type = IssueType.LOGIC_ERROR,
                message = "Activity should override onCreate method",
                filePath = filePath,
                suggestion = "Add onCreate method",
                autoFixable = true
            ))
        }

        return issues
    }

    private fun validateSpringBootCompliance(content: String, filePath: String): List<ValidationIssue> {
        val issues = mutableListOf<ValidationIssue>()

        // Check for proper Spring annotations
        if (content.contains("@RestController") && !content.contains("@RequestMapping")) {
            issues.add(ValidationIssue(
                severity = IssueSeverity.WARNING,
                type = IssueType.CODE_STYLE,
                message = "RestController should have RequestMapping",
                filePath = filePath,
                suggestion = "Add @RequestMapping annotation",
                autoFixable = true
            ))
        }

        return issues
    }

    private fun validateNextJsCompliance(content: String, filePath: String): List<ValidationIssue> {
        val issues = mutableListOf<ValidationIssue>()

        // Check for proper Next.js patterns
        if (filePath.contains("/pages/") && !content.contains("export default")) {
            issues.add(ValidationIssue(
                severity = IssueSeverity.ERROR,
                type = IssueType.LOGIC_ERROR,
                message = "Next.js pages must have default export",
                filePath = filePath,
                suggestion = "Add default export",
                autoFixable = true
            ))
        }

        return issues
    }

    private fun validateFlaskCompliance(content: String, filePath: String): List<ValidationIssue> {
        val issues = mutableListOf<ValidationIssue>()

        // Check for proper Flask patterns
        if (content.contains("@app.route") && !content.contains("def ")) {
            issues.add(ValidationIssue(
                severity = IssueSeverity.ERROR,
                type = IssueType.SYNTAX_ERROR,
                message = "Flask route must have function definition",
                filePath = filePath,
                suggestion = "Add function definition after route decorator",
                autoFixable = false
            ))
        }

        return issues
    }

    private fun validateReactCompliance(content: String, filePath: String): List<ValidationIssue> {
        val issues = mutableListOf<ValidationIssue>()

        // Check for proper React patterns
        if (content.contains("React.FC") && !content.contains("return")) {
            issues.add(ValidationIssue(
                severity = IssueSeverity.ERROR,
                type = IssueType.LOGIC_ERROR,
                message = "React component must return JSX",
                filePath = filePath,
                suggestion = "Add return statement with JSX",
                autoFixable = false
            ))
        }

        return issues
    }

    private fun validateExpressCompliance(content: String, filePath: String): List<ValidationIssue> {
        val issues = mutableListOf<ValidationIssue>()

        // Check for proper Express patterns
        if (content.contains("app.") && content.contains("(req, res") && !content.contains("res.")) {
            issues.add(ValidationIssue(
                severity = IssueSeverity.WARNING,
                type = IssueType.LOGIC_ERROR,
                message = "Express route handler should send response",
                filePath = filePath,
                suggestion = "Add response using res.send(), res.json(), etc.",
                autoFixable = false
            ))
        }

        return issues
    }

    private fun validateSecurity(content: String, language: String, filePath: String): List<ValidationIssue> {
        val issues = mutableListOf<ValidationIssue>()

        // Check for common security issues
        if (content.contains("eval(")) {
            issues.add(ValidationIssue(
                severity = IssueSeverity.ERROR,
                type = IssueType.SECURITY,
                message = "Use of eval() is dangerous and should be avoided",
                filePath = filePath,
                suggestion = "Find alternative to eval()",
                autoFixable = false
            ))
        }

        if (content.contains("password") && content.contains("=") && !content.contains("hash")) {
            issues.add(ValidationIssue(
                severity = IssueSeverity.WARNING,
                type = IssueType.SECURITY,
                message = "Potential plaintext password storage",
                filePath = filePath,
                suggestion = "Hash passwords before storage",
                autoFixable = false
            ))
        }

        return issues
    }

    private fun validatePerformance(content: String, language: String, filePath: String): List<ValidationIssue> {
        val issues = mutableListOf<ValidationIssue>()

        // Check for performance anti-patterns
        when (language) {
            "javascript", "typescript" -> {
                if (content.contains("for (") && content.contains(".length")) {
                    issues.add(ValidationIssue(
                        severity = IssueSeverity.SUGGESTION,
                        type = IssueType.PERFORMANCE,
                        message = "Cache array length in loops for better performance",
                        filePath = filePath,
                        suggestion = "Store array.length in variable before loop",
                        autoFixable = true
                    ))
                }
            }
            "python" -> {
                if (content.count("for ") > 2 && content.contains("range(len(")) {
                    issues.add(ValidationIssue(
                        severity = IssueSeverity.SUGGESTION,
                        type = IssueType.PERFORMANCE,
                        message = "Consider using enumerate() instead of range(len())",
                        filePath = filePath,
                        suggestion = "Use enumerate() for index and value",
                        autoFixable = false
                    ))
                }
            }
        }

        return issues
    }

    private fun validateFileReferences(
        filePath: String, 
        content: String, 
        analysis: CodeStructureAnalyzer.ProjectAnalysis
    ): List<CrossFileIssue> {
        val issues = mutableListOf<CrossFileIssue>()
        val language = determineLanguage(File(filePath).extension)

        // Extract imports from content
        val imports = extractImports(content, language)
        
        imports.forEach { import ->
            val referencedFile = findReferencedFile(import, analysis)
            if (referencedFile == null) {
                issues.add(CrossFileIssue(
                    type = CrossFileIssueType.BROKEN_IMPORT,
                    sourceFile = filePath,
                    targetFile = import,
                    reference = import,
                    message = "Cannot resolve import: $import",
                    impact = ImpactLevel.HIGH
                ))
            }
        }

        // Check for exported functions being used
        val fileInfo = analysis.structure.files.values.find { it.path == filePath }
        fileInfo?.exports?.forEach { export ->
            val usages = findExportUsages(export, analysis, filePath)
            if (usages.isEmpty()) {
                issues.add(CrossFileIssue(
                    type = CrossFileIssueType.ORPHANED_REFERENCE,
                    sourceFile = filePath,
                    targetFile = "",
                    reference = export,
                    message = "Exported '$export' is not used anywhere",
                    impact = ImpactLevel.LOW
                ))
            }
        }

        return issues
    }

    private fun validateCrossFileReferences(analysis: CodeStructureAnalyzer.ProjectAnalysis): List<CrossFileIssue> {
        val issues = mutableListOf<CrossFileIssue>()

        // Check for circular dependencies
        analysis.dependencies.cycles.forEach { cycle ->
            cycle.zipWithNext().forEach { (from, to) ->
                issues.add(CrossFileIssue(
                    type = CrossFileIssueType.CIRCULAR_REFERENCE,
                    sourceFile = from,
                    targetFile = to,
                    reference = "dependency cycle",
                    message = "Circular dependency detected: ${cycle.joinToString(" -> ")}",
                    impact = ImpactLevel.HIGH
                ))
            }
        }

        // Check for broken imports across all files
        analysis.structure.files.values.forEach { fileInfo ->
            fileInfo.imports.forEach { import ->
                val referencedFile = findReferencedFile(import, analysis)
                if (referencedFile == null) {
                    issues.add(CrossFileIssue(
                        type = CrossFileIssueType.BROKEN_IMPORT,
                        sourceFile = fileInfo.path,
                        targetFile = import,
                        reference = import,
                        message = "Cannot resolve import: $import",
                        impact = ImpactLevel.HIGH
                    ))
                }
            }
        }

        return issues
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

    private fun findReferencedFile(import: String, analysis: CodeStructureAnalyzer.ProjectAnalysis): String? {
        // Try to find the file that matches this import
        return analysis.structure.files.values.find { fileInfo ->
            fileInfo.exports.any { it == import.substringAfterLast(".") } ||
            fileInfo.path.contains(import.replace(".", "/"))
        }?.path
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

    private fun calculateQualityScore(issues: List<ValidationIssue>, crossFileIssues: List<CrossFileIssue>): Double {
        var score = 1.0

        issues.forEach { issue ->
            when (issue.severity) {
                IssueSeverity.ERROR -> score -= 0.2
                IssueSeverity.WARNING -> score -= 0.1
                IssueSeverity.INFO -> score -= 0.05
                IssueSeverity.SUGGESTION -> score -= 0.02
            }
        }

        crossFileIssues.forEach { issue ->
            when (issue.impact) {
                ImpactLevel.CRITICAL -> score -= 0.3
                ImpactLevel.HIGH -> score -= 0.15
                ImpactLevel.MEDIUM -> score -= 0.08
                ImpactLevel.LOW -> score -= 0.03
            }
        }

        return maxOf(0.0, score)
    }

    private fun assessFunctionalityRisk(issues: List<ValidationIssue>, crossFileIssues: List<CrossFileIssue>): FunctionalityRisk {
        val criticalIssues = issues.count { it.severity == IssueSeverity.ERROR } + 
                            crossFileIssues.count { it.impact == ImpactLevel.CRITICAL }
        val highIssues = issues.count { it.severity == IssueSeverity.WARNING } + 
                        crossFileIssues.count { it.impact == ImpactLevel.HIGH }

        return when {
            criticalIssues >= 3 -> FunctionalityRisk.CRITICAL
            criticalIssues >= 1 || highIssues >= 5 -> FunctionalityRisk.HIGH
            highIssues >= 2 -> FunctionalityRisk.MEDIUM
            issues.isNotEmpty() || crossFileIssues.isNotEmpty() -> FunctionalityRisk.LOW
            else -> FunctionalityRisk.NONE
        }
    }

    private fun generateImprovementSuggestions(
        issues: List<ValidationIssue>, 
        crossFileIssues: List<CrossFileIssue>, 
        language: String
    ): List<String> {
        val suggestions = mutableListOf<String>()

        // Add specific suggestions based on issues
        if (issues.any { it.type == IssueType.SYNTAX_ERROR }) {
            suggestions.add("Fix syntax errors to ensure code compiles")
        }

        if (issues.any { it.type == IssueType.SECURITY }) {
            suggestions.add("Review and fix security vulnerabilities")
        }

        if (crossFileIssues.any { it.type == CrossFileIssueType.BROKEN_IMPORT }) {
            suggestions.add("Fix broken imports and references")
        }

        if (crossFileIssues.any { it.type == CrossFileIssueType.CIRCULAR_REFERENCE }) {
            suggestions.add("Refactor to eliminate circular dependencies")
        }

        // Add language-specific suggestions
        when (language) {
            "kotlin" -> {
                if (issues.any { it.type == IssueType.NULL_SAFETY }) {
                    suggestions.add("Improve null safety with proper null checks")
                }
            }
            "javascript", "typescript" -> {
                if (issues.any { it.message.contains("===") }) {
                    suggestions.add("Use strict equality operators consistently")
                }
            }
            "python" -> {
                if (issues.any { it.message.contains("PEP 8") }) {
                    suggestions.add("Follow PEP 8 style guidelines")
                }
            }
        }

        return suggestions.distinct()
    }

    private fun determineLanguage(extension: String): String {
        return when (extension.lowercase()) {
            "kt" -> "kotlin"
            "java" -> "java"
            "js" -> "javascript"
            "ts" -> "typescript"
            "py" -> "python"
            "json" -> "json"
            "xml" -> "xml"
            else -> "unknown"
        }
    }

    private fun countChar(text: String, char: Char): Int {
        return text.count { it == char }
    }
}