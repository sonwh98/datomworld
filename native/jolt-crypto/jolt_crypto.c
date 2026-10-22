/*
 * jolt-crypto: the extern "C" wrapper behind the jolt overrides of
 * dao.host.hash and dao.host.sign
 * (docs/design/yin.repl-jolt-implementation.md §6.2-6.3).
 *
 * It implements no algorithm. BLAKE3 is the vendored upstream C reference,
 * linked in statically; SHA-256 and Ed25519 are the system libcrypto's public
 * EVP API, linked by absolute path. Every entry takes caller-owned pointer and
 * length arguments (never C strings: binary input may hold NUL) and writes a
 * fixed-size result into a caller-owned buffer; nothing it allocates outlives
 * the call. Entries answer 0 on success and a negative status on failure,
 * except ed25519_verify (1 valid, 0 invalid, negative failure) and provenance
 * (the byte count written).
 *
 * Export policy: nine narrow entries and nothing else — layout_version,
 * cleanse, blake3, sha256, ed25519_{public,sign,verify}, provenance (the
 * J1b eight) and random (J2: fresh entropy for yin.vm.linker.sign/generate's
 * :jolt arm, owner ruling 1792655000000; libcrypto RAND_bytes from the same
 * qualified library every other entry resolved to). A new export is a
 * documented, reviewable event, never a general-purpose surface.
 *
 * jolt_crypto_provenance reports OpenSSL_version_num(), the dladdr path of
 * all 19 libcrypto entries (including RAND_bytes) this file resolved and the SHA-256 of that library file, so
 * dao.host.hash can refuse to load when the process bound them to a library
 * other than the qualified one. The digest is computed by the library being
 * checked: it detects an upgrade or a replaced file, not an adversary able to
 * write the system library (who owns the process anyway).
 *
 * jolt_crypto_cleanse zeroes caller-owned memory without allocating, so
 * secret-holding native buffers can be cleansed on every release path.
 *
 * jolt_crypto_random fills a caller-owned buffer with n bytes from the same
 * libcrypto's CSPRNG (RAND_bytes). A failed draw is an error status, never
 * silent: randomness failure zeroes the output and is an error (owner ruling).
 */
#define _GNU_SOURCE
#include <dlfcn.h>
#include <limits.h>
#include <stdint.h>
#include <stdio.h>
#include <string.h>

#include <openssl/crypto.h>
#include <openssl/evp.h>
#include <openssl/opensslv.h>
#include <openssl/rand.h>

#include "blake3.h"

#if OPENSSL_VERSION_NUMBER < 0x30000000L
#error "jolt-crypto needs OpenSSL 3.0 or later headers"
#endif

#define JOLT_CRYPTO_EXPORT __attribute__((visibility("default")))

/* Bump when an entry's signature, a status meaning, or the exported set
   changes (J2: the random entry added it to 3); hash.jolt refuses a library
   that answers another layout, so a stale prebuilt wrapper fails the load
   gate instead of a missing-symbol error far from it. */
#define JOLT_CRYPTO_LAYOUT 3

enum {
  JC_OK = 0,
  JC_ARG = -1,     /* a NULL pointer argument */
  JC_OPENSSL = -2, /* libcrypto reported failure */
  JC_SPACE = -3,   /* provenance buffer too small */
  JC_SPLIT = -4,   /* libcrypto entries resolved to more than one library */
  JC_DLADDR = -5,  /* dladdr could not place an entry */
  JC_READ = -6     /* the resolved library file could not be read */
};

JOLT_CRYPTO_EXPORT int jolt_crypto_layout_version(void) {
  return JOLT_CRYPTO_LAYOUT;
}

JOLT_CRYPTO_EXPORT void jolt_crypto_cleanse(void *p, size_t n) {
  if (p) OPENSSL_cleanse(p, n);
}

JOLT_CRYPTO_EXPORT int jolt_crypto_random(uint8_t *out, size_t n) {
  if (!out || n > INT_MAX) return JC_ARG;
  /* The same libcrypto every EVP entry resolved to: RAND_bytes draws from its
     DRBG seeded by the OS. 1 success, 0 or negative failure; a failed draw is
     the caller's error, never a silent substitute. */
  if (RAND_bytes(out, (int)n) != 1) {
    OPENSSL_cleanse(out, n);
    return JC_OPENSSL;
  }
  return JC_OK;
}

JOLT_CRYPTO_EXPORT int jolt_crypto_blake3(const uint8_t *in, size_t n,
                                          uint8_t *out32) {
  if (!in || !out32) return JC_ARG;
  blake3_hasher h;
  blake3_hasher_init(&h);
  blake3_hasher_update(&h, in, n);
  blake3_hasher_finalize(&h, out32, BLAKE3_OUT_LEN);
  return JC_OK;
}

JOLT_CRYPTO_EXPORT int jolt_crypto_sha256(const uint8_t *in, size_t n,
                                          uint8_t *out32) {
  if (!in || !out32) return JC_ARG;
  unsigned int len = 0;
  if (EVP_Digest(in, n, out32, &len, EVP_sha256(), NULL) != 1 || len != 32)
    return JC_OPENSSL;
  return JC_OK;
}

static EVP_PKEY *private_key(const uint8_t *seed32) {
  return EVP_PKEY_new_raw_private_key(EVP_PKEY_ED25519, NULL, seed32, 32);
}

