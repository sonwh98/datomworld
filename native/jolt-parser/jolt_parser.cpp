// jolt-parser: the extern "C" boundary between jolt and the ANTLR4 C++
// Python3 parser (docs/design/yin.repl-jolt-implementation.md §7.3).
//
// J0c round 1 probe surface. Bytes in, bytes out: the caller passes UTF-8
// source by pointer and length and receives one private marshalling buffer,
// which it copies and returns through jolt_parser_free. No C++ class, layout
// or exception crosses this boundary; every entry catches std::exception and
// (...) and answers an explicit status. This is process-local marshalling (like
// a sockaddr), not a value codec: it never enters a stream. Packets are built
// by the shared jolt/Clojure exporter from these records, never here.
//
// Buffer layout, all integers little-endian:
//
//   magic   "JPT1"
//   u32     layout version (1)
//   u32     status (0 ok, 1 syntax errors)
//   u32     rule-name count R,     then R  x (u32 len, UTF-8 bytes)
//   u32     symbolic-name count S, then S  x (u32 len, UTF-8 bytes)
//   u32     node count N, then N preorder records:
//             u8  kind (0 rule, 1 token)
//             rule:  u32 rule index, u32 child count
//             token: i32 token type, u32 start, u32 stop (code-point indexes,
//                    0xFFFFFFFF = none), u32 text len, UTF-8 text
//   u32     error count E, then E x (u32 line, u32 column, u32 len, UTF-8 msg)
//
// Status codes returned by jolt_parser_parse:
//    0  parsed, buffer written (status field says whether errors occurred)
//   -1  invalid arguments
//   -2  C++ exception (std::exception); *out holds its message, UTF-8
//   -3  unknown exception
// On -2/-3 the message buffer is built without anything that can throw
// (strlen/malloc/memcpy only); if even that malloc fails, *out stays NULL and
// only the status reports the failure. No handler can throw.
//
//   -4  output would exceed the bound (64 MiB), checked while the buffer
//       grows, so an oversize answer is never built in full
//   -5  the worker is in use by another call (a worker serves one thread at
//       a time; this call did nothing)
//   -6  input over budget: more than kMaxInputBytes (4 MiB) of source; *out
//       holds "input <measured> <bound>" (J5, known-gaps row 59)
//   -7  nesting over budget: the prescan's worst simultaneous nesting is
//       over the depth bound for this thread; *out holds
//       "nesting <measured> <bound>"
// -6 and -7 are decided before any ANTLR object exists: no lexer, parser or
// buffer is built for a refused input, so no partial answer can escape.
//
// THE BUDGET (J5, glm-5.3 ruling archive/jolt/1793096000000-architect-mob-row59-j5-scope.glm-5.3.findings.md §2.1). Recursive descent
// over the pinned grammar uses native stack per nesting level, and stack
// exhaustion is uncatchable (§7.6), so the input is refused before parsing
// when it could nest deeper than the stack allows. The prescan
// (nesting_prescan) is one flat O(bytes) pass, O(1) space (the indent stack is
// bounded), no recursion and no allocation. It charges every recursive
// carrier the pinned Python3 grammar has as one nesting level, at the worst
// per-level frame cost measured (the parens/call shapes, about 1 KiB a level
// on the 512 KiB macOS jolt thread: crashes at 512/544 and 544/576, r5 stack
// table), so cheaper shapes are over-refused before expensive ones can run
// out of stack. The carriers (nesting_prescan; also in spec §7.6):
//   bracket   ( [ {                      open depth
//   unary     - + ~ not                  a run in operand position
//   power     **                         per logical line
//   ternary   if (inside an expression)  per logical line
//   lambda    lambda                     per logical line
//   indent    INDENT                     counted from leading whitespace by
//                                        the lexer helper's rule (tabs to 8),
//                                        never inferred from input size
// Their sum at each point is the nesting measured; its maximum over the
// input is compared with the depth bound. kMaxDepth (256) is the 2x margin
// under the shallowest crash bracket on the smallest stack a jolt parse runs
// on (512 KiB, the derivation basis kDepthBasisStack); a thread with a
// smaller stack clamps the bound down in proportion (thread_stack_size), and
// the clamp never raises it. Time is bounded by these two axes, not
// interrupted: no cancellation of an under-bound parse is offered.
//
// jolt_parser_parse (J0c round 1) recognizes with the generated static
// caches. jolt_parser_export takes a worker (§7.4, round 4): an opaque handle
// from jolt_parser_worker_new that owns its lexer and parser DFA vectors and
// PredictionContextCaches; each call installs fresh Lexer/ParserATNSimulators
// over them in place of the generated defaults (which point at the static
// caches) before recognition. The ATNs are the generated tables and stay
// shared; the memos the runtime would fill in them lazily are filled once,
// before the first worker exists (published_atns), so recognition only reads
// them (round 5). jolt_parser_worker_free disposes the handle.
//
// TEST SEAMS, compiled only with -DJOLT_PARSER_TEST_SEAMS=ON (CMake option,
// default OFF): jolt_parser_inject_fault makes the next parses fail
// deterministically so the containment paths can be shown without
// exhausting memory; jolt_parser_static_cache_stats and
// jolt_parser_worker_cache_stats count cache contents;
// jolt_parser_thread_info reports the calling thread's stack;
// jolt_parser_heap_in_use reports malloc's bytes in use.
//
// jolt_parser_export answers the records the jolt yang.antlr.cst adapter
// hands to the shared exporter (§7.3). Indexes are i32, -1 for ANTLR's
// "none" (INVALID_INDEX, a stop before a zero-width start), as the JVM
// reports them. Its layout (version from jolt_parser_export_layout_version):
//
//   magic   "JPX1"
//   u32     layout version (2; 2 added the offending token type)
//   u32     status (0 ok, 1 syntax errors)
//   u32     rule-name count R,     then R x (u32 len, UTF-8 bytes)
//   u32     symbolic-name count S, then S x (u32 len, UTF-8 bytes; "" none)
//   u32     literal-name count S,  then S x (u32 len, UTF-8 bytes; "" none)
//   u32     node count N, then N preorder records:
//             u8  kind (0 rule, 1 token)
//             rule:  u32 rule index, u32 child count,
//                    u8 has-start, i32 context start (start token's start)
//             token: i32 type, i32 start, i32 stop, i32 token index,
//                    u32 text len, UTF-8 text
//   u32     error count E, then E x
//             u32 line, u32 column, u32 len, UTF-8 message,
//             u8 span kind (0 none, 1 offending token, 2 lexer),
//             i32 a, i32 b (token start/stop, or lexer token-start char
//             index / input index when the error was reported),
//             i32 offending token type (-2 when there is no token)
//   first-failure evidence (the first error reported, lexer or parser):
//             u8 kind (0 none, 1 lexical, 2 token), u8 no-viable-alt,
//             i32 offending token index, u32 tokens buffered at the report
//   u32     buffered token count T after the parse, then T x
//             (i32 type, i32 token index, i32 start, i32 stop)

