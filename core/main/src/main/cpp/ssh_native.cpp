#include "include/ssh_native.h"
#include "include/ssh_session.h"
#include "include/ssh_channel.h"
#include "include/pty_handler.h"
#include <chrono>
#include <algorithm>

namespace ssh_native {

    SSHNativeController::SSHNativeController() 
        : io_buffer_(std::make_unique<IOBuffer>()) {
        LOGI("SSHNativeController created");
        
        // Initialize libssh2
        int rc = libssh2_init(0);
        if (rc != 0) {
            LOGE("Failed to initialize libssh2: %d", rc);
            setError("Failed to initialize SSH library");
        }
    }

    SSHNativeController::~SSHNativeController() {
        LOGI("SSHNativeController destructor");
        disconnect();
        stopThreads();
        libssh2_exit();
    }

    bool SSHNativeController::connect(const ConnectionParams& params) {
        LOGI("Connecting to %s:%d as %s", params.hostname.c_str(), params.port, params.username.c_str());
        
        if (state_ != ConnectionState::DISCONNECTED) {
            LOGE("Already connected or connecting");
            return false;
        }
        
        state_ = ConnectionState::CONNECTING;
        
        try {
            // Create SSH session
            session_ = std::make_unique<SSHSession>();
            if (!session_->connect(params)) {
                setError("Failed to establish SSH connection");
                state_ = ConnectionState::ERROR;
                return false;
            }
            
            state_ = ConnectionState::AUTHENTICATING;
            
            // Authenticate
            if (!session_->authenticate(params)) {
                setError("SSH authentication failed");
                state_ = ConnectionState::ERROR;
                return false;
            }
            
            state_ = ConnectionState::CONNECTED;
            LOGI("SSH connection established successfully");
            
            // Start background threads
            startKeepAliveThread();
            
            return true;
            
        } catch (const std::exception& e) {
            LOGE("Connection failed: %s", e.what());
            setError(std::string("Connection error: ") + e.what());
            state_ = ConnectionState::ERROR;
            return false;
        }
    }

    void SSHNativeController::disconnect() {
        LOGI("Disconnecting SSH session");
        
        should_stop_ = true;
        state_ = ConnectionState::DISCONNECTED;
        
        // Stop threads first
        stopThreads();
        
        // Destroy shell channel
        destroyShell();
        
        // Disconnect session
        if (session_) {
            session_->disconnect();
            session_.reset();
        }
        
        LOGI("SSH session disconnected");
    }

    bool SSHNativeController::isConnected() const {
        return state_ == ConnectionState::CONNECTED && 
               session_ && session_->isConnected();
    }

    ConnectionState SSHNativeController::getState() const {
        return state_;
    }

    std::string SSHNativeController::getLastError() const {
        std::lock_guard<std::mutex> lock(error_mutex_);
        return last_error_;
    }

    bool SSHNativeController::createShell(const TerminalConfig& config) {
        LOGI("Creating shell with terminal %dx%d", config.cols, config.rows);
        
        if (!isConnected()) {
            setError("Not connected to SSH server");
            return false;
        }
        
        try {
            // Create shell channel
            shell_channel_ = std::make_unique<SSHChannel>(session_.get());
            if (!shell_channel_->createShell(config)) {
                setError("Failed to create SSH shell");
                return false;
            }
            
            // Create PTY handler
            pty_handler_ = std::make_unique<PTYHandler>(shell_channel_.get(), config);
            
            // Start output thread
            startOutputThread();
            
            LOGI("SSH shell created successfully");
            return true;
            
        } catch (const std::exception& e) {
            LOGE("Failed to create shell: %s", e.what());
            setError(std::string("Shell creation error: ") + e.what());
            return false;
        }
    }

    void SSHNativeController::destroyShell() {
        LOGI("Destroying SSH shell");
        
        // Stop output thread
        should_stop_ = true;
        if (output_thread_.joinable()) {
            output_thread_.join();
        }
        
        // Destroy components
        pty_handler_.reset();
        shell_channel_.reset();
        
        // Clear buffers
        if (io_buffer_) {
            std::lock_guard<std::mutex> input_lock(io_buffer_->input_mutex);
            std::lock_guard<std::mutex> output_lock(io_buffer_->output_mutex);
            
            while (!io_buffer_->input_queue.empty()) {
                io_buffer_->input_queue.pop();
            }
            while (!io_buffer_->output_queue.empty()) {
                io_buffer_->output_queue.pop();
            }
            
            io_buffer_->has_input = false;
            io_buffer_->has_output = false;
        }
        
        should_stop_ = false; // Reset for next shell creation
    }

