package com.rk.terminal.ssh

import android.content.Context
import android.content.SharedPreferences
import androidx.security.crypto.EncryptedSharedPreferences
import androidx.security.crypto.MasterKey
import kotlinx.serialization.encodeToString
import kotlinx.serialization.decodeFromString
import kotlinx.serialization.json.Json
import java.security.GeneralSecurityException
import java.io.IOException

class SshConfigManager(private val context: Context) {
    private val json = Json { ignoreUnknownKeys = true }
    
    private val masterKey by lazy {
        MasterKey.Builder(context)
            .setKeyScheme(MasterKey.KeyScheme.AES256_GCM)
            .build()
    }
    
    private val encryptedPrefs: SharedPreferences by lazy {
        try {
            EncryptedSharedPreferences.create(
                context,
                "ssh_configs",
                masterKey,
                EncryptedSharedPreferences.PrefKeyEncryptionScheme.AES256_SIV,
                EncryptedSharedPreferences.PrefValueEncryptionScheme.AES256_GCM
            )
        } catch (e: GeneralSecurityException) {
            // Fallback to regular SharedPreferences if encryption fails
            context.getSharedPreferences("ssh_configs_fallback", Context.MODE_PRIVATE)
        } catch (e: IOException) {
            context.getSharedPreferences("ssh_configs_fallback", Context.MODE_PRIVATE)
        }
    }
    
    fun saveConfig(config: SshConfig): Boolean {
        return try {
            android.util.Log.d("SshConfigManager", "Saving SSH config: ${config.name} (${config.hostname}:${config.port})")
            val configs = getSavedConfigs().toMutableList()
            val existingIndex = configs.indexOfFirst { it.id == config.id }
            
            if (existingIndex >= 0) {
                configs[existingIndex] = config
                android.util.Log.d("SshConfigManager", "Updated existing config at index $existingIndex")
            } else {
                configs.add(config)
                android.util.Log.d("SshConfigManager", "Added new config, total configs: ${configs.size}")
            }
            
            val savedConfigs = SavedSshConfigs(configs)
            val jsonString = json.encodeToString(savedConfigs)
            encryptedPrefs.edit().putString("configs", jsonString).apply()
            android.util.Log.d("SshConfigManager", "SSH config saved successfully")
            true
        } catch (e: Exception) {
            android.util.Log.e("SshConfigManager", "Failed to save SSH config", e)
            false
        }
    }
    
    fun getSavedConfigs(): List<SshConfig> {
        return try {
            val jsonString = encryptedPrefs.getString("configs", null)
            if (jsonString != null) {
                json.decodeFromString<SavedSshConfigs>(jsonString).configs
            } else {
                emptyList()
            }
        } catch (e: Exception) {
            emptyList()
        }
    }
    
    fun getConfig(id: String): SshConfig? {
        return getSavedConfigs().find { it.id == id }
    }
    
    fun deleteConfig(id: String): Boolean {
        return try {
            val configs = getSavedConfigs().toMutableList()
            configs.removeAll { it.id == id }
            val savedConfigs = SavedSshConfigs(configs)
            val jsonString = json.encodeToString(savedConfigs)
            encryptedPrefs.edit().putString("configs", jsonString).apply()
            true
        } catch (e: Exception) {
            false
        }
    }
    
    fun generateConfigId(): String {
        return "ssh_${System.currentTimeMillis()}"
    }
}