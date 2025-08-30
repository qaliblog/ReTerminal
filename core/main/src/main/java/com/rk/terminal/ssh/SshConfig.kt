package com.rk.terminal.ssh

import kotlinx.serialization.Serializable

@Serializable
data class SshConfig(
    val id: String = "",
    val name: String = "",
    val hostname: String = "",
    val port: Int = 22,
    val username: String = "",
    val password: String = "",
    val privateKeyPath: String = "",
    val passphrase: String = "",
    val authMethod: AuthMethod = AuthMethod.PASSWORD,
    val strictHostKeyChecking: Boolean = false,
    val connectTimeout: Int = 30000, // 30 seconds
    val keepAliveInterval: Int = 60000, // 60 seconds
    val compressionEnabled: Boolean = true,
    val forwardX11: Boolean = false,
    val workingDirectory: String = "~",
    val environmentVariables: Map<String, String> = emptyMap()
)

enum class AuthMethod {
    PASSWORD,
    PRIVATE_KEY,
    PASSWORD_AND_KEY
}

@Serializable
data class SavedSshConfigs(
    val configs: List<SshConfig> = emptyList()
)