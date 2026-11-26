Created-GMT: 2026-11-26 15:18:06 GMT
Created-Local: 2026-11-26 10:18:06 EST
Role: Compiler Engineer (numeric semantics)
Branch: fix/float-kind-portability (base `7f4898f4`, resumed from WIP `09401506`, uncommitted working tree per orchestrator rule)
Authority:
- `collab/yang-clojure/1795693415000-architect-float-kind-portability-ruling.gpt-6-astra.findings.md` (normative, RULING 1–5)
- `collab/yang-clojure/1795695531000-engineer-float-kind-portability.prompt.md` (round brief)

Implementers:
- Status-Event: 2026-11-26 12:58 GMT | Model: glm-5.3 (session 519cc353, darkstar) | Status: superseded | Rationale: owner-directed migration to remote host; drafted Gates 1/2/4/5 test file + JVM pre-fix legacy fixture in `09401506`
- Status-Event: 2026-11-26 15:18 GMT | Model: gemini-4-neon (linux worktree) | Status: completed | Rationale: captured Node pre-fix legacy fixture, locked full 7-gate RED evidence on JVM+Node, implemented shared numeric-token factory + Clojure token-aware reader + carrier admission + scoped numeric primitives + migration/docs, verified GREEN across all four hosts (JVM, Node, Dart, Jolt) + regression oracles + demo build + `clj-kondo` 0 errors

# Findings: `fix/float-kind-portability` r1 — Lexical Preservation of Clojure Float Literals

## 1. Executive Summary

Clojure numeric literal kind preservation from original source text to `dao.jing.cbor` / UAST / VM execution is implemented and verified green across all seven acceptance gates of RULING 5 and all four hosts (JVM, Node, Dart, Jolt), strictly inside RULING 5's file boundaries:

1. **Shared numeric-token factory** (`src/cljc/yang/core/numeric_token.cljc`): single portable token classifier and carrier constructor with per-language dialect tables (`:clojure`, `:typescript`, `:python`, `:php`). TypeScript, Python, and PHP ANTLR lowerers were migrated onto it without changing any existing Python/TS/PHP golden addresses, and a new PHP float producer parity test (`test/yang/php/antlr/float_address_test.cljc`) was added.
2. **Frontend-owned, token-aware Clojure source reader** (`src/cljc/yang/clojure/reader.cljc`): consumes original Clojure source text directly and materializes numeric tokens through `yang.core.numeric-token/materialize :clojure` **at token time**, before collection construction (`{...}`, `#{...}`) or lowering can lose kind or coalesce `1` and `1.0`. Zero ambient mutable state, no regex-over-source, no postwalk over read `Number`s, no print-round-trip, and no global reader monkeypatch. Supports optional `:file`/`:source-positions?` indexing for `yang.io`.
3. **Carrier admission & scoped execution reboxing**:
   - `src/cljc/yang/clojure.cljc` admits `cbor/float64?`, `cbor/decimal?`, `cbor/ratio?`, and `host-int/big-carrier?` in `literal?` and `constant-form?` (direct, nested, and quoted).
   - `src/cljc/yang/clojure/numeric.cljc` exports carrier-aware Clojure numeric primitives (`+`, `-`, `*`, `/`, `<`, `<=`, `>`, `>=`, kind-strict `=` via `data/equiv`, cross-kind `==`, `!=`) that route float operations through `yin.vm.float` and rebox float results as `data/float64`. Scoped to `yang.clojure/primitives` via `yin.repl.frontends` — global `yin.vm/primitives`, `dao.jing.cbor`, `yin.vm.data`, and `Float64` `:carrier-coercion` policy are untouched.
4. **Migration, custody, and documentation** (RULING 3 & RULING 4):
   - `:yang/clojure` manifest bumped to revision `"c1-3"`, lowering profile `:yang.clojure/constructed-collections-float-kind-v1`, support profile `:yang.clojure/c1-float-kind`, grammar capability `:source-text` (JVM/Node) vs `:forms-only` (Dart/Jolt, with honest `:unavailable-on-cljd` / `:unavailable-on-jolt` refusals on direct `read-forms`).
   - Both genuine pre-fix `c1-2` legacy artifacts (JVM float-bearing + Node integer-bearing) archived under `test/resources/yang/clojure/` and verified loaded, decoded, re-encoded, and executed without recompilation on all four hosts.
   - Documentation updated in `docs/design/dao.jing.cbor.md`, `docs/design/yang.antlr.md` (§8.8.1), `docs/design/yang.preludes.md` (§8.1), `src/cljc/yang/docs/yang_implementation.md`, and `test/yang/clojure_collection_order_test.cljc`.

