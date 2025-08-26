#!/bin/bash
# Emergency SSH Terminal Fix Script
# Run this when you can't type in an SSH session

echo "🚨 EMERGENCY SSH TERMINAL FIX"
echo "============================="

# Step 1: Fix terminal settings immediately
echo "Step 1: Fixing terminal settings..."
stty sane 2>/dev/null || echo "stty sane failed"
stty echo 2>/dev/null || echo "stty echo failed"  
stty icanon 2>/dev/null || echo "stty icanon failed"

# Step 2: Set proper terminal type
echo "Step 2: Setting terminal type..."
export TERM=xterm-256color

# Step 3: Apply comprehensive terminal fixes
echo "Step 3: Applying comprehensive terminal fixes..."
stty cooked echo icrnl onlcr -ixon -ixoff iutf8 2>/dev/null || echo "Advanced stty failed"
stty erase '^H' kill '^U' intr '^C' 2>/dev/null || echo "Control chars failed"

# Step 4: Reset terminal
echo "Step 4: Resetting terminal..."
reset 2>/dev/null || clear

# Step 5: Test input capability
echo ""
echo "✅ EMERGENCY FIX COMPLETE!"
echo ""
echo "Testing input capability:"
echo "If you can see this, try typing now..."
echo ""
echo "Commands to try:"
echo "  whoami"
echo "  pwd" 
echo "  ls"
echo ""
echo "If input still doesn't work, try:"
echo "  1. Press Ctrl+C to interrupt"
echo "  2. Type 'exit' and press Enter"
echo "  3. Reconnect using the enhanced script"
echo ""

# Step 6: Show current status
echo "Current terminal status:"
echo "TERM=$TERM"
echo "TTY=$(tty 2>/dev/null || echo 'unknown')"
echo "Shell: $SHELL"
echo ""

# Step 7: Manual test
echo "Manual input test - type 'test' and press Enter:"
read -r test_input 2>/dev/null || echo "Read failed"
if [ -n "$test_input" ]; then
    echo "✅ Input working! You typed: $test_input"
else
    echo "❌ Input still not working"
    echo ""
    echo "EMERGENCY ESCAPE SEQUENCES:"
    echo "  ~.  - Force disconnect SSH"
    echo "  Ctrl+Z - Suspend session"
    echo "  Ctrl+C - Interrupt current command"
fi