#include <atomic>
#include <cstdio>
#include <cstdint>
#include <cstdlib>
#include <cstring>
#include <limits>
#include <mutex>
#include <string>
#include <string_view>
#include <vector>

#include "antlr4-runtime.h"
#include "support/Utf8.h"
#include "Python3Lexer.h"
#include "Python3Parser.h"

// After the generated headers: Mach's headers define TRUE/FALSE, which are
// also generated token names.
#include <pthread.h>
#if defined(__APPLE__)
#include <malloc/malloc.h>
#elif defined(__GLIBC__)
#include <malloc.h>
#include <sys/syscall.h>
#include <unistd.h>
#endif

#define JOLT_EXPORT extern "C" __attribute__((visibility("default")))

namespace {

constexpr size_t kMaxOutput = 64u * 1024u * 1024u;

#ifdef JOLT_PARSER_TEST_SEAMS
// 0 off; 1 throw std::bad_alloc inside the parse; 2 throw a non-std
// exception; 3 as 1, and the failure handler's malloc fails too.
std::atomic<int32_t> g_fault{0};

void maybe_fault() {
  switch (g_fault.load()) {
    case 1:
    case 3: throw std::bad_alloc();
    case 2: throw 42;
    default: break;
  }
}

bool fail_handler_malloc() { return g_fault.load() == 3; }
#else
void maybe_fault() {}
bool fail_handler_malloc() { return false; }
#endif

// Thrown (and caught by the entries) when the buffer would pass kMaxOutput.
struct Oversize {};

struct Out {
  std::vector<uint8_t> buf;
  void room(size_t n) {
    if (n > kMaxOutput - buf.size()) throw Oversize{};
  }
  void u8(uint8_t v) {
    room(1);
    buf.push_back(v);
  }
  void u32(uint32_t v) {
    room(4);
    for (int i = 0; i < 4; ++i) buf.push_back(static_cast<uint8_t>(v >> (8 * i)));
  }
  void str(std::string_view s) {
    u32(static_cast<uint32_t>(s.size()));
    room(s.size());
    buf.insert(buf.end(), s.begin(), s.end());
  }
};

uint32_t idx(size_t i) {
  return i > std::numeric_limits<uint32_t>::max()
             ? std::numeric_limits<uint32_t>::max()
             : static_cast<uint32_t>(i);
}

struct Error {
  uint32_t line, column;
  std::string msg;
};

class Collect : public antlr4::BaseErrorListener {
 public:
  std::vector<Error> errors;
  void syntaxError(antlr4::Recognizer*, antlr4::Token*, size_t line,
                   size_t column, const std::string& msg,
                   std::exception_ptr) override {
    errors.push_back({idx(line), idx(column), msg});
  }
};

void walk(antlr4::tree::ParseTree* t, Out& out, uint32_t& count) {
  ++count;
  if (auto* term = dynamic_cast<antlr4::tree::TerminalNode*>(t)) {
    antlr4::Token* tok = term->getSymbol();
    out.u8(1);
    out.u32(static_cast<uint32_t>(static_cast<int32_t>(tok->getType())));
    out.u32(idx(tok->getStartIndex()));
    out.u32(idx(tok->getStopIndex()));
    out.str(tok->getText());
    return;
  }
  auto* ctx = dynamic_cast<antlr4::ParserRuleContext*>(t);
  out.u8(0);
  out.u32(idx(ctx->getRuleIndex()));
  out.u32(idx(ctx->children.size()));
  for (auto* c : ctx->children) walk(c, out, count);
}

int32_t hand_off(const std::vector<uint8_t>& bytes, uint8_t** out,
                 size_t* out_len) {
  if (bytes.size() > kMaxOutput) return -4;
  auto* p = static_cast<uint8_t*>(std::malloc(bytes.empty() ? 1 : bytes.size()));
  if (!p) return -3;
  if (!bytes.empty()) std::memcpy(p, bytes.data(), bytes.size());
  *out = p;
  *out_len = bytes.size();
  return 0;
}

// The failure answer: `status`, plus the message copied into a malloc'd
// buffer when that is possible. Nonallocating apart from that one malloc,
// whose failure leaves *out NULL. Never throws.
int32_t message(const char* what, int32_t status, uint8_t** out,
                size_t* out_len) noexcept {
  *out = nullptr;
  *out_len = 0;
  const char* m = what ? what : "";
  size_t n = std::strlen(m);
  void* p = fail_handler_malloc() ? nullptr : std::malloc(n == 0 ? 1 : n);
  if (p) {
    std::memcpy(p, m, n);
    *out = static_cast<uint8_t*>(p);
    *out_len = n;
  }
  return status;
}

// --- the budget (J5) ----------------------------------------------------------

constexpr size_t kMaxInputBytes = size_t{4} << 20;  // 4 MiB
constexpr uint64_t kMaxDepth = 256;
constexpr uint64_t kDepthBasisStack = uint64_t{512} << 10;  // 512 KiB
constexpr int kIndentSlots = 1024;  // > any admitted indent depth

// The calling thread's stack size in bytes, 0 when it cannot be read. The
// production read of what the jolt_parser_thread_info test seam reports.
uint64_t thread_stack_size() noexcept {
#if defined(__APPLE__)
  return static_cast<uint64_t>(pthread_get_stacksize_np(pthread_self()));
#elif defined(__GLIBC__)
  pthread_attr_t attr;
  if (pthread_getattr_np(pthread_self(), &attr) != 0) return 0;
  void* low = nullptr;
  size_t size = 0;
  pthread_attr_getstack(&attr, &low, &size);
  pthread_attr_destroy(&attr);
  return static_cast<uint64_t>(size);
#else
  return 0;
#endif
}

// The depth bound on this thread: kMaxDepth, clamped down in proportion when
// the stack is smaller than the derivation basis (an unreadable stack keeps
// the bound). Never above kMaxDepth.
uint64_t depth_bound() noexcept {
  uint64_t stack = thread_stack_size();
  if (stack == 0 || stack >= kDepthBasisStack) return kMaxDepth;
  return (kMaxDepth * stack) / kDepthBasisStack;
}

bool ident_char(uint8_t c) noexcept {
  return c == '_' || (c >= 'a' && c <= 'z') || (c >= 'A' && c <= 'Z') ||
         (c >= '0' && c <= '9') || c >= 0x80;
}

// The worst simultaneous nesting of `src`, as described in the header.
// Strings and comments are skipped; a string or bracket left open at the end
// is measured as far as it got.
uint64_t nesting_prescan(const uint8_t* src, size_t len) noexcept {
  int indents[kIndentSlots];
  int indent_top = 0;
  indents[0] = 0;
  uint64_t brackets = 0, unary = 0, power = 0, ternary = 0, lambda = 0;
  uint64_t worst = 0;
  bool line_start = true;     // at the start of a logical line
  bool operand_next = true;   // an operand is expected (unary position)
  size_t i = 0;
  auto charge = [&]() {
    uint64_t now = static_cast<uint64_t>(indent_top) + brackets + unary +
                   power + ternary + lambda;
    if (now > worst) worst = now;
  };
  while (i < len) {
    if (line_start && brackets == 0) {
      // leading whitespace of a logical line: the INDENT rule
      int width = 0;
      size_t j = i;
      while (j < len && (src[j] == ' ' || src[j] == '\t' || src[j] == '\f')) {
        width = src[j] == '\t' ? (width / 8 + 1) * 8
                                : (src[j] == ' ' ? width + 1 : 0);
        ++j;
      }
      bool blank = j >= len || src[j] == '\n' || src[j] == '\r' ||
                   src[j] == '#';
      if (!blank) {
        if (width > indents[indent_top]) {
          // deeper than any admitted bound: the measurement is at least
          // the slot count, which refuses
          if (indent_top + 1 >= kIndentSlots)
            return worst > static_cast<uint64_t>(kIndentSlots)
                       ? worst
                       : static_cast<uint64_t>(kIndentSlots);
          indents[++indent_top] = width;
        } else {
          while (indent_top > 0 && width < indents[indent_top]) --indent_top;
        }
        power = ternary = lambda = 0;
        unary = 0;
        operand_next = true;
        charge();
      }
      i = j;
      line_start = false;  // a blank line's newline restores it below
      continue;
    }
    uint8_t c = src[i];
    if (c == '#') {
      while (i < len && src[i] != '\n') ++i;
      continue;
    }
    if (c == '\\' && i + 1 < len && (src[i + 1] == '\n' || src[i + 1] == '\r')) {
      i += 2;  // explicit line joining
      if (i < len && src[i - 1] == '\r' && src[i] == '\n') ++i;
      continue;
    }
    if (c == '\n' || c == '\r') {
      ++i;
      if (brackets == 0) line_start = true;
      continue;
    }
    if (c == '"' || c == '\'') {
      // a string literal (prefixes are identifier characters, already
      // passed): skip it, triple-quoted or not
      bool triple = i + 2 < len && src[i + 1] == c && src[i + 2] == c;
      i += triple ? 3 : 1;
      while (i < len) {
        if (src[i] == '\\') { i += 2; continue; }
        if (src[i] == c) {
          if (!triple) { ++i; break; }
          if (i + 2 < len && src[i + 1] == c && src[i + 2] == c) { i += 3; break; }
        }
        if (!triple && (src[i] == '\n' || src[i] == '\r')) break;
        ++i;
      }
      unary = 0;
      operand_next = false;
      continue;
    }
    if (c == ' ' || c == '\t' || c == '\f') { ++i; continue; }
    if (c == '(' || c == '[' || c == '{') {
      ++brackets;
      operand_next = true;
      charge();
      ++i;
      continue;
    }
    if (c == ')' || c == ']' || c == '}') {
      if (brackets > 0) --brackets;
      unary = 0;
      operand_next = false;
      ++i;
      continue;
    }
    if (c == '*' && i + 1 < len && src[i + 1] == '*') {
      ++power;
      operand_next = true;
      charge();
      i += 2;
      continue;
    }
    if ((c == '-' || c == '+' || c == '~') && operand_next) {
      ++unary;
      charge();
      ++i;
      continue;
    }
    if (ident_char(c)) {
      size_t j = i;
      while (j < len && ident_char(src[j])) ++j;
      size_t n = j - i;
      auto is = [&](const char* w) {
        size_t k = std::strlen(w);
        return n == k && std::memcmp(src + i, w, k) == 0;
      };
      if (is("not") && operand_next) {
        ++unary;
        charge();
      } else if (is("lambda")) {
        ++lambda;
        operand_next = true;
        charge();
      } else if (is("if") && !operand_next) {
        // `if` after an operand is a conditional expression
        ++ternary;
        operand_next = true;
        charge();
      } else if (is("if") || is("else") || is("and") || is("or") ||
                 is("in") || is("is") || is("for") || is("return") ||
                 is("yield") || is("await") || is("assert") || is("del") ||
                 is("elif") || is("while") || is("with") || is("from") ||
                 is("import") || is("raise") || is("as") || is("print")) {
        operand_next = true;
      } else {
        // an operand: a unary run applies to it and ends here
        unary = 0;
        operand_next = false;
      }
      i = j;
      continue;
    }
    // any other operator or delimiter: an operand follows it, except after
    // a closing delimiter (handled above)
    if (c == '.' && i + 1 < len && src[i + 1] >= '0' && src[i + 1] <= '9') {
      unary = 0;
      operand_next = false;
      ++i;
      continue;
    }
    operand_next = true;
    ++i;
  }
  return worst;
}

// The refusal for an input over either budget, or 0 when it is within both.
// Writes the message "input|nesting <measured> <bound>" without throwing.
int32_t over_budget(const uint8_t* src, size_t len, uint8_t** out,
                    size_t* out_len) noexcept {
  char text[96];
  if (len > kMaxInputBytes) {
    std::snprintf(text, sizeof text, "input %zu %zu", len, kMaxInputBytes);
    return message(text, -6, out, out_len);
  }
  uint64_t bound = depth_bound();
  uint64_t measured = nesting_prescan(src, len);
  if (measured > bound) {
    std::snprintf(text, sizeof text, "nesting %llu %llu",
                  static_cast<unsigned long long>(measured),
                  static_cast<unsigned long long>(bound));
    return message(text, -7, out, out_len);
  }
  return 0;
}

// --- jolt_parser_export ------------------------------------------------------

int32_t sidx(size_t i) {
  if (i == std::numeric_limits<size_t>::max()) return -1;  // INVALID_INDEX
  return i > static_cast<size_t>(std::numeric_limits<int32_t>::max())
             ? std::numeric_limits<int32_t>::max()
             : static_cast<int32_t>(i);
}

struct ExportError {
  uint32_t line, column;
  std::string msg;
  uint8_t span_kind;
  int32_t a, b;
  int32_t type;
};

class ExportCollect : public antlr4::BaseErrorListener {
 public:
  explicit ExportCollect(antlr4::CommonTokenStream* tokens) : tokens_(tokens) {}
  std::vector<ExportError> errors;
  uint8_t failure_kind = 0;  // 0 none, 1 lexical, 2 token
  uint8_t no_viable = 0;
  int32_t failure_index = -1;
  uint32_t buffered = 0;

