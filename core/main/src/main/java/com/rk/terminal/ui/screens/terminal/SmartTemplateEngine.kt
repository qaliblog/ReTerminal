package com.rk.terminal.ui.screens.terminal

import org.json.JSONArray
import org.json.JSONObject
import java.io.File
import java.util.regex.Pattern

/**
 * Smart Template Engine for AI Android Agent
 * 
 * This engine adapts based on detected project patterns and integrates all
 * framework knowledge, code analysis, and validation to generate intelligent,
 * context-aware code that maintains consistency and functionality.
 */
class SmartTemplateEngine {

    data class GenerationRequest(
        val type: GenerationType,
        val context: GenerationContext,
        val requirements: List<String>,
        val constraints: List<String> = emptyList(),
        val preferences: Map<String, Any> = emptyMap()
    )

    data class GenerationContext(
        val projectPath: String,
        val targetFile: String,
        val existingContent: String = "",
        val relatedFiles: List<String> = emptyList(),
        val userIntent: String,
        val framework: FrameworkTemplateManager.FrameworkTemplate?,
        val projectAnalysis: CodeStructureAnalyzer.ProjectAnalysis?
    )

    data class GenerationResult(
        val success: Boolean,
        val generatedContent: String,
        val appliedPatterns: List<String>,
        val validationResult: CodeQualityValidator.ValidationResult,
        val suggestions: List<String>,
        val metadata: GenerationMetadata
    )

    data class GenerationMetadata(
        val templateUsed: String,
        val adaptations: List<String>,
        val confidence: Double,
        val generationTime: Long,
        val dependenciesAdded: List<String>,
        val importsAdded: List<String>,
        val exportsAdded: List<String>
    )

    enum class GenerationType {
        FILE_CREATION,
        CLASS_IMPLEMENTATION,
        FUNCTION_IMPLEMENTATION,
        COMPONENT_CREATION,
        API_ENDPOINT,
        DATABASE_MODEL,
        TEST_IMPLEMENTATION,
        CONFIGURATION,
        DOCUMENTATION,
        REFACTORING
    }

    data class AdaptiveTemplate(
        val name: String,
        val baseTemplate: String,
        val variables: Map<String, TemplateVariable>,
        val adaptationRules: List<AdaptationRule>,
        val validationRules: List<ValidationRule>,
        val framework: String?,
        val language: String
    )

    data class TemplateVariable(
        val name: String,
        val type: String, // "string", "list", "boolean", "object"
        val defaultValue: Any?,
        val description: String,
        val isRequired: Boolean = true,
        val validator: ((Any) -> Boolean)? = null
    )

    data class AdaptationRule(
        val condition: String,
        val action: String,
        val priority: Int = 0
    )

    data class ValidationRule(
        val name: String,
        val check: String,
        val severity: String,
        val message: String
    )

    companion object {
        fun generateCode(request: GenerationRequest): GenerationResult {
            val engine = SmartTemplateEngine()
            return engine.performGeneration(request)
        }

        fun getAvailableTemplates(framework: String?, language: String?): List<AdaptiveTemplate> {
            val engine = SmartTemplateEngine()
            return engine.getTemplatesForContext(framework, language)
        }

        fun analyzeAndGenerate(
            projectPath: String,
            targetFile: String,
            userIntent: String,
            type: GenerationType
        ): GenerationResult {
            val engine = SmartTemplateEngine()
            return engine.performIntelligentGeneration(projectPath, targetFile, userIntent, type)
        }
    }

    private val templateRegistry = mutableMapOf<String, AdaptiveTemplate>()

    init {
        initializeTemplates()
    }

    private fun performGeneration(request: GenerationRequest): GenerationResult {
        val startTime = System.currentTimeMillis()

        // Analyze project context if not provided
        val analysis = request.context.projectAnalysis 
            ?: CodeStructureAnalyzer.analyzeProject(request.context.projectPath)

        // Select best template
        val template = selectBestTemplate(request, analysis)

        // Generate variable values from context
        val variables = generateVariableValues(request, analysis, template)

        // Apply adaptations based on project patterns
        val adaptedTemplate = applyAdaptations(template, request, analysis)

        // Generate content
        val content = generateContent(adaptedTemplate, variables)

        // Validate generated content
        val validationResult = CodeQualityValidator.validateCode(
            request.context.targetFile,
            content,
            analysis
        )

        // Resolve references and fix issues
        val finalContent = if (validationResult.functionalityRisk != CodeQualityValidator.FunctionalityRisk.NONE) {
            fixGeneratedContent(content, validationResult, request, analysis)
        } else {
            content
        }

        // Final validation
        val finalValidation = CodeQualityValidator.validateCode(
            request.context.targetFile,
            finalContent,
            analysis
        )

        val endTime = System.currentTimeMillis()

        return GenerationResult(
            success = finalValidation.isValid,
            generatedContent = finalContent,
            appliedPatterns = extractAppliedPatterns(adaptedTemplate),
            validationResult = finalValidation,
            suggestions = generateImprovementSuggestions(finalValidation, request),
            metadata = GenerationMetadata(
                templateUsed = template.name,
                adaptations = extractAdaptations(adaptedTemplate, template),
                confidence = calculateGenerationConfidence(finalValidation, template),
                generationTime = endTime - startTime,
                dependenciesAdded = extractDependencies(finalContent),
                importsAdded = extractImports(finalContent),
                exportsAdded = extractExports(finalContent)
            )
        )
    }

