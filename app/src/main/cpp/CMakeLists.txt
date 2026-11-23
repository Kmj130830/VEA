cmake_minimum_required(VERSION 3.22.1)
project(vea_native LANGUAGES CXX)

set(CMAKE_CXX_STANDARD 20)
set(CMAKE_CXX_STANDARD_REQUIRED ON)
set(CMAKE_CXX_EXTENSIONS OFF)
set(CMAKE_POSITION_INDEPENDENT_CODE ON)

add_library(veag2p SHARED
    veag2p_core.cpp
)
target_include_directories(veag2p PUBLIC ${CMAKE_CURRENT_SOURCE_DIR}/include)
target_compile_options(veag2p PRIVATE -Wall -Wextra)

add_library(veavsm SHARED
    vea_vsm_callback.cpp
)
target_include_directories(veavsm PUBLIC ${CMAKE_CURRENT_SOURCE_DIR}/include)
target_compile_definitions(veavsm PRIVATE VEA_VSM_BUILD)
target_compile_options(veavsm PRIVATE -Wall -Wextra)

add_library(veas5tensor SHARED
    vea_s5_tensor.cpp
    vea_s5_container.cpp
    vea_s5_typed_array.cpp
    vea_s5_activation.cpp
    vea_s5_stats.cpp
    vea_s5_dwconv.cpp
    vea_s5_gru_names.cpp
    vea_s5_residual.cpp
    vea_s5_gelu.cpp
    vea_s5_normalization.cpp
    vea_s5_gemm.cpp
    vea_s5_banked_linear.cpp
    vea_s5_stride2_conv1d.cpp
    vea_s5_upsample2_conv3.cpp
    vea_s5_block.cpp
    vea_s5_history_ring.cpp
    vea_s5_spp.cpp
)
target_include_directories(veas5tensor PUBLIC ${CMAKE_CURRENT_SOURCE_DIR}/include)
target_compile_definitions(veas5tensor PRIVATE VEA_S5_TENSOR_BUILD)
target_compile_options(veas5tensor PRIVATE -Wall -Wextra)

if(ANDROID)
    target_sources(veag2p PRIVATE veag2p_jni.cpp)
    target_sources(veavsm PRIVATE vea_vsm_callback_jni.cpp)
    target_sources(veas5tensor PRIVATE vea_s5_tensor_jni.cpp)
    find_library(android_log_lib log)
    if(android_log_lib)
        target_link_libraries(veag2p PRIVATE ${android_log_lib})
        target_link_libraries(veavsm PRIVATE ${android_log_lib})
    endif()
else()
    enable_testing()
    add_executable(veag2p_core_test tests/veag2p_core_test.cpp)
    target_link_libraries(veag2p_core_test PRIVATE veag2p)
    target_compile_options(veag2p_core_test PRIVATE -UNDEBUG)
    target_include_directories(veag2p_core_test PRIVATE ${CMAKE_CURRENT_SOURCE_DIR}/include)
    add_test(NAME veag2p_core_test COMMAND veag2p_core_test)

    add_executable(veavsm_callback_test tests/veavsm_callback_test.cpp)
    target_link_libraries(veavsm_callback_test PRIVATE veavsm)
    target_compile_options(veavsm_callback_test PRIVATE -UNDEBUG)
    target_include_directories(veavsm_callback_test PRIVATE ${CMAKE_CURRENT_SOURCE_DIR}/include)
    add_test(NAME veavsm_callback_test COMMAND veavsm_callback_test)

    add_executable(vea_s5_tensor_test tests/vea_s5_tensor_test.cpp)
    target_link_libraries(vea_s5_tensor_test PRIVATE veas5tensor)
    target_compile_options(vea_s5_tensor_test PRIVATE -UNDEBUG)
    target_include_directories(vea_s5_tensor_test PRIVATE ${CMAKE_CURRENT_SOURCE_DIR}/include)
    add_test(NAME vea_s5_tensor_test COMMAND vea_s5_tensor_test)
endif()