    bool SSHNativeController::sendInput(const char* data, size_t length) {
        if (!shell_channel_ || !shell_channel_->isReady()) {
            LOGE("Shell channel not ready for input");
            return false;
        }
        
        LOGD("Sending input: %zu bytes", length);
        
        // Send directly to shell channel
        return shell_channel_->writeData(data, length);
    }

    bool SSHNativeController::hasOutput() {
        return io_buffer_ && io_buffer_->has_output.load();
    }

    std::vector<char> SSHNativeController::readOutput() {
        if (!io_buffer_) {
            return {};
        }
        
        std::lock_guard<std::mutex> lock(io_buffer_->output_mutex);
        
        if (io_buffer_->output_queue.empty()) {
            io_buffer_->has_output = false;
            return {};
        }
        
        auto data = std::move(io_buffer_->output_queue.front());
        io_buffer_->output_queue.pop();
        
        if (io_buffer_->output_queue.empty()) {
            io_buffer_->has_output = false;
        }
        
        LOGD("Read output: %zu bytes", data.size());
        return data;
    }

    void SSHNativeController::resizeTerminal(int cols, int rows) {
        LOGI("Resizing terminal to %dx%d", cols, rows);
        
        if (pty_handler_) {
            pty_handler_->resize(cols, rows);
        }
    }

    std::string SSHNativeController::getConnectionInfo() const {
        if (!session_) {
            return "No active session";
        }
        
        return session_->getConnectionInfo();
    }

    bool SSHNativeController::sendKeepAlive() {
        if (!session_) {
            return false;
        }
        
        return session_->sendKeepAlive();
    }

    void SSHNativeController::setError(const std::string& error) {
        std::lock_guard<std::mutex> lock(error_mutex_);
        last_error_ = error;
        LOGE("Error set: %s", error.c_str());
    }

    void SSHNativeController::startOutputThread() {
        should_stop_ = false;
        output_thread_ = std::thread(&SSHNativeController::outputThreadFunc, this);
    }

    void SSHNativeController::startKeepAliveThread() {
        should_stop_ = false;
        keepalive_thread_ = std::thread(&SSHNativeController::keepAliveThreadFunc, this);
    }

    void SSHNativeController::stopThreads() {
        should_stop_ = true;
        
        if (output_thread_.joinable()) {
            output_thread_.join();
        }
        
        if (keepalive_thread_.joinable()) {
            keepalive_thread_.join();
        }
    }

    void SSHNativeController::outputThreadFunc() {
        LOGI("Output thread started");
        
        char buffer[4096];
        
        while (!should_stop_ && shell_channel_ && shell_channel_->isReady()) {
            try {
                ssize_t bytes_read = shell_channel_->readData(buffer, sizeof(buffer));
                
                if (bytes_read > 0) {
                    // Add to output queue
                    std::vector<char> data(buffer, buffer + bytes_read);
                    
                    {
                        std::lock_guard<std::mutex> lock(io_buffer_->output_mutex);
                        io_buffer_->output_queue.push(std::move(data));
                        io_buffer_->has_output = true;
                    }
                    
                    LOGD("Output thread read %zd bytes", bytes_read);
                } else if (bytes_read == 0) {
                    // No data available, small delay
                    std::this_thread::sleep_for(std::chrono::milliseconds(10));
                } else {
                    // Error or connection closed
                    LOGE("Output thread read error: %zd", bytes_read);
                    break;
                }
                
            } catch (const std::exception& e) {
                LOGE("Output thread exception: %s", e.what());
                break;
            }
        }
        
        LOGI("Output thread ended");
    }

    void SSHNativeController::keepAliveThreadFunc() {
        LOGI("Keep-alive thread started");
        
        while (!should_stop_ && isConnected()) {
            // Wait 30 seconds between keep-alive messages
            for (int i = 0; i < 300 && !should_stop_; ++i) {
                std::this_thread::sleep_for(std::chrono::milliseconds(100));
            }
            
            if (!should_stop_ && isConnected()) {
                if (!sendKeepAlive()) {
                    LOGE("Keep-alive failed, connection may be lost");
                    break;
                }
                LOGD("Keep-alive sent successfully");
            }
        }
        
        LOGI("Keep-alive thread ended");
    }

    // Global instance management
    static std::unique_ptr<SSHNativeController> g_instance;
    static std::mutex g_instance_mutex;

    SSHNativeController& getInstance() {
        std::lock_guard<std::mutex> lock(g_instance_mutex);
        if (!g_instance) {
            g_instance = std::make_unique<SSHNativeController>();
        }
        return *g_instance;
    }

    void destroyInstance() {
        std::lock_guard<std::mutex> lock(g_instance_mutex);
        g_instance.reset();
    }

} // namespace ssh_native