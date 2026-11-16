# libunireader_embed.so：llama.cpp / ggml 静态库 + embed_jni.cpp 链成一个 .so（只导出 JNI 符号）。
# 由 build-embed.sh 调用，-DLLAMA_SRC=<llama.cpp 源码目录>。
cmake_minimum_required(VERSION 3.22)
project(unireader_embed CXX C)

if (NOT LLAMA_SRC)
    message(FATAL_ERROR "LLAMA_SRC not set")
endif()

add_subdirectory(${LLAMA_SRC} llama)

add_library(unireader_embed SHARED embed_jni.cpp)
target_link_libraries(unireader_embed PRIVATE llama log)
target_compile_features(unireader_embed PRIVATE cxx_std_17)
target_link_options(unireader_embed PRIVATE
    "-Wl,--version-script=${CMAKE_CURRENT_SOURCE_DIR}/exports.map"
    "-Wl,--gc-sections"
    "-Wl,-z,max-page-size=16384")
