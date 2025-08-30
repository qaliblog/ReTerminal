package com.rk.terminal.ssh

import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.padding
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.Save
import androidx.compose.material.icons.filled.Download
import androidx.compose.material3.ExperimentalMaterial3Api
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Scaffold
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.material3.TextField
import androidx.compose.material3.TopAppBar
import androidx.compose.material3.TopAppBarDefaults
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberCoroutineScope
import androidx.compose.ui.Modifier
import androidx.compose.ui.unit.dp
import kotlinx.coroutines.launch
import android.os.Environment
import java.io.File

@OptIn(ExperimentalMaterial3Api::class)
@Composable
fun SshTextEditorView(remoteFile: RemoteFile, sshFileManager: SshFileManager) {
    val textState = remember { mutableStateOf("") }
    val scope = rememberCoroutineScope()
    val isSaving = remember { mutableStateOf(false) }
    val isLoading = remember { mutableStateOf(false) }

    LaunchedEffect(remoteFile) {
        isLoading.value = true
        try {
            val content = sshFileManager.readFileContent(remoteFile.path) ?: ""
            textState.value = content
        } finally {
            isLoading.value = false
        }
    }

    Scaffold(
        topBar = {
            TopAppBar(
                title = { Text(remoteFile.name) },
                actions = {
                    IconButton(
                        onClick = {
                            scope.launch {
                                // Download file to local storage
                                val downloadsDir = Environment.getExternalStoragePublicDirectory(Environment.DIRECTORY_DOWNLOADS)
                                val localFile = File(downloadsDir, remoteFile.name)
                                sshFileManager.downloadFile(remoteFile.path, localFile)
                            }
                        }
                    ) {
                        Icon(Icons.Default.Download, contentDescription = "Download")
                    }
                    
                    IconButton(
                        onClick = {
                            scope.launch {
                                isSaving.value = true
                                try {
                                    sshFileManager.writeFileContent(remoteFile.path, textState.value)
                                } finally {
                                    isSaving.value = false
                                }
                            }
                        },
                        enabled = !isSaving.value
                    ) {
                        Icon(Icons.Default.Save, contentDescription = "Save")
                    }
                },
                colors = TopAppBarDefaults.topAppBarColors(
                    containerColor = MaterialTheme.colorScheme.primaryContainer,
                    titleContentColor = MaterialTheme.colorScheme.onPrimaryContainer,
                )
            )
        }
    ) { paddingValues ->
        if (isLoading.value) {
            Text(
                text = "Loading file...",
                modifier = Modifier.padding(paddingValues).padding(16.dp),
                style = MaterialTheme.typography.bodyMedium
            )
        } else {
            TextField(
                value = textState.value,
                onValueChange = { textState.value = it },
                modifier = Modifier
                    .fillMaxSize()
                    .padding(paddingValues),
                textStyle = MaterialTheme.typography.bodyMedium.copy(
                    fontFamily = androidx.compose.ui.text.font.FontFamily.Monospace
                ),
                placeholder = { Text("Start typing...") }
            )
        }
    }
}