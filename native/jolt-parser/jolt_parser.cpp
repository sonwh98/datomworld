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
//
// jolt_parser_parse (J0c round 1) recognizes with the generated static
// caches. jolt_parser_export takes a worker (§7.4, round 4): an opaque handle
// from jolt_parser_worker_new that owns its lexer and parser DFA vectors and
// PredictionContextCaches; each call installs fresh Lexer/ParserATNSimulators
// over them in place of the generated defaults (which point at the static
// caches) before recognition. The ATNs are the generated immutable tables and
// stay shared. jolt_parser_worker_free disposes the handle.
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
#include <cstdint>
#include <cstdlib>
#include <cstring>
#include <limits>
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

// The generated, immutable ATNs (static data initialized once by the
// generated constructors).
struct Atns {
  const antlr4::atn::ATN* lexer;
  const antlr4::atn::ATN* parser;
};

Atns generated_atns() {
  antlr4::ANTLRInputStream input("");
  Python3Lexer lexer(&input);
  antlr4::CommonTokenStream tokens(&lexer);
  Python3Parser parser(&tokens);
  return {&lexer.getATN(), &parser.getATN()};
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
    return new Worker(generated_atns());
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
  try {
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
auto peek(CtxData);
auto peek(NextUpdated);
template struct Peek<&antlr4::atn::PredictionContextCache::_data, CtxData>;
template struct Peek<&antlr4::atn::ATNState::_nextTokenUpdated, NextUpdated>;

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

}  // namespace

// TEST SEAM: the generated static caches and the shared ATNs' lazily filled
// next-token memo. out[0..7] = lexer DFA states, lexer DFA edges, lexer
// context-cache entries, parser DFA states, parser DFA edges, parser
// context-cache entries, lexer ATN states with a filled next-token memo,
// parser ATN states with one. Call with no parse running.
JOLT_EXPORT int32_t jolt_parser_static_cache_stats(uint64_t* out) {
  if (!out) return -1;
  try {
    antlr4::ANTLRInputStream input("");
    Python3Lexer lexer(&input);
    antlr4::CommonTokenStream tokens(&lexer);
    Python3Parser parser(&tokens);
    auto* ls = lexer.getInterpreter<antlr4::atn::LexerATNSimulator>();
    auto* ps = parser.getInterpreter<antlr4::atn::ParserATNSimulator>();
    for (int i = 0; i < 8; ++i) out[i] = 0;
    dfa_counts(ls->_decisionToDFA, out[0], out[1]);
    out[2] = ctx_size(ls->getSharedContextCache());
    dfa_counts(ps->decisionToDFA, out[3], out[4]);
    out[5] = ctx_size(ps->getSharedContextCache());
    out[6] = memo_count(lexer.getATN());
    out[7] = memo_count(parser.getATN());
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