  void syntaxError(antlr4::Recognizer* recognizer, antlr4::Token* offending,
                   size_t line, size_t column, const std::string& msg,
                   std::exception_ptr e) override {
    ExportError err{idx(line), idx(column), msg, 0, -1, -1, -2};
    auto* lexer = dynamic_cast<antlr4::Lexer*>(recognizer);
    if (offending) {
      err.span_kind = 1;
      err.a = sidx(offending->getStartIndex());
      err.b = sidx(offending->getStopIndex());
      err.type = static_cast<int32_t>(offending->getType());
    } else if (lexer) {
      err.span_kind = 2;
      err.a = sidx(lexer->tokenStartCharIndex);
      err.b = sidx(lexer->getInputStream()->index());
    }
    if (failure_kind == 0) {
      if (!offending) {
        failure_kind = 1;
      } else {
        failure_kind = 2;
        failure_index = sidx(offending->getTokenIndex());
        if (e) {
          try {
            std::rethrow_exception(e);
          } catch (const antlr4::NoViableAltException&) {
            no_viable = 1;
          } catch (...) {
          }
        }
      }
      buffered = idx(tokens_->getTokens().size());
    }
    errors.push_back(std::move(err));
  }

 private:
  antlr4::CommonTokenStream* tokens_;
};

void walk_export(antlr4::tree::ParseTree* t, Out& out, uint32_t& count) {
  ++count;
  if (auto* term = dynamic_cast<antlr4::tree::TerminalNode*>(t)) {
    antlr4::Token* tok = term->getSymbol();
    out.u8(1);
    out.u32(static_cast<uint32_t>(static_cast<int32_t>(tok->getType())));
    out.u32(static_cast<uint32_t>(sidx(tok->getStartIndex())));
    out.u32(static_cast<uint32_t>(sidx(tok->getStopIndex())));
    out.u32(static_cast<uint32_t>(sidx(tok->getTokenIndex())));
    out.str(tok->getText());
    return;
  }
  auto* ctx = dynamic_cast<antlr4::ParserRuleContext*>(t);
  out.u8(0);
  out.u32(idx(ctx->getRuleIndex()));
  out.u32(idx(ctx->children.size()));
  out.u8(ctx->start ? 1 : 0);
  out.u32(static_cast<uint32_t>(ctx->start ? sidx(ctx->start->getStartIndex()) : -1));
  for (auto* c : ctx->children) walk_export(c, out, count);
}

// The source as code points, exactly: a strict UTF-8 decode that throws on an
// illegal sequence (as ANTLRInputStream's own does) but keeps a leading
// U+FEFF. ANTLRInputStream::load strips a UTF-8 BOM
// (runtime/src/ANTLRInputStream.cpp:43-48); the JVM's CharStreams.fromString
// does not, and a stripped BOM shifts every index against the source the
// exporter maps spans onto (J0c round 4 input policy).
class SourceStream : public antlr4::ANTLRInputStream {
 public:
  explicit SourceStream(std::string_view utf8) {
    auto cps = antlrcpp::Utf8::strictDecode(utf8);
    if (!cps.has_value())
      throw antlr4::IllegalArgumentException(
          "UTF-8 string contains an illegal byte sequence");
    _data = std::move(cps).value();
    p = 0;
  }
};

// --- workers (§7.4) -----------------------------------------------------------

constexpr uint64_t kWorkerTag = 0x4a504152534552ull;  // "JPARSER"

std::vector<antlr4::dfa::DFA> fresh_dfa(const antlr4::atn::ATN& atn) {
  std::vector<antlr4::dfa::DFA> dfa;
  const size_t n = atn.getNumberOfDecisions();
  dfa.reserve(n);
  for (size_t i = 0; i < n; ++i) dfa.emplace_back(atn.getDecisionState(i), i);
  return dfa;
}

// The generated ATNs (static data initialized once by the generated
// constructors), shared by every worker.
struct Atns {
  const antlr4::atn::ATN* lexer;
  const antlr4::atn::ATN* parser;
};

// The ATNs, published only after every memo the runtime would otherwise fill
// lazily in shared state during recognition has been filled (§7.4: no shared
// mutable cache):
//   * ATNState::_nextTokenWithinRule, set by ATN::nextTokens(s) (ATN.cpp:40-49)
//     for every state of both ATNs;
//   * LexerAction::_hashCode (LexerAction.cpp:5-15) for every action the
//     lexer ATN owns, hashed when the lexer builds an action executor;
//   * the hash of the process-wide PredictionContext::EMPTY
//     (PredictionContext.cpp:157-167).
// Each is a pure function of immutable data, so filling it early changes no
// result. std::call_once runs this once and makes it happen-before every
// return, so no worker (and no round-1 parse) exists until it has finished;
// after it, recognition only reads these. A throw leaves the flag unset and
// the next call retries.
Atns published_atns() {
  static Atns atns{nullptr, nullptr};
  static std::once_flag once;
  std::call_once(once, [] {
    antlr4::ANTLRInputStream input("");
    Python3Lexer lexer(&input);
    antlr4::CommonTokenStream tokens(&lexer);
    Python3Parser parser(&tokens);
    const antlr4::atn::ATN& l = lexer.getATN();
    const antlr4::atn::ATN& p = parser.getATN();
    for (auto* s : l.states)
      if (s) l.nextTokens(s);
    for (auto* s : p.states)
      if (s) p.nextTokens(s);
    for (const auto& a : l.lexerActions)
      if (a) a->hashCode();
    antlr4::atn::PredictionContext::EMPTY->hashCode();
    atns = {&l, &p};
  });
  return atns;
}

struct Worker {
  uint64_t tag = kWorkerTag;
  const antlr4::atn::ATN* lexer_atn;
  const antlr4::atn::ATN* parser_atn;
  std::vector<antlr4::dfa::DFA> lexer_dfa;
  std::vector<antlr4::dfa::DFA> parser_dfa;
  antlr4::atn::PredictionContextCache lexer_ctx;
  antlr4::atn::PredictionContextCache parser_ctx;
  std::atomic<bool> busy{false};

