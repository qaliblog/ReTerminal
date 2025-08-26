#!/bin/bash
# SSH Environment Setup Script for ReTerminal
# Fixes terminal display issues and sets up optimal SSH environment

set -e

# Colors for output
GREEN='\033[0;32m'
YELLOW='\033[1;33m'
RED='\033[0;31m'
BLUE='\033[0;34m'
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

print_debug() {
    echo -e "${BLUE}[DEBUG]${NC} $1"
}

# Fix terminal immediately on script start
fix_terminal_immediately() {
    print_status "Applying immediate terminal fixes..."
    
    # Fix basic terminal settings
    stty sane 2>/dev/null || true
    stty echo 2>/dev/null || true
    stty icanon 2>/dev/null || true
    
    # Set proper terminal type
    export TERM=xterm-256color
    
    # Fix common terminal issues
    stty cooked echo icrnl onlcr -ixon -ixoff iutf8 2>/dev/null || true
    stty erase ^H kill ^U intr ^C 2>/dev/null || true
    
    # Reset terminal to clear any corruption
    reset 2>/dev/null || clear
}

# Enhanced terminal configuration
setup_enhanced_terminal() {
    print_status "Setting up enhanced terminal configuration..."
    
    # Create enhanced bashrc configuration
    cat >> ~/.bashrc << 'EOF'

# === ReTerminal SSH Environment Configuration ===

# Terminal settings
export TERM=xterm-256color
export COLORTERM=truecolor
export LANG=en_US.UTF-8
export LC_ALL=en_US.UTF-8

# Enhanced prompt with color and status
if [ "$EUID" -eq 0 ]; then
    export PS1='\[\033[01;31m\]\u@\h\[\033[00m\]:\[\033[01;34m\]\w\[\033[00m\]\# '
else
    export PS1='\[\033[01;32m\]\u@\h\[\033[00m\]:\[\033[01;34m\]\w\[\033[00m\]\$ '
fi

# History settings
export HISTSIZE=10000
export HISTFILESIZE=20000
export HISTCONTROL=ignoredups:ignorespace
shopt -s histappend

# Terminal behavior improvements
set -o vi 2>/dev/null || true
bind 'set completion-ignore-case on' 2>/dev/null || true
bind 'set show-all-if-ambiguous on' 2>/dev/null || true

# Aliases for better experience
alias ll='ls -alF'
alias la='ls -A'
alias l='ls -CF'
alias grep='grep --color=auto'
alias fgrep='fgrep --color=auto'
alias egrep='egrep --color=auto'

# Terminal fix function
fix-terminal() {
    echo "Fixing terminal settings..."
    stty sane
    stty echo icanon
    export TERM=xterm-256color
    reset
    echo "Terminal fixed!"
}

# SSH session helpers
ssh-test() {
    echo "SSH Connection Test:"
    echo "==================="
    echo "Current user: $(whoami)"
    echo "Home directory: $HOME"
    echo "Working directory: $(pwd)"
    echo "Terminal type: $TERM"
    echo "SSH Client: ${SSH_CLIENT:-Not set}"
    echo "SSH TTY: ${SSH_TTY:-Not set}"
    echo "Terminal size: $(tput cols)x$(tput lines)"
    echo "==================="
}

ssh-info() {
    echo "SSH Environment Information:"
    echo "============================"
    echo "Session ID: $$"
    echo "Parent PID: $PPID"
    echo "Shell: $SHELL"
    echo "PATH: $PATH"
    echo "============================"
}

# Fix any immediate terminal issues
stty sane 2>/dev/null || true
stty echo icanon 2>/dev/null || true
EOF

    print_status "Enhanced bashrc configuration added"
}

# Setup working directory and permissions
setup_working_environment() {
    print_status "Setting up working environment..."
    
    # Ensure proper working directory
    if [ -d "/data/data/com.termux/files/home" ]; then
        cd "/data/data/com.termux/files/home"
        print_status "Set working directory to Termux home"
    elif [ -d "$HOME" ]; then
        cd "$HOME"
        print_status "Set working directory to user home"
    else
        print_warning "Could not determine proper home directory"
    fi
    
    # Create common directories
    mkdir -p ~/bin ~/tmp ~/Downloads 2>/dev/null || true
    
    # Set proper permissions
    chmod 755 ~ ~/bin ~/tmp ~/Downloads 2>/dev/null || true
}

# Package management setup for Termux
setup_termux_environment() {
    if [ -d "/data/data/com.termux" ]; then
        print_status "Detected Termux environment, applying Termux-specific fixes..."
        
        # Termux-specific environment
        export PREFIX="/data/data/com.termux/files/usr"
        export TMPDIR="/data/data/com.termux/files/usr/tmp"
        
        # Add to PATH if not already there
        if [[ ":$PATH:" != *":$PREFIX/bin:"* ]]; then
            export PATH="$PREFIX/bin:$PATH"
        fi
        
        print_status "Termux environment configured"
    fi
}

# Network and connectivity check
check_connectivity() {
    print_status "Checking network connectivity..."
    
    if command -v ping >/dev/null 2>&1; then
        if ping -c 1 8.8.8.8 >/dev/null 2>&1; then
            print_status "Network connectivity: OK"
        else
            print_warning "Network connectivity: Limited"
        fi
    else
        print_debug "Ping command not available"
    fi
}

# Main setup function
main() {
    echo ""
    print_status "Starting SSH Environment Setup for ReTerminal"
    echo "=============================================="
    
    # Apply fixes immediately
    fix_terminal_immediately
    
    # Setup enhanced environment
    setup_enhanced_terminal
    setup_working_environment
    setup_termux_environment
    check_connectivity
    
    # Final terminal configuration
    export TERM=xterm-256color
    stty sane echo icanon 2>/dev/null || true
    
    print_status "SSH environment setup completed successfully!"
    echo ""
    print_status "Available commands:"
    echo "  ssh-test    - Test SSH connection"
    echo "  ssh-info    - Show connection details" 
    echo "  fix-terminal - Fix terminal display issues"
    echo ""
    print_status "You can now use your terminal normally."
    print_status "Type 'ssh-test' to verify the connection is working."
    echo ""
}

# Run main setup
main

# Source the new configuration
source ~/.bashrc 2>/dev/null || true

# Final verification
ssh-test