    private fun performIntelligentGeneration(
        projectPath: String,
        targetFile: String,
        userIntent: String,
        type: GenerationType
    ): GenerationResult {
        // Analyze project
        val analysis = CodeStructureAnalyzer.analyzeProject(projectPath)

        // Detect framework and patterns
        val framework = analysis.framework

        // Build context
        val context = GenerationContext(
            projectPath = projectPath,
            targetFile = targetFile,
            existingContent = if (File(targetFile).exists()) File(targetFile).readText() else "",
            relatedFiles = findRelatedFiles(targetFile, analysis),
            userIntent = userIntent,
            framework = framework,
            projectAnalysis = analysis
        )

        // Extract requirements from intent
        val requirements = extractRequirements(userIntent, type, analysis)

        // Build request
        val request = GenerationRequest(
            type = type,
            context = context,
            requirements = requirements,
            constraints = generateConstraints(analysis),
            preferences = extractPreferences(analysis)
        )

        return performGeneration(request)
    }

    private fun initializeTemplates() {
        // Kotlin Android Templates
        templateRegistry["kotlin_activity"] = AdaptiveTemplate(
            name = "Kotlin Activity",
            baseTemplate = getKotlinActivityTemplate(),
            variables = mapOf(
                "className" to TemplateVariable("className", "string", null, "Activity class name"),
                "layoutName" to TemplateVariable("layoutName", "string", "activity_main", "Layout file name"),
                "hasViewModel" to TemplateVariable("hasViewModel", "boolean", false, "Include ViewModel"),
                "useCompose" to TemplateVariable("useCompose", "boolean", true, "Use Jetpack Compose")
            ),
            adaptationRules = listOf(
                AdaptationRule("hasViewModel == true", "add_viewmodel_integration", 1),
                AdaptationRule("useCompose == true", "use_compose_ui", 2)
            ),
            validationRules = listOf(
                ValidationRule("lifecycle", "contains('onCreate')", "error", "Activity must override onCreate")
            ),
            framework = "kotlin_android",
            language = "kotlin"
        )

        templateRegistry["kotlin_composable"] = AdaptiveTemplate(
            name = "Kotlin Composable",
            baseTemplate = getKotlinComposableTemplate(),
            variables = mapOf(
                "componentName" to TemplateVariable("componentName", "string", null, "Composable component name"),
                "parameters" to TemplateVariable("parameters", "list", emptyList<String>(), "Component parameters"),
                "hasState" to TemplateVariable("hasState", "boolean", false, "Component has internal state"),
                "preview" to TemplateVariable("preview", "boolean", true, "Include preview function")
            ),
            adaptationRules = listOf(
                AdaptationRule("hasState == true", "add_state_management", 1),
                AdaptationRule("preview == true", "add_preview_function", 2)
            ),
            validationRules = listOf(
                ValidationRule("composable_annotation", "contains('@Composable')", "error", "Function must have @Composable annotation")
            ),
            framework = "kotlin_android",
            language = "kotlin"
        )

        // Spring Boot Templates
        templateRegistry["spring_controller"] = AdaptiveTemplate(
            name = "Spring Boot Controller",
            baseTemplate = getSpringControllerTemplate(),
            variables = mapOf(
                "controllerName" to TemplateVariable("controllerName", "string", null, "Controller class name"),
                "basePath" to TemplateVariable("basePath", "string", "/api", "Base API path"),
                "entityName" to TemplateVariable("entityName", "string", null, "Entity name"),
                "serviceName" to TemplateVariable("serviceName", "string", null, "Service class name")
            ),
            adaptationRules = listOf(
                AdaptationRule("entityName != null", "add_crud_operations", 1),
                AdaptationRule("serviceName != null", "add_service_injection", 2)
            ),
            validationRules = listOf(
                ValidationRule("controller_annotation", "contains('@RestController')", "error", "Class must have @RestController annotation")
            ),
            framework = "java_spring_boot",
            language = "java"
        )

        // React Templates
        templateRegistry["react_component"] = AdaptiveTemplate(
            name = "React Component",
            baseTemplate = getReactComponentTemplate(),
            variables = mapOf(
                "componentName" to TemplateVariable("componentName", "string", null, "Component name"),
                "props" to TemplateVariable("props", "list", emptyList<String>(), "Component props"),
                "useState" to TemplateVariable("useState", "boolean", false, "Use useState hook"),
                "useEffect" to TemplateVariable("useEffect", "boolean", false, "Use useEffect hook"),
                "typescript" to TemplateVariable("typescript", "boolean", true, "Use TypeScript")
            ),
            adaptationRules = listOf(
                AdaptationRule("useState == true", "add_state_hook", 1),
                AdaptationRule("useEffect == true", "add_effect_hook", 2),
                AdaptationRule("typescript == true", "add_type_definitions", 3)
            ),
            validationRules = listOf(
                ValidationRule("export_default", "contains('export default')", "error", "Component must have default export")
            ),
            framework = "react",
            language = "typescript"
        )

        // Flask Templates
        templateRegistry["flask_route"] = AdaptiveTemplate(
            name = "Flask Route",
            baseTemplate = getFlaskRouteTemplate(),
            variables = mapOf(
                "routePath" to TemplateVariable("routePath", "string", null, "Route path"),
                "functionName" to TemplateVariable("functionName", "string", null, "Route function name"),
                "methods" to TemplateVariable("methods", "list", listOf("GET"), "HTTP methods"),
                "returnType" to TemplateVariable("returnType", "string", "json", "Return type (json, html, redirect)"),
                "hasValidation" to TemplateVariable("hasValidation", "boolean", false, "Include request validation")
            ),
            adaptationRules = listOf(
                AdaptationRule("hasValidation == true", "add_validation", 1),
                AdaptationRule("returnType == 'html'", "add_template_rendering", 2)
            ),
            validationRules = listOf(
                ValidationRule("route_decorator", "contains('@app.route')", "error", "Function must have @app.route decorator")
            ),
            framework = "flask",
            language = "python"
        )

        // Add more templates for other frameworks...
    }

