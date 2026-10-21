# jolt-crypto: BLAKE3, SHA-256 and Ed25519 behind an extern "C" wrapper for
# the jolt dao.host.hash and dao.host.sign overrides, built by
# `bb build:jolt-native` with the mise-pinned CMake
# (docs/design/yin.repl-jolt-implementation.md §6.2-6.3).
#
#   BLAKE3_PREFIX     the pinned BLAKE3 C reference installed by build:jolt-native
#   LIBCRYPTO_PATH    the declared system libcrypto (jolt-toolchain.edn
#                     :native :libcrypto :path), linked by absolute path
#   OPENSSL_INCLUDE   its headers
cmake_minimum_required(VERSION 3.20)
project(jolt_crypto C)

set(CMAKE_C_STANDARD 99)
set(CMAKE_C_STANDARD_REQUIRED ON)

if(NOT BLAKE3_PREFIX OR NOT LIBCRYPTO_PATH OR NOT OPENSSL_INCLUDE)
  message(FATAL_ERROR "BLAKE3_PREFIX, LIBCRYPTO_PATH and OPENSSL_INCLUDE are required")
endif()
if(NOT EXISTS "${LIBCRYPTO_PATH}")
  message(FATAL_ERROR "declared libcrypto ${LIBCRYPTO_PATH} is absent")
endif()
if(NOT EXISTS "${OPENSSL_INCLUDE}/openssl/evp.h")
  message(FATAL_ERROR "no openssl/evp.h under ${OPENSSL_INCLUDE}")
endif()

# One shared library: BLAKE3 is linked in statically; libcrypto is linked by
# its absolute path (its install name on macOS; DT_NEEDED plus an -rpath to
# its directory elsewhere), never found by a search. Only the jolt_crypto_*
# entries are exported (an exported-symbols list on macOS, a version script
# elsewhere).
add_library(jolt_crypto SHARED jolt_crypto.c)
target_include_directories(jolt_crypto PRIVATE
  "${BLAKE3_PREFIX}/include" "${OPENSSL_INCLUDE}")
target_link_libraries(jolt_crypto PRIVATE
  "${BLAKE3_PREFIX}/lib/libblake3.a" "${LIBCRYPTO_PATH}")
get_filename_component(LIBCRYPTO_DIR "${LIBCRYPTO_PATH}" DIRECTORY)
if(APPLE)
  target_link_options(jolt_crypto PRIVATE
    "LINKER:-exported_symbols_list,${CMAKE_CURRENT_SOURCE_DIR}/exports.darwin")
else()
  target_link_libraries(jolt_crypto PRIVATE dl)
  target_link_options(jolt_crypto PRIVATE
    "LINKER:--version-script=${CMAKE_CURRENT_SOURCE_DIR}/exports.linux"
    "LINKER:-rpath,${LIBCRYPTO_DIR}"
    "LINKER:-z,now")
endif()
set_target_properties(jolt_crypto PROPERTIES
  C_VISIBILITY_PRESET hidden)
install(TARGETS jolt_crypto LIBRARY DESTINATION lib)