---

## 2. Pre-Fix Legacy Custody Artifacts (Captured Before Production Edits)

Captured from pre-fix `c1-2` (`7f4898f4` / `09401506`) before modifying any production code; one-shot capture test `test/yang/floatkind_legacy_capture_test.cljc` was subsequently removed.

| Fixture | Host | Size | SHA-256 | Manifest Revision | Canonical Root | Runtime Value |
|---|---|---:|---|---|---|---|
| `test/resources/yang/clojure/float-kind-legacy-jvm-float.cbor` | JVM (`:jvm-float`) | 1,415 B | `71dc93298c26773dc8383f5d12e03d732840052692a272c16c4aaf4ec90783ec` | `"c1-2"` | `:segment/blake3-7cdae9dfee7f1980ccdd9543f279d0d7d20e4a4a4a44dda14f88f54ba9002049` | `float64(1.0)` |
| `test/resources/yang/clojure/float-kind-legacy-node-integer.cbor` | Node (`:node-integer`) | 1,390 B | `ffb9db68eb953ccadd681b916a8df6d262206baa055d6f91eda461d179174ef2` | `"c1-2"` | `:segment/blake3-541ba2ffc6b74327f70008d2069a35279612c2e96c82cc198d7b0b6782501e4c` | exact integer `1` |

---

## 3. TDD RED Evidence (Locked Before Implementation)

All RED logs are preserved unmodified under `collab/yang-clojure/1795702210000-floatkind-evidence/`:

| Stage | Host | Command | Result | Log File |
|---|---|---|---|---|
| Initial Gates 1/2/4/5 RED | JVM | `clojure -M:test -n yang.clojure-float-kind-test` | 18 tests, 180 assertions, **20 failures, 1 error** (`RC=1`) | `collab/yang-clojure/1795702210000-floatkind-evidence/red-jvm-gates1245.log` |
| Initial Gates 1/2/4/5 RED | Node | `npx shadow-cljs compile test && node target/node-tests.js --test=yang.clojure-float-kind-test` | 17 tests, 163 assertions, **63 failures, 4 errors** (`RC=1`) | `collab/yang-clojure/1795702210000-floatkind-evidence/red-node-gates1245.log` |
| Complete 7-Gate RED | JVM | `clojure -M:test -n yang.clojure-float-kind-test` + `yang.clojure-collection-order-test` (`corpus-cross-host-reference-test`, `unmodified-source-integral-floats-test`) | 21 tests / 228 assertions (**19 failures, 1 error**) + 2 tests / 22 assertions (**2 failures, 0 errors**) (`RC=1`) | `collab/yang-clojure/1795702210000-floatkind-evidence/red-jvm-all7gates.log` |
| Complete 7-Gate RED | Node | `node target/node-tests.js --test=yang.clojure-float-kind-test,yang.clojure-collection-order-test/corpus-cross-host-reference-test,yang.clojure-collection-order-test/unmodified-source-integral-floats-test` | 22 tests, 214 assertions, **75 failures, 5 errors** (`RC=1`) | `collab/yang-clojure/1795702210000-floatkind-evidence/red-node-all7gates.log` |

Key pre-fix failure modes observed in RED:
- On Node: `"0.0"`, `"1.0"`, `"-1.0"`, `"1e0"`, `"9007199254740992.0"`, `"1e19"`, `"2."`, `"1E5"` all collapsed into exact integers in `cljs.tools.reader` / `cljs.reader`; `"{1 :a 1.0 :b}"` and `"#{1 1.0}"` coalesced keys/elements during reading; `1/2` and `1M` were refused or misread; REPL arithmetic returned unboxed JS `Number`s; `"(inc 1.0)"` took the integer branch; Gate 3 unmodified-source `"{0.0 (+ n 1.0) 2e0 -0.0 1 1.5}"` diverged from JVM.
- On JVM: explicit `cbor/float64` carriers failed `yang.clojure/compile` (`literal?`/`constant-form?`), `yin.repl` arithmetic returned bare Doubles/reboxing mismatches, `c1-3` manifest/profiles did not exist, and Gate 3 `:integral-floats` had unrecorded pins.