    private fun selectBestTemplate(
        request: GenerationRequest,
        analysis: CodeStructureAnalyzer.ProjectAnalysis
    ): AdaptiveTemplate {
        val candidates = templateRegistry.values.filter { template ->
            // Filter by framework
            if (analysis.framework != null && template.framework != null) {
                template.framework == analysis.framework.name.lowercase().replace(" ", "_")
            } else {
                true
            }
        }.filter { template ->
            // Filter by generation type
            when (request.type) {
                GenerationType.CLASS_IMPLEMENTATION -> template.name.contains("class", ignoreCase = true) || 
                                                     template.name.contains("controller", ignoreCase = true) ||
                                                     template.name.contains("service", ignoreCase = true)
                GenerationType.COMPONENT_CREATION -> template.name.contains("component", ignoreCase = true) ||
                                                   template.name.contains("composable", ignoreCase = true)
                GenerationType.API_ENDPOINT -> template.name.contains("controller", ignoreCase = true) ||
                                             template.name.contains("route", ignoreCase = true)
                GenerationType.FILE_CREATION -> true // Any template can be used for file creation
                else -> true
            }
        }

        if (candidates.isEmpty()) {
            // Fallback to a generic template
            return createGenericTemplate(request, analysis)
        }

        // Score templates based on context match
        val scored = candidates.map { template ->
            val score = calculateTemplateScore(template, request, analysis)
            Pair(template, score)
        }.sortedByDescending { it.second }

        return scored.first().first
    }

    private fun generateVariableValues(
        request: GenerationRequest,
        analysis: CodeStructureAnalyzer.ProjectAnalysis,
        template: AdaptiveTemplate
    ): Map<String, Any> {
        val values = mutableMapOf<String, Any>()

        template.variables.forEach { (name, variable) ->
            val value = when (name) {
                "className", "componentName", "controllerName" -> {
                    extractNameFromIntent(request.context.userIntent) ?: 
                    generateNameFromFile(request.context.targetFile, variable.type)
                }
                "packageName" -> {
                    extractPackageName(request.context.targetFile, analysis)
                }
                "imports" -> {
                    generateRequiredImports(request, analysis, template)
                }
                "framework" -> {
                    analysis.framework?.name ?: "unknown"
                }
                "language" -> {
                    template.language
                }
                else -> {
                    // Try to extract from requirements or use default
                    extractValueFromRequirements(name, request.requirements) ?: variable.defaultValue
                }
            }

            if (value != null) {
                values[name] = value
            } else if (variable.isRequired) {
                // Generate a reasonable default
                values[name] = generateDefaultValue(name, variable, request)
            }
        }

        return values
    }

