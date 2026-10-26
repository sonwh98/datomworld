/*
 * jolt-file: the POSIX descriptor wrapper behind the jolt host branch of
 * dao.jing.file (the content log, J4a) and dao.space.store.fs (directory
 * ownership, bounded reads and HEAD replacement, J4b), per
 * collab/1792769713135-architect-j4-file-seams-design §2-§4. Private linkage,
 * not a dao.host profile and not an ABI: dao.posix.file (src/jolt) is its only
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
 * J4b (layout 2) adds, each fixed-signature and built in C: the whole-file
 * record lock (fcntl F_SETLK, F_WRLCK, SEEK_SET, 0, 0; release is close, so
 * there is no unlock entry), realpath (the C-owned result copied out and
 * freed before return), rename, a directory open+fsync+close, an O_EXCL
 * create for the HEAD temp (mode 0666 under the umask, not private), a
 * non-blocking read-only open, and classify/fclassify (lstat/stat/fstat kept
 * here, answering an enum, so no struct stat layout reaches the facade).
 * fidentity answers a descriptor's st_dev/st_ino for the O_CLOEXEC gate.
 * No registry lives here: the process's own holdings stay in
 * dao.space.store.fs `held`.
 *
 * Export policy: the jolt_file_* entries below and nothing else. A new
 * export is a reviewed event; bump JOLT_FILE_LAYOUT with it.
 */
#define _FILE_OFFSET_BITS 64
#define _GNU_SOURCE
#include <errno.h>
#include <fcntl.h>
#include <limits.h>
#include <stdint.h>
#include <stdio.h>
#include <stdlib.h>
#include <string.h>
#include <sys/stat.h>
#include <sys/types.h>
#include <unistd.h>

#define JOLT_FILE_EXPORT __attribute__((visibility("default")))

_Static_assert(sizeof(off_t) == 8, "jolt-file needs a 64-bit off_t");

/* Bump when an entry's signature, a result meaning, or the exported set
   changes; dao.posix.file refuses a library that answers another layout. */
#define JOLT_FILE_LAYOUT 2

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

/* ---------------------------------------------------------------------------
 * J4b: directory ownership, bounded reads, HEAD replacement (layout 2)
 * ------------------------------------------------------------------------- */

/* classify's answer: what a path or descriptor names. */
enum {
  JF_ABSENT = 0,
  JF_REGULAR = 1,
  JF_DIRECTORY = 2,
  JF_FIFO = 3,
  JF_SYMLINK = 4,
  JF_OTHER = 5
};

static int32_t kind_of(mode_t m) {
  if (S_ISREG(m)) return JF_REGULAR;
  if (S_ISDIR(m)) return JF_DIRECTORY;
  if (S_ISFIFO(m)) return JF_FIFO;
  if (S_ISLNK(m)) return JF_SYMLINK;
  return JF_OTHER;
}

/*
 * What path names: lstat when follow is 0 (a symlink answers JF_SYMLINK,
 * dangling or not), stat when 1 (the link's target; a dangling link answers
 * JF_ABSENT). No entry (ENOENT, or ENOTDIR for a component that is not a
 * directory) is JF_ABSENT; any other failure is -errno, never absence.
 */
JOLT_FILE_EXPORT int64_t jolt_file_classify(const uint8_t *path, int64_t n,
                                            int32_t follow) {
  int err = 0;
  char *p = path_copy(path, n, &err);
  if (!p) return -err;
  struct stat st;
  int r = follow ? stat(p, &st) : lstat(p, &st);
  err = r == 0 ? 0 : errno;
  free(p);
  if (r != 0) return (err == ENOENT || err == ENOTDIR) ? JF_ABSENT : -err;
  return kind_of(st.st_mode);
}

/* What an open descriptor is (fstat): the recheck after a non-blocking open. */
JOLT_FILE_EXPORT int64_t jolt_file_fclassify(int64_t fd) {
  if (!fd_ok(fd)) return -EBADF;
  struct stat st;
  if (fstat((int)fd, &st) != 0) return -errno;
  return kind_of(st.st_mode);
}

/* A descriptor's identity: out2[0] = st_dev, out2[1] = st_ino; 0 or -errno. */
JOLT_FILE_EXPORT int64_t jolt_file_fidentity(int64_t fd, uint64_t *out2) {
  if (!fd_ok(fd)) return -EBADF;
  if (!out2) return -EINVAL;
  struct stat st;
  if (fstat((int)fd, &st) != 0) return -errno;
  out2[0] = (uint64_t)st.st_dev;
  out2[1] = (uint64_t)st.st_ino;
  return 0;
}