---

## 4. Shared Numeric-Token Factory Design

Implemented in `src/cljc/yang/core/numeric_token.cljc` as the single shared numeric-token classifier and carrier constructor:

- **Dialect table (`dialects`)**:
  - `:clojure` — supports decimal/hex/octal/`Nr` exact integers, `N`-suffixed bigints, binary64 floats (including trailing-dot `2.`, leading-`+`, exponents, symbolic `##Inf`/`##-Inf`/`##NaN`), `M`-suffixed `cbor/decimal`, and `/` `cbor/ratio` (reducing via exact GCD and refusing zero denominator). No underscore separators.
  - `:typescript` — binary64 floats (decimal/hex/binary/octal) and `n`-suffixed exact BigInts, with `_` separators.
  - `:python` — binary64 floats (including leading `.5` and trailing `2.` with `_` separators), exact integers (decimal/hex/octal/binary), and imaginary suffix classification (`:imaginary`).
  - `:php` — binary64 floats (`Real` tokens with `_` separators, leading `.5`, trailing `2.`).
- **Core API**:
  - `classify [dialect raw-text]` — pure regex/lexeme classification returning `{:kind ... :text ...}` or throwing structured `:yang.core.numeric-token/invalid-token` refusal.
  - `materialize-classified [dialect classified]` and `materialize [dialect raw-text]` — constructs canonical carriers (`cbor/float64`, `host-int/demote`, `cbor/decimal`, `cbor/ratio`) portably across JVM, Node, Dart, and Jolt (`#?(:cljd (double/parse norm) :clj (Double/parseDouble norm) :cljs (js/parseFloat norm))`).
  - `float64-token [dialect raw-text]` — direct binary64 carrier construction for float-only grammar rules.
- **Adapter migrations (zero address churn)**:
  - `src/cljc/yang/typescript/antlr/lower.cljc`: `number-literal` delegates to `numeric-token/classify :typescript` + `materialize-classified`; removed private `host-double`, `radix-double`, `decimal-text`.
  - `src/cljc/yang/python/antlr/lower.cljc`: `parse-number` delegates to `numeric-token/classify :python` + `float64-token :python`.
  - `src/cljc/yang/php/antlr/lower.cljc`: `float-literal` delegates to `numeric-token/float64-token :php`.
  - Added `test/yang/php/antlr/float_address_test.cljc` verifying PHP `Real` tokens (`0.0`, `1.0`, `1e0`, `2.`, `.5`, `1.5`, `1_000.0`, `9007199254740992.0`) lower to canonical `cbor/float64` distinct from `Decimal` `1` on all four hosts.

---

## 5. Lexical-Coverage Demonstration & Clojure Source Reader (`src/cljc/yang/clojure/reader.cljc`)

Implemented as a self-contained, portable recursive-descent reader (zero ambient mutable state: no `*data-readers*`, no `*read-eval*`, no evaluation during reading, no global reader mutation). Every numeric token is materialized through `numeric-token/materialize :clojure` **before** any enclosing collection (`{...}`, `#{...}`) is constructed, so `{1 :a 1.0 :b}` and `#{1 1.0}` hold `1` and `Float64(1.0)` as distinct keys/members on both JVM and Node.

