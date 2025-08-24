#!/bin/bash
# SSH Environment Setup Script
# This script sets up a complete development environment for SSH sessions

set -e

echo "🚀 Setting up SSH Development Environment..."

# Colors for output
RED='\033[0;31m'
GREEN='\033[0;32m'
YELLOW='\033[1;33m'
BLUE='\033[0;34m'
NC='\033[0m' # No Color

# Function to print colored output
print_status() {
    echo -e "${GREEN}[INFO]${NC} $1"
}

print_warning() {
    echo -e "${YELLOW}[WARNING]${NC} $1"
}

print_error() {
    echo -e "${RED}[ERROR]${NC} $1"
}

# Check if running as root
if [[ $EUID -eq 0 ]]; then
    print_warning "Running as root. Some configurations may need adjustment."
fi

# Update package lists
print_status "Updating package lists..."
if command -v apt-get &> /dev/null; then
    sudo apt-get update -qq
elif command -v yum &> /dev/null; then
    sudo yum update -y -q
elif command -v pacman &> /dev/null; then
    sudo pacman -Sy --noconfirm
fi

# Install essential packages
print_status "Installing essential packages..."
if command -v apt-get &> /dev/null; then
    sudo apt-get install -y tmux vim neovim ranger nnn htop tree curl wget git zsh fish nodejs npm python3 python3-pip
elif command -v yum &> /dev/null; then
    sudo yum install -y tmux vim neovim ranger nnn htop tree curl wget git zsh fish nodejs npm python3 python3-pip
elif command -v pacman &> /dev/null; then
    sudo pacman -S --noconfirm tmux vim neovim ranger nnn htop tree curl wget git zsh fish nodejs npm python python-pip
fi

# Install additional tools
print_status "Installing additional development tools..."
pip3 install --user rich click typer prompt-toolkit

# Create directories
mkdir -p ~/.config/{tmux,ranger,nvim}
mkdir -p ~/.local/bin

print_status "Setting up Tmux configuration..."

# Create tmux configuration
cat > ~/.tmux.conf << 'EOF'
# Tmux Configuration for SSH Development Environment

# Set prefix to Ctrl-a
set -g prefix C-a
unbind C-b
bind-key C-a send-prefix

# Split panes using | and -
bind | split-window -h
bind - split-window -v
unbind '"'
unbind %

# Switch panes using Alt-arrow without prefix
bind -n M-Left select-pane -L
bind -n M-Right select-pane -R
bind -n M-Up select-pane -U
bind -n M-Down select-pane -D

# Enable mouse mode
set -g mouse on

# Set default terminal mode to 256color mode
set -g default-terminal "screen-256color"

# Enable activity alerts
setw -g monitor-activity on
set -g visual-activity on

# Status bar configuration
set -g status-bg black
set -g status-fg white
set -g status-left '#[fg=green]#H #[fg=white]| '
set -g status-right '#[fg=yellow]%Y-%m-%d %H:%M'

# Window numbering starts at 1
set -g base-index 1
setw -g pane-base-index 1

# Reload config file
bind r source-file ~/.tmux.conf \; display-message "Config reloaded!"

# Increase scrollback buffer size
set -g history-limit 10000
EOF

print_status "Setting up Ranger file manager..."

# Create ranger configuration
cat > ~/.config/ranger/rc.conf << 'EOF'
# Ranger Configuration

# Show hidden files
set show_hidden true

# Enable mouse support
set mouse_enabled true

# Preview files
set preview_files true
set preview_directories true

# Use external image viewer
set use_preview_script true

# Key bindings
map <C-f> console search%space
map <C-h> toggle_option show_hidden
map <DELETE> shell mv %s ~/.local/share/Trash/files/

# Set default editor
set default_editor nvim

# Preview images in terminal
set preview_images true
set preview_images_method kitty
EOF

print_status "Setting up NeoVim configuration..."

# Create basic nvim configuration
cat > ~/.config/nvim/init.vim << 'EOF'
" NeoVim Configuration for SSH Environment

" Basic settings
set number
set relativenumber
set expandtab
set tabstop=4
set shiftwidth=4
set smartindent
set wrap
set linebreak
set mouse=a

" Search settings
set ignorecase
set smartcase
set hlsearch
set incsearch

" Color scheme
colorscheme desert
set termguicolors

" File explorer
let g:netrw_banner = 0
let g:netrw_liststyle = 3
let g:netrw_browse_split = 4
let g:netrw_altv = 1
let g:netrw_winsize = 25

" Key mappings
nnoremap <C-n> :Lexplore<CR>
nnoremap <C-s> :w<CR>
nnoremap <C-q> :q<CR>

" Split navigation
nnoremap <C-h> <C-w>h
nnoremap <C-j> <C-w>j
nnoremap <C-k> <C-w>k
nnoremap <C-l> <C-w>l

