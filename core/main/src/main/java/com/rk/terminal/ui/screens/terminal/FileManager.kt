package com.rk.terminal.ui.screens.terminal

import android.webkit.MimeTypeMap
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxHeight
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.items
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.ArrowBack
import androidx.compose.material.icons.filled.CreateNewFolder
import androidx.compose.material.icons.filled.Delete
import androidx.compose.material.icons.filled.Edit
import androidx.compose.material.icons.filled.Folder
import androidx.compose.material.icons.filled.InsertDriveFile
import androidx.compose.material.icons.filled.NoteAdd
import androidx.compose.material.icons.filled.ContentCopy
import androidx.compose.material.icons.filled.ContentCut
import androidx.compose.material.icons.filled.ContentPaste
import androidx.compose.material3.AlertDialog
import androidx.compose.material3.Button
import androidx.compose.material3.Checkbox
import androidx.compose.material3.ExperimentalMaterial3Api
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedTextField
import androidx.compose.material3.Scaffold
import androidx.compose.material3.Text
import androidx.compose.material3.TopAppBar
import androidx.compose.material3.TopAppBarDefaults
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberCoroutineScope
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.unit.dp
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext
import java.io.File

@OptIn(ExperimentalMaterial3Api::class)
@Composable
fun FileManagerView(
    currentPath: String,
    onNavigate: (String) -> Unit,
    onEditFile: (File) -> Unit,
) {
    val scope = rememberCoroutineScope()
    val entriesState = remember { mutableStateOf<List<File>>(emptyList()) }
    val showNewFolderDialog = remember { mutableStateOf(false) }
    val newFolderName = remember { mutableStateOf("") }
    val showDeleteConfirm = remember { mutableStateOf<File?>(null) }
    val showNewFileDialog = remember { mutableStateOf(false) }
    val newFileName = remember { mutableStateOf("") }

    // Selection state
    val selectedPaths = remember { mutableStateOf(setOf<String>()) }
    val hasSelection = selectedPaths.value.isNotEmpty()

    // Clipboard for copy/cut
    val clipboardItems = remember { mutableStateOf<List<File>>(emptyList()) }
    val clipboardAction = remember { mutableStateOf<String?>(null) } // "copy" or "cut"

    suspend fun load(path: String) {
        val dir = File(path)
        val files = withContext(Dispatchers.IO) {
            dir.listFiles()?.sortedWith(compareBy<File> { !it.isDirectory }.thenBy { it.name.lowercase() }) ?: emptyList()
        }
        entriesState.value = files
        // Clear selection if items no longer exist
        selectedPaths.value = selectedPaths.value.filter { p -> files.any { it.absolutePath == p } }.toSet()
    }

    LaunchedEffect(currentPath) {
        load(currentPath)
    }

    fun toggleSelection(file: File) {
        selectedPaths.value = if (selectedPaths.value.contains(file.absolutePath)) {
            selectedPaths.value - file.absolutePath
        } else {
            selectedPaths.value + file.absolutePath
        }
    }

    suspend fun copyRecursively(src: File, dst: File) {
        if (src.isDirectory) {
            if (!dst.exists()) dst.mkdirs()
            src.listFiles()?.forEach { child ->
                copyRecursively(child, File(dst, child.name))
            }
        } else {
            withContext(Dispatchers.IO) {
                dst.parentFile?.mkdirs()
                src.inputStream().use { input ->
                    dst.outputStream().use { out ->
                        input.copyTo(out)
                    }
                }
            }
        }
    }

    suspend fun pasteInto(targetDir: File) {
        val action = clipboardAction.value ?: return
        val items = clipboardItems.value
        if (items.isEmpty()) return
        withContext(Dispatchers.IO) {
            for (item in items) {
                val dest = File(targetDir, item.name)
                if (dest.exists()) dest.deleteRecursively()
                if (action == "copy") {
                    copyRecursively(item, dest)
                } else if (action == "cut") {
                    item.renameTo(dest)
                }
            }
        }
        clipboardAction.value = null
        clipboardItems.value = emptyList()
        load(currentPath)
    }

    Scaffold(topBar = {
        TopAppBar(
            title = { Text(currentPath, maxLines = 1) },
            navigationIcon = {
                IconButton(onClick = {
                    val parent = File(currentPath).parentFile
                    if (parent != null && parent.exists()) onNavigate(parent.absolutePath)
                }) { Icon(Icons.Default.ArrowBack, contentDescription = "Up") }
            },
            actions = {
                // New file
                IconButton(onClick = { showNewFileDialog.value = true }) {
                    Icon(Icons.Default.NoteAdd, contentDescription = "New file")
                }
                // New folder
                IconButton(onClick = { showNewFolderDialog.value = true }) {
                    Icon(Icons.Default.CreateNewFolder, contentDescription = "New folder")
                }
                // Copy/Cut enabled when selection exists
                IconButton(onClick = {
                    if (hasSelection) {
                        clipboardAction.value = "copy"
                        clipboardItems.value = entriesState.value.filter { selectedPaths.value.contains(it.absolutePath) }
                        selectedPaths.value = emptySet()
                    }
                }, enabled = hasSelection) { Icon(Icons.Default.ContentCopy, contentDescription = "Copy") }
                IconButton(onClick = {
                    if (hasSelection) {
                        clipboardAction.value = "cut"
                        clipboardItems.value = entriesState.value.filter { selectedPaths.value.contains(it.absolutePath) }
                        selectedPaths.value = emptySet()
                    }
                }, enabled = hasSelection) { Icon(Icons.Default.ContentCut, contentDescription = "Cut") }
                // Paste visible when clipboard has items
                IconButton(onClick = {
                    scope.launch { pasteInto(File(currentPath)) }
                }, enabled = clipboardItems.value.isNotEmpty()) { Icon(Icons.Default.ContentPaste, contentDescription = "Paste") }
            },
            colors = TopAppBarDefaults.topAppBarColors(
                containerColor = androidx.compose.ui.graphics.Color.Transparent
            )
        )
    }) { padding ->
        LazyColumn(
            modifier = Modifier
                .padding(padding)
                .fillMaxSize()
        ) {
            item {
                Breadcrumbs(currentPath = currentPath, onNavigate = onNavigate)
            }
            items(entriesState.value, key = { it.absolutePath }) { file ->
                Row(
                    modifier = Modifier
                        .fillMaxWidth()
                        .clickable {
                            if (selectedPaths.value.isNotEmpty()) {
                                toggleSelection(file)
                            } else if (file.isDirectory) {
                                onNavigate(file.absolutePath)
                            }
                        }
                        .padding(12.dp),
                    verticalAlignment = Alignment.CenterVertically
                ) {
                    Icon(
                        imageVector = if (file.isDirectory) Icons.Default.Folder else Icons.Default.InsertDriveFile,
                        contentDescription = null
                    )
                    Text(
                        text = file.name.ifBlank { file.absolutePath },
                        style = MaterialTheme.typography.bodyLarge,
                        modifier = Modifier.padding(start = 12.dp)
                    )
                    Spacer(modifier = Modifier.weight(1f))
                    // Selection checkbox
                    Checkbox(
                        checked = selectedPaths.value.contains(file.absolutePath),
                        onCheckedChange = { toggleSelection(file) }
                    )
                    if (file.isFile) {
                        IconButton(onClick = {
                            onEditFile(file)
                            TabSwitchBus.request(2) // Editor tab index
                        }) {
                            Icon(Icons.Default.Edit, contentDescription = "Edit")
                        }
                    }
                    IconButton(onClick = { showDeleteConfirm.value = file }) {
                        Icon(Icons.Default.Delete, contentDescription = "Delete")
                    }
                }
            }
        }
    }

    if (showNewFolderDialog.value) {
        AlertDialog(
            onDismissRequest = { showNewFolderDialog.value = false },
            title = { Text("New folder") },
            text = {
                OutlinedTextField(
                    value = newFolderName.value,
                    onValueChange = { newFolderName.value = it },
                    singleLine = true,
                    label = { Text("Folder name") }
                )
            },
            confirmButton = {
                Button(onClick = {
                    val name = newFolderName.value.trim()
                    if (name.isNotEmpty()) {
                        scope.launch(Dispatchers.IO) {
                            runCatching {
                                File(currentPath, name).mkdirs()
                            }.onSuccess {
                                scope.launch { load(currentPath) }
                            }
                        }
                    }
                    newFolderName.value = ""
                    showNewFolderDialog.value = false
                }) { Text("Create") }
            },
            dismissButton = {
                Button(onClick = {
                    newFolderName.value = ""
                    showNewFolderDialog.value = false
                }) { Text("Cancel") }
            }
        )
    }

    if (showNewFileDialog.value) {
        AlertDialog(
            onDismissRequest = { showNewFileDialog.value = false },
            title = { Text("New file") },
            text = {
                OutlinedTextField(
                    value = newFileName.value,
                    onValueChange = { newFileName.value = it },
                    singleLine = true,
                    label = { Text("File name") }
                )
            },
            confirmButton = {
                Button(onClick = {
                    val name = newFileName.value.trim()
                    if (name.isNotEmpty()) {
                        scope.launch(Dispatchers.IO) {
                            runCatching {
                                val f = File(currentPath, name)
                                if (!f.exists()) f.parentFile?.mkdirs()
                                f.writeText("")
                            }.onSuccess {
                                scope.launch { load(currentPath) }
                            }
                        }
                    }
                    newFileName.value = ""
                    showNewFileDialog.value = false
                }) { Text("Create") }
            },
            dismissButton = {
                Button(onClick = {
                    newFileName.value = ""
                    showNewFileDialog.value = false
                }) { Text("Cancel") }
            }
        )
    }

    showDeleteConfirm.value?.let { target ->
        AlertDialog(
            onDismissRequest = { showDeleteConfirm.value = null },
            title = { Text("Delete") },
            text = { Text("Delete ${target.name}? This cannot be undone.") },
            confirmButton = {
                Button(onClick = {
                    scope.launch(Dispatchers.IO) {
                        runCatching { target.deleteRecursively() }
                            .onSuccess {
                                scope.launch { load(currentPath) }
                            }
                    }
                    showDeleteConfirm.value = null
                }) { Text("Delete") }
            },
            dismissButton = {
                Button(onClick = { showDeleteConfirm.value = null }) { Text("Cancel") }
            }
        )
    }
}