JOLT_CRYPTO_EXPORT int jolt_crypto_ed25519_public(const uint8_t *seed32,
                                                  uint8_t *out32) {
  if (!seed32 || !out32) return JC_ARG;
  EVP_PKEY *key = private_key(seed32);
  if (!key) return JC_OPENSSL;
  size_t len = 32;
  int ok = EVP_PKEY_get_raw_public_key(key, out32, &len) == 1 && len == 32;
  EVP_PKEY_free(key);
  return ok ? JC_OK : JC_OPENSSL;
}

JOLT_CRYPTO_EXPORT int jolt_crypto_ed25519_sign(const uint8_t *seed32,
                                                const uint8_t *msg, size_t n,
                                                uint8_t *out64) {
  if (!seed32 || !msg || !out64) return JC_ARG;
  EVP_PKEY *key = private_key(seed32);
  if (!key) return JC_OPENSSL;
  EVP_MD_CTX *ctx = EVP_MD_CTX_new();
  size_t len = 64;
  /* Ed25519 is one-shot: NULL digest, the whole message to EVP_DigestSign */
  int ok = ctx && EVP_DigestSignInit(ctx, NULL, NULL, NULL, key) == 1 &&
           EVP_DigestSign(ctx, out64, &len, msg, n) == 1 && len == 64;
  EVP_MD_CTX_free(ctx);
  EVP_PKEY_free(key);
  if (!ok) OPENSSL_cleanse(out64, 64);
  return ok ? JC_OK : JC_OPENSSL;
}

JOLT_CRYPTO_EXPORT int jolt_crypto_ed25519_verify(const uint8_t *pub32,
                                                  const uint8_t *msg, size_t n,
                                                  const uint8_t *sig64) {
  if (!pub32 || !msg || !sig64) return JC_ARG;
  EVP_PKEY *key =
      EVP_PKEY_new_raw_public_key(EVP_PKEY_ED25519, NULL, pub32, 32);
  if (!key) return JC_OPENSSL;
  EVP_MD_CTX *ctx = EVP_MD_CTX_new();
  int status = JC_OPENSSL;
  if (ctx && EVP_DigestVerifyInit(ctx, NULL, NULL, NULL, key) == 1) {
    /* 1 valid; 0 (or a negative EVP answer for a malformed signature) is a
       signature that does not verify */
    status = EVP_DigestVerify(ctx, sig64, 64, msg, n) == 1 ? 1 : 0;
  }
  EVP_MD_CTX_free(ctx);
  EVP_PKEY_free(key);
  return status;
}

/* The SHA-256 of the file at path, as 64 lowercase hex digits plus NUL. */
static int file_sha256(const char *path, char hex[65]) {
  FILE *f = fopen(path, "rb");
  if (!f) return JC_READ;
  EVP_MD_CTX *ctx = EVP_MD_CTX_new();
  int ok = ctx && EVP_DigestInit_ex(ctx, EVP_sha256(), NULL) == 1;
  unsigned char chunk[65536];
  size_t got;
  while (ok && (got = fread(chunk, 1, sizeof chunk, f)) > 0)
    ok = EVP_DigestUpdate(ctx, chunk, got) == 1;
  ok = ok && !ferror(f);
  unsigned char md[32];
  unsigned int len = 0;
  ok = ok && EVP_DigestFinal_ex(ctx, md, &len) == 1 && len == 32;
  EVP_MD_CTX_free(ctx);
  fclose(f);
  if (!ok) return JC_READ;
  for (int i = 0; i < 32; i++) snprintf(hex + 2 * i, 3, "%02x", md[i]);
  return JC_OK;
}

/*
 * "<OpenSSL_version_num decimal>\t<OpenSSL_version text>\t<library path>\t
 *  <SHA-256 hex of that file>" into buf (not NUL-terminated); answers the
 * byte count or a negative status. The path is the one library all 19 libcrypto
 * entries below resolved to.
 */
JOLT_CRYPTO_EXPORT int jolt_crypto_provenance(char *buf, size_t cap) {
  if (!buf) return JC_ARG;
  /* All 19 libcrypto entries, including entropy, must share one origin. */
  void *entries[] = {
      (void *)&EVP_DigestInit_ex,           (void *)&EVP_Digest,
      (void *)&EVP_DigestUpdate,            (void *)&EVP_DigestFinal_ex,
      (void *)&EVP_sha256,                  (void *)&EVP_PKEY_new_raw_private_key,
      (void *)&EVP_PKEY_new_raw_public_key, (void *)&EVP_PKEY_get_raw_public_key,
      (void *)&EVP_DigestSignInit,          (void *)&EVP_DigestSign,
      (void *)&EVP_DigestVerifyInit,        (void *)&EVP_DigestVerify,
      (void *)&EVP_MD_CTX_new,              (void *)&EVP_MD_CTX_free,
      (void *)&EVP_PKEY_free,               (void *)&OPENSSL_cleanse,
      (void *)&OpenSSL_version_num,         (void *)&OpenSSL_version, (void *)&RAND_bytes};
  const char *path = NULL;
  for (size_t i = 0; i < sizeof entries / sizeof entries[0]; i++) {
    Dl_info info;
    if (!dladdr(entries[i], &info) || !info.dli_fname) return JC_DLADDR;
    if (!path)
      path = info.dli_fname;
    else if (strcmp(path, info.dli_fname) != 0)
      return JC_SPLIT;
  }
  char hex[65];
  int status = file_sha256(path, hex);
  if (status != JC_OK) return status;
  int n = snprintf(buf, cap, "%lu\t%s\t%s\t%s", (unsigned long)OpenSSL_version_num(),
                   OpenSSL_version(OPENSSL_VERSION), path, hex);
  if (n < 0 || (size_t)n >= cap) return JC_SPACE;
  return n;
}
