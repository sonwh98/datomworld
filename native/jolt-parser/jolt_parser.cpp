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
//       holds "input <measured> <bound>" (J5, known-gaps row 59), decided
//       before any ANTLR object exists
//   -7  nesting over budget: a guard inside recognition refused; *out holds
//       "nesting <measured> <bound> <kind>", kind "rule" or "lookahead"
// A -7 is thrown out of recognition before any buffer is built, so no partial
// answer can escape; the lexer, parser and tree are destroyed with the call.
//
// THE BUDGET (J5b, docs/design/yin.repl-jolt-implementation.md §7.6). Stack
// exhaustion is uncatchable, so the nesting that costs native stack is
// measured where it happens, by the parser itself, and refused before it can
// exhaust the parse thread's stack:
//
//   rule       BoundedParser counts the rule frames live on the native stack
//              (every generated rule function enters through the virtual
//              Parser::enterRule or enterRecursionRule, Parser.h:273-290,
//              Python3Parser.cpp:8438/:8879). Parser::exitRule is not virtual
//              (Parser.h:275), so the count is not a counter decremented on
//              exit: the frame depth is the length of the current context's
//              parent chain, which equals the live rule activations because
//              each activation contributes exactly one context to the chain
//              at all times (a left-recursive loop replaces its frame's
//              context and reparents the previous one beneath it; unrolling
//              restores the caller's). The generated parser has no labelled
//              alternatives. The depth is recorded per context at entry
//              (its parent's plus one; a pushNewRecursionContext context
//              inherits its frame's) rather than walked, which measured up
//              to 2x slower at the bounds. Entering a frame deeper than
//              kMaxRule refuses.
//   lookahead  BoundedSimulator bounds prediction's own recursion
//              (closureCheckingStopState pops, PredictionContext merge,
//              equality, caching and destruction all recurse over context
//              chains, ParserATNSimulator.cpp:851-900): on every RULE
//              transition of a closure (getEpsilonTarget,
//              ParserATNSimulator.h:788) the chain the transition makes, the
//              longest parent path of config->context plus one, must not pass
//              kMaxLook in SLL prediction, or the outer context's length plus
//              kMaxFull in full-LL prediction (whose start state is the outer
//              rule stack, computeStartState). The walk is memoized for the
//              prediction, so flat input pays nothing. A prediction whose
//              context graph exceeds 4x the bound in distinct nodes also
//              refuses (a cap, not an undercount; not reached on the oracle
//              corpus). A full-LL refusal throws out of the reach
//              computation, whose caller (execATNWithFullContext) holds its
//              previous reach set as a raw pointer freed on the normal and
//              no-viable-alt exits only; BoundedSimulator's reach-computation
//              override frees it on the exception exit too (J5c, the
//              known-gaps reach-set leak row).
//
// The refusal (NestingRefused) derives from no exception class: the generated
// rule bodies catch only RecognitionException (Python3Parser.cpp:762), so it
// unwinds every rule frame, whose RAII finally (support/CPPUtils.h:23) runs
// exitRule/unrollRecursionContexts, and adaptivePredict's, which restores the
// input. Nothing the guards override runs during that unwinding.
//
// Recognition, export and the destruction of the parser, the tree and the
// worker's caches run on a wrapper-owned thread with a fixed kParseStack (16
// MiB, committed lazily), joined before the call returns, so the bounds hold
// whatever thread the caller parses on. The export walkers are iterative: a
// left-recursive chain (1+1+...) is a deep tree that costs recognition no
// stack and the walkers none either.
//
// Constants (§7.6 records the derivation): kMaxRule 8,192 frames and
// kMaxLook 4,096 links are each at most a quarter of kParseStack at the
// worst cost per frame and per link measured (the C4 calibration record);
// kMaxFull 256 links is set by time, not stack (full-LL lookahead over a
// unary run grows about 9x per doubling). Time is not bounded inside the
// axes (§7.6; slice J5c): no cancellation of an under-bound parse is offered.
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
// jolt_parser_heap_in_use reports malloc's bytes in use;
// jolt_parser_live_workers counts workers not yet freed;
// jolt_parser_last_stats reports the last parse's deepest rule frame and
// chains and, with jolt_parser_stack_probe on, its parse thread's stack
// high-water mark. -DJOLT_PARSER_UNBOUNDED=ON (test builds only) keeps the
// guards measuring but never refusing, for the calibration record.
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
#include <unordered_map>
#include <utility>
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

