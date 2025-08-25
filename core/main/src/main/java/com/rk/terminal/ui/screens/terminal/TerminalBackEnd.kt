package com.rk.terminal.ui.screens.terminal

import android.content.res.Configuration
import android.content.res.Resources
import android.media.MediaPlayer
import android.util.Log
import android.view.KeyEvent
import android.view.MotionEvent
import androidx.lifecycle.lifecycleScope
import com.blankj.utilcode.util.ClipboardUtils
import com.blankj.utilcode.util.KeyboardUtils
import com.rk.libcommons.child
import com.rk.libcommons.createFileIfNot
import com.rk.libcommons.dpToPx
import com.rk.settings.Settings
import com.rk.terminal.ui.activities.terminal.MainActivity
import com.rk.terminal.ui.screens.terminal.virtualkeys.SpecialButton
import com.rk.terminal.ui.screens.terminal.virtualkeys.VirtualKeysView
import com.termux.terminal.TerminalEmulator
import com.termux.terminal.TerminalSession
import com.termux.terminal.TerminalSessionClient
import com.termux.view.TerminalView
import com.termux.view.TerminalViewClient
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext
import java.io.File
import java.io.FileOutputStream

class TerminalBackEnd(val terminal: TerminalView,val activity: MainActivity) : TerminalViewClient, TerminalSessionClient {
    override fun onTextChanged(changedSession: TerminalSession) {
        terminal.onScreenUpdated()
    }
    
    override fun onTitleChanged(changedSession: TerminalSession) {

    }
    
    override fun onSessionFinished(finishedSession: TerminalSession) {

    }
    
    override fun onCopyTextToClipboard(session: TerminalSession, text: String) {
        ClipboardUtils.copyText("Terminal", text)
    }
    
    override fun onPasteTextFromClipboard(session: TerminalSession) {
        val clip = ClipboardUtils.getText().toString()
        if (clip.trim { it <= ' ' }.isNotEmpty() && terminal.mEmulator != null) {
            val service = activity.sessionBinder?.getService()
            if (service?.isInteractiveSsh(session) == true) {
                val sshTerm = service.getSshTerminalSessionForTerminalSession(session)
                if (sshTerm != null) {
                    Log.d("TerminalBackEnd", "Pasting text to SSH session: '${clip.take(50)}...'")
                    sshTerm.sendInput(clip)
                } else {
                    // Fallback to regular session write
                    session.write(clip)
                }
            } else {
                terminal.mEmulator.paste(clip)
            }
        }
    }
    
    override fun onBell(session: TerminalSession) {
        if (Settings.bell){
            activity.lifecycleScope.launch{
                val bellFile = activity.cacheDir.child("bell.oga")
                if (bellFile.exists().not()){
                    bellFile.createNewFile()
                    withContext(Dispatchers.IO){
                        activity.assets.open("bell.oga").use { assetIS ->
                            FileOutputStream(bellFile).use { bellFileOutS ->
                                assetIS.copyTo(bellFileOutS)
                            }
                        }
                    }

                }

                val mediaPlayer = MediaPlayer()
                mediaPlayer.setOnCompletionListener{
                    it?.release()
                }
                mediaPlayer.setDataSource(bellFile.absolutePath)
                mediaPlayer.prepare()
                mediaPlayer.start()
            }
        }
    }
    
    override fun onColorsChanged(session: TerminalSession) {}
    
    override fun onTerminalCursorStateChange(state: Boolean) {}
    
    override fun getTerminalCursorStyle(): Int {
        return TerminalEmulator.DEFAULT_TERMINAL_CURSOR_STYLE
    }
    
    override fun logError(tag: String?, message: String?) {
        Log.e(tag.toString(), message.toString())
    }
    
    override fun logWarn(tag: String?, message: String?) {
        Log.w(tag.toString(), message.toString())
    }
    
    override fun logInfo(tag: String?, message: String?) {
        Log.i(tag.toString(), message.toString())
    }
    
    override fun logDebug(tag: String?, message: String?) {
        Log.d(tag.toString(), message.toString())
    }
    
    override fun logVerbose(tag: String?, message: String?) {
        Log.v(tag.toString(), message.toString())
    }
    
    override fun logStackTraceWithMessage(tag: String?, message: String?, e: Exception?) {
        Log.e(tag.toString(), message.toString())
        e?.printStackTrace()
    }
    
    override fun logStackTrace(tag: String?, e: Exception?) {
        e?.printStackTrace()
    }

    override fun onScale(scale: Float): Float {
        val fontScale = scale.coerceIn(11f, 45f)
        terminal.setTextSize(fontScale.toInt())
        return fontScale
    }

    val isHardwareKeyboardConnected: Boolean
        get() {
            val config = Resources.getSystem().configuration
            return config.keyboard != Configuration.KEYBOARD_NOKEYS
        }


    override fun onSingleTapUp(e: MotionEvent) {
        if (!(isHardwareKeyboardConnected && Settings.hide_soft_keyboard_if_hwd)){
            showSoftInput()
        }
    }
    
    override fun shouldBackButtonBeMappedToEscape(): Boolean {
        return false
    }
    
    override fun shouldEnforceCharBasedInput(): Boolean {
        return true
    }
    
    override fun shouldUseCtrlSpaceWorkaround(): Boolean {
        return true
    }
    
    override fun isTerminalViewSelected(): Boolean {
        return true
    }
    