    private fun applyAdaptations(
        template: AdaptiveTemplate,
        request: GenerationRequest,
        analysis: CodeStructureAnalyzer.ProjectAnalysis
    ): AdaptiveTemplate {
        var adaptedTemplate = template.copy()

        // Apply adaptation rules based on context
        template.adaptationRules.sortedBy { it.priority }.forEach { rule ->
            if (evaluateCondition(rule.condition, request, analysis)) {
                adaptedTemplate = applyAdaptation(adaptedTemplate, rule.action, request, analysis)
            }
        }

        // Apply project-specific adaptations
        val projectPatterns = analysis.patterns
        projectPatterns.forEach { pattern ->
            adaptedTemplate = adaptProjectPattern(adaptedTemplate, pattern)
        }

        return adaptedTemplate
    }

    private fun generateContent(template: AdaptiveTemplate, variables: Map<String, Any>): String {
        var content = template.baseTemplate

        // Replace template variables
        variables.forEach { (name, value) ->
            val placeholder = "{{$name}}"
            val replacement = when (value) {
                is List<*> -> value.joinToString(", ")
                is Boolean -> value.toString()
                else -> value.toString()
            }
            content = content.replace(placeholder, replacement)
        }

        // Clean up any remaining placeholders
        content = content.replace(Regex("\\{\\{[^}]+\\}\\}"), "")

        return content
    }

    private fun fixGeneratedContent(
        content: String,
        validationResult: CodeQualityValidator.ValidationResult,
        request: GenerationRequest,
        analysis: CodeStructureAnalyzer.ProjectAnalysis
    ): String {
        var fixedContent = content

        // Fix syntax errors
        validationResult.issues.filter { it.severity == CodeQualityValidator.IssueSeverity.ERROR }
            .forEach { issue ->
                fixedContent = applySyntaxFix(fixedContent, issue)
            }

        // Resolve missing imports
        val referenceResult = ReferenceResolver.resolveFileReferences(
            request.context.targetFile,
            fixedContent,
            analysis
        )

        referenceResult.suggestions
            .filter { it.autoApplicable }
            .forEach { suggestion ->
                fixedContent = applyReferenceFix(fixedContent, suggestion)
            }

        return fixedContent
    }

    private fun getTemplatesForContext(framework: String?, language: String?): List<AdaptiveTemplate> {
        return templateRegistry.values.filter { template ->
            (framework == null || template.framework == framework) &&
            (language == null || template.language == language)
        }
    }

    // Template content generators
    private fun getKotlinActivityTemplate(): String = """
package {{packageName}}

import android.os.Bundle
import androidx.activity.ComponentActivity
{{#if useCompose}}
import androidx.activity.compose.setContent
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Surface
import androidx.compose.ui.Modifier
{{/if}}
{{#if hasViewModel}}
import androidx.lifecycle.viewmodel.compose.viewModel
{{/if}}
{{imports}}

class {{className}} : ComponentActivity() {
    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        {{#if useCompose}}
        setContent {
            MaterialTheme {
                Surface(
                    modifier = Modifier.fillMaxSize(),
                    color = MaterialTheme.colorScheme.background
                ) {
                    {{componentName}}()
                }
            }
        }
        {{else}}
        setContentView(R.layout.{{layoutName}})
        {{/if}}
    }
}
    """.trimIndent()

    private fun getKotlinComposableTemplate(): String = """
package {{packageName}}

import androidx.compose.foundation.layout.*
import androidx.compose.material3.*
import androidx.compose.runtime.*
import androidx.compose.ui.Modifier
import androidx.compose.ui.tooling.preview.Preview
{{imports}}

@Composable
fun {{componentName}}(
    {{parameters}}
    modifier: Modifier = Modifier
) {
    {{#if hasState}}
    var state by remember { mutableStateOf({{initialState}}) }
    {{/if}}
    
    // TODO: Implement component UI
    Text(
        text = "{{componentName}}",
        modifier = modifier
    )
}

{{#if preview}}
@Preview(showBackground = true)
@Composable
fun {{componentName}}Preview() {
    MaterialTheme {
        {{componentName}}()
    }
}
{{/if}}
    """.trimIndent()

