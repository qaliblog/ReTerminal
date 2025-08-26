#ifndef SSH_NATIVE_H
#define SSH_NATIVE_H

#include <jni.h>
#include <android/log.h>
#include <libssh2.h>
#include <string>
#include <memory>
#include <thread>
#include <atomic>
#include <mutex>
#include <queue>

#define LOG_TAG "NativeSSH"
#define LOGI(...) __android_log_print(ANDROID_LOG_INFO, LOG_TAG, __VA_ARGS__)
#define LOGE(...) __android_log_print(ANDROID_LOG_ERROR, LOG_TAG, __VA_ARGS__)
#define LOGD(...) __android_log_print(ANDROID_LOG_DEBUG, LOG_TAG, __VA_ARGS__)

namespace ssh_native {

    // Forward declarations
    class SSHSession;
    class SSHChannel;
    class PTYHandler;

    // Connection parameters
    struct ConnectionParams {
        std::string hostname;
        int port = 22;
        std::string username;
        std::string password;
        std::string private_key_path;
        bool use_key_auth = false;
        int timeout_seconds = 15;
        int keepalive_interval = 60;
    };

    // Terminal settings
    struct TerminalConfig {
        int cols = 80;
        int rows = 24;
        int width = 640;
        int height = 480;
        std::string term_type = "xterm-256color";
    };

    // SSH connection state
    enum class ConnectionState {
        DISCONNECTED,
        CONNECTING,
        AUTHENTICATING,
        CONNECTED,
        ERROR,
        TERMINATED
    };

    // Input/Output buffer for terminal data
    struct IOBuffer {
        std::queue<std::vector<char>> input_queue;
        std::queue<std::vector<char>> output_queue;
        std::mutex input_mutex;
        std::mutex output_mutex;
        std::atomic<bool> has_input{false};
        std::atomic<bool> has_output{false};
    };

    // Main SSH Native Controller
    class SSHNativeController {
    public:
        SSHNativeController();
        ~SSHNativeController();

        // Connection management
        bool connect(const ConnectionParams& params);
        void disconnect();
        bool isConnected() const;
        ConnectionState getState() const;
        std::string getLastError() const;

        // Terminal operations
        bool createShell(const TerminalConfig& config);
        void destroyShell();
        bool sendInput(const char* data, size_t length);
        bool hasOutput();
        std::vector<char> readOutput();
        void resizeTerminal(int cols, int rows);

        // Status and diagnostics
        std::string getConnectionInfo() const;
        bool sendKeepAlive();

    private:
        std::unique_ptr<SSHSession> session_;
        std::unique_ptr<SSHChannel> shell_channel_;
        std::unique_ptr<PTYHandler> pty_handler_;
        std::unique_ptr<IOBuffer> io_buffer_;

        std::atomic<ConnectionState> state_{ConnectionState::DISCONNECTED};
        std::string last_error_;
        mutable std::mutex error_mutex_;

        // Background threads
        std::thread output_thread_;
        std::thread keepalive_thread_;
        std::atomic<bool> should_stop_{false};

        // Private methods
        void setError(const std::string& error);
        void startOutputThread();
        void startKeepAliveThread();
        void stopThreads();
        void outputThreadFunc();
        void keepAliveThreadFunc();
    };

    // Global instance access
    SSHNativeController& getInstance();
    void destroyInstance();

} // namespace ssh_native

// JNI function declarations
extern "C" {
    JNIEXPORT jlong JNICALL Java_com_rk_terminal_ssh_NativeSSH_nativeCreate(JNIEnv* env, jobject thiz);
    JNIEXPORT void JNICALL Java_com_rk_terminal_ssh_NativeSSH_nativeDestroy(JNIEnv* env, jobject thiz, jlong handle);
    JNIEXPORT jboolean JNICALL Java_com_rk_terminal_ssh_NativeSSH_nativeConnect(JNIEnv* env, jobject thiz, jlong handle, jstring hostname, jint port, jstring username, jstring password, jstring keyPath, jboolean useKey, jint timeout);
    JNIEXPORT void JNICALL Java_com_rk_terminal_ssh_NativeSSH_nativeDisconnect(JNIEnv* env, jobject thiz, jlong handle);
    JNIEXPORT jboolean JNICALL Java_com_rk_terminal_ssh_NativeSSH_nativeCreateShell(JNIEnv* env, jobject thiz, jlong handle, jint cols, jint rows, jstring termType);
    JNIEXPORT jboolean JNICALL Java_com_rk_terminal_ssh_NativeSSH_nativeSendInput(JNIEnv* env, jobject thiz, jlong handle, jbyteArray data);
    JNIEXPORT jbyteArray JNICALL Java_com_rk_terminal_ssh_NativeSSH_nativeReadOutput(JNIEnv* env, jobject thiz, jlong handle);
    JNIEXPORT jboolean JNICALL Java_com_rk_terminal_ssh_NativeSSH_nativeHasOutput(JNIEnv* env, jobject thiz, jlong handle);
    JNIEXPORT jboolean JNICALL Java_com_rk_terminal_ssh_NativeSSH_nativeIsConnected(JNIEnv* env, jobject thiz, jlong handle);
    JNIEXPORT jstring JNICALL Java_com_rk_terminal_ssh_NativeSSH_nativeGetLastError(JNIEnv* env, jobject thiz, jlong handle);
    JNIEXPORT void JNICALL Java_com_rk_terminal_ssh_NativeSSH_nativeResize(JNIEnv* env, jobject thiz, jlong handle, jint cols, jint rows);
}

#endif // SSH_NATIVE_H