    override fun copyModeChanged(copyMode: Boolean) {}
    
    override fun onKeyDown(keyCode: Int, e: KeyEvent, session: TerminalSession): Boolean {
                    if (keyCode == KeyEvent.KEYCODE_ENTER && !session.isRunning) {
                activity.sessionBinder?.terminateSession(activity.sessionBinder!!.getService().currentSession.value.first)
                if (activity.sessionBinder!!.getService().sessionList.isEmpty()){
                    // Move app to background instead of closing
                    activity.moveTaskToBack(true)
                }else{
                    changeSession(activity,activity.sessionBinder!!.getService().sessionList.keys.first())
                }
                return true
            }
        // For interactive SSH, forward navigation/control keys explicitly if needed
        val service = activity.sessionBinder?.getService()
        if (service?.isInteractiveSsh(session) == true) {
            val sshTerm = service.getSshTerminalSessionForTerminalSession(session)
            when (keyCode) {
                KeyEvent.KEYCODE_DPAD_UP -> {
                    Log.v("TerminalBackEnd", "Sending UP arrow key to SSH")
                    sshTerm?.sendInput("\u001b[A")
                    return true
                }
                KeyEvent.KEYCODE_DPAD_DOWN -> {
                    Log.v("TerminalBackEnd", "Sending DOWN arrow key to SSH")
                    sshTerm?.sendInput("\u001b[B")
                    return true
                }
                KeyEvent.KEYCODE_DPAD_RIGHT -> {
                    Log.v("TerminalBackEnd", "Sending RIGHT arrow key to SSH")
                    sshTerm?.sendInput("\u001b[C")
                    return true
                }
                KeyEvent.KEYCODE_DPAD_LEFT -> {
                    Log.v("TerminalBackEnd", "Sending LEFT arrow key to SSH")
                    sshTerm?.sendInput("\u001b[D")
                    return true
                }
                KeyEvent.KEYCODE_ENTER -> {
                    Log.v("TerminalBackEnd", "Sending ENTER key to SSH")
                    sshTerm?.sendInput("\r")
                    return true
                }
                KeyEvent.KEYCODE_DEL -> {
                    Log.v("TerminalBackEnd", "Sending BACKSPACE key to SSH")
                    sshTerm?.sendInput("\u007f")
                    return true
                }
                KeyEvent.KEYCODE_FORWARD_DEL -> {
                    Log.v("TerminalBackEnd", "Sending DELETE key to SSH")
                    sshTerm?.sendInput("\u001b[3~")
                    return true
                }
                KeyEvent.KEYCODE_TAB -> {
                    Log.v("TerminalBackEnd", "Sending TAB key to SSH")
                    sshTerm?.sendInput("\t")
                    return true
                }
                KeyEvent.KEYCODE_ESCAPE -> {
                    Log.v("TerminalBackEnd", "Sending ESCAPE key to SSH")
                    sshTerm?.sendInput("\u001b")
                    return true
                }
            }
        }
        return false
    }
    
    override fun onKeyUp(keyCode: Int, e: KeyEvent): Boolean {
        return false
    }
    
    override fun onLongPress(event: MotionEvent): Boolean {
        return false
    }
    
    // keys
    override fun readControlKey(): Boolean {
        val state = virtualKeysView.get()?.readSpecialButton(
            SpecialButton.CTRL, true)
        return state != null && state
    }
    
    override fun readAltKey(): Boolean {
       val state = virtualKeysView.get()?.readSpecialButton(
           SpecialButton.ALT, true)
        return state != null && state
    }
    
    override fun readShiftKey(): Boolean {
        val state = virtualKeysView.get()?.readSpecialButton(
            SpecialButton.SHIFT, true)
        return state != null && state
    }
    
    override fun readFnKey(): Boolean {
        val state = virtualKeysView.get()?.readSpecialButton(
            SpecialButton.FN, true)
        return state != null && state
    }
    
    override fun onCodePoint(codePoint: Int, ctrlDown: Boolean, session: TerminalSession): Boolean {
        val service = activity.sessionBinder?.getService()
        if (service?.isInteractiveSsh(session) == true) {
            val ch = Character.toChars(codePoint)
            val sshTerm = service.getSshTerminalSessionForTerminalSession(session)
            
            // Use enhanced input handling with proper control key support
            val inputStr = if (ctrlDown) {
                // Handle Ctrl+key combinations
                when (codePoint.toChar().lowercaseChar()) {
                    'c' -> "\u0003" // Ctrl+C (SIGINT)
                    'd' -> "\u0004" // Ctrl+D (EOF)
                    'z' -> "\u001a" // Ctrl+Z (SIGTSTP)
                    'l' -> "\u000c" // Ctrl+L (clear screen)
                    else -> String(ch)
                }
            } else {
                String(ch)
            }
            
            Log.v("TerminalBackEnd", "Sending codepoint: $codePoint, char: '${inputStr}', ctrlDown: $ctrlDown")
            sshTerm?.sendInput(inputStr)
            return true
        }
        return false
    }
    
    override fun onEmulatorSet() {
        setTerminalCursorBlinkingState(true)
    }
    
    private fun setTerminalCursorBlinkingState(start: Boolean) {
        if (terminal.mEmulator != null) {
            terminal.setTerminalCursorBlinkerState(start, true)
        }
    }
    
    private fun showSoftInput() {
        terminal.requestFocus()
        KeyboardUtils.showSoftInput(terminal)
    }
}