| Lexical Construct | Behavior in `yang.clojure.reader` | Gate / Test Coverage |
|---|---|---|
| Whitespace & comments | Spaces, tabs, CR/LF/FF, commas, `;...` line comments, `#!...` shebang comments skipped cleanly (`"1.0"` inside comments never touched) | Gate 2 (`gate2-lexical-contexts-are-not-floats`) |
| Strings (`"..."`) | Standard escapes (`\"`, `\\`, `\n`, `\r`, `\t`, `\b`, `\f`, `\uXXXX`, octal `\NNN`); numeric text inside strings remains a string | Gate 2 (`gate2-lexical-contexts-are-not-floats`) |
| Character literals (`\...`) | Single-char, named (`\newline`, `\space`, `\tab`, `\backspace`, `\formfeed`, `\return`), `\uXXXX` (surrogates refused), `\oNNN` (range checked) | Reader unit paths & `yin.repl-test` |
| Binary64 float tokens | `0.0`, `1.0`, `-1.0`, `1e0`, `1.5`, `-0.0`, `9007199254740992.0`, `9007199254740994.0`, `1e19`, `1.5e-5`, `5e-324`, `1e-400`, `2.`, `+1.5`, `1E5` → `cbor/float64` preserving exact IEEE-754 bits | Gate 1 (`gate1-float-tokens-are-float64-content`) |
| Exact integer & bigint tokens | Plain, signed (`+7`, `-1`), hex (`0x10`), octal (`017`), radix (`2r101`, `36rZZ`), `N`-suffixed bigints (`9223372036854775808000N`) → exact integer / `big-carrier` | Gate 1 (`gate1-integer-tokens-stay-exact-integers`) |
| Decimal `M` & Ratio `/` tokens | `1M`, `10.03M`, `-2.5M` → `cbor/decimal`; `1/2` → `cbor/ratio` (reduced, zero-denominator refused) | Gate 1 (`gate1-unsupported-categories-not-relabeled`, `gate1-cross-host-canonical-identity`) |
| Symbolic values (`##`) | `##Inf`, `##-Inf`, `##NaN` → `cbor/float64` | Gate 1 / numeric-token |
| Symbols, keywords, booleans, `nil` | `true`, `false`, `nil`, `/`, qualified symbols/keywords, invalid `::` or multi-slash refused | Gate 1, Gate 2, `yin.repl-test` |
| Collections | `(...)`, `[...]`, `{...}` (even-count & duplicate-key checks), `#{...}` (duplicate-element check), `#:ns{...}` namespaced maps; `{1 :a 1.0 :b}` & `#{1 1.0}` retain both occurrences | Gate 2 (`gate2-equal-valued-occurrences-not-coalesced`), `yin.repl-test` |
| Reader macros | `'x` → `(quote x)`, `@x` → `(deref x)`, `#'x` → `(var x)`, `^:k`/`^Sym`/`^"s"`/`^{...}` metadata (merging onto `IObj`/`IWithMeta`), `~x`, `~@x` | Gate 2 (`gate2-lexical-contexts-are-not-floats`), `yin.repl-test` |
| Dispatch forms | `#{...}`, `#_` (parses & validates discarded form recursively, incl. nested `#_`), `#'`, `##`, `#"..."` regex, `#(...)` anonymous fn (nested `#()` refused), `#:ns{...}` | Gate 2, `yin.repl-test` (`regex-and-anonymous-fn-forms-read-as-on-the-jvm` now passes identically on Node too) |
| Explicit refusals | `#?` / `#?@` reader conditionals, `#=` eval, syntax-quote `` ` ``, unknown `#tag`, invalid numbers (`1.2.3`, `08`, `0x`, `1e`, `2r`), odd maps, duplicate map/set keys, unmatched/unclosed delimiters | Gate 1, Gate 2, `yin.repl-test` |
| Opt-in source positions | When `:file` or `:source-positions?` is passed in `opts` (used by `yang.io/compile-string` & `yang.io/read-source`), attaches `{:file :line :column :end-line :end-column}` via binary-searched line offsets without affecting plain `(read-forms src)` metadata | `yang.clojure-test/indexing-reader-injects-nested-source-positions` + Gate 2 metadata assertions |

---

## 6. Carrier Admission, Scoped Reboxing, & Entry-Point Audit

- **`src/cljc/yang/clojure.cljc`**:
  - Added `numeric-carrier?` (`cbor/float64?`, `cbor/decimal?`, `cbor/ratio?`, `host-int/big-carrier?`) to `literal?` and `constant-form?`.
  - Wired `read-forms` (`[source]` / `[source opts]`) to `yang.clojure.reader/read-forms` on JVM/Node while preserving explicit `:unavailable-on-cljd` / `:unavailable-on-jolt` refusals.
  - Exported `yang.clojure/primitives` delegating to `yang.clojure.numeric/primitives`.
