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
// worker disposed. Invalid arguments and an oversize answer run once.
// Prints a line per check and `HARNESS ok` / `HARNESS FAILED n`.
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

  // once (J5b, C5): the review's repros refuse (-7) and the shapes at their
  // bounds parse, on both entries, under the sanitizers; the same worker
  // parses after every refusal. Sources as src/dev/jolt_j5_shapes.cljc.
  {
    auto rep = [](long n, const std::string& s) {
      std::string r;
      r.reserve(n * s.size());
      for (long i = 0; i < n; ++i) r += s;
      return r;
    };
    const std::string refused[] = {
        "#\r" + rep(100000, "(") + "1" + rep(100000, ")") + "\n",
        "#\f" + rep(100000, "(") + "1" + rep(100000, ")") + "\n",
        "x = " + rep(100000, "1 if 1 else \\ \n") + "1\n",
        "[x" + rep(100000, " for x in x") + "]\n",
        "[x" + rep(50000, " for x in x if x") + "]\n",
        rep(128, rep(128, "not ") + "(0 or ") + "0" + rep(128, ")") + "\n",
        "x = " + rep(100000, "-") + "1\n",
        "x = " + rep(100000, "f(") + "1" + rep(100000, ")") + "\n",
        "x = " + rep(909, "(") + "1" + rep(909, ")") + "\n",
        "f(" + rep(454, "(") + "1" + rep(454, ")") + ")\n",
        "x = " + rep(254, "-") + "1\n",
    };
    std::string indent;
    for (int i = 0; i < 2045; ++i) indent += std::string(i, ' ') + "if x:\n";
    indent += std::string(2045, ' ') + "pass\n";
    const std::string at_bound[] = {
        "x = " + rep(908, "(") + "1" + rep(908, ")") + "\n",
        "x = " + rep(8178, "not ") + "1\n",
        "x = " + rep(253, "-") + "1\n",
        "x = " + rep(408, "f(") + "1" + rep(408, ")") + "\n",
        "f(" + rep(453, "(") + "1" + rep(453, ")") + ")\n",
        "[x" + rep(4085, " for x in x") + "]\n",
        "x = " + rep(4089, "lambda: ") + "1\n",
        indent,
        rep(20000, "1+") + "1\n",
    };
    void* w = jolt_parser_worker_new();
    for (const auto& s : refused) {
      run_export(w, s, -7, "nesting refused (-7)");
      run_export(w, "x * 6\n", 0, "worker parses again after -7");
      run_parse(s, -7, "nesting refused on the static-cache entry (-7)");
    }
    for (const auto& s : at_bound) {
      run_export(w, s, 0, "at the bound parses");
      run_parse(s, 0, "at the bound parses on the static-cache entry");
    }
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
