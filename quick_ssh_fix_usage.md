# IMMEDIATE SSH FIX USAGE

## Quick Solution for "Can't Type" Issue

Replace your SSH session creation with the improved version:

```kotlin
// OLD (broken input)
val sshTerminal = SshTerminalSession(sshSessionId, sessionClient)

// NEW (fixed input) 
val sshTerminal = ImprovedSSHTerminalSession(sshSessionId, sessionClient)
```

## In your MkSession.kt or similar file:

1. Import the improved session:
```kotlin
import com.rk.terminal.ssh.ImprovedSSHTerminalSession
```

2. Replace the session creation:
```kotlin
// Find this line in your code:
val sshTerminal = SshTerminalSession(sshSessionId, sessionClient)

// Replace with:
val sshTerminal = ImprovedSSHTerminalSession(sshSessionId, sessionClient)
```

## Benefits of the Quick Fix:

✅ **Fixed input queuing** - no more lost keystrokes
✅ **Better I/O handling** - separate threads for input/output  
✅ **Improved initialization** - proper terminal setup sequence
✅ **Better error handling** - more detailed logging
✅ **Queue-based input** - prevents input overwhelm

## Testing the Fix:

1. Apply the build configuration fix
2. Replace SSH session class  
3. Build and test
4. SSH input should work properly

If this quick fix resolves your issue, you can continue using it while
the full native implementation is being prepared for production.