- **`src/cljc/yang/clojure/numeric.cljc`**:
  - Scoped carrier-aware primitives (`+`, `-`, `*`, `/`, `<`, `<=`, `>`, `>=`, `=`, `==`, `!=`) backed by `yin.vm.float`, `yin.vm.integer`, and `yin.vm.data`.
  - Mixed int/float and float/float arithmetic unboxes through `yin.vm.float` and reboxes all float results as `data/float64`.
  - `=` uses kind-strict `data/equiv` (`(= 1.0 1) => false`, `(= [1.0] [1]) => false`); `==` implements Clojure's cross-kind numeric equality (`(== 1.0 1) => true`).
  - Does **not** mutate global `yin.vm/primitives` or `Float64` `:carrier-coercion` policy.
- **`src/cljc/yin/repl/frontends.cljc` & `src/cljc/yin/repl.cljc`**:
  - `clojure-binding` installs `:parse yang.clojure/read-forms`, `:lower clojure-lower`, `:lower-forms clojure-lower`, `:primitives yang.clojure/primitives` on JVM/Node, and `:input :forms`, `:parse identity`, `:lower clojure-lower`, `:primitives yang.clojure/primitives` on Dart/Jolt (`:jolt` and `:cljd` ordered before `:clj` in reader conditionals!).
  - `yin.repl/compile-command-ast` uses `:lower-forms` for form-argument `(compile ...)` commands and `:parse` + `:lower` for string-argument `(compile "...")` commands.
  - `yin.repl/quote-symbols` renders `cbor/float64?` on `:cljs` via `DisplayKey` + `vm-float/format` (preserving `"1.0"`, `"-0.0"`, `"##NaN"`, `"##Inf"`, `"##-Inf"`).
- **Entry-Point & Demo Audit Ledger**:
  - `src/cljc/yang/io.cljc`: migrated `read-forms` to delegate to `yang.clojure/read-forms` with `{:file file-name}`.
  - `src/cljc/yin/repl/adapter.cljc`: audited — already forwards original source text intact; untouched.
  - `src/cljs/datomworld/demo/compilation_pipeline.cljs`: migrated from `cljs.reader` to `yang.clojure/read-forms` + `yang.clojure/primitives`.
  - `src/cljs/datomworld/demo/continuation_stream.cljs`: migrated from `cljs.reader` to `yang.clojure/read-forms` + `yang.clojure/primitives`.
  - `src/cljs/datomworld/demo/equation_plotter.cljs`: audited and explicitly documented as a legacy raw-forms host-interop boundary (binds `js/Math.*` directly and passes raw JS `Number`s to SVG geometry).
  - Shared preludes (`yang.clojure.prelude`, `yang.clojure.core-impl`, `yang.python.antlr.clj-runtime`): audited on JVM via `gate5-quoted-prelude-audit-no-integral-doubles` — **0 bare integral `Double` constants**.

---

## 7. Per-Gate GREEN Evidence & Four-Host Capability Matrix

### 7.1 Four-Host Capability Classification (RULING 5 Gate 6)

| Host | Capability Claim | Source Reading | Forms Compilation | Artifact Loading & Decode/Re-encode | Execution |
|---|---|---|---|---|---|
| **JVM** (`clojure 1.12.4.1602`) | `:source-text` | Verified (`yang.clojure/read-forms`, REPL, `yang.io`) | Verified (source + carrier forms + corpus) | Verified (both pre-fix `c1-2` files + embedded bytes) | Verified (AST walker, Semantic, Stack, Register VMs; linked & REPL) |
| **Node** (`node v25.6.1`, shadow-cljs) | `:source-text` | Verified (`yang.clojure/read-forms`, REPL, browser demos) | Verified (source + carrier forms + corpus) | Verified (both pre-fix `c1-2` files + embedded bytes) | Verified (AST walker, Semantic, Stack, Register VMs; linked & REPL) |
| **Dart** (`flutter 3.47.8`, ClojureDart) | `:forms-only` | Honest `:unavailable-on-cljd` refusal verified | Verified (carrier forms, PHP `Real` tokens) | Verified (both pre-fix `c1-2` disk files + embedded bytes, byte-identical re-encode) | Verified (AST walker on carrier forms, linked `inc`/`dec` carrier forms, legacy artifacts) |
| **Jolt** (`chez 10.4.1`, `f22df3c7` + native crypto/parser/file) | `:forms-only` | Honest `:unavailable-on-jolt` refusal verified | Verified (carrier forms, PHP `Real` tokens, 19-case corpus reference) | Verified (both pre-fix `c1-2` embedded artifacts, byte-identical re-encode) | Verified (AST walker on carrier forms, linked `inc`/`dec` carrier forms, legacy artifacts) |

