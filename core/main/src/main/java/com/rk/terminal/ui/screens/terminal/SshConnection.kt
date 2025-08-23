package com.rk.terminal.ui.screens.terminal

import androidx.compose.runtime.mutableStateOf
import org.json.JSONObject

data class SshConnectionConfig(
    val host: String,
    val port: Int = 22,
    val username: String,
    val password: String = "",
    val privateKeyPath: String = "",
    val name: String = "",
    val useKey: Boolean = false
) {
    fun toJson(): JSONObject {
        return JSONObject().apply {
            put("host", host)
            put("port", port)
            put("username", username)
            put("password", password)
            put("privateKeyPath", privateKeyPath)
            put("name", name)
            put("useKey", useKey)
        }
    }

    companion object {
        fun fromJson(json: JSONObject): SshConnectionConfig {
            return SshConnectionConfig(
                host = json.getString("host"),
                port = json.optInt("port", 22),
                username = json.getString("username"),
                password = json.optString("password", ""),
                privateKeyPath = json.optString("privateKeyPath", ""),
                name = json.optString("name", ""),
                useKey = json.optBoolean("useKey", false)
            )
        }
    }
}

object SshConnectionManager {
    private val _savedConnections = mutableStateOf<List<SshConnectionConfig>>(emptyList())
    val savedConnections get() = _savedConnections.value

    fun loadSavedConnections(): List<SshConnectionConfig> {
        // TODO: Load from preferences
        return emptyList()
    }

    fun saveConnection(config: SshConnectionConfig) {
        // TODO: Save to preferences
        val current = _savedConnections.value.toMutableList()
        current.add(config)
        _savedConnections.value = current
    }

    fun deleteConnection(config: SshConnectionConfig) {
        val current = _savedConnections.value.toMutableList()
        current.removeIf { it.host == config.host && it.username == config.username }
        _savedConnections.value = current
    }
}