  explicit Worker(Atns atns)
      : lexer_atn(atns.lexer),
        parser_atn(atns.parser),
        lexer_dfa(fresh_dfa(*atns.lexer)),
        parser_dfa(fresh_dfa(*atns.parser)) {}
};

Worker* as_worker(void* w) {
  auto* worker = static_cast<Worker*>(w);
  return worker && worker->tag == kWorkerTag ? worker : nullptr;
}

// Holds a worker for one call; a second concurrent call is refused.
struct Hold {
  Worker* w;
  bool held;
  explicit Hold(Worker* worker) : w(worker) {
    bool expected = false;
    held = w->busy.compare_exchange_strong(expected, true);
  }
  ~Hold() {
    if (held) w->busy.store(false);
  }
};

}  // namespace

// A new worker owning fresh DFA vectors and PredictionContextCaches. NULL when
// it cannot be made (nothing throws across the boundary).
JOLT_EXPORT void* jolt_parser_worker_new(void) {
  try {
    return new Worker(published_atns());
  } catch (...) {
    return nullptr;
  }
}

// Dispose a worker and its caches. NULL is accepted. The caller must not use
// the handle again, nor free it while a call on it runs.
JOLT_EXPORT void jolt_parser_worker_free(void* w) {
  if (Worker* worker = as_worker(w)) {
    worker->tag = 0;
    delete worker;
  }
}

