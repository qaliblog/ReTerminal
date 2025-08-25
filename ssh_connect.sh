#!/bin/bash
# SSH Connection Script with Automatic Environment Setup

set -e

# Colors for output
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

# Usage function
usage() {
    echo "Usage: $0 [user@]hostname [port]"
    echo "  hostname: SSH server hostname or IP"
    echo "  port:     SSH port (default: 22)"
    echo ""
    echo "Examples:"
    echo "  $0 user@example.com"
    echo "  $0 192.168.1.100 2222"
    echo "  $0 user@example.com 2222"
    exit 1
}

# Check arguments
if [ $# -lt 1 ]; then
    usage
fi

SSH_HOST="$1"
SSH_PORT="${2:-22}"

print_status "Setting up SSH connection to $SSH_HOST:$SSH_PORT"

# Check if setup script exists
if [ ! -f "ssh_environment_setup.sh" ]; then
    print_error "ssh_environment_setup.sh not found in current directory"
    exit 1
fi

# Make setup script executable
chmod +x ssh_environment_setup.sh

print_status "Transferring environment setup script..."

# Transfer the setup script to remote server
scp -P "$SSH_PORT" ssh_environment_setup.sh "$SSH_HOST:/tmp/"

if [ $? -ne 0 ]; then
    print_error "Failed to transfer setup script"
    exit 1
fi

print_status "Connecting to SSH server and running setup..."

# Connect to SSH and run setup, then start interactive session
ssh -tt -p "$SSH_PORT" "$SSH_HOST" << 'EOF'
# Fix terminal issues first
export TERM=xterm-256color
stty sane
stty cooked echo icrnl onlcr -ixon -ixoff iutf8
reset

echo "🚀 Running SSH environment setup..."

# Run the setup script
chmod +x /tmp/ssh_environment_setup.sh
/tmp/ssh_environment_setup.sh

# Source the new bashrc
source ~/.bashrc

# Show welcome message
echo ""
echo "🎉 SSH Environment Ready!"
echo ""
echo "Available commands:"
echo "  env         - Start integrated development environment"
echo "  fm          - Launch file manager (Ranger)"
echo "  e <file>    - Edit file with NeoVim"
echo "  chat        - Start chat session"
echo "  fix-terminal - Fix terminal display issues"
echo ""
echo "Quick start: Type 'env' to launch the integrated environment"
echo ""

# Start a new shell with the updated configuration
exec bash
EOF

print_status "SSH session ended"