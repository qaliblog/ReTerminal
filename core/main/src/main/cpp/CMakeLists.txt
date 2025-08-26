cmake_minimum_required(VERSION 3.22.1)

project("reterminal_ssh")

# Configure C++ standard
set(CMAKE_CXX_STANDARD 17)
set(CMAKE_CXX_STANDARD_REQUIRED ON)

# Find required packages
find_package(PkgConfig REQUIRED)

# Add libssh2 as external project
include(ExternalProject)

ExternalProject_Add(
    libssh2_external
    URL https://github.com/libssh2/libssh2/releases/download/libssh2-1.11.0/libssh2-1.11.0.tar.gz
    URL_HASH SHA256=3736161e41e2693324deb38c26cfdc3efe6209d634ba4204d1735a30aa394966
    CONFIGURE_COMMAND <SOURCE_DIR>/configure 
        --host=${ANDROID_TOOLCHAIN_NAME}
        --prefix=<INSTALL_DIR>
        --enable-static
        --disable-shared
        --disable-examples-build
        --disable-tests
        --with-crypto=openssl
        CC=${CMAKE_C_COMPILER}
        CXX=${CMAKE_CXX_COMPILER}
        CFLAGS=${CMAKE_C_FLAGS}
        CXXFLAGS=${CMAKE_CXX_FLAGS}
        LDFLAGS=${CMAKE_SHARED_LINKER_FLAGS}
    BUILD_COMMAND make -j${CMAKE_BUILD_PARALLEL_LEVEL}
    INSTALL_COMMAND make install
    LOG_CONFIGURE ON
    LOG_BUILD ON
    LOG_INSTALL ON
)

# Get libssh2 install directory
ExternalProject_Get_Property(libssh2_external INSTALL_DIR)
set(LIBSSH2_INSTALL_DIR ${INSTALL_DIR})

# Create imported target for libssh2
add_library(libssh2 STATIC IMPORTED)
add_dependencies(libssh2 libssh2_external)
set_target_properties(libssh2 PROPERTIES
    IMPORTED_LOCATION ${LIBSSH2_INSTALL_DIR}/lib/libssh2.a
    INTERFACE_INCLUDE_DIRECTORIES ${LIBSSH2_INSTALL_DIR}/include
)

# Native SSH implementation
add_library(
    reterminal_ssh
    SHARED
    src/main/cpp/ssh_native.cpp
    src/main/cpp/ssh_session.cpp
    src/main/cpp/ssh_channel.cpp
    src/main/cpp/pty_handler.cpp
    src/main/cpp/jni_bridge.cpp
)

# Include directories
target_include_directories(reterminal_ssh PRIVATE
    ${LIBSSH2_INSTALL_DIR}/include
    src/main/cpp/include
)

# Link libraries
target_link_libraries(reterminal_ssh
    libssh2
    crypto
    ssl
    z
    log
    android
)

# Compiler flags
target_compile_options(reterminal_ssh PRIVATE
    -Wall
    -Wextra
    -O2
    -fvisibility=hidden
    -DANDROID
    -DLIBSSH2_ANDROID
)

# Export symbols for JNI
set_target_properties(reterminal_ssh PROPERTIES
    LINK_FLAGS "-Wl,--export-dynamic"
)