" Terminal mode
tnoremap <Esc> <C-\><C-n>
EOF

print_status "Setting up chat and communication tools..."

# Create a simple chat script using netcat and tmux
cat > ~/.local/bin/ssh-chat << 'EOF'
#!/bin/bash
# Simple SSH Chat Tool

CHAT_PORT=${1:-9999}
CHAT_HOST=${2:-localhost}

if [ "$3" = "server" ]; then
    echo "Starting chat server on port $CHAT_PORT..."
    while true; do
        nc -l -p $CHAT_PORT
    done
else
    echo "Connecting to chat server at $CHAT_HOST:$CHAT_PORT..."
    nc $CHAT_HOST $CHAT_PORT
fi
EOF

chmod +x ~/.local/bin/ssh-chat

# Create SSH environment startup script
cat > ~/.local/bin/ssh-env << 'EOF'
#!/bin/bash
# SSH Environment Launcher

echo "🚀 Starting SSH Development Environment..."

# Start tmux session if not already in one
if [ -z "$TMUX" ]; then
    # Create new tmux session with multiple panes
    tmux new-session -d -s ssh-dev
    
    # Split into 4 panes
    tmux split-window -h -t ssh-dev
    tmux split-window -v -t ssh-dev:0.0
    tmux split-window -v -t ssh-dev:0.1
    
    # Setup panes
    tmux send-keys -t ssh-dev:0.0 'echo "📁 File Manager (Ranger: r, NNN: nnn)"' C-m
    tmux send-keys -t ssh-dev:0.1 'echo "💬 Chat/Communication"' C-m
    tmux send-keys -t ssh-dev:0.2 'echo "📝 Editor (nvim filename)"' C-m
    tmux send-keys -t ssh-dev:0.3 'echo "🖥️  Main Shell"' C-m
    
    # Focus on main shell pane
    tmux select-pane -t ssh-dev:0.3
    
    # Attach to session
    tmux attach-session -t ssh-dev
else
    echo "Already in tmux session"
fi
EOF

chmod +x ~/.local/bin/ssh-env

print_status "Creating quick access commands..."

# Create quick launcher scripts
cat > ~/.local/bin/fm << 'EOF'
#!/bin/bash
# Quick File Manager Launcher
if command -v ranger &> /dev/null; then
    ranger
elif command -v nnn &> /dev/null; then
    nnn
else
    echo "Installing file manager..."
    sudo apt-get install -y ranger || sudo yum install -y ranger || sudo pacman -S ranger
    ranger
fi
EOF

cat > ~/.local/bin/edit << 'EOF'
#!/bin/bash
# Quick Editor Launcher
if [ -z "$1" ]; then
    echo "Usage: edit <filename>"
    exit 1
fi

if command -v nvim &> /dev/null; then
    nvim "$1"
elif command -v vim &> /dev/null; then
    vim "$1"
else
    nano "$1"
fi
EOF

chmod +x ~/.local/bin/fm ~/.local/bin/edit

print_status "Setting up shell enhancements..."

# Add to bashrc
if ! grep -q "SSH Environment" ~/.bashrc; then
    cat >> ~/.bashrc << 'EOF'

# SSH Environment Setup
export PATH="$HOME/.local/bin:$PATH"

# Aliases for quick access
alias fm='~/.local/bin/fm'
alias e='~/.local/bin/edit'
alias chat='~/.local/bin/ssh-chat'
alias env='~/.local/bin/ssh-env'

# Enhanced prompt
export PS1='\[\033[01;32m\]\u@\h\[\033[00m\]:\[\033[01;34m\]\w\[\033[00m\]\$ '

# Auto-start SSH environment on login
if [ -z "$TMUX" ] && [ "$SSH_CONNECTION" != "" ]; then
    echo "Type 'env' to start the integrated development environment"
fi
EOF
fi

print_status "Setting up terminal fixes..."

# Create terminal fix script
cat > ~/.local/bin/fix-terminal << 'EOF'
#!/bin/bash
# Fix common SSH terminal issues

echo "Fixing terminal settings..."

# Reset terminal
reset

# Fix echo and input
stty echo
stty icanon
stty -raw

# Set proper terminal type
export TERM=xterm-256color

# Clear screen
clear

echo "Terminal fixed! Type 'env' to start development environment."
EOF

chmod +x ~/.local/bin/fix-terminal

print_status "✅ SSH Environment Setup Complete!"

echo ""
echo "🎉 Your SSH development environment is ready!"
echo ""
echo "Quick commands:"
echo "  env     - Start integrated development environment"
echo "  fm      - Launch file manager"
echo "  e <file> - Edit file"
echo "  chat    - Start chat session"
echo "  fix-terminal - Fix terminal issues"
echo ""
echo "To get started, run: source ~/.bashrc && env"
echo ""