// Preorder over `root` with an explicit stack (a tree may be as deep as its
// input is long: a left-recursive chain), calling node(t) for each node and
// counting them.
template <typename Node>
void preorder(antlr4::tree::ParseTree* root, uint32_t& count, Node node) {
  std::vector<antlr4::tree::ParseTree*> stack{root};
  while (!stack.empty()) {
    antlr4::tree::ParseTree* t = stack.back();
    stack.pop_back();
    ++count;
    node(t);
    if (!dynamic_cast<antlr4::tree::TerminalNode*>(t))
      for (auto c = t->children.rbegin(); c != t->children.rend(); ++c)
        stack.push_back(*c);
  }
}

void walk(antlr4::tree::ParseTree* tree, Out& out, uint32_t& count) {
  preorder(tree, count, [&](antlr4::tree::ParseTree* t) {
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
  });
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

// --- the budget (J5b) ---------------------------------------------------------

constexpr size_t kMaxInputBytes = size_t{4} << 20;  // 4 MiB
constexpr size_t kParseStack = size_t{16} << 20;    // 16 MiB
constexpr uint64_t kMaxRule = 8192;                 // rule frames
constexpr uint64_t kMaxLook = 4096;                 // SLL chain links
constexpr uint64_t kMaxFull = 256;                  // full-LL links past the outer context

#ifdef JOLT_PARSER_UNBOUNDED
constexpr bool kEnforce = false;  // the calibration build: measure, never refuse
#else
constexpr bool kEnforce = true;
#endif

enum class Kind : uint8_t { Rule, Lookahead };

// The guards' refusal. Not derived from std::exception or RecognitionException,
// so no handler in the generated parser or the runtime catches it.
struct NestingRefused {
  Kind kind;
  uint64_t measured;
  uint64_t bound;
};

// What the guards saw in one parse (the jolt_parser_last_stats seam).
struct Stats {
  uint64_t max_rule = 0;   // deepest rule frame entered
  uint64_t max_look = 0;   // longest SLL chain a rule transition made
  uint64_t max_full = 0;   // longest full-LL chain past the outer context
  uint64_t full_predictions = 0;
};

// The input bound, checked before any ANTLR object exists.
int32_t over_input(size_t len, uint8_t** out, size_t* out_len) noexcept {
  if (len <= kMaxInputBytes) return 0;
  char text[96];
  std::snprintf(text, sizeof text, "input %zu %zu", len, kMaxInputBytes);
  return message(text, -6, out, out_len);
}

int32_t refused(const NestingRefused& r, uint8_t** out, size_t* out_len) noexcept {
  char text[96];
  std::snprintf(text, sizeof text, "nesting %llu %llu %s",
                static_cast<unsigned long long>(r.measured),
                static_cast<unsigned long long>(r.bound),
                r.kind == Kind::Rule ? "rule" : "lookahead");
  return message(text, -7, out, out_len);
}

// The rule-depth guard. A frame's depth is the length of the current
// context's parent chain plus one: each live rule activation contributes
// exactly one context to that chain at all times. The depth is recorded per
// context when it is entered (its parent's plus one) rather than walked: the
// walk measured 9-123% slower on the at-bound shapes (J5b round 2). A frame
// deeper than kMaxRule is refused before the base entry runs, so the refused
// frame never becomes the current context and installs no finally.
class BoundedParser : public Python3Parser {
 public:
  BoundedParser(antlr4::TokenStream* input, Stats& stats)
      : Python3Parser(input), stats_(stats) {}

  using Python3Parser::enterRecursionRule;

  void enterRule(antlr4::ParserRuleContext* localctx, size_t state,
                 size_t ruleIndex) override {
    enter_frame(localctx);
    Python3Parser::enterRule(localctx, state, ruleIndex);
  }

  void enterRecursionRule(antlr4::ParserRuleContext* localctx, size_t state,
                          size_t ruleIndex, int precedence) override {
    enter_frame(localctx);
    Python3Parser::enterRecursionRule(localctx, state, ruleIndex, precedence);
  }

  // the loop of a left-recursive rule: a new context, the same frame
  void pushNewRecursionContext(antlr4::ParserRuleContext* localctx,
                               size_t state, size_t ruleIndex) override {
    depth_[localctx] = depth_of(_ctx);
    Python3Parser::pushNewRecursionContext(localctx, state, ruleIndex);
  }

  // a labelled alternative would replace the context in the same frame; the
  // pinned grammar has none, so this never records anything
  void enterOuterAlt(antlr4::ParserRuleContext* localctx, size_t altNum) override {
    if (localctx != _ctx) depth_[localctx] = depth_of(_ctx);
    Python3Parser::enterOuterAlt(localctx, altNum);
  }

 private:
  void enter_frame(antlr4::ParserRuleContext* localctx) {
    uint64_t d = depth_of(_ctx) + 1;
    if (d > stats_.max_rule) stats_.max_rule = d;
    if (kEnforce && d > kMaxRule) throw NestingRefused{Kind::Rule, d, kMaxRule};
    depth_[localctx] = d;
  }

  // The frame depth of `ctx` (0 for none): the recorded depth of its nearest
  // recorded ancestor, plus one per unrecorded link, which can only overstate.
  uint64_t depth_of(antlr4::RuleContext* ctx) const {
    uint64_t unknown = 0;
    for (antlr4::tree::ParseTree* c = ctx; c; c = c->parent) {
      auto it = depth_.find(static_cast<antlr4::RuleContext*>(c));
      if (it != depth_.end()) return it->second + unknown;
      ++unknown;
    }
    return unknown;
  }

  Stats& stats_;
  std::unordered_map<const antlr4::RuleContext*, uint64_t> depth_;
};

// The prediction guard (see the header). Installed per parse over the
// worker's (or the static) caches.
class BoundedSimulator : public antlr4::atn::ParserATNSimulator {
 public:
  using Ctx = antlr4::atn::PredictionContext;

  BoundedSimulator(antlr4::Parser* parser, const antlr4::atn::ATN& atn,
                   std::vector<antlr4::dfa::DFA>& dfa,
                   antlr4::atn::PredictionContextCache& cache, Stats& stats)
      : ParserATNSimulator(parser, atn, dfa, cache), stats_(stats) {}

  size_t adaptivePredict(antlr4::TokenStream* input, size_t decision,
                         antlr4::ParserRuleContext* outer) override {
    // the memo lives for one prediction; its references keep the contexts
    // it names alive, so no address is reused while it is keyed
    struct Clear {
      BoundedSimulator* s;
      ~Clear() {
        s->memo_.clear();
        s->full_base_ = 0;
        s->full_s0_ = nullptr;
      }
    } clear{this};
    return ParserATNSimulator::adaptivePredict(input, decision, outer);
  }

  std::unique_ptr<antlr4::atn::ATNConfigSet> computeStartState(
      antlr4::atn::ATNState* p, antlr4::RuleContext* ctx, bool fullCtx) override {
    if (fullCtx) {
      // the length of PredictionContext::fromRuleContext(ctx)
      uint64_t n = 0;
      for (antlr4::RuleContext* c = ctx;
           c && c->parent && c != &antlr4::ParserRuleContext::EMPTY;
           c = static_cast<antlr4::RuleContext*>(c->parent))
        ++n;
      full_base_ = n;
      ++stats_.full_predictions;
      auto s0 = ParserATNSimulator::computeStartState(p, ctx, true);
      full_s0_ = s0.get();
      return s0;
    }
    return ParserATNSimulator::computeStartState(p, ctx, false);
  }

  Ref<antlr4::atn::ATNConfig> getEpsilonTarget(
      Ref<antlr4::atn::ATNConfig> const& config, const antlr4::atn::Transition* t,
      bool collectPredicates, bool inContext, bool fullCtx,
      bool treatEofAsEpsilon) override {
    if (t->getTransitionType() == antlr4::atn::TransitionType::RULE)
      check(config->context, fullCtx);
    return ParserATNSimulator::getEpsilonTarget(config, t, collectPredicates,
                                                inContext, fullCtx,
                                                treatEofAsEpsilon);
  }

  // The full-context loop (execATNWithFullContext) holds its previous reach
  // set as a raw pointer released from a unique_ptr and frees it on its
  // normal and no-viable-alt exits only (delete previous; the exception's
  // conditional delete); an exception thrown out of the reach computation —
  // where the lookahead guard throws — is the one exit that does not, leaking
  // one prediction set per refusal. Free the caller's set on that exit: not
  // the start set (the caller's own unique_ptr frees it, which is why
  // computeStartState records it), and never the SLL caller's set, which
  // belongs to its DFAState.
  std::unique_ptr<antlr4::atn::ATNConfigSet> computeReachSet(
      antlr4::atn::ATNConfigSet* closure, size_t t, bool fullCtx) override {
    if (!fullCtx)
      return ParserATNSimulator::computeReachSet(closure, t, false);
    try {
      return ParserATNSimulator::computeReachSet(closure, t, true);
    } catch (...) {
      if (closure != full_s0_) delete closure;
      throw;
    }
  }

 private:
  // A RULE transition pushes one link onto `ctx`.
  void check(const Ref<const Ctx>& ctx, bool fullCtx) {
    const uint64_t base = fullCtx ? full_base_ : 0;
    const uint64_t bound = fullCtx ? kMaxFull : kMaxLook;
    const uint64_t made = links(ctx, base + bound, 4 * (base + bound)) + 1;
    const uint64_t past = made > base ? made - base : 0;
    uint64_t& seen = fullCtx ? stats_.max_full : stats_.max_look;
    if (past > seen) seen = past;
    if (kEnforce && past > bound)
      throw NestingRefused{Kind::Lookahead, past, bound};
  }

  // The longest parent path of `root` in links, memoized for this
  // prediction: iterative, at most `visits` new contexts walked; a path
  // longer than `cap`, or the visit cap reached, answers cap + 1.
  uint64_t links(const Ref<const Ctx>& root, uint64_t cap, uint64_t visits) {
    struct Frame {
      const Ref<const Ctx>* ctx;
      size_t next;
      uint64_t best;
    };
    auto known = [&](const Ctx* c, uint64_t& v) {
      if (c->isEmpty()) {
        v = 0;
        return true;
      }
      auto it = memo_.find(c);
      if (it == memo_.end()) return false;
      v = it->second.second;
      return true;
    };
    uint64_t v = 0;
    if (known(root.get(), v)) return v;
    std::vector<Frame> stack{{&root, 0, 0}};
    uint64_t walked = 0;
    while (!stack.empty()) {
      Frame& f = stack.back();
      const Ctx* c = f.ctx->get();
      if (f.next < c->size()) {
        size_t i = f.next++;
        if (c->getReturnState(i) == Ctx::EMPTY_RETURN_STATE) continue;
        const Ref<const Ctx>& parent = c->getParent(i);
        if (!parent) continue;
        uint64_t pv = 0;
        if (known(parent.get(), pv)) {
          if (pv + 1 > f.best) f.best = pv + 1;
          continue;
        }
        if (stack.size() > cap || ++walked > visits) return cap + 1;
        stack.push_back({&parent, 0, 0});
        continue;
      }
      uint64_t len = f.best;
      memo_.emplace(c, std::make_pair(*f.ctx, len));
      stack.pop_back();
      if (stack.empty()) return len;
      Frame& up = stack.back();
      if (len + 1 > up.best) up.best = len + 1;
    }
    return 0;
  }

  Stats& stats_;
  uint64_t full_base_ = 0;
  // this prediction's full-LL start set, the one reach input the caller frees
  const antlr4::atn::ATNConfigSet* full_s0_ = nullptr;
  std::unordered_map<const Ctx*, std::pair<Ref<const Ctx>, uint64_t>> memo_;
};

// --- the parse thread -----------------------------------------------------------

#ifdef JOLT_PARSER_TEST_SEAMS
std::atomic<bool> g_stack_probe{false};
std::atomic<uint64_t> g_last[6];  // the last parse's Stats, high-water, stack

// The stack's lowest usable address on this thread (0 unknown).
uintptr_t stack_low() noexcept {
#if defined(__APPLE__)
  pthread_t self = pthread_self();
  return reinterpret_cast<uintptr_t>(pthread_get_stackaddr_np(self)) -
         pthread_get_stacksize_np(self);
#elif defined(__GLIBC__)
  pthread_attr_t attr;
  if (pthread_getattr_np(pthread_self(), &attr) != 0) return 0;
  void* low = nullptr;
  size_t size = 0;
  pthread_attr_getstack(&attr, &low, &size);
  pthread_attr_destroy(&attr);
  return reinterpret_cast<uintptr_t>(low);
#else
  return 0;
#endif
}

constexpr uint64_t kPaint = 0xa5a5a5a5a5a5a5a5ull;
constexpr uintptr_t kPaintMargin = 64u << 10;  // left unpainted above the guard
#endif

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

// One job on a parse thread: `run` answers a status; it catches everything
// itself, so nothing unwinds out of the thread.
struct Job {
  int32_t (*run)(Job&);
  void* arg;
  Stats stats;
  int32_t status = -3;
  bool record = false;  // a parse: its Stats become the last parse's
};

void* parse_thread_main(void* p) {
  Job& job = *static_cast<Job*>(p);
#ifdef JOLT_PARSER_TEST_SEAMS
  // paint the unused stack below this frame, run, then find the deepest
  // unpainted word: the job's high-water mark
  const bool probe = g_stack_probe.load();
  volatile char here = 0;
  const uintptr_t top = reinterpret_cast<uintptr_t>(&here) - 4096;
  const uintptr_t low = stack_low() + kPaintMargin;
  if (probe && low && low < top)
    for (auto* w = reinterpret_cast<volatile uint64_t*>(low);
         reinterpret_cast<uintptr_t>(w) < top; ++w)
      *w = kPaint;
#endif
  job.status = job.run(job);
#ifdef JOLT_PARSER_TEST_SEAMS
  if (!job.record) return nullptr;
  uint64_t high_water = 0;
  if (probe && low && low < top) {
    auto* w = reinterpret_cast<volatile uint64_t*>(low);
    while (reinterpret_cast<uintptr_t>(w) < top && *w == kPaint) ++w;
    high_water = reinterpret_cast<uintptr_t>(&here) - reinterpret_cast<uintptr_t>(w);
  }
  g_last[0] = job.stats.max_rule;
  g_last[1] = job.stats.max_look;
  g_last[2] = job.stats.max_full;
  g_last[3] = job.stats.full_predictions;
  g_last[4] = high_water;
  g_last[5] = thread_stack_size();
#endif
  return nullptr;
}

// Run `job` on a new thread with a kParseStack stack and wait for it. The
// caller's thread only waits; the job touches no caller memory but its
// arguments. -3 with a message when the thread cannot be made.
int32_t on_parse_thread(Job& job, uint8_t** out, size_t* out_len) noexcept {
  pthread_attr_t attr;
  if (pthread_attr_init(&attr) != 0)
    return message("parse thread attributes could not be made", -3, out, out_len);
  pthread_t thread;
  int rc = pthread_attr_setstacksize(&attr, kParseStack);
  if (rc == 0) rc = pthread_create(&thread, &attr, parse_thread_main, &job);
  pthread_attr_destroy(&attr);
  if (rc != 0) return message("parse thread could not be started", -3, out, out_len);
  pthread_join(thread, nullptr);
  return job.status;
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

void walk_export(antlr4::tree::ParseTree* tree, Out& out, uint32_t& count) {
  preorder(tree, count, [&](antlr4::tree::ParseTree* t) {
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
  });
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

// Workers made and not yet destroyed (the jolt_parser_live_workers seam).
std::atomic<int64_t> g_live_workers{0};

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
        parser_dfa(fresh_dfa(*atns.parser)) {
    g_live_workers.fetch_add(1);
  }
  ~Worker() { g_live_workers.fetch_sub(1); }
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

namespace {

// One parse's arguments, for the parse thread. `w` is null for
// jolt_parser_parse, which recognizes over the generated static caches.
struct Call {
  Worker* w;
  const uint8_t* src;
  size_t len;
  uint8_t** out;
  size_t* out_len;
};

int32_t export_job(Job& job) {
  Call& c = *static_cast<Call*>(job.arg);
  try {
    maybe_fault();
    SourceStream input(
        std::string_view(reinterpret_cast<const char*>(c.src), c.len));
    Python3Lexer lexer(&input);
    // setInterpreter deletes the generated default simulator, the one over
    // the static caches; the generated destructors delete these at scope exit
    lexer.setInterpreter(new antlr4::atn::LexerATNSimulator(
        &lexer, *c.w->lexer_atn, c.w->lexer_dfa, c.w->lexer_ctx));
    antlr4::CommonTokenStream tokens(&lexer);
    BoundedParser parser(&tokens, job.stats);
    parser.setInterpreter(new BoundedSimulator(
        &parser, *c.w->parser_atn, c.w->parser_dfa, c.w->parser_ctx, job.stats));
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
    return hand_off(o.buf, c.out, c.out_len);
  } catch (const NestingRefused& r) {
    return refused(r, c.out, c.out_len);
  } catch (const Oversize&) {
    return -4;
  } catch (const std::exception& e) {
    return message(e.what(), -2, c.out, c.out_len);
  } catch (...) {
    return message("unknown exception", -3, c.out, c.out_len);
  }
}

int32_t parse_job(Job& job) {
  Call& c = *static_cast<Call*>(job.arg);
  try {
    published_atns();
    maybe_fault();
    SourceStream input(
        std::string_view(reinterpret_cast<const char*>(c.src), c.len));
    Python3Lexer lexer(&input);
    antlr4::CommonTokenStream tokens(&lexer);
    BoundedParser parser(&tokens, job.stats);
    // the guard over the generated static caches the default simulator uses
    auto* generated = parser.getInterpreter<antlr4::atn::ParserATNSimulator>();
    std::vector<antlr4::dfa::DFA>& dfa = generated->decisionToDFA;
    antlr4::atn::PredictionContextCache& cache = generated->getSharedContextCache();
    parser.setInterpreter(
        new BoundedSimulator(&parser, parser.getATN(), dfa, cache, job.stats));
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
    return hand_off(o.buf, c.out, c.out_len);
  } catch (const NestingRefused& r) {
    return refused(r, c.out, c.out_len);
  } catch (const Oversize&) {
    return -4;
  } catch (const std::exception& e) {
    return message(e.what(), -2, c.out, c.out_len);
  } catch (...) {
    return message("unknown exception", -3, c.out, c.out_len);
  }
}

int32_t free_job(Job& job) {
  delete static_cast<Worker*>(job.arg);
  return 0;
}

int32_t stack_job(Job& job) {
  *static_cast<uint64_t*>(job.arg) = thread_stack_size();
  return 0;
}

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
// the handle again, nor free it while a call on it runs. The caches are
// destroyed on a parse thread: a cached prediction-context chain is destroyed
// recursively, as deep as the lookahead bound allows. When no parse thread
// can be started the worker is leaked, never destroyed on the caller's stack.
JOLT_EXPORT void jolt_parser_worker_free(void* w) {
  if (Worker* worker = as_worker(w)) {
    worker->tag = 0;
    Job job{free_job, worker, {}, -3};
    uint8_t* msg = nullptr;
    size_t msg_len = 0;
    on_parse_thread(job, &msg, &msg_len);
    std::free(msg);
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
  if (int32_t refused = over_input(len, out, out_len)) return refused;
  Hold hold(w);
  if (!hold.held) return -5;
  Call call{w, src, len, out, out_len};
  Job job{export_job, &call, {}, -3, true};
  return on_parse_thread(job, out, out_len);
}

// Parse UTF-8 `src[0, len)` from the `file_input` rule, with the generated
// static caches (the round-1 probe surface).
JOLT_EXPORT int32_t jolt_parser_parse(const uint8_t* src, size_t len,
                                      uint8_t** out, size_t* out_len) {
  if (!out || !out_len || (!src && len != 0)) return -1;
  *out = nullptr;
  *out_len = 0;
  if (int32_t refused = over_input(len, out, out_len)) return refused;
  Call call{nullptr, src, len, out, out_len};
  Job job{parse_job, &call, {}, -3, true};
  return on_parse_thread(job, out, out_len);
}

// Release a buffer jolt_parser_parse handed out. NULL is accepted.
JOLT_EXPORT void jolt_parser_free(uint8_t* p) { std::free(p); }

// The JPT1 layout version jolt_parser_parse writes.
JOLT_EXPORT int32_t jolt_parser_layout_version(void) { return 1; }

// The JPX1 layout version jolt_parser_export writes.
JOLT_EXPORT int32_t jolt_parser_export_layout_version(void) { return 2; }

// The budget (J5b): out[0] the input bound in bytes, out[1] the rule-frame
// bound, out[2] the SLL lookahead bound, out[3] the full-LL lookahead bound
// (links past the outer context), out[4] the stack a parse thread actually
// has (read on one; 0 unreadable). -3 when no parse thread can be started.
JOLT_EXPORT int32_t jolt_parser_budget(uint64_t* out) {
  if (!out) return -1;
  out[0] = kMaxInputBytes;
  out[1] = kMaxRule;
  out[2] = kMaxLook;
  out[3] = kMaxFull;
  out[4] = 0;
  Job job{stack_job, &out[4], {}, -3};
  uint8_t* msg = nullptr;
  size_t msg_len = 0;
  int32_t status = on_parse_thread(job, &msg, &msg_len);
  std::free(msg);
  return status;
}

#ifdef JOLT_PARSER_TEST_SEAMS

// TEST SEAM: set the fault mode (0 off, 1-3 as g_fault above) for later
// parses. Answers the previous mode.
JOLT_EXPORT int32_t jolt_parser_inject_fault(int32_t mode) {
  return g_fault.exchange(mode);
}

// TEST SEAM: workers made and not yet destroyed, process-wide.
JOLT_EXPORT int64_t jolt_parser_live_workers(void) { return g_live_workers.load(); }

// TEST SEAM: paint each later parse thread's stack and measure its
// high-water mark (1 on, 0 off). Answers the previous setting.
JOLT_EXPORT int32_t jolt_parser_stack_probe(int32_t on) {
  return g_stack_probe.exchange(on != 0) ? 1 : 0;
}

// TEST SEAM: the last parse (either entry, any thread): out[0] the deepest
// rule frame entered, out[1] the longest SLL chain made, out[2] the longest
// full-LL chain past the outer context, out[3] full-LL predictions, out[4]
// the parse thread's stack high-water mark in bytes (0 unless the probe was
// on), out[5] that thread's stack size; out[6] 1 when this build enforces the
// bounds (0: -DJOLT_PARSER_UNBOUNDED).
JOLT_EXPORT int32_t jolt_parser_last_stats(uint64_t* out) {
  if (!out) return -1;
  for (int i = 0; i < 6; ++i) out[i] = g_last[i].load();
  out[6] = kEnforce ? 1 : 0;
  return 0;
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
