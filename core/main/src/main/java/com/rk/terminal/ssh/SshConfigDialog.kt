package com.rk.terminal.ssh

import androidx.compose.foundation.layout.*
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.items
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.verticalScroll
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.Delete
import androidx.compose.material.icons.filled.Visibility
import androidx.compose.material.icons.filled.VisibilityOff
import androidx.compose.material3.*
import androidx.compose.runtime.*
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.text.input.PasswordVisualTransformation
import androidx.compose.ui.text.input.VisualTransformation
import androidx.compose.ui.unit.dp
import com.rk.components.compose.preferences.base.PreferenceGroup
import java.util.UUID

@OptIn(ExperimentalMaterial3Api::class)
@Composable
fun SshConfigDialog(
    initialConfig: SshConfig? = null,
    onDismiss: () -> Unit,
    onSave: (SshConfig, Boolean) -> Unit
) {
    var isConnecting by remember { mutableStateOf(false) }
    val context = LocalContext.current
    val configManager = remember { SshConfigManager(context) }
    
    var name by remember { mutableStateOf(initialConfig?.name ?: "") }
    var hostname by remember { mutableStateOf(initialConfig?.hostname ?: "") }
    var port by remember { mutableStateOf(initialConfig?.port?.toString() ?: "22") }
    var username by remember { mutableStateOf(initialConfig?.username ?: "") }
    var password by remember { mutableStateOf(initialConfig?.password ?: "") }
    var privateKeyPath by remember { mutableStateOf(initialConfig?.privateKeyPath ?: "") }
    var passphrase by remember { mutableStateOf(initialConfig?.passphrase ?: "") }
    var authMethod by remember { mutableStateOf(initialConfig?.authMethod ?: AuthMethod.PASSWORD) }
    var strictHostKeyChecking by remember { mutableStateOf(initialConfig?.strictHostKeyChecking ?: false) }
    var connectTimeout by remember { mutableStateOf(initialConfig?.connectTimeout?.toString() ?: "30000") }
    var keepAliveInterval by remember { mutableStateOf(initialConfig?.keepAliveInterval?.toString() ?: "60000") }
    var compressionEnabled by remember { mutableStateOf(initialConfig?.compressionEnabled ?: true) }
    var forwardX11 by remember { mutableStateOf(initialConfig?.forwardX11 ?: false) }
    var workingDirectory by remember { mutableStateOf(initialConfig?.workingDirectory ?: "~") }
    var saveConfig by remember { mutableStateOf(false) }
    
    var passwordVisible by remember { mutableStateOf(false) }
    var passphraseVisible by remember { mutableStateOf(false) }
    
    AlertDialog(
        onDismissRequest = onDismiss,
        title = { 
            Text(if (initialConfig != null) "Edit SSH Configuration" else "New SSH Configuration") 
        },
        text = {
            Column(
                modifier = Modifier
                    .fillMaxWidth()
                    .heightIn(max = 600.dp)
                    .verticalScroll(rememberScrollState()),
                verticalArrangement = Arrangement.spacedBy(12.dp)
            ) {
                // Basic Connection Settings
                PreferenceGroup {
                    OutlinedTextField(
                        value = name,
                        onValueChange = { name = it },
                        label = { Text("Connection Name") },
                        modifier = Modifier.fillMaxWidth(),
                        singleLine = true
                    )
                    
                    OutlinedTextField(
                        value = hostname,
                        onValueChange = { hostname = it },
                        label = { Text("Hostname/IP") },
                        modifier = Modifier.fillMaxWidth(),
                        singleLine = true,
                        placeholder = { Text("example.com or 192.168.1.100") }
                    )
                    
                    OutlinedTextField(
                        value = port,
                        onValueChange = { port = it },
                        label = { Text("Port") },
                        modifier = Modifier.fillMaxWidth(),
                        singleLine = true
                    )
                    
                    OutlinedTextField(
                        value = username,
                        onValueChange = { username = it },
                        label = { Text("Username") },
                        modifier = Modifier.fillMaxWidth(),
                        singleLine = true
                    )
                }
                
                // Authentication Settings
                Text("Authentication Method", style = MaterialTheme.typography.titleSmall)
                
                Row(verticalAlignment = Alignment.CenterVertically) {
                    RadioButton(
                        selected = authMethod == AuthMethod.PASSWORD,
                        onClick = { authMethod = AuthMethod.PASSWORD }
                    )
                    Text("Password", modifier = Modifier.padding(start = 8.dp))
                }
                
                Row(verticalAlignment = Alignment.CenterVertically) {
                    RadioButton(
                        selected = authMethod == AuthMethod.PRIVATE_KEY,
                        onClick = { authMethod = AuthMethod.PRIVATE_KEY }
                    )
                    Text("Private Key", modifier = Modifier.padding(start = 8.dp))
                }
                
                Row(verticalAlignment = Alignment.CenterVertically) {
                    RadioButton(
                        selected = authMethod == AuthMethod.PASSWORD_AND_KEY,
                        onClick = { authMethod = AuthMethod.PASSWORD_AND_KEY }
                    )
                    Text("Password + Key", modifier = Modifier.padding(start = 8.dp))
                }
                
                // Password field (shown for PASSWORD and PASSWORD_AND_KEY)
                if (authMethod == AuthMethod.PASSWORD || authMethod == AuthMethod.PASSWORD_AND_KEY) {
                    OutlinedTextField(
                        value = password,
                        onValueChange = { password = it },
                        label = { Text("Password") },
                        modifier = Modifier.fillMaxWidth(),
                        singleLine = true,
                        visualTransformation = if (passwordVisible) VisualTransformation.None else PasswordVisualTransformation(),
                        trailingIcon = {
                            IconButton(onClick = { passwordVisible = !passwordVisible }) {
                                Icon(
                                    imageVector = if (passwordVisible) Icons.Default.VisibilityOff else Icons.Default.Visibility,
                                    contentDescription = if (passwordVisible) "Hide password" else "Show password"
                                )
                            }
                        }
                    )
                }
                
                // Private key fields (shown for PRIVATE_KEY and PASSWORD_AND_KEY)
                if (authMethod == AuthMethod.PRIVATE_KEY || authMethod == AuthMethod.PASSWORD_AND_KEY) {
                    OutlinedTextField(
                        value = privateKeyPath,
                        onValueChange = { privateKeyPath = it },
                        label = { Text("Private Key Path") },
                        modifier = Modifier.fillMaxWidth(),
                        singleLine = true,
                        placeholder = { Text("/path/to/private/key or paste key content") }
                    )
                    
                    OutlinedTextField(
                        value = passphrase,
                        onValueChange = { passphrase = it },
                        label = { Text("Key Passphrase (optional)") },
                        modifier = Modifier.fillMaxWidth(),
                        singleLine = true,
                        visualTransformation = if (passphraseVisible) VisualTransformation.None else PasswordVisualTransformation(),
                        trailingIcon = {
                            IconButton(onClick = { passphraseVisible = !passphraseVisible }) {
                                Icon(
                                    imageVector = if (passphraseVisible) Icons.Default.VisibilityOff else Icons.Default.Visibility,
                                    contentDescription = if (passphraseVisible) "Hide passphrase" else "Show passphrase"
                                )
                            }
                        }
                    )
                }
                
                // Advanced Settings
                Text("Advanced Settings", style = MaterialTheme.typography.titleSmall)
                
                OutlinedTextField(
                    value = workingDirectory,
                    onValueChange = { workingDirectory = it },
                    label = { Text("Working Directory") },
                    modifier = Modifier.fillMaxWidth(),
                    singleLine = true
                )
                
                Row(
                    modifier = Modifier.fillMaxWidth(),
                    horizontalArrangement = Arrangement.SpaceBetween
                ) {
                    OutlinedTextField(
                        value = connectTimeout,
                        onValueChange = { connectTimeout = it },
                        label = { Text("Connect Timeout (ms)") },
                        modifier = Modifier.weight(1f),
                        singleLine = true
                    )
                    
                    Spacer(modifier = Modifier.width(8.dp))
                    
                    OutlinedTextField(
                        value = keepAliveInterval,
                        onValueChange = { keepAliveInterval = it },
                        label = { Text("Keep Alive (ms)") },
                        modifier = Modifier.weight(1f),
                        singleLine = true
                    )
                }
                
                // Toggles
                Row(
                    modifier = Modifier.fillMaxWidth(),
                    horizontalArrangement = Arrangement.SpaceBetween,
                    verticalAlignment = Alignment.CenterVertically
                ) {
                    Text("Strict Host Key Checking")
                    Switch(
                        checked = strictHostKeyChecking,
                        onCheckedChange = { strictHostKeyChecking = it }
                    )
                }
                
                Row(
                    modifier = Modifier.fillMaxWidth(),
                    horizontalArrangement = Arrangement.SpaceBetween,
                    verticalAlignment = Alignment.CenterVertically
                ) {
                    Text("Enable Compression")
                    Switch(
                        checked = compressionEnabled,
                        onCheckedChange = { compressionEnabled = it }
                    )
                }
                
                Row(
                    modifier = Modifier.fillMaxWidth(),
                    horizontalArrangement = Arrangement.SpaceBetween,
                    verticalAlignment = Alignment.CenterVertically
                ) {
                    Text("Forward X11")
                    Switch(
                        checked = forwardX11,
                        onCheckedChange = { forwardX11 = it }
                    )
                }
                
                // Save Configuration Toggle
                Row(
                    modifier = Modifier.fillMaxWidth(),
                    horizontalArrangement = Arrangement.SpaceBetween,
                    verticalAlignment = Alignment.CenterVertically
                ) {
                    Text("Save Configuration", style = MaterialTheme.typography.titleMedium)
                    Switch(
                        checked = saveConfig,
                        onCheckedChange = { saveConfig = it }
                    )
                }
            }
        },
        confirmButton = {
            TextButton(
                onClick = {
                    if (!isConnecting) {
                        isConnecting = true
                        val configId = initialConfig?.id ?: configManager.generateConfigId()
                        android.util.Log.d("SshConfigDialog", "Creating config with ID: $configId, saveConfig: $saveConfig")
                        val config = SshConfig(
                            id = configId,
                            name = name.ifBlank { "$username@$hostname" },
                            hostname = hostname,
                            port = port.toIntOrNull() ?: 22,
                            username = username,
                            password = password,
                            privateKeyPath = privateKeyPath,
                            passphrase = passphrase,
                            authMethod = authMethod,
                            strictHostKeyChecking = strictHostKeyChecking,
                            connectTimeout = connectTimeout.toIntOrNull() ?: 30000,
                            keepAliveInterval = keepAliveInterval.toIntOrNull() ?: 60000,
                            compressionEnabled = compressionEnabled,
                            forwardX11 = forwardX11,
                            workingDirectory = workingDirectory
                        )
                        android.util.Log.d("SshConfigDialog", "Calling onSave with saveConfig=$saveConfig for ${config.name}")
                        onSave(config, saveConfig)
                        isConnecting = false
                    }
                },
                enabled = !isConnecting && hostname.isNotBlank() && username.isNotBlank() && 
                         (authMethod == AuthMethod.PRIVATE_KEY || password.isNotBlank())
            ) {
                if (isConnecting) {
                    Text("Connecting...")
                } else {
                    Text("Connect")
                }
            }
        },
        dismissButton = {
            TextButton(onClick = onDismiss) {
                Text("Cancel")
            }
        }
    )
}