### 7.2 Exact GREEN Commands, Counts, and Log Paths

| Leg | Command | Result | Log File |
|---|---|---|---|
| **JVM 7-Gates + PHP Producer** | `clojure -M:test -n yang.clojure-float-kind-test -n yang.php.antlr.float-address-test` | **22 tests, 290 assertions, 0 failures, 0 errors** | `collab/yang-clojure/1795702210000-floatkind-evidence/green-jvm-all7gates.log` |
| **JVM Gate 3 Corpus + Unmodified Source** | `clojure -M:test -n yang.clojure-collection-order-test -v yang.clojure-collection-order-test/corpus-cross-host-reference-test -v yang.clojure-collection-order-test/unmodified-source-integral-floats-test` | **2 tests, 22 assertions, 0 failures, 0 errors** | `collab/yang-clojure/1795702210000-floatkind-evidence/green-jvm-all7gates.log` |
| **JVM `yang.clojure-test` + `yin.repl-test`** | `clojure -M:test -n yang.clojure-test -n yin.repl-test` | **157 tests, 1,264 assertions, 0 failures, 0 errors** | `collab/yang-clojure/1795702210000-floatkind-evidence/green-jvm-repl-clojure.log` |
| **JVM Regression Oracles (10 other namespaces)** | `clojure -M:test -n yang.python.antlr.float-address-test -n yang.typescript.antlr.float-address-test -n yang.python.antlr.descriptor-profile-test -n yin.vm.data-test -n yin.vm.float-test -n dao.jing.cbor-test -n yang.clojure-callsite-test -n yang.clojure.stream-eval-test -n yin.repl.frontend-catalog-test -n yin.repl.adapter-test` | **154 tests, 2,395 assertions, 0 failures, 0 errors** (within 311-test/3,659-assertion run whose only pre-edit failures were the 2 fixes re-verified above) | `collab/yang-clojure/1795702210000-floatkind-evidence/green-jvm-regressions.log` |
| **Node 7-Gates + PHP Producer + Gate 3** | `npx shadow-cljs compile test --config-merge '{:autorun false}' && node target/node-tests.js --test=yang.clojure-float-kind-test,yang.php.antlr.float-address-test,yang.clojure-collection-order-test/corpus-cross-host-reference-test,yang.clojure-collection-order-test/unmodified-source-integral-floats-test` | **24 tests, 310 assertions, 0 failures, 0 errors** | `collab/yang-clojure/1795702210000-floatkind-evidence/green-node-all7gates.log` |
| **Node Regression Oracles (9 namespaces)** | `node target/node-tests.js --test=yang.python.antlr.float-address-test,yang.typescript.antlr.float-address-test,yin.vm.data-test,yin.vm.float-test,dao.jing.cbor-test,yang.clojure-callsite-test,yin.repl-test,yin.repl.frontend-catalog-test,yin.repl.adapter-test` | **178 tests, 2,137 assertions, 0 failures, 0 errors** | `collab/yang-clojure/1795702210000-floatkind-evidence/green-node-regressions.log` |
| **Browser `:demo` Build** | `npx shadow-cljs compile demo` | **324 files, 323 compiled, 0 warnings** | `collab/yang-clojure/1795702210000-floatkind-evidence/green-demo-build.log` |
| **Dart Gate 4/5/6/7 + PHP + Regressions** | `bb src/dev/cljd_agg.clj --only yang.clojure-float-kind-test,yang.php.antlr.float-address-test,yin.repl.frontend-catalog-test,yin.vm.float-test,yin.vm.data-test` | **63/63 tests passed, 0 failures, 0 errors** | `collab/yang-clojure/1795702210000-floatkind-evidence/green-dart-gates.log` |
| **Jolt Gate 4/5/6/7 + PHP + Corpus Ref** | `. build/tools/jolt-env.sh && "$JOLT_HOME/bin/jolt" -Srepro -M:jolt:jolt-test src/dev/jolt_test_runner.clj yang.clojure-float-kind-test yang.php.antlr.float-address-test yin.vm.float-test yin.vm.data-test` + `corpus-cross-host-reference-test` | `yang.clojure-float-kind-test`: **21 tests, 55/55 assertions pass (0 fail, 0 error)**; `yang.php.antlr.float-address-test`: **45/45 pass**; `yin.vm.float-test`: **108/108 pass**; `corpus-cross-host-reference-test`: **0 fail, 0 error** (`yin.vm.data-test`: 527/530 pass; 3 errors are pre-existing Chez Scheme `:jolt/unimplemented dao.host.text/units->string` unpaired-surrogate limitation) | `collab/yang-clojure/1795702210000-floatkind-evidence/green-jolt-gates.log` |
| **`clj-kondo` Lint** | `clojure -M:kondo --lint <all 17 touched/created src & test files>` | **0 errors** (6 pre-existing/minor warnings) | `collab/yang-clojure/1795702210000-floatkind-evidence/kondo.log` |

