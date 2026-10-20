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
//   -4  output would exceed the bound
// On -2/-3 the message buffer is built without anything that can throw
// (strlen/malloc/memcpy only); if even that malloc fails, *out stays NULL and
// only the status reports the failure. No handler can throw.
//
// jolt_parser_inject_fault is a TEST SEAM (off unless called): it makes the
// next parses fail deterministically so the containment paths can be shown
// from jolt without exhausting memory.
//
// jolt_parser_export (J0c round 2) answers the same statuses and failure
// paths with the records the jolt yang.antlr.cst adapter hands to the shared
// exporter (§7.3). Indexes are i32, -1 for ANTLR's "none" (INVALID_INDEX, a
// stop before a zero-width start), as the JVM reports them. Its layout:
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
#include "Python3Lexer.h"
#include "Python3Parser.h"

#define JOLT_EXPORT extern "C" __attribute__((visibility("default")))

namespace {

constexpr size_t kMaxOutput = 64u * 1024u * 1024u;

// 0 off; 1 throw std::bad_alloc inside the parse; 2 throw a non-std
// exception; 3 as 1, and the failure handler's malloc fails too.
std::atomic<int32_t> g_fault{0};

struct Out {
  std::vector<uint8_t> buf;
  void u8(uint8_t v) { buf.push_back(v); }
  void u32(uint32_t v) {
    for (int i = 0; i < 4; ++i) buf.push_back(static_cast<uint8_t>(v >> (8 * i)));
  }
  void str(std::string_view s) {
    u32(static_cast<uint32_t>(s.size()));
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
  void* p = g_fault.load() == 3 ? nullptr : std::malloc(n == 0 ? 1 : n);
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

}  // namespace

// Parse UTF-8 `src[0, len)` from `file_input` and answer the exporter's
// records (layout "JPX1" above). Statuses as jolt_parser_parse.
JOLT_EXPORT int32_t jolt_parser_export(const uint8_t* src, size_t len,
                                       uint8_t** out, size_t* out_len) {
  if (!out || !out_len || (!src && len != 0)) return -1;
  *out = nullptr;
  *out_len = 0;
  try {
    switch (g_fault.load()) {
      case 1:
      case 3: throw std::bad_alloc();
      case 2: throw 42;
      default: break;
    }
    std::string text(reinterpret_cast<const char*>(src), len);
    antlr4::ANTLRInputStream input(text);
    Python3Lexer lexer(&input);
    antlr4::CommonTokenStream tokens(&lexer);
    Python3Parser parser(&tokens);
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
  } catch (const std::exception& e) {
    return message(e.what(), -2, out, out_len);
  } catch (...) {
    return message("unknown exception", -3, out, out_len);
  }
}

// Parse UTF-8 `src[0, len)` from the `file_input` rule.
JOLT_EXPORT int32_t jolt_parser_parse(const uint8_t* src, size_t len,
                                      uint8_t** out, size_t* out_len) {
  if (!out || !out_len || (!src && len != 0)) return -1;
  *out = nullptr;
  *out_len = 0;
  try {
    switch (g_fault.load()) {
      case 1:
      case 3: throw std::bad_alloc();
      case 2: throw 42;
      default: break;
    }
    std::string text(reinterpret_cast<const char*>(src), len);
    antlr4::ANTLRInputStream input(text);
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
  } catch (const std::exception& e) {
    return message(e.what(), -2, out, out_len);
  } catch (...) {
    return message("unknown exception", -3, out, out_len);
  }
}

// Release a buffer jolt_parser_parse handed out. NULL is accepted.
JOLT_EXPORT void jolt_parser_free(uint8_t* p) { std::free(p); }

// TEST SEAM: set the fault mode (0 off, 1-3 as g_fault above) for later
// parses. Answers the previous mode.
JOLT_EXPORT int32_t jolt_parser_inject_fault(int32_t mode) {
  return g_fault.exchange(mode);
}

// The marshalling layout version this library writes.
JOLT_EXPORT int32_t jolt_parser_layout_version(void) { return 1; }
