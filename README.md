# SSH Development Environment Setup

This repository provides a complete SSH development environment with integrated shell, file manager, editor, and communication tools.

## 🚀 Quick Start

1. **Make scripts executable:**
   ```bash
   chmod +x ssh_environment_setup.sh ssh_connect.sh
   ```

2. **Connect to your SSH server:**
   ```bash
   ./ssh_connect.sh user@your-server.com
   # or with custom port
   ./ssh_connect.sh user@your-server.com 2222
   ```

3. **Start the integrated environment:**
   ```bash
   env
   ```

## 🛠️ What's Included

### 🖥️ Terminal Session Manager (Tmux)
- **4-pane layout** with dedicated spaces for:
  - File Manager
  - Chat/Communication
  - Editor
  - Main Shell
- **Mouse support** enabled
- **Custom key bindings**:
  - `Ctrl-a` as prefix
  - `|` for horizontal split
  - `-` for vertical split
  - `Alt + arrows` for pane navigation

### 📁 File Manager (Ranger)
- **Visual file browser** with preview
- **Mouse support**
- **Hidden files** toggle (`Ctrl-h`)
- **Search functionality** (`Ctrl-f`)
- **Image preview** support

### 📝 Editor (NeoVim)
- **Syntax highlighting**
- **Line numbers** and relative numbers
- **Mouse support**
- **Split navigation** with `Ctrl + hjkl`
- **File explorer** with `Ctrl-n`
- **Built-in terminal** support

### 💬 Chat/Communication
- **Simple chat server** using netcat
- **Multi-user support**
- **Custom port configuration**

## 🎯 Quick Commands

After setup, use these commands in your SSH session:

| Command | Description |
|---------|-------------|
| `env` | Start integrated development environment |
| `fm` | Launch file manager (Ranger) |
| `e <file>` | Edit file with NeoVim |
| `chat` | Start chat session |
| `fix-terminal` | Fix terminal display issues |

## 🔧 Manual Setup

If you prefer to run the setup manually:

```bash
# Transfer the setup script
scp ssh_environment_setup.sh user@server:/tmp/

# SSH into your server
ssh user@server

# Run the setup
chmod +x /tmp/ssh_environment_setup.sh
/tmp/ssh_environment_setup.sh

# Reload your shell configuration
source ~/.bashrc
```

## 🎮 Using the Environment

### Starting the Integrated Environment
```bash
env
```

This creates a tmux session with 4 panes:
- **Top-left**: File Manager area
- **Top-right**: Chat/Communication area  
- **Bottom-left**: Editor area
- **Bottom-right**: Main shell

### File Management
```bash
# Quick file manager
fm

# Navigate with arrow keys, Enter to open
# Press 'q' to quit
```

### Editing Files
```bash
# Edit a file
e myfile.txt

# Or use nvim directly
nvim myfile.txt
```

### Chat/Communication
```bash
# Start a chat server (on port 9999)
chat 9999 localhost server

# Connect to chat server
chat 9999 server-ip
```

## 🔑 Tmux Key Bindings

| Key Combination | Action |
|----------------|--------|
| `Ctrl-a` | Prefix key |
| `Ctrl-a \|` | Split horizontally |
| `Ctrl-a -` | Split vertically |
| `Alt + arrows` | Navigate panes |
| `Ctrl-a r` | Reload config |

## 🛠️ Customization

### Tmux Configuration
Edit `~/.tmux.conf` to customize your tmux setup.

### NeoVim Configuration  
Edit `~/.config/nvim/init.vim` to customize your editor.

### Ranger Configuration
Edit `~/.config/ranger/rc.conf` to customize your file manager.

## 🚨 Troubleshooting

### Terminal Display Issues
```bash
fix-terminal
```

### Characters Not Appearing
```bash
stty echo
reset
export TERM=xterm-256color
```

### Tmux Session Issues
```bash
# Kill existing sessions
tmux kill-server

# Start fresh
env
```

### Package Installation Issues
The setup script supports multiple package managers:
- **Debian/Ubuntu**: `apt-get`
- **RHEL/CentOS**: `yum`
- **Arch Linux**: `pacman`

## 📋 Requirements

### Local Machine
- `ssh` client
- `scp` for file transfer

### Remote Server
- **sudo access** (for package installation)
- **Internet connection** (for downloading packages)
- **Modern terminal** support

## 🎨 Environment Features

- **Color-coded output** for better visibility
- **Enhanced prompt** with user/host/path
- **Mouse support** across all tools
- **Persistent sessions** with tmux
- **Quick access commands** and aliases
- **Automatic terminal fixes** for SSH issues

## 📝 Notes

- The setup script automatically detects your package manager
- All configurations are stored in standard locations
- The environment is designed to work over SSH with minimal bandwidth
- Sessions persist even if SSH connection drops (tmux)

## 🤝 Contributing

Feel free to customize the scripts for your specific needs. The modular design makes it easy to add or remove components.

---

**Enjoy your enhanced SSH development environment!** 🎉

