package com.rk.terminal.ssh

import com.termux.terminal.TerminalSession
import com.termux.terminal.TerminalSessionClient

class SshTerminalSessionClient(
    private val originalClient: TerminalSessionClient,
    private val sshTerminalSession: SshTerminalSession
) : TerminalSessionClient {
    
    override fun onTextChanged(changedSession: TerminalSession) {
        originalClient.onTextChanged(changedSession)
    }
    
    override fun onTitleChanged(changedSession: TerminalSession) {
        originalClient.onTitleChanged(changedSession)
    }
    
    override fun onSessionFinished(finishedSession: TerminalSession) {
        // Clean up SSH connection when session finishes
        sshTerminalSession.finishIfRunning()
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
        originalClient.onPasteTextFromClipboard(session)
    }
    
    override fun getTerminalCursorStyle(): Int {
        return originalClient.getTerminalCursorStyle()
    }
    
    override fun logError(tag: String?, message: String?) {
        originalClient.logError(tag, message)
    }
}