@Composable
private fun Breadcrumbs(currentPath: String, onNavigate: (String) -> Unit) {
    val parts = currentPath.split('/').filter { it.isNotBlank() }
    Row(
        modifier = Modifier
            .fillMaxWidth()
            .padding(horizontal = 12.dp, vertical = 4.dp),
        verticalAlignment = Alignment.CenterVertically,
        horizontalArrangement = Arrangement.Start
    ) {
        var running = if (currentPath.startsWith('/')) "/" else ""
        if (currentPath.startsWith('/')) {
            androidx.compose.material3.Text(
                text = "/",
                modifier = Modifier
                    .padding(end = 8.dp)
                    .clickable { onNavigate("/") },
                color = MaterialTheme.colorScheme.primary
            )
            running = "/"
        }
        parts.forEach { part ->
            running = if (running == "/") "/$part" else if (running.isEmpty()) part else "$running/$part"
            androidx.compose.material3.Text(
                text = part,
                modifier = Modifier
                    .padding(end = 8.dp)
                    .clickable { onNavigate(running) },
                color = MaterialTheme.colorScheme.primary
            )
        }
    }
}

// Simple bus to signal file opening to the editor pane
object FileOpenBus {
    private val selectedFileState = mutableStateOf<File?>(null)
    fun open(file: File) { selectedFileState.value = file }
    @Composable
    fun current(): File? = selectedFileState.value
}

// Bus to switch tabs by index (0: Terminal, 1: Files, 2: Editor, 3: Chat)
object TabSwitchBus {
    private val targetTab = mutableStateOf<Int?>(null)
    fun request(index: Int) { targetTab.value = index }
    @Composable
    fun pending(): Int? = targetTab.value
    fun clear() { targetTab.value = null }
}