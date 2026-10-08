Created-GMT: 2026-09-22 05:44:39 GMT
Created-Local: 2026-09-22 12:44:39 +07 (Indochina Time)
Coding-Agent: claude
Session-ID: d3f7a2c9-1b8e-4a6f-9c5d-7e2b8f4a3d61 (resumed: your B0 session; it already knows the parity normalizer and corpus)
# Task: debruijn-b1 — the executable dimension and validator
Role: yin.vm / Interpreter Engineer
Implementers:
- Model: claude-sonnet-5 | Assigned: 2026-09-22 12:44:39 +07 | Status: active | Rationale: same engineer as B0 (resume; it holds the parity-normalizer and corpus context this phase builds on); architect-signed design, no owner gate blocking it; dispatched concurrently with an unrelated dao.space epic (disjoint files)

Work in /Users/sto/workspace/worktree-debruijn-b1 (your launch directory;
branch debruijn-b1, HEAD f54a9888). Do NOT stage, commit, merge or push.

## Spec (B1, from the architect-signed design)

Read docs/design/yin.vm.debruijn-vm.md IN FULL first (it is short), then
section 2 (Instruction dimension and code identity) and the "B1: executable
dimension and validator" phase box (restated below) closely; this phase's
correctness depends on getting every detail of section 2 right, not just
the phase box summary.