// Parse UTF-8 `src[0, len)` from `file_input` with `worker`'s caches and
// answer the exporter's records (layout "JPX1" above). Statuses as
// jolt_parser_parse, plus -5; -1 also for a NULL or unrecognized worker.
JOLT_EXPORT int32_t jolt_parser_export(void* worker, const uint8_t* src,
                                       size_t len, uint8_t** out,
                                       size_t* out_len) {
  if (!out || !out_len || (!src && len != 0)) return -1;
  *out = nullptr;
  *out_len = 0;
  Worker* w = as_worker(worker);
  if (!w) return -1;
  if (int32_t refused = over_budget(src, len, out, out_len)) return refused;
  Hold hold(w);
  if (!hold.held) return -5;
  try {
    maybe_fault();
    SourceStream input(
        std::string_view(reinterpret_cast<const char*>(src), len));
    Python3Lexer lexer(&input);
    // setInterpreter deletes the generated default simulator, the one over
    // the static caches; the generated destructors delete these at scope exit
    lexer.setInterpreter(new antlr4::atn::LexerATNSimulator(
        &lexer, *w->lexer_atn, w->lexer_dfa, w->lexer_ctx));
    antlr4::CommonTokenStream tokens(&lexer);
    Python3Parser parser(&tokens);
    parser.setInterpreter(new antlr4::atn::ParserATNSimulator(
        &parser, *w->parser_atn, w->parser_dfa, w->parser_ctx));
    ExportCollect errors(&tokens);
    lexer.removeErrorListeners();
    parser.removeErrorListeners();
    lexer.addErrorListener(&errors);
    parser.addErrorListener(&errors);

    antlr4::tree::ParseTree* tree = parser.file_input();

    Out o;
    o.buf.insert(o.buf.end(), {'J', 'P', 'X', '1'});
    o.u32(2);
    o.u32(errors.errors.empty() ? 0 : 1);
    const auto& rules = parser.getRuleNames();
    o.u32(idx(rules.size()));
    for (const auto& r : rules) o.str(r);
    const auto& vocab = parser.getVocabulary();
    size_t max_type = vocab.getMaxTokenType();
    o.u32(idx(max_type + 1));
    for (size_t i = 0; i <= max_type; ++i) o.str(vocab.getSymbolicName(i));
    o.u32(idx(max_type + 1));
    for (size_t i = 0; i <= max_type; ++i) o.str(vocab.getLiteralName(i));
    size_t count_at = o.buf.size();
    o.u32(0);
    uint32_t count = 0;
    walk_export(tree, o, count);
    for (int i = 0; i < 4; ++i)
      o.buf[count_at + i] = static_cast<uint8_t>(count >> (8 * i));
    o.u32(idx(errors.errors.size()));
    for (const auto& e : errors.errors) {
      o.u32(e.line);
      o.u32(e.column);
      o.str(e.msg);
      o.u8(e.span_kind);
      o.u32(static_cast<uint32_t>(e.a));
      o.u32(static_cast<uint32_t>(e.b));
      o.u32(static_cast<uint32_t>(e.type));
    }
    o.u8(errors.failure_kind);
    o.u8(errors.no_viable);
    o.u32(static_cast<uint32_t>(errors.failure_index));
    o.u32(errors.buffered);
    const auto buffered = tokens.getTokens();
    o.u32(idx(buffered.size()));
    for (auto* t : buffered) {
      o.u32(static_cast<uint32_t>(static_cast<int32_t>(t->getType())));
      o.u32(static_cast<uint32_t>(sidx(t->getTokenIndex())));
      o.u32(static_cast<uint32_t>(sidx(t->getStartIndex())));
      o.u32(static_cast<uint32_t>(sidx(t->getStopIndex())));
    }
    return hand_off(o.buf, out, out_len);
  } catch (const Oversize&) {
    return -4;
  } catch (const std::exception& e) {
    return message(e.what(), -2, out, out_len);
  } catch (...) {
    return message("unknown exception", -3, out, out_len);
  }
}

