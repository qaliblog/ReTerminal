package com.rk.terminal.ssh

import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.*
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.items
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.*
import androidx.compose.material3.*
import androidx.compose.runtime.*
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.unit.dp
import kotlinx.coroutines.launch

@OptIn(ExperimentalMaterial3Api::class)
@Composable
fun AlpineSshFileManagerView(
    sshConfig: SshConfig,
    currentPath: String,
    onNavigate: (String) -> Unit,
    onEditFile: (String) -> Unit
) {
    val scope = rememberCoroutineScope()
    val fileManager = remember { AlpineSshFileManager(sshConfig) }
    
    Column(modifier = Modifier.fillMaxSize()) {
        // SSH Connection Info Header
        Card(
            modifier = Modifier.fillMaxWidth().padding(8.dp),
            colors = CardDefaults.cardColors(containerColor = MaterialTheme.colorScheme.primaryContainer)
        ) {
            Column(modifier = Modifier.padding(16.dp)) {
                Text(
                    text = "SSH File Manager (Alpine)",
                    style = MaterialTheme.typography.titleMedium,
                    color = MaterialTheme.colorScheme.onPrimaryContainer
                )
                Text(
                    text = "Connected to: ${fileManager.getConnectionInfo()}",
                    style = MaterialTheme.typography.bodyMedium,
                    color = MaterialTheme.colorScheme.onPrimaryContainer
                )
                Text(
                    text = "Working Directory: $currentPath",
                    style = MaterialTheme.typography.bodySmall,
                    color = MaterialTheme.colorScheme.onPrimaryContainer
                )
            }
        }
        
        // File Operations
        LazyColumn(
            modifier = Modifier.fillMaxSize().padding(horizontal = 8.dp),
            verticalArrangement = Arrangement.spacedBy(8.dp)
        ) {
            item {
                Card {
                    Column(modifier = Modifier.padding(16.dp)) {
                        Text(
                            text = "Alpine SSH File Operations",
                            style = MaterialTheme.typography.titleSmall
                        )
                        
                        Spacer(modifier = Modifier.height(8.dp))
                        
                        Text(
                            text = "Use these commands in the terminal for file operations:",
                            style = MaterialTheme.typography.bodyMedium
                        )
                        
                        Spacer(modifier = Modifier.height(8.dp))
                        
                        Column(verticalArrangement = Arrangement.spacedBy(4.dp)) {
                            CommandExample("List files:", "ls -la")
                            CommandExample("Change directory:", "cd /path/to/directory")
                            CommandExample("Edit file:", "nano filename.txt")
                            CommandExample("Create directory:", "mkdir dirname")
                            CommandExample("Remove file:", "rm filename")
                            CommandExample("Copy file:", "cp source dest")
                            CommandExample("Move file:", "mv source dest")
                            CommandExample("View file:", "cat filename.txt")
                            CommandExample("Find files:", "find . -name \"*.txt\"")
                        }
                    }
                }
            }
            
            item {
                Card {
                    Column(modifier = Modifier.padding(16.dp)) {
                        Text(
                            text = "File Transfer Commands",
                            style = MaterialTheme.typography.titleSmall
                        )
                        
                        Spacer(modifier = Modifier.height(8.dp))
                        
                        Column(verticalArrangement = Arrangement.spacedBy(4.dp)) {
                            CommandExample("Download file:", "scp ${sshConfig.username}@${sshConfig.hostname}:/remote/file /local/path")
                            CommandExample("Upload file:", "scp /local/file ${sshConfig.username}@${sshConfig.hostname}:/remote/path")
                            CommandExample("Sync directories:", "rsync -avz /local/dir/ ${sshConfig.username}@${sshConfig.hostname}:/remote/dir/")
                        }
                    }
                }
            }
            
            item {
                Card {
                    Column(modifier = Modifier.padding(16.dp)) {
                        Text(
                            text = "Quick Actions",
                            style = MaterialTheme.typography.titleSmall
                        )
                        
                        Spacer(modifier = Modifier.height(8.dp))
                        
                        Row(
                            modifier = Modifier.fillMaxWidth(),
                            horizontalArrangement = Arrangement.spacedBy(8.dp)
                        ) {
                            Button(
                                onClick = {
                                    scope.launch {
                                        // Execute pwd command to get current directory
                                        // This would need integration with the terminal
                                    }
                                },
                                modifier = Modifier.weight(1f)
                            ) {
                                Text("Get Current Dir")
                            }
                            
                            Button(
                                onClick = {
                                    scope.launch {
                                        // Execute ls command
                                        // This would need integration with the terminal
                                    }
                                },
                                modifier = Modifier.weight(1f)
                            ) {
                                Text("List Files")
                            }
                        }
                    }
                }
            }
        }
    }
}

@Composable
private fun CommandExample(label: String, command: String) {
    Row(
        modifier = Modifier.fillMaxWidth(),
        horizontalArrangement = Arrangement.SpaceBetween,
        verticalAlignment = Alignment.CenterVertically
    ) {
        Text(
            text = label,
            style = MaterialTheme.typography.bodySmall,
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