    private fun getSpringControllerTemplate(): String = """
package {{packageName}};

import {{serviceName}};
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.http.ResponseEntity;
import org.springframework.web.bind.annotation.*;
{{imports}}

@RestController
@RequestMapping("{{basePath}}")
@CrossOrigin(origins = "*")
public class {{controllerName}} {

    @Autowired
    private {{serviceName}} {{serviceVariableName}};

    @GetMapping
    public ResponseEntity<?> getAll() {
        return ResponseEntity.ok({{serviceVariableName}}.findAll());
    }

    @GetMapping("/{id}")
    public ResponseEntity<?> getById(@PathVariable Long id) {
        return ResponseEntity.ok({{serviceVariableName}}.findById(id));
    }

    @PostMapping
    public ResponseEntity<?> create(@RequestBody {{entityName}} entity) {
        return ResponseEntity.ok({{serviceVariableName}}.save(entity));
    }

    @PutMapping("/{id}")
    public ResponseEntity<?> update(@PathVariable Long id, @RequestBody {{entityName}} entity) {
        return ResponseEntity.ok({{serviceVariableName}}.update(id, entity));
    }

    @DeleteMapping("/{id}")
    public ResponseEntity<?> delete(@PathVariable Long id) {
        {{serviceVariableName}}.delete(id);
        return ResponseEntity.ok().build();
    }
}
    """.trimIndent()

    private fun getReactComponentTemplate(): String = """
{{#if typescript}}
import React{{#if useState}}, { useState }{{/if}}{{#if useEffect}}, { useEffect }{{/if}} from 'react';
{{else}}
import React{{#if useState}}, { useState }{{/if}}{{#if useEffect}}, { useEffect }{{/if}} from 'react';
{{/if}}
{{imports}}

{{#if typescript}}
interface {{componentName}}Props {
  {{props}}
}

const {{componentName}}: React.FC<{{componentName}}Props> = ({ {{propNames}} }) => {
{{else}}
const {{componentName}} = ({ {{propNames}} }) => {
{{/if}}
  {{#if useState}}
  const [state, setState] = useState({{initialState}});
  {{/if}}

  {{#if useEffect}}
  useEffect(() => {
    // TODO: Implement effect logic
  }, []);
  {{/if}}

  return (
    <div>
      <h1>{{componentName}}</h1>
      {/* TODO: Implement component JSX */}
    </div>
  );
};

export default {{componentName}};
    """.trimIndent()

    private fun getFlaskRouteTemplate(): String = """
{{imports}}
from flask import request, jsonify{{#if returnType == 'html'}}, render_template{{/if}}

@app.route('{{routePath}}', methods={{methods}})
def {{functionName}}():
    {{#if hasValidation}}
    # Validate request data
    if not request.json:
        return jsonify({'error': 'Request must be JSON'}), 400
    {{/if}}
    
    try:
        # TODO: Implement route logic
        {{#if returnType == 'json'}}
        return jsonify({'message': 'Success'})
        {{else if returnType == 'html'}}
        return render_template('{{templateName}}.html')
        {{else}}
        return redirect(url_for('{{redirectTarget}}'))
        {{/if}}
    except Exception as e:
        return jsonify({'error': str(e)}), 500
    """.trimIndent()

    // Helper methods
    private fun createGenericTemplate(
        request: GenerationRequest,
        analysis: CodeStructureAnalyzer.ProjectAnalysis
    ): AdaptiveTemplate {
        val language = determineLanguageFromFile(request.context.targetFile)
        
        return AdaptiveTemplate(
            name = "Generic Template",
            baseTemplate = getGenericTemplate(language),
            variables = mapOf(
                "className" to TemplateVariable("className", "string", "NewClass", "Class name"),
                "packageName" to TemplateVariable("packageName", "string", "", "Package name"),
                "imports" to TemplateVariable("imports", "list", emptyList<String>(), "Import statements")
            ),
            adaptationRules = emptyList(),
            validationRules = emptyList(),
            framework = analysis.framework?.name?.lowercase()?.replace(" ", "_"),
            language = language
        )
    }

    private fun getGenericTemplate(language: String): String {
        return when (language) {
            "kotlin" -> """
package {{packageName}}

{{imports}}

class {{className}} {
    // TODO: Implement class
}
            """.trimIndent()
            "java" -> """
package {{packageName}};

{{imports}}

public class {{className}} {
    // TODO: Implement class
}
            """.trimIndent()
            "typescript" -> """
{{imports}}

export class {{className}} {
  // TODO: Implement class
}
            """.trimIndent()
            "python" -> """
{{imports}}

class {{className}}:
    \"\"\"{{className}} implementation\"\"\"
    
    def __init__(self):
        # TODO: Implement constructor
        pass
            """.trimIndent()
            else -> "// TODO: Implement"
        }
    }