// Parse UTF-8 `src[0, len)` from the `file_input` rule, with the generated
// static caches (the round-1 probe surface).
JOLT_EXPORT int32_t jolt_parser_parse(const uint8_t* src, size_t len,
                                      uint8_t** out, size_t* out_len) {
  if (!out || !out_len || (!src && len != 0)) return -1;
  *out = nullptr;
  *out_len = 0;
  if (int32_t refused = over_budget(src, len, out, out_len)) return refused;
  try {
    published_atns();
    maybe_fault();
    SourceStream input(
        std::string_view(reinterpret_cast<const char*>(src), len));
    Python3Lexer lexer(&input);
    antlr4::CommonTokenStream tokens(&lexer);
    Python3Parser parser(&tokens);
    Collect errors;
    lexer.removeErrorListeners();
    parser.removeErrorListeners();
    lexer.addErrorListener(&errors);
    parser.addErrorListener(&errors);

    antlr4::tree::ParseTree* tree = parser.file_input();

    Out o;
    o.buf.insert(o.buf.end(), {'J', 'P', 'T', '1'});
    o.u32(1);
    o.u32(errors.errors.empty() ? 0 : 1);
    const auto& rules = parser.getRuleNames();
    o.u32(idx(rules.size()));
    for (const auto& r : rules) o.str(r);
    const auto& vocab = parser.getVocabulary();
    size_t max_type = vocab.getMaxTokenType();
    o.u32(idx(max_type + 1));
    for (size_t i = 0; i <= max_type; ++i) o.str(vocab.getSymbolicName(i));
    size_t count_at = o.buf.size();
    o.u32(0);
    uint32_t count = 0;
    walk(tree, o, count);
    for (int i = 0; i < 4; ++i)
      o.buf[count_at + i] = static_cast<uint8_t>(count >> (8 * i));
    o.u32(idx(errors.errors.size()));
    for (const auto& e : errors.errors) {
      o.u32(e.line);
      o.u32(e.column);
      o.str(e.msg);
    }
    return hand_off(o.buf, out, out_len);
  } catch (const Oversize&) {
    return -4;
  } catch (const std::exception& e) {
    return message(e.what(), -2, out, out_len);
  } catch (...) {
    return message("unknown exception", -3, out, out_len);
  }
}