/* open with flags, retrying EINTR (an interrupted open acquired nothing). */
static int64_t open_flags(const uint8_t *path, int64_t n, int flags,
                          mode_t mode) {
  int err = 0;
  char *p = path_copy(path, n, &err);
  if (!p) return -err;
  int fd;
  do {
    fd = open(p, flags, mode);
  } while (fd < 0 && errno == EINTR);
  err = fd < 0 ? errno : 0;
  free(p);
  return fd < 0 ? -err : fd;
}

/*
 * The HEAD temp: O_WRONLY | O_CREAT | O_EXCL | O_CLOEXEC, mode 0666 under
 * the umask (the mode travels with the inode across the rename, so it is the
 * other hosts' HEAD mode, not a private one). An existing entry is EEXIST.
 */
JOLT_FILE_EXPORT int64_t jolt_file_open_excl(const uint8_t *path, int64_t n) {
  return open_flags(path, n, O_WRONLY | O_CREAT | O_EXCL | O_CLOEXEC, 0666);
}

/*
 * A bounded read's open: O_RDONLY | O_NONBLOCK | O_CLOEXEC, never creating.
 * Non-blocking so a FIFO swapped in after classify cannot block the open;
 * the caller rechecks the descriptor with fclassify.
 */
JOLT_FILE_EXPORT int64_t jolt_file_open_read(const uint8_t *path, int64_t n) {
  return open_flags(path, n, O_RDONLY | O_NONBLOCK | O_CLOEXEC, 0);
}

/*
 * The whole-file write lock: fcntl(F_SETLK) with F_WRLCK, SEEK_SET, start 0,
 * length 0, the same record-lock family as the JVM's FileChannel.tryLock and
 * Dart's lockSync, so the three exclude one another. Non-blocking (no
 * F_SETLKW). 0 acquired; 1 held by another process (EAGAIN or EACCES, the
 * POSIX pair); -errno otherwise. Released by closing the descriptor (record
 * locks are per process and drop when any descriptor on the inode closes).
 */
JOLT_FILE_EXPORT int64_t jolt_file_lock(int64_t fd) {
  if (!fd_ok(fd)) return -EBADF;
  struct flock fl;
  memset(&fl, 0, sizeof fl);
  fl.l_type = F_WRLCK;
  fl.l_whence = SEEK_SET;
  fl.l_start = 0;
  fl.l_len = 0;
  int r;
  do {
    r = fcntl((int)fd, F_SETLK, &fl);
  } while (r != 0 && errno == EINTR);
  if (r == 0) return 0;
  int err = errno;
  return (err == EAGAIN || err == EACCES) ? 1 : -err;
}

/*
 * realpath of path into buf (not terminated): the byte count, -ERANGE when
 * cap is too small, or -errno. The C library's result is copied out and
 * freed before return.
 */
JOLT_FILE_EXPORT int64_t jolt_file_realpath(const uint8_t *path, int64_t n,
                                            uint8_t *buf, int64_t cap) {
  if (!buf || cap <= 0) return -EINVAL;
  int err = 0;
  char *p = path_copy(path, n, &err);
  if (!p) return -err;
  char *real = realpath(p, NULL);
  err = real ? 0 : errno;
  free(p);
  if (!real) return -err;
  size_t len = strlen(real);
  int64_t r = (int64_t)len > cap ? -ERANGE : (int64_t)len;
  if (r >= 0) memcpy(buf, real, len);
  free(real);
  return r;
}

/* rename(from, to): 0 or -errno. No copy-and-delete fallback (EXDEV fails). */
JOLT_FILE_EXPORT int64_t jolt_file_rename(const uint8_t *from, int64_t n1,
                                          const uint8_t *to, int64_t n2) {
  int err = 0;
  char *a = path_copy(from, n1, &err);
  if (!a) return -err;
  char *b = path_copy(to, n2, &err);
  if (!b) {
    free(a);
    return -err;
  }
  int r = rename(a, b);
  err = r == 0 ? 0 : errno;
  free(a);
  free(b);
  return -err;
}

/*
 * Make a directory's entries durable: open it read-only (O_DIRECTORY,
 * O_CLOEXEC), fsync, close. 0, or the -errno of the first step that failed
 * (an fsync failure is reported even though the close then succeeds).
 */
JOLT_FILE_EXPORT int64_t jolt_file_sync_dir(const uint8_t *path, int64_t n) {
  int64_t fd = open_flags(path, n, O_RDONLY | O_DIRECTORY | O_CLOEXEC, 0);
  if (fd < 0) return fd;
  int err = fsync((int)fd) == 0 ? 0 : errno;
  int cerr = close((int)fd) == 0 ? 0 : errno;
  return -(err ? err : cerr);
}
