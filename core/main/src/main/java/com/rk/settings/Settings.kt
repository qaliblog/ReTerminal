package com.rk.settings

import android.annotation.SuppressLint
import android.content.Context
import android.content.SharedPreferences
import android.content.res.Configuration
import android.os.Build
import androidx.appcompat.app.AppCompatDelegate
import androidx.compose.runtime.Composable
import androidx.compose.ui.platform.LocalContext
import androidx.core.content.pm.PackageInfoCompat
import com.rk.components.compose.preferences.normal.Preference
import com.rk.libcommons.application
import com.rk.terminal.ui.screens.settings.WorkingMode
import java.nio.charset.Charset
import org.json.JSONArray
import androidx.security.crypto.EncryptedSharedPreferences
import androidx.security.crypto.MasterKey

object Settings {
    //Boolean

    var amoled
        get() = Preference.getBoolean(key = "oled", default = false)
        set(value) = Preference.setBoolean(key = "oled",value)
    var monet
        get() = Preference.getBoolean(
            key = "monet",
            default = Build.VERSION.SDK_INT >= Build.VERSION_CODES.S
        )
        set(value) = Preference.setBoolean(key = "monet",value)
    var ignore_storage_permission
        get() = Preference.getBoolean(key = "ignore_storage_permission",default = false)
        set(value) = Preference.setBoolean(key = "ignore_storage_permission",value)
    var github
        get() = Preference.getBoolean(key = "github", default = true)
        set(value) = Preference.setBoolean(key = "github",value)


    var default_night_mode
        get() = Preference.getInt(key = "default_night_mode", default = AppCompatDelegate.MODE_NIGHT_FOLLOW_SYSTEM)
        set(value) = Preference.setInt(key = "default_night_mode",value)

    var terminal_font_size
        get() = Preference.getInt(key = "terminal_font_size", default = 13)
        set(value) = Preference.setInt(key = "terminal_font_size",value)
    var working_Mode
        get() = Preference.getInt(key = "workingMode", default = WorkingMode.ALPINE)
        set(value) = Preference.setInt(key = "workingMode",value)

    var custom_background_name
        get() = Preference.getString(key = "custom_bg_name", default = "No Image Selected")
        set(value) = Preference.setString(key = "custom_bg_name",value)
    var custom_font_name
        get() = Preference.getString(key = "custom_ttf_name", default = "No Font Selected")
        set(value) = Preference.setString(key = "custom_ttf_name",value)

    var blackTextColor
        get() = Preference.getBoolean(key = "blackText", default = false)
        set(value) = Preference.setBoolean(key = "blackText",value)

    var bell
        get() = Preference.getBoolean(key = "bell", default = false)
        set(value) = Preference.setBoolean(key = "bell",value)

    var vibrate
        get() = Preference.getBoolean(key = "vibrate", default = true)
        set(value) = Preference.setBoolean(key = "vibrate",value)

    var toolbar
        get() = Preference.getBoolean(key = "toolbar", default = true)
        set(value) = Preference.setBoolean(key = "toolbar",value)

    var statusBar
        get() = Preference.getBoolean(key = "statusBar", default = true)
        set(value) = Preference.setBoolean(key = "statusBar",value)

    var horizontal_statusBar
        get() = Preference.getBoolean(key = "horizontal_statusBar", default = true)
        set(value) = Preference.setBoolean(key = "horizontal_statusBar",value)

    var toolbar_in_horizontal
        get() = Preference.getBoolean(key = "toolbar_h", default = true)
        set(value) = Preference.setBoolean(key = "toolbar_h",value)

    var virtualKeys
        get() = Preference.getBoolean(key = "virtualKeys", default = true)
        set(value) = Preference.setBoolean(key = "virtualKeys",value)

    var hide_soft_keyboard_if_hwd
        get() = Preference.getBoolean(key = "force_soft_keyboard", default = true)
        set(value) = Preference.setBoolean(key = "force_soft_keyboard",value)