// Release a buffer jolt_parser_parse handed out. NULL is accepted.
JOLT_EXPORT void jolt_parser_free(uint8_t* p) { std::free(p); }

// The JPT1 layout version jolt_parser_parse writes.
JOLT_EXPORT int32_t jolt_parser_layout_version(void) { return 1; }

// The JPX1 layout version jolt_parser_export writes.
JOLT_EXPORT int32_t jolt_parser_export_layout_version(void) { return 2; }

// The budget (J5): out[0] the input bound in bytes, out[1] the depth bound,
// out[2] the depth bound on the calling thread (after the stack clamp),
// out[3] the calling thread's stack size (0 unreadable), out[4] the
// derivation basis stack. Read-only.
JOLT_EXPORT int32_t jolt_parser_budget(uint64_t* out) {
  if (!out) return -1;
  out[0] = kMaxInputBytes;
  out[1] = kMaxDepth;
  out[2] = depth_bound();
  out[3] = thread_stack_size();
  out[4] = kDepthBasisStack;
  return 0;
}

// The prescan alone (J5): the nesting it measures for `src[0, len)`, without
// parsing. Read-only; for the budget gates and the build record.
JOLT_EXPORT uint64_t jolt_parser_nesting(const uint8_t* src, size_t len) {
  if (!src && len != 0) return 0;
  return nesting_prescan(src, len);
}

#ifdef JOLT_PARSER_TEST_SEAMS

// TEST SEAM: set the fault mode (0 off, 1-3 as g_fault above) for later
// parses. Answers the previous mode.
JOLT_EXPORT int32_t jolt_parser_inject_fault(int32_t mode) {
  return g_fault.exchange(mode);
}

namespace {

// Reading two private counters for the stats below, through the standard
// explicit-instantiation access idiom (test seam only; reads, never writes).
template <auto M, typename Tag>
struct Peek {
  friend auto peek(Tag) { return M; }
};
struct CtxData {};
struct NextUpdated {};
struct ActionHash {};
struct ContextHash {};
auto peek(CtxData);
auto peek(NextUpdated);
auto peek(ActionHash);
auto peek(ContextHash);
template struct Peek<&antlr4::atn::PredictionContextCache::_data, CtxData>;
template struct Peek<&antlr4::atn::ATNState::_nextTokenUpdated, NextUpdated>;
template struct Peek<&antlr4::atn::LexerAction::_hashCode, ActionHash>;
template struct Peek<&antlr4::atn::PredictionContext::_hashCode, ContextHash>;

void dfa_counts(const std::vector<antlr4::dfa::DFA>& dfas, uint64_t& states,
                uint64_t& edges) {
  for (const auto& d : dfas) {
    states += d.states.size() + (d.s0 ? 1 : 0);
    if (d.s0) edges += d.s0->edges.size();
    for (const auto* st : d.states) edges += st->edges.size();
  }
}

uint64_t ctx_size(const antlr4::atn::PredictionContextCache& c) {
  return (c.*peek(CtxData{})).size();
}

uint64_t memo_count(const antlr4::atn::ATN& atn) {
  uint64_t n = 0;
  for (auto* st : atn.states)
    if (st && (st->*peek(NextUpdated{})).load()) ++n;
  return n;
}

uint64_t state_count(const antlr4::atn::ATN& atn) {
  uint64_t n = 0;
  for (auto* st : atn.states)
    if (st) ++n;
  return n;
}

uint64_t hashed_actions(const antlr4::atn::ATN& atn) {
  uint64_t n = 0;
  for (const auto& a : atn.lexerActions)
    if (a && (a.get()->*peek(ActionHash{})).load() != 0) ++n;
  return n;
}

}  // namespace

