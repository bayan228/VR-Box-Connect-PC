cmake_minimum_required(VERSION 3.20)
project(vrbox_server VERSION 0.3.0 LANGUAGES CXX)

set(CMAKE_CXX_STANDARD 20)
set(CMAKE_CXX_STANDARD_REQUIRED ON)

if(NOT CMAKE_BUILD_TYPE)
    set(CMAKE_BUILD_TYPE Release CACHE STRING "Build type" FORCE)
endif()

if(MSVC)
    add_compile_options(/W4 /permissive- /utf-8)
    add_compile_definitions(_CRT_SECURE_NO_WARNINGS NOMINMAX WIN32_LEAN_AND_MEAN)
elseif(CMAKE_CXX_COMPILER_ID MATCHES "GNU|Clang|AppleClang")
    add_compile_options(-Wall -Wextra -Wpedantic -Wno-unused-parameter)
endif()

add_library(vrbox_protocol src/protocol/protocol.cpp)
target_include_directories(vrbox_protocol PUBLIC include)

add_library(vrbox_tracking src/tracking/head_pose.cpp)
target_include_directories(vrbox_tracking PUBLIC src)

add_library(vrbox_steamvr_bridge src/steamvr/bridge.cpp)
target_include_directories(vrbox_steamvr_bridge PUBLIC src)

add_library(vrbox_video src/video/video_source.cpp)
target_include_directories(vrbox_video PUBLIC src)
target_link_libraries(vrbox_video PUBLIC vrbox_steamvr_bridge)
if(WIN32)
    target_link_libraries(vrbox_video PRIVATE d3d11 dxgi mfplat mfuuid wmcodecdspuuid ole32 oleaut32 user32)
endif()

# Vendored nlohmann/json 3.11.3, MIT, from the upstream release's single_include directory.
file(SHA256 "${CMAKE_CURRENT_SOURCE_DIR}/third_party/nlohmann/json.hpp" JSON_HEADER_SHA256)
if(NOT JSON_HEADER_SHA256 STREQUAL "9bea4c8066ef4a1c206b2be5a36302f8926f7fdc6087af5d20b417d0cf103ea6")
    message(FATAL_ERROR "The vendored nlohmann/json 3.11.3 header has an unexpected checksum")
endif()

add_executable(${PROJECT_NAME}
    src/main.cpp
    src/network/network.cpp
)
target_include_directories(${PROJECT_NAME} SYSTEM PRIVATE third_party)
target_link_libraries(${PROJECT_NAME} PRIVATE vrbox_protocol vrbox_video vrbox_tracking)
add_custom_command(TARGET ${PROJECT_NAME} POST_BUILD
    COMMAND ${CMAKE_COMMAND} -E make_directory "$<TARGET_FILE_DIR:${PROJECT_NAME}>/licenses"
    COMMAND ${CMAKE_COMMAND} -E copy_if_different
        "${CMAKE_CURRENT_SOURCE_DIR}/third_party/nlohmann/LICENSE.MIT"
        "$<TARGET_FILE_DIR:${PROJECT_NAME}>/licenses/nlohmann-json.LICENSE.MIT"
)

if(WIN32)
    target_link_libraries(${PROJECT_NAME} PRIVATE ws2_32 iphlpapi)
else()
    target_link_libraries(${PROJECT_NAME} PRIVATE pthread)
endif()

set_property(DIRECTORY ${CMAKE_CURRENT_SOURCE_DIR} PROPERTY VS_STARTUP_PROJECT ${PROJECT_NAME})

option(VRBOX_BUILD_STEAMVR_DRIVER "Build the experimental Windows SteamVR HMD driver" ${WIN32})
if(VRBOX_BUILD_STEAMVR_DRIVER)
    add_subdirectory(steamvr-driver)
endif()

include(CTest)
if(BUILD_TESTING)
    add_executable(protocol_tests tests/protocol_tests.cpp)
    target_link_libraries(protocol_tests PRIVATE vrbox_protocol)
    add_test(NAME protocol_tests COMMAND protocol_tests)
    add_executable(head_pose_tests tests/head_pose_tests.cpp)
    target_link_libraries(head_pose_tests PRIVATE vrbox_tracking)
    add_test(NAME head_pose_tests COMMAND head_pose_tests)
    if(WIN32)
        find_program(VRBOX_POWERSHELL NAMES powershell)
        if(VRBOX_POWERSHELL)
            add_test(NAME launcher_tests COMMAND "${VRBOX_POWERSHELL}" -NoProfile -ExecutionPolicy Bypass
                -File "${CMAKE_CURRENT_SOURCE_DIR}/tests/launcher_tests.ps1")
            set_tests_properties(launcher_tests PROPERTIES TIMEOUT 120)
        endif()
        add_executable(steamvr_bridge_tests tests/steamvr_bridge_tests.cpp)
        target_link_libraries(steamvr_bridge_tests PRIVATE vrbox_steamvr_bridge)
        add_test(NAME steamvr_bridge_tests COMMAND steamvr_bridge_tests)
        set_tests_properties(steamvr_bridge_tests PROPERTIES TIMEOUT 15)
        add_executable(video_tests tests/video_tests.cpp)
        target_link_libraries(video_tests PRIVATE vrbox_video)
        add_test(NAME video_tests COMMAND video_tests)
        set_tests_properties(video_tests PROPERTIES TIMEOUT 30)
        if(TARGET driver_vrbox)
            add_executable(steamvr_driver_tests tests/steamvr_driver_tests.cpp)
            target_include_directories(steamvr_driver_tests SYSTEM PRIVATE "${CMAKE_CURRENT_SOURCE_DIR}/third_party/openvr/headers")
            target_link_libraries(steamvr_driver_tests PRIVATE vrbox_steamvr_bridge d3d11 dxgi)
            add_test(NAME steamvr_driver_tests COMMAND steamvr_driver_tests)
            set_tests_properties(steamvr_driver_tests PROPERTIES TIMEOUT 30 SKIP_RETURN_CODE 77)
        endif()
    endif()
endif()
