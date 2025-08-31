package com.rk.terminal.ssh

import androidx.compose.foundation.layout.*
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.*
import androidx.compose.material3.*
import androidx.compose.runtime.*
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.unit.dp

@OptIn(ExperimentalMaterial3Api::class)
@Composable
fun AlpineSshTextEditorView(filePath: String, sshConfig: SshConfig) {
    val fileManager = remember { AlpineSshFileManager(sshConfig) }
    var content by remember { mutableStateOf("Loading file from SSH server...") }
    var isModified by remember { mutableStateOf(false) }
    
    Scaffold(
        topBar = {
            TopAppBar(
                title = { 
                    Column {
                        Text(filePath.substringAfterLast("/"))
                        Text(
                            text = "SSH: ${sshConfig.username}@${sshConfig.hostname}",
                            style = MaterialTheme.typography.bodySmall
                        )
                    }
                },
                actions = {
                    IconButton(
                        onClick = {
                            // TODO: Implement file download
                        }
                    ) {
                        Icon(Icons.Default.Download, contentDescription = "Download")
                    }
                    
                    IconButton(
                        onClick = {
                            // TODO: Implement save to SSH server
                            isModified = false
                        },
                        enabled = isModified
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
        Column(
            modifier = Modifier
                .fillMaxSize()
                .padding(paddingValues)
                .padding(16.dp)
        ) {
            Card(
                modifier = Modifier.fillMaxWidth(),
                colors = CardDefaults.cardColors(containerColor = MaterialTheme.colorScheme.surfaceVariant)
            ) {
                Column(modifier = Modifier.padding(16.dp)) {
                    Text(
                        text = "Alpine SSH Text Editor",
                        style = MaterialTheme.typography.titleMedium
                    )
                    Text(
                        text = "File: $filePath",
                        style = MaterialTheme.typography.bodyMedium
                    )
                    Text(
                        text = "Server: ${fileManager.getConnectionInfo()}",
                        style = MaterialTheme.typography.bodySmall
                    )
                }
            }
            
            Spacer(modifier = Modifier.height(16.dp))
            
            Card(modifier = Modifier.fillMaxWidth()) {
                Column(modifier = Modifier.padding(16.dp)) {
                    Text(
                        text = "Terminal Commands for File Editing:",
                        style = MaterialTheme.typography.titleSmall
                    )
                    
                    Spacer(modifier = Modifier.height(8.dp))
                    
                    Column(verticalArrangement = Arrangement.spacedBy(8.dp)) {
                        CommandCard("View file:", "cat $filePath")
                        CommandCard("Edit with nano:", "nano $filePath")
                        CommandCard("Edit with vi:", "vi $filePath")
                        CommandCard("Append to file:", "echo 'content' >> $filePath")
                        CommandCard("Create backup:", "cp $filePath $filePath.backup")
                    }
                }
            }
            
            Spacer(modifier = Modifier.height(16.dp))
            
            TextField(
                value = content,
                onValueChange = { 
                    content = it
                    isModified = true
                },
                modifier = Modifier.fillMaxSize(),
                textStyle = MaterialTheme.typography.bodyMedium.copy(
                    fontFamily = androidx.compose.ui.text.font.FontFamily.Monospace
                ),
                placeholder = { Text("File content will appear here when loaded from SSH server...") },
                label = { Text("File Content (Read-only preview)") }
            )
        }
    }
}

@Composable
private fun CommandCard(label: String, command: String) {
    Card(
        modifier = Modifier.fillMaxWidth(),
        colors = CardDefaults.cardColors(containerColor = MaterialTheme.colorScheme.surface)
    ) {
        Row(
            modifier = Modifier
                .fillMaxWidth()
                .padding(12.dp),
            horizontalArrangement = Arrangement.SpaceBetween,
            verticalAlignment = Alignment.CenterVertically
        ) {
            Text(
                text = label,
                style = MaterialTheme.typography.bodyMedium,
                modifier = Modifier.weight(1f)
            )
            Card(
                colors = CardDefaults.cardColors(containerColor = MaterialTheme.colorScheme.surfaceVariant)
            ) {
                Text(
                    text = command,
                    style = MaterialTheme.typography.bodySmall,
                    modifier = Modifier.padding(horizontal = 8.dp, vertical = 4.dp),
                    fontFamily = androidx.compose.ui.text.font.FontFamily.Monospace
                )
            }
        }
    }
}