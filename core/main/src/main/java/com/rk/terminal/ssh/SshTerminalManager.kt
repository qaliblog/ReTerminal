package com.rk.terminal.ssh

import android.util.Log
import com.termux.terminal.TerminalSession
import kotlinx.coroutines.*

class SshTerminalManager {
    companion object {
        private const val TAG = "SshTerminalManager"
        private val activeSshSessions = mutableMapOf<TerminalSession, SafeSshTerminal>()
        
        fun registerSshSession(terminalSession: TerminalSession, sshTerminal: SafeSshTerminal) {
            activeSshSessions[terminalSession] = sshTerminal
            Log.d(TAG, "Registered SSH session for terminal")
        }
        
        fun getSshTerminal(terminalSession: TerminalSession): SafeSshTerminal? {
            return activeSshSessions[terminalSession]
        }
        
        fun handleTerminalInput(terminalSession: TerminalSession, input: String): Boolean {
            val sshTerminal = activeSshSessions[terminalSession]
            return if (sshTerminal != null && sshTerminal.isConnected()) {
                Log.d(TAG, "Redirecting input to SSH: $input")
                sshTerminal.writeToSsh(input)
                true // Input handled by SSH
            } else {
                false // Let terminal handle normally
            }
        }
        
        fun handleTerminalInput(terminalSession: TerminalSession, data: ByteArray, offset: Int, count: Int): Boolean {
            val sshTerminal = activeSshSessions[terminalSession]
            return if (sshTerminal != null && sshTerminal.isConnected()) {
                val text = String(data, offset, count)
                Log.d(TAG, "Redirecting bytes to SSH: $text")
                sshTerminal.writeToSsh(data, offset, count)
                true // Input handled by SSH
            } else {
                false // Let terminal handle normally
            }
        }
        
        fun cleanupSshSession(terminalSession: TerminalSession) {
            val sshTerminal = activeSshSessions.remove(terminalSession)
            sshTerminal?.cleanup()
            Log.d(TAG, "Cleaned up SSH session for terminal")
        }
    }
}