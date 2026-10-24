/*
 * jolt-file: the POSIX descriptor wrapper behind the jolt host branch of
 * dao.jing.file (the content log), J4a of
 * collab/1792769713135-architect-j4-file-seams-design §2. Private linkage,
 * not a dao.host profile and not an ABI: dao.host.file (src/jolt) is its only
 * caller.
 *
 * Every entry has a fixed signature of fixed-width integers and pointers (no
 * variadic open/fcntl from Clojure, no struct stat layout outside C). Entries
 * answer a non-negative result or -errno, with errno read inside the same
 * invocation, before anything else can overwrite it. One entry is one system
 * call: the read/write loops (short transfers, EINTR, zero progress) are the
 * facade's, so its tests can inject transfers without a production fault
 * mode here.
 *
 * Binary data and paths travel as caller-owned pointer + length, never as C
 * strings: payload bytes may hold NUL, and a path holding NUL is refused
 * (EINVAL), never truncated at it. Path bytes are copied into a terminated
 * buffer owned by the call. Nothing allocated here outlives the call and no
 * pointer is retained.
 *
 * Offsets and counts are int64 and checked into off_t/size_t: negative, or an
 * offset + count past the off_t range, is EINVAL. One transfer moves at most
 * JOLT_FILE_MAX_CHUNK bytes; a larger request answers a short count, which
 * the facade's loop continues.
 *
 * The frame's signed 32-bit big-endian length prefix (review F2) is packed
 * and unpacked here, by s32be_get/s32be_put, never by jolt bit operations on
 * bytes, so a prefix with the top bit set reads back negative.
 *
 * Export policy: the jolt_file_* entries below and nothing else. No lock
 * operations (J4b), no directory sync or rename (J4b). A new export is a
 * reviewed event; bump JOLT_FILE_LAYOUT with it.
 */
#define _FILE_OFFSET_BITS 64
#define _GNU_SOURCE
#include <errno.h>
#include <fcntl.h>
#include <limits.h>
#include <stdint.h>
#include <stdlib.h>
#include <string.h>
#include <sys/stat.h>
#include <sys/types.h>
#include <unistd.h>

#define JOLT_FILE_EXPORT __attribute__((visibility("default")))

_Static_assert(sizeof(off_t) == 8, "jolt-file needs a 64-bit off_t");

/* Bump when an entry's signature, a result meaning, or the exported set
   changes; dao.host.file refuses a library that answers another layout. */
#define JOLT_FILE_LAYOUT 1

/* The largest single transfer: bounds one call independently of the frame
   size (the facade loops), and stays below Linux's 0x7ffff000 per-call cap. */
#define JOLT_FILE_MAX_CHUNK ((int64_t)1 << 30)

JOLT_FILE_EXPORT int32_t jolt_file_layout_version(void) {
  return JOLT_FILE_LAYOUT;
}

/* EINTR as this platform's headers define it: the facade retries on it. */
JOLT_FILE_EXPORT int32_t jolt_file_eintr(void) { return EINTR; }

/* A terminated copy of path bytes; NULL with *err set on refusal. */
static char *path_copy(const uint8_t *path, int64_t n, int *err) {
  if (!path || n <= 0 || n >= PATH_MAX) {
    *err = n >= PATH_MAX ? ENAMETOOLONG : EINVAL;
    return NULL;
  }
  if (memchr(path, 0, (size_t)n)) {
    *err = EINVAL;
    return NULL;
  }
  char *p = malloc((size_t)n + 1);
  if (!p) {
    *err = ENOMEM;
    return NULL;
  }
  memcpy(p, path, (size_t)n);
  p[n] = 0;
  return p;
}

/* mkdir of one directory: an existing directory is success, an existing
   non-directory is ENOTDIR, any other failure is that errno. */
static int mkdir_one(const char *dir) {
  if (mkdir(dir, 0777) == 0) return 0;
  int e = errno;
  if (e != EEXIST) return e;
  struct stat st;
  if (stat(dir, &st) != 0) return errno;
  return S_ISDIR(st.st_mode) ? 0 : ENOTDIR;
}

/*
 * Create every missing parent directory of path (not path itself), checked:
 * 0, or -errno of the first component that could not be made a directory.
 * A path with no directory part has nothing to create.
 */
JOLT_FILE_EXPORT int64_t jolt_file_make_parents(const uint8_t *path,
                                                int64_t n) {
  int err = 0;
  char *p = path_copy(path, n, &err);
  if (!p) return -err;
  char *slash = strrchr(p, '/');
  if (slash && slash != p) {
    *slash = 0;
    /* each prefix ending before a '/', then the whole parent */
    for (char *c = p + 1; *c && !err; c++) {
      if (*c == '/') {
        *c = 0;
        err = mkdir_one(p);
        *c = '/';
      }
    }
    if (!err) err = mkdir_one(p);
  }
  free(p);
  return -err;
}

/*
 * Open or create path for positional reading and writing: O_RDWR | O_CREAT |
 * O_CLOEXEC, mode 0666 under the umask. Never O_TRUNC (existing data is the
 * log) and never O_APPEND (Linux ignores pwrite's offset under it). Answers
 * the descriptor or -errno; EINTR is retried here because an interrupted
 * open has acquired nothing.
 */
