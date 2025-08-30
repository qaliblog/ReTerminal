package com.rk.terminal.ssh

import android.util.Log
import com.termux.terminal.TerminalSession
import java.lang.reflect.InvocationHandler
import java.lang.reflect.Method
import java.lang.reflect.Proxy

object SshSessionProxy {
    private const val TAG = "SshSessionProxy"
    
    fun createProxy(originalSession: TerminalSession, sshTerminal: SimpleSshTerminal): TerminalSession {
        return try {
            // Create a dynamic proxy that intercepts write calls
            val handler = SshSessionInvocationHandler(originalSession, sshTerminal)
            
            Proxy.newProxyInstance(
                originalSession.javaClass.classLoader,
                arrayOf(TerminalSession::class.java),
                handler
            ) as TerminalSession
            
        } catch (e: Exception) {
            Log.w(TAG, "Could not create SSH session proxy: ${e.message}")
            // Return original session if proxy creation fails
            originalSession
        }
    }
    
    private class SshSessionInvocationHandler(
        private val originalSession: TerminalSession,
        private val sshTerminal: SimpleSshTerminal
    ) : InvocationHandler {
        
        override fun invoke(proxy: Any?, method: Method?, args: Array<out Any>?): Any? {
            return try {
                when (method?.name) {
                    "write" -> {
                        // Intercept write calls and redirect to SSH
                        if (sshTerminal.isConnected()) {
                            when {
                                args?.size == 1 && args[0] is String -> {
                                    val text = args[0] as String
                                    Log.d(TAG, "Intercepting write(String): $text")
                                    sshTerminal.writeToSsh(text)
                                    return null
                                }
                                args?.size == 3 && args[0] is ByteArray -> {
                                    val data = args[0] as ByteArray
                                    val offset = args[1] as Int
                                    val count = args[2] as Int
                                    Log.d(TAG, "Intercepting write(ByteArray): ${String(data, offset, count)}")
                                    sshTerminal.writeToSsh(data, offset, count)
                                    return null
                                }
                            }
                        }
                        // Fallback to original method
                        method.invoke(originalSession, *args.orEmpty())
                    }
                    else -> {
                        // For all other methods, delegate to original session
                        method.invoke(originalSession, *args.orEmpty())
                    }
                }
            } catch (e: Exception) {
                Log.e(TAG, "Error in SSH session proxy: ${e.message}")
                // Fallback to original method
                try {
                    method?.invoke(originalSession, *args.orEmpty())
                } catch (fallbackError: Exception) {
                    Log.e(TAG, "Error in fallback method call", fallbackError)
                    null
                }
            }
        }
    }
}