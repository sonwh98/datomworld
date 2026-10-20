# jolt-parser: the ANTLR4 C++ Python3 parser behind an extern "C" wrapper,
# built by `bb build:jolt-native` with the mise-pinned CMake
# (docs/design/yin.repl-jolt-implementation.md §7.2-7.3).
#
#   ANTLR4_PREFIX  the pinned runtime installed by build:jolt-native
#   GENERATED_DIR  the Cpp target of `clj -M:antlr-gen --target cpp`
#                  (LOCAL-BUILD-ONLY, under build/)
cmake_minimum_required(VERSION 3.20)
project(jolt_parser CXX)

set(CMAKE_CXX_STANDARD 17)
set(CMAKE_CXX_STANDARD_REQUIRED ON)

if(NOT ANTLR4_PREFIX OR NOT GENERATED_DIR)
  message(FATAL_ERROR "ANTLR4_PREFIX and GENERATED_DIR are required")
endif()

file(GLOB GENERATED_SOURCES "${GENERATED_DIR}/*.cpp")
if(NOT GENERATED_SOURCES)
  message(FATAL_ERROR "no generated sources in ${GENERATED_DIR}")
endif()

# One shared library: the runtime is linked in statically, and only the
# extern "C" entries are exported.
add_library(jolt_parser SHARED jolt_parser.cpp ${GENERATED_SOURCES})
target_include_directories(jolt_parser PRIVATE
  "${ANTLR4_PREFIX}/include/antlr4-runtime" "${GENERATED_DIR}")
target_compile_definitions(jolt_parser PRIVATE ANTLR4CPP_STATIC)
target_link_libraries(jolt_parser PRIVATE "${ANTLR4_PREFIX}/lib/libantlr4-runtime.a")
set_target_properties(jolt_parser PROPERTIES
  CXX_VISIBILITY_PRESET hidden
  VISIBILITY_INLINES_HIDDEN ON)
install(TARGETS jolt_parser LIBRARY DESTINATION lib)
