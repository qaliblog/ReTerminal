package com.rk.terminal.ssh

import android.util.Log
import com.termux.terminal.TerminalSession
import com.termux.terminal.TerminalSessionClient

class SshAwareSessionClient(
    private val originalClient: TerminalSessionClient,
    private val terminalSession: TerminalSession
) : TerminalSessionClient {
    
    companion object {
        private const val TAG = "SshAwareSessionClient"
    }
    
    override fun onTextChanged(changedSession: TerminalSession) {
        originalClient.onTextChanged(changedSession)
    }
    
    override fun onTitleChanged(changedSession: TerminalSession) {
        originalClient.onTitleChanged(changedSession)
    }
    
    override fun onSessionFinished(finishedSession: TerminalSession) {
        // Clean up SSH when session finishes
        SshTerminalManager.cleanupSshSession(finishedSession)
        originalClient.onSessionFinished(finishedSession)
    }
    
    override fun onBell(session: TerminalSession) {
        originalClient.onBell(session)
    }
    
    override fun onColorsChanged(session: TerminalSession) {
        originalClient.onColorsChanged(session)
    }
    
    override fun onTerminalCursorStateChange(state: Boolean) {
        originalClient.onTerminalCursorStateChange(state)
    }
    
    override fun onCopyTextToClipboard(session: TerminalSession, text: String) {
        originalClient.onCopyTextToClipboard(session, text)
    }
    
    override fun onPasteTextFromClipboard(session: TerminalSession) {
        // Try to redirect paste to SSH first
        val handled = SshTerminalManager.handleTerminalInput(session, getClipboardText())
        if (!handled) {
            originalClient.onPasteTextFromClipboard(session)
        }
    }
    
    override fun getTerminalCursorStyle(): Int {
        return originalClient.getTerminalCursorStyle()
    }
    
    override fun logError(tag: String?, message: String?) {
        originalClient.logError(tag, message)
    }
    
    override fun logWarn(tag: String?, message: String?) {
        originalClient.logWarn(tag, message)
    }
    
    override fun logInfo(tag: String?, message: String?) {
        originalClient.logInfo(tag, message)
    }
    
    override fun logDebug(tag: String?, message: String?) {
        originalClient.logDebug(tag, message)
    }
    
    override fun logVerbose(tag: String?, message: String?) {
        originalClient.logVerbose(tag, message)
    }
    
    override fun logStackTraceWithMessage(tag: String?, message: String?, e: Exception?) {
        originalClient.logStackTraceWithMessage(tag, message, e)
    }
    
    override fun logStackTrace(tag: String?, e: Exception?) {
        originalClient.logStackTrace(tag, e)
    }
    
    private fun getClipboardText(): String {
        return try {
            // This would need proper clipboard implementation
            ""
        } catch (e: Exception) {
            ""
        }
    }
}