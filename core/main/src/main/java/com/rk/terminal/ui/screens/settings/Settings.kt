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
import androidx.compose.material3.OutlinedButton
import androidx.compose.runtime.snapshots.SnapshotStateList


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
    const val SSH = 2
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

            SettingsCard(
                title = { Text("SSH") },
                description = {Text("Connect to remote SSH server")},
                startWidget = {
                    RadioButton(
                        modifier = Modifier
                            .padding(start = 8.dp),
                        selected = selectedOption == WorkingMode.SSH,
                        onClick = {
                            selectedOption = WorkingMode.SSH
                            Settings.working_Mode = selectedOption
                        })
                },
                onClick = {
                    selectedOption = WorkingMode.SSH
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
                Row(modifier = Modifier
                    .fillMaxWidth()
                    .padding(vertical = 6.dp)) {
                    RadioButton(selected = provider.equals("fireworks", true), onClick = {
                        provider = "fireworks"; Settings.api_provider = provider
                        if (baseUrl.isBlank() || baseUrl == "https://api.openai.com") {
                            baseUrl = "https://api.fireworks.ai"
                            Settings.api_base_url = baseUrl
                        }
                        if (model.isBlank() || model == "gpt-4o-mini") {
                            model = "accounts/fireworks/models/llama-v3p1-8b-instruct"
                            Settings.api_model = model
                        }
                    })
                    Text(text = "Fireworks AI", modifier = Modifier.padding(start = 8.dp))
                }
                Row(modifier = Modifier
                    .fillMaxWidth()
                    .padding(vertical = 6.dp)) {
                    RadioButton(selected = provider.equals("ollama", true), onClick = {
                        provider = "ollama"; Settings.api_provider = provider
                        if (baseUrl.isBlank() || baseUrl == "https://api.openai.com") {
                            baseUrl = "http://127.0.0.1:11434"
                            Settings.api_base_url = baseUrl
                        }
                        if (model.isBlank() || model == "gpt-4o-mini") {
                            model = "llama3.1"
                            Settings.api_model = model
                        }
                    })
                    Text(text = "Ollama (Local)", modifier = Modifier.padding(start = 8.dp))
                }

                val onlineProvider = provider.equals("openai", true) || provider.equals("anthropic", true) || provider.equals("gemini", true) || provider.equals("fireworks", true) || provider.equals("openai_compatible", true)

                if (onlineProvider) {
                    OutlinedTextField(
                        value = apiKey,
                        onValueChange = { apiKey = it; Settings.api_key = it },
                        label = { Text("API Key") },
                        modifier = Modifier.fillMaxWidth().padding(top = 8.dp),
                        singleLine = true
                    )
                }

                OutlinedTextField(
                    value = baseUrl,
                    onValueChange = { baseUrl = it; Settings.api_base_url = it },
                    label = { Text(when {
                        provider.equals("ollama", true) -> "Base URL (Ollama)"
                        provider.equals("fireworks", true) -> "Base URL (Fireworks)"
                        else -> "Base URL (OpenAI-compatible)"
                    }) },
                    modifier = Modifier.fillMaxWidth().padding(top = 8.dp),
                    singleLine = true
                )

                OutlinedTextField(
                    value = model,
                    onValueChange = { model = it; Settings.api_model = it },
                    label = { Text("Model (e.g., gpt-4o-mini / claude-3-haiku / gemini-1.5-flash / llama3.1 / accounts/fireworks/models/llama-v3p1-8b-instruct)") },
                    modifier = Modifier.fillMaxWidth().padding(top = 8.dp),
                    singleLine = true
                )

                Text(text = if (provider.equals("ollama", true)) {
                    "Ollama: ensure the daemon is running on the device or network host at the configured Base URL."
                } else {
                    "Chat will use these API settings. Local model download and folders have been replaced."
                }, modifier = Modifier.padding(top = 8.dp))
            }
        }

        // Gemini API key rotation
        PreferenceGroup(heading = "Gemini Key Rotation") {
            var rotationEnabled by remember { mutableStateOf(Settings.api_key_rotation_enabled) }
            SettingsToggle(
                label = "Enable API Key Rotation",
                description = "Use multiple Gemini API keys and rotate when rate limited",
                showSwitch = true,
                default = rotationEnabled,
                sideEffect = { checked ->
                    rotationEnabled = checked
                    Settings.api_key_rotation_enabled = checked
                }
            )

            if (rotationEnabled) {
                val keys: SnapshotStateList<String> = remember { mutableStateListOf<String>().also { it.addAll(Settings.getGeminiApiKeys()) } }
                var newKey by remember { mutableStateOf("") }
                Column(modifier = Modifier.padding(horizontal = 16.dp)) {
                    Row(modifier = Modifier.fillMaxWidth().padding(top = 8.dp)) {
                        OutlinedTextField(
                            value = newKey,
                            onValueChange = { newKey = it },
                            label = { Text("Add Gemini API key") },
                            modifier = Modifier.weight(1f),
                            singleLine = true
                        )
                        OutlinedButton(modifier = Modifier.padding(start = 8.dp), onClick = {
                            val trimmed = newKey.trim()
                            if (trimmed.isNotEmpty()) {
                                keys.add(trimmed)
                                Settings.setGeminiApiKeys(keys)
                                newKey = ""
                            }
                        }) { Text("Add") }
                    }

                    // Existing keys list with remove/up/down
                    keys.forEachIndexed { index, k ->
                        Row(modifier = Modifier.fillMaxWidth().padding(top = 8.dp)) {
                            Text(text = "${index + 1}. ${k}", modifier = Modifier.weight(1f))
                            OutlinedButton(onClick = {
                                if (index > 0) {
                                    val moved = keys.removeAt(index)
                                    keys.add(index - 1, moved)
                                    Settings.setGeminiApiKeys(keys)
                                }
                            }) { Text("Up") }
                            OutlinedButton(modifier = Modifier.padding(start = 8.dp), onClick = {
                                if (index < keys.lastIndex) {
                                    val moved = keys.removeAt(index)
                                    keys.add(index + 1, moved)
                                    Settings.setGeminiApiKeys(keys)
                                }
                            }) { Text("Down") }
                            OutlinedButton(modifier = Modifier.padding(start = 8.dp), onClick = {
                                keys.removeAt(index)
                                Settings.setGeminiApiKeys(keys)
                            }) { Text("Remove") }
                        }
                    }
                }
            }
        }

        // Back-plan & Main Instructions toggles
        PreferenceGroup(heading = "Agent Safety & Instructions") {
            var backplan by remember { mutableStateOf(Settings.backplan_enabled) }
            SettingsToggle(
                label = "Enable back-plan auto-fix",
                description = "If a write fails, analyze context and auto-apply a corrective patch",
                showSwitch = true,
                default = backplan,
                sideEffect = { checked ->
                    backplan = checked
                    Settings.backplan_enabled = checked
                }
            )

            var mainInstr by remember { mutableStateOf(Settings.main_instructions_enabled) }
            SettingsToggle(
                label = "Enable main instructions injection",
                description = "Send expectations + blueprint + codebase summary as JSON context to the AI",
                showSwitch = true,
                default = mainInstr,
                sideEffect = { checked ->
                    mainInstr = checked
                    Settings.main_instructions_enabled = checked
                }
            )
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
                description = "Summarize and surface useful insights while running",
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
            var researcherEnabled by remember { mutableStateOf(Settings.researcher_agent_enabled) }
            SettingsToggle(
                label = "Enable researcher agent",
                description = "When needed, research the web for solutions and context",
                showSwitch = true,
                default = researcherEnabled,
                sideEffect = { checked ->
                    researcherEnabled = checked
                    Settings.researcher_agent_enabled = checked
                }
            )
        }

        // Writer agent configuration
        PreferenceGroup(heading = "Writer Agent") {
            var writerEnabled by remember { mutableStateOf(Settings.writer_agent_enabled) }
            SettingsToggle(
                label = "Enable writer agent",
                description = "Improve modifying tool selections for code changes",
                showSwitch = true,
                default = writerEnabled,
                sideEffect = { checked ->
                    writerEnabled = checked
                    Settings.writer_agent_enabled = checked
                }
            )
        }

        // Control workflow API
        PreferenceGroup(heading = "Control API (optional)") {
            var controlEnabled by remember { mutableStateOf(Settings.control_api_enabled) }
            var controlBase by remember { mutableStateOf(Settings.control_api_base_url) }
            SettingsToggle(
                label = "Enable external control API",
                description = "Send telemetry and request plans from an external orchestrator",
                showSwitch = true,
                default = controlEnabled,
                sideEffect = { checked ->
                    controlEnabled = checked
                    Settings.control_api_enabled = checked
                }
            )
            Column(modifier = Modifier.padding(horizontal = 16.dp)) {
                OutlinedTextField(
                    value = controlBase,
                    onValueChange = { v -> controlBase = v; Settings.control_api_base_url = v },
                    label = { Text("Control API Base URL") },
                    modifier = Modifier.fillMaxWidth().padding(top = 8.dp),
                    singleLine = true
                )
            }
        }
    }
}