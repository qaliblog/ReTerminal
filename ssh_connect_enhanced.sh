#!/bin/bash
# Enhanced SSH Connection Script for ReTerminal
# Provides robust SSH connection with comprehensive tunnel fixes

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

# Usage function
usage() {
    echo "Enhanced SSH Connection Script for ReTerminal"
    echo "Usage: $0 [user@]hostname [port] [options]"
    echo ""
    echo "Arguments:"
    echo "  hostname: SSH server hostname or IP"
    echo "  port:     SSH port (default: 22)"
    echo ""
    echo "Options:"
    echo "  --key-auth PATH       Use SSH key authentication"
    echo "  --debug              Enable debug mode"
    echo "  --no-setup           Skip environment setup"
    echo "  --keepalive SECONDS  Set keepalive interval (default: 60)"
    echo ""
    echo "Examples:"
    echo "  $0 user@example.com"
    echo "  $0 192.168.1.100 2222"
    echo "  $0 user@example.com 2222 --debug"
    echo "  $0 user@server.com --key-auth ~/.ssh/id_rsa"
    exit 1
}

# Parse command line arguments
parse_arguments() {
    SSH_HOST=""
    SSH_PORT="22"
    USE_KEY_AUTH=false
    SSH_KEY_PATH=""
    DEBUG_MODE=false
    SKIP_SETUP=false
    KEEPALIVE_INTERVAL=60
    
    while [[ $# -gt 0 ]]; do
        case $1 in
            --key-auth)
                USE_KEY_AUTH=true
                SSH_KEY_PATH="$2"
                shift 2
                ;;
            --debug)
                DEBUG_MODE=true
                shift
                ;;
            --no-setup)
                SKIP_SETUP=true
                shift
                ;;
            --keepalive)
                KEEPALIVE_INTERVAL="$2"
                shift 2
                ;;
            --help|-h)
                usage
                ;;
            *)
                if [ -z "$SSH_HOST" ]; then
                    SSH_HOST="$1"
                elif [ -z "$SSH_PORT" ] || [ "$SSH_PORT" = "22" ]; then
                    SSH_PORT="$1"
                else
                    print_error "Unknown argument: $1"
                    usage
                fi
                shift
                ;;
        esac
    done
    
    if [ -z "$SSH_HOST" ]; then
        print_error "SSH hostname is required"
        usage
    fi
}

# Validate SSH key if key authentication is used
validate_ssh_key() {
    if [ "$USE_KEY_AUTH" = true ]; then
        if [ ! -f "$SSH_KEY_PATH" ]; then
            print_error "SSH key file not found: $SSH_KEY_PATH"
            exit 1
        fi
        
        if [ ! -r "$SSH_KEY_PATH" ]; then
            print_error "SSH key file is not readable: $SSH_KEY_PATH"
            exit 1
        fi
        
        print_status "Using SSH key authentication: $SSH_KEY_PATH"
    fi
}

# Test basic connectivity
test_connectivity() {
    print_status "Testing basic connectivity to $SSH_HOST:$SSH_PORT"
    
    if command -v nc >/dev/null 2>&1; then
        if timeout 10 nc -z "$SSH_HOST" "$SSH_PORT" 2>/dev/null; then
            print_status "✓ Port $SSH_PORT is reachable on $SSH_HOST"
            return 0
        else
            print_warning "✗ Port $SSH_PORT is not reachable on $SSH_HOST"
            return 1
        fi
    elif command -v telnet >/dev/null 2>&1; then
        if timeout 10 bash -c "echo | telnet $SSH_HOST $SSH_PORT" 2>/dev/null | grep -q "Connected"; then
            print_status "✓ Port $SSH_PORT is reachable on $SSH_HOST"
            return 0
        else
            print_warning "✗ Port $SSH_PORT is not reachable on $SSH_HOST"
            return 1
        fi
    else
        print_debug "No connectivity testing tools available (nc, telnet)"
        return 0
    fi
}

# Prepare SSH options for enhanced connection
prepare_ssh_options() {
    SSH_OPTIONS=(
        "-o" "ServerAliveInterval=$KEEPALIVE_INTERVAL"
        "-o" "ServerAliveCountMax=3"
        "-o" "TCPKeepAlive=yes"
        "-o" "ConnectTimeout=15"
        "-o" "StrictHostKeyChecking=no"
        "-o" "UserKnownHostsFile=/dev/null"
        "-o" "Compression=yes"
        "-o" "RequestTTY=force"
        "-p" "$SSH_PORT"
    )
    
    if [ "$USE_KEY_AUTH" = true ]; then
        SSH_OPTIONS+=("-i" "$SSH_KEY_PATH")
        SSH_OPTIONS+=("-o" "PasswordAuthentication=no")
        SSH_OPTIONS+=("-o" "PubkeyAuthentication=yes")
    else
        SSH_OPTIONS+=("-o" "PasswordAuthentication=yes")
        SSH_OPTIONS+=("-o" "PubkeyAuthentication=no")
    fi
    
    if [ "$DEBUG_MODE" = true ]; then
        SSH_OPTIONS+=("-v")
    fi
}

