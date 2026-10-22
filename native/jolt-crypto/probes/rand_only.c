/* Linux LD_PRELOAD probe: only RAND_bytes is interposed. A marker proves
 * whether any seed draw was reached before the provenance gate refused. */
#include <stdio.h>
#include <stdlib.h>
#include <string.h>
int RAND_bytes(unsigned char *out, int n) {
  const char *marker = getenv("JOLT_RAND_MARKER");
  if (marker) { FILE *f = fopen(marker, "w"); if (f) fclose(f); }
  memset(out, 0x5a, (size_t)n);
  return 1;
}
