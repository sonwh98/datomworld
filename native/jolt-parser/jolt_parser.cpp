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

}  // namespace

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
