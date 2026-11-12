# ==========================================================================
# NATIVE MEDIA ENGINE (Zero-Implicit & Dead-Code-Elimination Bypass)
# ==========================================================================
cmake_minimum_required(VERSION 3.22.1)
project("media_engine")

set(CMAKE_EXPORT_COMPILE_COMMANDS ON)

# 1. Dynamické cesty podle aktuálně kompilovaného ABI (vyřeší arm64 i x86_64)
set(FFMPEG_LIB_DIR "${CMAKE_CURRENT_SOURCE_DIR}/libs/${ANDROID_ABI}")
set(FFMPEG_INCLUDE_DIR "${CMAKE_CURRENT_SOURCE_DIR}/include/${ANDROID_ABI}")

# Bezpečnostní kontrola pro tebe (zobrazí se v Build okénku, pokud něco chybí)
if(NOT EXISTS "${FFMPEG_LIB_DIR}")
    message(FATAL_ERROR "❌ Složka pro ${ANDROID_ABI} neexistuje! Zkontroluj cpp/libs/${ANDROID_ABI}")
endif()

# 2. Vytvoření tvé C++ knihovny (FFmpeg Media Engine + VideoGen DiT Bridge + TAE Decoder)
add_library(media_engine SHARED
    media_engine_jni.cpp
    academic_yuv_jni.cpp
    videogen_bridge.cpp
    tae_decoder.cpp
)

target_include_directories(media_engine PRIVATE 
    ${CMAKE_CURRENT_SOURCE_DIR}/include
    ${FFMPEG_INCLUDE_DIR}
    ${FFMPEG_INCLUDE_DIR}/freetype2
    ${FFMPEG_INCLUDE_DIR}/harfbuzz
    ${FFMPEG_INCLUDE_DIR}/fribidi
)

# 3. Automatická detekce dostupných statických knihoven
# Tímto se vyhneme pádům CMake, pokud ti náhodou chybí např. libexpat.a
set(FFMPEG_CORE_LIBS "")
foreach(LIB avfilter avformat avcodec swscale swresample avutil)
    if(EXISTS "${FFMPEG_LIB_DIR}/lib${LIB}.a")
        list(APPEND FFMPEG_CORE_LIBS "${FFMPEG_LIB_DIR}/lib${LIB}.a")
    endif()
endforeach()

set(FFMPEG_EXT_LIBS "")
# Přidány nové knihovny: dav1d, SvtAv1Enc, mp3lame, vpx, aom.
foreach(LIB ass harfbuzz freetype fribidi aom dav1d SvtAv1Enc opus mp3lame vpx)
    if(EXISTS "${FFMPEG_LIB_DIR}/lib${LIB}.a")
        list(APPEND FFMPEG_EXT_LIBS "${FFMPEG_LIB_DIR}/lib${LIB}.a")
    endif()
endforeach()

target_link_directories(media_engine PRIVATE "${CMAKE_CURRENT_SOURCE_DIR}/libs/${ANDROID_ABI}")

# 🔴 MASTER FIX #2: Ochrana proti odstranění kodeků (Whole-Archive)
# --start-group řeší cyklické závislosti (když avformat volá avcodec a naopak)
target_link_libraries(media_engine
    -Wl,--start-group
    -Wl,--whole-archive
    ${FFMPEG_CORE_LIBS}
    -Wl,--no-whole-archive
    ${FFMPEG_EXT_LIBS}
    -Wl,--end-group
    -lstable-diffusion
    -lomp
    -landroid
    -llog
    -lz
    -ldl
    -lm
    -ljnigraphics
    -lEGL
    -lGLESv3
    -latomic
    -lmediandk
    -lc++_shared
    -lnativewindow
)

# Aplikace Google Play Security, Android 15 Alignment & Duplicate Symbol Bypass
set_target_properties(media_engine PROPERTIES
    LINK_FLAGS "-Wl,-Bsymbolic -Wl,--allow-multiple-definition -Wl,-z,max-page-size=16384 -Wl,-z,noexecstack -Wl,-z,relro -Wl,-z,now -femulated-tls -Wl,--build-id=sha1"
)
target_compile_options(media_engine PRIVATE -O3 -fPIC -fopenmp -fno-stack-protector -ftls-model=global-dynamic -femulated-tls)
target_compile_features(media_engine PRIVATE cxx_std_17)
