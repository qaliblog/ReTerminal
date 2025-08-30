package com.rk.terminal.ssh

import android.util.Log
import android.view.KeyEvent
import android.view.MotionEvent
import com.rk.terminal.ui.activities.terminal.MainActivity
import com.rk.terminal.ui.screens.terminal.TerminalBackEnd
import com.termux.terminal.TerminalSession
import com.termux.view.TerminalView
import java.nio.charset.StandardCharsets

class SshTerminalBackEnd(
    terminalView: TerminalView,
    mainActivity: MainActivity,
    private val sshTerminalEmulator: SshTerminalEmulator
) : TerminalBackEnd(terminalView, mainActivity) {
    
    companion object {
        private const val TAG = "SshTerminalBackEnd"
    }
    
    override fun onTextChanged(changedSession: TerminalSession?) {
        super.onTextChanged(changedSession)
        // Additional SSH-specific text change handling if needed
    }
    
    override fun onTitleChanged(changedSession: TerminalSession?) {
        super.onTitleChanged(changedSession)
        // Update title to show SSH connection info
        changedSession?.let { session ->
            if (session.tag is SshTerminalEmulator) {
                val sshEmulator = session.tag as SshTerminalEmulator
                val sshInfo = sshEmulator.getSshSession()?.getSessionInfo()
                if (sshInfo != null) {
                    // Update session title with SSH info
                    Log.d(TAG, "SSH session title: $sshInfo")
                }
            }
        }
    }
    
    override fun onSessionFinished(finishedSession: TerminalSession?) {
        super.onSessionFinished(finishedSession)
        // Clean up SSH connection when session finishes
        finishedSession?.let { session ->
            if (session.tag is SshTerminalEmulator) {
                val sshEmulator = session.tag as SshTerminalEmulator
                sshEmulator.cleanup()
                Log.d(TAG, "SSH session cleaned up")
            }
        }
    }
    
    override fun onBell(session: TerminalSession?) {
        super.onBell(session)
        // Handle SSH session bell if needed
    }
    
    override fun onColorsChanged(session: TerminalSession?) {
        super.onColorsChanged(session)
        // Handle SSH session color changes if needed
    }
    
    override fun onTerminalCursorStateChanged(state: Boolean) {
        super.onTerminalCursorStateChanged(state)
        // Handle SSH cursor state changes if needed
    }
    
    override fun setTerminalShellPid(session: TerminalSession?, pid: Int) {
        super.setTerminalShellPid(session, pid)
        // SSH sessions don't have local PIDs, so we can ignore this
        Log.d(TAG, "SSH session PID request ignored (no local process)")
    }
    
    override fun onScale(scale: Float) {
        super.onScale(scale)
        // Handle SSH session scaling if needed
    }
    
    override fun onSingleTapUp(e: MotionEvent?) {
        super.onSingleTapUp(e)
        // Handle SSH session tap events if needed
    }
    
    override fun shouldBackButtonBeMappedToEscape(): Boolean {
        return super.shouldBackButtonBeMappedToEscape()
    }
    
    override fun shouldEnforceCharBasedInput(): Boolean {
        return super.shouldEnforceCharBasedInput()
    }
    
    override fun shouldUseCtrlSpaceWorkaround(): Boolean {
        return super.shouldUseCtrlSpaceWorkaround()
    }
    
    override fun isTerminalViewSelected(): Boolean {
        return super.isTerminalViewSelected()
    }
    
    override fun copyModeChanged(copyMode: Boolean) {
        super.copyModeChanged(copyMode)
        // Handle SSH session copy mode changes if needed
    }
    
    override fun onKeyDown(keyCode: Int, e: KeyEvent?, session: TerminalSession?): Boolean {
        // Forward key events to SSH session
        val result = super.onKeyDown(keyCode, e, session)
        
        // Additional SSH-specific key handling if needed
        session?.let { terminalSession ->
            if (terminalSession.tag is SshTerminalEmulator) {
                // Key event is already handled by the terminal emulator
                // and will be sent to SSH through the normal input stream
            }
        }
        
        return result
    }
    
    override fun onKeyUp(keyCode: Int, e: KeyEvent?): Boolean {
        return super.onKeyUp(keyCode, e)
    }
    
    override fun onLongPress(event: MotionEvent?): Boolean {
        return super.onLongPress(event)
    }
    
    override fun readControlKey(): Boolean {
        return super.readControlKey()
    }
    
    override fun readAltKey(): Boolean {
        return super.readAltKey()
    }
    
    override fun readShiftKey(): Boolean {
        return super.readShiftKey()
    }
    
    override fun readFnKey(): Boolean {
        return super.readFnKey()
    }
    
    override fun onCodePoint(codePoint: Int, ctrlDown: Boolean, session: TerminalSession?): Boolean {
        // Forward code points to SSH session
        val result = super.onCodePoint(codePoint, ctrlDown, session)
        
        session?.let { terminalSession ->
            if (terminalSession.tag is SshTerminalEmulator) {
                val sshEmulator = terminalSession.tag as SshTerminalEmulator
                try {
                    val bytes = Character.toChars(codePoint).concatToString().toByteArray(StandardCharsets.UTF_8)
                    sshEmulator.writeToSsh(bytes)
                } catch (e: Exception) {
                    Log.e(TAG, "Error sending code point to SSH", e)
                }
            }
        }
        
        return result
    }
}