@Composable
fun SavedSshConfigsDialog(
    onDismiss: () -> Unit,
    onConfigSelected: (SshConfig) -> Unit,
    onConfigDeleted: (SshConfig) -> Unit
) {
    val context = LocalContext.current
    val configManager = remember { SshConfigManager(context) }
    var savedConfigs by remember { mutableStateOf(configManager.getSavedConfigs()) }
    
    AlertDialog(
        onDismissRequest = onDismiss,
        title = { Text("Saved SSH Configurations") },
        text = {
            LazyColumn(
                modifier = Modifier.heightIn(max = 400.dp),
                verticalArrangement = Arrangement.spacedBy(8.dp)
            ) {
                items(savedConfigs) { config ->
                    Card(
                        modifier = Modifier.fillMaxWidth(),
                        onClick = { onConfigSelected(config) }
                    ) {
                        Row(
                            modifier = Modifier
                                .fillMaxWidth()
                                .padding(16.dp),
                            horizontalArrangement = Arrangement.SpaceBetween,
                            verticalAlignment = Alignment.CenterVertically
                        ) {
                            Column(modifier = Modifier.weight(1f)) {
                                Text(
                                    text = config.name,
                                    style = MaterialTheme.typography.titleMedium
                                )
                                Text(
                                    text = "${config.username}@${config.hostname}:${config.port}",
                                    style = MaterialTheme.typography.bodyMedium,
                                    color = MaterialTheme.colorScheme.onSurfaceVariant
                                )
                            }
                            
                            IconButton(
                                onClick = { 
                                    configManager.deleteConfig(config.id)
                                    savedConfigs = configManager.getSavedConfigs()
                                    onConfigDeleted(config)
                                }
                            ) {
                                Icon(
                                    imageVector = Icons.Default.Delete,
                                    contentDescription = "Delete configuration"
                                )
                            }
                        }
                    }
                }
                
                if (savedConfigs.isEmpty()) {
                    item {
                        Text(
                            text = "No saved configurations",
                            modifier = Modifier.padding(16.dp),
                            style = MaterialTheme.typography.bodyMedium,
                            color = MaterialTheme.colorScheme.onSurfaceVariant
                        )
                    }
                }
            }
        },
        confirmButton = {
            TextButton(onClick = onDismiss) {
                Text("Close")
            }
        }
    )
}