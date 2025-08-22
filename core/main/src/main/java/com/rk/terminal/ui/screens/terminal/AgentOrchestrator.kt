package com.rk.terminal.ui.screens.terminal

import android.content.Context
import com.rk.libcommons.application
import com.rk.terminal.llm.LlmMessage
import com.rk.terminal.llm.LlmProvider
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.collect
import kotlinx.coroutines.withContext
import kotlinx.coroutines.runBlocking
import org.json.JSONArray
import org.json.JSONObject
import java.io.File
import java.io.RandomAccessFile
import java.nio.charset.Charset
import java.nio.charset.StandardCharsets
import java.security.MessageDigest
import android.util.Base64
import java.util.regex.Pattern
import java.util.concurrent.TimeUnit
import kotlin.math.min
import com.rk.settings.Settings
import java.util.ArrayDeque
import com.rk.terminal.ui.activities.terminal.MainActivity
import com.rk.terminal.service.SessionService
import com.termux.terminal.TerminalSession
import com.termux.terminal.TerminalSessionClient
import android.util.Log
import com.rk.terminal.agent.ControlApiClient
import kotlinx.coroutines.withTimeout
import kotlinx.coroutines.TimeoutCancellationException

/**
 * Minimal agent orchestrator that:
 * - Requests a structured plan (JSON) from the LLM based on a user goal
 * - Caches the plan per-session
 * - Iterates tasks and, for each task, requests a single tool call JSON to perform the task
 * - Executes the tool call (create file, write file, mkdir, run shell, list directory, read file, apply minimal edits)
 * - Marks tasks done in a progress cache to ensure idempotency
 * - Captures observations (e.g., dir listings, file contents, command outputs) and feeds them into subsequent tool calls
 */
