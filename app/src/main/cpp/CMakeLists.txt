cmake_minimum_required(VERSION 3.22.1)
project(veag2p LANGUAGES CXX)

set(CMAKE_CXX_STANDARD 20)
set(CMAKE_CXX_STANDARD_REQUIRED ON)
set(CMAKE_CXX_EXTENSIONS OFF)
set(CMAKE_POSITION_INDEPENDENT_CODE ON)

add_library(veag2p SHARED
    veag2p_core.cpp
)

target_include_directories(veag2p PUBLIC
    ${CMAKE_CURRENT_SOURCE_DIR}/include
)

target_compile_options(veag2p PRIVATE
    -Wall
    -Wextra
)

if(ANDROID)
    target_sources(veag2p PRIVATE veag2p_jni.cpp)
    find_library(android_log_lib log)
    if(android_log_lib)
        target_link_libraries(veag2p PRIVATE ${android_log_lib})
    endif()
else()
    enable_testing()
    add_executable(veag2p_core_test
        tests/veag2p_core_test.cpp
    )
    target_link_libraries(veag2p_core_test PRIVATE veag2p)
    target_include_directories(veag2p_core_test PRIVATE
        ${CMAKE_CURRENT_SOURCE_DIR}/include
    )
    add_test(NAME veag2p_core_test COMMAND veag2p_core_test)
endif()
