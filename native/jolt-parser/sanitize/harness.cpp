// jolt-parser standalone lifetime harness (J0c round 4, step D): drives the
// extern "C" surface of jolt_parser.cpp from plain C++, never loaded into
// jolt, so AddressSanitizer/UBSan (or macOS `leaks`) see every allocation the
// wrapper and the ANTLR runtime make. Built by src/dev/jolt_j0c_sanitize.clj.
//
//   jolt_parser_harness [cycles]
//
// Each cycle: a worker is made; it exports an ok source, a syntax-error
// source, a lexer-error source, invalid UTF-8, a BOM source and a NUL source;
// the round-1 entry parses an ok and an error source; the three fault modes
// run on both entries; the stats seams are read; every buffer is freed and the
// worker disposed. Invalid arguments and an oversize answer run once, and
// the J5 stack clamp runs once on a thread with a 128 KiB stack.
// Prints a line per check and `HARNESS ok` / `HARNESS FAILED n`.
#include <pthread.h>

#include <cstdint>
#include <cstdio>
#include <cstdlib>
#include <cstring>
#include <string>

extern "C" {
void* jolt_parser_worker_new(void);
void jolt_parser_worker_free(void*);
int32_t jolt_parser_export(void*, const uint8_t*, size_t, uint8_t**, size_t*);
int32_t jolt_parser_parse(const uint8_t*, size_t, uint8_t**, size_t*);
void jolt_parser_free(uint8_t*);
int32_t jolt_parser_inject_fault(int32_t);
int32_t jolt_parser_static_cache_stats(uint64_t*);
int32_t jolt_parser_worker_cache_stats(void*, uint64_t*);
int32_t jolt_parser_thread_info(uint64_t*);
uint64_t jolt_parser_heap_in_use(void);
int32_t jolt_parser_export_layout_version(void);
int32_t jolt_parser_budget(uint64_t*);
}

namespace {

int failures = 0;

void expect(bool ok, const char* what, int32_t status) {
  if (!ok) {
    ++failures;
    std::printf("FAIL %s (status %d)\n", what, status);
  }
}

// Export `src` with `w`, require `want`, free whatever came back.
void run_export(void* w, const std::string& src, int32_t want, const char* what) {
  uint8_t* out = nullptr;
  size_t len = 0;
  int32_t st = jolt_parser_export(w, reinterpret_cast<const uint8_t*>(src.data()),
                                  src.size(), &out, &len);
  expect(st == want, what, st);
  if (st == 0) expect(len >= 4 && std::memcmp(out, "JPX1", 4) == 0, what, st);
  jolt_parser_free(out);
}

void run_parse(const std::string& src, int32_t want, const char* what) {
  uint8_t* out = nullptr;
  size_t len = 0;
  int32_t st = jolt_parser_parse(reinterpret_cast<const uint8_t*>(src.data()),
                                 src.size(), &out, &len);
  expect(st == want, what, st);
  jolt_parser_free(out);
}

// J5 stack clamp, run on a thread whose stack is a quarter of the 512 KiB
// derivation basis: the depth bound read there is 256 / 4 = 64, nesting 65
// and 200 (fine on a full-size stack) refuse with -7 before any parse, and a
// shallow source still parses. No deep parse runs, so the leg is safe under
// ASan's larger frames.
constexpr size_t kSmallStack = size_t{128} << 10;

void* clamp_leg(void* arg) {
  void* w = arg;
  uint64_t b[5] = {0, 0, 0, 0, 0};
  expect(jolt_parser_budget(b) == 0, "budget on the small-stack thread", 0);
  std::printf("RECORD clamp budget input %llu depth %llu depth-here %llu stack %llu basis %llu\n",
              static_cast<unsigned long long>(b[0]), static_cast<unsigned long long>(b[1]),
              static_cast<unsigned long long>(b[2]), static_cast<unsigned long long>(b[3]),
              static_cast<unsigned long long>(b[4]));
  // the stack read may round the requested size up to a page multiple
  uint64_t want = b[1] * b[3] / b[4];
  expect(b[3] >= kSmallStack && b[3] < b[4] && b[2] == want && b[2] < b[1],
         "the bound clamps down on a small stack (depth-here = 256 * stack / basis)", 0);
  for (uint64_t n : {b[2] + 1, uint64_t{200}}) {
    std::string src = "x = " + std::string(n, '(') + "1" + std::string(n, ')') + "\n";
    uint8_t* out = nullptr;
    size_t len = 0;
    int32_t st = jolt_parser_export(w, reinterpret_cast<const uint8_t*>(src.data()),
                                    src.size(), &out, &len);
    std::string msg = out ? std::string(reinterpret_cast<char*>(out), len) : "";
    std::string expected = "nesting " + std::to_string(n) + " " + std::to_string(b[2]);
    std::printf("RECORD clamp nesting %llu status %d message %s\n",
                static_cast<unsigned long long>(n), st, msg.c_str());
    expect(st == -7 && msg == expected,
           "an over-clamp nesting refuses with -7 on the small-stack thread", st);
    jolt_parser_free(out);
  }
  run_export(w, "x = (1)\n", 0, "a shallow source parses on the small-stack thread");
  return nullptr;
}

}  // namespace

