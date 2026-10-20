# jolt-parser standalone lifetime harness (J0c round 4, step D), built by
# src/dev/jolt_j0c_sanitize.clj with the mise-pinned CMake. Never installed.
#
#   ANTLR4_PREFIX  a runtime build to link (the sanitized one for SANITIZE=ON)
#   GENERATED_DIR  the Cpp target of `clj -M:antlr-gen --target cpp`
#   SANITIZE       ON: -fsanitize=address,undefined, fatal on the first report
cmake_minimum_required(VERSION 3.20)
project(jolt_parser_harness CXX)

set(CMAKE_CXX_STANDARD 17)
set(CMAKE_CXX_STANDARD_REQUIRED ON)
option(SANITIZE "AddressSanitizer + UBSan" ON)

file(GLOB GENERATED_SOURCES "${GENERATED_DIR}/*.cpp")
add_executable(jolt_parser_harness harness.cpp ../jolt_parser.cpp ${GENERATED_SOURCES})
target_include_directories(jolt_parser_harness PRIVATE
  "${ANTLR4_PREFIX}/include/antlr4-runtime" "${GENERATED_DIR}")
target_compile_definitions(jolt_parser_harness PRIVATE ANTLR4CPP_STATIC JOLT_PARSER_TEST_SEAMS)
if(SANITIZE)
  set(SAN -fsanitize=address,undefined -fno-sanitize-recover=all -fno-omit-frame-pointer)
  target_compile_options(jolt_parser_harness PRIVATE ${SAN} -g -O1)
  target_link_options(jolt_parser_harness PRIVATE ${SAN})
endif()
target_link_libraries(jolt_parser_harness PRIVATE "${ANTLR4_PREFIX}/lib/libantlr4-runtime.a")
