package com.ssh.terminal

import android.content.Context
import android.graphics.*
import android.util.AttributeSet
import android.view.KeyEvent
import android.view.View
import android.view.inputmethod.BaseInputConnection
import android.view.inputmethod.EditorInfo
import android.view.inputmethod.InputConnection
import android.view.inputmethod.InputMethodManager

class TerminalView @JvmOverloads constructor(
    context: Context,
    attrs: AttributeSet? = null,
    defStyleAttr: Int = 0
) : View(context, attrs, defStyleAttr) {
    
    private val paint = Paint()
    private val textLines = mutableListOf<String>()
    private var sshConnection: SSHConnection? = null
    
    init {
        paint.color = Color.GREEN
        paint.textSize = 40f
        paint.typeface = Typeface.MONOSPACE
        paint.isAntiAlias = true
        
        isFocusable = true
        isFocusableInTouchMode = true
        setBackgroundColor(Color.BLACK)
    }
    
    fun attachSSH(connection: SSHConnection) {
        sshConnection = connection
        connection.onOutputReceived = { output ->
            appendOutput(output)
        }
    }
    
    private fun appendOutput(output: String) {
        post {
            for (line in output.split('\n')) {
                if (line.isNotEmpty()) {
                    textLines.add(line)
                    if (textLines.size > 50) {
                        textLines.removeAt(0)
                    }
                }
            }
            invalidate()
        }
    }
    
    override fun onDraw(canvas: Canvas) {
        super.onDraw(canvas)
        
        val lineHeight = paint.textSize + 10
        for (i in textLines.indices) {
            canvas.drawText(textLines[i], 20f, (i + 1) * lineHeight, paint)
        }
    }
    
    override fun onKeyDown(keyCode: Int, event: KeyEvent?): Boolean {
        if (event != null && sshConnection != null) {
            when (keyCode) {
                KeyEvent.KEYCODE_ENTER -> {
                    sshConnection!!.sendInput("\r\n")
                    return true
                }
                KeyEvent.KEYCODE_DEL -> {
                    sshConnection!!.sendInput("\b")
                    return true
                }
                else -> {
                    val char = event.unicodeChar
                    if (char != 0) {
                        sshConnection!!.sendInput(char.toChar().toString())
                        return true
                    }
                }
            }
        }
        return super.onKeyDown(keyCode, event)
    }
    
    override fun onCreateInputConnection(outAttrs: EditorInfo?): InputConnection {
        outAttrs?.inputType = EditorInfo.TYPE_CLASS_TEXT
        return object : BaseInputConnection(this, false) {
            override fun commitText(text: CharSequence?, newCursorPosition: Int): Boolean {
                if (text != null && sshConnection != null) {
                    sshConnection!!.sendInput(text.toString())
                }
                return true
            }
        }
    }
    
    fun showKeyboard() {
        requestFocus()
        val imm = context.getSystemService(Context.INPUT_METHOD_SERVICE) as InputMethodManager
        imm.showSoftInput(this, InputMethodManager.SHOW_IMPLICIT)
    }
}
