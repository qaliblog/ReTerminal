package com.rk.terminal.ssh

import android.content.Context
import android.graphics.Canvas
import android.graphics.Color
import android.graphics.Paint
import android.graphics.Rect
import android.graphics.Typeface
import android.text.TextPaint
import android.util.AttributeSet
import android.util.Log
import android.view.KeyEvent
import android.view.MotionEvent
import android.view.View
import android.view.inputmethod.EditorInfo
import android.view.inputmethod.InputConnection
import android.view.inputmethod.InputMethodManager
import kotlinx.coroutines.*
import java.util.concurrent.ConcurrentLinkedQueue
import kotlin.math.max
import kotlin.math.min

/**
 * Custom terminal view that directly integrates with native SSH
 * Bypasses Android terminal session limitations for reliable SSH input/output
 */
class NativeSSHTerminalView @JvmOverloads constructor(
    context: Context,
    attrs: AttributeSet? = null,
    defStyleAttr: Int = 0
) : View(context, attrs, defStyleAttr) {

    companion object {
        private const val TAG = "NativeSSHTerminalView"
        private const val DEFAULT_COLS = 80
        private const val DEFAULT_ROWS = 24
    }

    // SSH connection
    private var nativeSSH: NativeSSH? = null
    private val scope = CoroutineScope(Dispatchers.Main + SupervisorJob())

    // Terminal display
    private var terminalBuffer = Array(DEFAULT_ROWS) { CharArray(DEFAULT_COLS) { ' ' } }
    private var cursorRow = 0
    private var cursorCol = 0
    private var terminalCols = DEFAULT_COLS
    private var terminalRows = DEFAULT_ROWS

    // Rendering
    private var textPaint = TextPaint().apply {
        isAntiAlias = true
        typeface = Typeface.MONOSPACE
        color = Color.GREEN
        textSize = 14f * context.resources.displayMetrics.density
    }
    
    private var backgroundPaint = Paint().apply {
        color = Color.BLACK
    }
    
    private var cursorPaint = Paint().apply {
        color = Color.WHITE
        alpha = 180
    }

    private var charWidth = 0f
    private var charHeight = 0f
    private var lineSpacing = 2f

    // Input handling
    private val inputQueue = ConcurrentLinkedQueue<String>()
    private var showCursor = true
    private val cursorBlinkRunnable = object : Runnable {
        override fun run() {
            showCursor = !showCursor
            invalidate()
            postDelayed(this, 500) // Blink every 500ms
        }
    }

    // Connection state
    var onConnectionStateChanged: ((NativeSSH.ConnectionState) -> Unit)? = null
    var onError: ((String) -> Unit)? = null

    init {
        // Make view focusable for input
        isFocusable = true
        isFocusableInTouchMode = true
        
        // Calculate text metrics
        updateTextMetrics()
        
        // Start cursor blinking
        post(cursorBlinkRunnable)
        
        Log.d(TAG, "NativeSSHTerminalView initialized")
    }

    /**
     * Connect to SSH server using native implementation
     */
    suspend fun connectSSH(config: NativeSSH.ConnectionConfig): Result<Unit> {
        return try {
            Log.i(TAG, "Connecting to SSH: ${config.username}@${config.hostname}:${config.port}")
            
            // Create native SSH instance
            nativeSSH = NativeSSH().apply {
                onOutputReceived = { data ->
                    scope.launch {
                        processOutputData(data)
                    }
                }
                onConnectionStateChanged = { state ->
                    scope.launch {
                        this@NativeSSHTerminalView.onConnectionStateChanged?.invoke(state)
                    }
                }
                onError = { error ->
                    scope.launch {
                        this@NativeSSHTerminalView.onError?.invoke(error)
                    }
                }
            }
            
            // Connect to SSH server
            val connectResult = nativeSSH!!.connect(config)
            if (connectResult.isFailure) {
                return connectResult
            }
            
            // Create shell
            val terminalConfig = NativeSSH.TerminalConfig(
                cols = terminalCols,
                rows = terminalRows,
                termType = "xterm-256color"
            )
            
            val shellResult = nativeSSH!!.createShell(terminalConfig)
            if (shellResult.isFailure) {
                return shellResult
            }
            
            // Clear terminal and show connection success
            clearTerminal()
            appendToTerminal("SSH Connection Established\r\n")
            appendToTerminal("Server: ${config.hostname}:${config.port}\r\n")
            appendToTerminal("User: ${config.username}\r\n")
            appendToTerminal("Terminal: ${terminalCols}x${terminalRows}\r\n")
            appendToTerminal("Type commands below:\r\n\r\n")
            
            Log.i(TAG, "SSH connection and shell creation successful")
            Result.success(Unit)
            
        } catch (e: Exception) {
            Log.e(TAG, "SSH connection failed", e)
            onError?.invoke("Connection failed: ${e.message}")
            Result.failure(e)
        }
    }
    
    /**
     * Disconnect SSH session
     */
    fun disconnectSSH() {
        Log.i(TAG, "Disconnecting SSH session")
        
        nativeSSH?.disconnect()
        nativeSSH?.destroy()
        nativeSSH = null
        
        clearTerminal()
        appendToTerminal("SSH Connection Closed\r\n")
        appendToTerminal("Use connection dialog to reconnect\r\n")
    }
    
    /**
     * Check if SSH is connected
     */
    fun isSSHConnected(): Boolean {
        return nativeSSH?.isConnected() == true
    }

    override fun onDraw(canvas: Canvas) {
        super.onDraw(canvas)
        
        // Draw background
        canvas.drawRect(0f, 0f, width.toFloat(), height.toFloat(), backgroundPaint)
        
        // Draw terminal text
        for (row in 0 until terminalRows) {
            val y = (row + 1) * (charHeight + lineSpacing)
            val text = String(terminalBuffer[row])
            canvas.drawText(text, 8f, y, textPaint)
        }
        
        // Draw cursor
        if (showCursor && cursorRow < terminalRows && cursorCol < terminalCols) {
            val x = 8f + cursorCol * charWidth
            val y = cursorRow * (charHeight + lineSpacing) + lineSpacing
            canvas.drawRect(
                x, y, 
                x + charWidth, y + charHeight,
                cursorPaint
            )
        }
    }

    override fun onSizeChanged(w: Int, h: Int, oldw: Int, oldh: Int) {
        super.onSizeChanged(w, h, oldw, oldh)
        
        // Calculate terminal size based on view dimensions
        if (charWidth > 0 && charHeight > 0) {
            val newCols = max(1, ((w - 16) / charWidth).toInt())
            val newRows = max(1, ((h - 16) / (charHeight + lineSpacing)).toInt())
            
            if (newCols != terminalCols || newRows != terminalRows) {
                resizeTerminal(newCols, newRows)
            }
        }
    }

    override fun onKeyDown(keyCode: Int, event: KeyEvent): Boolean {
        Log.d(TAG, "Key down: $keyCode, event: $event")
        
        if (!isSSHConnected()) {
            Log.w(TAG, "SSH not connected, ignoring key input")
            return super.onKeyDown(keyCode, event)
        }
        
        val inputText = when (keyCode) {
            KeyEvent.KEYCODE_ENTER -> "\r\n"
            KeyEvent.KEYCODE_DEL -> "\u007f" // Backspace
            KeyEvent.KEYCODE_TAB -> "\t"
            KeyEvent.KEYCODE_DPAD_UP -> "\u001b[A"
            KeyEvent.KEYCODE_DPAD_DOWN -> "\u001b[B"
            KeyEvent.KEYCODE_DPAD_RIGHT -> "\u001b[C"
            KeyEvent.KEYCODE_DPAD_LEFT -> "\u001b[D"
            else -> {
                // Handle regular character input
                val character = event.unicodeChar
                if (character != 0) {
                    String(Character.toChars(character))
                } else {
                    null
                }
            }
        }
        
        if (inputText != null) {
            sendInput(inputText)
            return true
        }
        
        return super.onKeyDown(keyCode, event)
    }

    override fun onTouchEvent(event: MotionEvent): Boolean {
        when (event.action) {
            MotionEvent.ACTION_DOWN -> {
                // Request focus and show soft keyboard
                requestFocus()
                val imm = context.getSystemService(Context.INPUT_METHOD_SERVICE) as InputMethodManager
                imm.showSoftInput(this, InputMethodManager.SHOW_IMPLICIT)
                return true
            }
        }
        return super.onTouchEvent(event)
    }

    override fun onCreateInputConnection(outAttrs: EditorInfo): InputConnection {
        outAttrs.inputType = EditorInfo.TYPE_CLASS_TEXT
        outAttrs.imeOptions = EditorInfo.IME_FLAG_NO_FULLSCREEN
        
        return SSHInputConnection(this)
    }
    
    /**
     * Send input to SSH session
     */
    fun sendInput(text: String) {
        if (isSSHConnected()) {
            Log.d(TAG, "Sending input: '$text'")
            nativeSSH?.sendInput(text)
        } else {
            Log.w(TAG, "Cannot send input - SSH not connected")
        }
    }
    
    /**
     * Process output data from SSH
     */
    private fun processOutputData(data: ByteArray) {
        val text = String(data)
        Log.d(TAG, "Processing output: ${data.size} bytes")
        
        // Process each character for terminal emulation
        for (char in text) {
            when (char) {
                '\r' -> {
                    cursorCol = 0
                }
                '\n' -> {
                    cursorRow++
                    if (cursorRow >= terminalRows) {
                        scrollUp()
                        cursorRow = terminalRows - 1
                    }
                }
                '\b' -> {
                    if (cursorCol > 0) {
                        cursorCol--
                        terminalBuffer[cursorRow][cursorCol] = ' '
                    }
                }
                '\t' -> {
                    cursorCol = ((cursorCol / 8) + 1) * 8
                    if (cursorCol >= terminalCols) {
                        cursorCol = 0
                        cursorRow++
                        if (cursorRow >= terminalRows) {
                            scrollUp()
                            cursorRow = terminalRows - 1
                        }
                    }
                }
                in ' '..'~' -> {
                    // Printable character
                    if (cursorCol < terminalCols && cursorRow < terminalRows) {
                        terminalBuffer[cursorRow][cursorCol] = char
                        cursorCol++
                        if (cursorCol >= terminalCols) {
                            cursorCol = 0
                            cursorRow++
                            if (cursorRow >= terminalRows) {
                                scrollUp()
                                cursorRow = terminalRows - 1
                            }
                        }
                    }
                }
                // Handle ANSI escape sequences (basic support)
                '\u001b' -> {
                    // TODO: Implement proper ANSI escape sequence handling
                    Log.d(TAG, "ANSI escape sequence detected")
                }
            }
        }
        
        // Trigger redraw
        post { invalidate() }
    }
    
    /**
     * Clear terminal buffer
     */
    private fun clearTerminal() {
        for (row in 0 until terminalRows) {
            for (col in 0 until terminalCols) {
                terminalBuffer[row][col] = ' '
            }
        }
        cursorRow = 0
        cursorCol = 0
        invalidate()
    }
    
    /**
     * Append text to terminal
     */
    private fun appendToTerminal(text: String) {
        for (char in text) {
            when (char) {
                '\r' -> cursorCol = 0
                '\n' -> {
                    cursorRow++
                    if (cursorRow >= terminalRows) {
                        scrollUp()
                        cursorRow = terminalRows - 1
                    }
                }
                else -> {
                    if (cursorCol < terminalCols && cursorRow < terminalRows) {
                        terminalBuffer[cursorRow][cursorCol] = char
                        cursorCol++
                        if (cursorCol >= terminalCols) {
                            cursorCol = 0
                            cursorRow++
                            if (cursorRow >= terminalRows) {
                                scrollUp()
                                cursorRow = terminalRows - 1
                            }
                        }
                    }
                }
            }
        }
        invalidate()
    }
    
    /**
     * Scroll terminal buffer up by one line
     */
    private fun scrollUp() {
        for (row in 0 until terminalRows - 1) {
            for (col in 0 until terminalCols) {
                terminalBuffer[row][col] = terminalBuffer[row + 1][col]
            }
        }
        // Clear last line
        for (col in 0 until terminalCols) {
            terminalBuffer[terminalRows - 1][col] = ' '
        }
    }
    
    /**
     * Resize terminal
     */
    private fun resizeTerminal(newCols: Int, newRows: Int) {
        Log.i(TAG, "Resizing terminal from ${terminalCols}x${terminalRows} to ${newCols}x${newRows}")
        
        val newBuffer = Array(newRows) { CharArray(newCols) { ' ' } }
        
        // Copy existing content
        val copyRows = min(terminalRows, newRows)
        val copyCols = min(terminalCols, newCols)
        
        for (row in 0 until copyRows) {
            for (col in 0 until copyCols) {
                newBuffer[row][col] = terminalBuffer[row][col]
            }
        }
        
        terminalBuffer = newBuffer
        terminalCols = newCols
        terminalRows = newRows
        
        // Adjust cursor position
        cursorRow = min(cursorRow, terminalRows - 1)
        cursorCol = min(cursorCol, terminalCols - 1)
        
        // Notify SSH of terminal resize
        nativeSSH?.resizeTerminal(terminalCols, terminalRows)
        
        invalidate()
    }
    
    /**
     * Update text rendering metrics
     */
    private fun updateTextMetrics() {
        val bounds = Rect()
        textPaint.getTextBounds("M", 0, 1, bounds)
        charWidth = textPaint.measureText("M")
        charHeight = bounds.height().toFloat()
    }
    
    override fun onDetachedFromWindow() {
        super.onDetachedFromWindow()
        
        // Cleanup resources
        removeCallbacks(cursorBlinkRunnable)
        scope.cancel()
        disconnectSSH()
        
        Log.d(TAG, "NativeSSHTerminalView detached and cleaned up")
    }
    
    /**
     * Custom InputConnection for handling soft keyboard input
     */
    private class SSHInputConnection(private val terminalView: NativeSSHTerminalView) : 
        android.view.inputmethod.BaseInputConnection(terminalView, false) {
        
        override fun commitText(text: CharSequence, newCursorPosition: Int): Boolean {
            terminalView.sendInput(text.toString())
            return true
        }
        
        override fun sendKeyEvent(event: KeyEvent): Boolean {
            if (event.action == KeyEvent.ACTION_DOWN) {
                terminalView.onKeyDown(event.keyCode, event)
            }
            return true
        }
        
        override fun deleteSurroundingText(beforeLength: Int, afterLength: Int): Boolean {
            if (beforeLength > 0) {
                terminalView.sendInput("\u007f") // Backspace
            }
            return true
        }
    }
}