### 7.3 Per-Gate Checklist (RULING 5)

- **Gate 1 (Real Source Strings)**: `gate1-float-tokens-are-float64-content`, `gate1-integer-tokens-stay-exact-integers`, `gate1-unsupported-categories-not-relabeled`, `gate1-cross-host-canonical-identity` — **GREEN on JVM & Node**.
- **Gate 2 (Structural & Lexical Preservation)**: `gate2-floats-preserve-through-structure`, `gate2-lexical-contexts-are-not-floats`, `gate2-equal-valued-occurrences-not-coalesced`, `gate2-float-key-policy-parity`, `gate2-macro-generated-numeric-data` — **GREEN on JVM & Node**.
- **Gate 3 (Collection-Order & Provenance Cross-Host Identity)**: `corpus-cross-host-reference-test` (19 cases including `:integral-floats`) — **GREEN on JVM, Node, & Jolt**; `unmodified-source-integral-floats-test` — **GREEN on JVM & Node**.
- **Gate 4 (Runtime Semantics Oracle Independent of Digests)**: `gate4-data-oracle`, `gate4-library-inc-dec-float-behavior`, `gate4-repl-arithmetic-reboxed`, `gate4-repl-literal-kind-through-the-catalog`, `gate4-unboxed-control-distinguishes`, `gate4-linked-library-arithmetic-reboxed` — **GREEN on JVM & Node** (plus `gate4-data-oracle` & carrier `inc`/`dec` **GREEN on Dart & Jolt**).
- **Gate 5 (API/Build Carrier Boundary & Parked Round)**: `gate5-explicit-carrier-forms-compile-and-run`, `gate5-carrier-forms-one-root-everywhere` (all 4 hosts), `gate5-parked-float-result-crosses-the-round` (JVM & Node), `gate5-quoted-prelude-audit-no-integral-doubles` (JVM) — **GREEN**.
- **Gate 6 (Cross-Host Artifacts & Pre-Fix Legacy Custody)**: `gate6-legacy-custody-and-cross-host-artifacts` — **GREEN on all 4 hosts (JVM, Node, Dart, Jolt)**, proving both pre-fix `c1-2` JVM float and Node integer binary artifacts load, decode, re-encode, and execute without recompilation while preserving historical meanings.
- **Gate 7 (Profiles, Revisions, Capabilities, & Unchanged Contracts)**: `gate7-repaired-manifest-revisions-and-capabilities` — **GREEN on all 4 hosts (JVM, Node, Dart, Jolt)**.

---

## 8. Re-Pin & Migration Ledger (RULING 3, 4, 5)

### 8.1 New / Re-Pinned Values

