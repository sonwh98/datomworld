/* Compile the production wrapper with a deterministic failed RAND draw.
 * No production test switch or exported surface is added. */
#define RAND_bytes probe_failed_rand
#include "../jolt_crypto.c"

int probe_failed_rand(unsigned char *out, int n) {
  memset(out, 0x5a, (size_t)n);
  return 0;
}

int main(void) {
  unsigned char seed[32];
  memset(seed, 0x91, sizeof seed);
  if (jolt_crypto_random(seed, sizeof seed) != JC_OPENSSL) return 1;
  for (size_t i = 0; i < sizeof seed; ++i) if (seed[i] != 0) return 2;
  puts("PASS failed native draw zeroes every output byte");
  return 0;
}