    private fun calculateTemplateScore(
        template: AdaptiveTemplate,
        request: GenerationRequest,
        analysis: CodeStructureAnalyzer.ProjectAnalysis
    ): Double {
        var score = 0.0

        // Framework match
        if (analysis.framework != null && template.framework == analysis.framework.name.lowercase().replace(" ", "_")) {
            score += 50.0
        }

        // Language match
        val targetLanguage = determineLanguageFromFile(request.context.targetFile)
        if (template.language == targetLanguage) {
            score += 30.0
        }

        // Type match
        when (request.type) {
            GenerationType.COMPONENT_CREATION -> {
                if (template.name.contains("component", ignoreCase = true) || 
                    template.name.contains("composable", ignoreCase = true)) {
                    score += 20.0
                }
            }
            GenerationType.API_ENDPOINT -> {
                if (template.name.contains("controller", ignoreCase = true) || 
                    template.name.contains("route", ignoreCase = true)) {
                    score += 20.0
                }
            }
            else -> {
                // Default scoring
                score += 10.0
            }
        }

        return score
    }

    private fun extractNameFromIntent(intent: String): String? {
        // Simple name extraction - in production, would use NLP
        val words = intent.split(" ")
        val nameWords = words.filter { it.matches(Regex("[A-Z][a-zA-Z]+")) }
        return nameWords.firstOrNull()
    }

    private fun generateNameFromFile(filePath: String, type: String): String {
        val fileName = File(filePath).nameWithoutExtension
        return when (type) {
            "class" -> fileName.split("_", "-").joinToString("") { it.replaceFirstChar { char -> if (char.isLowerCase()) char.titlecase() else char.toString() } }
            else -> fileName
        }
    }

    private fun extractPackageName(filePath: String, analysis: CodeStructureAnalyzer.ProjectAnalysis): String {
        // Extract package name based on file path and project structure
        val relativePath = File(filePath).absolutePath.removePrefix(analysis.structure.rootPath)
        val pathParts = relativePath.split(File.separator).filter { it.isNotEmpty() }
        
        // Find package-like structure
        val srcIndex = pathParts.indexOfFirst { it in setOf("src", "main", "java", "kotlin") }
        if (srcIndex >= 0 && srcIndex < pathParts.size - 1) {
            return pathParts.subList(srcIndex + 1, pathParts.size - 1).joinToString(".")
        }
        
        return ""
    }

    private fun generateRequiredImports(
        request: GenerationRequest,
        analysis: CodeStructureAnalyzer.ProjectAnalysis,
        template: AdaptiveTemplate
    ): List<String> {
        val imports = mutableListOf<String>()
        
        // Add framework-specific imports
        analysis.framework?.let { framework ->
            when (framework.name) {
                "Kotlin Android" -> {
                    imports.add("import android.os.Bundle")
                    if (request.type == GenerationType.COMPONENT_CREATION) {
                        imports.add("import androidx.compose.runtime.*")
                        imports.add("import androidx.compose.ui.Modifier")
                    }
                }
                "Java Spring Boot" -> {
                    imports.add("import org.springframework.stereotype.Component")
                    if (request.type == GenerationType.API_ENDPOINT) {
                        imports.add("import org.springframework.web.bind.annotation.*")
                    }
                }
                "React" -> {
                    imports.add("import React from 'react';")
                    if (template.variables.containsKey("useState")) {
                        imports.add("import { useState } from 'react';")
                    }
                }
            }
        }
        
        return imports
    }

    private fun extractValueFromRequirements(name: String, requirements: List<String>): Any? {
        // Extract specific values from requirements
        requirements.forEach { requirement ->
            when {
                name == "methods" && requirement.contains("HTTP") -> {
                    return requirement.split(" ").filter { it.uppercase() in setOf("GET", "POST", "PUT", "DELETE") }
                }
                name == "hasState" && requirement.contains("state") -> {
                    return true
                }
                name == "useCompose" && requirement.contains("Compose") -> {
                    return true
                }
            }
        }
        return null
    }

    private fun generateDefaultValue(
        name: String,
        variable: TemplateVariable,
        request: GenerationRequest
    ): Any {
        return when (variable.type) {
            "string" -> when (name) {
                "className", "componentName" -> File(request.context.targetFile).nameWithoutExtension.replaceFirstChar { if (it.isLowerCase()) it.titlecase() else it.toString() }
                "functionName" -> "handleRequest"
                "routePath" -> "/api/endpoint"
                else -> variable.defaultValue ?: ""
            }
            "boolean" -> variable.defaultValue ?: false
            "list" -> variable.defaultValue ?: emptyList<String>()
            else -> variable.defaultValue ?: ""
        }
    }