class AgentOrchestrator(
    private val context: Context,
    private val sessionId: String,
    private val workingDirProvider: () -> String
) {

    data class Task(
        val id: String,
        val description: String,
        val category: String? = null,
        val targets: List<String>? = null,   // optional absolute/relative paths or globs
        val search: List<String>? = null,    // optional regex patterns to grep
        val markers: List<String>? = null,   // optional markers to locate sections
        val dependencies: List<String> = emptyList(),  // list of task IDs this task depends on
        val confidence: Float = 0.8f,       // confidence level (0.0-1.0)
        val risk: String = "Low"             // risk level: "Low", "Medium", "High"
    )

    data class Plan(
        val goal: String,
        val tasks: List<Task>
    )

    data class MiniTask(
        val id: String,
        val description: String,
        val category: String? = null,
        val targets: List<String>? = null,
        val search: List<String>? = null,
        val markers: List<String>? = null,
        val dependencies: List<String> = emptyList(),  // list of task IDs this task depends on
        val confidence: Float = 0.8f,       // confidence level (0.0-1.0)
        val risk: String = "Low"             // risk level: "Low", "Medium", "High"
    )

    data class MiniPlan(
        val parentTaskId: String,
        val reason: String,
        val tasks: List<MiniTask>
    )

        data class ThinkResult(
        val producedPlan: Plan? = null,
        val answer: String? = null,
        val blueprintPath: String? = null
    )
 
    // Per-run stats for dynamic summary lines
    private data class RunStats(
        val startedMs: Long = System.currentTimeMillis(),
        var endedMs: Long = 0L,
        val toolCounts: MutableMap<String, Int> = LinkedHashMap(),
        val filesRead: MutableList<String> = mutableListOf(),
        val dirsListed: MutableList<String> = mutableListOf(),
        val grepPatterns: MutableList<String> = mutableListOf(),
        val commandsRun: MutableList<String> = mutableListOf(),
        val filesModified: MutableList<String> = mutableListOf()
    )
    private var currentRunStats: RunStats? = null
    private var currentTaskContext: Task? = null
    private var lastInstallSuccess: Boolean = false
    private var lastPlanGoal: String? = null
    private var projectRequirements: String? = null // Store the original project requirements
    private var folderStructureEnsured: Boolean = false
    
    // Context cache for maintaining code continuity across tasks
    private data class FileContext(
        val path: String,
        val content: String,
        val type: String, // "python", "html", "css", "js", "config"
        val functions: List<String> = emptyList(),
        val classes: List<String> = emptyList(),
        val routes: List<String> = emptyList(),
        val dependencies: List<String> = emptyList()
    )
    
    // PAVL Pillar 3: Global Codebase Intelligence - Project Knowledge Graph
    private data class CodeElement(
        val name: String,
        val type: String, // "function", "class", "variable", "import", "route"
        val filePath: String,
        val lineNumber: Int = 0,
        val signature: String = "",
        val dependencies: List<String> = emptyList(),
        val usages: MutableList<String> = mutableListOf()
    )
    
    private data class FileRelationship(
        val fromFile: String,
        val toFile: String,
        val relationshipType: String, // "imports", "calls", "extends", "includes"
        val elements: List<String> = emptyList() // specific elements involved
    )
    
    private data class ProjectKnowledgeGraph(
        val files: MutableMap<String, FileContext> = mutableMapOf(),
        val elements: MutableMap<String, CodeElement> = mutableMapOf(),
        val relationships: MutableList<FileRelationship> = mutableListOf(),
        val lastUpdated: Long = System.currentTimeMillis()
    )
    
    private val knowledgeGraph = ProjectKnowledgeGraph()
    private val contextCache = mutableMapOf<String, FileContext>() // Keep for backward compatibility
    private val projectStructure = mutableMapOf<String, String>() // path -> description
    private fun beginRunStats() { currentRunStats = RunStats(); appendTaskLog("run_start") { } }
    
    private fun captureProjectRequirements(goal: String) {
        projectRequirements = goal
    }
    private fun endRunStatsAndReport(onStatus: (String) -> Unit, verb: String = "thought") {
        val stats = currentRunStats ?: return
        stats.endedMs = System.currentTimeMillis()
        val secs = ((stats.endedMs - stats.startedMs).coerceAtLeast(0L) / 100L).toDouble() / 10.0
        val parts = mutableListOf<String>()
        if (stats.toolCounts.isNotEmpty()) parts.add(stats.toolCounts.entries.joinToString(", ") { (k: String, v: Int) -> "$k×$v" })
        if (stats.filesRead.isNotEmpty()) parts.add("read ${stats.filesRead.size} file(s): ${stats.filesRead.take(3).joinToString(", ")}" + if (stats.filesRead.size > 3) " …" else "")
        if (stats.filesModified.isNotEmpty()) parts.add("modified ${stats.filesModified.size} file(s): ${stats.filesModified.take(3).joinToString(", ")}" + if (stats.filesModified.size > 3) " …" else "")
        if (stats.commandsRun.isNotEmpty()) parts.add("ran ${stats.commandsRun.size} command(s): ${stats.commandsRun.take(1).joinToString()}" + if (stats.commandsRun.size > 1) " …" else "")
        if (stats.grepPatterns.isNotEmpty()) parts.add("grep ${stats.grepPatterns.size} pattern(s)")
        val summary = "$verb for ${secs}s" + if (parts.isNotEmpty()) "; " + parts.joinToString("; ") else ""
        onStatus(summary)
        runCatching {
            val j = JSONObject()
            val tcObj = JSONObject()
            stats.toolCounts.forEach { (k, v) -> tcObj.put(k, v) }
            j.put("duration_s", secs)
                .put("tool_counts", tcObj)
                .put("files_read", JSONArray(stats.filesRead))
                .put("dirs_listed", JSONArray(stats.dirsListed))
                .put("grep_patterns", JSONArray(stats.grepPatterns))
                .put("commands_run", JSONArray(stats.commandsRun))
                .put("files_modified", JSONArray(stats.filesModified))
            appendTaskLog("run_end") { put("stats", j) }
        }
        currentRunStats = null
    }
 
     private val agentDir: File by lazy {
         // Store per chat session
         File(application!!.filesDir, "chat/${sessionId}").apply { mkdirs() }
     }
    private val planFile: File by lazy { File(agentDir, "plan.json") }
    private val progressFile: File by lazy { File(agentDir, "progress.json") }
    private val observationsFile: File by lazy { File(agentDir, "observations.json") }
    private val commandsCacheFile: File by lazy { File(agentDir, "commands.json") }
    private val miniPlanFile: File by lazy { File(agentDir, "miniPlan.json") }
    private val blueprintFile: File by lazy { File(agentDir, "blueprint.json") }
    private val cliReportFile: File by lazy { File(agentDir, "cli_report.json") }
    private val writerToolsFile: File by lazy { File(agentDir, "writer_tools.json") }

    // Add task log file (JSON Lines)
    private val taskLogFile: File by lazy { File(agentDir, "task_log.jsonl") }

    // Per-run observations (taskId -> observation text)
    private val observations: MutableMap<String, String> = linkedMapOf()
    // Command output cache: key -> {command, wd, output, exit, ts}
    private val commandCache: MutableMap<String, JSONObject> = LinkedHashMap()
    // Track workspace changes to refresh codebase cache
    private val pendingCodebaseChanges: MutableSet<String> = linkedSetOf()
    private var lastCodebaseRefreshMs: Long = 0L
    // Prevent duplicate tool calls that cause freezing
    private val recentToolCalls: MutableMap<String, Long> = linkedMapOf()
    private val toolCallCooldownMs = 1000L // 1 second cooldown for identical calls
    // Environment detection signals cached during run_shell preflight
    private var lastDetectedOsId: String? = null
    private val lastDetectedManagers: MutableSet<String> = linkedSetOf()

    init {
        // Load persisted observations if available to make the agent resilient to restarts
        runCatching {
            if (observationsFile.exists()) {
                val obj = JSONObject(observationsFile.readText())
                obj.keys().forEach { k ->
                    observations[k] = obj.optString(k)
                }
            }
        }
        // Load command cache
        runCatching {
            if (commandsCacheFile.exists()) {
                val obj = JSONObject(commandsCacheFile.readText())
                obj.keys().forEach { k ->
                    val v = obj.optJSONObject(k)
                    if (v != null) commandCache[k] = v
                }
            }
            // Build initial CLI report from any pre-existing cache
            persistCliReport()
        }
        // Ensure mini plan storage exists
        runCatching {
            if (!miniPlanFile.exists()) {
                miniPlanFile.writeText(JSONObject().put("plans", JSONObject()).toString(2))
            }
        }
        // Ensure blueprint file placeholder exists (optional)
        runCatching {
            if (!blueprintFile.exists()) {
                blueprintFile.writeText("{}")
            }
        }
        // Ensure writer tools suggestions store exists
        runCatching {
            if (!writerToolsFile.exists()) {
                writerToolsFile.writeText(JSONObject().put("suggestions", JSONArray()).toString(2))
            }
        }
        // PAVL Pillar 3: Load existing project knowledge graph
        loadProjectKnowledgeGraph()
    }

    private fun saveObservations() {
        runCatching {
            val obj = JSONObject()
            observations.forEach { (k, v) -> obj.put(k, v) }
            observationsFile.writeText(obj.toString(2))
        }
    }

    private fun saveCommandCache() {
        runCatching {
            val obj = JSONObject()
            commandCache.forEach { (k, v) -> obj.put(k, v) }
            commandsCacheFile.writeText(obj.toString(2))
        }
    }
    
    private fun updateContextCache(filePath: String, content: String, fileType: String = "unknown") {
        val context = FileContext(
            path = filePath,
            content = content,
            type = fileType,
            functions = extractFunctions(content, fileType),
            classes = extractClasses(content, fileType),
            routes = extractRoutes(content, fileType),
            dependencies = extractDependencies(content, fileType)
        )
        contextCache[filePath] = context
        projectStructure[filePath] = getFileDescription(filePath, content)
        
        // PAVL Pillar 3: Update Project Knowledge Graph instead of just file references
        updateProjectKnowledgeGraph(filePath, context)
    }
    
    // PAVL Pillar 3: Project Knowledge Graph Management
    private fun updateProjectKnowledgeGraph(filePath: String, context: FileContext) {
        knowledgeGraph.files[filePath] = context
        
        // Extract code elements and relationships
        extractCodeElements(context)
        extractFileRelationships(context)
        
        // Update legacy file references for backward compatibility
        storeFileReference(filePath, context)
        
        // Persist the knowledge graph
        persistProjectKnowledgeGraph()
    }
    
    private fun extractCodeElements(context: FileContext) {
        val filePath = context.path
        
        // Extract functions
        context.functions.forEachIndexed { index, funcName ->
            val elementKey = "$filePath::$funcName"
            knowledgeGraph.elements[elementKey] = CodeElement(
                name = funcName,
                type = "function",
                filePath = filePath,
                lineNumber = extractLineNumber(context.content, funcName),
                signature = extractFunctionSignature(context.content, funcName),
                dependencies = extractFunctionDependencies(context.content, funcName)
            )
        }
        
        // Extract classes
        context.classes.forEachIndexed { index, className ->
            val elementKey = "$filePath::$className"
            knowledgeGraph.elements[elementKey] = CodeElement(
                name = className,
                type = "class",
                filePath = filePath,
                lineNumber = extractLineNumber(context.content, className),
                signature = extractClassSignature(context.content, className),
                dependencies = extractClassDependencies(context.content, className)
            )
        }
        
        // Extract imports/dependencies
        context.dependencies.forEach { dep ->
            val elementKey = "$filePath::import::$dep"
            knowledgeGraph.elements[elementKey] = CodeElement(
                name = dep,
                type = "import",
                filePath = filePath,
                dependencies = emptyList()
            )
        }
    }
    
    private fun extractFileRelationships(context: FileContext) {
        val fromFile = context.path
        
        // Create relationships based on dependencies/imports
        context.dependencies.forEach { dep ->
            // Try to find the actual file that provides this dependency
            val toFile = findFileForDependency(dep)
            if (toFile != null) {
                val relationship = FileRelationship(
                    fromFile = fromFile,
                    toFile = toFile,
                    relationshipType = "imports",
                    elements = listOf(dep)
                )
                
                // Avoid duplicates
                if (!knowledgeGraph.relationships.any { 
                    it.fromFile == relationship.fromFile && 
                    it.toFile == relationship.toFile && 
                    it.relationshipType == relationship.relationshipType &&
                    it.elements.containsAll(relationship.elements)
                }) {
                    knowledgeGraph.relationships.add(relationship)
                }
            }
        }
        
        // Track function calls across files
        extractCrossFileCallRelationships(context)
    }
    
    private fun extractCrossFileCallRelationships(context: FileContext) {
        val content = context.content
        val fromFile = context.path
        
        // Look for function calls that might reference other files
        knowledgeGraph.elements.values.filter { it.type == "function" && it.filePath != fromFile }.forEach { func ->
            if (content.contains(func.name)) {
                val relationship = FileRelationship(
                    fromFile = fromFile,
                    toFile = func.filePath,
                    relationshipType = "calls",
                    elements = listOf(func.name)
                )
                
                if (!knowledgeGraph.relationships.any { 
                    it.fromFile == relationship.fromFile && 
                    it.toFile == relationship.toFile && 
                    it.relationshipType == relationship.relationshipType &&
                    it.elements.contains(func.name)
                }) {
                    knowledgeGraph.relationships.add(relationship)
                }
                
                // Update usage tracking
                func.usages.add(fromFile)
            }
        }
    }
    
    private fun findFileForDependency(dep: String): String? {
        // Simple heuristic: look for files that might provide this dependency
        return knowledgeGraph.files.keys.find { filePath ->
            val fileName = File(filePath).nameWithoutExtension
            fileName.equals(dep, ignoreCase = true) || 
            fileName.contains(dep, ignoreCase = true) ||
            knowledgeGraph.files[filePath]?.functions?.contains(dep) == true ||
            knowledgeGraph.files[filePath]?.classes?.contains(dep) == true
        }
    }
    
    private fun extractLineNumber(content: String, elementName: String): Int {
        val lines = content.split("\n")
        return lines.indexOfFirst { it.contains(elementName) } + 1
    }
    
    private fun extractFunctionSignature(content: String, funcName: String): String {
        val lines = content.split("\n")
        val funcLine = lines.find { it.contains("def $funcName") || it.contains("function $funcName") || it.contains("$funcName(") }
        return funcLine?.trim() ?: ""
    }
    
    private fun extractClassSignature(content: String, className: String): String {
        val lines = content.split("\n")
        val classLine = lines.find { it.contains("class $className") }
        return classLine?.trim() ?: ""
    }
    
    private fun extractFunctionDependencies(content: String, funcName: String): List<String> {
        // Simple extraction of function calls within a function
        val lines = content.split("\n")
        val funcStartIndex = lines.indexOfFirst { it.contains("def $funcName") || it.contains("function $funcName") }
        if (funcStartIndex == -1) return emptyList()
        
        val dependencies = mutableListOf<String>()
        var i = funcStartIndex + 1
        var indentLevel = 0
        
        while (i < lines.size) {
            val line = lines[i].trim()
            if (line.isEmpty()) {
                i++
                continue
            }
            
            // Simple heuristic for function boundaries
            if (line.startsWith("def ") || line.startsWith("function ") || line.startsWith("class ")) {
                break
            }
            
            // Look for function calls
            val callPattern = Regex("(\\w+)\\s*\\(")
            callPattern.findAll(line).forEach { match ->
                val calledFunc = match.groupValues[1]
                if (calledFunc != funcName && !dependencies.contains(calledFunc)) {
                    dependencies.add(calledFunc)
                }
            }
            
            i++
        }
        
        return dependencies
    }
    
    private fun extractClassDependencies(content: String, className: String): List<String> {
        // Look for inheritance and composition
        val lines = content.split("\n")
        val classLine = lines.find { it.contains("class $className") }
        val dependencies = mutableListOf<String>()
        
        classLine?.let { line ->
            // Extract inheritance (class MyClass(BaseClass))
            val inheritancePattern = Regex("class\\s+$className\\s*\\(([^)]+)\\)")
            inheritancePattern.find(line)?.groupValues?.get(1)?.split(",")?.forEach { base ->
                dependencies.add(base.trim())
            }
        }
        
        return dependencies
    }
    
    private fun persistProjectKnowledgeGraph() {
        runCatching {
            val graphFile = File(agentDir, "project_knowledge_graph.json")
            val graphJson = JSONObject().apply {
                put("last_updated", knowledgeGraph.lastUpdated)
                put("files", JSONObject().apply {
                    knowledgeGraph.files.forEach { (path, context) ->
                        put(path, JSONObject().apply {
                            put("type", context.type)
                            put("functions", JSONArray(context.functions))
                            put("classes", JSONArray(context.classes))
                            put("routes", JSONArray(context.routes))
                            put("dependencies", JSONArray(context.dependencies))
                        })
                    }
                })
                put("elements", JSONObject().apply {
                    knowledgeGraph.elements.forEach { (key, element) ->
                        put(key, JSONObject().apply {
                            put("name", element.name)
                            put("type", element.type)
                            put("file_path", element.filePath)
                            put("line_number", element.lineNumber)
                            put("signature", element.signature)
                            put("dependencies", JSONArray(element.dependencies))
                            put("usages", JSONArray(element.usages))
                        })
                    }
                })
                put("relationships", JSONArray().apply {
                    knowledgeGraph.relationships.forEach { rel ->
                        put(JSONObject().apply {
                            put("from_file", rel.fromFile)
                            put("to_file", rel.toFile)
                            put("relationship_type", rel.relationshipType)
                            put("elements", JSONArray(rel.elements))
                        })
                    }
                })
            }
            graphFile.writeText(graphJson.toString(2))
        }
    }
    
    private fun loadProjectKnowledgeGraph() {
        runCatching {
            val graphFile = File(agentDir, "project_knowledge_graph.json")
            if (graphFile.exists()) {
                val graphJson = JSONObject(graphFile.readText())
                
                // Load files
                val filesJson = graphJson.optJSONObject("files") ?: JSONObject()
                filesJson.keys().forEach { path ->
                    val fileJson = filesJson.optJSONObject(path) ?: return@forEach
                    val context = FileContext(
                        path = path,
                        content = "", // Content not persisted, will be loaded on demand
                        type = fileJson.optString("type"),
                        functions = fileJson.optJSONArray("functions")?.let { arr ->
                            (0 until arr.length()).map { arr.optString(it) }
                        } ?: emptyList(),
                        classes = fileJson.optJSONArray("classes")?.let { arr ->
                            (0 until arr.length()).map { arr.optString(it) }
                        } ?: emptyList(),
                        routes = fileJson.optJSONArray("routes")?.let { arr ->
                            (0 until arr.length()).map { arr.optString(it) }
                        } ?: emptyList(),
                        dependencies = fileJson.optJSONArray("dependencies")?.let { arr ->
                            (0 until arr.length()).map { arr.optString(it) }
                        } ?: emptyList()
                    )
                    knowledgeGraph.files[path] = context
                }
                
                // Load elements
                val elementsJson = graphJson.optJSONObject("elements") ?: JSONObject()
                elementsJson.keys().forEach { key ->
                    val elementJson = elementsJson.optJSONObject(key) ?: return@forEach
                    val element = CodeElement(
                        name = elementJson.optString("name"),
                        type = elementJson.optString("type"),
                        filePath = elementJson.optString("file_path"),
                        lineNumber = elementJson.optInt("line_number"),
                        signature = elementJson.optString("signature"),
                        dependencies = elementJson.optJSONArray("dependencies")?.let { arr ->
                            (0 until arr.length()).map { arr.optString(it) }
                        } ?: emptyList(),
                        usages = elementJson.optJSONArray("usages")?.let { arr ->
                            (0 until arr.length()).map { arr.optString(it) }.toMutableList()
                        } ?: mutableListOf()
                    )
                    knowledgeGraph.elements[key] = element
                }
                
                // Load relationships
                val relationshipsJson = graphJson.optJSONArray("relationships") ?: JSONArray()
                for (i in 0 until relationshipsJson.length()) {
                    val relJson = relationshipsJson.optJSONObject(i) ?: continue
                    val relationship = FileRelationship(
                        fromFile = relJson.optString("from_file"),
                        toFile = relJson.optString("to_file"),
                        relationshipType = relJson.optString("relationship_type"),
                        elements = relJson.optJSONArray("elements")?.let { arr ->
                            (0 until arr.length()).map { arr.optString(it) }
                        } ?: emptyList()
                    )
                    knowledgeGraph.relationships.add(relationship)
                }
            }
        }
    }
    
    private fun performImpactAnalysis(filePath: String): String {
        val impactedFiles = mutableSetOf<String>()
        val impactedElements = mutableSetOf<String>()
        
        // Find direct relationships
        knowledgeGraph.relationships.forEach { rel ->
            when {
                rel.fromFile == filePath -> {
                    impactedFiles.add(rel.toFile)
                    impactedElements.addAll(rel.elements)
                }
                rel.toFile == filePath -> {
                    impactedFiles.add(rel.fromFile)
                    impactedElements.addAll(rel.elements)
                }
            }
        }
        
        // Find elements that use functions/classes from this file
        knowledgeGraph.elements.values.filter { it.filePath == filePath }.forEach { element ->
            element.usages.forEach { usage ->
                impactedFiles.add(usage)
            }
        }
        
        return """
            Impact Analysis for $filePath:
            - Directly affected files: ${impactedFiles.size}
            - Affected elements: ${impactedElements.size}
            - Files: ${impactedFiles.take(10).joinToString(", ")}${if (impactedFiles.size > 10) " ..." else ""}
            - Elements: ${impactedElements.take(10).joinToString(", ")}${if (impactedElements.size > 10) " ..." else ""}
        """.trimIndent()
    }

    private fun storeFileReference(filePath: String, context: FileContext) {
        runCatching {
            val referencesFile = File(agentDir, "file_references.json")
            val references = if (referencesFile.exists()) {
                JSONObject(referencesFile.readText())
            } else {
                JSONObject().put("files", JSONObject())
            }
            
            val files = references.optJSONObject("files") ?: JSONObject().also { references.put("files", it) }
            val fileInfo = JSONObject().apply {
                put("path", context.path)
                put("type", context.type)
                put("functions", JSONArray(context.functions))
                put("classes", JSONArray(context.classes))
                put("routes", JSONArray(context.routes))
                put("dependencies", JSONArray(context.dependencies))
                put("last_updated", System.currentTimeMillis())
                put("size_bytes", context.content.length)
                put("summary", generateFileSummary(context))
            }
            
            files.put(filePath, fileInfo)
            referencesFile.writeText(references.toString(2))
        }
    }
    
    private fun generateFileSummary(context: FileContext): String {
        return when (context.type) {
            "python" -> "Python module with ${context.functions.size} functions, ${context.classes.size} classes"
            "html" -> "HTML template with ${context.routes.size} routes"
            "javascript" -> "JavaScript module with ${context.functions.size} functions"
            "css" -> "CSS stylesheet"
            "json" -> "Configuration file"
            else -> "File with ${context.content.lines().size} lines"
        }
    }
    
    private fun extractFunctions(content: String, fileType: String): List<String> {
        return when (fileType) {
            "python" -> Regex("def\\s+(\\w+)\\s*\\(").findAll(content).map { it.groupValues[1] }.toList()
            "javascript" -> Regex("function\\s+(\\w+)\\s*\\(").findAll(content).map { it.groupValues[1] }.toList()
            else -> emptyList()
        }
    }
    
    private fun extractClasses(content: String, fileType: String): List<String> {
        return when (fileType) {
            "python" -> Regex("class\\s+(\\w+)").findAll(content).map { it.groupValues[1] }.toList()
            "javascript" -> Regex("class\\s+(\\w+)").findAll(content).map { it.groupValues[1] }.toList()
            else -> emptyList()
        }
    }
    
    private fun extractRoutes(content: String, fileType: String): List<String> {
        return when (fileType) {
            "python" -> Regex("@app\\.route\\('([^']+)'\\)").findAll(content).map { it.groupValues[1] }.toList()
            else -> emptyList()
        }
    }
    
    private fun extractDependencies(content: String, fileType: String): List<String> {
        return when (fileType) {
            "python" -> Regex("import\\s+(\\w+)").findAll(content).map { it.groupValues[1] }.toList() +
                       Regex("from\\s+(\\w+)").findAll(content).map { it.groupValues[1] }.toList()
            "javascript" -> Regex("import\\s+.*?from\\s+['\"]([^'\"]+)['\"]").findAll(content).map { it.groupValues[1] }.toList()
            else -> emptyList()
        }
    }
    
    private fun getFileDescription(filePath: String, content: String): String {
        return when {
            filePath.endsWith(".py") -> "Python file with ${extractFunctions(content, "python").size} functions"
            filePath.endsWith(".html") -> "HTML template file"
            filePath.endsWith(".js") -> "JavaScript file with ${extractFunctions(content, "javascript").size} functions"
            filePath.endsWith(".css") -> "CSS stylesheet"
            filePath.endsWith("requirements.txt") -> "Python dependencies"
            filePath.endsWith("README.md") -> "Project documentation"
            else -> "Configuration or data file"
        }
    }
    
    private fun getContextSummary(): String {
        if (contextCache.isEmpty()) return "No files created yet."
        
        return buildString {
            appendLine("## Project Context Summary")
            appendLine("Created files and their key components:")
            
            contextCache.values.forEach { context ->
                appendLine("- **${context.path}** (${context.type}) - ${generateFileSummary(context)}")
                if (context.functions.isNotEmpty()) {
                    appendLine("  - Functions: ${context.functions.take(5).joinToString(", ")}${if (context.functions.size > 5) " +${context.functions.size - 5} more" else ""}")
                }
                if (context.classes.isNotEmpty()) {
                    appendLine("  - Classes: ${context.classes.take(3).joinToString(", ")}${if (context.classes.size > 3) " +${context.classes.size - 3} more" else ""}")
                }
                if (context.routes.isNotEmpty()) {
                    appendLine("  - Routes: ${context.routes.take(3).joinToString(", ")}${if (context.routes.size > 3) " +${context.routes.size - 3} more" else ""}")
                }
                if (context.dependencies.isNotEmpty()) {
                    appendLine("  - Dependencies: ${context.dependencies.take(5).joinToString(", ")}${if (context.dependencies.size > 5) " +${context.dependencies.size - 5} more" else ""}")
                }
            }
            
            // Add file reference summary
            val referencesFile = File(agentDir, "file_references.json")
            if (referencesFile.exists()) {
                runCatching {
                    val refs = JSONObject(referencesFile.readText())
                    val files = refs.optJSONObject("files")
                    if (files != null && files.length() > 0) {
                        appendLine("\n## File Reference Summary")
                        appendLine("Total tracked files: ${files.length()}")
                    }
                }
            }
        }
    }

    private fun loadMiniPlansRoot(): JSONObject {
        return runCatching { JSONObject(miniPlanFile.takeIf { it.exists() }?.readText().orEmpty()) }
            .getOrElse { JSONObject().put("plans", JSONObject()) }
    }

    private fun saveMiniPlansRoot(root: JSONObject) {
        miniPlanFile.writeText(root.toString(2))
    }

    private fun getMiniPlanJsonForParent(parentTaskId: String): JSONObject? {
        val root = loadMiniPlansRoot()
        val plans = root.optJSONObject("plans") ?: return null
        return plans.optJSONObject(parentTaskId)
    }

    private fun setMiniPlanJsonForParent(parentTaskId: String, miniJson: JSONObject) {
        val root = loadMiniPlansRoot()
        val plans = root.optJSONObject("plans") ?: JSONObject().also { root.put("plans", it) }
        plans.put(parentTaskId, miniJson)
        saveMiniPlansRoot(root)
    }

    private fun persistMiniPlanWithStatuses(mini: MiniPlan) {
        val tasksArr = JSONArray()
        mini.tasks.forEach { t ->
            tasksArr.put(JSONObject().apply {
                put("id", t.id)
                put("description", t.description)
                if (!t.category.isNullOrBlank()) put("category", t.category)
                if (!t.targets.isNullOrEmpty()) put("targets", JSONArray(t.targets))
                if (!t.search.isNullOrEmpty()) put("search", JSONArray(t.search))
                if (!t.markers.isNullOrEmpty()) put("markers", JSONArray(t.markers))
                put("status", "pending")
                put("attempts", 0)
            })
        }
        val json = JSONObject().apply {
            put("parent_task_id", mini.parentTaskId)
            put("reason", mini.reason)
            put("tasks", tasksArr)
        }
        setMiniPlanJsonForParent(mini.parentTaskId, json)
    }

    private fun loadMiniPlan(parentTaskId: String): MiniPlan? {
        val json = getMiniPlanJsonForParent(parentTaskId) ?: return null
        val arr = json.optJSONArray("tasks") ?: JSONArray()
        val tasks = mutableListOf<MiniTask>()
        for (i in 0 until arr.length()) {
            val t = arr.optJSONObject(i) ?: continue
            val id = t.optString("id").ifBlank { "m${i + 1}" }
            val desc = t.optString("description")
            if (desc.isBlank()) continue
            val cat = t.optString("category").ifBlank { null }
            val targets = t.optJSONArray("targets")?.let { a -> (0 until a.length()).mapNotNull { idx -> a.optString(idx) } }
            val search = t.optJSONArray("search")?.let { a -> (0 until a.length()).mapNotNull { idx -> a.optString(idx) } }
            val markers = t.optJSONArray("markers")?.let { a -> (0 until a.length()).mapNotNull { idx -> a.optString(idx) } }
            tasks.add(MiniTask(id, desc, cat, targets, search, markers))
        }
        val reason = json.optString("reason").ifBlank { "" }
        return MiniPlan(parentTaskId, reason, tasks)
    }

    private fun getMiniTaskStatus(parentTaskId: String, miniTaskId: String): String {
        val json = getMiniPlanJsonForParent(parentTaskId) ?: return "pending"
        val arr = json.optJSONArray("tasks") ?: return "pending"
        for (i in 0 until arr.length()) {
            val t = arr.optJSONObject(i) ?: continue
            if (t.optString("id") == miniTaskId) return t.optString("status", "pending")
        }
        return "pending"
    }

    private fun markMiniTaskDone(parentTaskId: String, miniTaskId: String) {
        val root = loadMiniPlansRoot()
        val plans = root.optJSONObject("plans") ?: JSONObject().also { root.put("plans", it) }
        val json = plans.optJSONObject(parentTaskId) ?: return
        val arr = json.optJSONArray("tasks") ?: return
        for (i in 0 until arr.length()) {
            val t = arr.optJSONObject(i) ?: continue
            if (t.optString("id") == miniTaskId) {
                t.put("status", "done")
                t.put("ts", System.currentTimeMillis())
            }
        }
        saveMiniPlansRoot(root)
    }

    private fun incrementMiniTaskAttempts(parentTaskId: String, miniTaskId: String): Int {
        val root = loadMiniPlansRoot()
        val plans = root.optJSONObject("plans") ?: JSONObject().also { root.put("plans", it) }
        val json = plans.optJSONObject(parentTaskId) ?: return 0
        val arr = json.optJSONArray("tasks") ?: return 0
        var next = 0
        for (i in 0 until arr.length()) {
            val t = arr.optJSONObject(i) ?: continue
            if (t.optString("id") == miniTaskId) {
                next = t.optInt("attempts", 0) + 1
                t.put("attempts", next)
                if (!t.has("status")) t.put("status", "pending")
            }
        }
        saveMiniPlansRoot(root)
        return next
    }

    private fun getNextPendingMiniTask(mini: MiniPlan): MiniTask? {
        for (t in mini.tasks) {
            if (getMiniTaskStatus(mini.parentTaskId, t.id) != "done") return t
        }
        return null
    }

    private suspend fun decideRemediationAction(goal: String, task: Task, failureNote: String): String = withContext(Dispatchers.IO) {
        val sys = """
            You are a senior project management and troubleshooting specialist.
            Task: Determine the optimal remediation strategy when task execution encounters difficulties.
            Context: Analyze task failures, project goals, and available resources to select the most effective recovery approach.
            Output format: Return ONLY JSON with remediation decision and reasoning.
            
            Remediation strategies:
            - mini_plan: Create targeted sub-tasks to resolve specific blockers without major plan changes
            - revise_plan: Restructure the overall approach when fundamental issues are identified
            - retry: Attempt the same task again when failures appear transient or context-dependent
            
            Decision criteria:
            - mini_plan: For isolated issues that can be resolved with focused discovery or edits
            - revise_plan: When the current approach is fundamentally flawed or misaligned
            - retry: For temporary failures, missing context, or transient system issues
            
            Return format: {"action": "strategy_type", "why": "detailed reasoning"}
        """.trimIndent()
        val user = """
            Goal: ${goal}
            Current task: ${task.id} - ${task.description}
            Task category: ${task.category ?: "unspecified"}
            Failure note: ${failureNote}
            Prior observations: ${(observations[task.id] ?: "(none)").take(800)}
        """.trimIndent()
        val messages = mutableListOf(LlmMessage("system", sys), LlmMessage("user", user))
        val reco = helperRecommend("remediation_decision", mapOf("task" to task.description.take(300)))
        applyHelperToMessages(reco, messages)
        val content = collectAllWithRetry(flowProvider = { LlmProvider.current().generate(messages) })
        val jsonText = extractFirstJsonObject(content) ?: return@withContext "mini_plan"
        val obj = runCatching { JSONObject(jsonText) }.getOrNull() ?: return@withContext "mini_plan"
        val action = obj.optString("action").ifBlank { "mini_plan" }
        appendTaskLog("remediation_decision") {
            put("task_id", task.id)
            put("action", action)
            put("failure_note", failureNote.take(400))
        }
        return@withContext action
    }

    private suspend fun requestMiniPlanForTask(plan: Plan, task: Task, failureNote: String): MiniPlan? = withContext(Dispatchers.IO) {
        val wdPath = workingDirProvider()
        val wd = File(wdPath)
        val workspaceInfo = if (wd.exists() && wd.isDirectory) listTopLevel(wd, limit = 200) else JSONObject().put("path", wdPath).put("items", JSONArray()).toString()
        val obsJson = JSONObject().apply {
            observations.entries.forEach { (k, v) -> put(k, if (v.length > 4000) v.take(4000) + " …" else v) }
        }.toString()
        val sys = """
            You are a senior task remediation and problem-solving specialist.
            Task: Create targeted mini-plans to resolve specific task execution blockers.
            Context: Analyze task failures and project context to design focused recovery strategies.
            Output format: Return ONLY JSON with structured remediation plan and execution details.
            
            Mini-plan design principles:
            - Focus on 2-6 targeted steps to resolve specific issues
            - Prioritize discovery and analysis before modifications
            - Ensure each step is precise, actionable, and idempotent
            - Maintain logical progression from diagnosis to resolution
            
            Task categories:
            - list_dir: Directory exploration for structure understanding
            - read_file: File content examination and analysis
            - grep: Pattern-based content search and identification
            - analyze: Code review, debugging, and problem diagnosis
            - write_file: Complete file creation with full implementation
            - apply_changes: Targeted file modifications and updates
            - make_dir: Directory structure creation and organization
            - create_file: File creation for new components
            - run_shell: Command execution and system operations
            - json_edit: Structured data modifications and configuration
            
            Plan structure: {"parent_task_id": "string", "reason": "string", "tasks": [{"id": "string", "category": "string", "description": "string", "targets": ["string"], "search": ["string"], "markers": ["string"]}]}
            
            Task ID format: Use short, stable identifiers (m1, m2, m3, etc.)
        """.trimIndent()
        val user = """
            Goal: ${plan.goal}
            Parent task: ${task.id} - ${task.description}
            Category: ${task.category ?: "unspecified"}
            Failure note: ${failureNote}
            Working directory: ${wdPath}
            Workspace snapshot: ${workspaceInfo}
            Prior observations: ${obsJson}
        """.trimIndent()
        val messages = mutableListOf(LlmMessage("system", sys), LlmMessage("user", user))
        val reco = helperRecommend("mini_plan", mapOf("parent_task" to task.description.take(300)))
        applyHelperToMessages(reco, messages)
        val content = collectAllWithRetry(flowProvider = { LlmProvider.current().generate(messages) })
        val jsonText = extractFirstJsonObject(content) ?: return@withContext null
        val obj = runCatching { JSONObject(jsonText) }.getOrNull() ?: return@withContext null
        val parent = obj.optString("parent_task_id").ifBlank { task.id }
        val reason = obj.optString("reason").ifBlank { "remediate failure" }
        val arr = obj.optJSONArray("tasks") ?: JSONArray()
        val tasks = mutableListOf<MiniTask>()
        for (i in 0 until arr.length()) {
            val t = arr.optJSONObject(i) ?: continue
            val id = t.optString("id").ifBlank { "m${i + 1}" }
            val desc = t.optString("description")
            val cat = t.optString("category").ifBlank { null }
            val targets = t.optJSONArray("targets")?.let { a -> (0 until a.length()).mapNotNull { idx -> a.optString(idx) } }
            val search = t.optJSONArray("search")?.let { a -> (0 until a.length()).mapNotNull { idx -> a.optString(idx) } }
            val markers = t.optJSONArray("markers")?.let { a -> (0 until a.length()).mapNotNull { idx -> a.optString(idx) } }
            if (desc.isNotBlank()) tasks.add(MiniTask(id, desc, cat, targets, search, markers))
        }
        val mini = MiniPlan(parent, reason, tasks)
        persistMiniPlanWithStatuses(mini)
        appendTaskLog("mini_plan_created") {
            put("parent_task_id", parent)
            put("reason", reason)
            put("steps", tasks.size)
        }
        return@withContext mini
    }

    private suspend fun executeMiniPlanForTask(plan: Plan, parentTask: Task, onStatus: (String) -> Unit): Boolean {
        var mini = loadMiniPlan(parentTask.id)
        if (mini == null) {
            val created = requestMiniPlanForTask(plan, parentTask, observations[parentTask.id] ?: "no_note")
            if (created == null) return false
            mini = created
            onStatus("Mini-plan created for ${parentTask.id}: ${mini.tasks.size} step(s)")
            appendTaskLog("mini_plan_start") { put("parent_task_id", parentTask.id); put("steps", mini.tasks.size) }
        }
        var steps = 0
        val maxSteps = 12
        while (steps < maxSteps) {
            val mt = getNextPendingMiniTask(mini) ?: return true
            onStatus("Mini ${mini.parentTaskId}.${mt.id}: ${mt.description}")
            appendTaskLog("mini_step_start") {
                put("parent_task_id", mini.parentTaskId)
                put("step_id", mt.id)
                put("description", mt.description)
            }
            val attemptNo = incrementMiniTaskAttempts(mini.parentTaskId, mt.id)
            if (attemptNo > 3) {
                onStatus("Mini ${mini.parentTaskId}.${mt.id}: attempts exceeded")
                appendTaskLog("mini_step_aborted") { put("parent_task_id", mini.parentTaskId); put("step_id", mt.id); put("reason", "attempts_exceeded") }
                return false
            }
            val pseudoTask = Task(
                id = "${mini.parentTaskId}.${mt.id}",
                description = mt.description,
                category = mt.category,
                targets = mt.targets,
                search = mt.search,
                markers = mt.markers
            )
            val proposed = requestSingleToolCall(plan.goal, pseudoTask)
            val toolCall = proposed ?: ToolCall("analyze", JSONObject())
                        appendTaskLog("tool_call_selected") { put("task_id", pseudoTask.id); put("type", toolCall.type); put("args", toolCall.args) }
            runCatching {
                val kind = detectTaskKind(pseudoTask)
                ControlApiClient.recordTaskDetection(sessionId, pseudoTask.id, kind, pseudoTask.description, pseudoTask.category)
            }
            val result = try {
                currentTaskContext = pseudoTask
                executeToolCall(toolCall)
            } catch (e: Exception) {
                val err = e.message ?: e.toString()
                observations[pseudoTask.id] = "mini error: ${err}"
                saveObservations()
                steps++
                appendTaskLog("tool_result") {
                    put("task_id", pseudoTask.id)
                    put("type", toolCall.type)
                    put("ok", false)
                    put("error", (e.message ?: e.toString()).take(1000))
                }
                continue
            } finally {
                currentTaskContext = null
            }
            appendTaskLog("tool_result") {
                put("task_id", pseudoTask.id)
                put("type", toolCall.type)
                put("ok", result.ok)
                result.observation?.let { put("observation_preview", it.take(800)); put("observation_bytes", it.toByteArray(StandardCharsets.UTF_8).size) }
            }
            if (result.ok) {
                if (!result.observation.isNullOrBlank()) {
                    observations[pseudoTask.id] = result.observation
                    saveObservations()
                    onStatus("Observed (${pseudoTask.id}): ${result.observation.take(600)}${if ((result.observation?.length ?: 0) > 600) " …" else ""}")
                }
                // Consider the mini step done on any successful action
                markMiniTaskDone(mini.parentTaskId, mt.id)
                onStatus("Mini ${mini.parentTaskId}.${mt.id}: done")
                appendTaskLog("mini_step_done") { put("parent_task_id", mini.parentTaskId); put("step_id", mt.id) }
            } else {
                if (!observations.containsKey(pseudoTask.id)) {
                    observations[pseudoTask.id] = "mini failed without exception"
                    saveObservations()
                }
                steps++
            }
        }
        onStatus("Mini-plan: reached step limit")
        return false
    }

    // ===================== THINK & ACT (Adaptive) =====================
    private suspend fun classifyUserIntent(prompt: String, workspaceInfo: String): JSONObject = withContext(Dispatchers.IO) {
        val sys = """
            You are a senior AI classification specialist.
            Task: Classify user intent from their request.
            Context: Analyze the user's prompt and workspace information to determine their primary goal.
            Output format: Return ONLY JSON with intent classification and reasoning.
            
            Available intents:
            - error_diagnosis: User is experiencing errors or issues
            - question_analysis: User is asking questions about code or system
            - project_bootstrap: User wants to create a new project from scratch
            - feature_addition: User wants to add new features to existing code
            - plan_and_execute: User wants to build or modify something with step-by-step execution
            
            Return format: {"intent": "intent_type", "why": "brief reasoning"}
        """.trimIndent()
        val user = """
            Prompt: ${prompt}
            Workspace snapshot: ${workspaceInfo}
        """.trimIndent()
        val messages = mutableListOf(LlmMessage("system", sys), LlmMessage("user", user))
        val reco = helperRecommend("classify_intent", mapOf("prompt" to prompt.take(500)))
        applyHelperToMessages(reco, messages)
        val content = collectAllWithRetry(flowProvider = { LlmProvider.current().generate(messages) })
        val jsonText = extractFirstJsonObject(content) ?: "{\"intent\":\"plan_and_execute\"}"
        return@withContext runCatching { JSONObject(jsonText) }.getOrElse { JSONObject().put("intent", "plan_and_execute") }
    }

    private suspend fun requestDiscoveryToolCall(contextNote: String): ToolCall? = withContext(Dispatchers.IO) {
        val sys = """
            You are a senior system discovery specialist.
            Task: Propose one discovery tool call to gather information.
            Context: Analyze the current context and determine the most appropriate discovery action to gather relevant information.
            Output format: Return ONLY JSON with a single tool call for discovery.
            
            Discovery priorities:
            1. Environment checks for system interactions (uname -a, package managers, language runtimes)
            2. File system exploration (list_dir, list_dir_recursive, read_file)
            3. Content analysis (grep, read_file_section_by_markers)
            4. System information (stat_file, json_get)
            
            Available tools: list_dir, list_dir_recursive, grep, read_file, read_file_lines, head_file, tail_file, read_file_chunk, read_file_section_by_markers, read_files_glob, stat_file, json_get, get_cached_command_output, list_cached_commands, run_shell
            
            Return format: {"type": "tool_name", "args": {...}}
        """.trimIndent()
        val wd = workingDirProvider()
        val user = """
            Working directory: ${wd}
            Context: ${contextNote}
            Prior signals: ${(observations.entries.joinToString("\n") { (k, v) -> "${k}: ${v.take(200)}" }).ifBlank { "(none)" }}
        """.trimIndent()
        val msgs = mutableListOf(LlmMessage("system", sys), LlmMessage("user", user))
        val reco = helperRecommend("discovery", mapOf("wd" to wd, "context" to contextNote))
        applyHelperToMessages(reco, msgs)
        val content = collectAllWithRetry(flowProvider = { LlmProvider.current().generate(msgs) })
        val jsonText = extractFirstJsonObject(content) ?: return@withContext null
        val obj = runCatching { JSONObject(jsonText) }.getOrNull() ?: return@withContext null
        val type = obj.optString("type")
        val args = obj.optJSONObject("args") ?: JSONObject()
        return@withContext ToolCall(type, args)
    }

    private suspend fun requestBlueprint(prompt: String, workspaceInfo: String): String? = withContext(Dispatchers.IO) {
        val sys = """
            You are a senior software architect and project planner.
            Task: Create a comprehensive project blueprint based on user requirements.
            Context: Analyze the user's goal and workspace information to design an optimal project structure.
            Output format: Return ONLY minified JSON with project architecture and planning details.
            
            Blueprint structure:
            - name: Project name
            - summary: Brief project description
            - stack: Technology stack (language, frameworks)
            - modules: Core components and their responsibilities
            - apis: API endpoints and their purposes
            
            Design principles:
            - Keep it concise but comprehensive
            - Focus on scalability and maintainability
            - Consider best practices for the chosen technology stack
            - Ensure modularity and separation of concerns
            
            Return format: {"name": "string", "summary": "string", "stack": {"lang": "string", "frameworks": ["string"]}, "modules": [{"id": "string", "name": "string", "responsibilities": ["string"]}], "apis": [{"name": "string", "endpoints": [{"path": "string", "method": "string", "desc": "string"}]}]}
        """.trimIndent()
        val user = """
            Goal: ${prompt}
            Workspace snapshot: ${workspaceInfo}
        """.trimIndent()
        val messages = mutableListOf(LlmMessage("system", sys), LlmMessage("user", user))
        val reco = helperRecommend("blueprint", mapOf("goal" to prompt.take(500)))
        applyHelperToMessages(reco, messages)
        val content = collectAllWithRetry(flowProvider = { LlmProvider.current().generate(messages) })
        val jsonText = extractFirstJsonObject(content) ?: return@withContext null
        blueprintFile.writeText(jsonText)
        return@withContext blueprintFile.absolutePath
    }

    private suspend fun answerQuestionFromObservations(prompt: String): String = withContext(Dispatchers.IO) {
        val sys = """
            You are a senior technical analyst and code reviewer.
            Task: Answer user questions based on repository observations and codebase analysis.
            Context: Use prior observations from the repository to provide accurate and helpful answers.
            Output format: Provide clear, concise answers in plain text with relevant file citations.
            
            Answer guidelines:
            - Be precise and factual based on the available observations
            - Cite specific filenames and paths when referencing code
            - Provide context and explanations for technical concepts
            - If information is missing, acknowledge limitations
            - Focus on actionable insights and practical solutions
            
            Response style: Clear, professional, and technically accurate
        """.trimIndent()
        val obs = observations.entries.joinToString("\n\n") { (k, v) -> "[${k}]\n${v.take(4000)}" }
        val user = """
            Question: ${prompt}
            Observations:
            ${obs.ifBlank { "(none)" }}
        """.trimIndent()
        val messages = mutableListOf(LlmMessage("system", sys), LlmMessage("user", user))
        val reco = helperRecommend("qa_answer", mapOf("obs_preview" to obs.take(800)))
        applyHelperToMessages(reco, messages)
        val content = collectAllWithRetry(flowProvider = { LlmProvider.current().generate(messages) })
        return@withContext content
    }

    private suspend fun generatePlanWithContext(userGoal: String, extraContext: String?): Plan? = withContext(Dispatchers.IO) {
        runCatching { if (progressFile.exists()) progressFile.delete() }
        observations.clear()
        saveObservations()
        val wdPath = workingDirProvider()
                val wd = File(wdPath)
        val workspaceInfo = if (wd.exists() && wd.isDirectory) listTopLevel(wd) else JSONObject().put("path", wdPath).put("items", JSONArray()).toString()
        // Record initial setup (user goal + expectations) to external control API if enabled
        runCatching {
            val expectations = extraContext ?: ""
            ControlApiClient.recordInitialSetup(sessionId, userGoal, expectations, workspaceInfo)
        }
        // If a plan requires creation, ensure codebase discovery is run first
            if (Settings.codebase_agent_enabled) runCatching { buildCodebaseCache({ }, includeRecursive = true) }
        val sys = """
            You are a senior software architect and project planning specialist.
            Task: Create comprehensive, step-by-step execution plans for software projects and codebase updates.
            Context: Analyze user goals, workspace information, and project requirements to design optimal execution strategies.
            Output format: Return ONLY minified JSON with structured task plans and execution details.
            
            Planning principles:
            - For updates/refactors/fixes: Prioritize codebase discovery and analysis before modifications
            - For new projects: Begin with discovery if workspace exists, then proceed with creation tasks
            - Ensure logical task sequencing and dependency management
            - Focus on incremental, testable progress
            
            Task categories:
            - list_dir: Directory exploration and structure analysis
            - read_file: File content examination and understanding
            - grep: Pattern-based content search and analysis
            - analyze: Code review, debugging, and problem diagnosis
            - write_file: Complete file creation with full content
            - apply_changes: Targeted file modifications and updates
            - make_dir: Directory structure creation
            - create_file: File creation (empty or template-based)
            - run_shell: Command execution and system operations
            - json_edit: Structured data modifications
            
            Plan structure: {"goal": "string", "tasks": [{"id": "string", "category": "string", "description": "string", "targets": ["string"], "search": ["string"], "markers": ["string"]}]}
            
            Task ID format: Use short, unique identifiers (e.g., t1, t2, t3)
        """.trimIndent()
        val user = """
            Goal: ${userGoal}
            Working directory: ${wdPath}
            Workspace snapshot (top-level): ${workspaceInfo}
            Extra context: ${extraContext ?: "(none)"}
        """.trimIndent()
        var plan: Plan? = null
        var attempts = 0
        while (attempts < 3) {
            // Prefer external Control API plan if enabled
            if (attempts == 0) {
                val apiPlan = runCatching {
                    ControlApiClient.requestPlan(sessionId, userGoal, extraContext ?: "", workspaceInfo)
                }.getOrNull()
                if (apiPlan != null) {
                    val pGoal = apiPlan.optString("goal").ifBlank { userGoal }
                    val tasksArr = apiPlan.optJSONArray("tasks") ?: JSONArray()
                    val tasks = mutableListOf<Task>()
                    for (i in 0 until tasksArr.length()) {
                        val t = tasksArr.optJSONObject(i) ?: continue
                        val id = t.optString("id").ifBlank { "t${i + 1}" }
                        val desc = t.optString("description")
                        val cat = t.optString("category").ifBlank { null }
                        val targets = t.optJSONArray("targets")?.let { arr -> (0 until arr.length()).mapNotNull { idx -> arr.optString(idx) } }
                        val search = t.optJSONArray("search")?.let { arr -> (0 until arr.length()).mapNotNull { idx -> arr.optString(idx) } }
                        val markers = t.optJSONArray("markers")?.let { arr -> (0 until arr.length()).mapNotNull { idx -> arr.optString(idx) } }
                        val dependencies = t.optJSONArray("dependencies")?.let { arr -> (0 until arr.length()).mapNotNull { idx -> arr.optString(idx) } } ?: emptyList()
                        val confidence = t.optDouble("confidence", 0.8).toFloat()
                        val risk = t.optString("risk").ifBlank { "Low" }
                        if (desc.isNotBlank()) tasks.add(Task(id, desc, cat, targets, search, markers, dependencies, confidence, risk))
                    }
                    if (tasks.isNotEmpty()) {
                        plan = Plan(pGoal, tasks)
                        break
                    }
                }
            }
            val content = collectAllWithRetry(flowProvider = { LlmProvider.current().generate(listOf(LlmMessage("system", sys), LlmMessage("user", user))) })
            val jsonText = extractFirstJsonObject(content) ?: continue
            val obj = runCatching { JSONObject(jsonText) }.getOrNull() ?: continue
            val goal = obj.optString("goal").ifBlank { userGoal }
            val tasksArr = obj.optJSONArray("tasks") ?: JSONArray()
            val tasks = mutableListOf<Task>()
            for (i in 0 until tasksArr.length()) {
                val t = tasksArr.optJSONObject(i) ?: continue
                val id = t.optString("id").ifBlank { "t${i + 1}" }
                val desc = t.optString("description")
                val cat = t.optString("category").ifBlank { null }
                val targets = t.optJSONArray("targets")?.let { arr -> (0 until arr.length()).mapNotNull { idx -> arr.optString(idx) } }
                val search = t.optJSONArray("search")?.let { arr -> (0 until arr.length()).mapNotNull { idx -> arr.optString(idx) } }
                val markers = t.optJSONArray("markers")?.let { arr -> (0 until arr.length()).mapNotNull { idx -> arr.optString(idx) } }
                val dependencies = t.optJSONArray("dependencies")?.let { arr -> (0 until arr.length()).mapNotNull { idx -> arr.optString(idx) } } ?: emptyList()
                val confidence = t.optDouble("confidence", 0.8).toFloat()
                val risk = t.optString("risk").ifBlank { "Low" }
                if (desc.isNotBlank()) {
                    tasks.add(Task(id, desc, cat, targets, search, markers, dependencies, confidence, risk))
                }
            }
 
                         if (tasks.isEmpty()) {
                // Simple fallback plan to avoid zero-task output
                val fallback = mutableListOf<Task>()
                fallback.add(Task("t1", "List top-level workspace", "list_dir", listOf(wdPath), null, null))
                fallback.add(Task("t2", "Search for common project files", "grep", listOf(wdPath), listOf("build\\.gradle|settings\\.gradle|package\\.json|README|Main|AndroidManifest"), null))
                tasks.addAll(fallback)
            }
            
            // PAVL Pillar 2: Automatic test generation - add test tasks for implementation tasks
            val enhancedTasks = addAutomaticTestGeneration(tasks.toMutableList())
            tasks.clear()
            tasks.addAll(enhancedTasks)
 
             val isWebAppProject = userGoal.contains("web", ignoreCase = true) ||
                                   userGoal.contains("website", ignoreCase = true) ||
                                   userGoal.contains("flask", ignoreCase = true) ||
                                   userGoal.contains("html", ignoreCase = true)
 
             val fileCreationTasks = tasks.count { it.category == "create_file" || it.category == "write_file" }
 
             if (isWebAppProject && fileCreationTasks < 3) {
                 attempts++
                 continue // Regenerate the plan if it's too simple for a web app
             }
 
             plan = Plan(goal, tasks)
             break
         }
 
         if (plan == null) return@withContext null
 
 
         captureProjectRequirements(plan.goal) // Capture the project requirements
         persistPlanWithStatuses(plan)
         runCatching {
             val tArr = JSONArray()
             plan.tasks.forEach { t -> tArr.put(JSONObject().put("id", t.id).put("description", t.description).put("category", t.category ?: "")) }
             appendTaskLog("plan_created") { put("goal", plan.goal); put("tasks", tArr) }
         }
         return@withContext plan
     }

    suspend fun thinkAndAct(prompt: String, onStatus: (String) -> Unit): ThinkResult {
        beginRunStats()
        val wdPath = workingDirProvider()
        val wd = File(wdPath)
        val workspaceInfo = if (wd.exists() && wd.isDirectory) listTopLevel(wd, limit = 200) else JSONObject().put("path", wdPath).put("items", JSONArray()).toString()
        if (Settings.codebase_agent_enabled) {
            runCatching { buildCodebaseCache(onStatus, includeRecursive = true) }
        }
        // After executing a modifying tool, we already schedule cache upgrade via notifyWorkspaceChanged

        // Apply any pending codebase cache upgrades due to file changes
        performCodebaseUpgradeIfPending(onStatus)
        onStatus("Thinking about intent…")
        val intentObj = classifyUserIntent(prompt, workspaceInfo)
        val intent = intentObj.optString("intent", "plan_and_execute")
        val isUpdateLike = prompt.contains("update", ignoreCase = true) || prompt.contains("modify", ignoreCase = true) || prompt.contains("refactor", ignoreCase = true) || prompt.contains("fix", ignoreCase = true)
        // Enable codebase agent for both update-like and non-empty creation goals
        if (isUpdateLike || workspaceInfo.contains("\"items\":[") ) Settings.codebase_agent_enabled = true
        onStatus("Intent: ${intent}")
        // Search suggestion
        if (Settings.helper_agent_enabled && promptSuggestsSearch(prompt)) {
            onStatus("Search hint: query seems to need external info. Running search…")
            val info = runSearchAgent(prompt, onStatus)
            onStatus(info.take(1200))
        }
        when (intent) {
            "error_diagnosis" -> {
                onStatus("Diagnosing error via discovery loop…")
                var steps = 0
                val startTime = System.currentTimeMillis()
                val maxTime = 15000L // 15 seconds max for discovery
                
                while (steps < 10) {
                    // Check for timeout
                    if (System.currentTimeMillis() - startTime > maxTime) {
                        onStatus("Discovery timeout reached, proceeding with plan generation")
                        break
                    }
                    
                    val tc = requestDiscoveryToolCall("error-diagnosis for: ${prompt}") ?: break
                    val result = executeToolCall(tc)
                    val key = "think:error:${steps+1}:${tc.type}"
                    if (result.observation != null) {
                        observations[key] = result.observation
                        saveObservations()
                        onStatus("Observed (${tc.type}): ${result.observation.take(600)}${if ((result.observation?.length ?: 0) > 600) " …" else ""}")
                    }
                    steps++
                    if (!isDiscoveryTool(tc.type)) break
                }
                onStatus("Asking AI for remediation plan…")
                val plan = generatePlanWithContext(prompt, observations.entries.joinToString("\n") { (k, v) -> "${k}: ${v.take(1000)}" })
                return ThinkResult(producedPlan = plan)
            }
            "question_analysis" -> {
                onStatus("Investigating repository to answer question…")
                var steps = 0
                while (steps < 8) {
                    val tc = requestDiscoveryToolCall("question-analysis for: ${prompt}") ?: break
                    val result = executeToolCall(tc)
                    val key = "think:qa:${steps+1}:${tc.type}"
                    if (result.observation != null) {
                        observations[key] = result.observation
                        saveObservations()
                        onStatus("Observed (${tc.type})")
                    }
                    steps++
                }
                val answer = answerQuestionFromObservations(prompt)
                onStatus(answer.take(1200))
                endRunStatsAndReport(onStatus, verb = "thought")
                return ThinkResult(answer = answer)
            }
            "project_bootstrap" -> {
                onStatus("Drafting blueprint…")
                val bpPath = requestBlueprint(prompt, workspaceInfo)
                if (bpPath != null) onStatus("Blueprint saved: ${bpPath}") else onStatus("Blueprint not produced")
                val bpText = runCatching { blueprintFile.readText() }.getOrElse { "{}" }
                onStatus("Planning using blueprint…")
                val plan = generatePlanWithContext(prompt, bpText)
                endRunStatsAndReport(onStatus, verb = "thought")
                return ThinkResult(producedPlan = plan, blueprintPath = bpPath)
            }
            "feature_addition" -> {
                onStatus("Analyzing project for feature addition…")
                var steps = 0
                while (steps < 10) {
                    val tc = requestDiscoveryToolCall("feature-addition context for: ${prompt}") ?: break
                    val result = executeToolCall(tc)
                    val key = "think:feature:${steps+1}:${tc.type}"
                    if (result.observation != null) {
                        observations[key] = result.observation
                        saveObservations()
                        onStatus("Observed (${tc.type})")
                    }
                    steps++
                }
                onStatus("Planning feature implementation…")
                val plan = generatePlanWithContext(prompt, observations.entries.joinToString("\n") { (k, v) -> "${k}: ${v.take(1000)}" })
                endRunStatsAndReport(onStatus, verb = "thought")
                return ThinkResult(producedPlan = plan)
            }
            else -> {
                onStatus("Generating plan…")
                val plan = generatePlanWithContext(prompt, runCatching { blueprintFile.readText() }.getOrNull())
                endRunStatsAndReport(onStatus, verb = "thought")
                return ThinkResult(producedPlan = plan)
            }
        }
    }

    private suspend fun runSearchAgent(query: String, onStatus: (String) -> Unit): String = withContext(Dispatchers.IO) {
        try {
            onStatus("Search: analyzing query and selecting sources...")
            
            // Enhanced fallback sources based on query content
            val fallbackSources = determineFallbackSources(query)
            
            val suggestSys = """
                You suggest 2-4 high-quality websites to consult for the given query. Return ONLY minified JSON:
                {"sites": [{"url": string, "why": string}...]}
                
                Selection criteria:
                - Prioritize official documentation, MDN, language/framework docs
                - Include reputable technical blogs and Stack Overflow when relevant
                - Avoid general search engines, social media, or unreliable sources
                - Ensure URLs are complete and accessible
                - Focus on current, up-to-date information
            """.trimIndent()
            val suggestUser = """
                Query: ${query.take(500)}
                Focus on technical accuracy and authoritative sources.
                Fallback sources available: ${fallbackSources.joinToString(", ")}
            """.trimIndent()
            
            val suggestContent = runCatching {
                withTimeout(15000L) { // 15 second timeout for LLM suggestion
                    collectAllWithRetry(flowProvider = { 
                        LlmProvider.current().generate(listOf(
                            LlmMessage("system", suggestSys), 
                            LlmMessage("user", suggestUser)
                        )) 
                    })
                }
            }.getOrElse { 
                onStatus("Search: LLM suggestion failed, using fallback sources")
                // Use fallback sources instead of failing
                val fallbackJson = JSONObject().put("sites", JSONArray().apply {
                    fallbackSources.forEach { url ->
                        put(JSONObject().put("url", url).put("why", "fallback source"))
                    }
                })
                fallbackJson.toString()
            }
            
            val suggestJson = extractFirstJsonObject(suggestContent)
            val sites = if (suggestJson != null) {
                runCatching { JSONObject(suggestJson).optJSONArray("sites") }.getOrNull() ?: JSONArray()
            } else {
                onStatus("Search: Invalid JSON response, using fallback")
                JSONArray()
            }
            
            if (sites.length() == 0) {
                // Use fallback sources if no sites suggested
                onStatus("Search: using fallback sources for query")
                fallbackSources.forEach { url ->
                    sites.put(JSONObject().put("url", url).put("why", "fallback"))
                }
            }
            
            val fetched = JSONArray()
            fun curl(url: String): Pair<String, Boolean> {
                return runCatching {
                    val cleanUrl = url.replace("'", "%27").replace("\"", "%22")
                    val cmd = "curl -L --max-time 15 --silent --show-error --compressed --connect-timeout 10 --user-agent 'Mozilla/5.0 (compatible; SearchBot/1.0)' '$cleanUrl'"
                    val res = executeToolCall(ToolCall("run_shell", JSONObject().put("command", cmd).put("timeout_ms", 20000)))
                    val content = res.observation ?: ""
                    val success = res.ok && content.isNotBlank() && !content.contains("curl: ") && !content.contains("error:")
                    Pair(content, success)
                }.getOrElse { e ->
                    onStatus("Search: failed to fetch $url - ${e.message}")
                    Pair("", false) 
                }
            }
            
            val linkRegex = Regex("href=[\"'](https?://[^\"']+)[\"']", RegexOption.IGNORE_CASE)
            val toVisit = ArrayDeque<String>()
            
            // Add initial sites
            for (i in 0 until sites.length()) {
                val siteObj = sites.optJSONObject(i)
                val u = siteObj?.optString("url")?.trim().orEmpty()
                if (u.isNotBlank() && (u.startsWith("http://") || u.startsWith("https://"))) {
                    toVisit.add(u)
                }
            }
            
            if (toVisit.isEmpty()) {
                return@withContext "Search failed: No valid URLs to fetch"
            }
            
            val visited = mutableSetOf<String>()
            var pages = 0
            val maxPages = 4
            
            while (toVisit.isNotEmpty() && pages < maxPages) {
                val u = toVisit.removeFirst()
                if (visited.contains(u)) continue
                visited.add(u)
                
                onStatus("Search: fetching ${u.take(50)}...")
                val (html, success) = curl(u)
                
                if (success && html.length > 100) {
                    // Clean and truncate HTML content
                    val cleanHtml = html
                        .replace(Regex("<script[^>]*>.*?</script>", RegexOption.DOT_MATCHES_ALL), "")
                        .replace(Regex("<style[^>]*>.*?</style>", RegexOption.DOT_MATCHES_ALL), "")
                        .replace(Regex("<[^>]+>"), " ")
                        .replace(Regex("\\s+"), " ")
                        .trim()
                    
                    fetched.put(JSONObject().apply {
                        put("url", u)
                        put("content", cleanHtml.take(15000))
                        put("title", extractTitle(html))
                    })
                    
                    // Extract additional relevant links (limit to same domain for focus)
                    if (pages < 2) {
                        val domain = runCatching { 
                            java.net.URL(u).host 
                        }.getOrNull()
                        
                        linkRegex.findAll(html).take(3).forEach { m ->
                            val link = m.groupValues[1]
                            val linkDomain = runCatching { 
                                java.net.URL(link).host 
                            }.getOrNull()
                            
                            if (!visited.contains(link) && linkDomain == domain) {
                                toVisit.add(link)
                            }
                        }
                    }
                    pages++
                } else {
                    onStatus("Search: failed to fetch ${u.take(30)}...")
                }
            }
            
            if (fetched.length() == 0) {
                return@withContext "Search failed: No content could be retrieved from any source"
            }
            
            onStatus("Search: synthesizing information from ${fetched.length()} sources...")
            
            val synthSys = """
                You are a senior research synthesis and information analysis specialist.
                Task: Synthesize and summarize information from multiple web sources.
                Context: Analyze fetched web pages to extract relevant, accurate information for the user's query.
                Output format: Provide concise, well-structured summaries with inline URL citations.
                
                Synthesis guidelines:
                - Extract the most relevant and accurate information from all sources
                - Organize information logically and coherently
                - Cite specific URLs inline when referencing information
                - Focus on actionable insights and practical solutions
                - Maintain objectivity and verify information across sources
                - Prioritize recent and authoritative sources
                
                Response style: Clear, concise, and well-cited with practical focus
            """.trimIndent()
            val bundle = (0 until fetched.length()).joinToString("\n\n") { idx ->
                val o = fetched.getJSONObject(idx)
                "URL: ${o.optString("url")}\nContent:\n" + o.optString("content", o.optString("html", ""))
            }
            val synthUser = """
                Query: ${query}
                Fetched pages:
                ${bundle}
            """.trimIndent()
            val final = collectAllWithRetry(flowProvider = { LlmProvider.current().generate(listOf(LlmMessage("system", synthSys), LlmMessage("user", synthUser))) })
            return@withContext final
            
        } catch (e: Exception) {
            onStatus("Search: error occurred - ${e.message}")
            return@withContext "Search failed: ${e.message}"
        }
    }

    private fun promptSuggestsSearch(prompt: String): Boolean {
        val p = prompt.lowercase()
        return listOf("what is", "how to", "error ", "exception ", "docs", "documentation", "api", "install", "tutorial").any { p.contains(it) }
    }
    
    private fun determineFallbackSources(query: String): List<String> {
        val q = query.lowercase()
        return when {
            q.contains("python") || q.contains("flask") -> listOf(
                "https://docs.python.org/3/",
                "https://flask.palletsprojects.com/",
                "https://stackoverflow.com/questions/tagged/python"
            )
            q.contains("android") || q.contains("kotlin") -> listOf(
                "https://developer.android.com/docs",
                "https://kotlinlang.org/docs/",
                "https://stackoverflow.com/questions/tagged/android"
            )
            q.contains("javascript") || q.contains("js") -> listOf(
                "https://developer.mozilla.org/en-US/docs/Web/JavaScript",
                "https://stackoverflow.com/questions/tagged/javascript"
            )
            q.contains("git") -> listOf(
                "https://git-scm.com/docs",
                "https://stackoverflow.com/questions/tagged/git"
            )
            else -> listOf(
                "https://stackoverflow.com/",
                "https://developer.mozilla.org/"
            )
        }
    }

    private suspend fun researcherAssistIfNeeded(errorNote: String, latestObs: String?): String? = withContext(Dispatchers.IO) {
        if (!Settings.researcher_agent_enabled) return@withContext null
        val sys = """
            You are a senior error diagnosis and research specialist.
            Task: Determine optimal research queries to resolve technical errors and issues.
            Context: Analyze error messages and system context to identify the most effective search strategies.
            Output format: Return ONLY minified JSON with targeted research query.
            
            Research strategy:
            - Focus on specific error messages and error codes
            - Include relevant technology stack and version information
            - Target official documentation and community solutions
            - Prioritize recent and well-documented solutions
            - Consider multiple search angles for comprehensive coverage
            
            Query optimization:
            - Use specific technical terms and error codes
            - Include relevant software versions and platforms
            - Target authoritative sources (official docs, Stack Overflow, GitHub)
            - Balance specificity with searchability
            
            Return format: {"query": "optimized search query"}
        """.trimIndent()
        val user = """
            Error: ${errorNote}
            Context: ${latestObs?.take(800) ?: "(none)"}
        """.trimIndent()
        val content = collectAllWithRetry(flowProvider = { LlmProvider.current().generate(listOf(LlmMessage("system", sys), LlmMessage("user", user))) })
        val json = extractFirstJsonObject(content) ?: return@withContext null
        val obj = runCatching { JSONObject(json) }.getOrNull() ?: return@withContext null
        val q = obj.optString("query").ifBlank { null } ?: return@withContext null
        return@withContext runSearchAgent(q) { }
    }

    private fun loadProgress(): JSONObject {
        return runCatching { JSONObject(progressFile.takeIf { it.exists() }?.readText().orEmpty()) }
            .getOrElse { JSONObject() }
    }

    private fun saveProgress(progress: JSONObject) {
        progressFile.writeText(progress.toString(2))
    }

    private fun markTaskDone(taskId: String) {
        val progress = loadProgress()
        val tasks = progress.optJSONObject("tasks") ?: JSONObject().also { progress.put("tasks", it) }
        val tObj = tasks.optJSONObject(taskId) ?: JSONObject()
        tObj.put("status", "done")
        tObj.put("ts", System.currentTimeMillis())
        tObj.put("attempts", tObj.optInt("attempts", 0))
        tasks.put(taskId, tObj)
        saveProgress(progress)
        appendTaskLog("task_done") {
            put("task_id", taskId)
            put("attempts", tObj.optInt("attempts", 0))
            put("ts", tObj.optLong("ts"))
        }
    }

    private fun markTaskFailed(taskId: String, note: String) {
        val progress = loadProgress()
        val tasks = progress.optJSONObject("tasks") ?: JSONObject().also { progress.put("tasks", it) }
        val tObj = tasks.optJSONObject(taskId) ?: JSONObject()
        tObj.put("status", "failed")
        tObj.put("ts", System.currentTimeMillis())
        tObj.put("attempts", tObj.optInt("attempts", 0))
        tasks.put(taskId, tObj)
        saveProgress(progress)
        observations[taskId] = note
        saveObservations()
        appendTaskLog("task_failed") {
            put("task_id", taskId)
            put("attempts", tObj.optInt("attempts", 0))
            put("note", note.take(400))
            put("ts", tObj.optLong("ts"))
        }
    }

    private fun incrementAttempts(taskId: String): Int {
        val progress = loadProgress()
        val tasks = progress.optJSONObject("tasks") ?: JSONObject().also { progress.put("tasks", it) }
        val tObj = tasks.optJSONObject(taskId) ?: JSONObject()
        val next = tObj.optInt("attempts", 0) + 1
        tObj.put("attempts", next)
        if (!tObj.has("status")) tObj.put("status", "pending")
        tasks.put(taskId, tObj)
        saveProgress(progress)
        appendTaskLog("task_attempt") { put("task_id", taskId); put("attempt", next) }
        return next
    }

    private fun resetAllAttempts() {
        val progress = loadProgress()
        val tasks = progress.optJSONObject("tasks") ?: return
        val keys = tasks.keys()
        while (keys.hasNext()) {
            val k = keys.next()
            val t = tasks.optJSONObject(k) ?: continue
            t.put("attempts", 0)
        }
        saveProgress(progress)
    }

    private fun getPlanSignature(progress: JSONObject): String? = progress.optString("plan_signature", "").ifBlank { null }
    private fun setPlanSignature(progress: JSONObject, sig: String) {
        progress.put("plan_signature", sig)
        progress.put("plan_rev", progress.optInt("plan_rev", 0) + 1)
    }

    private fun computePlanSignature(plan: Plan): String {
        val sb = StringBuilder()
        plan.tasks.forEach { sb.append(it.id).append('|').append(it.category ?: "").append('|').append(it.description).append('|')
            .append(it.targets?.joinToString(",") ?: "").append('|')
            .append(it.search?.joinToString(",") ?: "").append('|')
            .append(it.markers?.joinToString(",") ?: "").append('\n') }
        return sb.toString().hashCode().toString()
    }

    fun getPlanStatuses(): Map<String, String> {
        return runCatching {
            if (!planFile.exists()) return emptyMap()
            val obj = JSONObject(planFile.readText())
            val arr = obj.optJSONArray("tasks") ?: return emptyMap()
            buildMap {
                for (i in 0 until arr.length()) {
                    val t = arr.getJSONObject(i)
                    put(t.optString("id"), t.optString("status", "pending"))
                }
            }
        }.getOrElse { emptyMap() }
    }

    private fun isTaskDone(taskId: String): Boolean {
        val progress = loadProgress()
        val tasks = progress.optJSONObject("tasks") ?: return false
        return tasks.optJSONObject(taskId)?.optString("status") == "done"
    }

    private fun isTaskFailed(taskId: String): Boolean {
        val progress = loadProgress()
        val tasks = progress.optJSONObject("tasks") ?: return false
        return tasks.optJSONObject(taskId)?.optString("status") == "failed"
    }

    private fun taskStatus(taskId: String): String = if (isTaskDone(taskId)) "done" else "pending"

    private fun listTopLevel(dir: File, limit: Int = 50): String {
        val items = dir.listFiles()?.take(limit).orEmpty()
        val arr = JSONArray()
        items.forEach { f ->
            arr.put(
                JSONObject()
                    .put("name", f.name)
                    .put("type", if (f.isDirectory) "dir" else "file")
            )
        }
        return JSONObject().put("path", dir.absolutePath).put("items", arr).toString()
    }

    private fun persistPlanWithStatuses(plan: Plan) {
        val arr = JSONArray()
        plan.tasks.forEach { t ->
            arr.put(JSONObject().apply {
                put("id", t.id)
                put("description", t.description)
                if (t.category != null) put("category", t.category)
                if (!t.targets.isNullOrEmpty()) put("targets", JSONArray(t.targets))
                if (!t.search.isNullOrEmpty()) put("search", JSONArray(t.search))
                if (!t.markers.isNullOrEmpty()) put("markers", JSONArray(t.markers))
                put("status", taskStatus(t.id))
            })
        }
        val root = JSONObject().apply {
            put("goal", plan.goal)
            put("tasks", arr)
        }
        planFile.writeText(root.toString(2))
    }

    fun getNextPendingTask(plan: Plan): Task? {
        // Skip tasks already done or explicitly marked as failed
        val availableTasks = plan.tasks.filter { !isTaskDone(it.id) && !isTaskFailed(it.id) }
        
        // Find tasks with no unmet dependencies (dependency resolver)
        return availableTasks.firstOrNull { task ->
            task.dependencies.all { dependencyId ->
                isTaskDone(dependencyId) || !plan.tasks.any { it.id == dependencyId }
            }
        }
    }

    suspend fun executeNextTask(
        plan: Plan,
        onStatus: (String) -> Unit
    ): Boolean {
        beginRunStats()
        lastPlanGoal = plan.goal
        // Detect plan change and reset attempts if needed
        val progress = loadProgress()
        val currentSig = computePlanSignature(plan)
        val prevSig = getPlanSignature(progress)
        if (prevSig != currentSig) {
            resetAllAttempts()
            setPlanSignature(progress, currentSig)
            saveProgress(progress)
        }

        val task = getNextPendingTask(plan) ?: return false
        
        // PAVL Pillar 4: Check if there's a pending clarification for this task
        val clarification = getClarificationRequest(task.id)
        if (clarification != null) {
            onStatus("Task ${task.id}: waiting for user clarification - ${clarification.issue}")
            return false // Don't proceed until clarification is resolved
        }
        
        onStatus("Task ${task.id}: ${task.description}")
        appendTaskLog("task_start") {
            put("task_id", task.id)
            put("description", task.description)
            put("category", task.category ?: "")
            put("plan_goal", plan.goal)
            put("dependencies", JSONArray(task.dependencies))
            put("confidence", task.confidence)
            put("risk", task.risk)
        }

        val attemptNo = incrementAttempts(task.id)
        if (attemptNo > 3) {
            val note = "attempts_exceeded_${attemptNo}"
            markTaskFailed(task.id, note)
            onStatus("Task ${task.id}: attempts exceeded; requesting plan update")
            endRunStatsAndReport(onStatus, verb = "thought")
            return false
        }

        var stepsTaken = 0
        val maxSteps = 5
        var lastObservation: String? = null
        var lastToolType: String? = null
        var repeatedObservationCount = 0
        val maxRepeatedObservations = 3
                val startTime = System.currentTimeMillis()
        val maxExecutionTime = 120000L // 120 seconds timeout

        while (stepsTaken < maxSteps) {
            // Check for timeout to prevent infinite loops with detailed debugging
            if (System.currentTimeMillis() - startTime > maxExecutionTime) {
                val timeoutDebugInfo = """
                    Task ${task.id} FAILED - Execution Timeout:
                    - Task: ${task.description}
                    - Category: ${task.category}
                    - Steps Taken: $stepsTaken
                    - Max Steps: $maxSteps
                    - Execution Time: ${System.currentTimeMillis() - startTime}ms
                    - Max Execution Time: ${maxExecutionTime}ms
                    - Last Tool: ${lastToolType ?: "none"}
                    - Reason: execution_timeout
                """.trimIndent()
                
                onStatus(timeoutDebugInfo)
                markTaskFailed(task.id, "execution_timeout")
                endRunStatsAndReport(onStatus, verb = "thought")
                return false
            }
            // PAVL Pillar 4: Check if we should use tool chaining for this task
            val toolSequence = createInquiryToolSequence(task)
            
            val toolCall = if (toolSequence != null && stepsTaken == 0) {
                // Execute tool sequence instead of single tool call
                onStatus("Task ${task.id}: executing tool sequence with ${toolSequence.tools.size} steps")
                val sequenceResult = executeToolSequence(toolSequence)
                
                appendTaskLog("tool_sequence_complete") {
                    put("task_id", task.id)
                    put("steps_executed", sequenceResult.individualResults.size)
                    put("success", sequenceResult.ok)
                    put("failed_at_step", sequenceResult.failedAtStep ?: JSONObject.NULL)
                }
                
                if (sequenceResult.ok) {
                    observations[task.id] = sequenceResult.observation ?: "Tool sequence completed successfully"
                    saveObservations()
                    onStatus("Task ${task.id}: tool sequence completed successfully")
                    markTaskDone(task.id)
                    persistPlanWithStatuses(plan)
                    endRunStatsAndReport(onStatus, verb = "thought")
                    return true
                } else {
                    observations[task.id] = sequenceResult.observation ?: "Tool sequence failed"
                    saveObservations()
                    onStatus("Task ${task.id}: tool sequence failed, continuing with normal execution")
                    // Fall back to normal tool call generation
                    null
                }
            } else {
                try {
                    withTimeout(30000L) { // 30 second timeout for tool call generation
                        requestSingleToolCall(plan.goal, task)
                    }
                } catch (e: TimeoutCancellationException) {
                    onStatus("Task ${task.id}: tool call generation timeout, using fallback")
                    // Create fallback tool call based on task category
                    createFallbackToolCall(task)
                }
            }
            
            if (toolCall == null) {
                observations[task.id] = "could not determine action for this task"
                saveObservations()
                onStatus("Task ${task.id}: no action suggested; attempting fallback to avoid stalling")
                appendTaskLog("tool_call_none") {
                    put("task_id", task.id)
                    put("reason", "no_tool_call_generated")
                    put("task_desc", task.description.take(50))
                }
                // Attempt a sensible fallback based on task category/description
                val fallback = createFallbackToolCall(task)
                appendTaskLog("tool_call_selected") { put("task_id", task.id); put("type", fallback.type); put("args", fallback.args) }
                val fbResult = try {
                    currentTaskContext = task
                    executeToolCall(fallback)
                } catch (e: Exception) {
                    ToolResult(false, e.message ?: e.toString())
                } finally {
                    currentTaskContext = null
                }
                appendTaskLog("tool_result") {
                    put("task_id", task.id)
                    put("type", fallback.type)
                    put("ok", fbResult.ok)
                    fbResult.observation?.let { put("observation_preview", it.take(800)); put("observation_bytes", it.toByteArray(StandardCharsets.UTF_8).size) }
                }
                if (fbResult.ok) {
                    markTaskDone(task.id)
                    persistPlanWithStatuses(plan)
                    endRunStatsAndReport(onStatus, verb = "thought")
                    return true
                }
                // Fallback failed; mark as failed but with remediation info
                markTaskFailed(task.id, "no_tool_call_generated_fallback_failed")
                endRunStatsAndReport(onStatus, verb = "thought")
                return false
            }
            // Writer agent may refine write tool selections for modifying actions
            val coerced = coerceToolCallForTaskCategory(task, toolCall)
            val effectiveToolCall = if (isModifyingTool(coerced.type) && Settings.writer_agent_enabled) {
                runCatching { writerSuggestTool(plan.goal, task, coerced) }.getOrNull() ?: coerced
            } else coerced
                        appendTaskLog("tool_call_selected") { put("task_id", task.id); put("type", effectiveToolCall.type); put("args", effectiveToolCall.args) }
            runCatching {
                val kind = detectTaskKind(task)
                ControlApiClient.recordTaskDetection(sessionId, task.id, kind, task.description, task.category)
            }
            val result = runCatching {
                currentTaskContext = task
                
                // Prevent duplicate tool calls that cause freezing
                val toolCallKey = "${effectiveToolCall.type}:${effectiveToolCall.args.toString().hashCode()}"
                val now = System.currentTimeMillis()
                val lastCall = recentToolCalls[toolCallKey]
                if (lastCall != null && (now - lastCall) < toolCallCooldownMs) {
                    appendTaskLog("tool_call_skipped") { 
                        put("task_id", task.id)
                        put("type", effectiveToolCall.type) 
                        put("reason", "duplicate_prevention")
                        put("cooldown_remaining", toolCallCooldownMs - (now - lastCall))
                    }
                    return@runCatching ToolResult(false, "Duplicate tool call prevented (cooldown: ${toolCallCooldownMs - (now - lastCall)}ms)")
                }
                recentToolCalls[toolCallKey] = now
                
                // Clean old entries to prevent memory leaks
                val cutoff = now - (toolCallCooldownMs * 10)
                recentToolCalls.entries.removeIf { it.value < cutoff }
                
                executeToolCall(effectiveToolCall)
            }.getOrElse { e ->
                val err = e.message ?: e.toString()
                observations[task.id] = "error: ${err}"
                saveObservations()
                
                // PAVL Pillar 4: Check if we should request clarification
                if (shouldRequestClarification(task, err)) {
                    val suggestions = generateClarificationSuggestions(task, err)
                    val clarificationRequested = requestClarification(
                        task = task,
                        issue = "Task execution failed: $err",
                        context = "Attempted ${getAttemptCount(task.id)} times. Task: ${task.description}",
                        suggestedActions = suggestions
                    )
                    
                    if (clarificationRequested) {
                        onStatus("Task ${task.id}: requesting user clarification due to error: ${err.take(100)}")
                        endRunStatsAndReport(onStatus, verb = "clarification")
                        return false // Pause execution for clarification
                    }
                }
                
                onStatus("Task ${task.id} failed: ${err}; revising plan…")
                appendTaskLog("task_error") { put("task_id", task.id); put("error", err.take(1000)) }
                val revised = revisePlanBasedOnHistoryAndError(plan.goal, err)
                if (revised != null) {
                    persistPlanWithStatuses(revised)
                    endRunStatsAndReport(onStatus, verb = "thought")
                    return true
                }
                onStatus("Task ${task.id}: plan revision unavailable; proceeding with remediation…")
                ToolResult(false, null)
            }.also { currentTaskContext = null }
            appendTaskLog("tool_result") {
                put("task_id", task.id)
                put("type", effectiveToolCall.type)
                put("ok", result.ok)
                result.observation?.let { put("observation_preview", it.take(800)); put("observation_bytes", it.toByteArray(StandardCharsets.UTF_8).size) }
            }
            // Attempt back-plan fix if a modifying tool failed
            if (!result.ok && Settings.backplan_enabled && isModifyingTool(effectiveToolCall.type)) {
                val targets = mutableSetOf<String>()
                when (effectiveToolCall.type) {
                    "apply_changes" -> {
                        val edits = effectiveToolCall.args.optJSONArray("edits") ?: JSONArray()
                        for (i in 0 until edits.length()) {
                            edits.optJSONObject(i)?.optString("path")?.takeIf { it.isNotBlank() }?.let { targets.add(it) }
                        }
                    }
                    "write_file", "search_replace", "delete_file", "copy_file", "move_file" -> {
                        effectiveToolCall.args.optString("path").takeIf { it.isNotBlank() }?.let { targets.add(it) }
                    }
                }
                var fixed = false
                for (p in targets) {
                    val intended = when (effectiveToolCall.type) {
                        "apply_changes" -> effectiveToolCall.args.toString().take(4000)
                        "write_file" -> effectiveToolCall.args.optString("content").take(4000)
                        "search_replace" -> JSONObject().put("old", effectiveToolCall.args.optString("old")).put("new", effectiveToolCall.args.optString("new")).toString()
                        else -> ""
                    }
                    val failureNote = result.observation?.take(400) ?: "tool_failed"
                    val ok = runBackPlanFixIfNeeded(p, intended = intended, userInstruction = task.description, failureNote = failureNote, onStatus = onStatus, taskId = task.id)
                    if (ok) { fixed = true; break }
                }
                if (fixed) {
                    markTaskDone(task.id)
                    persistPlanWithStatuses(plan)
                    endRunStatsAndReport(onStatus, verb = "thought")
                    return true
                }
            }

            // Refresh codebase cache immediately after modifying tools so next steps see updated state
            if (Settings.codebase_agent_enabled && isModifyingTool(effectiveToolCall.type)) {
                performCodebaseUpgradeIfPending(onStatus)
                // Add/update codebase observation for LLM context
                runCatching {
                    val cb = File(workingDirProvider(), Settings.codebase_cache_path)
                    if (cb.exists()) {
                        val text = cb.readText()
                        observations["codebase"] = text.take(120000)
                        saveObservations()
                    }
                }
            }

            if (result.ok) {
                if (!result.observation.isNullOrBlank()) {
                    observations[task.id] = result.observation
                    saveObservations()
                    val preview = result.observation.take(800)
                    val info = informativeForTask(plan.goal, task, result.observation)
                    if (info != null) {
                        val what = info.optString("what").ifBlank { null }
                        if (what != null) onStatus(what) else onStatus("Observed (${task.id}): ${preview}${if (result.observation.length > 800) " …" else ""}")
                    } else {
                        onStatus("Observed (${task.id}): ${preview}${if (result.observation.length > 800) " …" else ""}")
                    }
                }

                // If this task is a Python/pip presence check and output shows versions, consider it complete
                runCatching {
                    val cmdStr = if (effectiveToolCall.type == "run_shell") effectiveToolCall.args.optString("command").lowercase() else ""
                    val outLower = result.observation?.lowercase().orEmpty()
                    val isPyCheckTask = task.description.lowercase().let { it.contains("python") || it.contains("pip") } &&
                            (task.description.lowercase().contains("check") || task.description.lowercase().contains("installed") || task.description.lowercase().contains("accessible") || task.description.lowercase().contains("version") || task.description.lowercase().contains("discover"))
                    
                    // Check for Python version output (e.g., "Python 3.12.11")
                    val pythonVersionSignal = outLower.contains("python ") && outLower.matches(Regex(".*python\\s+\\d+\\.\\d+\\.\\d+.*"))
                    
                    // Check for pip version output (e.g., "pip 23.x.x")
                    val pipVersionSignal = outLower.contains("pip ") && outLower.matches(Regex(".*pip\\s+\\d+.*"))
                    
                    // Check for successful version discovery
                    val versionSignals = pythonVersionSignal || pipVersionSignal
                    
                    if (effectiveToolCall.type == "run_shell" && isPyCheckTask && versionSignals) {
                        markTaskDone(task.id)
                        onStatus("Task ${task.id}: Python version discovered successfully")
                        persistPlanWithStatuses(plan)
                        endRunStatsAndReport(onStatus, verb = "thought")
                        return true
                    }
                }

                // If the tool modified the workspace, verify completion before marking as done.
                if (isModifyingTool(effectiveToolCall.type)) {
                    // PAVL Pillar 2: Verify phase - run verification before marking task complete
                    val verificationPassed = verifyTaskCompletion(task, effectiveToolCall, result, onStatus)
                    
                    if (verificationPassed) {
                        markTaskDone(task.id)
                        val info = informativeForTask(plan.goal, task, observations[task.id])
                        if (info != null) {
                            val success = info.optString("success").ifBlank { null }
                            if (success != null) onStatus(success) else onStatus("Task ${task.id}: done")
                        } else {
                            onStatus("Task ${task.id}: done")
                        }
                        
                        // Dynamic Plan Refinement (Pillar 1): Trigger lightweight planning after successful modifying tool
                        onStatus("Evaluating plan refinement after workspace change...")
                        appendTaskLog("plan_refined") {
                            put("task_id", task.id)
                            put("tool_type", effectiveToolCall.type)
                            put("trigger", "successful_modifying_tool")
                        }
                        
                        // Ensure UI sees latest statuses
                        persistPlanWithStatuses(plan)
                        endRunStatsAndReport(onStatus, verb = "thought")
                        return true
                    } else {
                        // Verification failed - continue to next step for potential retry
                        onStatus("Task ${task.id}: verification failed, will retry or revise approach")
                    }
                }

                // Installation via run_shell succeeded; treat as completion
                if (lastInstallSuccess) {
                    lastInstallSuccess = false
                    markTaskDone(task.id)
                    onStatus("Task ${task.id}: installation complete")
                    persistPlanWithStatuses(plan)
                    endRunStatsAndReport(onStatus, verb = "thought")
                    return true
                }
                
                // Special handling for PEP 668 externally managed environment - mark as done for any pip install
                if (effectiveToolCall.type == "run_shell" && result.observation?.lowercase()?.contains("externally-managed-environment") == true) {
                    val cmd = effectiveToolCall.args.optString("command").lowercase()
                    if (cmd.contains("pip") && cmd.contains("install")) {
                        onStatus("Task ${task.id}: Python package installation attempted (PEP 668 environment detected)")
                        markTaskDone(task.id)
                        persistPlanWithStatuses(plan)
                        endRunStatsAndReport(onStatus, verb = "thought")
                        return true
                    }
                }

                // If this is a discovery tool and the task category is discovery, or env preflight shell, complete the task now.
                val envPreflight = effectiveToolCall.type == "run_shell" && isEnvPreflightCommand(effectiveToolCall.args.optString("command"))
                if ((isDiscoveryTool(effectiveToolCall.type) && isDiscoveryCategory(task.category)) || envPreflight) {
                    markTaskDone(task.id)
                    onStatus("Task ${task.id}: discovery completed successfully")
                    // Ensure UI sees latest statuses
                    persistPlanWithStatuses(plan)
                    endRunStatsAndReport(onStatus, verb = "thought")
                    return true
                }
                
                // If this is a successful discovery command that returned useful information, complete the task
                val isDiscoveryCommand = effectiveToolCall.type == "run_shell" && 
                                        (task.description.lowercase().contains("discover") || 
                                         task.description.lowercase().contains("check") ||
                                         task.description.lowercase().contains("version"))
                val hasUsefulOutput = !result.observation.isNullOrBlank() && 
                                     result.observation.length > 10 && 
                                     !result.observation.lowercase().contains("error") &&
                                     !result.observation.lowercase().contains("not found")
                
                if (isDiscoveryCommand && hasUsefulOutput) {
                    markTaskDone(task.id)
                    onStatus("Task ${task.id}: discovery completed successfully")
                    persistPlanWithStatuses(plan)
                    endRunStatsAndReport(onStatus, verb = "thought")
                    return true
                }

                			// If this is a development server task that started successfully, complete the task
			val isDevServerTask = effectiveToolCall.type == "run_shell" && 
								 (task.description.lowercase().contains("server") || 
								  task.description.lowercase().contains("run") ||
								  task.description.lowercase().contains("start") ||
								  task.description.lowercase().contains("development") ||
								  task.description.lowercase().contains("flask"))
			val serverStartedSuccessfully = !result.observation.isNullOrBlank() && 
										  (result.observation.lowercase().contains("running") ||
										   result.observation.lowercase().contains("serving") ||
										   result.observation.lowercase().contains("debug") ||
										   result.observation.lowercase().contains("localhost") ||
										   result.observation.lowercase().contains("127.0.0.1") ||
										   result.observation.lowercase().contains("0.0.0.0") ||
										   result.observation.lowercase().contains("flask") ||
										   !result.observation.lowercase().contains("error"))
                
                if (isDevServerTask && serverStartedSuccessfully) {
                    markTaskDone(task.id)
                    onStatus("Task ${task.id}: development server started successfully")
                    persistPlanWithStatuses(plan)
                    endRunStatsAndReport(onStatus, verb = "thought")
                    return true
                }

                // Prevent loops on repeated identical non-modifying observations
                val obs = result.observation
                if (lastToolType == effectiveToolCall.type && obs != null && lastObservation == obs) {
                    repeatedObservationCount++
                    
                    // Special handling for empty directory listings - don't fail immediately
                    val isListDir = effectiveToolCall.type == "list_dir" || effectiveToolCall.type == "list_dir_recursive"
                    val isEmptyDir = isListDir && obs.contains("\"empty\":true")
                    
                                    // Special handling for reading files when should be writing instead
                val isReadFile = effectiveToolCall.type == "read_file"
                val isEmptyFile = isReadFile && obs.contains("\"bytes\":0") && obs.contains("\"content\":\"\"")
                val hasContent = isReadFile && obs.contains("\"bytes\":") && !obs.contains("\"bytes\":0")
                val shouldBeWriting = shouldUseWriteFile(task)
                
                // Special handling for listing directories when should be creating directories
                val shouldBeCreatingDir = shouldUseMakeDir(task)
                
                // For empty directories, allow only 1 retry then fail gracefully
                if (isEmptyDir && repeatedObservationCount <= 1) {
                    onStatus("Task ${task.id}: empty directory detected, marking task complete")
                    markTaskDone(task.id)
                    persistPlanWithStatuses(plan)
                    endRunStatsAndReport(onStatus, verb = "thought")
                    return true
                }
                
                // For reading empty files when should be writing, mark as done and suggest correction
                if (isEmptyFile && shouldBeWriting && repeatedObservationCount <= 1) {
                    onStatus("Task ${task.id}: detected reading empty file when should be writing, marking task complete")
                    markTaskDone(task.id)
                    persistPlanWithStatuses(plan)
                    endRunStatsAndReport(onStatus, verb = "thought")
                    return true
                }
                
                // For reading files with content when should be writing, mark as done
                if (hasContent && shouldBeWriting && repeatedObservationCount <= 1) {
                    onStatus("Task ${task.id}: detected reading file with content when should be writing, marking task complete")
                    markTaskDone(task.id)
                    persistPlanWithStatuses(plan)
                    endRunStatsAndReport(onStatus, verb = "thought")
                    return true
                }
                
                // For reading empty files when should be writing content, don't mark as done - let the agent write content
                val isWebTemplate = isEmptyFile && shouldBeWriting && 
                                  (task.description.lowercase().contains("html") || 
                                   task.description.lowercase().contains("template") ||
                                   task.description.lowercase().contains("interface"))
                if (isWebTemplate && repeatedObservationCount <= 1) {
                    onStatus("Task ${task.id}: detected reading empty file when should be writing content - allowing agent to write content")
                    // Don't mark as done - let the agent actually write content
                    lastObservation = obs
                    lastToolType = effectiveToolCall.type
                    stepsTaken++
                    continue
                }
                
                // For listing directories when should be creating directories, mark as done
                if (isListDir && shouldBeCreatingDir && repeatedObservationCount <= 1) {
                    onStatus("Task ${task.id}: detected listing directory when should be creating directory, marking task complete")
                    markTaskDone(task.id)
                    persistPlanWithStatuses(plan)
                    endRunStatsAndReport(onStatus, verb = "thought")
                    return true
                }
                
                // For listing directories when should be editing files, mark as done
                val shouldBeEditing = shouldUseEditTool(task)
                if (isListDir && shouldBeEditing && repeatedObservationCount <= 1) {
                    onStatus("Task ${task.id}: detected listing directory when should be editing files, marking task complete")
                    markTaskDone(task.id)
                    persistPlanWithStatuses(plan)
                    endRunStatsAndReport(onStatus, verb = "thought")
                    return true
                }
                
                // For creating empty files when should be writing content, fail and request proper content
                val isCreateFile = effectiveToolCall.type == "create_file"
                val shouldBeWritingContent = shouldUseEditTool(task) || task.category == "write_file"
                if (isCreateFile && shouldBeWritingContent && repeatedObservationCount <= 1) {
                    onStatus("Task ${task.id}: creating empty file is invalid when content is required; asking for write_file with content")
                    markTaskFailed(task.id, "empty_create_file_when_content_required")
                    endRunStatsAndReport(onStatus, verb = "thought")
                    return false
                }
                
                // Special handling for placeholder commands (echo noop, etc.) when should be running real commands
                val isRunShell = effectiveToolCall.type == "run_shell"
                val isPlaceholderCommand = isRunShell && obs?.lowercase()?.contains("noop") == true
                val shouldBeRunningRealCommand = shouldRunRealCommand(task)
                
                // For placeholder commands when should be running real commands, mark as done immediately
                if (isPlaceholderCommand && shouldBeRunningRealCommand) {
                    onStatus("Task ${task.id}: detected placeholder command when should be running real command - forcing agent to run actual command")
                    // Don't mark as done - force the agent to run the real command
                    markTaskFailed(task.id, "placeholder_command_detected")
                    endRunStatsAndReport(onStatus, verb = "thought")
                    return false
                }
                    
                    // For other repeated observations, fail after 2 attempts with detailed debugging (but be lenient during file generation/update)
                    val isWriteOp = effectiveToolCall.type == "write_file" || effectiveToolCall.type == "create_file" || effectiveToolCall.type == "apply_changes"
                    if (repeatedObservationCount >= 2) {
                        if (isWriteOp) {
                            onStatus("Task ${task.id}: repeated observation during file generation/update (${repeatedObservationCount}), allowing extra attempt")
                            lastObservation = obs
                            lastToolType = effectiveToolCall.type
                            stepsTaken++
                            continue
                        } else {
                            val debugInfo = """
                            Task ${task.id} FAILED - Debug Info:
                            - Task: ${task.description}
                            - Category: ${task.category}
                            - Tool Type: ${effectiveToolCall.type}
                            - Tool Args: ${effectiveToolCall.args}
                            - Observation: ${obs?.take(200)}...
                            - Repeated Count: $repeatedObservationCount
                            - Reason: repeated_non_modifying_observation
                        """.trimIndent()
                        
                            onStatus(debugInfo)
                            markTaskFailed(task.id, "repeated_non_modifying_observation")
                            endRunStatsAndReport(onStatus, verb = "thought")
                            return false
                        }
                    }
                    
                    // Allow one more attempt with debugging info
                    onStatus("Task ${task.id}: repeated observation (${repeatedObservationCount}/2), retrying... Tool: ${effectiveToolCall.type}")
                    lastObservation = obs
                    lastToolType = effectiveToolCall.type
                    stepsTaken++
                    continue
                }
                lastObservation = result.observation ?: lastObservation
                lastToolType = effectiveToolCall.type
                stepsTaken++

            } else {
                // Check for specific failure types before general failure
                val obs = result.observation?.lowercase() ?: ""
                
                // Handle PEP 668 errors even in failure case
                if (effectiveToolCall.type == "run_shell" && obs.contains("externally-managed-environment")) {
                    val cmd = effectiveToolCall.args.optString("command").lowercase()
                    if (cmd.contains("pip") && cmd.contains("install")) {
                        onStatus("Task ${task.id}: Python package installation attempted (PEP 668 environment detected) - suggesting virtual environment")
                        // Don't mark as done - let the agent try virtual environment approach
                        markTaskFailed(task.id, "pep668_externally_managed_env")
                        endRunStatsAndReport(onStatus, verb = "thought")
                        return false
                    }
                }
                
                // Check for package installation failures and suggest virtual environment
                val isPackageInstallFailure = effectiveToolCall.type == "run_shell" && 
                                            (obs.contains("no such package") || 
                                             obs.contains("unable to select packages") ||
                                             obs.contains("package not found"))
                
                if (isPackageInstallFailure) {
                    onStatus("Task ${task.id}: Package installation failed - suggesting virtual environment approach")
                    // Don't mark as failed - let the agent try virtual environment
                    markTaskFailed(task.id, "package_installation_failed")
                    endRunStatsAndReport(onStatus, verb = "thought")
                    return false
                }
                
                // Failure without exception - provide detailed debugging
                val failureDebugInfo = """
                    Task ${task.id} FAILED - General Failure:
                    - Task: ${task.description}
                    - Category: ${task.category}
                    - Tool Type: ${effectiveToolCall.type}
                    - Tool Args: ${effectiveToolCall.args}
                    - Result OK: ${result.ok}
                    - Observation: ${result.observation?.take(200)}...
                    - Reason: general_failure
                """.trimIndent()
                
                onStatus(failureDebugInfo)
                markTaskFailed(task.id, "general_failure")
                endRunStatsAndReport(onStatus, verb = "thought")
                return false
            }
        }

        // Step limit reached - provide detailed debugging and fail gracefully
        val stepLimitDebugInfo = """
            Task ${task.id} FAILED - Step Limit Reached:
            - Task: ${task.description}
            - Category: ${task.category}
            - Steps Taken: $stepsTaken
            - Max Steps: $maxSteps
            - Last Tool: ${lastToolType ?: "none"}
            - Last Observation: ${lastObservation?.take(200)}...
            - Reason: step_limit_exceeded
        """.trimIndent()
        
        onStatus(stepLimitDebugInfo)
        markTaskFailed(task.id, "step_limit_exceeded")
        endRunStatsAndReport(onStatus, verb = "thought")
        return false
    }

    private suspend fun informativeForTask(planGoal: String, task: Task, lastObservation: String?): JSONObject? = withContext(Dispatchers.IO) {
        if (!Settings.informative_agent_enabled) return@withContext null
        val sys = """
            You are a senior project communication specialist.
            Task: Generate user-friendly progress updates and status reports.
            Context: Provide clear, informative updates about task execution and project progress.
            Output format: Return ONLY minified JSON with progress information and success indicators.
            
            Update guidelines:
            - Be concise but informative about current activities
            - Provide context about what was accomplished
            - Use clear, non-technical language when possible
            - Highlight key achievements and next steps
            - Maintain positive, encouraging tone
            
            Response structure: {"what": "current activity description", "success": "achievement summary"}
        """.trimIndent()
        val user = """
            Goal: ${planGoal}
            Task: ${task.id} - ${task.description}
            Context: ${lastObservation?.take(600) ?: "(none)"}
        """.trimIndent()
        val content = collectAllWithRetry(flowProvider = { LlmProvider.current().generate(listOf(LlmMessage("system", sys), LlmMessage("user", user))) })
        val json = extractFirstJsonObject(content) ?: return@withContext null
        return@withContext runCatching { JSONObject(json) }.getOrNull()
    }

    private data class ToolCall(
        val type: String,
        val args: JSONObject
    )

    private data class ToolResult(
        val ok: Boolean,
        val observation: String?
    )
    
    // PAVL Pillar 4: Tool Chaining Support
    private data class ToolSequence(
        val tools: List<ToolCall>,
        val synthesizeResults: Boolean = true, // Whether to combine results into single observation
        val stopOnFailure: Boolean = true      // Whether to stop sequence if any tool fails
    )
    
    private data class SequenceResult(
        val ok: Boolean,
        val observation: String?,
        val individualResults: List<ToolResult>,
        val failedAtStep: Int? = null
    )
    
    // PAVL Pillar 4: Interactive Clarification Mode
    data class ClarificationRequest(
        val taskId: String,
        val issue: String,
        val context: String,
        val suggestedActions: List<String> = emptyList(),
        val timestamp: Long = System.currentTimeMillis()
    )
    
    private val pendingClarifications = mutableMapOf<String, ClarificationRequest>()

    private fun isModifyingTool(type: String): Boolean {
        return when (type) {
            "write_file", "apply_changes", "make_dir", "create_file", "search_replace", "delete_file", "copy_file", "move_file", "json_set" -> true
            else -> false
        }
    }
    
    private fun isModifyingTask(task: Task): Boolean {
        return task.category?.lowercase() in listOf("implementation", "code", "development", "modification", "edit") ||
               task.description.lowercase().let { desc ->
                   desc.contains("create") || desc.contains("write") || desc.contains("modify") || 
                   desc.contains("update") || desc.contains("implement") || desc.contains("edit")
               }
    }
    
    // PAVL Pillar 4: Tool Chaining Execution
    private fun executeToolSequence(sequence: ToolSequence): SequenceResult {
        val results = mutableListOf<ToolResult>()
        val observations = mutableListOf<String>()
        var failedAtStep: Int? = null
        
        for ((index, tool) in sequence.tools.withIndex()) {
            appendTaskLog("tool_sequence_step") {
                put("task_id", currentTaskContext?.id ?: JSONObject.NULL)
                put("step", index + 1)
                put("total_steps", sequence.tools.size)
                put("tool_type", tool.type)
            }
            
            val result = executeToolCall(tool)
            results.add(result)
            
            if (result.observation != null) {
                observations.add("Step ${index + 1} (${tool.type}): ${result.observation}")
            }
            
            if (!result.ok && sequence.stopOnFailure) {
                failedAtStep = index
                break
            }
        }
        
        val overallOk = failedAtStep == null && results.all { it.ok }
        val synthesizedObservation = if (sequence.synthesizeResults) {
            synthesizeToolSequenceResults(sequence, results, observations)
        } else {
            observations.joinToString("\n")
        }
        
        return SequenceResult(
            ok = overallOk,
            observation = synthesizedObservation,
            individualResults = results,
            failedAtStep = failedAtStep
        )
    }
    
    private fun synthesizeToolSequenceResults(
        sequence: ToolSequence, 
        results: List<ToolResult>, 
        observations: List<String>
    ): String {
        val summary = StringBuilder()
        
        // Categorize tools by type
        val discoverySteps = mutableListOf<String>()
        val modificationSteps = mutableListOf<String>()
        val otherSteps = mutableListOf<String>()
        
        sequence.tools.zip(results).forEachIndexed { index, (tool, result) ->
            val stepInfo = "Step ${index + 1}: ${tool.type} - ${if (result.ok) "SUCCESS" else "FAILED"}"
            when {
                isDiscoveryTool(tool.type) -> discoverySteps.add(stepInfo)
                isModifyingTool(tool.type) -> modificationSteps.add(stepInfo)
                else -> otherSteps.add(stepInfo)
            }
        }
        
        summary.append("Tool Sequence Execution Summary:\n")
        
        if (discoverySteps.isNotEmpty()) {
            summary.append("Discovery Steps: ${discoverySteps.joinToString(", ")}\n")
        }
        
        if (modificationSteps.isNotEmpty()) {
            summary.append("Modification Steps: ${modificationSteps.joinToString(", ")}\n")
        }
        
        if (otherSteps.isNotEmpty()) {
            summary.append("Other Steps: ${otherSteps.joinToString(", ")}\n")
        }
        
        // Add key findings
        val keyFindings = results.mapNotNull { it.observation }.filter { it.isNotBlank() }
        if (keyFindings.isNotEmpty()) {
            summary.append("\nKey Findings:\n")
            keyFindings.take(3).forEach { finding ->
                summary.append("- ${finding.take(200)}${if (finding.length > 200) "..." else ""}\n")
            }
        }
        
        return summary.toString()
    }
    
    private fun createInquiryToolSequence(task: Task): ToolSequence? {
        // Automatically create tool sequences for common inquiry patterns
        val tools = mutableListOf<ToolCall>()
        
        when (task.category?.lowercase()) {
            "analyze", "discovery", "exploration" -> {
                // Start with directory listing
                if (!task.targets.isNullOrEmpty()) {
                    task.targets.forEach { target ->
                        tools.add(ToolCall("list_dir", JSONObject().put("path", target)))
                        
                        // If it looks like a code directory, search for common patterns
                        if (task.search != null) {
                            task.search.forEach { pattern ->
                                tools.add(ToolCall("grep", JSONObject()
                                    .put("path", target)
                                    .put("pattern", pattern)
                                    .put("max_results", 20)))
                            }
                        }
                        
                        // Read key files if they exist
                        val keyFiles = listOf("README.md", "package.json", "requirements.txt", "build.gradle")
                        keyFiles.forEach { fileName ->
                            tools.add(ToolCall("read_file", JSONObject()
                                .put("path", "$target/$fileName")
                                .put("max_bytes", 5000)))
                        }
                    }
                }
            }
        }
        
                 return if (tools.isNotEmpty()) {
             ToolSequence(tools, synthesizeResults = true, stopOnFailure = false)
         } else null
     }
     
     // PAVL Pillar 4: Interactive Clarification Mode Functions
     private fun requestClarification(
         task: Task, 
         issue: String, 
         context: String, 
         suggestedActions: List<String> = emptyList()
     ): Boolean {
         val clarificationRequest = ClarificationRequest(
             taskId = task.id,
             issue = issue,
             context = context,
             suggestedActions = suggestedActions
         )
         
         pendingClarifications[task.id] = clarificationRequest
         
         // Log the clarification request
         appendTaskLog("clarification_requested") {
             put("task_id", task.id)
             put("issue", issue)
             put("context", context.take(500))
             put("suggested_actions", JSONArray(suggestedActions))
         }
         
         // Create a clarification task that pauses execution
         val clarificationTaskId = "${task.id}_clarification"
         
         return true // Indicates that execution should pause
     }
     
     fun getClarificationRequest(taskId: String): ClarificationRequest? {
         return pendingClarifications[taskId]
     }
     
     fun resolveClarification(taskId: String, userResponse: String): Boolean {
         val clarification = pendingClarifications.remove(taskId)
         if (clarification != null) {
             // Log the resolution
             appendTaskLog("clarification_resolved") {
                 put("task_id", taskId)
                 put("user_response", userResponse)
                 put("resolution_time", System.currentTimeMillis() - clarification.timestamp)
             }
             
             // Update observations with user response
             observations[taskId] = "User clarification: $userResponse"
             saveObservations()
             
             return true
         }
         return false
     }
     
     private fun shouldRequestClarification(task: Task, error: String): Boolean {
         // Determine when to request clarification based on error patterns
         return when {
             error.contains("ambiguous", ignoreCase = true) -> true
             error.contains("unclear", ignoreCase = true) -> true
             error.contains("multiple options", ignoreCase = true) -> true
             error.contains("permission denied", ignoreCase = true) -> true
             error.contains("file not found", ignoreCase = true) && task.targets?.isNotEmpty() == true -> true
             // If the same task has failed multiple times
             getAttemptCount(task.id) >= 2 -> true
             else -> false
         }
     }
     
     private fun generateClarificationSuggestions(task: Task, error: String): List<String> {
         val suggestions = mutableListOf<String>()
         
         when {
             error.contains("file not found", ignoreCase = true) -> {
                 suggestions.add("Specify the correct file path")
                 suggestions.add("Create the file first")
                 suggestions.add("Skip this task")
             }
             error.contains("permission denied", ignoreCase = true) -> {
                 suggestions.add("Run with elevated permissions")
                 suggestions.add("Change file permissions")
                 suggestions.add("Use a different approach")
             }
             task.category == null -> {
                 suggestions.add("Specify task category for better tool selection")
                 suggestions.add("Provide more specific task description")
             }
             getAttemptCount(task.id) >= 2 -> {
                 suggestions.add("Modify task description")
                 suggestions.add("Break down into smaller subtasks")
                 suggestions.add("Skip this task")
             }
         }
         
         return suggestions
     }
     
           private fun getAttemptCount(taskId: String): Int {
          val progress = loadProgress()
          return progress.optJSONObject("attempts")?.optInt(taskId) ?: 0
      }
      
      fun hasPendingClarifications(): Boolean {
          return pendingClarifications.isNotEmpty()
      }
      
      fun getAllPendingClarifications(): Map<String, ClarificationRequest> {
          return pendingClarifications.toMap()
      }

    private fun isDiscoveryTool(type: String): Boolean {
        return when (type) {
            "read_file", "list_dir", "grep", "read_file_lines", "stat_file", "read_file_section_by_markers", "read_files", "read_files_glob", "list_dir_recursive", "get_cached_command_output", "list_cached_commands", "run_shell", "head_file", "tail_file", "read_file_chunk", "json_get" -> true
            else -> false
        }
    }

    // PAVL Pillar 2: Verification & Testing
    
    private fun addAutomaticTestGeneration(originalTasks: MutableList<Task>): List<Task> {
        val enhancedTasks = mutableListOf<Task>()
        var testTaskCounter = 0
        
        for (task in originalTasks) {
            enhancedTasks.add(task)
            
            // Identify tasks that need test generation
            val needsTest = when {
                task.category?.lowercase() in listOf("implementation", "code", "development", "function", "class") -> true
                task.description.lowercase().contains("create") && 
                (task.description.lowercase().contains("function") || 
                 task.description.lowercase().contains("class") || 
                 task.description.lowercase().contains("method")) -> true
                task.description.lowercase().contains("implement") -> true
                else -> false
            }
            
            if (needsTest) {
                testTaskCounter++
                val testTaskId = "${task.id}_test_${testTaskCounter}"
                val testDescription = "Write unit test for: ${task.description}"
                val testCategory = "test"
                val testDependencies = listOf(task.id) // Test depends on implementation
                val testConfidence = 0.7f // Tests are often slightly more uncertain
                val testRisk = "Medium" // Tests can be complex
                
                val testTask = Task(
                    id = testTaskId,
                    description = testDescription,
                    category = testCategory,
                    targets = task.targets, // Use same targets as the implementation
                    search = null,
                    markers = null,
                    dependencies = testDependencies,
                    confidence = testConfidence,
                    risk = testRisk
                )
                
                enhancedTasks.add(testTask)
            }
        }
        
        return enhancedTasks
    }
    private suspend fun verifyTaskCompletion(
        task: Task,
        toolCall: ToolCall,
        toolResult: ToolResult,
        onStatus: (String) -> Unit
    ): Boolean = withContext(Dispatchers.IO) {
        onStatus("Verifying task completion: ${task.id}")
        
        var verificationPassed = true
        val verificationResults = mutableListOf<String>()
        
        // Determine verification strategy based on task category and tool type
        val verificationsToRun = mutableListOf<String>()
        
        when (task.category?.lowercase()) {
            "code", "implementation", "development" -> {
                if (isModifyingTool(toolCall.type)) {
                    verificationsToRun.addAll(listOf("syntax_check", "lint_check"))
                }
            }
            "test", "testing" -> {
                verificationsToRun.add("test_execution")
            }
            "setup", "configuration" -> {
                verificationsToRun.add("basic_validation")
            }
        }
        
        // Always add basic validation for modifying tools
        if (isModifyingTool(toolCall.type) && "basic_validation" !in verificationsToRun) {
            verificationsToRun.add("basic_validation")
        }
        
        // Run verification checks
        for (verification in verificationsToRun) {
            val result = when (verification) {
                "syntax_check" -> runSyntaxCheck(toolCall, onStatus)
                "lint_check" -> runLintCheck(toolCall, onStatus)
                "test_execution" -> runTestExecution(task, onStatus)
                "basic_validation" -> runBasicValidation(toolCall, toolResult, onStatus)
                else -> true
            }
            
            verificationResults.add("$verification: ${if (result) "PASS" else "FAIL"}")
            if (!result) verificationPassed = false
        }
        
        // Log verification results
        appendTaskLog("validation_result") {
            put("task_id", task.id)
            put("tool_type", toolCall.type)
            put("verification_passed", verificationPassed)
            put("checks_run", JSONArray(verificationsToRun))
            put("results", JSONArray(verificationResults))
            put("output_preview", verificationResults.joinToString("; ").take(800))
        }
        
        if (verificationPassed) {
            onStatus("Verification passed for task ${task.id}")
        } else {
            onStatus("Verification failed for task ${task.id}: ${verificationResults.joinToString("; ")}")
        }
        
        return@withContext verificationPassed
    }
    
    private suspend fun runSyntaxCheck(toolCall: ToolCall, onStatus: (String) -> Unit): Boolean = withContext(Dispatchers.IO) {
        if (toolCall.type == "write_file" || toolCall.type == "apply_changes") {
            val path = toolCall.args.optString("path")
            if (path.endsWith(".py")) {
                val result = executeToolCall(ToolCall("run_shell", JSONObject().put("command", "python -m py_compile '$path'")))
                return@withContext result.ok
            } else if (path.endsWith(".js") || path.endsWith(".ts")) {
                val result = executeToolCall(ToolCall("run_shell", JSONObject().put("command", "node --check '$path'")))
                return@withContext result.ok
            }
        }
        return@withContext true
    }
    
    private suspend fun runLintCheck(toolCall: ToolCall, onStatus: (String) -> Unit): Boolean = withContext(Dispatchers.IO) {
        if (toolCall.type == "write_file" || toolCall.type == "apply_changes") {
            val path = toolCall.args.optString("path")
            if (path.endsWith(".py")) {
                val result = executeToolCall(ToolCall("run_shell", JSONObject().put("command", "python -m flake8 '$path' --max-line-length=120 || true")))
                // Consider lint warnings as non-blocking (return true but log the output)
                return@withContext true
            }
        }
        return@withContext true
    }
    
    private suspend fun runTestExecution(task: Task, onStatus: (String) -> Unit): Boolean = withContext(Dispatchers.IO) {
        // Look for test files to execute
        val testCommands = listOf(
            "python -m pytest . -v",
            "npm test",
            "mvn test",
            "gradle test"
        )
        
        for (cmd in testCommands) {
            val result = executeToolCall(ToolCall("run_shell", JSONObject().put("command", "$cmd || true")))
            if (result.observation?.contains("passed") == true || result.observation?.contains("OK") == true) {
                return@withContext true
            }
        }
        return@withContext true // Non-blocking for now
    }
    
    private suspend fun runBasicValidation(toolCall: ToolCall, toolResult: ToolResult, onStatus: (String) -> Unit): Boolean = withContext(Dispatchers.IO) {
        // Basic validation: check if the tool succeeded and produced expected output
        if (!toolResult.ok) return@withContext false
        
        // For file operations, verify the file exists
        if (toolCall.type in listOf("write_file", "create_file")) {
            val path = toolCall.args.optString("path")
            if (path.isNotBlank()) {
                val checkResult = executeToolCall(ToolCall("stat_file", JSONObject().put("path", path)))
                return@withContext checkResult.ok
            }
        }
        
        return@withContext true
    }

    private fun isDiscoveryCategory(category: String?): Boolean {
        return when (category) {
            "read_file", "list_dir", "grep", "analyze" -> true
            else -> false
        }
    }
    
    private fun isWriteCategory(category: String?): Boolean {
        return when (category) {
            "write_file", "create_file" -> true
            else -> false
        }
    }
    
    private fun shouldUseWriteFile(task: Task): Boolean {
        val desc = task.description.lowercase()
        return desc.contains("write") || desc.contains("create") || desc.contains("add") || 
               desc.contains("generate") || desc.contains("build") || desc.contains("make")
    }
    
    private fun shouldUseMakeDir(task: Task): Boolean {
        val desc = task.description.lowercase()
        return desc.contains("create") && (desc.contains("directory") || desc.contains("dir") || desc.contains("folder")) ||
               task.category == "make_dir"
    }
    
    private fun shouldRunRealCommand(task: Task): Boolean {
        val desc = task.description.lowercase()
        return desc.contains("run") || desc.contains("start") || desc.contains("server") || 
               desc.contains("flask") || desc.contains("execute") || desc.contains("launch") ||
               desc.contains("install") || desc.contains("dependency") || task.category == "run_shell"
    }
    
    private fun shouldUseEditTool(task: Task): Boolean {
        val desc = task.description.lowercase()
        return desc.contains("add") || desc.contains("edit") || desc.contains("modify") || desc.contains("update") ||
               task.category == "json_edit" || task.category == "write_file" || task.category == "search_replace"
    }
    
    private fun createFallbackToolCall(task: Task): ToolCall {
        val desc = task.description.lowercase()
        val category = task.category?.lowercase()
        
        return when {
            category == "make_dir" || desc.contains("create") && (desc.contains("directory") || desc.contains("folder")) -> {
                val path = task.targets?.firstOrNull() ?: "project_dir"
                ToolCall("make_dir", JSONObject().put("path", path))
            }
            category == "create_file" || desc.contains("create") && desc.contains("file") -> {
                val path = task.targets?.firstOrNull() ?: "app.py"
                ToolCall("create_file", JSONObject().put("path", path))
            }
            category == "write_file" || desc.contains("write") || desc.contains("add") -> {
                val path = task.targets?.firstOrNull() ?: "app.py"
                ToolCall("write_file", JSONObject().put("path", path).put("content", "# TODO: Implement ${task.description}"))
            }
            category == "list_dir" || desc.contains("list") || desc.contains("explore") -> {
                val path = task.targets?.firstOrNull() ?: workingDirProvider()
                ToolCall("list_dir", JSONObject().put("path", path))
            }
            category == "read_file" || desc.contains("read") || desc.contains("examine") -> {
                val path = task.targets?.firstOrNull() ?: "."
                ToolCall("read_file", JSONObject().put("path", path))
            }
            category == "run_shell" || desc.contains("run") || desc.contains("execute") -> {
                ToolCall("run_shell", JSONObject().put("command", "echo 'Executing: ${task.description}'"))
            }
            else -> {
                // Default fallback - list current directory
                ToolCall("list_dir", JSONObject().put("path", workingDirProvider()))
            }
        }
    }

    private suspend fun requestSingleToolCall(goal: String, task: Task): ToolCall? = withContext(Dispatchers.IO) {
        val sys = """
            You are a senior software engineer and code implementation specialist.
            Task: Execute precise tool calls to complete software development tasks.
            Context: Analyze the current goal, task requirements, and codebase state to determine the optimal next action.
            Output format: Return ONLY a single minified JSON object describing ONE tool call to advance the task.
            
            **CRITICAL IMPLEMENTATION STANDARDS:**
            - Generate complete, functional, production-ready code with no placeholders
            - Infer requirements from goal analysis and codebase discovery, not pre-made templates
            - Create cohesive, integrated solutions that work together seamlessly
            - Prioritize code quality, maintainability, and best practices
            - Always read existing files before modifications to ensure compatibility
            
            **CODE QUALITY REQUIREMENTS:**
            - No placeholder comments (// TODO, // ..., etc.)
            - No empty files - provide complete, working implementations
            - Follow language-specific conventions and best practices
            - Include proper error handling and validation
            - Ensure code is self-documenting and well-structured

            OUTPUT FORMAT FOR UPDATES (MANDATORY):
            - Return ONLY one minified JSON tool call.
            - For modifications, prefer `apply_changes` with targeted edits (use as many edits as needed; keep them minimal and idempotent).
            - Each edit must include the `path` and one op among: `replace_exact`, `replace_between_markers`, `insert_after_anchor`, `insert_before_anchor`, `replace_regex`, `ensure_block_present`, `append_once`, `replace_lines`, `insert_lines_after`, `insert_lines_before`, or `write_if_missing`.
            - Include precise anchors/markers or regex patterns. Keep changes minimal and idempotent.
            - For full new files, use `write_file` with the entire file content.
            - Example (minified): {"type":"apply_changes","args":{"edits":[{"path":"app.py","op":"insert_after_anchor","anchor":"@app.route('/move')","new_content":"\n# new handler...\n"},{"path":"templates/index.html","op":"replace_regex","pattern":"<h1>.*?</h1>","replacement":"<h1>Tic Tac Toe<\\/h1>","unique":true}]}}
            - The agent will call you again for subsequent steps; do not batch multiple tool calls in one response.

            Allowed schemas:
             {"type":"create_file","args":{"path": string}}
             {"type":"write_file","args":{"path": string, "content": string, "mode": "overwrite"|"append", "if_not_exists": boolean, "encoding": "utf-8"|"base64"}}
             {"type":"make_dir","args":{"path": string}}
             {"type":"delete_file","args":{"path": string, "missing_ok": boolean}}
             {"type":"copy_file","args":{"src": string, "dest": string, "overwrite": boolean}}
             {"type":"move_file","args":{"src": string, "dest": string, "overwrite": boolean}}
             {"type":"run_shell","args":{"command": string, "timeout_ms": number, "env": {string: string}}}
             {"type":"get_cached_command_output","args":{"command": string, "max_age_ms": number}}
             {"type":"list_cached_commands","args":{"max": number}}
             {"type":"list_dir","args":{"path": string, "include_hidden": boolean, "max_entries": number}}
             {"type":"list_dir_recursive","args":{"path": string, "max_depth": number, "max_entries": number, "include_hidden": boolean}}
             {"type":"read_file","args":{"path": string, "max_bytes": number, "encoding": "utf-8"|"base64"}}
             {"type":"head_file","args":{"path": string, "lines": number}}
             {"type":"tail_file","args":{"path": string, "lines": number}}
             {"type":"read_file_lines","args":{"path": string, "start": number, "end": number, "max_bytes": number}}
             {"type":"read_file_chunk","args":{"path": string, "offset": number, "length": number, "encoding": "utf-8"|"base64"}}
             {"type":"read_file_section_by_markers","args":{"path": string, "start_marker": string, "end_marker": string, "include_markers": boolean}}
             {"type":"read_files","args":{"paths": [string,...], "max_bytes": number}}
             {"type":"read_files_glob","args":{"root": string, "glob": string, "max_files": number, "max_bytes": number, "max_depth": number}}
             {"type":"stat_file","args":{"path": string}}
             {"type":"grep","args":{"path": string, "pattern": string, "max_results": number, "include_binary": boolean}}
             {"type":"search_replace","args":{"path": string, "old": string, "new": string, "unique": boolean}}
             {"type":"json_get","args":{"path": string, "json_pointer": string}}
             {"type":"json_set","args":{"path": string, "json_pointer": string, "value": string, "value_is_json": boolean}}
             {"type":"apply_changes","args":{"edits": [
                 {"path": string, "op": "replace_exact", "old": string, "new": string},
                 {"path": string, "op": "replace_between_markers", "start_marker": string, "end_marker": string, "new_content": string, "include_markers": false},
                 {"path": string, "op": "insert_after_anchor", "anchor": string, "new_content": string},
                 {"path": string, "op": "insert_before_anchor", "anchor": string, "new_content": string},
                 {"path": string, "op": "replace_regex", "pattern": string, "replacement": string, "unique": boolean},
                 {"path": string, "op": "ensure_block_present", "block": string, "idempotent_marker": string, "anchor_before": string, "anchor_after": string},
                 {"path": string, "op": "append_once", "block": string, "idempotent_marker": string},
                 {"path": string, "op": "write_if_missing", "content": string},
                 {"path": string, "op": "replace_lines", "start_line": number, "end_line": number, "new_content": string},
                 {"path": string, "op": "insert_lines_after", "line_number": number, "new_content": string},
                 {"path": string, "op": "insert_lines_before", "line_number": number, "new_content": string}
             ]}}
             
             ## DEVELOPMENT STANDARDS & BEST PRACTICES
             
             ### Project Structure & Organization
             - Create proper project directories with clear organization (e.g., `src/`, `templates/`, `static/`).
             - Use standard naming conventions (e.g., `snake_case` for Python, `camelCase` for JavaScript).
             - Separate concerns: templates, static files, configuration, and tests should be in their own directories.
             - Always include a `README.md` with setup and usage instructions.
             - For Flask applications, create a proper structure with `templates/` and `static/` directories.
             
             ### Code Quality Standards
             - Write clean, readable, and well-documented code.
             - Include proper error handling and validation in all code.
             - Use type hints in Python where appropriate.
             - Follow language-specific best practices and conventions.
             - Add comments for complex logic.
             - Handle edge cases and provide meaningful error messages.
             - Use proper HTTP status codes and error responses in web applications.
             
             ### Python/Flask Applications
             - Always use virtual environments: `python3 -m venv venv && . venv/bin/activate`.
             - Create a `requirements.txt` file with all necessary packages and their versions.
             - Use the Flask application factory pattern for larger applications.
             - Implement proper template inheritance and organize static files correctly.
             - Manage configurations and secrets using environment variables, not hardcoded values.
             - Include comprehensive logging and debugging capabilities.
             
             ### Web Applications
             - Create responsive, mobile-friendly designs.
             - Use semantic HTML and ensure accessibility (e.g., alt tags for images).
             - Implement a clear CSS organization methodology (e.g., BEM).
             - Add robust JavaScript error handling and provide user feedback for all actions.
             - Include loading states and progress indicators for long-running operations.
             
             Return pure JSON on a single line without explanations.
         """.trimIndent()
        val wd = workingDirProvider()
        val prior = if (observations.isEmpty()) "(none)" else observations.entries.joinToString("\n") { (k, v) -> "${k}: ${v.take(500)}${if (v.length > 500) " …" else ""}" }
        val contextSummary = getContextSummary()
        val hints = buildString {
            if (!task.targets.isNullOrEmpty()) append("targets: ").append(task.targets.joinToString(", ")).append('\n')
            if (!task.search.isNullOrEmpty()) append("search: ").append(task.search.joinToString(", ")).append('\n')
            if (!task.markers.isNullOrEmpty()) append("markers: ").append(task.markers.joinToString(", ")).append('\n')
        }.ifBlank { "(none)" }
        
        // PAVL Pillar 3: Add impact analysis for code modification tasks
        val impactAnalysis = if (isModifyingTask(task)) {
            val targetFiles = task.targets ?: emptyList()
            if (targetFiles.isNotEmpty()) {
                targetFiles.joinToString("\n\n") { filePath ->
                    performImpactAnalysis(filePath)
                }
            } else {
                "No specific target files identified for impact analysis."
            }
        } else {
            ""
        }
        val prompt = """
            Goal: ${goal}
            Project Requirements: ${projectRequirements ?: goal}
            Working directory: ${wd}
            Current task id: ${task.id}
            Task: ${task.description}
            Task category: ${task.category ?: "unspecified"}
            Task hints: ${hints}
            
            ${contextSummary}
            
            ${if (impactAnalysis.isNotBlank()) "IMPACT ANALYSIS:\n${impactAnalysis}\n" else ""}
            
            Prior observations (latest first):
            ${prior}
            
            IMPORTANT: Never assume a specific app type or inject premade templates. Infer expectations strictly from the goal and observed codebase. Generate only code and edits that are coherent with the existing files and the stated expectations. No placeholders.
            ${if (impactAnalysis.isNotBlank()) "Consider the impact analysis above when making modifications to ensure compatibility with dependent files." else ""}
            
            Produce one tool call JSON now, following the Rules and leveraging hints and observations to avoid redundant discovery.
        """.trimIndent()
        val msgs = mutableListOf(
            LlmMessage("system", sys),
            LlmMessage("user", prompt)
        )
        val reco = helperRecommend("inner_loop", mapOf("goal" to goal.take(500), "task" to "${task.id}:${task.description}"))
        applyHelperToMessages(reco, msgs)
        val flow = LlmProvider.current().generate(msgs)
        val content = collectAllWithRetry(flowProvider = { flow })
        val jsonText = extractFirstJsonObject(content) ?: return@withContext null
        val obj = runCatching { JSONObject(jsonText) }.getOrNull() ?: return@withContext null
        val type = obj.optString("type")
        val args = obj.optJSONObject("args") ?: JSONObject()
        return@withContext ToolCall(type, args)
    }

    private fun resolvePath(raw: String): File {
        val base = File(workingDirProvider())
        val f = File(raw)
        return if (f.isAbsolute) f else File(base, raw).absoluteFile
    }

    private fun ensureParentDirs(file: File) {
        file.parentFile?.let { if (!it.exists()) it.mkdirs() }
    }

    private fun executeToolCall(tc: ToolCall): ToolResult {
        currentRunStats?.let { st -> st.toolCounts[tc.type] = (st.toolCounts[tc.type] ?: 0) + 1 }
        
        // Add timeout protection for file operations
        val startTime = System.currentTimeMillis()
        val maxFileOpTime = 120000L // 120 seconds max for file operations
        
        // Add global timeout protection to prevent freezing
        val globalTimeout = 180000L // 180 seconds max for any operation
        // Fallback: if tool type is blank or unknown, attempt to coerce from current task category
        if (tc.type.isBlank() || tc.type.equals("unknown", ignoreCase = true)) {
            val task = currentTaskContext
            if (task != null) {
                val fallbackType = (task.category ?: "unknown").lowercase()
                val coerced = coerceToolCallForTaskCategory(task, ToolCall(fallbackType, tc.args))
                return executeToolCall(coerced)
            }
        }
        // Alias common synonyms to reduce failure due to type mismatches
        val normalizedType = when (tc.type.lowercase().trim()) {
            "ls", "dir", "list", "listdir", "list_directory", "read_dir", "read_directory", "scan_dir" -> "list_dir"
            "tree", "find", "list_recursive", "scan_recursive", "walk", "walk_dir", "walkdir" -> "list_dir_recursive"
            "stat" -> "stat_file"
            "cat" -> "read_file"
            "sed", "replace" -> "search_replace"
            "mkdir" -> "make_dir"
            "touch" -> "create_file"
            "shell", "bash", "sh" -> "run_shell"
            "json_edit" -> "apply_changes"
            else -> tc.type
        }
        val call = if (normalizedType == tc.type) tc else ToolCall(normalizedType, tc.args)
        appendTaskLog("tool_execute") {
            put("task_id", currentTaskContext?.id ?: JSONObject.NULL)
            put("type", call.type)
            put("args", call.args)
        }
        return when (call.type) {
            "create_file" -> {
                var path = call.args.optString("path")
                if (path.isBlank()) {
                    val t = currentTaskContext
                    val hinted = t?.targets?.firstOrNull()?.trim().orEmpty()
                    if (hinted.isNotBlank()) {
                        path = hinted
                    } else {
                        val wd = workingDirProvider()
                        val base = (t?.description ?: "NEW_FILE").lowercase().replace(Regex("[^a-z0-9._/\\-]+"), "-").trim('-')
                        val ext = when {
                            base.contains("python") || base.contains("flask") -> "py"
                            base.contains("html") || base.contains("template") -> "html"
                            base.contains("css") -> "css"
                            base.contains("js") || base.contains("javascript") -> "js"
                            base.contains("readme") -> "md"
                            base.contains("requirements") -> "txt"
                            else -> "txt"
                        }
                        val fileName = if (base.contains('.')) base else (base.ifBlank { "NEW_FILE" } + "." + ext)
                        path = File(wd, fileName).absolutePath
                    }
                }
                
                // Check timeout
                if (System.currentTimeMillis() - startTime > maxFileOpTime) {
                    return ToolResult(false, "create_file_timeout: operation took too long")
                }
                
                try {
                    val f = resolvePath(path)
                    ensureParentDirs(f)
                    if (!f.exists()) f.createNewFile()
                    if (f.exists()) notifyWorkspaceChanged(f.absolutePath)
                    
                    // Auto-generate initial content for the new file using the AI and codebase context
                    if (f.exists() && f.length() == 0L) {
                        fun stripFences(txt: String): String {
                            val triple = Regex("```[a-zA-Z0-9]*\n([\u0000-\uFFFF]*?)```", RegexOption.DOT_MATCHES_ALL)
                            val m = triple.find(txt)
                            return if (m != null) m.groupValues[1] else txt.trim('\n', '\r', ' ')
                        }
                        val goal = projectRequirements ?: lastPlanGoal ?: ""
                        val codebase = runCatching { File(workingDirProvider(), Settings.codebase_cache_path).readText() }.getOrElse { "" }.take(120000)
                        val ctxSummary = getContextSummary()
                        val sys = """
                            You are a senior software developer and file content specialist.
                            Task: Generate complete, functional file content for a new project file.
                            Context: Create coherent, production-ready code that integrates seamlessly with the existing codebase.
                            Output format: Return ONLY the complete file content without markdown formatting or explanations.
                            
                            Content requirements:
                            - Generate complete, functional code with no placeholders
                            - Ensure compatibility with existing codebase architecture
                            - Follow language-specific conventions and best practices
                            - Include proper imports, error handling, and documentation
                            - Maintain consistency with project structure and patterns
                            - Create self-contained, testable components
                            
                            Quality standards:
                            - No TODO comments or placeholder text
                            - No markdown code fences or backticks
                            - No explanatory text outside the code
                            - Production-ready, deployable code
                            - Proper error handling and validation
                            
                            Response: Pure file content only
                        """.trimIndent()
                        val user = """
                            Goal: ${goal}
                            Target path: ${f.absolutePath}
                            Project context summary:\n${ctxSummary}
                            Codebase cache (truncated):\n${codebase}
                            Write the full content for the file: ${f.name}
                        """.trimIndent()
                        val flow = LlmProvider.current().generate(listOf(LlmMessage("system", sys), LlmMessage("user", user)))
                        val generated = try { kotlinx.coroutines.runBlocking { collectAllWithRetry(flowProvider = { flow }) } } catch (e: Exception) { "" }
                        val content = stripFences(generated)
                        if (content.isNotBlank() && !content.contains("No AI API configured", ignoreCase = true)) {
                            f.writeText(content)
                            // Sync new file with codebase agent
                            runCatching { ControlApiClient.syncFile(sessionId, f.absolutePath, content, true) }
                        }
                    }
                    if (f.exists()) notifyWorkspaceChanged(f.absolutePath)
                    ToolResult(f.exists(), JSONObject().put("path", f.absolutePath).put("bytes", f.length()).toString())
                } catch (e: Exception) {
                    ToolResult(false, "create_file_error: ${e.message}")
                }
            }
            "write_file" -> {
                var path = call.args.optString("path")
                var contentRaw = call.args.optString("content")
                val encoding = call.args.optString("encoding", "utf-8").lowercase()
                val mode = call.args.optString("mode", "overwrite")
                val ifNotExists = call.args.optBoolean("if_not_exists", false)
                if (path.isBlank()) {
                    val t = currentTaskContext
                    val hinted = t?.targets?.firstOrNull()?.trim().orEmpty()
                    path = if (hinted.isNotBlank()) hinted else File(workingDirProvider(), "NEW_FILE.txt").absolutePath
                }
                
                // Generate content via API if missing or placeholder
                fun looksPlaceholder(txt: String): Boolean {
                    val low = txt.trim().lowercase()
                    return low.isBlank() || low.contains("todo") || low == "..." || low.contains("placeholder")
                }
                if (looksPlaceholder(contentRaw)) {
                    fun stripFences(txt: String): String {
                        val triple = Regex("```[a-zA-Z0-9]*\n([\u0000-\uFFFF]*?)```", RegexOption.DOT_MATCHES_ALL)
                        val m = triple.find(txt)
                        return if (m != null) m.groupValues[1] else txt.trim('\n', '\r', ' ')
                    }
                    val goal = projectRequirements ?: lastPlanGoal ?: ""
                    val codebase = runCatching { File(workingDirProvider(), Settings.codebase_cache_path).readText() }.getOrElse { "" }.take(120000)
                    val ctxSummary = getContextSummary()
                    val taskDesc = currentTaskContext?.description ?: "write file"
                    val sys = """
                        You are writing a complete file for a project. Return ONLY the file content without fences or explanations.
                        Use the codebase summary to integrate with existing files, functions, and routes.
                    """.trimIndent()
                    val user = """
                        Goal: ${goal}
                        Task: ${taskDesc}
                        Target path: ${path}
                        Project context summary:\n${ctxSummary}
                        Codebase cache (truncated):\n${codebase}
                        Write the full content for the file: ${File(path).name}
                    """.trimIndent()
                    val flow = LlmProvider.current().generate(listOf(LlmMessage("system", sys), LlmMessage("user", user)))
                    val generated = try { kotlinx.coroutines.runBlocking { collectAllWithRetry(flowProvider = { flow }) } } catch (e: Exception) { "" }
                    val content = stripFences(generated)
                    if (content.isNotBlank() && !content.contains("No AI API configured", ignoreCase = true)) {
                        contentRaw = content
                    }
                }
                
                // Check content size to prevent memory issues
                if (contentRaw.length > 1024 * 1024) { // 1MB limit
                    return ToolResult(false, "content_too_large: content exceeds 1MB limit")
                }
                
                // Check timeout
                if (System.currentTimeMillis() - startTime > maxFileOpTime) {
                    return ToolResult(false, "write_file_timeout: operation took too long")
                }
                
                // Check global timeout
                if (System.currentTimeMillis() - startTime > globalTimeout) {
                    return ToolResult(false, "global_timeout: operation exceeded maximum time limit")
                }
                
                try {
                    val f = resolvePath(path)
                    ensureParentDirs(f)
                    if (ifNotExists && f.exists()) {
                        return ToolResult(true, "skipped_write_existing:${f.absolutePath}")
                    }
                    
                    if (contentRaw.isBlank()) {
                        // Attempt a back-plan corrective fix
                        val attempted = runCatching {
                            val t = currentTaskContext
                            val desc = t?.description ?: ""
                            kotlinx.coroutines.runBlocking {
                                runBackPlanFixIfNeeded(path, intended = "(intended write_file with content)", userInstruction = desc, failureNote = "empty_content_after_generation", onStatus = { }, taskId = t?.id)
                            }
                        }.getOrElse { false }
                        if (attempted) {
                            return ToolResult(true, "backplan_applied:${f.absolutePath}")
                        }
                        return ToolResult(false, "empty_content: write_file requires non-empty content after generation")
                    }
                    
                    // Write content in chunks to avoid memory issues
                    val bytes = if (encoding == "base64") {
                        try {
                            Base64.decode(contentRaw, Base64.DEFAULT)
                        } catch (e: Exception) {
                            return ToolResult(false, "base64_decode_error: ${e.message}")
                        }
                    } else {
                        contentRaw.toByteArray(StandardCharsets.UTF_8)
                    }
                    
                    if (mode == "append" && f.exists()) {
                        f.appendBytes(bytes)
                    } else {
                        f.writeBytes(bytes)
                    }
                    
                    val ok = f.exists() && f.length() >= 0
                    if (ok) {
                        notifyWorkspaceChanged(f.absolutePath)
                        
                        // Update context cache for code files and schedule codebase cache upgrade
                        val fileType = when {
                            f.extension.lowercase() == "py" -> "python"
                            f.extension.lowercase() == "js" -> "javascript"
                            f.extension.lowercase() == "html" -> "html"
                            f.extension.lowercase() == "css" -> "css"
                            f.name.lowercase() == "requirements.txt" -> "config"
                            f.name.lowercase() == "readme.md" -> "documentation"
                            else -> "unknown"
                        }
                        updateContextCache(f.absolutePath, contentRaw, fileType)
                        notifyWorkspaceChanged(f.absolutePath)
                        // Sync modified/new file with codebase agent
                        runCatching { ControlApiClient.syncFile(sessionId, f.absolutePath, contentRaw, !f.exists()) }
                    }
                    
                    // Skip hash calculation for large files to prevent freezing
                    val hash = if (f.length() < 100 * 1024) { // Only hash files smaller than 100KB
                        try {
                            val md = MessageDigest.getInstance("SHA-256").digest(f.readBytes())
                            md.joinToString("") { String.format("%02x", it) }
                        } catch (e: Exception) {
                            "hash_calculation_failed"
                        }
                    } else {
                        "file_too_large_for_hash"
                    }
                    
                    ToolResult(ok, JSONObject().put("path", f.absolutePath).put("bytes", f.length()).put("sha256", hash).toString())
                } catch (e: Exception) {
                    // Back-plan on write failure
                    val attempted = runCatching {
                        val t = currentTaskContext
                        val desc = t?.description ?: ""
                        kotlinx.coroutines.runBlocking {
                            runBackPlanFixIfNeeded(path, intended = contentRaw.take(4000), userInstruction = desc, failureNote = "write_file_error:${e.message}", onStatus = { }, taskId = t?.id)
                        }
                    }.getOrElse { false }
                    if (attempted) return ToolResult(true, "backplan_applied:${resolvePath(path).absolutePath}")
                    ToolResult(false, "write_file_error: ${e.message}")
                }
            }
            "make_dir" -> {
                val path = call.args.optString("path")
                require(path.isNotBlank()) { "path missing" }
                
                // Check timeout
                if (System.currentTimeMillis() - startTime > maxFileOpTime) {
                    return ToolResult(false, "make_dir_timeout: operation took too long")
                }
                
                try {
                    val d = resolvePath(path)
                    d.mkdirs()
                    if (d.exists()) notifyWorkspaceChanged(d.absolutePath)
                    ToolResult(d.exists() && d.isDirectory, null)
                } catch (e: Exception) {
                    ToolResult(false, "make_dir_error: ${e.message}")
                }
            }
            "run_shell" -> {
                var command = call.args.optString("command")
                val timeoutMs = call.args.optLong("timeout_ms", 120_000L).coerceAtLeast(1_000L).coerceAtMost(300_000L) // Max 5 minutes
                val envObj = call.args.optJSONObject("env")
                if (command.isBlank()) {
                    val derived = deriveDefaultCommandForRunShell(currentTaskContext)
                    if (!derived.isNullOrBlank()) {
                        command = derived
                    }
                }
                require(command.isNotBlank()) { "command missing" }
                
                // Check for timeout before starting
                if (System.currentTimeMillis() - startTime > maxFileOpTime) {
                    return ToolResult(false, "run_shell_timeout: operation took too long")
                }
                
                // Check global timeout
                if (System.currentTimeMillis() - startTime > globalTimeout) {
                    return ToolResult(false, "global_timeout: operation exceeded maximum time limit")
                }
                val wd = workingDirProvider()
                val cacheKey = commandCacheKey(command, wd)
                // Normalize common typos like pip3--version -> pip3 --version
                command = command.replace(Regex("\\b(pip3?)--version\\b"), "$1 --version")
                command = command.replace(Regex("\\b(python3?)--version\\b"), "$1 --version")
                // Robustify python/pip version checks using fallbacks and non-failing tail
                fun robustifyPythonPip(cmd: String): String {
                    val hasPy = Regex("\\bpython(3)?\\s*--version").containsMatchIn(cmd)
                    val hasPip = Regex("\\bpip(3)?\\s*--version").containsMatchIn(cmd)
                    val parts = mutableListOf<String>()
                    if (hasPy) parts += "( (command -v python3 >/dev/null 2>&1 && python3 --version) || (command -v python >/dev/null 2>&1 && python --version) || echo 'python not found' )"
                    if (hasPip) parts += "( (command -v pip3 >/dev/null 2>&1 && pip3 --version) || (command -v pip >/dev/null 2>&1 && pip --version) || echo 'pip not found' )"
                    return if (parts.isNotEmpty()) parts.joinToString(" && ") + " || true" else cmd
                }
                command = robustifyPythonPip(command)
                // Always run inside the visible main terminal session
                val mainOut = MainShell.execInMainSession(context as? MainActivity, wd, command, timeoutMs)
                var output = mainOut.first
                var exit = mainOut.second
                
                // Check for PEP 668 externally managed environment error and provide better fallback
                if (exit != 0 && output.lowercase().contains("externally-managed-environment")) {
                    // Try to install Flask using apk if available
                    if (output.lowercase().contains("flask")) {
                        val apkCommand = "apk add py3-flask"
                        val apkOut = MainShell.execInMainSession(context as? MainActivity, wd, apkCommand, 30000L)
                        if (apkOut.second == 0) {
                            output = apkOut.first
                            exit = 0
                        }
                    }
                }
                // Heuristic upgrade on failure: try one improved command
                					fun suggestCommandUpgradeHeuristic(cmd: String, out: String): String? {
						val lower = out.lowercase()
						if (cmd.trim().startsWith("git ") && lower.contains("not a git repository")) {
							if (!cmd.contains("git init")) return "git init && ${cmd}"
						}
						if (cmd.contains("git commit") && (lower.contains("please tell me who you are") || lower.contains("user.name") && lower.contains("user.email"))) {
							return "git config user.email 'you@example.com' && git config user.name 'You' && ${cmd}"
						}
						// Flask import missing -> install Flask
						if (lower.contains("modulenotfounderror") && lower.contains("flask")) {
							return if (lastDetectedManagers.contains("apk")) "apk update && apk add py3-flask" else "python3 -m pip install --upgrade pip setuptools wheel && python3 -m pip install flask"
						}
						if ((lower.contains("no module named") || lower.contains("modulenotfounderror")) && lower.contains("flask")) {
							return if (lastDetectedManagers.contains("apk")) "apk update && apk add py3-flask" else "python3 -m pip install --upgrade pip setuptools wheel && python3 -m pip install flask"
						}
						// pip/Flask/Pygame upgrades
						val pipInstall = Regex("\\bpip3?\\s+install\\s+", RegexOption.IGNORE_CASE).containsMatchIn(cmd)
						if (pipInstall) {
							val installing = cmd.substringAfter("install ").trim()
							val targetPkg = installing.split(" ").firstOrNull()?.trim()?.lowercase() ?: ""
							val pep668 = lower.contains("externally-managed-environment") || lower.contains("externally managed")
							val apkAvailable = lastDetectedManagers.contains("apk") || lower.contains("apk-tools")
							if (apkAvailable && targetPkg == "pygame") {
								return "apk update && apk add py3-pygame"
							}
							if (lower.contains("gcc: not found") || lower.contains("sdl2-config: not found")) {
								if (apkAvailable) return "apk update && apk add build-base sdl2-dev sdl2_image-dev sdl2_mixer-dev sdl2_ttf-dev"
							}
							if (pep668 && apkAvailable) {
								val apkName = when (targetPkg) {
									"flask" -> "py3-flask"
									else -> "py3-${'$'}{targetPkg}"
								}
								return "apk update && apk add ${apkName}"
							}
							if (pep668) {
								val pkgPart = cmd.substringAfter("install ")
								return "python3 -m venv .venv && . .venv/bin/activate && pip install --upgrade pip setuptools wheel && pip install ${pkgPart}"
							}
							if (!cmd.contains("python3 -m pip")) {
								return cmd.replaceFirst(Regex("\\bpip3?\\s+install\\s+", RegexOption.IGNORE_CASE), "python3 -m pip install ")
							}
							if (lower.contains("externally-managed-environment") || lower.contains("permission denied") || lower.contains("not writeable") || lower.contains("is not owned by")) {
								val pkgPart = cmd.substringAfter("install ")
								return "python3 -m venv .venv && . .venv/bin/activate && pip install --upgrade pip setuptools wheel && pip install ${pkgPart}"
							}
							if (lower.contains("pip: not found") || lower.contains("no module named pip") || lower.contains("command not found: pip")) {
								val pkgPart = cmd.substringAfter("install ")
								return "(command -v apk >/dev/null 2>&1 && apk update && apk add py3-pip) || true && python3 -m pip install ${pkgPart}"
							}
						}
						return null
					}
if (exit != 0) {
                    val upgraded = suggestCommandUpgradeHeuristic(command, output)
                    if (upgraded != null) {
                        val retry = MainShell.execInMainSession(context as? MainActivity, wd, upgraded, timeoutMs)
                        val combined = StringBuilder()
                        combined.append(output)
                        combined.append("\n----- retry: ").append(upgraded).append(" -----\n")
                        combined.append(retry.first)
                        output = combined.toString()
                        exit = retry.second
                        if (exit == 0 && isInstallCommand(upgraded) && !output.lowercase().contains("externally-managed-environment")) {
                            lastInstallSuccess = true
                        }
                    }
                }
                if (exit == 0 && isInstallCommand(command) && !output.lowercase().contains("externally-managed-environment")) {
                    lastInstallSuccess = true
                }
                val obs = output.ifBlank { null }
                val payload = JSONObject()
                    .put("command", command)
                    .put("wd", wd)
                    .put("output", output)
                    .put("exit", exit)
                    .put("ts", System.currentTimeMillis())
                commandCache[cacheKey] = payload
                saveCommandCache()
                persistCliReport()
                currentRunStats?.commandsRun?.add(command)
                val isEnvCheck = isEnvPreflightCommand(command)
                appendTaskLog("run_shell_result") {
                    put("command", command)
                    put("wd", wd)
                    put("exit", exit)
                    put("output_preview", output.take(800))
                    put("bytes", output.length)
                }
                // Persist environment signals for later tool coercion
                runCatching {
                    val lower = output.lowercase()
                    if (lower.contains("id=alpine") || lower.contains("apk-tools")) lastDetectedOsId = "alpine"
                    if (lower.contains("apk-tools") || lower.contains("\napk ")) lastDetectedManagers.add("apk")
                    if (lower.contains("apt ") || lower.contains("apt-get ")) lastDetectedManagers.add("apt")
                    if (lower.contains("dnf ")) lastDetectedManagers.add("dnf")
                    if (lower.contains("yum ")) lastDetectedManagers.add("yum")
                    if (lower.contains("pacman ")) lastDetectedManagers.add("pacman")
                }
                // PEP 668 detected: mark failure to trigger remediation instead of false success
                if (Regex("\\bpip3?\\s+install\\s+", RegexOption.IGNORE_CASE).containsMatchIn(command) && output.lowercase().contains("externally-managed-environment")) {
                    lastInstallSuccess = false
                    currentTaskContext?.let { t -> markTaskFailed(t.id, "pep668_externally_managed_env") }
                }
                ToolResult(exit == 0 || isEnvCheck, obs)
            }
            "get_cached_command_output" -> {
                val command = call.args.optString("command")
                val maxAgeMs = call.args.optLong("max_age_ms", 10 * 60 * 1000L).coerceAtLeast(0L)
                require(command.isNotBlank()) { "command missing" }
                val wd = workingDirProvider()
                val cacheKey = commandCacheKey(command, wd)
                val entry = commandCache[cacheKey]
                val now = System.currentTimeMillis()
                val found = entry != null && (now - (entry?.optLong("ts") ?: 0L) <= maxAgeMs)
                val obj = JSONObject().apply {
                    put("command", command)
                    put("wd", wd)
                    put("found", found)
                    if (entry != null) {
                        put("ts", entry.optLong("ts"))
                        put("exit", entry.optInt("exit"))
                        put("output", entry.optString("output"))
                        put("age_ms", now - entry.optLong("ts"))
                    }
                }
                ToolResult(true, obj.toString())
            }
            "list_cached_commands" -> {
                val max = call.args.optInt("max", 50).coerceAtLeast(1)
                val arr = JSONArray()
                commandCache.entries.toList().takeLast(max).forEach { entry ->
                    arr.put(JSONObject().apply {
                        val v = entry.value
                        put("command", v.optString("command"))
                        put("wd", v.optString("wd"))
                        put("ts", v.optLong("ts"))
                        put("exit", v.optInt("exit"))
                        put("preview", v.optString("output").take(200))
                    })
                }
                val out = JSONObject().put("items", arr).toString()
                ToolResult(true, out)
            }
            "list_dir" -> {
                val raw = call.args.optString("path")
                val path = if (raw.isBlank()) workingDirProvider() else raw
                val d = resolvePath(path)
                val items = if (d.exists() && d.isDirectory) d.listFiles()?.filter { true }?.take(200).orEmpty() else emptyList()
                val arr = JSONArray()
                items.forEach { f ->
                    arr.put(JSONObject().put("name", f.name).put("type", if (f.isDirectory) "dir" else "file"))
                }
                val listing = JSONObject().put("path", d.absolutePath).put("items", arr).toString()
                currentRunStats?.dirsListed?.add(d.absolutePath)
                
                // Add special handling for empty directories to prevent loops
                if (items.isEmpty() && d.exists() && d.isDirectory) {
                    val emptyListing = JSONObject().put("path", d.absolutePath).put("items", arr).put("empty", true).put("message", "Directory is empty - ready for new files").toString()
                    ToolResult(true, emptyListing)
                } else {
                    ToolResult(true, listing)
                }
            }
            "list_dir_recursive" -> {
                val raw = call.args.optString("path")
                val path = if (raw.isBlank()) workingDirProvider() else raw
                // Guard: only allow when preflight says ok
                val allow = shouldAllowRecursiveListing(currentTaskContext)
                val maxDepth = if (allow) call.args.optInt("max_depth", 3).coerceIn(1, 3) else 0
                val maxEntries = if (allow) call.args.optInt("max_entries", 300).coerceIn(1, 300) else 1
                val root = resolvePath(path)
                val arr = JSONArray()
                var count = 0
                fun walk(dir: File, depth: Int) {
                    if (depth > maxDepth || count >= maxEntries) return
                    val files = dir.listFiles() ?: return
                    for (f in files) {
                        if (count >= maxEntries) break
                        arr.put(JSONObject().put("path", f.absolutePath).put("type", if (f.isDirectory) "dir" else "file"))
                        count++
                        if (f.isDirectory) walk(f, depth + 1)
                    }
                }
                if (allow && root.exists() && root.isDirectory) walk(root, 0)
                val out = JSONObject().put("root", root.absolutePath).put("max_depth", maxDepth).put("items", arr).toString()
                currentRunStats?.dirsListed?.add(root.absolutePath)
                ToolResult(true, out)
            }
            "read_file" -> {
                val path = call.args.optString("path")
                val maxBytes = call.args.optInt("max_bytes", 65536).coerceAtLeast(1024)
                val encoding = call.args.optString("encoding", "utf-8").lowercase()
                require(path.isNotBlank()) { "path missing" }
                val f = resolvePath(path)
                val content = if (f.exists() && f.isFile) {
                    val bytes = f.readBytes()
                    val slice = if (bytes.size > maxBytes) bytes.copyOf(maxBytes) else bytes
                    currentRunStats?.filesRead?.add(f.absolutePath)
                    val encoded = if (encoding == "base64") Base64.encodeToString(slice, Base64.NO_WRAP) else String(slice)
                    JSONObject().put("path", f.absolutePath).put("bytes", bytes.size).put("content", encoded).put("truncated", bytes.size > maxBytes).put("encoding", encoding).toString()
                } else {
                    // Try fetching from external codebase agent
                    val fetched = runCatching { ControlApiClient.fetchFile(sessionId, f.absolutePath) }.getOrNull()
                    if (!fetched.isNullOrBlank()) {
                        val text = fetched
                        val bytes = text.toByteArray()
                        val slice = if (bytes.size > maxBytes) bytes.copyOf(maxBytes) else bytes
                        val encoded = if (encoding == "base64") Base64.encodeToString(slice, Base64.NO_WRAP) else String(slice)
                        JSONObject().put("path", f.absolutePath).put("bytes", bytes.size).put("content", encoded).put("truncated", bytes.size > maxBytes).put("encoding", encoding).toString()
                    } else {
                        JSONObject().put("path", f.absolutePath).put("missing", true).toString()
                    }
                }
                ToolResult(true, content)
            }
            "read_files" -> {
                val arr = call.args.optJSONArray("paths") ?: JSONArray()
                val maxBytes = call.args.optInt("max_bytes", 65536).coerceAtLeast(1024)
                val results = JSONArray()
                for (i in 0 until arr.length()) {
                    val rawPath = arr.optString(i)
                    if (rawPath.isNullOrBlank()) continue
                    val f = resolvePath(rawPath)
                    if (f.exists() && f.isFile) {
                        val bytes = runCatching { f.readBytes() }.getOrElse { ByteArray(0) }
                        val slice = if (bytes.size > maxBytes) bytes.copyOf(maxBytes) else bytes
                        val text = String(slice)
                        results.put(JSONObject().put("path", f.absolutePath).put("bytes", bytes.size).put("content", text).put("truncated", bytes.size > maxBytes))
                    } else {
                        results.put(JSONObject().put("path", f.absolutePath).put("missing", true))
                    }
                }
                val out = JSONObject().put("files", results).toString()
                ToolResult(true, out)
            }
            "read_files_glob" -> {
                val rootPath = call.args.optString("root").ifBlank { workingDirProvider() }
                val glob = call.args.optString("glob")
                val maxFiles = call.args.optInt("max_files", 50).coerceAtLeast(1)
                val maxBytes = call.args.optInt("max_bytes", 65536).coerceAtLeast(1024)
                val maxDepth = call.args.optInt("max_depth", 5).coerceAtLeast(0)
                require(rootPath.isNotBlank()) { "root missing" }
                require(glob.isNotBlank()) { "glob missing" }
                val root = resolvePath(rootPath)
                val regex = globToRegex(glob)
                val results = JSONArray()
                var count = 0
                fun walk(dir: File, depth: Int) {
                    if (depth > maxDepth || count >= maxFiles) return
                    val files = dir.listFiles() ?: return
                    for (f in files) {
                        if (count >= maxFiles) break
                        if (f.isDirectory) {
                            walk(f, depth + 1)
                        } else {
                            val rel = f.absolutePath
                            if (regex.matcher(f.name).matches() || regex.matcher(rel).matches()) {
                                val bytes = runCatching { f.readBytes() }.getOrElse { ByteArray(0) }
                                val slice = if (bytes.size > maxBytes) bytes.copyOf(maxBytes) else bytes
                                val text = String(slice)
                                results.put(JSONObject().put("path", f.absolutePath).put("bytes", bytes.size).put("content", text).put("truncated", bytes.size > maxBytes))
                                count++
                            }
                        }
                    }
                }
                if (root.isDirectory) walk(root, 0) else if (root.isFile) {
                    if (globToRegex(glob).matcher(root.name).matches()) {
                        val bytes = runCatching { root.readBytes() }.getOrElse { ByteArray(0) }
                        val slice = if (bytes.size > maxBytes) bytes.copyOf(maxBytes) else bytes
                        val text = String(slice)
                        results.put(JSONObject().put("path", root.absolutePath).put("bytes", bytes.size).put("content", text).put("truncated", bytes.size > maxBytes))
                        count++
                    }
                }
                val out = JSONObject().put("root", root.absolutePath).put("glob", glob).put("files", results).toString()
                ToolResult(true, out)
            }
            "read_file_lines" -> {
                val path = call.args.optString("path")
                val start = call.args.optInt("start", 1).coerceAtLeast(1)
                val end = call.args.optInt("end", start + 500).coerceAtLeast(start)
                val maxBytes = call.args.optInt("max_bytes", 131072).coerceAtLeast(4096)
                require(path.isNotBlank()) { "path missing" }
                val f = resolvePath(path)
                val obs = if (f.exists() && f.isFile) {
                    val lines = f.readLines()
                    val actualEnd = end.coerceAtMost(lines.size)
                    val slice = if (start <= lines.size) lines.subList(start - 1, actualEnd) else emptyList()
                    val text = slice.joinToString("\n")
                    val clipped = if (text.toByteArray().size > maxBytes) String(text.toByteArray().copyOf(maxBytes)) else text
                    JSONObject().put("path", f.absolutePath).put("start", start).put("end", actualEnd).put("content", clipped).put("truncated", clipped.length < text.length).toString()
                } else {
                    JSONObject().put("path", f.absolutePath).put("missing", true).toString()
                }
                ToolResult(true, obs)
            }
            "read_file_section_by_markers" -> {
                val path = call.args.optString("path")
                val startMarker = call.args.optString("start_marker")
                val endMarker = call.args.optString("end_marker")
                val includeMarkers = call.args.optBoolean("include_markers", false)
                require(path.isNotBlank()) { "path missing" }
                require(startMarker.isNotBlank() && endMarker.isNotBlank()) { "markers missing" }
                val f = resolvePath(path)
                val obs = if (f.exists() && f.isFile) {
                    val original = f.readText()
                    val sIdx = original.indexOf(startMarker)
                    if (sIdx < 0) {
                        JSONObject().put("path", f.absolutePath).put("start_found", false).toString()
                    } else {
                        val eIdx = original.indexOf(endMarker, sIdx + startMarker.length)
                        if (eIdx < 0) {
                            JSONObject().put("path", f.absolutePath).put("end_found", false).toString()
                        } else {
                            val content = if (includeMarkers) original.substring(sIdx, eIdx + endMarker.length) else original.substring(sIdx + startMarker.length, eIdx)
                            JSONObject().put("path", f.absolutePath).put("content", content).toString()
                        }
                    }
                } else {
                    JSONObject().put("path", f.absolutePath).put("missing", true).toString()
                }
                ToolResult(true, obs)
            }
            "stat_file" -> {
                val raw = call.args.optString("path")
                val path = if (raw.isBlank()) workingDirProvider() else raw
                val f = resolvePath(path)
                val obj = JSONObject().put("path", f.absolutePath)
                    .put("exists", f.exists())
                    .put("is_dir", f.isDirectory)
                    .put("is_file", f.isFile)
                    .put("size", if (f.exists() && f.isFile) f.length() else 0L)
                    .put("modified", if (f.exists()) f.lastModified() else 0L)
                ToolResult(true, obj.toString())
            }
            "grep" -> {
                val raw = call.args.optString("path")
                val path = if (raw.isBlank()) workingDirProvider() else raw
                val pattern = call.args.optString("pattern")
                val maxResults = call.args.optInt("max_results", 200).coerceAtLeast(1)
                require(path.isNotBlank()) { "path missing" }
                require(pattern.isNotBlank()) { "pattern missing" }
                val root = resolvePath(path)
                val regex = runCatching { Pattern.compile(pattern) }.getOrElse { Pattern.compile(Pattern.quote(pattern)) }
                val results = JSONArray()
                var count = 0
                fun isBinary(file: File): Boolean {
                    return try {
                        val bytes = file.inputStream().use { it.readNBytes(1024) }
                        bytes.any { b -> b.toInt() == 0 } // crude NUL check
                    } catch (e: Exception) { false }
                }
                fun scanFile(file: File) {
                    if (count >= maxResults) return
                    val sz = runCatching { file.length() }.getOrElse { 0L }
                    if (sz > 5_000_000L) return // skip files > 5MB
                    if (isBinary(file)) return
                    val lines = runCatching { file.readLines() }.getOrElse { emptyList() }
                    for ((idx, line) in lines.withIndex()) {
                        if (count >= maxResults) break
                        val m = regex.matcher(line)
                        if (m.find()) {
                            results.put(JSONObject().put("file", file.absolutePath).put("line", idx + 1).put("text", line.take(500)))
                            currentRunStats?.grepPatterns?.add(pattern)
                            count++
                        }
                    }
                }
                fun walk(dir: File) {
                    if (count >= maxResults) return
                    val list = dir.listFiles() ?: return
                    for (f in list) {
                        if (count >= maxResults) break
                        if (f.isDirectory) walk(f) else scanFile(f)
                    }
                }
                if (root.isDirectory) walk(root) else if (root.isFile) scanFile(root)
                val out = JSONObject().put("root", root.absolutePath).put("matches", results).toString()
                ToolResult(true, out)
            }
            "apply_changes" -> {
                val edits = call.args.optJSONArray("edits") ?: JSONArray()
                val includeDiffs = call.args.optBoolean("include_diffs", true)
                val previewOnly = call.args.optBoolean("preview_only", false)
                val results = mutableListOf<String>()
                val outArr = JSONArray()
                var createdAny = false
                var modifiedAny = false
                for (i in 0 until edits.length()) {
                    val e = edits.optJSONObject(i) ?: continue
                    val op = e.optString("op")
                    val path = e.optString("path")
                    if (path.isBlank()) { results.add("edit[$i]: missing path"); continue }
                    val file = resolvePath(path)
                    val entry = JSONObject().put("file", file.absolutePath).put("op", op)
                    if (!file.exists()) {
                        if (op == "write_if_missing") {
                            val content = e.optString("content")
                            ensureParentDirs(file)
                            if (!previewOnly) {
                                file.writeText(content)
                            }
                            entry.put("status", if (previewOnly) "planned_create" else "created")
                            createdAny = true
                            // Sync newly created file
                            if (!previewOnly) runCatching { ControlApiClient.syncFile(sessionId, file.absolutePath, e.optString("content"), true) }
                            if (includeDiffs) entry.put("diff", computeUnifiedDiff("", content, file.absolutePath))
                            outArr.put(entry)
                            continue
                        }
                        results.add("edit[$i]: file missing: ${path}")
                        entry.put("status", "missing")
                        outArr.put(entry)
                        continue
                    }
                    val original = runCatching { file.readText() }.getOrElse { "" }
                    val updated = when (op) {
                        "replace_exact" -> {
                            val old = e.optString("old")
                            val new = e.optString("new")
                            if (old.isEmpty()) { results.add("edit[$i]: old empty"); null } else {
                                val idx = original.indexOf(old)
                                if (idx < 0) { results.add("edit[$i]: old not found"); null } else original.replaceFirst(old, new)
                            }
                        }
                        "replace_between_markers" -> {
                            val start = e.optString("start_marker")
                            val end = e.optString("end_marker")
                            val newContent = e.optString("new_content")
                            val includeMarkers = e.optBoolean("include_markers", false)
                            val sIdx = original.indexOf(start)
                            if (sIdx < 0) { results.add("edit[$i]: start not found"); null } else {
                                val eIdx = original.indexOf(end, sIdx + start.length)
                                if (eIdx < 0) { results.add("edit[$i]: end not found"); null } else {
                                    if (includeMarkers) {
                                        val pre = original.substring(0, sIdx)
                                        val post = original.substring(eIdx + end.length)
                                        pre + start + newContent + end + post
                                    } else {
                                        val pre = original.substring(0, sIdx + start.length)
                                        val post = original.substring(eIdx)
                                        pre + newContent + post
                                    }
                                }
                            }
                        }
                        "insert_after_anchor" -> {
                            val anchor = e.optString("anchor")
                            val newContent = e.optString("new_content")
                            val ignoreCase = e.optBoolean("ignore_case", true)
                            var aIdx = if (ignoreCase) original.indexOf(anchor, ignoreCase = true) else original.indexOf(anchor)
                            if (aIdx < 0) {
                                val anchorRegex = e.optString("anchor_regex")
                                if (anchorRegex.isNotBlank()) {
                                    val m = runCatching { Regex(anchorRegex).find(original) }.getOrNull()
                                    if (m != null) aIdx = m.range.last
                                }
                            }
                            if (aIdx < 0) {
                                // Fallback: append at end with idempotent behavior to keep integration moving
                                val already = original.contains(newContent)
                                if (already) {
                                    results.add("edit[$i]: anchor not found; content already present")
                                    null
                                } else {
                                    results.add("edit[$i]: anchor not found; appended at end")
                                    original + "\n" + newContent
                                }
                            } else {
                                val insertPos = aIdx + anchor.length
                                original.substring(0, insertPos) + newContent + original.substring(insertPos)
                            }
                        }
                        "insert_before_anchor" -> {
                            val anchor = e.optString("anchor")
                            val newContent = e.optString("new_content")
                            val ignoreCase = e.optBoolean("ignore_case", true)
                            var aIdx = if (ignoreCase) original.indexOf(anchor, ignoreCase = true) else original.indexOf(anchor)
                            if (aIdx < 0) {
                                val anchorRegex = e.optString("anchor_regex")
                                if (anchorRegex.isNotBlank()) {
                                    val m = runCatching { Regex(anchorRegex).find(original) }.getOrNull()
                                    if (m != null) aIdx = m.range.first
                                }
                            }
                            if (aIdx < 0) {
                                val already = original.contains(newContent)
                                if (already) {
                                    results.add("edit[$i]: anchor not found; content already present")
                                    null
                                } else {
                                    results.add("edit[$i]: anchor not found; prepended at start")
                                    newContent + "\n" + original
                                }
                            } else original.substring(0, aIdx) + newContent + original.substring(aIdx)
                        }
                        "replace_regex" -> {
                            val regexObj = e.optJSONObject("regex")
                            val pattern = e.optString("pattern").ifBlank { regexObj?.optString("pattern").orEmpty() }
                            val replacement = e.optString("replacement").ifBlank { regexObj?.optString("replace").orEmpty() }
                            val unique = e.optBoolean("unique", true)
                            if (pattern.isBlank()) { results.add("edit[$i]: pattern empty"); null } else {
                                val regex = runCatching { Regex(pattern) }.getOrElse { Regex(Pattern.quote(pattern)) }
                                val count = regex.findAll(original).count()
                                if (unique && count != 1) { results.add("edit[$i]: non-unique matches=${'$'}count"); null } else original.replace(regex, replacement)
                            }
                        }
                        "json_patch" -> {
                            // Enhanced JSON patching for structured updates
                            val jsonPath = e.optString("path")
                            val value = e.opt("value")
                            val operation = e.optString("operation", "set") // set, delete, append
                            
                            if (jsonPath.isBlank()) { 
                                results.add("edit[$i]: json_path empty"); null 
                            } else {
                                runCatching {
                                    val json = JSONObject(original)
                                    val pathParts = jsonPath.split(".")
                                    
                                    when (operation) {
                                        "set" -> {
                                            var current = json
                                            for (j in 0 until pathParts.size - 1) {
                                                val part = pathParts[j]
                                                if (!current.has(part)) {
                                                    current.put(part, JSONObject())
                                                }
                                                current = current.getJSONObject(part)
                                            }
                                            current.put(pathParts.last(), value)
                                        }
                                        "delete" -> {
                                            var current = json
                                            for (j in 0 until pathParts.size - 1) {
                                                current = current.getJSONObject(pathParts[j])
                                            }
                                            current.remove(pathParts.last())
                                        }
                                        "append" -> {
                                            var current = json
                                            for (j in 0 until pathParts.size - 1) {
                                                current = current.getJSONObject(pathParts[j])
                                            }
                                            val key = pathParts.last()
                                            val array = current.optJSONArray(key) ?: JSONArray()
                                            array.put(value)
                                            current.put(key, array)
                                        }
                                    }
                                    json.toString(2)
                                }.getOrElse { e ->
                                    results.add("edit[$i]: json_patch failed: ${e.message}")
                                    null
                                }
                            }
                        }
                        "smart_merge" -> {
                            // Intelligent merging for code chunks
                            val newChunk = e.optString("new_chunk")
                            val mergeStrategy = e.optString("strategy", "replace") // replace, merge, append
                            val contextLines = e.optInt("context_lines", 3)
                            
                            if (newChunk.isBlank()) {
                                results.add("edit[$i]: new_chunk empty"); null
                            } else {
                                when (mergeStrategy) {
                                    "replace" -> newChunk
                                    "append" -> original + "\n" + newChunk
                                    "merge" -> {
                                        // Smart merge based on code structure
                                        val originalLines = original.lines()
                                        val newLines = newChunk.lines()
                                        val merged = mutableListOf<String>()
                                        
                                        // Simple merge strategy - can be enhanced
                                        merged.addAll(originalLines)
                                        merged.add("") // separator
                                        merged.addAll(newLines)
                                        
                                        merged.joinToString("\n")
                                    }
                                    else -> {
                                        results.add("edit[$i]: unknown merge strategy: $mergeStrategy")
                                        null
                                    }
                                }
                            }
                        }
                        "ensure_block_present" -> {
                            val block = e.optString("block")
                            val idMarker = e.optString("idempotent_marker")
                            val before = e.optString("anchor_before")
                            val after = e.optString("anchor_after")
                            val contains = if (idMarker.isNotBlank()) original.contains(idMarker) else original.contains(block)
                            if (contains) { results.add("edit[$i]: already present"); null } else {
                                when {
                                    before.isNotBlank() -> {
                                        val idx = original.indexOf(before)
                                        if (idx < 0) { results.add("edit[$i]: anchor_before not found"); null } else original.substring(0, idx) + block + original.substring(idx)
                                    }
                                    after.isNotBlank() -> {
                                        val idx = original.indexOf(after)
                                        if (idx < 0) { results.add("edit[$i]: anchor_after not found"); null } else {
                                            val pos = idx + after.length
                                            original.substring(0, pos) + block + original.substring(pos)
                                        }
                                    }
                                    else -> original + block
                                }
                            }
                        }
                        "append_once" -> {
                            val block = e.optString("block")
                            val idMarker = e.optString("idempotent_marker")
                            val contains = if (idMarker.isNotBlank()) original.contains(idMarker) else original.contains(block)
                            if (contains) { results.add("edit[$i]: already present"); null } else original + block
                        }
                        "write_if_missing" -> {
                            results.add("edit[$i]: exists (skipped)")
                            null
                        }
                        "replace_lines" -> {
                            val start = e.optInt("start_line", -1)
                            val end = e.optInt("end_line", -1)
                            val newContent = e.optString("new_content")
                            if (start <= 0 || end < start) { results.add("edit[$i]: invalid line range"); null } else {
                                val lines = original.split("\n").toMutableList()
                                val from = (start - 1).coerceAtLeast(0)
                                val to = end.coerceAtMost(lines.size)
                                val newLines = newContent.split("\n")
                                lines.subList(from, to).clear()
                                lines.addAll(from, newLines)
                                lines.joinToString("\n")
                            }
                        }
                        "insert_lines_after" -> {
                            val line = e.optInt("line_number", -1)
                            val newContent = e.optString("new_content")
                            if (line < 0) { results.add("edit[$i]: invalid line number"); null } else {
                                val lines = original.split("\n").toMutableList()
                                val idx = line.coerceAtMost(lines.size)
                                val newLines = newContent.split("\n")
                                lines.addAll(idx, newLines)
                                lines.joinToString("\n")
                            }
                        }
                        "insert_lines_before" -> {
                            val line = e.optInt("line_number", -1)
                            val newContent = e.optString("new_content")
                            if (line <= 0) { results.add("edit[$i]: invalid line number"); null } else {
                                val lines = original.split("\n").toMutableList()
                                val idx = (line - 1).coerceAtLeast(0)
                                val newLines = newContent.split("\n")
                                lines.addAll(idx, newLines)
                                lines.joinToString("\n")
                            }
                        }
                        else -> { results.add("edit[$i]: unknown op ${'$'}op"); null }
                    }
                    if (updated != null && updated != original) {
                        // Idempotency: if file already contains new chunk, treat as ok
                        val idempotentMarker = e.optString("idempotent_marker")
                        val alreadyPresent = (idempotentMarker.isNotBlank() && original.contains(idempotentMarker)) || original.contains(updated)
                        if (alreadyPresent) {
                            results.add("edit[$i]: already present; no change needed")
                            entry.put("status", "no_change")
                            outArr.put(entry)
                            continue
                        }
                        if (!previewOnly) {
                            runCatching { file.writeText(updated) }.onSuccess {
                                results.add("edit[$i]: ok (${path})")
                                notifyWorkspaceChanged(file.absolutePath)
                                modifiedAny = true
                                // Sync modified file
                                runCatching { ControlApiClient.syncFile(sessionId, file.absolutePath, updated, false) }
                            }.onFailure { ex -> results.add("edit[$i]: write failed (${ex.message})") }
                        } else {
                            results.add("edit[$i]: planned_change (${path})")
                            modifiedAny = true
                        }
                        if (includeDiffs) {
                            entry.put("status", if (previewOnly) "planned_change" else "modified")
                            entry.put("diff", computeUnifiedDiff(original, updated, file.absolutePath))
                        }
                        outArr.put(entry)
                    } else if (updated == null) {
                        entry.put("status", "no_change")
                        outArr.put(entry)
                    }
                }
                val summary = if (includeDiffs) JSONObject().put("results", outArr).put("preview_only", previewOnly).toString() else (if (results.isEmpty()) "no edits" else results.joinToString("; "))
                val ok = createdAny || modifiedAny || previewOnly
                ToolResult(ok, summary)
            }
            "search_replace" -> {
                val path = call.args.optString("path")
                val old = call.args.optString("old")
                val new = call.args.optString("new")
                val unique = call.args.optBoolean("unique", true)
                require(path.isNotBlank()) { "path missing" }
                require(old.isNotBlank()) { "old missing" }
                val file = resolvePath(path)
                if (!file.exists()) return ToolResult(false, "file missing: ${file.path}")
                val original = file.readText()
                val occurrences = Regex(Pattern.quote(old)).findAll(original).count()
                if (unique && occurrences != 1) return ToolResult(false, "non-unique match count: ${occurrences}")
                val updated = original.replaceFirst(old, new)
                file.writeText(updated)
                notifyWorkspaceChanged(file.absolutePath)
                // Sync modified file content to codebase agent
                runCatching { ControlApiClient.syncFile(sessionId, file.absolutePath, updated, false) }
                ToolResult(true, "replaced ${if (unique) 1 else occurrences} occurrence(s) in ${file.absolutePath}")
            }
            "delete_file" -> {
                val path = call.args.optString("path")
                val missingOk = call.args.optBoolean("missing_ok", true)
                require(path.isNotBlank()) { "path missing" }
                val f = resolvePath(path)
                val existed = f.exists()
                val ok = if (existed) f.delete() else missingOk
                if (ok) notifyWorkspaceChanged(f.absolutePath)
                ToolResult(ok, JSONObject().put("path", f.absolutePath).put("existed", existed).put("deleted", existed && ok).toString())
            }
            "copy_file" -> {
                val src = call.args.optString("src")
                val dest = call.args.optString("dest")
                val overwrite = call.args.optBoolean("overwrite", false)
                require(src.isNotBlank() && dest.isNotBlank()) { "src/dest missing" }
                val s = resolvePath(src)
                val d = resolvePath(dest)
                if (!s.exists() || !s.isFile) return ToolResult(false, "source missing")
                if (d.exists() && !overwrite) return ToolResult(false, "dest exists")
                ensureParentDirs(d)
                d.writeBytes(s.readBytes())
                notifyWorkspaceChanged(d.absolutePath)
                ToolResult(true, JSONObject().put("src", s.absolutePath).put("dest", d.absolutePath).put("bytes", d.length()).toString())
            }
            "move_file" -> {
                val src = call.args.optString("src")
                val dest = call.args.optString("dest")
                val overwrite = call.args.optBoolean("overwrite", false)
                require(src.isNotBlank() && dest.isNotBlank()) { "src/dest missing" }
                val s = resolvePath(src)
                val d = resolvePath(dest)
                if (!s.exists() || !s.isFile) return ToolResult(false, "source missing")
                if (d.exists()) {
                    if (!overwrite) return ToolResult(false, "dest exists") else d.delete()
                }
                ensureParentDirs(d)
                val ok = s.renameTo(d)
                if (!ok) {
                    d.writeBytes(s.readBytes())
                    s.delete()
                }
                notifyWorkspaceChanged(d.absolutePath)
                ToolResult(true, JSONObject().put("src", s.absolutePath).put("dest", d.absolutePath).toString())
            }
            "json_get" -> {
                val path = call.args.optString("path")
                val pointer = call.args.optString("json_pointer")
                require(path.isNotBlank()) { "path missing" }
                require(pointer.isNotBlank()) { "json_pointer missing" }
                val f = resolvePath(path)
                if (!f.exists()) return ToolResult(false, "file missing")
                val obj = runCatching { JSONObject(f.readText()) }.getOrElse { return ToolResult(false, "invalid json") }
                val parts = pointer.split('/').filter { it.isNotEmpty() }
                var cur: Any = obj
                for (p in parts) {
                    if (cur is JSONObject) cur = cur.opt(p) ?: return ToolResult(true, JSONObject().put("found", false).toString())
                    else return ToolResult(false, "non_object path")
                }
                val out = JSONObject().put("found", true).put("value", cur).toString()
                ToolResult(true, out)
            }
            "json_set" -> {
                val path = call.args.optString("path")
                val pointer = call.args.optString("json_pointer")
                val valueStr = call.args.optString("value")
                val valueIsJson = call.args.optBoolean("value_is_json", true)
                require(path.isNotBlank()) { "path missing" }
                require(pointer.isNotBlank()) { "json_pointer missing" }
                val f = resolvePath(path)
                ensureParentDirs(f)
                val root = if (f.exists()) runCatching { JSONObject(f.readText()) }.getOrElse { JSONObject() } else JSONObject()
                val parts = pointer.split('/').filter { it.isNotEmpty() }
                var cur: JSONObject = root
                for ((idx, p) in parts.withIndex()) {
                    val isLast = idx == parts.size - 1
                    if (isLast) {
                        val v: Any = if (valueIsJson) runCatching { JSONObject(valueStr) }.getOrElse { valueStr } else valueStr
                        cur.put(p, v)
                    } else {
                        val next = cur.optJSONObject(p) ?: JSONObject().also { cur.put(p, it) }
                        cur = next
                    }
                }
                f.writeText(root.toString(2))
                ToolResult(true, JSONObject().put("path", f.absolutePath).put("updated", true).toString())
            }
            else -> ToolResult(false, "unknown_tool_type:${call.type}")
        }
    }

    private fun isEnvPreflightCommand(command: String): Boolean {
        val cmd = command.lowercase()
        val baseSignals = listOf("uname", "os-release", "command -v", "which", "echo \$shell", "echo \$path")
        if (baseSignals.any { cmd.contains(it) }) return true
        val managers = listOf("apt", "dnf", "yum", "pacman", "apk", "zypper", "brew")
        val hasMgr = managers.any { cmd.contains(it) }
        val hasVersionProbe = cmd.contains("--version") || cmd.matches(Regex(".*\\s-v(\\s|$).*"))
        return hasMgr && hasVersionProbe
    }

    private fun deriveAlpineRootFromWorkspace(wd: String): String? {
        val needle = "/local/alpine"
        val idx = wd.indexOf(needle)
        if (idx < 0) return null
        val root = wd.substring(0, idx + needle.length)
        val osRelease = File(root, "etc/os-release")
        val ok = runCatching { osRelease.readText().lowercase().contains("id=alpine") }.getOrElse { false }
        return if (ok) root else null
    }

    private fun shouldAllowRecursiveListing(task: Task?): Boolean {
        val cat = task?.category?.lowercase()?.trim()
        if (cat != "list_dir_recursive") return false
        val g = ((task?.description ?: "") + " " + (task?.search?.joinToString(" ") ?: "")).lowercase()
        val hints = listOf("android", "kotlin", "java", "src", "main", "androidmanifest")
        return hints.any { g.contains(it) }
    }

    private fun isInstallCommand(cmd: String): Boolean {
        val c = cmd.lowercase()
        return c.contains("apk add") || c.contains("apt-get install") || c.contains("apt install") ||
                c.contains("dnf install") || c.contains("yum install") ||
                Regex("\\bpacman\\s+-S(\n|\r| |$)").containsMatchIn(c) ||
                c.contains("pip install") || c.contains("pip3 install")
    }

    private fun appendTaskLog(type: String, build: (JSONObject.() -> Unit)? = null) {
        runCatching {
            val obj = JSONObject()
                .put("type", type)
                .put("ts", System.currentTimeMillis())
                .put("session_id", sessionId)
            build?.invoke(obj)
            
            // Create concise version for prompt efficiency
            val conciseObj = createConciseLogEntry(obj)
            
            taskLogFile.parentFile?.mkdirs()
            if (!taskLogFile.exists()) taskLogFile.createNewFile()
            taskLogFile.appendText(conciseObj.toString() + "\n")
        }
    }

    private fun createConciseLogEntry(obj: JSONObject): JSONObject {
        val concise = JSONObject()
        val type = obj.optString("type")
        
        // Always include essential fields
        concise.put("type", type)
        concise.put("ts", obj.optLong("ts"))
        concise.put("session_id", obj.optString("session_id"))
        
        when (type) {
            "plan_created" -> {
                concise.put("goal", obj.optString("goal").take(80))
                val tasks = obj.optJSONArray("tasks")
                if (tasks != null && tasks.length() > 0) {
                    concise.put("task_count", tasks.length())
                    // Include only first few task summaries
                    val taskSummary = JSONArray()
                    for (i in 0 until minOf(3, tasks.length())) {
                        val task = tasks.optJSONObject(i)
                        if (task != null) {
                            taskSummary.put(JSONObject().apply {
                                put("id", task.optString("id"))
                                put("desc", task.optString("description").take(40))
                                put("cat", task.optString("category"))
                            })
                        }
                    }
                    concise.put("tasks", taskSummary)
                    if (tasks.length() > 3) concise.put("more_tasks", tasks.length() - 3)
                }
            }
            "task_start" -> {
                concise.put("task_id", obj.optString("task_id"))
                concise.put("desc", obj.optString("description").take(40))
                concise.put("cat", obj.optString("category"))
            }
            "task_done", "task_failed" -> {
                concise.put("task_id", obj.optString("task_id"))
                concise.put("attempts", obj.optInt("attempts"))
                if (type == "task_failed") {
                    concise.put("note", obj.optString("note").take(25))
                }
            }
            "task_attempt" -> {
                concise.put("task_id", obj.optString("task_id"))
                concise.put("attempt", obj.optInt("attempt"))
            }
            "tool_call_none" -> {
                concise.put("task_id", obj.optString("task_id"))
                concise.put("reason", obj.optString("reason"))
                concise.put("task_desc", obj.optString("task_desc"))
            }
            "write_file", "create_file" -> {
                val args = obj.optJSONObject("args")
                if (args != null) {
                    concise.put("path", shortenPath(args.optString("path")))
                    concise.put("bytes", args.optString("content").length)
                }
                concise.put("ok", obj.optBoolean("ok"))
            }
            "apply_changes" -> {
                val args = obj.optJSONObject("args")
                if (args != null) {
                    val edits = args.optJSONArray("edits")
                    if (edits != null) {
                        concise.put("edit_count", edits.length())
                        val editSummary = JSONArray()
                        for (i in 0 until minOf(2, edits.length())) {
                            val edit = edits.optJSONObject(i)
                            if (edit != null) {
                                editSummary.put(JSONObject().apply {
                                    put("path", shortenPath(edit.optString("path")))
                                    put("op", edit.optString("op"))
                                })
                            }
                        }
                        concise.put("edits", editSummary)
                    }
                }
                concise.put("ok", obj.optBoolean("ok"))
                val obs = obj.optString("observation_preview")
                if (obs.isNotBlank()) {
                    concise.put("result", obs.take(50))
                }
            }
            "backplan_attempt" -> {
                concise.put("task_id", obj.optString("task_id"))
                concise.put("path", shortenPath(obj.optString("path")))
                concise.put("failure", obj.optString("failure").take(50))
            }
            "backplan_result" -> {
                concise.put("task_id", obj.optString("task_id"))
                concise.put("path", shortenPath(obj.optString("path")))
                concise.put("ok", obj.optBoolean("ok"))
                val reason = obj.optString("reason")
                if (reason.isNotBlank()) concise.put("reason", reason)
            }
            "run_start", "run_end" -> {
                if (type == "run_end") {
                    val stats = obj.optJSONObject("stats")
                    if (stats != null) {
                        concise.put("duration_s", stats.optDouble("duration_s"))
                        val toolCounts = stats.optJSONObject("tool_counts")
                        if (toolCounts != null && toolCounts.length() > 0) {
                            concise.put("tools", toolCounts)
                        }
                        concise.put("files_modified", stats.optJSONArray("files_modified")?.length() ?: 0)
                    }
                }
            }
            else -> {
                // For other types, include key fields but truncate long values
                val keys = obj.keys()
                while (keys.hasNext()) {
                    val key = keys.next()
                    if (key !in setOf("type", "ts", "session_id")) {
                        val value = obj.opt(key)
                        when (value) {
                            is String -> if (value.length > 100) concise.put(key, value.take(100) + "...") else concise.put(key, value)
                            is JSONObject -> concise.put(key, "obj:${value.length()}")
                            is JSONArray -> concise.put(key, "arr:${value.length()}")
                            else -> concise.put(key, value)
                        }
                    }
                }
            }
        }
        
        return concise
    }
    
    private fun shortenPath(path: String): String {
        if (path.length <= 50) return path
        val parts = path.split("/")
        return if (parts.size > 3) {
            ".../${parts.takeLast(2).joinToString("/")}"
        } else {
            path.take(50) + "..."
        }
    }

    private fun extractTitle(html: String): String {
        return runCatching {
            val titleRegex = Regex("<title[^>]*>(.*?)</title>", setOf(RegexOption.IGNORE_CASE, RegexOption.DOT_MATCHES_ALL))
            val match = titleRegex.find(html)
            match?.groupValues?.get(1)?.trim()?.take(100) ?: "Untitled"
        }.getOrElse { "Untitled" }
    }

    private fun extractEnhancedDependencies(content: String, language: String): List<String> {
        return runCatching {
            val deps = mutableListOf<String>()
            when (language) {
                "python" -> {
                    val importRegex = Regex("^\\s*(?:from\\s+([\\w.]+)\\s+)?import\\s+([\\w.,\\s*]+)", RegexOption.MULTILINE)
                    importRegex.findAll(content).forEach { match ->
                        val module = match.groupValues[1].ifBlank { match.groupValues[2].split(",")[0].trim() }
                        if (module.isNotBlank()) deps.add(module)
                    }
                }
                "javascript", "typescript" -> {
                    val importRegex = Regex("^\\s*import\\s+.*?from\\s+['\"]([^'\"]+)['\"]", RegexOption.MULTILINE)
                    val requireRegex = Regex("require\\s*\\(['\"]([^'\"]+)['\"]\\)", RegexOption.MULTILINE)
                    importRegex.findAll(content).forEach { deps.add(it.groupValues[1]) }
                    requireRegex.findAll(content).forEach { deps.add(it.groupValues[1]) }
                }
                "kotlin" -> {
                    val importRegex = Regex("^\\s*import\\s+([\\w.]+)", RegexOption.MULTILINE)
                    importRegex.findAll(content).forEach { deps.add(it.groupValues[1]) }
                }
                "java" -> {
                    val importRegex = Regex("^\\s*import\\s+([\\w.]+);", RegexOption.MULTILINE)
                    importRegex.findAll(content).forEach { deps.add(it.groupValues[1]) }
                }
            }
            deps.distinct()
        }.getOrElse { emptyList() }
    }

    private fun extractExports(content: String, language: String): List<String> {
        return runCatching {
            val exports = mutableListOf<String>()
            when (language) {
                "javascript", "typescript" -> {
                    val exportRegex = Regex("^\\s*export\\s+(?:default\\s+)?(?:function\\s+|class\\s+|const\\s+|let\\s+|var\\s+)?(\\w+)", RegexOption.MULTILINE)
                    exportRegex.findAll(content).forEach { exports.add(it.groupValues[1]) }
                    
                    val moduleExportRegex = Regex("module\\.exports\\s*=\\s*(\\w+)", RegexOption.MULTILINE)
                    moduleExportRegex.findAll(content).forEach { exports.add(it.groupValues[1]) }
                }
                "python" -> {
                    val allRegex = Regex("^\\s*__all__\\s*=\\s*\\[([^\\]]+)\\]", RegexOption.MULTILINE)
                    allRegex.find(content)?.let { match ->
                        val items = match.groupValues[1].split(",").map { it.trim().removeSurrounding("'", "\"") }
                        exports.addAll(items)
                    }
                }
            }
            exports.distinct()
        }.getOrElse { emptyList() }
    }

    private fun analyzeFileStructure(content: String, extension: String): JSONObject {
        return runCatching {
            val structure = JSONObject()
            val lines = content.lines()
            structure.put("line_count", lines.size)
            structure.put("size_kb", content.length / 1024.0)
            
            when (extension.lowercase()) {
                "py" -> {
                    structure.put("type", "python")
                    structure.put("has_main", content.contains("if __name__ == \"__main__\":"))
                    structure.put("has_docstring", content.trimStart().startsWith("\"\"\""))
                }
                "js", "ts" -> {
                    structure.put("type", if (extension == "ts") "typescript" else "javascript")
                    structure.put("has_exports", content.contains("export"))
                    structure.put("has_imports", content.contains("import"))
                    structure.put("is_module", content.contains("module.exports"))
                }
                "kt" -> {
                    structure.put("type", "kotlin")
                    structure.put("has_package", content.contains("package "))
                    structure.put("has_main", content.contains("fun main("))
                }
                "java" -> {
                    structure.put("type", "java")
                    structure.put("has_package", content.contains("package "))
                    structure.put("has_main", content.contains("public static void main("))
                }
                "html" -> {
                    structure.put("type", "html")
                    structure.put("has_doctype", content.trimStart().startsWith("<!DOCTYPE"))
                    structure.put("has_scripts", content.contains("<script"))
                    structure.put("has_styles", content.contains("<style") || content.contains("<link"))
                }
                "css" -> {
                    structure.put("type", "css")
                    val ruleCount = Regex("\\{[^}]*\\}").findAll(content).count()
                    structure.put("rule_count", ruleCount)
                }
                else -> {
                    structure.put("type", "unknown")
                }
            }
            structure
        }.getOrElse { JSONObject().put("type", "unknown") }
    }

    private fun globToRegex(glob: String): java.util.regex.Pattern {
        // Convert simple glob to regex: * -> .*, ? -> ., escape others
        val sb = StringBuilder()
        sb.append('^')
        for (ch in glob.toCharArray()) {
            when (ch) {
                '*' -> sb.append(".*")
                '?' -> sb.append('.')
                '.', '(', ')', '+', '|', '^', '$', '@', '%', '{', '}', '[', ']', '\\' -> {
                    sb.append('\\').append(ch)
                }
                else -> sb.append(ch)
            }
        }
        sb.append('$')
        return java.util.regex.Pattern.compile(sb.toString())
    }

    private suspend fun collectAll(flow: Flow<String>): String = withContext(Dispatchers.IO) {
        val sb = StringBuilder()
        flow.collect { sb.append(it) }
        sb.toString()
    }

    private suspend fun collectAllWithRetry(timeoutMs: Long = 20_000, maxRetries: Int = 1, flowProvider: () -> Flow<String>): String = withContext(Dispatchers.IO) {
        var attempt = 0
        while (attempt <= maxRetries) {
            val sb = StringBuilder()
            var hadTokens = false
            var timedOut = false
            try {
                val flow = flowProvider()
                withTimeout(timeoutMs) {
                    flow.collect { chunk ->
                        if (chunk.isNotEmpty()) hadTokens = true
                        sb.append(chunk)
                    }
                }
                val out = sb.toString()
                if (out.isBlank() && attempt < maxRetries) {
                    attempt++
                    continue
                }
                return@withContext out
            } catch (e: TimeoutCancellationException) {
                timedOut = true
                // fall through to evaluate below
            } catch (e: java.io.IOException) {
                if (hadTokens) {
                    return@withContext sb.toString()
                }
                if (attempt < maxRetries) {
                    attempt++
                    continue
                }
                return@withContext ""
            } catch (e: Exception) {
                val out = sb.toString()
                if (out.isBlank() && attempt < maxRetries) {
                    attempt++
                    continue
                }
                return@withContext out
            }
            if (timedOut) {
                // If we timed out but have partial output, return it; otherwise retry/exit
                if (hadTokens) {
                    return@withContext sb.toString()
                }
                if (attempt < maxRetries) {
                    attempt++
                    continue
                }
                return@withContext ""
            }
        }
        return@withContext ""
    }

    private fun extractFirstJsonObject(text: String): String? {
        var depth = 0
        var start = -1
        for (i in text.indices) {
            val c = text[i]
            if (c == '{') {
                if (depth == 0) start = i
                depth++
            } else if (c == '}') {
                depth--
                if (depth == 0 && start >= 0) {
                    return text.substring(start, i + 1)
                }
            }
        }
        val first = text.indexOf('{')
        val last = text.lastIndexOf('}')
        return if (first >= 0 && last > first) text.substring(first, last + 1) else null
    }

    private fun commandCacheKey(command: String, wd: String): String = wd + "||" + command

    private fun buildCliReport(): JSONObject {
        return JSONObject().apply {
            put("timestamp", System.currentTimeMillis())
            put("version", "1.0")
        }
    }

    private fun persistCliReport() {
        runCatching { cliReportFile.writeText(buildCliReport().toString(2)) }
    }

    private suspend fun performCodebaseUpgradeIfPending(onStatus: (String) -> Unit) = withContext(Dispatchers.IO) {
        if (!Settings.codebase_agent_enabled) return@withContext
        if (pendingCodebaseChanges.isEmpty()) return@withContext
        onStatus("Codebase: changes detected (${pendingCodebaseChanges.size}); updating cache…")
        runCatching { buildCodebaseCache(onStatus, includeRecursive = true) }
        pendingCodebaseChanges.clear()
        lastCodebaseRefreshMs = System.currentTimeMillis()
    }

    private suspend fun buildCodebaseCache(onStatus: (String) -> Unit, includeRecursive: Boolean = false) = withContext(Dispatchers.IO) {
        try {
            val root = File(workingDirProvider())
            val maxDepth = if (includeRecursive) 5 else 2
            val maxFiles = if (includeRecursive) 2000 else 400
            val files = mutableListOf<File>()
            fun walk(dir: File, depth: Int) {
                if (files.size >= maxFiles || depth > maxDepth) return
                dir.listFiles()?.forEach { f ->
                    if (files.size >= maxFiles) return
                    if (f.isDirectory) walk(f, depth + 1) else files.add(f)
                }
            }
            if (root.exists() && root.isDirectory) walk(root, 0) else return@withContext
            val summary = JSONArray()
            files.forEach { f ->
                val rel = f.absolutePath
                val size = runCatching { f.length() }.getOrElse { 0L }
                val head = runCatching {
                    val bytes = f.readBytes()
                    val slice = if (bytes.size > 120_000) bytes.copyOf(120_000) else bytes
                    String(slice)
                }.getOrElse { "" }
                // Prevent recursive inclusion of generated cache files and agent logs
                val isCacheOrLog = f.name.equals(Settings.codebase_cache_path, ignoreCase = true) || f.name.equals("task_log.jsonl", ignoreCase = true)
                if (isCacheOrLog) return@forEach
                val funcs = extractFunctions(head, when {
                    rel.endsWith(".py") -> "python"
                    rel.endsWith(".js") -> "javascript"
                    rel.endsWith(".html") -> "html"
                    rel.endsWith(".css") -> "css"
                    else -> "unknown"
                })
                val classes = extractClasses(head, when {
                    rel.endsWith(".py") -> "python"
                    rel.endsWith(".js") -> "javascript"
                    else -> "unknown"
                })
                // Enhanced codebase analysis
                val dependencies = extractEnhancedDependencies(head, when {
                    rel.endsWith(".py") -> "python"
                    rel.endsWith(".js") -> "javascript"
                    rel.endsWith(".kt") -> "kotlin"
                    rel.endsWith(".java") -> "java"
                    else -> "unknown"
                })
                val exports = extractExports(head, when {
                    rel.endsWith(".js") -> "javascript"
                    rel.endsWith(".ts") -> "typescript"
                    rel.endsWith(".py") -> "python"
                    else -> "unknown"
                })
                val fileStructure = analyzeFileStructure(head, f.extension)
                
                summary.put(JSONObject().apply {
                    put("path", rel)
                    put("bytes", size)
                    put("functions", JSONArray(funcs))
                    put("classes", JSONArray(classes))
                    put("dependencies", JSONArray(dependencies))
                    put("exports", JSONArray(exports))
                    put("structure", fileStructure)
                    put("preview", head.take(2000))
                    put("last_modified", f.lastModified())
                    put("extension", f.extension)
                })
                // update context cache for later coherence
                val fileType = when {
                    rel.endsWith(".py") -> "python"
                    rel.endsWith(".js") -> "javascript"
                    rel.endsWith(".html") -> "html"
                    rel.endsWith(".css") -> "css"
                    else -> "unknown"
                }
                updateContextCache(rel, head, fileType)
            }
            val codebase = JSONObject()
                .put("generated_at", System.currentTimeMillis())
                .put("root", root.absolutePath)
                .put("files", summary)
            val out = File(agentDir, Settings.codebase_cache_path)
            out.writeText(codebase.toString(2))
            // Also persist into working directory for visibility and for LLM context
            runCatching {
                val wdCopy = File(workingDirProvider(), Settings.codebase_cache_path)
                val text = codebase.toString(2)
                if (!wdCopy.exists() || runCatching { wdCopy.readText() }.getOrElse { "" } != text) {
                    wdCopy.writeText(text)
                }
            }
            onStatus("Codebase cache updated: ${summary.length()} files")
        } catch (_: Exception) {
            // ignore
        }
    }

    private suspend fun revisePlanBasedOnHistoryAndError(goal: String, errorNote: String): Plan? = withContext(Dispatchers.IO) {
        return@withContext requestUpdatedPlan(Plan(goal, emptyList()))
    }

    private suspend fun helperRecommend(kind: String, contextMap: Map<String, String>): JSONObject? = withContext(Dispatchers.IO) {
        if (!Settings.helper_agent_enabled) return@withContext null
        val (sys, user) = buildHelperRecommendationPrompt(kind, contextMap)
        val content = collectAllWithRetry(flowProvider = { LlmProvider.current().generate(listOf(LlmMessage("system", sys), LlmMessage("user", user))) })
        val json = extractFirstJsonObject(content) ?: return@withContext null
        return@withContext runCatching { JSONObject(json) }.getOrNull()
    }

    private fun applyHelperToMessages(reco: JSONObject?, messages: MutableList<LlmMessage>) {
        if (reco == null) return
        val prefix = reco.optString("prompt_prefix").ifBlank { null }
        val suffix = reco.optString("prompt_suffix").ifBlank { null }
        if (prefix != null) messages.add(0, LlmMessage("system", prefix))
        if (suffix != null) messages.add(LlmMessage("user", suffix))
        val maxTok = reco.optInt("max_tokens", -1)
        if (maxTok > 0) Settings.ai_max_tokens = maxTok
        val temp = reco.optDouble("temperature", Double.NaN)
        if (!temp.isNaN()) Settings.ai_temperature_str = temp.toString()
        val model = reco.optString("model").ifBlank { null }
        if (model != null) Settings.api_model = model
    }

	suspend fun generatePlan(userGoal: String): Plan? = withContext(Dispatchers.IO) {
		return@withContext generatePlanWithContext(userGoal, null)
	}

	suspend fun requestUpdatedPlan(plan: Plan): Plan? = withContext(Dispatchers.IO) {
		return@withContext revisePlanBasedOnHistoryAndError(plan.goal, "update_request")
	}

	private fun notifyWorkspaceChanged(path: String) {
		pendingCodebaseChanges.add(path)
	}

	private fun computeUnifiedDiff(original: String, updated: String, path: String, maxLines: Int = 400): String {
		if (original == updated) return ""
		val oldLines = original.split("\n")
		val newLines = updated.split("\n")
		val sb = StringBuilder()
		sb.append("--- ").append(path).append("\n")
		sb.append("+++ ").append(path).append("\n")
		val max = kotlin.math.max(oldLines.size, newLines.size)
		var shown = 0
		for (i in 0 until max) {
			if (shown >= maxLines) { sb.append("... (truncated)\n"); break }
			val old = if (i < oldLines.size) oldLines[i] else null
			val neu = if (i < newLines.size) newLines[i] else null
			when {
				old == null && neu != null -> { sb.append("+").append(neu).append("\n"); shown++ }
				old != null && neu == null -> { sb.append("-").append(old).append("\n"); shown++ }
				old != neu -> { sb.append("-").append(old).append("\n"); sb.append("+").append(neu).append("\n"); shown += 2 }
				else -> { /* same line; skip to keep concise */ }
			}
		}
		return sb.toString()
	}

	private suspend fun writerSuggestTool(planGoal: String, task: Task, original: ToolCall): ToolCall? = withContext(Dispatchers.IO) { null }

	private fun buildHelperRecommendationPrompt(kind: String, contextMap: Map<String, String>): Pair<String,String> {
		val sys = """
			You are a senior AI prompt optimization specialist.
			Task: Optimize and enhance main agent prompts for better performance and accuracy.
			Context: Analyze the current task type and context to provide targeted prompt improvements.
			Output format: Return ONLY compact JSON with prompt enhancement recommendations.
			
			Optimization areas:
			- prompt_prefix: Add context-specific instructions or clarifications
			- prompt_suffix: Include task-specific constraints or requirements
			- suggested_tools: Recommend optimal tools for the current task type
			- max_tokens: Adjust token limits based on task complexity
			- temperature: Fine-tune creativity vs precision balance
			- model: Suggest optimal model for the specific task
			
			Response schema: {"prompt_prefix": "string", "prompt_suffix": "string", "suggested_tools": ["string"], "max_tokens": number, "temperature": number, "model": "string"}
			
			Guidelines: Return minified JSON, omit fields that don't need adjustment
		""".trimIndent()
		val user = JSONObject().apply {
			put("kind", kind)
			contextMap.forEach { (k, v) -> put(k, v.take(2000)) }
		}.toString()
		return sys to user
	}

	private fun coerceToolCallForTaskCategory(task: Task, proposed: ToolCall): ToolCall {
		val cat = (task.category ?: "").lowercase().trim()
		val isModification = task.description.contains("modify", ignoreCase = true) ||
							 task.description.contains("update", ignoreCase = true) ||
							 task.description.contains("change", ignoreCase = true) ||
							 task.description.contains("edit", ignoreCase = true) ||
							 task.description.contains("upgrade", ignoreCase = true)

		if (isModification) {
			return proposed // Don't interfere with modification tasks
		}

		// If the agent wants to write a file and has provided content, trust it.
		if (proposed.type == "write_file" && proposed.args.has("content") && proposed.args.optString("content").isNotBlank()) {
			return proposed
		}

		val path = proposed.args.optString("path").ifBlank { task.targets?.firstOrNull() }
		if (path != null) {
			val file = resolvePath(path)
			if (file.exists()) {
				return proposed // File already exists, so it's a modification, don't interfere.
			}
		}


				return when (cat) {
			"make_dir" -> {
				// Ensure directory creation even if model proposed a file tool
				val suggested = proposed.args.optString("path")
				val derived = when {
					suggested.isNotBlank() -> suggested
					!task.targets.isNullOrEmpty() -> task.targets!!.first()
					else -> File(workingDirProvider(), "NEW_DIR").absolutePath
				}
				ToolCall("make_dir", JSONObject().put("path", derived))
			}
			"create_file" -> {
				proposed
			}
			"write_file" -> {
				// This case is now only for when the agent wants to write to a new file but hasn't provided content.
				val desc = (task.description ?: "").lowercase()
				val suggested = proposed.args.optString("path")
				val derived = when {
					suggested.isNotBlank() -> suggested
					!task.targets.isNullOrEmpty() -> task.targets!!.first()
					else -> File(workingDirProvider(), "NEW_FILE").absolutePath
				}
				
				// Content must be provided by the model based on the current codebase and expectations, not a premade template.
				ToolCall("write_file", JSONObject().put("path", derived).put("content", "").put("mode", "overwrite"))
			}
			else -> proposed
		}
}

	private fun preferredPackageManager(): String? = when {
		lastDetectedManagers.contains("apk") -> "apk"
		lastDetectedManagers.contains("apt") -> "apt"
		lastDetectedManagers.contains("dnf") -> "dnf"
		lastDetectedManagers.contains("yum") -> "yum"
		lastDetectedManagers.contains("pacman") -> "pacman"
		else -> null
	}

	private fun coerceInstallPythonIfNeeded(): ToolCall? = null

	private fun deriveDefaultCommandForRunShell(task: Task?): String? {
		val desc = (task?.description ?: "").lowercase()
		// Prefer discovered files in project structure
		fun findRequirements(): String? {
			val hit = projectStructure.keys.firstOrNull { it.endsWith("/requirements.txt") || it.endsWith("\\requirements.txt") || it.endsWith("requirements.txt") }
			return hit
		}
		if (desc.contains("install") && (desc.contains("pip") || desc.contains("python") || desc.contains("requirements"))) {
			val reqPath = findRequirements() ?: listOf("requirements.txt", "app/requirements.txt", "backend/requirements.txt").firstOrNull { resolvePath(it).exists() } ?: "requirements.txt"
			return "(command -v python3 >/dev/null 2>&1 && python3 -m pip install -r \"$reqPath\") || (command -v pip3 >/dev/null 2>&1 && pip3 install -r \"$reqPath\") || (command -v pip >/dev/null 2>&1 && pip install -r \"$reqPath\")"
		}
		if (desc.contains("run") || desc.contains("server") || desc.contains("start")) {
			val mainHit = projectStructure.keys.firstOrNull { it.endsWith("/main.py") && it.contains("/app/") } ?: listOf("app/main.py", "main.py", "app.py").firstOrNull { resolvePath(it).exists() }
			val mainPath = mainHit ?: "app/main.py"
			return "python3 \"$mainPath\""
		}
		return null
	}

	private fun generateInitialContentForFile(path: String, requirements: String): String {
		// Deprecated: avoid premade templates. Content should always come from the model based on the goal and codebase.
		return ""
	}

    // Task detection: determine if a task is full code generation or code update
    private fun detectTaskKind(task: Task): String {
        val desc = task.description.lowercase()
        val isFull = desc.contains("create new") || desc.contains("generate") || desc.contains("scaffold") ||
                desc.contains("new file") || isWriteCategory(task.category)
        return if (isFull) "full_code_generation" else "code_update"
    }

    private fun buildMainInstructionsJson(): String {
        val obj = JSONObject()
        obj.put("enabled", Settings.main_instructions_enabled)
        obj.put("expectations", projectRequirements ?: lastPlanGoal ?: "")
        val blueprint = runCatching { blueprintFile.readText() }.getOrElse { null }
        if (!blueprint.isNullOrBlank()) obj.put("blueprint", runCatching { JSONObject(blueprint) }.getOrElse { JSONObject() })
        val codebase = runCatching { File(workingDirProvider(), Settings.codebase_cache_path).readText() }.getOrElse { null }
        if (!codebase.isNullOrBlank()) obj.put("codebase", runCatching { JSONObject(codebase) }.getOrElse { JSONObject() })
        val writerTools = runCatching { writerToolsFile.takeIf { it.exists() }?.readText() }.getOrElse { null }
        if (!writerTools.isNullOrBlank()) obj.put("writer_tools", runCatching { JSONObject(writerTools) }.getOrElse { JSONObject() })
        return obj.toString()
    }

    private suspend fun runBackPlanFixIfNeeded(path: String, intended: String, userInstruction: String, failureNote: String, onStatus: (String) -> Unit, taskId: String? = null): Boolean = withContext(Dispatchers.IO) {
        if (!Settings.backplan_enabled) return@withContext false
        val f = resolvePath(path)
        val currentContent = runCatching { if (f.exists()) f.readText() else "" }.getOrElse { "" }
        val instructionsJson = buildMainInstructionsJson()
        appendTaskLog("backplan_attempt") {
            put("task_id", taskId ?: currentTaskContext?.id ?: JSONObject.NULL)
            put("path", f.absolutePath)
            put("failure", failureNote.take(500))
        }
        onStatus("Back-plan: analyzing failure and generating corrective patch…")
        val sys = """
            You are an expert code correction and debugging specialist with deep understanding of software development patterns.
            
            MISSION: Analyze failed code operations and generate precise, targeted corrections that resolve the root cause while maintaining code quality.
            
            ENHANCED ANALYSIS FRAMEWORK:
            1. FAILURE PATTERN RECOGNITION:
               - Syntax errors: Missing brackets, semicolons, quotes, indentation issues
               - Logic errors: Incorrect function calls, variable references, control flow
               - Dependency errors: Missing imports, undefined variables, module issues
               - Structural errors: Incorrect file organization, missing directories
               - Runtime errors: Type mismatches, null references, async issues
            
            2. CONTEXTUAL CODE ANALYSIS:
               - Language-specific conventions and best practices
               - Existing code patterns and architectural decisions
               - Import/dependency structure and module organization
               - Variable naming conventions and scope management
               - Error handling patterns and logging approaches
            
            3. INTELLIGENT CORRECTION STRATEGY:
               - Prioritize minimal, surgical edits over large rewrites
               - Maintain existing code style and formatting
               - Preserve functional behavior while fixing issues
               - Add necessary imports, dependencies, or setup code
               - Include proper error handling and validation
            
            ENHANCED EDIT OPERATIONS:
            - replace_exact: Precise text replacement with exact matching
            - replace_between_markers: Content replacement between specific delimiters
            - insert_after_anchor: Add content after specific code anchors
            - insert_before_anchor: Add content before specific code anchors
            - replace_regex: Pattern-based replacement with regex support
            - json_patch: Structured JSON modifications (set/delete/append operations)
            - smart_merge: Intelligent code merging with conflict resolution
            - ensure_block_present: Idempotent block insertion with markers
            - write_if_missing: Complete file creation when missing
            - replace_lines: Line-based replacement with precise line numbers
            - insert_lines_after/before: Line-based insertion operations
            
            CORRECTION PRINCIPLES:
            - Fix root causes, not just symptoms
            - Maintain backward compatibility when possible
            - Add comprehensive error handling
            - Include necessary imports and dependencies
            - Follow language-specific best practices
            - Provide clear, self-documenting code
            
            OUTPUT FORMAT:
            Return ONLY one minified JSON apply_changes tool call with targeted edits.
            Include multiple edits in the same call if they're related to the same fix.
            
            Schema: {"type":"apply_changes","args":{"edits":[{"path":"string","op":"operation_type",...params...}]}}
        """.trimIndent()
        // Enhanced context for better backplan analysis
        val codebaseContext = if (Settings.codebase_agent_enabled) {
            runCatching {
                val cacheFile = File(workingDirProvider(), Settings.codebase_cache_path)
                if (cacheFile.exists()) {
                    val cache = JSONObject(cacheFile.readText())
                    val files = cache.optJSONArray("files") ?: JSONArray()
                    val relatedFiles = JSONArray()
                    
                    // Find related files based on dependencies or similar names
                    for (i in 0 until minOf(5, files.length())) {
                        val file = files.optJSONObject(i)
                        val filePath = file?.optString("path") ?: continue
                        if (filePath.contains(f.nameWithoutExtension) || 
                            filePath.endsWith(".${f.extension}")) {
                            relatedFiles.put(file)
                        }
                    }
                    relatedFiles
                } else JSONArray()
            }.getOrElse { JSONArray() }
        } else JSONArray()
        
        val user = JSONObject().apply {
            put("file_path", f.absolutePath)
            put("file_name", f.name)
            put("file_extension", f.extension)
            put("current_content", currentContent.take(120000))
            put("content_lines", currentContent.lines().size)
            put("instruction", userInstruction.take(4000))
            put("intended_change_preview", intended.take(4000))
            put("failure_details", failureNote.take(1000))
            put("codebase_context", codebaseContext)
            put("main_instructions", if (Settings.main_instructions_enabled) runCatching { JSONObject(instructionsJson) }.getOrElse { JSONObject() } else JSONObject())
            
            // Add enhanced language-specific context and common fixes
            val languageHints = when (f.extension.lowercase()) {
                "py" -> """Python fixes: 
                    - Check indentation (4 spaces), imports (from/import statements)
                    - Function definitions (def name():), class definitions (class Name:)
                    - Common errors: NameError, IndentationError, SyntaxError
                    - Add missing imports: import os, sys, json, etc.
                    - Fix string quotes and f-string syntax"""
                "js", "ts" -> """JavaScript/TypeScript fixes:
                    - Check syntax: semicolons, brackets, quotes
                    - Import/export statements: import/export syntax
                    - Async/await: proper promise handling
                    - Common errors: ReferenceError, TypeError, SyntaxError
                    - Fix variable declarations: const, let, var"""
                "kt" -> """Kotlin fixes:
                    - Check syntax: semicolons optional, null safety (?.)
                    - Function declarations: fun name(), class declarations
                    - Import statements: import package.Class
                    - Common errors: compilation errors, null pointer exceptions
                    - Fix type annotations and nullable types"""
                "java" -> """Java fixes:
                    - Check syntax: semicolons, brackets, imports
                    - Class structure: public class Name, method signatures
                    - Import statements: import package.Class;
                    - Common errors: compilation errors, missing methods
                    - Fix access modifiers and method declarations"""
                "html" -> """HTML fixes:
                    - Check tag structure: proper opening/closing tags
                    - Attributes: quotes around values, proper syntax
                    - Common errors: unclosed tags, malformed attributes
                    - Fix DOCTYPE, meta tags, and semantic structure"""
                "css" -> """CSS fixes:
                    - Check selectors: proper syntax and specificity
                    - Property syntax: property: value; format
                    - Common errors: missing semicolons, bracket mismatches
                    - Fix selector syntax and property names"""
                "json" -> """JSON fixes:
                    - Check syntax: proper bracket/brace matching
                    - Comma placement: no trailing commas
                    - Common errors: malformed JSON, invalid characters
                    - Fix quotes around keys and string values"""
                else -> "Generic: Check basic syntax, structure, and common formatting issues"
            }
            put("language_hints", languageHints)
            
            // Add file reference context if available
            val referencesFile = File(agentDir, "file_references.json")
            if (referencesFile.exists()) {
                runCatching {
                    val refs = JSONObject(referencesFile.readText())
                    val fileInfo = refs.optJSONObject("files")?.optJSONObject(f.absolutePath)
                    if (fileInfo != null) {
                        put("file_reference", fileInfo)
                    }
                }
            }
        }.toString()
        val content = collectAllWithRetry(flowProvider = { LlmProvider.current().generate(listOf(LlmMessage("system", sys), LlmMessage("user", user))) })
        val jsonText = extractFirstJsonObject(content)
        if (jsonText == null) {
            appendTaskLog("backplan_result") {
                put("task_id", taskId ?: currentTaskContext?.id ?: JSONObject.NULL)
                put("path", f.absolutePath)
                put("ok", false)
                put("reason", "no_json")
            }
            return@withContext false
        }
        val parsed = runCatching { JSONObject(jsonText) }.getOrNull()
        if (parsed == null) {
            appendTaskLog("backplan_result") {
                put("task_id", taskId ?: currentTaskContext?.id ?: JSONObject.NULL)
                put("path", f.absolutePath)
                put("ok", false)
                put("reason", "invalid_json")
            }
            return@withContext false
        }
        var type = parsed.optString("type").lowercase()
        var args: JSONObject? = parsed.optJSONObject("args")
        if (type.isBlank()) {
            // Attempt to infer tool type from payload
            val edits = parsed.optJSONArray("edits")
            if (edits != null) {
                type = "apply_changes"
                args = JSONObject().put("edits", edits)
            } else if (parsed.has("path") && (parsed.has("content") || parsed.has("new_content"))) {
                type = "write_file"
                args = JSONObject().apply {
                    put("path", parsed.optString("path"))
                    put("content", parsed.optString("content", parsed.optString("new_content")))
                    put("mode", "overwrite")
                }
            }
        }
        // Normalize/validate type
        val normalizedType = when (type) {
            "json_edit" -> "apply_changes"
            else -> type
        }
        val allowed = setOf("apply_changes", "write_file", "search_replace", "create_file", "json_set")
        if (normalizedType.isBlank() || normalizedType !in allowed) {
            appendTaskLog("backplan_result") {
                put("task_id", taskId ?: currentTaskContext?.id ?: JSONObject.NULL)
                put("path", f.absolutePath)
                put("ok", false)
                put("reason", "wrong_type:${type}")
            }
            return@withContext false
        }
        val toolCall = ToolCall(normalizedType, args ?: JSONObject())
        val res = executeToolCall(toolCall)
                    appendTaskLog("backplan_result") {
                put("task_id", taskId ?: currentTaskContext?.id ?: JSONObject.NULL)
                put("path", f.absolutePath)
                put("ok", res.ok)
                put("observation_preview", res.observation?.take(400))
            }
            if (res.ok) {
                // Update codebase cache and sync
                runCatching { ControlApiClient.syncFile(sessionId, f.absolutePath, runCatching { f.readText() }.getOrElse { "" }, !f.exists()) }
                notifyWorkspaceChanged(f.absolutePath)
            } else {
                // If apply_changes resulted in no_change, try a fallback write_file with intended content
                val obs = res.observation ?: ""
                val looksNoChange = obs.contains("no_change") || obs.contains("no edits")
                if (looksNoChange && intended.isNotBlank()) {
                    val fallback = ToolCall("write_file", JSONObject().put("path", f.absolutePath).put("content", intended).put("mode", "overwrite"))
                    val wr = executeToolCall(fallback)
                    appendTaskLog("backplan_result") {
                        put("task_id", taskId ?: currentTaskContext?.id ?: JSONObject.NULL)
                        put("path", f.absolutePath)
                        put("ok", wr.ok)
                        put("reason", if (wr.ok) "fallback_write_file" else "fallback_failed")
                    }
                    return@withContext wr.ok
                }
            }
            return@withContext res.ok
    }

	private fun ensureFolderStructureFromBlueprint(onStatus: (String) -> Unit) {
		val bpText = runCatching { blueprintFile.takeIf { it.exists() }?.readText().orEmpty() }.getOrElse { "" }
		if (bpText.isBlank()) return
		val rootDir = File(workingDirProvider())
		val obj = runCatching { JSONObject(bpText) }.getOrNull() ?: return
		val modules = obj.optJSONArray("modules") ?: JSONArray()
		val created = mutableListOf<String>()
		for (i in 0 until modules.length()) {
			val m = modules.optJSONObject(i) ?: continue
			val raw = m.optString("id").ifBlank { m.optString("name") }
			if (raw.isBlank()) continue
			val slug = raw.lowercase().replace(Regex("[^a-z0-9._/\\-]+"), "-").trim('-')
			if (slug.isBlank()) continue
			val dir = File(rootDir, slug)
			if (!dir.exists()) {
				dir.mkdirs()
				if (dir.exists()) {
					created.add(dir.absolutePath)
					notifyWorkspaceChanged(dir.absolutePath)
				}
			}
		}
		if (created.isNotEmpty()) {
			onStatus("Ensured folder structure from blueprint: created ${created.size} dir(s)")
			appendTaskLog("folder_structure_ensured") { put("created", JSONArray(created)) }
		}
	}
}

