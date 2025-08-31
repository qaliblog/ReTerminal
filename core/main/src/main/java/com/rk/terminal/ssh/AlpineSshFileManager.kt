package com.rk.terminal.ssh

import android.util.Log
import com.rk.libcommons.alpineDir
import java.io.File

class AlpineSshFileManager(
    private val sshConfig: SshConfig
) {
    companion object {
        private const val TAG = "AlpineSshFileManager"
    }
    
    fun getWorkingDirectory(): String {
        return sshConfig.workingDirectory
    }
    
    fun getConnectionInfo(): String {
        return "${sshConfig.username}@${sshConfig.hostname}:${sshConfig.port}"
    }
    
    fun getSshCommand(): String {
        val cmd = StringBuilder("ssh")
        
        if (sshConfig.port != 22) {
            cmd.append(" -p ${sshConfig.port}")
        }
        
        if (sshConfig.authMethod != AuthMethod.PASSWORD && sshConfig.privateKeyPath.isNotEmpty()) {
            cmd.append(" -i ${sshConfig.privateKeyPath}")
        }
        
        cmd.append(" -o ConnectTimeout=${sshConfig.connectTimeout / 1000}")
        cmd.append(" -o ServerAliveInterval=${sshConfig.keepAliveInterval / 1000}")
        
        if (!sshConfig.strictHostKeyChecking) {
            cmd.append(" -o StrictHostKeyChecking=no")
            cmd.append(" -o UserKnownHostsFile=/dev/null")
        }
        
        if (sshConfig.compressionEnabled) {
            cmd.append(" -C")
        }
        
        cmd.append(" ${sshConfig.username}@${sshConfig.hostname}")
        
        return cmd.toString()
    }
    
    fun createRemoteListCommand(remotePath: String): String {
        return "${getSshCommand()} 'ls -la \"$remotePath\"'"
    }
    
    fun createRemoteEditCommand(remoteFile: String): String {
        return "${getSshCommand()} 'cat \"$remoteFile\"'"
    }
    
    fun createRemoteSaveCommand(remoteFile: String, content: String): String {
        // Escape content for shell
        val escapedContent = content.replace("'", "'\\''")
        return "${getSshCommand()} 'echo '$escapedContent' > \"$remoteFile\"'"
    }
    
    fun createRemoteMkdirCommand(remotePath: String): String {
        return "${getSshCommand()} 'mkdir -p \"$remotePath\"'"
    }
    
    fun createRemoteDeleteCommand(remotePath: String): String {
        return "${getSshCommand()} 'rm -rf \"$remotePath\"'"
    }
    
    fun createRemotePwdCommand(): String {
        return "${getSshCommand()} 'pwd'"
    }
    
    fun createRemoteCdCommand(remotePath: String): String {
        return "${getSshCommand()} 'cd \"$remotePath\" && pwd'"
    }
    
    // Helper method to get Alpine root directory for local file operations
    fun getAlpineRoot(): File {
        return alpineDir()
    }
    
    // Create a simple script file in Alpine for complex operations
    fun createSshScript(scriptContent: String): String {
        val scriptFile = "/tmp/ssh_script_${System.currentTimeMillis()}.sh"
        val alpineScriptPath = File(alpineDir(), "root${scriptFile}")
        
        try {
            alpineScriptPath.parentFile?.mkdirs()
            alpineScriptPath.writeText(scriptContent)
            Log.d(TAG, "Created SSH script at ${alpineScriptPath.absolutePath}")
            return scriptFile
        } catch (e: Exception) {
            Log.e(TAG, "Error creating SSH script", e)
            return ""
        }
    }
}