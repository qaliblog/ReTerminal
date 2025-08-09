package com.rk.terminal.ui.screens.settings

import androidx.compose.foundation.ExperimentalFoundationApi
import androidx.compose.foundation.combinedClickable
import androidx.compose.foundation.interaction.MutableInteractionSource
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxHeight
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.automirrored.outlined.KeyboardArrowRight
import androidx.compose.material.icons.filled.Add
import androidx.compose.material3.Button
import androidx.compose.material3.ExperimentalMaterial3Api
import androidx.compose.material3.Icon
import androidx.compose.material3.RadioButton
import androidx.compose.material3.Text
import androidx.compose.material3.ripple
import androidx.compose.runtime.*
import androidx.compose.runtime.remember
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.unit.dp
import androidx.navigation.NavController
import com.rk.components.compose.preferences.base.PreferenceGroup
import com.rk.components.compose.preferences.base.PreferenceLayout
import com.rk.components.compose.preferences.base.PreferenceTemplate
import com.rk.terminal.ui.components.InputDialog
import com.rk.resources.strings
import com.rk.settings.Settings
import com.rk.terminal.ui.activities.terminal.MainActivity
import com.rk.terminal.ui.components.SettingsToggle
import com.rk.terminal.ui.routes.MainActivityRoutes


@OptIn(ExperimentalFoundationApi::class)
@Composable
fun SettingsCard(
    modifier: Modifier = Modifier,
    interactionSource: MutableInteractionSource = remember { MutableInteractionSource() },
    title: @Composable () -> Unit,
    description: @Composable () -> Unit = {},
    startWidget: (@Composable () -> Unit)? = null,
    endWidget: (@Composable () -> Unit)? = null,
    isEnabled: Boolean = true,
    onClick: () -> Unit
) {
    PreferenceTemplate(
        modifier = modifier
            .combinedClickable(
                enabled = isEnabled,
                indication = ripple(),
                interactionSource = interactionSource,
                onClick = onClick
            ),
        contentModifier = Modifier
            .fillMaxHeight()
            .padding(vertical = 16.dp)
            .padding(start = 16.dp),
        title = title,
        description = description,
        startWidget = startWidget,
        endWidget = endWidget,
        applyPaddings = false
    )

}


object WorkingMode{
    const val ALPINE = 0
    const val ANDROID = 1
}


@OptIn(ExperimentalMaterial3Api::class)
@Composable
fun Settings(modifier: Modifier = Modifier,navController: NavController,mainActivity: MainActivity) {
    val context = LocalContext.current
    var selectedOption by remember { mutableIntStateOf(Settings.working_Mode) }

    PreferenceLayout(label = stringResource(strings.settings)) {
        PreferenceGroup(heading = "Default Working mode") {

            SettingsCard(
                title = { Text("Alpine") },
                description = {Text("Alpine Linux")},
                startWidget = {
                    RadioButton(
                        modifier = Modifier.padding(start = 8.dp),
                        selected = selectedOption == WorkingMode.ALPINE,
                        onClick = {
                            selectedOption = WorkingMode.ALPINE
                            Settings.working_Mode = selectedOption
                        })
                },
                onClick = {
                    selectedOption = WorkingMode.ALPINE
                    Settings.working_Mode = selectedOption
                })


            SettingsCard(
                title = { Text("Android") },
                description = {Text("ReTerminal Android shell")},
                startWidget = {
                    RadioButton(
                        modifier = Modifier
                            .padding(start = 8.dp)
                            ,
                        selected = selectedOption == WorkingMode.ANDROID,
                        onClick = {
                            selectedOption = WorkingMode.ANDROID
                            Settings.working_Mode = selectedOption
                        })
                },
                onClick = {
                    selectedOption = WorkingMode.ANDROID
                    Settings.working_Mode = selectedOption
                })
        }

        PreferenceGroup(heading = "AI Model Folders") {
            // Local reactive state for selection and list
            var selectedModel by remember { mutableStateOf(Settings.selected_model_folder) }
            var foldersCsv by remember { mutableStateOf(Settings.model_folders_csv) }
            val folders = remember(foldersCsv) { foldersCsv.split(',').map { it.trim() }.filter { it.isNotBlank() } }

            Text(text = if (selectedModel.isBlank()) "Selected: (auto)" else "Selected: $selectedModel", modifier = Modifier.padding(horizontal = 16.dp, vertical = 8.dp))

            folders.forEach { path ->
                Row(modifier = Modifier
                    .fillMaxWidth()
                    .padding(horizontal = 16.dp, vertical = 6.dp)) {
                    Text(text = path, modifier = Modifier.weight(1f))
                    Button(onClick = {
                        Settings.selected_model_folder = path
                        selectedModel = path
                        com.rk.terminal.llm.ModelManager.setSelectedModel(path)
                    }) { Text("Use") }
                }
            }

            var showAdd by remember { mutableStateOf(false) }
            Button(onClick = { showAdd = true }, modifier = Modifier.padding(horizontal = 16.dp, vertical = 8.dp)) {
                Icon(imageVector = Icons.Default.Add, contentDescription = null)
                Text(text = "Add folder", modifier = Modifier.padding(start = 8.dp))
            }

            if (showAdd) {
                var input by remember { mutableStateOf("/sdcard/reterminalAssets/YourModelFolder") }
                InputDialog(
                    title = "Add model folder",
                    inputLabel = "Absolute folder path",
                    inputValue = input,
                    onInputValueChange = { input = it },
                    onConfirm = {
                        val trimmed = input.trim()
                        if (trimmed.isNotBlank()) {
                            val updated = (folders + trimmed).toSet().joinToString(",")
                            Settings.model_folders_csv = updated
                            foldersCsv = updated
                            Settings.selected_model_folder = trimmed
                            selectedModel = trimmed
                            com.rk.terminal.llm.ModelManager.setSelectedModel(trimmed)
                        }
                    },
                    onDismiss = { showAdd = false },
                    singleLineMode = true
                )
            }
        }

        PreferenceGroup {
            SettingsToggle(
                label = "Customizations",
                showSwitch = false,
                default = false,
                sideEffect = {
                   navController.navigate(MainActivityRoutes.Customization.route)
            }, endWidget = {
                Icon(imageVector = Icons.AutoMirrored.Outlined.KeyboardArrowRight, contentDescription = null,modifier = Modifier.padding(16.dp))
            })
        }
    }
}