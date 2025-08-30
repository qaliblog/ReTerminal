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
    
    override fun onTerminalCursorStateChanged(state: Boolean) {
        originalClient.onTerminalCursorStateChanged(state)
    }
    
    override fun setTerminalShellPid(session: TerminalSession, pid: Int) {
        // SSH sessions don't have local PIDs
        originalClient.setTerminalShellPid(session, pid)
    }
    
    override fun onScale(scale: Float): Float {
        return originalClient.onScale(scale)
    }
    
    override fun onSingleTapUp(e: android.view.MotionEvent) {
        originalClient.onSingleTapUp(e)
    }
    
    override fun shouldBackButtonBeMappedToEscape(): Boolean {
        return originalClient.shouldBackButtonBeMappedToEscape()
    }
    
    override fun shouldEnforceCharBasedInput(): Boolean {
        return originalClient.shouldEnforceCharBasedInput()
    }
    
    override fun shouldUseCtrlSpaceWorkaround(): Boolean {
        return originalClient.shouldUseCtrlSpaceWorkaround()
    }
    
    override fun isTerminalViewSelected(): Boolean {
        return originalClient.isTerminalViewSelected()
    }
    
    override fun copyModeChanged(copyMode: Boolean) {
        originalClient.copyModeChanged(copyMode)
    }
    
    override fun onKeyDown(keyCode: Int, e: android.view.KeyEvent, session: TerminalSession): Boolean {
        return originalClient.onKeyDown(keyCode, e, session)
    }
    
    override fun onKeyUp(keyCode: Int, e: android.view.KeyEvent): Boolean {
        return originalClient.onKeyUp(keyCode, e)
    }
    
    override fun onLongPress(event: android.view.MotionEvent): Boolean {
        return originalClient.onLongPress(event)
    }
    
    override fun readControlKey(): Boolean {
        return originalClient.readControlKey()
    }
    
    override fun readAltKey(): Boolean {
        return originalClient.readAltKey()
    }
    
    override fun readShiftKey(): Boolean {
        return originalClient.readShiftKey()
    }
    
    override fun readFnKey(): Boolean {
        return originalClient.readFnKey()
    }
    
    override fun onCodePoint(codePoint: Int, ctrlDown: Boolean, session: TerminalSession): Boolean {
        return originalClient.onCodePoint(codePoint, ctrlDown, session)
    }
}