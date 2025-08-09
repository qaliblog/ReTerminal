package com.rk.terminal.ui.screens.terminal

import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.padding
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.Save
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
import com.rk.terminal.ui.activities.terminal.MainActivity
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext
import java.io.File

@Composable
fun TextEditorView(mainActivityActivity: MainActivity) {
    val opened = FileOpenBus.current()
    val fileState = remember { mutableStateOf<File?>(null) }
    val textState = remember { mutableStateOf("") }
    val scope = rememberCoroutineScope()

    LaunchedEffect(opened) {
        fileState.value = opened
        opened?.let { file ->
            val content = withContext(Dispatchers.IO) {
                runCatching { file.readText() }.getOrElse { "" }
            }
            textState.value = content
        }
    }

    val title = fileState.value?.name ?: "Editor"

    Scaffold(topBar = {
        TopAppBar(
            title = { Text(title) },
            actions = {
                IconButton(onClick = {
                    val f = fileState.value ?: return@IconButton
                    scope.launch(Dispatchers.IO) {
                        runCatching { f.writeText(textState.value) }
                    }
                }) { Icon(Icons.Default.Save, contentDescription = "Save") }
            },
            colors = TopAppBarDefaults.topAppBarColors(containerColor = androidx.compose.ui.graphics.Color.Transparent)
        )
    }) { padding ->
        TextField(
            value = textState.value,
            onValueChange = { textState.value = it },
            modifier = Modifier
                .padding(padding)
                .fillMaxSize(),
            textStyle = MaterialTheme.typography.bodyLarge
        )
    }
}