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
import com.rk.resources.strings
import com.rk.settings.Settings
import com.rk.terminal.ui.activities.terminal.MainActivity
import com.rk.terminal.ui.components.SettingsToggle
import com.rk.terminal.ui.routes.MainActivityRoutes
import androidx.compose.material3.OutlinedTextField


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

        // AI API configuration
        PreferenceGroup(heading = "AI Chat Provider") {
            var provider by remember { mutableStateOf(Settings.api_provider) }
            var apiKey by remember { mutableStateOf(Settings.api_key) }
            var baseUrl by remember { mutableStateOf(Settings.api_base_url) }
            var model by remember { mutableStateOf(Settings.api_model) }

            Column(modifier = Modifier.padding(horizontal = 16.dp)) {
                Text(text = "Select provider")
                Row(modifier = Modifier
                    .fillMaxWidth()
                    .padding(vertical = 6.dp)) {
                    RadioButton(selected = provider.equals("openai", true), onClick = { provider = "openai"; Settings.api_provider = provider })
                    Text(text = "OpenAI / Compatible", modifier = Modifier.padding(start = 8.dp))
                }
                Row(modifier = Modifier
                    .fillMaxWidth()
                    .padding(vertical = 6.dp)) {
                    RadioButton(selected = provider.equals("anthropic", true), onClick = { provider = "anthropic"; Settings.api_provider = provider })
                    Text(text = "Anthropic", modifier = Modifier.padding(start = 8.dp))
                }
                Row(modifier = Modifier
                    .fillMaxWidth()
                    .padding(vertical = 6.dp)) {
                    RadioButton(selected = provider.equals("gemini", true), onClick = { provider = "gemini"; Settings.api_provider = provider })
                    Text(text = "Google Gemini", modifier = Modifier.padding(start = 8.dp))
                }

                OutlinedTextField(
                    value = apiKey,
                    onValueChange = { apiKey = it; Settings.api_key = it },
                    label = { Text("API Key") },
                    modifier = Modifier.fillMaxWidth().padding(top = 8.dp),
                    singleLine = true
                )

                if (provider.equals("openai", true)) {
                    OutlinedTextField(
                        value = baseUrl,
                        onValueChange = { baseUrl = it; Settings.api_base_url = it },
                        label = { Text("Base URL (OpenAI-compatible)") },
                        modifier = Modifier.fillMaxWidth().padding(top = 8.dp),
                        singleLine = true
                    )
                }

                OutlinedTextField(
                    value = model,
                    onValueChange = { model = it; Settings.api_model = it },
                    label = { Text("Model (e.g., gpt-4o-mini / claude-3-haiku / gemini-1.5-flash)") },
                    modifier = Modifier.fillMaxWidth().padding(top = 8.dp),
                    singleLine = true
                )

                Text(text = "Chat will use these API settings. Local model download and folders have been replaced.", modifier = Modifier.padding(top = 8.dp))
            }
        }

        // Helper agent configuration
        PreferenceGroup(heading = "Helper Agent") {
            var enabled by remember { mutableStateOf(Settings.helper_agent_enabled) }
            var maxTokens by remember { mutableIntStateOf(Settings.ai_max_tokens) }
            var tempStr by remember { mutableStateOf(Settings.ai_temperature_str) }

            SettingsToggle(
                label = "Enable helper agent",
                description = "Use a side helper to optimize prompts, tools, and AI settings",
                showSwitch = true,
                default = enabled,
                sideEffect = { checked ->
                    enabled = checked
                    Settings.helper_agent_enabled = checked
                }
            )

            Column(modifier = Modifier.padding(horizontal = 16.dp)) {
                OutlinedTextField(
                    value = maxTokens.toString(),
                    onValueChange = { v -> v.toIntOrNull()?.let { maxTokens = it; Settings.ai_max_tokens = it } },
                    label = { Text("Max tokens override (optional)") },
                    modifier = Modifier.fillMaxWidth().padding(top = 8.dp),
                    singleLine = true
                )
                OutlinedTextField(
                    value = tempStr,
                    onValueChange = { v -> tempStr = v; Settings.ai_temperature_str = v },
                    label = { Text("Temperature override (e.g., 0, 0.2, 0.7)") },
                    modifier = Modifier.fillMaxWidth().padding(top = 8.dp),
                    singleLine = true
                )
            }
        }

        // Informative agent configuration
        PreferenceGroup(heading = "Informative Agent") {
            var infoEnabled by remember { mutableStateOf(Settings.informative_agent_enabled) }
            SettingsToggle(
                label = "Enable informative agent",
                description = "Adds richer task progress blurbs in chat",
                showSwitch = true,
                default = infoEnabled,
                sideEffect = { checked ->
                    infoEnabled = checked
                    Settings.informative_agent_enabled = checked
                }
            )
        }

        // Researcher agent configuration
        PreferenceGroup(heading = "Researcher Agent") {
            var resEnabled by remember { mutableStateOf(Settings.researcher_agent_enabled) }
            SettingsToggle(
                label = "Enable researcher agent",
                description = "Search docs/errors when debugging or investigating APIs",
                showSwitch = true,
                default = resEnabled,
                sideEffect = { checked ->
                    resEnabled = checked
                    Settings.researcher_agent_enabled = checked
                }
            )
        }

        // Writer agent configuration
        PreferenceGroup(heading = "Writer Agent") {
            var wEnabled by remember { mutableStateOf(Settings.writer_agent_enabled) }
            SettingsToggle(
                label = "Enable writer agent",
                description = "Suggests safest write tools and regex patterns for edits",
                showSwitch = true,
                default = wEnabled,
                sideEffect = { checked ->
                    wEnabled = checked
                    Settings.writer_agent_enabled = checked
                }
            )
        }

        // Agent shell configuration
        PreferenceGroup(heading = "Agent Shell") {
            var useTerm by remember { mutableStateOf(Settings.agent_use_terminal_session) }
            SettingsToggle(
                label = "Use hidden terminal session",
                description = "Run agent commands in a background terminal (full PATH/env)",
                showSwitch = true,
                default = useTerm,
                sideEffect = { checked ->
                    useTerm = checked
                    Settings.agent_use_terminal_session = checked
                }
            )
        }

        // Codebase agent configuration
        PreferenceGroup(heading = "Codebase Agent") {
            var cbEnabled by remember { mutableStateOf(Settings.codebase_agent_enabled) }
            var cachePath by remember { mutableStateOf(Settings.codebase_cache_path) }

            SettingsToggle(
                label = "Enable codebase agent",
                description = "Scan and analyze repo to build an overview and important files cache",
                showSwitch = true,
                default = cbEnabled,
                sideEffect = { checked ->
                    cbEnabled = checked
                    Settings.codebase_agent_enabled = checked
                }
            )

            Column(modifier = Modifier.padding(horizontal = 16.dp)) {
                OutlinedTextField(
                    value = cachePath,
                    onValueChange = { v -> cachePath = v; Settings.codebase_cache_path = v },
                    label = { Text("Cache path (relative to workspace)") },
                    modifier = Modifier.fillMaxWidth().padding(top = 8.dp),
                    singleLine = true
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