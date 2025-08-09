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
import androidx.compose.material.icons.filled.Folder
import androidx.compose.material.icons.filled.InsertDriveFile
import androidx.compose.material3.AlertDialog
import androidx.compose.material3.Button
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
) {
    val scope = rememberCoroutineScope()
    val entriesState = remember { mutableStateOf<List<File>>(emptyList()) }
    val showNewFolderDialog = remember { mutableStateOf(false) }
    val newFolderName = remember { mutableStateOf("") }
    val showDeleteConfirm = remember { mutableStateOf<File?>(null) }

    suspend fun load(path: String) {
        val dir = File(path)
        val files = withContext(Dispatchers.IO) {
            dir.listFiles()?.sortedWith(compareBy<File> { !it.isDirectory }.thenBy { it.name.lowercase() }) ?: emptyList()
        }
        entriesState.value = files
    }

    LaunchedEffect(currentPath) {
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
                IconButton(onClick = { showNewFolderDialog.value = true }) {
                    Icon(Icons.Default.CreateNewFolder, contentDescription = "New folder")
                }
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
                            if (file.isDirectory) {
                                onNavigate(file.absolutePath)
                            } else {
                                // Open in editor tab
                                FileOpenBus.open(file)
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
        var accum = if (currentPath.startsWith('/')) "/" else ""
        parts.forEachIndexed { idx, part ->
            val next = if (accum == "/") "$accum$part" else "$accum/$part"
            Text(
                text = if (idx == 0 && currentPath.startsWith('/')) "/" else part,
                modifier = Modifier
                    .padding(end = 8.dp)
                    .clickable { onNavigate(next) },
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