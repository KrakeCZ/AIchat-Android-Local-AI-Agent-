cmake_minimum_required(VERSION 3.22.1)

project("ai-chat" VERSION 1.0.0 LANGUAGES C CXX)

set(CMAKE_C_STANDARD 11)
set(CMAKE_C_STANDARD_REQUIRED true)

set(CMAKE_CXX_STANDARD 17)
set(CMAKE_CXX_STANDARD_REQUIRED true)

set(CMAKE_C_FLAGS   "${CMAKE_C_FLAGS}"   CACHE STRING "" FORCE)
set(CMAKE_CXX_FLAGS "${CMAKE_CXX_FLAGS}" CACHE STRING "" FORCE)

# --------------------------------------------------------------------------
# AI Chat library
# --------------------------------------------------------------------------

if(DEFINED ANDROID_ABI)
    message(STATUS "Detected Android ABI: ${ANDROID_ABI}")
    if(ANDROID_ABI STREQUAL "arm64-v8a")
        set(GGML_SYSTEM_ARCH "ARM")
        # FIX: KleidiAI vyžaduje stažení externích závislostí (FetchContent), což selhává bez Git/Net.
        # Vypínám optimalizaci pro zajištění sestavení.
        set(GGML_CPU_KLEIDIAI OFF)
        set(GGML_OPENMP ON)
    elseif(ANDROID_ABI STREQUAL "x86_64")
        set(GGML_SYSTEM_ARCH "x86")
        set(GGML_CPU_KLEIDIAI OFF)
        set(GGML_OPENMP OFF)
    else()
        message(FATAL_ERROR "Unsupported ABI: ${ANDROID_ABI}")
    endif()
endif()

set(LLAMA_SRC ${CMAKE_CURRENT_LIST_DIR}/../../../../../../)

# Standalone mtmd build in upstream llama.cpp
set(LLAMA_BUILD_MTMD ON CACHE BOOL "Build tools/mtmd library" FORCE)

add_subdirectory(${LLAMA_SRC} build-llama)

if(TARGET llama-common AND NOT TARGET common)
    add_library(common ALIAS llama-common)
endif()

if(NOT TARGET mtmd)
    # Fallback pro starší verze pokud není mtmd target z kořenového CMake
    if(NOT DEFINED LLAMA_INSTALL_VERSION OR LLAMA_INSTALL_VERSION STREQUAL "")
        set(LLAMA_INSTALL_VERSION "0.0.0")
    endif()
    add_subdirectory(${LLAMA_SRC}/tools/mtmd build-mtmd)
endif()

add_library(${CMAKE_PROJECT_NAME} SHARED
        ai_chat.cpp)

target_compile_definitions(${CMAKE_PROJECT_NAME} PRIVATE
        GGML_SYSTEM_ARCH=${GGML_SYSTEM_ARCH}
        GGML_CPU_KLEIDIAI=$<BOOL:${GGML_CPU_KLEIDIAI}>
        GGML_OPENMP=$<BOOL:${GGML_OPENMP}>
)

target_include_directories(${CMAKE_PROJECT_NAME} PRIVATE
        ${LLAMA_SRC}
        ${LLAMA_SRC}/common
        ${LLAMA_SRC}/include
        ${LLAMA_SRC}/ggml/include
        ${LLAMA_SRC}/ggml/src
        ${LLAMA_SRC}/tools/mtmd
        ${LLAMA_SRC}/vendor)

target_link_libraries(${CMAKE_PROJECT_NAME}
        llama
        common
        mtmd
        android
        log)

# ── 16 KB Page Alignment (Android 15+ / Google Play požadavek od 1.11.2025) ──
# Všechny .so generované z tohoto projektu musí mít LOAD segmenty zarovnány
# na 16 KB hranici. max-page-size=16384 zajistí správné ELF segment zarovnání.
# NDK r26b+ a r27+ toto podporuje pro arm64-v8a (primární ABI S24 Ultra).
if(ANDROID_ABI STREQUAL "arm64-v8a")
    target_link_options(${CMAKE_PROJECT_NAME} PRIVATE
        "-Wl,-z,max-page-size=16384"
    )
    # Propagujeme flag i na llama/ggml pokud je to sdílená knihovna
    if(TARGET llama)
        target_link_options(llama PRIVATE "-Wl,-z,max-page-size=16384")
    endif()
endif()