    // Model management (legacy, kept for backward compatibility but unused in API mode)
    var model_folders_csv
        get() = Preference.getString(key = "model_folders_csv", default = "")
        set(value) = Preference.setString(key = "model_folders_csv", value)

    var selected_model_folder
        get() = Preference.getString(key = "selected_model_folder", default = "")
        set(value) = Preference.setString(key = "selected_model_folder", value)

    // API-based chat settings
    var api_provider
        get() = Preference.getString(key = "api_provider", default = "none")
        set(value) = Preference.setString(key = "api_provider", value)

    var api_key
        get() = Preference.getString(key = "api_key", default = "")
        set(value) = Preference.setString(key = "api_key", value)

    var api_base_url
        get() = Preference.getString(key = "api_base_url", default = "https://api.openai.com")
        set(value) = Preference.setString(key = "api_base_url", value)

    var api_model
        get() = Preference.getString(key = "api_model", default = "gpt-4o-mini")
        set(value) = Preference.setString(key = "api_model", value)

    // Gemini API key rotation
    var api_key_rotation_enabled
        get() = Preference.getBoolean(key = "api_key_rotation_enabled", default = false)
        set(value) = Preference.setBoolean(key = "api_key_rotation_enabled", value)

    var gemini_api_keys_json
        get() = Preference.getString(key = "gemini_api_keys_json", default = "[]")
        set(value) = Preference.setString(key = "gemini_api_keys_json", value)

    fun getGeminiApiKeys(): MutableList<String> {
        return try {
            val arr = JSONArray(gemini_api_keys_json)
            val list = ArrayList<String>(arr.length())
            for (i in 0 until arr.length()) {
                val k = arr.optString(i)
                if (!k.isNullOrBlank()) list.add(k)
            }
            list
        } catch (_: Exception) {
            mutableListOf()
        }
    }

    fun setGeminiApiKeys(keys: List<String>) {
        val sanitized = keys.map { it.trim() }.filter { it.isNotEmpty() }
        val arr = JSONArray()
        sanitized.forEach { arr.put(it) }
        gemini_api_keys_json = arr.toString()
    }

    // Back-plan auto fix
    var backplan_enabled
        get() = Preference.getBoolean(key = "backplan_enabled", default = false)
        set(value) = Preference.setBoolean(key = "backplan_enabled", value)

    // Main instructions injection (expectations + blueprint + codebase summary)
    var main_instructions_enabled
        get() = Preference.getBoolean(key = "main_instructions_enabled", default = false)
        set(value) = Preference.setBoolean(key = "main_instructions_enabled", value)

    // Helper agent toggle
    var helper_agent_enabled
        get() = Preference.getBoolean(key = "helper_agent_enabled", default = false)
        set(value) = Preference.setBoolean(key = "helper_agent_enabled", value)

    // Optional overrides that helper can set ephemerally
    var ai_max_tokens
        get() = Preference.getInt(key = "ai_max_tokens", default = 1024)
        set(value) = Preference.setInt(key = "ai_max_tokens", value)

    var ai_temperature_str
        get() = Preference.getString(key = "ai_temperature_str", default = "")
        set(value) = Preference.setString(key = "ai_temperature_str", value)

    // Codebase agent
    var codebase_agent_enabled
        get() = Preference.getBoolean(key = "codebase_agent_enabled", default = false)
        set(value) = Preference.setBoolean(key = "codebase_agent_enabled", value)

    var codebase_cache_path
        get() = Preference.getString(key = "codebase_cache_path", default = "codebase_cache.json")
        set(value) = Preference.setString(key = "codebase_cache_path", value)

    // Informative agent
    var informative_agent_enabled
        get() = Preference.getBoolean(key = "informative_agent_enabled", default = false)
        set(value) = Preference.setBoolean(key = "informative_agent_enabled", value)

    // Researcher agent
    var researcher_agent_enabled
        get() = Preference.getBoolean(key = "researcher_agent_enabled", default = false)
        set(value) = Preference.setBoolean(key = "researcher_agent_enabled", value)

    // Writer agent
    var writer_agent_enabled
        get() = Preference.getBoolean(key = "writer_agent_enabled", default = false)
        set(value) = Preference.setBoolean(key = "writer_agent_enabled", value)

