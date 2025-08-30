package com.rk.terminal.ssh

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
import androidx.compose.material.icons.filled.Download
import androidx.compose.material.icons.filled.Upload
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
import kotlinx.coroutines.launch
import java.io.File
import java.text.SimpleDateFormat
import java.util.Date
import java.util.Locale

@OptIn(ExperimentalMaterial3Api::class)
@Composable
fun SshFileManagerView(
    sshFileManager: SshFileManager,
    currentPath: String,
    onNavigate: (String) -> Unit,
    onEditFile: (RemoteFile) -> Unit,
    onDownloadFile: (RemoteFile) -> Unit = {},
    onUploadFile: (String) -> Unit = {}
) {
    val scope = rememberCoroutineScope()
    val entriesState = remember { mutableStateOf<List<RemoteFile>>(emptyList()) }
    val showNewFolderDialog = remember { mutableStateOf(false) }
    val newFolderName = remember { mutableStateOf("") }
    val showDeleteConfirm = remember { mutableStateOf<RemoteFile?>(null) }
    val showNewFileDialog = remember { mutableStateOf(false) }
    val newFileName = remember { mutableStateOf("") }
    val isLoading = remember { mutableStateOf(false) }
    
    // Selection state
    val selectedPaths = remember { mutableStateOf(setOf<String>()) }
    val hasSelection = selectedPaths.value.isNotEmpty()

    suspend fun load(path: String) {
        isLoading.value = true
        try {
            val files = sshFileManager.listFiles(path)
            entriesState.value = files
            // Clear selection if items no longer exist
            selectedPaths.value = selectedPaths.value.filter { p -> 
                files.any { it.path == p } 
            }.toSet()
        } finally {
            isLoading.value = false
        }
    }

    LaunchedEffect(currentPath) {
        load(currentPath)
    }

    fun toggleSelection(file: RemoteFile) {
        selectedPaths.value = if (selectedPaths.value.contains(file.path)) {
            selectedPaths.value - file.path
        } else {
            selectedPaths.value + file.path
        }
    }

    Scaffold(
        topBar = {
            TopAppBar(
                title = { Text(currentPath) },
                navigationIcon = {
                    if (currentPath != "/") {
                        IconButton(onClick = {
                            val parentPath = File(currentPath).parent ?: "/"
                            onNavigate(parentPath)
                        }) {
                            Icon(Icons.Default.ArrowBack, contentDescription = "Back")
                        }
                    }
                },
                actions = {
                    if (hasSelection) {
                        IconButton(onClick = {
                            scope.launch {
                                selectedPaths.value.forEach { path ->
                                    val file = entriesState.value.find { it.path == path }
                                    if (file != null) {
                                        if (file.isDirectory) {
                                            sshFileManager.deleteDirectory(file.path)
                                        } else {
                                            sshFileManager.deleteFile(file.path)
                                        }
                                    }
                                }
                                selectedPaths.value = emptySet()
                                load(currentPath)
                            }
                        }) {
                            Icon(Icons.Default.Delete, contentDescription = "Delete selected")
                        }
                    }
                    
                    IconButton(onClick = { showNewFileDialog.value = true }) {
                        Icon(Icons.Default.NoteAdd, contentDescription = "New file")
                    }
                    
                    IconButton(onClick = { showNewFolderDialog.value = true }) {
                        Icon(Icons.Default.CreateNewFolder, contentDescription = "New folder")
                    }
                    
                    IconButton(onClick = { onUploadFile(currentPath) }) {
                        Icon(Icons.Default.Upload, contentDescription = "Upload file")
                    }
                },
                colors = TopAppBarDefaults.topAppBarColors(
                    containerColor = MaterialTheme.colorScheme.primaryContainer,
                    titleContentColor = MaterialTheme.colorScheme.onPrimaryContainer,
                )
            )
        }
    ) { paddingValues ->
        LazyColumn(
            modifier = Modifier
                .fillMaxSize()
                .padding(paddingValues),
            verticalArrangement = Arrangement.spacedBy(4.dp)
        ) {
            if (isLoading.value) {
                item {
                    Text(
                        text = "Loading...",
                        modifier = Modifier.padding(16.dp),
                        style = MaterialTheme.typography.bodyMedium
                    )
                }
            } else {
                items(entriesState.value) { file ->
                    val isSelected = selectedPaths.value.contains(file.path)
                    val dateFormat = SimpleDateFormat("MMM dd, yyyy HH:mm", Locale.getDefault())
                    
                    Row(
                        modifier = Modifier
                            .fillMaxWidth()
                            .clickable {
                                if (hasSelection) {
                                    toggleSelection(file)
                                } else if (file.isDirectory) {
                                    onNavigate(file.path)
                                } else {
                                    onEditFile(file)
                                }
                            }
                            .padding(horizontal = 16.dp, vertical = 8.dp),
                        verticalAlignment = Alignment.CenterVertically,
                        horizontalArrangement = Arrangement.spacedBy(12.dp)
                    ) {
                        if (hasSelection) {
                            Checkbox(
                                checked = isSelected,
                                onCheckedChange = { toggleSelection(file) }
                            )
                        }
                        
                        Icon(
                            imageVector = if (file.isDirectory) Icons.Default.Folder else Icons.Default.InsertDriveFile,
                            contentDescription = null,
                            tint = if (file.isDirectory) MaterialTheme.colorScheme.primary else MaterialTheme.colorScheme.onSurface
                        )
                        
                        Row(
                            modifier = Modifier.weight(1f),
                            verticalAlignment = Alignment.CenterVertically
                        ) {
                            Text(
                                text = file.name,
                                style = MaterialTheme.typography.bodyMedium,
                                modifier = Modifier.weight(1f)
                            )
                            
                            if (!file.isDirectory) {
                                Text(
                                    text = formatFileSize(file.size),
                                    style = MaterialTheme.typography.bodySmall,
                                    color = MaterialTheme.colorScheme.onSurfaceVariant
                                )
                            }
                        }
                        
                        if (!hasSelection) {
                            if (!file.isDirectory) {
                                IconButton(onClick = { onDownloadFile(file) }) {
                                    Icon(
                                        Icons.Default.Download,
                                        contentDescription = "Download",
                                        tint = MaterialTheme.colorScheme.primary
                                    )
                                }
                            }
                            
                            IconButton(onClick = { 
                                if (file.isDirectory) {
                                    onNavigate(file.path)
                                } else {
                                    onEditFile(file)
                                }
                            }) {
                                Icon(
                                    if (file.isDirectory) Icons.Default.Folder else Icons.Default.Edit,
                                    contentDescription = if (file.isDirectory) "Open" else "Edit",
                                    tint = MaterialTheme.colorScheme.primary
                                )
                            }
                        }
                    }
                }
            }
        }
    }

    // New Folder Dialog
    if (showNewFolderDialog.value) {
        AlertDialog(
            onDismissRequest = { showNewFolderDialog.value = false },
            title = { Text("Create New Folder") },
            text = {
                OutlinedTextField(
                    value = newFolderName.value,
                    onValueChange = { newFolderName.value = it },
                    label = { Text("Folder Name") },
                    singleLine = true
                )
            },
            confirmButton = {
                Button(
                    onClick = {
                        scope.launch {
                            val folderPath = if (currentPath.endsWith("/")) {
                                "$currentPath${newFolderName.value}"
                            } else {
                                "$currentPath/${newFolderName.value}"
                            }
                            
                            if (sshFileManager.createDirectory(folderPath)) {
                                load(currentPath)
                            }
                            
                            newFolderName.value = ""
                            showNewFolderDialog.value = false
                        }
                    },
                    enabled = newFolderName.value.isNotBlank()
                ) {
                    Text("Create")
                }
            },
            dismissButton = {
                Button(onClick = { 
                    showNewFolderDialog.value = false
                    newFolderName.value = ""
                }) {
                    Text("Cancel")
                }
            }
        )
    }

    // New File Dialog
    if (showNewFileDialog.value) {
        AlertDialog(
            onDismissRequest = { showNewFileDialog.value = false },
            title = { Text("Create New File") },
            text = {
                OutlinedTextField(
                    value = newFileName.value,
                    onValueChange = { newFileName.value = it },
                    label = { Text("File Name") },
                    singleLine = true
                )
            },
            confirmButton = {
                Button(
                    onClick = {
                        scope.launch {
                            val filePath = if (currentPath.endsWith("/")) {
                                "$currentPath${newFileName.value}"
                            } else {
                                "$currentPath/${newFileName.value}"
                            }
                            
                            if (sshFileManager.writeFileContent(filePath, "")) {
                                load(currentPath)
                            }
                            
                            newFileName.value = ""
                            showNewFileDialog.value = false
                        }
                    },
                    enabled = newFileName.value.isNotBlank()
                ) {
                    Text("Create")
                }
            },
            dismissButton = {
                Button(onClick = { 
                    showNewFileDialog.value = false
                    newFileName.value = ""
                }) {
                    Text("Cancel")
                }
            }
        )
    }

    // Delete Confirmation Dialog
    showDeleteConfirm.value?.let { file ->
        AlertDialog(
            onDismissRequest = { showDeleteConfirm.value = null },
            title = { Text("Delete ${if (file.isDirectory) "Folder" else "File"}") },
            text = { Text("Are you sure you want to delete '${file.name}'?") },
            confirmButton = {
                Button(
                    onClick = {
                        scope.launch {
                            val success = if (file.isDirectory) {
                                sshFileManager.deleteDirectory(file.path)
                            } else {
                                sshFileManager.deleteFile(file.path)
                            }
                            
                            if (success) {
                                load(currentPath)
                            }
                            
                            showDeleteConfirm.value = null
                        }
                    }
                ) {
                    Text("Delete")
                }
            },
            dismissButton = {
                Button(onClick = { showDeleteConfirm.value = null }) {
                    Text("Cancel")
                }
            }
        )
    }
}

private fun formatFileSize(bytes: Long): String {
    if (bytes < 1024) return "$bytes B"
    val kb = bytes / 1024.0
    if (kb < 1024) return "%.1f KB".format(kb)
    val mb = kb / 1024.0
    if (mb < 1024) return "%.1f MB".format(mb)
    val gb = mb / 1024.0
    return "%.1f GB".format(gb)
}