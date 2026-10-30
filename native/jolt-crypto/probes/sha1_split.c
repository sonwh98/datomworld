/* J6c: EVP_sha1 is inside the provenance audit. Compiled with -DSPLIT_SHA1
 * the production wrapper's EVP_sha1 is this executable's own function, so
 * one of the audited entries resolves outside libcrypto and provenance must
 * answer JC_SPLIT before writing anything; compiled without it, the same
 * route answers the report (the control). No production test switch or
 * exported surface is added. */
#ifdef SPLIT_SHA1
#define EVP_sha1 probe_evp_sha1
#endif
#include "../jolt_crypto.c"

#ifdef SPLIT_SHA1
const EVP_MD *probe_evp_sha1(void) { return EVP_sha256(); }
#endif

int main(void) {
  char buf[4096];
  memset(buf, 0x5a, sizeof buf);
  int n = jolt_crypto_provenance(buf, sizeof buf);
#ifdef SPLIT_SHA1
  if (n != JC_SPLIT) {
    printf("FAIL provenance answered %d, expected JC_SPLIT (%d)\n", n, JC_SPLIT);
    return 1;
  }
  for (size_t i = 0; i < sizeof buf; ++i)
    if (buf[i] != 0x5a) {
      puts("FAIL provenance wrote into the buffer before refusing");
      return 2;
    }
  puts("PASS an EVP_sha1 outside libcrypto splits the provenance audit");
#else
  if (n <= 0) {
    printf("FAIL control provenance answered %d\n", n);
    return 3;
  }
  printf("PASS control provenance report %.*s\n", n, buf);
#endif
  return 0;
}
