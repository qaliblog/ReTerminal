package com.rk.terminal.ui.screens.terminal

import androidx.compose.runtime.mutableStateOf
import org.json.JSONArray
import org.json.JSONObject
import com.rk.libcommons.application
import android.content.Context
import android.util.Log
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.GlobalScope
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext

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
    private const val PREFS_NAME = "ssh_connections"
    private const val CONNECTIONS_KEY = "saved_connections"
    
    private val _savedConnections = mutableStateOf<List<SshConnectionConfig>>(emptyList())
    val savedConnections get() = _savedConnections.value

    private val prefs by lazy {
        application?.getSharedPreferences(PREFS_NAME, Context.MODE_PRIVATE)
            ?: throw IllegalStateException("Application context not available")
    }

    init {
        _savedConnections.value = loadSavedConnections()
    }

    fun loadSavedConnections(): List<SshConnectionConfig> {
        return try {
            val connectionsJson = prefs.getString(CONNECTIONS_KEY, "[]")
            val jsonArray = JSONArray(connectionsJson)
            val connections = mutableListOf<SshConnectionConfig>()
            
            for (i in 0 until jsonArray.length()) {
                val connectionJson = jsonArray.getJSONObject(i)
                connections.add(SshConnectionConfig.fromJson(connectionJson))
            }
            
            connections
        } catch (e: Exception) {
            emptyList()
        }
    }

    fun saveConnection(config: SshConnectionConfig) {
        Log.d("SshConnectionManager", "Saving connection: ${config.name} (${config.username}@${config.host})")
        
        // Ensure save operation runs on main thread to avoid handler issues
        GlobalScope.launch(Dispatchers.Main) {
            val current = _savedConnections.value.toMutableList()
            
            // Remove existing connection with same host/username if it exists
            current.removeIf { it.host == config.host && it.username == config.username }
            
            // Add new connection
            current.add(config)
            _savedConnections.value = current
            
            Log.d("SshConnectionManager", "Total saved connections: ${current.size}")
            
            // Save to preferences on IO thread
            withContext(Dispatchers.IO) {
                saveToPreferences(current)
            }
        }
    }

    fun deleteConnection(config: SshConnectionConfig) {
        val current = _savedConnections.value.toMutableList()
        current.removeIf { it.host == config.host && it.username == config.username }
        _savedConnections.value = current
        
        // Save to preferences
        saveToPreferences(current)
    }
    
    private fun saveToPreferences(connections: List<SshConnectionConfig>) {
        try {
            Log.d("SshConnectionManager", "Saving ${connections.size} connections to preferences")
            val jsonArray = JSONArray()
            connections.forEach { config ->
                jsonArray.put(config.toJson())
            }
            
            val result = prefs.edit()
                .putString(CONNECTIONS_KEY, jsonArray.toString())
                .commit() // Use commit() instead of apply() for immediate feedback
                
            Log.d("SshConnectionManager", "Save to preferences result: $result")
        } catch (e: Exception) {
            Log.e("SshConnectionManager", "Failed to save connections to preferences", e)
        }
    }
}