| Artifact / Pin | Location | Value | Justification |
|---|---|---|---|
| `:yang/clojure` revision | `src/cljc/yin/repl/frontends.cljc` | `"c1-3"` (from `"c1-2"`) | RULING 3: changed Clojure source-to-Data mapping for integral float literals |
| `:yang/clojure` lowering profile | `src/cljc/yin/repl/frontends.cljc` | `:yang.clojure/constructed-collections-float-kind-v1` | RULING 3: preserves constructed-collection ordering contract + adds float-kind identity |
| `:yang/clojure` support profile | `src/cljc/yin/repl/frontends.cljc` | `:yang.clojure/c1-float-kind` | RULING 3: scoped float-kind Clojure support profile |
| `:yang/clojure` grammar capability | `src/cljc/yin/repl/frontends.cljc` | `:source-text` (JVM/Node), `:forms-only` (Dart/Jolt) | RULING 3/4: honest host capability claim |
| Gate 1 `token-digest-reference` | `test/yang/clojure_float_kind_test.cljc` | `"0.0"`=`4f3fd1e3c562a4c8690929fb1fd3b8e607d7d604bffdd4d5a406c737b735e578`, `"1.0"`=`1962a94c09507f12a3de739a3f584ce18ffbca152f16ebfb82ea15f3a650e90b`, `"1"`=`48fc721fbbc172e0925fa27af1671de225ba927134802998b10a1568a188652b`, `"1/2"`=`598aa35607a7350acc43a2ea659e1c94e8a8738705131d7670170d9a6a8b0b83`, `"1M"`=`28d6a5ec777e7e798d4be8e68242b049bc8794be9c3f6577f0af4451b0e0d05f` | Verified bit-identical across JVM & Node |
| Gate 5 `carrier-root-reference` | `test/yang/clojure_float_kind_test.cljc` | `:segment/blake3-3eaa6d2a6a66538adbe59324cbe5b9e9b5f05da946f7793187a559e7b4971bca` | Verified bit-identical across JVM, Node, Dart, & Jolt |
| Gate 3 `:integral-floats` corpus pin | `test/yang/clojure_collection_order_test.cljc` | `:root :segment/blake3-77ac71cec90eaba7887fabb9f28dabe94b52e80227709de3e83dc6f2309e0bd4`, `:applications 2`, `:keys-digest "2622d67b40b9c98529b2f245a1f1c9650d896843833a594dd01f889456a0fda3"`, `:c1-digest "cd58c99c96985edcbafda1a12fb684970a4e69ad3d7d3e3b81182eba9f1d928a"`, `:instruction-provenance-digest "78a888061880da6171d94e347aa1868a0dcccb1f81f8fa31625432851d0a42c4"` | Restored integral-float corpus case; verified identical on JVM, Node, & Jolt and equal to unmodified source `"{0.0 (+ n 1.0) 2e0 -0.0 1 1.5}"` |
| `test/yin/repl_test.cljc` (2 expectations) | `test/yin/repl_test.cljc:458,767` | Node now reads `#_#"%"` and `#_#(+ %1 1)` as `"['%]"` (matching JVM & Dart under `c1-3`); `==` renders as `"{:type :host-fn, :name '==}"` because `=` (kind-strict) and `==` (cross-kind) are distinct primitives | Direct semantic consequence of RULING 2 |

### 8.2 Explicitly Untouched Pins & Boundaries

- `dao.jing.cbor` encoding/decoding semantics and conformance digests: **untouched**
- `yin.vm.data` equality, key admission, and `Float64` `:carrier-coercion` policy: **untouched**
- Global `yin.vm/primitives`: **untouched**
- C1 marker key/version (`:yang/call-site`, `1`) and SPI version (`1`): **untouched**
- `yang.python` (`clojure-core-v1`, `py-v1`), `yang.typescript`, `yang.php` revisions and golden addresses: **untouched** (verified via `descriptor-profile-test`, `yang.python.antlr.float-address-test`, `yang.typescript.antlr.float-address-test`)
- Historical `c1-1` legacy fixture in `clojure_collection_order_test.cljc` and archived `fixorder-r3-node-order-floats-divergence.log`: **untouched**

### 8.3 Documentation Placements (RULING 4)

- `docs/design/dao.jing.cbor.md`: cross-reference to `yang.antlr.md` §8.8 for frontend numeric-token producer obligations.
- `docs/design/yang.antlr.md` (§8.8.1): normative Clojure float-kind contract, shared `yang.core.numeric-token` factory, revision `c1-3` / profiles, legacy custody, and four-host capability matrix.
- `docs/design/yang.preludes.md` (§8.1): retained shared-prelude producer & profile obligation (zero bare integral `Double` constants in host-quoted prelude tables, explicit `cbor/float64` & `yin.vm.float` reboxing).
- `src/cljc/yang/docs/yang_implementation.md`: Portability and Numeric-Kind Contract section covering `:source-text` vs `:forms-only` boundaries and carrier admission.
- `test/yang/clojure_collection_order_test.cljc`: `:floats` comment referencing the archived r3 divergence log and ruling `1795693415000`, paired with `:integral-floats` and `unmodified-source-integral-floats-test`.
