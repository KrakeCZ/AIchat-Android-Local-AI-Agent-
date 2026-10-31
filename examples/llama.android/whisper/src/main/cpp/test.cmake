cmake_minimum_required(VERSION 3.22)
macro(add_custom_command)
    set(_skip_cmd OFF)
    foreach(_arg IN LISTS ARGV)
        string(FIND "${_arg}" "vulkan-shaders-gen" _pos)
        message(STATUS "Arg: ${_arg} | Pos: ${_pos}")
        if(NOT _pos EQUAL -1)
            set(_skip_cmd ON)
        endif()
    endforeach()
    if(_skip_cmd)
        message(STATUS "SKIPPED!")
    else()
        message(STATUS "NOT SKIPPED: ${ARGV}")
    endif()
endmacro()

add_custom_command(
    OUTPUT file.cpp
    DEPENDS vulkan-shaders-gen
)
