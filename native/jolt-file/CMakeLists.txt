# jolt-file: the POSIX descriptor wrapper behind the jolt host branch of
# dao.jing.file (J4a, collab/1792769713135-architect-j4-file-seams-design §2),
# built by `bb build:jolt-native` with the mise-pinned CMake. System libc
# only: no libcrypto, no other dependency.
cmake_minimum_required(VERSION 3.20)
project(jolt_file C)

set(CMAKE_C_STANDARD 11)
set(CMAKE_C_STANDARD_REQUIRED ON)

# Only the jolt_file_* entries are exported (an exported-symbols list on
# macOS, a version script elsewhere); build:jolt-native checks the result
# against the same allowlist.
add_library(jolt_file SHARED jolt_file.c)
if(APPLE)
  target_link_options(jolt_file PRIVATE
    "LINKER:-exported_symbols_list,${CMAKE_CURRENT_SOURCE_DIR}/exports.darwin")
else()
  target_link_options(jolt_file PRIVATE
    "LINKER:--version-script=${CMAKE_CURRENT_SOURCE_DIR}/exports.linux"
    "LINKER:-z,now")
endif()
set_target_properties(jolt_file PROPERTIES
  C_VISIBILITY_PRESET hidden)
install(TARGETS jolt_file LIBRARY DESTINATION lib)