    // Control workflow API
    var control_api_enabled
        get() = Preference.getBoolean(key = "control_api_enabled", default = false)
        set(value) = Preference.setBoolean(key = "control_api_enabled", value)

    var control_api_base_url
        get() = Preference.getString(key = "control_api_base_url", default = "")
        set(value) = Preference.setString(key = "control_api_base_url", value)
}

object Preference {
    private var sharedPreferences: SharedPreferences = application!!.getSharedPreferences("Settings", Context.MODE_PRIVATE)

    // Encrypted preferences for sensitive values
    private val securePreferences: SharedPreferences by lazy {
        val masterKey = MasterKey.Builder(application!!)
            .setKeyScheme(MasterKey.KeyScheme.AES256_GCM)
            .build()
        EncryptedSharedPreferences.create(
            application!!,
            "SecureSettings",
            masterKey,
            EncryptedSharedPreferences.PrefKeyEncryptionScheme.AES256_SIV,
            EncryptedSharedPreferences.PrefValueEncryptionScheme.AES256_GCM
        )
    }

    private fun isSensitiveKey(key: String): Boolean {
        return key == "api_key" || key == "gemini_api_keys_json"
    }

    //store the result into memory for faster access
    private val memory = HashMap<String,Any>()
    fun getBoolean(key: String,default: Boolean): Boolean{
        return if (memory.containsKey(key) && memory[key] is Boolean) memory[key] as Boolean
        else{
            if (sharedPreferences.contains(key)){
                val result = sharedPreferences.getBoolean(key,default)
                memory[key] = result
                result
            }else{
                memory[key] = default
                sharedPreferences.getBoolean(key,default)
            }
        }
    }
    fun setBoolean(key: String,value: Boolean){
        memory[key] = value
        sharedPreferences.edit().putBoolean(key, value).apply()
    }

    fun getString(key: String,default: String): String{
        return if (memory.containsKey(key) && memory[key] is String) memory[key] as String
        else{
            if (isSensitiveKey(key)){
                // Migrate from plain prefs if needed
                if (!securePreferences.contains(key) && sharedPreferences.contains(key)) {
                    val legacy = sharedPreferences.getString(key, default) ?: default
                    securePreferences.edit().putString(key, legacy).apply()
                    sharedPreferences.edit().remove(key).apply()
                }
                val result = securePreferences.getString(key, default) ?: default
                memory[key] = result
                result
            } else if (sharedPreferences.contains(key)){
                val result = sharedPreferences.getString(key,default) ?: default
                memory[key] = result
                result
            }else{
                memory[key] = default
                if (isSensitiveKey(key)) securePreferences.getString(key, default) ?: default else sharedPreferences.getString(key,default) ?: default
            }
        }
    }
    fun setString(key: String,value: String){
        memory[key] = value
        if (isSensitiveKey(key)) {
            securePreferences.edit().putString(key, value).apply()
        } else {
            sharedPreferences.edit().putString(key, value).apply()
        }
    }

    fun getInt(key: String,default: Int): Int{
        return if (memory.containsKey(key) && memory[key] is Int) memory[key] as Int
        else{
            if (sharedPreferences.contains(key)){
                val result = sharedPreferences.getInt(key,default)
                memory[key] = result
                result
            }else{
                memory[key] = default
                sharedPreferences.getInt(key,default)
            }
        }
    }
    fun setInt(key: String,value: Int){
        memory[key] = value
        sharedPreferences.edit().putInt(key, value).apply()
    }

    @SuppressLint("ApplySharedPref")
    fun clearData(){
        sharedPreferences.edit().clear().commit()
        securePreferences.edit().clear().commit()
    }

    fun removeKey(key: String){
        if (sharedPreferences.contains(key).not() && securePreferences.contains(key).not()){
            return
        }
        if (isSensitiveKey(key)) {
            securePreferences.edit().remove(key).apply()
        } else {
            sharedPreferences.edit().remove(key).apply()
        }
        memory.remove(key)
    }
}