int main(int argc, char** argv) {
  const long cycles = argc > 1 ? std::strtol(argv[1], nullptr, 10) : 200;
  expect(jolt_parser_export_layout_version() == 2, "export layout version", 0);
  const std::string ok = "def f(a):\n    if a:\n        return [a, {1: 'x'}]\n    return 2\nf(3)\n";
  const std::string bad = "def f(:\n    return 1\n";
  const std::string lexbad = "x = $\n";
  const std::string bom = "\xef\xbb\xbfx = 1\n";
  const std::string nul = std::string("s = 'a\0b'\n", 10);
  const std::string utf8bad = "s = '\xff'\n";
  uint64_t stats[13];

  for (long c = 0; c < cycles; ++c) {
    void* w = jolt_parser_worker_new();
    expect(w != nullptr, "worker_new", 0);
    run_export(w, ok, 0, "export ok source");
    run_export(w, bad, 0, "export syntax-error source");
    run_export(w, lexbad, 0, "export lexer-error source");
    run_export(w, bom, 0, "export BOM source");
    run_export(w, nul, 0, "export NUL source");
    run_export(w, utf8bad, -2, "export invalid UTF-8 fails closed");
    run_export(w, "", 0, "export empty source");
    run_parse(ok, 0, "parse ok source (static caches)");
    run_parse(bad, 0, "parse syntax-error source");
    for (int32_t mode = 1; mode <= 3; ++mode) {
      jolt_parser_inject_fault(mode);
      run_export(w, ok, mode == 2 ? -3 : -2, "export under an injected fault");
      run_parse(ok, mode == 2 ? -3 : -2, "parse under an injected fault");
      jolt_parser_inject_fault(0);
    }
    expect(jolt_parser_static_cache_stats(stats) == 0, "static cache stats", 0);
    // every shared memo was filled before the first worker existed
    expect(stats[6] == stats[8] && stats[7] == stats[9] && stats[10] == stats[11] &&
               stats[12] == 1,
           "shared memos published complete", 0);
    expect(jolt_parser_worker_cache_stats(w, stats) == 0, "worker cache stats", 0);
    expect(jolt_parser_thread_info(stats) == 0, "thread info", 0);
    (void)jolt_parser_heap_in_use();
    jolt_parser_worker_free(w);
  }

  // once: invalid arguments and an oversize answer
  {
    void* w = jolt_parser_worker_new();
    uint8_t* out = nullptr;
    size_t len = 0;
    int32_t st = jolt_parser_export(w, nullptr, 3, &out, &len);
    expect(st == -1 && out == nullptr, "NULL source with a length", st);
    st = jolt_parser_export(nullptr, reinterpret_cast<const uint8_t*>("1\n"), 2, &out, &len);
    expect(st == -1 && out == nullptr, "NULL worker", st);
    st = jolt_parser_export(w, reinterpret_cast<const uint8_t*>("1\n"), 2, nullptr, &len);
    expect(st == -1, "NULL out cell", st);
    void* fake = std::calloc(1, 256);
    st = jolt_parser_export(fake, reinterpret_cast<const uint8_t*>("1\n"), 2, &out, &len);
    expect(st == -1 && out == nullptr, "unrecognized worker", st);
    std::free(fake);
    // J5: a source past the 4 MiB input bound is refused before parsing
    std::string huge = "s = '" + std::string(70u * 1024u * 1024u, 'a') + "'\n";
    run_export(w, huge, -6, "over-bound input refused (-6)");
    run_export(w, ok, 0, "worker parses again after -6");
    // a legal source (600 KB, nesting 0) whose answer passes the 64 MiB
    // output bound: about 129 output bytes per input byte of `1\n`
    std::string big;
    for (int i = 0; i < 300000; ++i) big += "1\n";
    run_export(w, big, -4, "oversize answer refused (-4)");
    run_export(w, ok, 0, "worker parses again after -4");
    jolt_parser_worker_free(w);
    jolt_parser_worker_free(nullptr);
  }

  // once: the stack clamp on a 128 KiB thread (the worker is made here and
  // used by that thread alone)
  {
    void* w = jolt_parser_worker_new();
    pthread_attr_t attr;
    pthread_attr_init(&attr);
    expect(pthread_attr_setstacksize(&attr, kSmallStack) == 0, "set a 128 KiB stack", 0);
    pthread_t t;
    int rc = pthread_create(&t, &attr, clamp_leg, w);
    expect(rc == 0, "start the small-stack thread", rc);
    if (rc == 0) pthread_join(t, nullptr);
    pthread_attr_destroy(&attr);
    jolt_parser_worker_free(w);
  }

  std::printf("cycles %ld\n", cycles);
  if (failures == 0) {
    std::printf("HARNESS ok\n");
    return 0;
  }
  std::printf("HARNESS FAILED %d\n", failures);
  return 1;
}