JOLT_FILE_EXPORT int64_t jolt_file_open(const uint8_t *path, int64_t n) {
  int err = 0;
  char *p = path_copy(path, n, &err);
  if (!p) return -err;
  int fd;
  do {
    fd = open(p, O_RDWR | O_CREAT | O_CLOEXEC, 0666);
  } while (fd < 0 && errno == EINTR);
  err = fd < 0 ? errno : 0;
  free(p);
  return fd < 0 ? -err : fd;
}

static int fd_ok(int64_t fd) { return fd >= 0 && fd <= INT_MAX; }

/* The file's size by fstat, or -errno. */
JOLT_FILE_EXPORT int64_t jolt_file_length(int64_t fd) {
  if (!fd_ok(fd)) return -EBADF;
  struct stat st;
  if (fstat((int)fd, &st) != 0) return -errno;
  return (int64_t)st.st_size;
}

/* off/at/n non-negative, off + n within off_t, n clamped to one chunk. */
static int transfer_args(int64_t fd, int64_t off, const void *buf, int64_t at,
                         int64_t n, size_t *len) {
  if (!fd_ok(fd)) return EBADF;
  if (!buf || off < 0 || at < 0 || n < 0) return EINVAL;
  if (n > INT64_MAX - off) return EINVAL;
  *len = (size_t)(n > JOLT_FILE_MAX_CHUNK ? JOLT_FILE_MAX_CHUNK : n);
  return 0;
}

/*
 * One pread of up to n bytes at file offset off into buf + at: the count
 * (0 is end of file), or -errno (-EINTR included: the facade decides).
 */
JOLT_FILE_EXPORT int64_t jolt_file_pread(int64_t fd, int64_t off, uint8_t *buf,
                                         int64_t at, int64_t n) {
  size_t len = 0;
  int err = transfer_args(fd, off, buf, at, n, &len);
  if (err) return -err;
  ssize_t got = pread((int)fd, buf + at, len, (off_t)off);
  return got < 0 ? -errno : (int64_t)got;
}

/* One pwrite of up to n bytes from buf + at at file offset off: the count
   written, or -errno. */
JOLT_FILE_EXPORT int64_t jolt_file_pwrite(int64_t fd, int64_t off,
                                          const uint8_t *buf, int64_t at,
                                          int64_t n) {
  size_t len = 0;
  int err = transfer_args(fd, off, buf, at, n, &len);
  if (err) return -err;
  ssize_t put = pwrite((int)fd, buf + at, len, (off_t)off);
  return put < 0 ? -errno : (int64_t)put;
}

/* ftruncate to len: 0 or -errno. */
JOLT_FILE_EXPORT int64_t jolt_file_truncate(int64_t fd, int64_t len) {
  if (!fd_ok(fd)) return -EBADF;
  if (len < 0) return -EINVAL;
  int r;
  do {
    r = ftruncate((int)fd, (off_t)len);
  } while (r != 0 && errno == EINTR);
  return r == 0 ? 0 : -errno;
}

/* A full fsync (not fdatasync, not a port flush): 0 or -errno. Not retried:
   after a failed fsync the kernel may have dropped the dirty pages, so a
   retry that answers 0 would not mean the frame is durable. */
JOLT_FILE_EXPORT int64_t jolt_file_sync(int64_t fd) {
  if (!fd_ok(fd)) return -EBADF;
  return fsync((int)fd) == 0 ? 0 : -errno;
}

/* Exactly one close attempt: 0 or -errno. Never retried after EINTR: on both
   qualified platforms the descriptor is released anyway, and a retry could
   close a descriptor another thread has since been given. */
JOLT_FILE_EXPORT int64_t jolt_file_close(int64_t fd) {
  if (!fd_ok(fd)) return -EBADF;
  return close((int)fd) == 0 ? 0 : -errno;
}

/* The signed 32-bit big-endian integer at buf + at (review F2). */
JOLT_FILE_EXPORT int32_t jolt_file_s32be_get(const uint8_t *buf, int64_t at) {
  const uint8_t *b = buf + at;
  uint32_t u = ((uint32_t)b[0] << 24) | ((uint32_t)b[1] << 16) |
               ((uint32_t)b[2] << 8) | (uint32_t)b[3];
  int32_t v;
  memcpy(&v, &u, sizeof v); /* two's complement, without signed overflow */
  return v;
}

/* v as four signed 32-bit big-endian bytes at buf + at (review F2). */
JOLT_FILE_EXPORT void jolt_file_s32be_put(uint8_t *buf, int64_t at,
                                          int32_t v) {
  uint32_t u;
  memcpy(&u, &v, sizeof u);
  uint8_t *b = buf + at;
  b[0] = (uint8_t)(u >> 24);
  b[1] = (uint8_t)(u >> 16);
  b[2] = (uint8_t)(u >> 8);
  b[3] = (uint8_t)u;
}

/* strerror text of e into buf (not terminated): the byte count written. */
JOLT_FILE_EXPORT int64_t jolt_file_strerror(int64_t e, uint8_t *buf,
                                            int64_t cap) {
  if (!buf || cap <= 0) return 0;
  char tmp[256];
  const char *s = tmp;
  tmp[0] = 0;
#if defined(__GLIBC__)
  s = strerror_r((int)e, tmp, sizeof tmp);
#else
  if (strerror_r((int)e, tmp, sizeof tmp) != 0) tmp[0] = 0;
#endif
  size_t len = strlen(s);
  if ((int64_t)len > cap) len = (size_t)cap;
  memcpy(buf, s, len);
  return (int64_t)len;
}
