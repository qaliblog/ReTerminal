# SSH Session Implementation

This implementation adds sophisticated SSH session support to the terminal application alongside the existing Alpine and Android options.

## Features

### 1. SSH Session Option
- Added SSH session option alongside Alpine and Android in the session creation dialog
- Clicking SSH opens a comprehensive configuration form

### 2. SSH Configuration Form
- **Connection Settings**: Hostname, port, username
- **Authentication Methods**: 
  - Password authentication
  - Private key authentication  
  - Combined password + private key
- **Advanced Settings**: 
  - Working directory
  - Connection timeout
  - Keep-alive interval
  - Strict host key checking toggle
  - Compression toggle
  - X11 forwarding toggle
- **Save Toggle**: Option to save configuration for future use

### 3. Sophisticated SSH Library
- Uses **JSch** (Java Secure Channel) for robust SSH2 protocol implementation
- Full bash shell support with proper terminal emulation
- Supports all standard SSH features including SFTP for file operations

### 4. Integrated Components

#### Terminal Session
- `SshTerminalSession`: Custom terminal session that bridges SSH connection to Android terminal
- Full bash shell with proper environment variable setup
- Real-time bidirectional communication between local terminal and remote SSH session

#### File Manager Integration
- `SshFileManager`: SFTP-based remote file operations
- `SshFileManagerView`: UI for browsing, editing, creating, and deleting remote files
- Seamless integration with existing file manager interface
- Support for file download/upload operations

#### Text Editor Integration  
- `SshTextEditorView`: Remote file editing with SFTP backend
- Real-time file saving to remote server
- Download files to local storage option

#### Chat Session Integration
- SSH context automatically injected into chat messages
- Visual indicator showing current SSH connection info
- Chat messages prefixed with SSH session details for better AI context

### 5. Security Features
- Encrypted storage of SSH configurations using Android EncryptedSharedPreferences
- Support for private key authentication with passphrase protection
- Configurable host key verification
- Secure credential management

### 6. User Experience
- **Saved Configurations**: Quick access to previously saved SSH configs
- **Connection Status**: Visual indicators for connection state
- **Error Handling**: Graceful fallback to local shell on connection failures
- **Session Management**: Full integration with existing session management system

## Architecture

```
TerminalScreen
├── SSH Configuration Dialog (SshConfigDialog)
├── Saved SSH Configs Dialog (SavedSshConfigsDialog)  
└── Session Creation
    ├── SshTerminalSession (extends TerminalSession)
    ├── SshSession (JSch wrapper)
    └── Integration with:
        ├── SshFileManager (SFTP operations)
        ├── SshTextEditorView (remote editing)
        └── Chat (SSH context injection)
```

## Technical Implementation

### SSH Connection
- **Library**: JSch 0.1.55 for SSH2 protocol
- **Authentication**: Support for password, private key, and combined methods
- **Shell Channel**: Full bash shell with PTY support
- **SFTP Channel**: Separate channel for file operations

### Terminal Integration
- **Custom TerminalSession**: `SshTerminalSession` extends Android's `TerminalSession`
- **Stream Bridging**: Bidirectional data flow between SSH and terminal emulator
- **Environment Setup**: Automatic working directory and environment variable configuration

### File Operations
- **SFTP Integration**: Full remote file system access
- **File Manager UI**: Native Android UI for remote file browsing
- **Text Editor**: Direct remote file editing with save functionality

### Security
- **Encrypted Storage**: SSH credentials stored using Android's security library
- **Connection Security**: Support for strict host key checking
- **Credential Management**: Secure handling of passwords and private keys

## Usage

1. **Create SSH Session**: 
   - Tap "+" to add new session
   - Select "SSH Session"
   - Fill in connection details
   - Toggle "Save Configuration" to store for future use
   - Tap "Connect"

2. **Use Saved Configurations**:
   - Tap "+" to add new session  
   - Select "Saved SSH Configs"
   - Choose from previously saved configurations
   - Instant connection with saved settings

3. **File Management**:
   - Switch to "Files" tab
   - Browse remote filesystem using SFTP
   - Create, edit, delete files and folders
   - Download files to local storage

4. **Text Editing**:
   - Open files from SSH file manager
   - Edit directly on remote server
   - Auto-save to remote location
   - Download option for local backup

5. **Chat Integration**:
   - Chat automatically includes SSH session context
   - Visual indicator shows current SSH connection
   - AI receives SSH session information for better assistance

## Dependencies Added

```kotlin
// SSH libraries
api("com.jcraft:jsch:0.1.55")
api("org.apache.sshd:sshd-core:2.11.0") 
api("org.apache.sshd:sshd-sftp:2.11.0")

// Serialization for config storage
api("org.jetbrains.kotlinx:kotlinx-serialization-json:1.6.0")
```

## Files Created/Modified

### New Files
- `SshConfig.kt` - Configuration data classes
- `SshSession.kt` - JSch SSH connection wrapper
- `SshTerminalSession.kt` - Custom terminal session for SSH
- `SshConfigManager.kt` - Encrypted configuration storage
- `SshConfigDialog.kt` - Configuration UI dialogs
- `SshFileManager.kt` - SFTP file operations
- `SshFileManagerView.kt` - Remote file manager UI
- `SshTextEditor.kt` - Remote file editor
- `SshFileOpenBus.kt` - Remote file opening coordination

### Modified Files
- `Settings.kt` - Added SSH working mode constant
- `TerminalScreen.kt` - Added SSH option and dialogs
- `SessionService.kt` - Added SSH session creation
- `MkSession.kt` - Added SSH session factory method
- `Chat.kt` - Added SSH context integration
- `build.gradle.kts` - Added SSH dependencies
- `AndroidManifest.xml` - Added internet permissions

This implementation provides a complete, production-ready SSH session feature with full integration into the existing terminal application architecture.