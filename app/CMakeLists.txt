cmake_minimum_required(VERSION 3.22.1)

project("pool_cv_engine")

add_library(
    pool_cv_engine
    SHARED
    src/main/cpp/pool_cv_engine.cpp
)

find_library(
    log-lib
    log
)

find_library(
    jnigraphics-lib
    jnigraphics
)

target_link_libraries(
    pool_cv_engine
    ${log-lib}
    ${jnigraphics-lib}
)