// TEST SEAM: the generated static caches and the shared lazily filled memos
// (published_atns fills the memos). out[0..12] = lexer DFA states, lexer DFA
// edges, lexer context-cache entries, parser DFA states, parser DFA edges,
// parser context-cache entries, lexer ATN states with a filled next-token
// memo, parser ATN states with one, lexer ATN states, parser ATN states,
// lexer actions with a cached hash, lexer actions, and 1 when
// PredictionContext::EMPTY's hash is cached. Reading publishes nothing. Call
// with no parse running.
JOLT_EXPORT int32_t jolt_parser_static_cache_stats(uint64_t* out) {
  if (!out) return -1;
  try {
    antlr4::ANTLRInputStream input("");
    Python3Lexer lexer(&input);
    antlr4::CommonTokenStream tokens(&lexer);
    Python3Parser parser(&tokens);
    auto* ls = lexer.getInterpreter<antlr4::atn::LexerATNSimulator>();
    auto* ps = parser.getInterpreter<antlr4::atn::ParserATNSimulator>();
    for (int i = 0; i < 13; ++i) out[i] = 0;
    dfa_counts(ls->_decisionToDFA, out[0], out[1]);
    out[2] = ctx_size(ls->getSharedContextCache());
    dfa_counts(ps->decisionToDFA, out[3], out[4]);
    out[5] = ctx_size(ps->getSharedContextCache());
    out[6] = memo_count(lexer.getATN());
    out[7] = memo_count(parser.getATN());
    out[8] = state_count(lexer.getATN());
    out[9] = state_count(parser.getATN());
    out[10] = hashed_actions(lexer.getATN());
    out[11] = lexer.getATN().lexerActions.size();
    out[12] = (antlr4::atn::PredictionContext::EMPTY.get()->*peek(ContextHash{})).load() != 0;
    return 0;
  } catch (...) {
    return -3;
  }
}

// TEST SEAM: a worker's own caches, out[0..5] as out[0..5] above.
JOLT_EXPORT int32_t jolt_parser_worker_cache_stats(void* worker, uint64_t* out) {
  Worker* w = as_worker(worker);
  if (!w || !out) return -1;
  for (int i = 0; i < 6; ++i) out[i] = 0;
  dfa_counts(w->lexer_dfa, out[0], out[1]);
  out[2] = ctx_size(w->lexer_ctx);
  dfa_counts(w->parser_dfa, out[3], out[4]);
  out[5] = ctx_size(w->parser_ctx);
  return 0;
}

// TEST SEAM: the calling thread's stack. out[0..4] = stack size in bytes,
// lowest and highest stack address, the current stack position, 1 when it is
// the process's main thread.
JOLT_EXPORT int32_t jolt_parser_thread_info(uint64_t* out) {
  if (!out) return -1;
  volatile char here = 0;
  out[3] = reinterpret_cast<uint64_t>(&here);
#if defined(__APPLE__)
  pthread_t self = pthread_self();
  uint64_t size = pthread_get_stacksize_np(self);
  uint64_t high = reinterpret_cast<uint64_t>(pthread_get_stackaddr_np(self));
  out[0] = size;
  out[1] = high - size;
  out[2] = high;
  out[4] = pthread_main_np() == 1 ? 1 : 0;
  return 0;
#elif defined(__GLIBC__)
  pthread_attr_t attr;
  if (pthread_getattr_np(pthread_self(), &attr) != 0) return -3;
  void* low = nullptr;
  size_t size = 0;
  pthread_attr_getstack(&attr, &low, &size);
  pthread_attr_destroy(&attr);
  out[0] = size;
  out[1] = reinterpret_cast<uint64_t>(low);
  out[2] = reinterpret_cast<uint64_t>(low) + size;
  out[4] = getpid() == static_cast<pid_t>(syscall(SYS_gettid)) ? 1 : 0;
  return 0;
#else
  return -3;
#endif
}

// TEST SEAM: bytes malloc reports in use (all zones on macOS, glibc's
// mallinfo2 on Linux; 0 elsewhere).
JOLT_EXPORT uint64_t jolt_parser_heap_in_use(void) {
#if defined(__APPLE__)
  malloc_statistics_t st;
  malloc_zone_statistics(nullptr, &st);
  return st.size_in_use;
#elif defined(__GLIBC__) && (__GLIBC__ > 2 || (__GLIBC__ == 2 && __GLIBC_MINOR__ >= 33))
  return mallinfo2().uordblks;
#else
  return 0;
#endif
}

#endif  // JOLT_PARSER_TEST_SEAMS