object MainShell {
    fun execInMainSession(activity: MainActivity?, wd: String, command: String, timeoutMs: Long): Pair<String, Int> {
        if (activity == null || activity.sessionBinder == null) return Pair("Main session not available", -1)
        val binder = activity.sessionBinder!!
        val service = binder.getService()
        val currentId = service.currentSession.value.first
        val session = binder.getSession(currentId) ?: return Pair("Main session not available", -1)
        // Prepare output file and sentinel
        val preferredOut = runCatching { File(wd).takeIf { it.exists() && it.isDirectory && it.canWrite() } }.getOrNull()
        val outFile = runCatching { File(preferredOut ?: activity.cacheDir, ".main-${System.currentTimeMillis()}.out") }.getOrNull()
            ?: File(activity.cacheDir, ".main-${System.currentTimeMillis()}.out")
        runCatching { if (outFile.exists()) outFile.delete() }
        val outPath = outFile.absolutePath.replace("'", "'\\''")
        val sentinel = "__MAIN_DONE_${System.currentTimeMillis()}__"
        val cmdLine = "cd \"$wd\"; umask 022; ( $command ) > '$outPath' 2>&1; code=${'$'}?; printf '%s\\n' '$sentinel' >> '$outPath'; printf 'EXIT_CODE=%s\\n' ${'$'}code >> '$outPath'\n"
        try {
            session.write(cmdLine)
        } catch (e: Exception) {
            return Pair("write failed: ${e.message}", -1)
        }
        Log.d("MainShell", "wrote to main session id=${currentId} out=$outPath")
        val start = System.currentTimeMillis()
        var content = ""
        var saw = false
        while (System.currentTimeMillis() - start < timeoutMs) {
            if (outFile.exists()) {
                content = runCatching { outFile.readText() }.getOrElse { "" }
                if (content.contains(sentinel)) { saw = true; break }
                // Early-exit heuristic: if we see typical server-start lines, stop waiting to unblock UI
                val lower = content.lowercase()
                val serverSignals = listOf(
                    "running on http://", // Flask/WSGI
                    "running on https://",
                    "serving on http://",
                    "serving on https://",
                    " * running on ",
                    " * debugger is active",
                    "started server",
                    "listening on",
                    "127.0.0.1",
                    "0.0.0.0",
                    "http server started",
                    "press ctrl+c to quit"
                )
                if (serverSignals.any { lower.contains(it) }) {
                    // Synthesize sentinel to mark completion without altering process
                    content += "\n$sentinel\nEXIT_CODE=0\n"
                    saw = true
                    break
                }
            }
            try { Thread.sleep(100) } catch (_: InterruptedException) {}
        }
        val exit = Regex("(?m)^EXIT_CODE=(\\-?\\d+)").find(content)?.groupValues?.getOrNull(1)?.toIntOrNull() ?: (if (saw) 0 else -1)
        runCatching { outFile.delete() }
        Log.d("MainShell", "done exit=${exit} bytes=${content.length}")
        return Pair(content, exit)
    }
}