    private fun evaluateCondition(
        condition: String,
        request: GenerationRequest,
        analysis: CodeStructureAnalyzer.ProjectAnalysis
    ): Boolean {
        // Simple condition evaluation - in production, would use expression parser
        return when {
            condition.contains("hasViewModel == true") -> {
                request.requirements.any { it.contains("ViewModel", ignoreCase = true) }
            }
            condition.contains("useCompose == true") -> {
                analysis.framework?.name == "Kotlin Android" || 
                request.requirements.any { it.contains("Compose", ignoreCase = true) }
            }
            condition.contains("typescript == true") -> {
                request.context.targetFile.endsWith(".ts") || request.context.targetFile.endsWith(".tsx")
            }
            else -> false
        }
    }

    private fun applyAdaptation(
        template: AdaptiveTemplate,
        action: String,
        request: GenerationRequest,
        analysis: CodeStructureAnalyzer.ProjectAnalysis
    ): AdaptiveTemplate {
        var adaptedTemplate = template

        when (action) {
            "add_viewmodel_integration" -> {
                adaptedTemplate = adaptedTemplate.copy(
                    baseTemplate = adaptedTemplate.baseTemplate.replace(
                        "{{imports}}",
                        "import androidx.lifecycle.viewmodel.compose.viewModel\n{{imports}}"
                    )
                )
            }
            "use_compose_ui" -> {
                adaptedTemplate = adaptedTemplate.copy(
                    baseTemplate = adaptedTemplate.baseTemplate.replace(
                        "setContentView(R.layout.{{layoutName}})",
                        "setContent { {{componentName}}() }"
                    )
                )
            }
            "add_state_management" -> {
                adaptedTemplate = adaptedTemplate.copy(
                    baseTemplate = adaptedTemplate.baseTemplate.replace(
                        "{{content}}",
                        "var state by remember { mutableStateOf(initialState) }\n    {{content}}"
                    )
                )
            }
        }

        return adaptedTemplate
    }

    private fun adaptProjectPattern(
        template: AdaptiveTemplate,
        pattern: CodeStructureAnalyzer.UsagePattern
    ): AdaptiveTemplate {
        // Adapt template based on detected project patterns
        return when (pattern.name) {
            "Jetpack Compose Component" -> {
                template.copy(
                    baseTemplate = template.baseTemplate.replace(
                        "{{imports}}",
                        "import androidx.compose.runtime.*\nimport androidx.compose.ui.Modifier\n{{imports}}"
                    )
                )
            }
            "React Functional Component" -> {
                template.copy(
                    baseTemplate = template.baseTemplate.replace(
                        "{{imports}}",
                        "import React from 'react';\n{{imports}}"
                    )
                )
            }
            else -> template
        }
    }

    private fun applySyntaxFix(content: String, issue: CodeQualityValidator.ValidationIssue): String {
        return when (issue.type) {
            CodeQualityValidator.IssueType.SYNTAX_ERROR -> {
                when {
                    issue.message.contains("Missing semicolon") -> {
                        val lines = content.lines().toMutableList()
                        if (issue.lineNumber <= lines.size) {
                            lines[issue.lineNumber - 1] = lines[issue.lineNumber - 1] + ";"
                            lines.joinToString("\n")
                        } else content
                    }
                    issue.message.contains("Missing colon") -> {
                        val lines = content.lines().toMutableList()
                        if (issue.lineNumber <= lines.size) {
                            lines[issue.lineNumber - 1] = lines[issue.lineNumber - 1] + ":"
                            lines.joinToString("\n")
                        } else content
                    }
                    else -> content
                }
            }
            else -> content
        }
    }

    private fun applyReferenceFix(content: String, suggestion: ReferenceResolver.ResolutionSuggestion): String {
        return when (suggestion.type) {
            ReferenceResolver.SuggestionType.ADD_IMPORT -> {
                val importStatement = generateImportStatement(suggestion.description)
                importStatement + "\n" + content
            }
            else -> content
        }
    }

    private fun extractAppliedPatterns(template: AdaptiveTemplate): List<String> {
        // Extract patterns that were applied to the template
        return template.adaptationRules.map { it.action }
    }

    private fun extractAdaptations(adapted: AdaptiveTemplate, original: AdaptiveTemplate): List<String> {
        val adaptations = mutableListOf<String>()
        
        if (adapted.baseTemplate != original.baseTemplate) {
            adaptations.add("Template content adapted")
        }
        
        return adaptations
    }

    private fun calculateGenerationConfidence(
        validation: CodeQualityValidator.ValidationResult,
        template: AdaptiveTemplate
    ): Double {
        var confidence = 0.8 // Base confidence
        
        // Adjust based on validation score
        confidence *= validation.score
        
        // Adjust based on template specificity
        if (template.name != "Generic Template") {
            confidence += 0.1
        }
        
        return minOf(1.0, confidence)
    }

    private fun extractDependencies(content: String): List<String> {
        // Extract any new dependencies from generated content
        return emptyList() // Would implement dependency extraction
    }

