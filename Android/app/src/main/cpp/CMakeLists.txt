cmake_minimum_required(VERSION 3.18)
project(mesen2_oos_android)

set(CMAKE_CXX_STANDARD 17)
set(CMAKE_CXX_STANDARD_REQUIRED ON)
set(CMAKE_C_STANDARD 11)

get_filename_component(MESEN_ROOT "${CMAKE_CURRENT_LIST_DIR}/../../../../.." ABSOLUTE)
get_filename_component(YAZE_SDL "${MESEN_ROOT}/../yaze/ext/SDL" ABSOLUTE)

message(STATUS "Mesen root: ${MESEN_ROOT}")
message(STATUS "SDL root: ${YAZE_SDL}")

if(NOT EXISTS "${YAZE_SDL}/CMakeLists.txt")
    message(FATAL_ERROR "SDL source not found at ${YAZE_SDL}")
endif()

set(SDL_SHARED ON CACHE BOOL "" FORCE)
set(SDL_STATIC OFF CACHE BOOL "" FORCE)
set(SDL_TEST OFF CACHE BOOL "" FORCE)
set(SDL2_DISABLE_INSTALL ON CACHE BOOL "" FORCE)
add_subdirectory("${YAZE_SDL}" "${CMAKE_BINARY_DIR}/sdl2")

file(GLOB_RECURSE CORESRC "${MESEN_ROOT}/Core/*.cpp")
file(GLOB_RECURSE UTILSRC "${MESEN_ROOT}/Utilities/*.cpp" "${MESEN_ROOT}/Utilities/*.c")
file(GLOB SEVENZIPSRC "${MESEN_ROOT}/SevenZip/*.c")
file(GLOB LUASRC "${MESEN_ROOT}/Lua/*.c")
file(GLOB DLLSRC "${MESEN_ROOT}/InteropDLL/*.cpp")
file(GLOB SDLSRC "${MESEN_ROOT}/Sdl/*.cpp")

add_library(main SHARED
    mesen_android_main.cpp
    ${CORESRC}
    ${UTILSRC}
    ${SEVENZIPSRC}
    ${LUASRC}
    ${DLLSRC}
    ${SDLSRC}
)

target_include_directories(main PRIVATE
    "${MESEN_ROOT}"
    "${MESEN_ROOT}/Core"
    "${MESEN_ROOT}/Utilities"
    "${MESEN_ROOT}/Sdl"
    "${MESEN_ROOT}/InteropDLL"
    "${MESEN_ROOT}/SevenZip"
    "${MESEN_ROOT}/Lua"
    "${YAZE_SDL}/include"
)

target_compile_definitions(main PRIVATE
    __ANDROID__
    LUA_USE_POSIX
)

target_compile_options(main PRIVATE
    -fPIC
    -O2
    -Wno-unused-parameter
    -Wno-unused-variable
    -Wno-unused-function
    -Wno-switch
)

find_library(log-lib log)
find_library(android-lib android)
find_library(dl-lib dl)

target_link_libraries(main
    SDL2::SDL2
    ${android-lib}
    ${log-lib}
    ${dl-lib}
)
