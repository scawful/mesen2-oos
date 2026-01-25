cmake_minimum_required(VERSION 3.10)
project(Mesen2-OOS)

# Set C++ standard
set(CMAKE_CXX_STANDARD 17)
set(CMAKE_CXX_STANDARD_REQUIRED ON)

# Define options
option(USE_GCC "Use GCC instead of Clang" OFF)
option(DEBUG "Build with debug information" OFF)
option(PGO "Enable Profile Guided Optimization" OFF)
option(SYSTEM_LIBEVDEV "Use system libevdev" OFF)

# Set compiler (only override on Linux, use system default on macOS)
if(NOT CMAKE_SYSTEM_NAME STREQUAL "Darwin")
    if(USE_GCC)
        set(CMAKE_CXX_COMPILER "g++")
        set(CMAKE_C_COMPILER "gcc")
        set(PROFILE_GEN_FLAG "-fprofile-generate")
        set(PROFILE_USE_FLAG "-fprofile-use")
    else()
        set(CMAKE_CXX_COMPILER "clang++")
        set(CMAKE_C_COMPILER "clang")
        set(PROFILE_GEN_FLAG "-fprofile-instr-generate=${CMAKE_CURRENT_SOURCE_DIR}/PGOHelper/pgo.profraw")
        set(PROFILE_USE_FLAG "-fprofile-instr-use=${CMAKE_CURRENT_SOURCE_DIR}/PGOHelper/pgo.profdata")
    endif()
endif()

# Find SDL2
find_package(SDL2 REQUIRED)
include_directories(${SDL2_INCLUDE_DIRS})

# Define MESENPLATFORM
if(CMAKE_SYSTEM_NAME STREQUAL "Linux")
    set(MESENOS "linux")
    set(SHAREDLIB "MesenCore")
    set(SHAREDLIB_FILE "libMesenCore.so")
elseif(CMAKE_SYSTEM_NAME STREQUAL "Darwin")
    set(MESENOS "osx")
    set(SHAREDLIB "MesenCore")
    set(SHAREDLIB_FILE "libMesenCore.dylib")
endif()

if(CMAKE_SYSTEM_PROCESSOR STREQUAL "x86_64" OR CMAKE_SYSTEM_PROCESSOR MATCHES "i.86")
    set(MESENPLATFORM "${MESENOS}-x64")
elseif(CMAKE_SYSTEM_PROCESSOR MATCHES "arm.*")
    set(MESENPLATFORM "${MESENOS}-arm64")
endif()

# set(MESENFLAGS "-m64")

# Set flags based on build type
# if(DEBUG)
#     set(MESENFLAGS "${MESENFLAGS} -O0 -g")
# else()
#     set(MESENFLAGS "${MESENFLAGS} -O3")
# endif()

# Handle PGO
if(PGO STREQUAL "profile")
    set(MESENFLAGS "${MESENFLAGS} ${PROFILE_GEN_FLAG}")
elseif(PGO STREQUAL "optimize")
    set(MESENFLAGS "${MESENFLAGS} ${PROFILE_USE_FLAG}")
endif()

# Include directories
include_directories(${CMAKE_CURRENT_SOURCE_DIR})
include_directories(${CMAKE_CURRENT_SOURCE_DIR}/Core)
include_directories(${CMAKE_CURRENT_SOURCE_DIR}/Utilities)
include_directories(${CMAKE_CURRENT_SOURCE_DIR}/Linux)
include_directories(${CMAKE_CURRENT_SOURCE_DIR}/MacOS)
include_directories(${CMAKE_CURRENT_SOURCE_DIR}/Sdl)

# Source files (use GLOB_RECURSE to include subdirectories)
file(GLOB_RECURSE CORESRC "Core/*.cpp")
file(GLOB_RECURSE UTILSRC "Utilities/*.cpp" "Utilities/*.c")
file(GLOB LINUXSRC "Linux/*.cpp")
file(GLOB SEVENZIPSRC "SevenZip/*.c")
file(GLOB LUASRC "Lua/*.c")
file(GLOB DLLSRC "InteropDLL/*.cpp")
file(GLOB SDLSRC "Sdl/*.cpp")

if(MESENOS STREQUAL "osx")
    file(GLOB MACOSSRC "MacOS/*.mm")
    set(LINUXSRC "")
    set(LIBEVDEVSRC "")
else()
    set(MACOSSRC "")
    if(SYSTEM_LIBEVDEV)
        find_package(PkgConfig REQUIRED)
        pkg_check_modules(LIBEVDEV REQUIRED libevdev)
        include_directories(${LIBEVDEV_INCLUDE_DIRS})
    else()
        file(GLOB LIBEVDEVSRC "Linux/libevdev/*.c")
        include_directories(${CMAKE_CURRENT_SOURCE_DIR}/Linux/libevdev)
    endif()
endif()

# Compile targets
add_library(${SHAREDLIB} SHARED ${CORESRC} ${UTILSRC} ${LINUXSRC} ${SEVENZIPSRC} ${LUASRC} ${DLLSRC} ${SDLSRC} ${MACOSSRC} ${LIBEVDEVSRC})

# Link options and libraries
if(MESENOS STREQUAL "osx")
    target_link_libraries(${SHAREDLIB} ${SDL2_LIBRARIES} "-framework Foundation" "-framework Cocoa")
else()
    target_link_libraries(${SHAREDLIB} ${SDL2_LIBRARIES} pthread stdc++fs)
endif()

if(SYSTEM_LIBEVDEV)
    target_link_libraries(${SHAREDLIB} ${LIBEVDEV_LIBRARIES})
endif()

# Set compile flags
target_compile_options(${SHAREDLIB} PRIVATE ${MESENFLAGS} -fPIC -Wall)

# Output directories
set_target_properties(${SHAREDLIB} PROPERTIES
    RUNTIME_OUTPUT_DIRECTORY ${CMAKE_BINARY_DIR}/bin/${MESENPLATFORM}
    LIBRARY_OUTPUT_DIRECTORY ${CMAKE_BINARY_DIR}/lib/${MESENPLATFORM}
    ARCHIVE_OUTPUT_DIRECTORY ${CMAKE_BINARY_DIR}/lib/${MESENPLATFORM}
)

# Add custom target for UI
add_custom_target(ui ALL
    COMMAND mkdir -p ${CMAKE_BINARY_DIR}/bin/${MESENPLATFORM}/Dependencies
    COMMAND rm -fr ${CMAKE_BINARY_DIR}/bin/${MESENPLATFORM}/Dependencies/*
    COMMAND cp ${CMAKE_BINARY_DIR}/lib/${MESENPLATFORM}/${SHAREDLIB_FILE} ${CMAKE_BINARY_DIR}/bin/${MESENPLATFORM}/${SHAREDLIB_FILE}
    COMMAND dotnet publish UI -c ${CMAKE_BUILD_TYPE} -p:OptimizeUi="true" ${PUBLISHFLAGS}
    WORKING_DIRECTORY ${CMAKE_CURRENT_SOURCE_DIR}
    DEPENDS ${SHAREDLIB}
)
