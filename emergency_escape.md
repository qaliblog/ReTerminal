# EMERGENCY SSH ESCAPE GUIDE

If you get stuck in an SSH session where you can't type:

## Method 1: SSH Escape Sequence
1. Press **Enter**
2. Type **~.** (tilde followed by dot)
3. This will force disconnect the SSH session

## Method 2: Control Characters  
- **Ctrl+C** - Interrupt current command
- **Ctrl+D** - Exit cleanly  
- **Ctrl+Z** - Suspend session

## Method 3: App Reset
1. Force close ReTerminal app
2. Restart the app
3. Try connecting again

## Method 4: Use ADB
If you have ADB access:
```bash
adb shell am force-stop com.rk.terminal
adb shell am start com.rk.terminal/.MainActivity
```

## Prevention
- Use the ImprovedSSHTerminalSession class
- Always test with `stty sane` first
- Use the enhanced SSH connection scripts