# Test SSH authentication
test_ssh_auth() {
    print_status "Testing SSH authentication..."
    
    # Try a simple command to test authentication
    if timeout 15 ssh "${SSH_OPTIONS[@]}" "$SSH_HOST" "echo 'auth_test_ok'" 2>/dev/null | grep -q "auth_test_ok"; then
        print_status "✓ SSH authentication successful"
        return 0
    else
        print_error "✗ SSH authentication failed"
        return 1
    fi
}

# Transfer and execute environment setup
setup_remote_environment() {
    if [ "$SKIP_SETUP" = true ]; then
        print_debug "Skipping environment setup as requested"
        return 0
    fi
    
    print_status "Setting up remote SSH environment..."
    
    # Check if setup script exists
    if [ ! -f "ssh_environment_setup.sh" ]; then
        print_error "ssh_environment_setup.sh not found in current directory"
        print_status "Please ensure the environment setup script is available"
        return 1
    fi
    
    # Make setup script executable
    chmod +x ssh_environment_setup.sh
    
    # Transfer the setup script
    print_status "Transferring environment setup script..."
    if ! scp "${SSH_OPTIONS[@]}" ssh_environment_setup.sh "$SSH_HOST:/tmp/"; then
        print_error "Failed to transfer setup script"
        return 1
    fi
    
    # Execute the setup script on remote server
    print_status "Executing environment setup on remote server..."
    ssh "${SSH_OPTIONS[@]}" "$SSH_HOST" "chmod +x /tmp/ssh_environment_setup.sh && /tmp/ssh_environment_setup.sh"
    
    return $?
}

# Main SSH connection with enhanced features
connect_ssh() {
    print_status "Establishing enhanced SSH connection to $SSH_HOST:$SSH_PORT"
    
    # Connect with enhanced terminal session
    ssh "${SSH_OPTIONS[@]}" "$SSH_HOST" << 'EOF'
# Enhanced SSH session startup
export TERM=xterm-256color
stty sane echo icanon 2>/dev/null || true

echo ""
echo "🚀 Enhanced SSH Connection Established"
echo "======================================"
echo "Host: $(hostname)"
echo "User: $(whoami)"  
echo "Working Directory: $(pwd)"
echo "Terminal: $TERM"
echo "Time: $(date)"
echo "======================================"
echo ""

# Source any existing configuration
[ -f ~/.bashrc ] && source ~/.bashrc

# Display helpful information
echo "SSH Connection Features:"
echo "• Keepalive: Active (${KEEPALIVE_INTERVAL:-60}s intervals)"
echo "• Terminal: Enhanced with 256-color support"
echo "• Compression: Enabled for better performance"
echo "• Environment: Optimized for ReTerminal"
echo ""
echo "Available commands:"
echo "  ssh-test     - Test connection status"
echo "  ssh-info     - Show connection details"
echo "  fix-terminal - Fix any terminal display issues"
echo ""
echo "Type 'exit' to disconnect, or use Ctrl+D"
echo ""

# Start interactive shell
exec bash --login
EOF
}

# Main function
main() {
    echo ""
    print_status "ReTerminal Enhanced SSH Connection Script"
    echo "=========================================="
    
    # Parse arguments
    parse_arguments "$@"
    
    print_status "Connecting to: $SSH_HOST:$SSH_PORT"
    if [ "$USE_KEY_AUTH" = true ]; then
        print_status "Authentication: SSH Key ($SSH_KEY_PATH)"
    else
        print_status "Authentication: Password"
    fi
    
    # Validate requirements
    validate_ssh_key
    
    # Prepare SSH connection options
    prepare_ssh_options
    
    if [ "$DEBUG_MODE" = true ]; then
        print_debug "SSH Options: ${SSH_OPTIONS[*]}"
    fi
    
    # Test connectivity and authentication
    if ! test_connectivity; then
        print_error "Basic connectivity test failed"
        print_status "Please check:"
        print_status "  1. Network connection"
        print_status "  2. Server hostname/IP: $SSH_HOST"
        print_status "  3. SSH port: $SSH_PORT"
        exit 1
    fi
    
    if ! test_ssh_auth; then
        print_error "SSH authentication test failed"
        print_status "Please check:"
        print_status "  1. Username and password/key"
        print_status "  2. SSH server configuration"
        print_status "  3. Network connectivity"
        exit 1
    fi
    
    # Setup remote environment
    if ! setup_remote_environment; then
        print_warning "Environment setup failed, continuing with basic connection..."
    fi
    
    # Establish main SSH connection
    print_status "Starting interactive SSH session..."
    print_status "Use Ctrl+D or type 'exit' to disconnect"
    echo ""
    
    connect_ssh
    
    print_status "SSH session ended"
}

# Run main function with all arguments
main "$@"