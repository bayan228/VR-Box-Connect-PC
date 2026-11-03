if(NOT WIN32 OR NOT CMAKE_SIZEOF_VOID_P EQUAL 8)
    message(FATAL_ERROR "The VR Box SteamVR driver requires a 64-bit Windows build")
endif()
include(${CMAKE_CURRENT_SOURCE_DIR}/fetch_sdk.cmake)
add_library(driver_vrbox SHARED driver.cpp)
target_include_directories(driver_vrbox SYSTEM PRIVATE "${OPENVR_ROOT}/headers")
target_link_libraries(driver_vrbox PRIVATE vrbox_steamvr_bridge d3d11 dxgi)
set(VRBOX_DRIVER_PACKAGE "${CMAKE_BINARY_DIR}/steamvr/vrbox")
set_target_properties(driver_vrbox PROPERTIES
    RUNTIME_OUTPUT_DIRECTORY "${VRBOX_DRIVER_PACKAGE}/bin/win64"
    RUNTIME_OUTPUT_DIRECTORY_RELEASE "${VRBOX_DRIVER_PACKAGE}/bin/win64"
    RUNTIME_OUTPUT_DIRECTORY_DEBUG "${VRBOX_DRIVER_PACKAGE}/bin/win64")
add_custom_command(TARGET driver_vrbox POST_BUILD
    COMMAND ${CMAKE_COMMAND} -E make_directory "${VRBOX_DRIVER_PACKAGE}/resources/settings"
    COMMAND ${CMAKE_COMMAND} -E copy_if_different "${CMAKE_CURRENT_SOURCE_DIR}/driver.vrdrivermanifest" "${VRBOX_DRIVER_PACKAGE}/driver.vrdrivermanifest"
    COMMAND ${CMAKE_COMMAND} -E copy_if_different "${CMAKE_CURRENT_SOURCE_DIR}/resources/settings/default.vrsettings" "${VRBOX_DRIVER_PACKAGE}/resources/settings/default.vrsettings"
    COMMAND ${CMAKE_COMMAND} -E copy_if_different "${OPENVR_ROOT}/LICENSE" "${VRBOX_DRIVER_PACKAGE}/OpenVR-LICENSE")
