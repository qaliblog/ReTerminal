#!/bin/bash
# Robust SSH Connection Script
# Prevents terminal display issues and provides fallback options

set -e

# Colors
GREEN='\033[0;32m'
YELLOW='\033[1;33m'
RED='\033[0;31m'
NC='\033[0m'

print_status() {
    echo -e "${GREEN}[INFO]${NC} $1"
}

print_warning() {
    echo -e "${YELLOW}[WARNING]${NC} $1"
}

print_error() {
    echo -e "${RED}[ERROR]${NC} $1"
}

# Usage
if [ $# -lt 1 ]; then
    echo "Usage: $0 [user@]hostname [port]"
    echo ""
    echo "This script uses multiple fallback methods to ensure a working SSH connection"
    echo ""
    echo "Examples:"
    echo "  $0 user@server.com"
    echo "  $0 192.168.1.100 2222"
    exit 1
fi

SSH_HOST="$1"
SSH_PORT="${2:-22}"

print_status "Attempting robust SSH connection to $SSH_HOST:$SSH_PORT"

# Method 1: Standard connection with proper terminal allocation
print_status "Method 1: Standard connection with forced TTY..."
if timeout 10 ssh -o ConnectTimeout=10 -tt -p "$SSH_PORT" "$SSH_HOST" 'echo "Connection test successful"; exit'; then
    print_status "Standard connection works. Connecting..."
    ssh -tt -p "$SSH_PORT" "$SSH_HOST"
    exit 0
fi

print_warning "Method 1 failed. Trying Method 2..."

# Method 2: Connection with specific terminal settings
print_status "Method 2: Connection with terminal environment..."
if timeout 10 ssh -o ConnectTimeout=10 -o SendEnv=TERM -o RequestTTY=force -p "$SSH_PORT" "$SSH_HOST" 'echo "Connection test successful"; exit'; then
    print_status "Environment connection works. Connecting..."
    ssh -o SendEnv=TERM -o RequestTTY=force -p "$SSH_PORT" "$SSH_HOST"
    exit 0
fi

print_warning "Method 2 failed. Trying Method 3..."

# Method 3: Connection with comprehensive options
print_status "Method 3: Connection with comprehensive terminal options..."
SSH_OPTS="-o RequestTTY=force -o SendEnv=TERM -o ServerAliveInterval=60 -o ServerAliveCountMax=3 -o TCPKeepAlive=yes"

if timeout 10 ssh -o ConnectTimeout=10 $SSH_OPTS -p "$SSH_PORT" "$SSH_HOST" 'echo "Connection test successful"; exit'; then
    print_status "Comprehensive connection works. Connecting..."
    ssh $SSH_OPTS -p "$SSH_PORT" "$SSH_HOST"
    exit 0
fi

print_warning "Method 3 failed. Trying Method 4..."

# Method 4: Interactive connection with immediate commands
print_status "Method 4: Interactive connection with terminal fix..."
ssh -tt -p "$SSH_PORT" "$SSH_HOST" << 'EOF'
# Immediate terminal fixes
export TERM=xterm-256color
stty echo
stty sane
clear

echo "🔧 Terminal fixed automatically!"
echo ""
echo "SSH Escape Sequences (if terminal becomes unresponsive):"
echo "  ~.  - Disconnect"
echo "  ~^Z - Suspend"
echo "  ~?  - Help"
echo ""
echo "Type 'exit' to disconnect normally"
echo ""

# Start interactive shell
exec bash
EOF

print_error "All connection methods failed!"
print_status "Manual troubleshooting steps:"
echo ""
echo "1. Check if SSH service is running on the server"
echo "2. Verify network connectivity: ping $SSH_HOST"
echo "3. Try different SSH client or terminal"
echo "4. Check firewall settings"
echo "5. Try SSH with verbose output: ssh -v $SSH_HOST"
echo ""
echo "Emergency escape sequences if you get connected but stuck:"
echo "  Press Enter, then ~. (tilde + dot) to force disconnect"