    private fun extractImports(content: String): List<String> {
        val imports = mutableListOf<String>()
        val lines = content.lines()
        
        lines.forEach { line ->
            when {
                line.trim().startsWith("import ") -> imports.add(line.trim())
                line.trim().startsWith("from ") && line.contains("import") -> imports.add(line.trim())
            }
        }
        
        return imports
    }

    private fun extractExports(content: String): List<String> {
        val exports = mutableListOf<String>()
        val lines = content.lines()
        
        lines.forEach { line ->
            when {
                line.contains("export class") -> {
                    val className = Regex("export class\\s+(\\w+)").find(line)?.groupValues?.get(1)
                    className?.let { exports.add(it) }
                }
                line.contains("export function") -> {
                    val functionName = Regex("export function\\s+(\\w+)").find(line)?.groupValues?.get(1)
                    functionName?.let { exports.add(it) }
                }
                line.contains("export default") -> {
                    exports.add("default")
                }
            }
        }
        
        return exports
    }

    private fun findRelatedFiles(targetFile: String, analysis: CodeStructureAnalyzer.ProjectAnalysis): List<String> {
        val relatedFiles = mutableListOf<String>()
        val targetDir = File(targetFile).parent
        
        // Find files in the same directory
        analysis.structure.files.values
            .filter { File(it.path).parent == targetDir }
            .forEach { relatedFiles.add(it.path) }
        
        return relatedFiles.take(5) // Limit to 5 related files
    }

    private fun extractRequirements(
        userIntent: String,
        type: GenerationType,
        analysis: CodeStructureAnalyzer.ProjectAnalysis
    ): List<String> {
        val requirements = mutableListOf<String>()
        
        // Extract from user intent
                 val words = userIntent.lowercase().split(" ")
        
        when (type) {
            GenerationType.COMPONENT_CREATION -> {
                if (words.any { it in setOf("button", "input", "form") }) {
                    requirements.add("Interactive UI component")
                }
                if (words.any { it in setOf("state", "stateful") }) {
                    requirements.add("Component with state")
                }
            }
            GenerationType.API_ENDPOINT -> {
                if (words.any { it in setOf("get", "fetch") }) {
                    requirements.add("GET endpoint")
                }
                if (words.any { it in setOf("post", "create") }) {
                    requirements.add("POST endpoint")
                }
            }
            else -> {
                // Extract general requirements
                requirements.add("Standard implementation")
            }
        }
        
        return requirements
    }

    private fun generateConstraints(analysis: CodeStructureAnalyzer.ProjectAnalysis): List<String> {
        val constraints = mutableListOf<String>()
        
        // Add framework constraints
        analysis.framework?.let { framework ->
            constraints.add("Follow ${framework.name} conventions")
            constraints.addAll(framework.conventions.directoryStructure.map { "Use $it directory structure" })
        }
        
        // Add project-specific constraints
        if (analysis.qualityMetrics.conventionCompliance > 0.8) {
            constraints.add("Maintain existing code style")
        }
        
        return constraints
    }

    private fun extractPreferences(analysis: CodeStructureAnalyzer.ProjectAnalysis): Map<String, Any> {
        val preferences = mutableMapOf<String, Any>()
        
        // Extract from detected conventions
        preferences["fileNaming"] = analysis.conventions.fileNaming
        preferences["functionNaming"] = analysis.conventions.functionNaming
        preferences["classNaming"] = analysis.conventions.classNaming
        
        // Extract from framework
        analysis.framework?.let { framework ->
            preferences["language"] = framework.language
            framework.conventions.testingFramework?.let { 
                preferences["testingFramework"] = it
            }
        }
        
        return preferences
    }

    private fun generateImprovementSuggestions(
        validation: CodeQualityValidator.ValidationResult,
        request: GenerationRequest
    ): List<String> {
        val suggestions = mutableListOf<String>()
        
        suggestions.addAll(validation.suggestions)
        
        if (validation.score < 0.8) {
            suggestions.add("Consider refactoring for better code quality")
        }
        
        if (validation.functionalityRisk != CodeQualityValidator.FunctionalityRisk.NONE) {
            suggestions.add("Review generated code for potential functionality issues")
        }
        
        return suggestions
    }

    private fun determineLanguageFromFile(filePath: String): String {
        return when (File(filePath).extension.lowercase()) {
            "kt" -> "kotlin"
            "java" -> "java"
            "js" -> "javascript"
            "ts", "tsx" -> "typescript"
            "py" -> "python"
            else -> "unknown"
        }
    }

    private fun generateImportStatement(description: String): String {
        // Generate appropriate import statement based on description
        return "// TODO: Add import statement for: $description"
    }
}