File box (exactly this):
- NEW src/cljc/yin/vm/debruijn_code.cljc
- NEW test/yin/vm/debruijn_code_test.cljc
- Existing edits: NONE
- Must not change: yin.vm.code, :yin.code/*, the merged de Bruijn projection
  namespace (yin.vm.debruijn/yin.vm.pipeline, dormant per D12)

## What to build (section 2, restated with exact reuse targets)

1. **The descriptor.** Declares a lowering-contract version, arity, ordered
   slots, canonical encoding, and one lift morphism from
   `:yin.debruijn.code/*` to `:yin.code/*` (the morphism's exact shape is
   B2's job to USE; B1 only declares and exports it as data/a documented
   contract point -- do not implement the lift itself, that is B2's file
   box).
2. **The derived opcode table.** Derive it AS DATA from the existing
   `yin.vm.code/vector-operand-table` (read it; do not hand-copy or
   re-derive its shape by eye). Only lexical addressing differs: `:var`
   becomes `:load-bound` or `:load-free` (two mnemonics where the named
   table has one; decide the operand shape for each -- a bound reference
   needs a depth and a position, a free reference needs a name), and
   `:closure` carries arity and a body reference rather than a parameter
   vector. Every other mnemonic (stack ops, `:const`, `:push`, `:halt`,
   `:branch-false`, stream ops, store ops, park, resume, gensym, FFI)
   keeps its existing operand shape exactly.
3. **The exact executable scalar encoder.** A NEW scalar tag table, deliberately
   distinct from `yin.vm.debruijn/encode-value` (the merged projection's
   encoder) -- do not reuse it, do not reach its private vars. Copy only
   the SMALL framing and length-prefix HELPERS the projection namespace
   uses (read them first; they are meant to be copied, not imported).
   Distinct tags for: nil, booleans, long, double, ratio, bigint, char,
   string, keyword, symbol, vectors, lists (a distinct class from
   vectors), maps, sets, and any other value class the design's supported
   domain names. Map and set entries ordered by ENCODED BYTES (not host
   comparison). Numeric bits and string/name UTF-8 bytes used EXACTLY as
   supplied: no NFC normalization, no integral-double-to-long folding.
   Unpaired UTF-16 surrogates refused with `:unsupported-value` (match the
   projection's UTF-16 guard's rule, not its code). Any value outside the
   declared domain refused with a qualified `:unsupported-value`
   diagnostic naming what failed, never silently canonicalized.
4. **Host scalar classification is explicit, not incidental:**
   - CLJS: a JS number is a long when `js/Number.isSafeInteger`, else a
     double. An integral-valued double (e.g. `1.0` where `(== 1.0 1)`) is
     OUTSIDE the CLJS common scalar domain (JS cannot tell it from a long),
     so a CLJS receiver REFUSES an image containing one with
     `:unsupported-value`. This means: on encode, if you are given a JS
     value that is already ambiguous (a double bit-pattern that could not
     have come from a long, safe-integer or not, this is fine and IS the
     double class; the refusal is specifically for the case a received
     image's `:const` tag claims :double but the value is integral --
     encode never needs to guess this on JS, since JS gives you the value
     with its already-decided tag from the AST; the refusal matters on
     DECODE/RECEIVE of a foreign image, so implement it in the decode/load
     path, not encode).
   - Characters: host characters where the host has them, strings
     otherwise (Dart and JS have no character type; encode a char as a
     one-codepoint string on those hosts, with its own tag distinct from
     ordinary strings so it round-trips as a char on a host that can).
   - Ratios and big integers are JVM/Dart classes unless a host provides
     an equivalent; JS has neither natively (BigInt exists but is a
     different type) -- read how the existing repo already handles
     cross-host bigint/ratio representation (grep for existing patterns in
     yin.vm.debruijn or dao.jing before inventing a new one) and match it
     unless there is a documented reason not to.
   - Cross-host BYTE IDENTITY is required only for values in the COMMON
     scalar domain (present, unambiguous, and identically encodable on all
     three hosts). B1 tests for `1` vs `1.0`, ratios, and chars are
     JVM/Dart-only tests (CLJS cannot hold these distinctions the same
     way); shared cross-host fixtures cover only the common domain.
5. **Scalar-class derivation for host-boundary refusal.** The scalar
   classes an image uses are DERIVED by scanning tags in its hashed
   `:const` operands (no separate manifest field). A receiving host missing
   a class refuses the whole image before execution with a qualified
   `:unsupported-value` outcome (a host-boundary outcome, not a stream
   gap -- word it that way in any ex-data/diagnostic map, distinct from a
   `:dao.stream/gap`).
6. **`image-hash`, the ONE function that computes H.** Hashes: the
   descriptor hash (including the lowering-contract version) plus the
   canonical positional instruction vector (pc-indexed tuples, refs already
   resolved to pcs, no header, exact scalar bytes). Provenance (binder
   names, diagnostics) lives in a side table indexed by pc and is OUTSIDE
   the hash -- never let it leak into the hashed bytes. Use `jing/sha256`
   (or `jing/sha256-bytes` if you hash raw bytes rather than a string; pick
   whichever fits your canonical encoding's shape and say which). H is the
   ONLY executable/sharing identity: no projection fingerprint, no second
   identity attached anywhere in this file.
7. **The validator.** Two things, both required by section 3's "scope
   validation is mandatory in both places an image can enter" -- B1 owns
   the SECOND check (B2 owns the first, in its own file; do not implement
   B2's check here, but DO build B1's validator to be usable as the
   authority B2's own check must also satisfy): walk each body's declared
   arity and enclosing-body chain, and reject nonnegative-depth/position
   violations against it. A hand-built image with a `:load-bound` operand
   like `[5 0]` pointing past a body's declared arity/enclosing chain must
   be rejected before execution, not merely produce a wrong runtime result.
   Also reject malformed rows and out-of-range bound operands generically
   (this validator is the "B1 image validator" section 3 refers to as the
   only admitter of executable images on this path -- no projected reader
   participates).

## Completion criteria (the design's own list; do not weaken any of these)

- Malformed rows are rejected.
- Out-of-range bound operands are rejected (the `[5 0]` case above, and
  others you construct).
- Exact scalar spelling is preserved through encode/hash (no NFC, no
  numeric folding).
- Composed and decomposed e-acute (`"é"` vs `"é"`) hash to
  DISTINCT values (proves no NFC is silently applied).
- A ratio fixture is either encoded (with its own tag) or refused with
  `:unsupported-value` on hosts without a ratio type -- your choice, state
  which and why for each host.
- `1` and `1.0` hash to DISTINCT values on JVM and Dart (JVM/Dart-only
  test, per the CLJS domain restriction above).
- Ratios and chars hash distinctly from other classes on JVM and Dart.
- IDENTICAL bytes across all three hosts for every value in the common
  scalar domain.
- Golden image bytes and H values for a FROZEN corpus, checked on all
  three host lanes -- pick or build a small fixed corpus of executable
  images (hand-built vectors of instruction tuples; you do not need B2's
  lowerer to exist yet, since this phase tests the dimension and validator
  standalone) and commit their expected bytes/H as literal test fixtures.
  A later change to this file's layout must fail these fixtures rather
  than silently forking identity -- say explicitly in your report that you
  verified this property (e.g. by trying a small layout perturbation and
  confirming the golden test catches it, then reverting).

## Never

Do not implement B2's lowerer, B3's VM, or B6's linker -- this phase is the
dimension and validator only, standalone, with hand-built test fixtures.
Do not touch `yin.vm.code`, `:yin.code/*`, or the merged projection
namespace (`yin.vm.debruijn`, `yin.vm.pipeline`) in any way, including
reaching their private vars. Do not add a second lossless record structure
(D2: no lossless DAG). Keep files pure ASCII, no em dashes, cljstyle-style
Clojure (blank lines between top-level forms), docstrings that say what
and why, matching this design doc's own register (declarative, precise,
no hedging).

## Environment

Default PATH gives Java 21 and the mise clojure and bb. Focused JVM run:
`clojure -M:test -n yin.vm.debruijn-code-test` (confirm the actual namespace
your file defines first). If you need kondo, cljstyle, the Java 17 lane, or
the CLJD lane and they are denied, say exactly what was denied and stop; do
not retry, do not use `--dangerously-skip-permissions`.

## Final report

Begin exactly with:
Completed-GMT: <YYYY-MM-DD HH:MM:SS GMT>
Completed-Local: <YYYY-MM-DD HH:MM:SS local-timezone-name>
Coding-Agent: claude
Session-ID: d3f7a2c9-1b8e-4a6f-9c5d-7e2b8f4a3d61
Then: the descriptor and opcode-table design and exactly how it derives
from vector-operand-table; the scalar encoder design and what it copied
from the projection namespace's framing helpers (cite what); how each
completion criterion is met, naming the test; the golden-corpus fixtures
and how you proved a layout change breaks them; every host-specific
decision (chars, ratios, bigints, the CLJS integral-double refusal) and
your reasoning; what you ran with exact counts; what you could not run;
every deviation. Facts only; promise nothing.
