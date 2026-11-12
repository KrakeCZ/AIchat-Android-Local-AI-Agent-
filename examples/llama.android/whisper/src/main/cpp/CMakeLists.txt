cmake_minimum_required(VERSION 3.22.1)

project(whisper-jni VERSION 1.0.0 LANGUAGES C CXX)

set(CMAKE_C_STANDARD 11)
set(CMAKE_C_STANDARD_REQUIRED true)
set(CMAKE_CXX_STANDARD 17)
set(CMAKE_CXX_STANDARD_REQUIRED true)

# Isolate symbols - this is CRITICAL to prevent collision with libai-chat.so
set(CMAKE_C_VISIBILITY_PRESET hidden)
set(CMAKE_CXX_VISIBILITY_PRESET hidden)
set(CMAKE_VISIBILITY_INLINES_HIDDEN 1)

set(WHISPER_SRC ${CMAKE_CURRENT_LIST_DIR}/../../../../lib/src/main/cpp/whisper.cpp)

# Omezit kompilaci jen na nutné a zapnout optimizations
set(WHISPER_BUILD_EXAMPLES OFF CACHE BOOL "" FORCE)
set(WHISPER_BUILD_TESTS OFF CACHE BOOL "" FORCE)
set(WHISPER_BUILD_SERVER OFF CACHE BOOL "" FORCE)

# Pridani samotneho whisper repozitare (ktery si definuje svuj ggml target)
# [MODULÁRNÍ INTEGRACE]: Aktivace čistého, licenčně bezpečného otevřeného standardu pro GPU
set(GGML_VULKAN ON CACHE BOOL "Enable Vulkan support" FORCE)

# Nastaveni cesty ke glslc.exe z Android NDK (jinak by skoncilo jako NOTFOUND)
set(Vulkan_GLSLC_EXECUTABLE "C:/Users/x/AppData/Local/Android/Sdk/ndk/29.0.13846066/shader-tools/windows-x86_64/glslc.exe" CACHE FILEPATH "Path to glslc")

# --- CLEAN ROOM WORKAROUND FOR GGML-VULKAN ON WINDOWS ---
# NDK křížová kompilace na Windows selhává při pokusu o kompilaci a spuštění
# hostitelského shader generátoru. Zde elegantně obejdeme cmake příkazy bez 
# nutnosti narušovat upstream CMakeLists.txt (v rámci Clean Room architektury).
include(ExternalProject)
macro(add_custom_command)
    set(_skip_cmd OFF)
    foreach(_arg ${ARGV})
        string(FIND "${_arg}" "vulkan-shaders-gen" _pos)
        if(NOT _pos EQUAL -1)
            set(_skip_cmd ON)
        endif()
    endforeach()
    if(_skip_cmd)
        # Preskakujeme generovani - pouzijeme predgenerovane .cpp soubory
    else()
        _add_custom_command(${ARGV})
    endif()
endmacro()

macro(ExternalProject_Add _name)
    if("${_name}" STREQUAL "vulkan-shaders-gen")
        message(STATUS "[Clean Room] Preskakuji kompilaci vulkan-shaders-gen")
        add_custom_target(vulkan-shaders-gen) # dummy target
    else()
        _ExternalProject_Add(${ARGV})
    endif()
endmacro()

# Zkopirujeme predgenerovane shadery do binary diru, kde je ggml-vulkan ocekava
file(COPY "${WHISPER_SRC}/ggml/src/ggml-vulkan/ggml-vulkan-shaders.hpp" DESTINATION "${CMAKE_CURRENT_BINARY_DIR}/build-whisper/ggml/src/ggml-vulkan/")
file(GLOB _comp_cpps "${WHISPER_SRC}/ggml/src/ggml-vulkan/*.comp.cpp")
if(_comp_cpps)
    file(COPY ${_comp_cpps} DESTINATION "${CMAKE_CURRENT_BINARY_DIR}/build-whisper/ggml/src/ggml-vulkan/")
endif()
# --------------------------------------------------------

# NDK r25+ nativne neobsahuje vulkan.hpp, proto explicitne includujeme Khronos Vulkan-Headers
include_directories("${CMAKE_CURRENT_SOURCE_DIR}/Vulkan-Headers/include")

add_subdirectory(${WHISPER_SRC} build-whisper)

add_library(${CMAKE_PROJECT_NAME} SHARED
    whisper_jni.cpp
)

target_include_directories(${CMAKE_PROJECT_NAME} PRIVATE
    ${WHISPER_SRC}/include
    ${WHISPER_SRC}/ggml/include
)

target_link_libraries(${CMAKE_PROJECT_NAME}
    whisper
    android
    log
)

if(ANDROID_ABI STREQUAL "arm64-v8a")
    target_link_options(${CMAKE_PROJECT_NAME} PRIVATE
        "-Wl,-z,max-page-size=16384"
    )
    # Zabezpeceni aby i wrapper mel 16KB stranky pro Play Store 2025+
    if(TARGET whisper)
        target_link_options(whisper PRIVATE "-Wl,-z,max-page-size=16384")
    endif()
endif()
