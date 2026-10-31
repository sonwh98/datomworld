# Plan: reconcile the antlr-multi architect plan against the codex review

## Context

The prompt `collab/1792294000000-architect-antlr-multi-reconcile.prompt.md` asks the
Architect to revise the plan in
`collab/1792289000000-architect-antlr-multi-plan.claude-cloud-fable.findings.md` in place,
answering codex's REQUEST CHANGES review
(`collab/1792292000000-reviewer-antlr-multi-plan-review.gpt-6.1-sol.findings.md`: six P1,
three P2). The deliverable is a document, not code. Nothing under `src/`, `test/`, `bb.edn`,
`deps.edn` or `docs/` changes in this round; the engineer round builds from the revised plan.

All nine findings were checked against the current tree (master `548f9faa`; the review's
worktree `datomworld-c4hang` is on the same base) and all nine hold. Evidence per finding is in
the ledger below and goes into the document verbatim. Nothing was executed; the two validator
counterexamples were re-derived by reading `packet.cljc:25-41`.

## Action (one step)

Overwrite `collab/1792289000000-architect-antlr-multi-plan.claude-cloud-fable.findings.md`
with the "Revised document (v2)" below, verbatim. Keep the file name (the reviewer re-reviews
the same path). The final chat response starts with the two `Completed-GMT / Completed-Local`
lines as the prompt requires. No commit (collab/ is never committed).

## Verification

- `git status` shows only that collab file modified; no tracked file touched.
- The document contains a "Delta vs v1" section listing every section that changed, and a
  ledger entry per finding (1-9) with concede/rebut and file:line evidence.
- Re-read the ledger against the review once: every "Required" clause has a corresponding
  design change in the named section.

---

# Revised document (v2)

```markdown
# ANTLR multi-target plan v2: one grammar, one parse-tree IR, N hosts, any language

Architect plan answering `collab/1792289000000-architect-antlr-multi-plan.prompt.md`,
revised against the codex review
`collab/1792292000000-reviewer-antlr-multi-plan-review.gpt-6.1-sol.findings.md`
(REQUEST CHANGES: P1 x6, P2 x3) per
`collab/1792294000000-architect-antlr-multi-reconcile.prompt.md`. It still gates the
engineer round `collab/1792282000000-engineer-antlr-multi-target.prompt.md`; no worktree
`datomworld-antlr-multi` exists yet.

The owner's requirements are unchanged and govern every revision below: (R1) ANTLR
works for JS and Dart before the legacy compiler retires; (R2) adding a new ANTLR
language stays mechanical.

## 0. Reconciliation ledger (for the re-review)

Every finding is conceded on evidence; three carry a qualification. Section numbers
point at the revised design.

| # | Sev | Verdict | Evidence | Revised in |
|---|---|---|---|---|
| 1 | P1 | **Conceded.** `validate!` checks only `i < k` and a `seen` set that is not updated between one node's children (`packet.cljc:34-38`): `[1 1 2]` and the index-ordered non-preorder tree both pass; the final count check (`:39-41`) cannot tell. Error packets have no `:yang.cst/nodes`, so `validate!` throws "no root" on a legitimate error packet (`:23-24`). A source-only `conform` cannot check names against grammar tables and v1's listed checks omitted real-token text and UTF-8 boundaries. | §1.2, §1.3 |
| 2 | P1 | **Conceded.** `packets` in `python_antlr_test.cljc:44-49` are built by the `packet` helper of `lower_portable_test.cljc:11-37`: every span `[0 0]`, no `:synthetic`, grammar `"hand"`. `node-shape` (`python_antlr_test.cljc:340-344`) drops `:span` and `:synthetic`. These are lowering fixtures; v1 wrongly called them parity goldens. | §5.1, §5.2, §5.4 |
| 3 | P1 | **Conceded.** `frontends.cljc:17-18` requires the parser namespace statically, and v1's `parser.cljc` (§4) required a generated recognizer on every host. An informational `:targets` set cannot stop a require that fails at compile time (on Dart a missing `package:` import fails the whole cljd compile). | §4.2, §6, §7 |
| 4 | P1 | **Conceded, with a qualification.** `test:cljs` depends only on `build:yin-repl-node` and `test:cljd` only on `build:yin-repl-peer` (`bb.edn:33-38`); both builds compile `yin.repl.frontends` and neither depends on generation. Qualification: babashka runs `:depends` in declaration order, so `test:clj`'s sibling `[build:yin-repl-node gen:python-antlr]` (`bb.edn:30`) works today only because `build:yin-repl-node` does not yet need the generated code. v2 attaches generation to the consuming build tasks and covers the `:cljs-yin-repl` alias (`deps.edn:67-72`), which has its own `:extra-paths`. | §3.4 |
| 5 | P1 | **Conceded.** v1 §2 allowed a shared read-mostly cache on single-threaded hosts; `yang.antlr.md` §4.3 ("Generated recognizers may use shared caches... isolate them by worker, process, or classloader, or provide instance-owned caches"; "Merely constructing a fresh parser is insufficient evidence") admits no such fallback. Both runtimes expose instance-owned caches, so no fallback is needed. | §2.3 |
| 6 | P1 | **Conceded.** `compilation_pipeline.cljs:50` requires `yang.python` and `:158` calls `py/compile` for the demo's `:python` branch; v1 §10's preconditions would all pass while deleting `yang/python.cljc` breaks the `:demo` and `:local` browser builds. `yang.php.md:1296-1306` already makes demo retargeting a retirement prerequisite for PHP. | §10 |
| 7 | P2 | **Conceded.** `yin_repl.cljs:38` requires `yin.repl.driver`, `driver.cljc:22` requires `yin.repl.frontends`; the `:demo`/`:local` browser builds (`shadow-cljs.edn`) therefore reach the catalog. Removing the gate puts the runtime and generated parser into the browser bundle at M2. v2 decides browser inclusion explicitly. | §6.2 |
| 8 | P2 | **Conceded.** v1 §4 listed `:clj` before `:cljd` in its own example against its own rule. `repl_probe.clj:59-68` uses `.charAt` and `.startsWith`; `.charAt` is not Dart `String` API, so purity did not make `scan` portable. M1 depended implicitly on M2 for a profile to construct recognizers with. | §2.4, §4.1, §11 |
| 9 | P2 | **Conceded.** TS upstream helpers are Java/C#/C++ with dialect and prediction-mode requirements (`yang.typescript.md:504-512`); PHP vendors its MIT Java helper beside the `.g4` (`yang.php.md:1319-1325`); the pub `antlr4` licence page carries MIT notices beside the BSD text; descriptive runtime strings enforce nothing; grammars-v4 does not guarantee cross-target compatibility (`yang.antlr.md:535-540`). | §3.1, §3.5, §7, §9 |

Two risks codex judged non-blocking stay as fixtures only: JS code-point input
(`CharStreams.fromString` in antlr4 4.13.2 decodes to code points) and Dart's acceptance
of a null-safe `<3.0.0` package under SDK `>=3.7.0`; both are verified by M0's executable
acceptance (§11), not assumed.

## Delta vs v1 (what the reviewer should re-read)

- New: §0 ledger; §1.2 outcome-specific `validate!`; §1.3 `conform` against source and
  vocabulary; §2.3 isolation mechanism per runtime, fallback removed; §2.4 portable probe;
  §3.4 build ordering and both classpaths; §3.5 pin enforcement and lockfiles; §4.2 missing
  target mechanism with a Java-only demonstration; §5 rewritten (genuine goldens, diagnostic
  parity rules); §6.2 browser decision; §7 eligibility condition; §10 consumer inventory and
  demo migration; §11 resequenced (M0 executable acceptance, `parser.cljc` moves to M1,
  browser and demo work in M2).
- Unchanged: the ruling that the IR is CST packet v1 (§1.1), the exporter contract (§2.1),
  the manifest shape apart from `:runtime` (§3.1), the boundary (§8), the owner
  requirements, the lanes policy.
- Corrected statements: §9 (TS/PHP helper facts, vendoring exception, licences).

## Context (unchanged from v1 except where marked)

`(lang :yang.python/antlr)` works on the JVM only. The generator
(`src/dev/yang_antlr_gen.clj`, `bb gen:python-antlr`) produces the Java target only,
`src/clj/yang/antlr/cst.clj` is the only exporter, and `src/cljc/yin/repl/frontends.cljc`
installs the ANTLR frontend under `#?(:cljd nil :clj ...)`. Node and Dart answer
`unavailable-parser` through `yin.repl/frontend-binding` (`repl.cljc:1696-1706`): a
selected frontend whose binding is not usable throws the diagnostic. That mechanism is
reused in §4.2.

Owner rulings (verbatim): "don't remove legacy until antlr works for JS and dart too";
"future languages will be added too and whatever is design should make it easy to add new
antlr supported languages"; "maybe an antlr intermediate representation is needed if antlr
doesn't have that concept already."

Facts (verified against the pinned sources; v1's list, two corrections marked):

- grammars-v4 `python/python3` at `8af0d4c2` ships helper bases for Java, JavaScript,
  Dart, C#, C++, Go, Python3, TypeScript; `desc.xml` tests `Cpp;CSharp;Java;JavaScript;Python3`.
  The Dart helper is not exercised upstream.
- The JS and Dart lexer bases carry the same NEWLINE/DEDENT `commonToken` construction the
  Java patch fixes; the patch is ported per target.
- JS helpers: ESM, `import antlr4 from 'antlr4'`, circular import with the generated parser.
  Dart helpers: `package:antlr4/antlr4.dart`, `dart:io`. No licence notice in either.
- Runtimes: npm `antlr4` 4.13.2 (BSD-3-Clause, ESM, node>=16); pub `antlr4` 4.13.2 (SDK
  `>=2.12.0 <3.0.0`, deps `logging`, `collection`). **Corrected (finding 9):** the pub
  package's published licence combines BSD-3-Clause with MIT notices for bundled parts; the
  manifest records the full published text at pin time, not "BSD-3-Clause".
- ANTLR has no target-neutral tree; the repo's neutral form is CST packet v1
  (`cst.clj:16-35`), read through `packet.cljc`. **Corrected (finding 2):** the hand-built
  packets of `lower_portable_test.cljc` and `python_antlr_test.cljc` are lowering fixtures
  (zero spans, no synthetic flags, grammar `"hand"`), not parser goldens.
- TS (`yang.typescript.md` 5.3) and PHP (`yang.php.md` 6) are JVM-only for helper
  availability reasons: TS has Java/C#/C++ bases upstream (plus dialect-flag and
  prediction-mode requirements of its own); PHP has Java (vendored, MIT), JavaScript,
  C#, C++, Python3, Antlr4ng bases and no Dart one.

## The design in one paragraph (unchanged)

ANTLR gives "one grammar, N generated recognizers". The repo adds the other half: one
target-neutral parse-tree IR that every host's exporter produces and every language's
lowering consumes. That IR is CST packet v1, now formally owned by `packet.cljc` with an
outcome-specific validator and a conformance oracle. A language is: a manifest (grammar,
per-target helpers, patches, digests, runtime pins), a ~40-line `parser.cljc` with one
reader-conditional branch per generated target, its lowering over the IR, one catalog
`install` line, and its goldens. Nothing per language touches the generator, the exporter,
the packet reader, or the REPL.

## 1. The IR: `:yang.cst/v1`, formalized

### 1.1 Home and ruling (unchanged)

The IR is the existing packet shape, not a new schema. `packet.cljc` owns it: the shape
doc at `cst.clj:16-35` moves there verbatim; `packet-version` moves there. D1 (the
diagnostic namespace) stays with lift L2 of `yang.typescript.md` 17.

### 1.2 Structural validation is outcome-specific (revised, finding 1)

`packet/validate!` is rewritten; its contract is per outcome:

- Every outcome: `:yang.cst/version 1`, `:yang.cst/outcome` in
  `#{:yang.cst/ok :yang.cst/syntax-error :yang.cst/source-error}`, `:yang.cst/complete true`,
  `:yang.cst/grammar` a string, `:yang.cst/unit` present. Unknown outcome: malformed.
- `:yang.cst/ok`: `:yang.cst/root 0`; `:yang.cst/nodes` a non-empty vector; no
  `:yang.cst/errors`. **Preorder, uniqueness and reachability are established by one
  recursive walk** from node 0 with a counter: visiting node `i` requires `(:id (nth nodes
  counter)) = counter = i`, then walks its `:children` in order, each child's id being the
  counter at the moment it is visited; after the walk the counter equals `(count nodes)`.
  This rejects duplicate edges (the second visit finds the counter past the id), the
  index-ordered non-preorder tree (`[0 1 3 2]`: node 1's child must be 2), forward
  references, and unreachable nodes, in one pass. A rule node's `:children` is a vector
  (possibly empty); a token node has no `:children`. `:kind` in `#{:rule :token}`; rule
  nodes carry a string `:rule`, token nodes a string `:type` and string `:text`; every node
  a `[s e]` span of two non-negative integers with `s <= e`.
- `:yang.cst/syntax-error` and `:yang.cst/source-error`: no `:yang.cst/nodes`, no
  `:yang.cst/root`; `:yang.cst/errors` a non-empty vector of records whose `:kind` is
  `:syntax-error` (line >= 1, column >= 0, span nil or `[s e]`) or `:source-error`, each with
  a string `:message`.

A legitimate error packet therefore validates. The tests `validate!` gains: the two codex
counterexamples, an error packet, a token with children, a rule without `:children`.

### 1.3 `conform`: content against source and vocabulary (revised, finding 1)

`packet/conform` takes `packet`, the sealed `source` string, and a `vocabulary`
`{:rule-names #{...} :token-names #{...}}`. It runs after `validate!`. Returns the packet
or throws a qualified `malformed-cst` naming the first violated invariant. Checks, for
`:yang.cst/ok`:

1. **Names**: every `:rule` is in `:rule-names`; every `:type` is in `:token-names` or is
   `"EOF"`. The vocabulary is exported by each host (`cst/vocabulary worker`, §2.1), so the
   check is against the generated tables of the running host; cross-host name-set
   equality is a separate golden (§5.1).
2. **Byte bounds and UTF-8 boundaries**: let `B` be the set of byte offsets that start a
   code point in `source`, plus `utf8-length`. Every span end is in `B`. (Portable: build
   `B` by walking code points with `utf8-length` as `cst.clj:68-88` does; no host string
   internals.)
3. **Real-token text**: for a token without `:synthetic`, `s < e` and the source bytes at
   `[s e]` decode to exactly `:text` (decode via the code-point index that `B` gives;
   compare strings).
4. **Terminal ordering** (stated separately from rule spans): walking tokens in preorder,
   each real token's `s` is >= the previous real token's `e`; a synthetic token has `s = e`
   and sits at an offset >= the previous real token's `e` and <= the next real token's `s`.
5. **Rule extents over real descendants**: a rule's span equals `[min s, max e]` over the
   real tokens in its subtree; a rule with no real descendant is zero-width at an offset
   between its neighbouring real tokens (rule 4 applied to the empty rule's position).
6. **Synthetic flag is justified**: a token marked `:synthetic` either has text different
   from the source at its position or would overlap the previous real token; a token not
   marked has text equal to the source at its span (this is check 3 from the other side,
   so an exporter cannot hide a misplaced token behind the flag).

For error outcomes: each `:syntax-error` span, when present, satisfies 2; `:line`/`:column`
are consistent with the span (the line containing byte `s` and the code-point column within
it, computed from `source`). For `:source-error`: nothing beyond `validate!`.

Not checked by `conform`: that the root's end equals `utf8-length` (false when the source
ends in untokenized trivia); fixtures assert it where true.

`conform` costs nothing at runtime (tests only).

### 1.4 Normative invariants (restated; they are what §1.2-1.3 check)

1. Ids are DFS-preorder indexes, root 0, children a tree (§1.2 walk).
2. `:rule` and `:type` come from the host's generated tables (§1.3.1); `"EOF"` for EOF.
3. Spans are half-open UTF-8 byte offsets on code-point boundaries (§1.3.2); each target
   converts its own index unit at the boundary.
4. A real token's text is the source at its span; a synthetic token is zero-width (§1.3.3, 6).
5. Terminals are ordered and non-overlapping; rule spans cover real descendants (§1.3.4, 5).
6. A syntax error yields an error packet with errors and no nodes (§1.2).
7. `:yang.cst/grammar` names grammar revision and tool; the target does not appear in the
   packet, in node fields, or in diagnostic text (§5.3).

## 2. The exporter: `yang.antlr.cst` on three hosts

### 2.1 Contract (v1 plus one export)

`cst.clj` stays. `cst.cljs` and `cst.cljd` implement the same public names: `make-worker`,
`parse-unit`, `parse-transform`, `utf8-bytes`, `first-failure` (§2.4), and **`vocabulary`**
(new): `{:rule-names #{...} :token-names #{...}}` from the worker's parser (`ruleNames`,
symbolic and literal names). Profile contract unchanged:
`{:grammar-id :make-lexer :make-parser :start}`.

`parse-transform` and `source-error` are host-free and move to a cljc helper (engineer's
call on the namespace; minimal diff), so each host file holds only the host walk.

### 2.2 Host walks (unchanged from v1)

Node: `CharStreams.fromString` (code points), `CommonTokenStream`, an `ErrorListener`
subclass, `ctx.children`, `ctx.ruleIndex`, `parser.ruleNames`, `symbolicNames`/`literalNames`,
`node.symbol.{type,start,stop,text}`. Dart: `InputStream.fromString`, `CommonTokenStream`,
`BaseErrorListener`, `childCount/getChild`, `TerminalNode.symbol`, `ruleNames`, `vocabulary`.
Recorded ClojureDart traps apply (reader-conditional ordering, `for` over chunked seqs,
multi-key `assoc` on nil, unary minus, protocol `[_ _]`).

### 2.3 Worker isolation: the mechanism per runtime, no fallback (revised, finding 5)

The v1 fallback ("document that the host is single-threaded and the cache is read-mostly")
is removed. `yang.antlr.md` §4.3 requires isolation; both runtimes support the JVM's
mechanism exactly:

| Runtime | Instance-owned caches | How the worker installs them |
|---|---|---|
| JVM (today) | `DFA[]` per decision, `PredictionContextCache` | `setInterpreter` with `LexerATNSimulator`/`ParserATNSimulator` (`cst.clj:242-258`) |
| antlr4 npm 4.13.2 | `DFA` array, `PredictionContextCache` exported | `lexer._interp = new LexerATNSimulator(lexer, atn, decisionToDFA, cache)`, same for the parser's `_interp` |
| antlr4 pub 4.13.2 | `DFA`, `PredictionContextCache` | `lexer.interpreter = LexerATNSimulator(lexer, atn, decisionToDFA, cache)`, same for the parser |

`make-worker` on each host builds fresh DFA arrays and caches from the recognizer's ATN
(as `cst.clj:100-124`); `parse-unit` installs them on every fresh lexer and parser. The
generated recognizers' static shared caches are never used by a worker's parse.

Verification (in `yang.antlr.conformance`, every host): an **interleaved-worker test**:
workers `w1`, `w2`; parse A on `w1`, B on `w2`, A' (a variant of A that exercises other
decisions) on `w1`, then A on `w2`; every packet equals the packet a fresh worker gives
for the same source; `w1`'s and `w2`'s DFA arrays and caches are distinct objects
(`identical?` false); and on the JVM, Node and Dart alike the recognizer's static
`_decisionToDFA`/`_sharedContextCache` is not the object installed on the worker. If a
runtime's generated code reaches its static cache through a path the worker cannot
redirect, that is a blocking M1 finding, not a documented exception.

### 2.4 Probe (revised, finding 8)

`scan`/`probe` move to `src/cljc/yang/python/antlr/repl_probe.cljc` **with portable string
access**: `(nth text i)` for a character, `subs` plus `=` (or `clojure.string/starts-with?`
on a `subs`) for the closer test, `count` for length; no `.charAt`, `.startsWith`,
`.offsetByCodePoints`. The host-specific `first-failure` becomes a `yang.antlr.cst` export
(`first-failure worker source` answering `nil | :end | :early`) per host. The probe's
existing JVM tests become cljc and run on three hosts (a scan-golden table is portable by
construction).

## 3. The build: one manifest, N targets

### 3.1 Manifest (v1 shape; `:runtime` becomes structured, finding 9)

As v1, with these corrections:

```clojure
:targets
{:java       {:runtime {:maven "org.antlr/antlr4-runtime" :version "4.13.2"} ...}
 :javascript {:runtime {:npm "antlr4" :version "4.13.2"} ...}
 :dart       {:runtime {:pub "antlr4" :version "4.13.2"} ...}}
:license {...  "antlr4 4.13.2 (npm)" "BSD-3-Clause (LICENSE.txt at pin)"
          "antlr4 4.13.2 (pub)" "<full published licence text summary: BSD-3-Clause plus the MIT notices it carries>"
          "JavaScript/Python3LexerBase.js" "no notice; UNRESOLVED" ...}
```

Helper files are fetched by digest into `build/antlr/<grammar>/upstream` as today. The
"vendor `.g4` only" rule has one recorded exception: a helper with an explicit licence
notice may be vendored beside the `.g4` with its notice (PHP's `Java/PhpLexerBase.java`,
`yang.php.md:1319-1325`); the manifest's `:files` entry says `:vendored` either way, as
today's generator already supports.

### 3.2 Patches, digests, landing dirs (unchanged from v1)

Per-target `:patches` (positioned-token port for JS and Dart), per-target `:generated`
digests and `generated-digests.edn`, ASCII check per target. Java byte-identical. Node:
`build/antlr/python3/js` with ESM under `yang/antlr/gen/python3/`; Dart: `lib/antlr/python3`
(`lib/` is gitignored, package root `datomworld_cljd`).

### 3.3 Generator loop (unchanged)

`yang_antlr_gen.clj` loops over `:targets`; `-Dlanguage=` is the per-target switch; the
package-clause prepend and `javac` stay Java-only.

### 3.4 Build ordering and classpaths (revised, finding 4)

- `bb.edn`: `gen:python-antlr` becomes `gen:antlr` (all grammars, all targets,
  `--grammar`/`--target` filters). **`:depends [gen:antlr]` is attached to the consuming
  build tasks**: `build:yin-repl-node` and `build:yin-repl-peer`. `test:clj`,
  `test:all:clj`, `test:slow:clj` keep a direct dependency too (the JVM lane loads classes
  without a build task). The `test:cljs`/`test:cljd` families inherit through the builds.
  Every remaining `gen:python-antlr` reference (docs, `build-n-test.md`) is renamed.
- `deps.edn`: `build/antlr/python3/js` is added to **both** `:cljs` and `:cljs-yin-repl`
  `:extra-paths` (`deps.edn:39,67-72`); the `:dev` alias too, since `watch demo` runs
  through it. Dart needs no path: generated files are package sources.
- Acceptance is a **clean direct build**, not a test wrapper: from a clean `build/` and
  `lib/antlr`, `bb build:yin-repl-node` and `bb build:yin-repl-peer` each succeed
  on their own, and `clj -M:cljs-yin-repl` starts.

### 3.5 Pins, lockfiles, consistency (revised, finding 9)

- `package.json` gains `"antlr4": "4.13.2"` (exact) and `package-lock.json` is updated in
  the same commit; `pubspec.yaml` gains `antlr4: 4.13.2` and `pubspec.lock` likewise.
- `gen:antlr` **enforces** that for every target in the manifest the runtime pin equals
  the installed/locked version: Maven via the `:antlr-gen` classpath (as today), npm via
  `package-lock.json`'s resolved `antlr4` version, pub via `pubspec.lock`. A mismatch fails
  generation with the three versions printed. Tool and runtimes remain one release.

## 4. The parser namespace per language

### 4.1 Shape (ordering corrected, call typed; finding 8)

```clojure
(ns yang.python.antlr.parser
  (:require [yang.antlr.cst :as cst] [yang.stage :as stage]
            #?@(:cljd [["package:datomworld_cljd/antlr/python3/Python3Lexer.dart" :as l]
                       ["package:datomworld_cljd/antlr/python3/Python3Parser.dart" :as p]]
                :cljs [["antlr4" :as antlr4]
                       ["/yang/antlr/gen/python3/Python3Lexer.js" :default Python3Lexer]
                       ["/yang/antlr/gen/python3/Python3Parser.js" :default Python3Parser]]
                :clj  []))
  #?(:clj (:import (yang.python.antlr.gen Python3Lexer Python3Parser))))

(def grammar-id "grammars-v4/python/python3@8af0d4c2... antlr-4.13.2")   ; one string, every host

(def profile
  {:grammar-id  grammar-id
   :make-lexer  #?(:cljd (fn [cs] (l/Python3Lexer cs))  :cljs (fn [cs] (Python3Lexer. cs))  :clj (fn [cs] (Python3Lexer. cs)))
   :make-parser #?(:cljd (fn [ts] (p/Python3Parser ts)) :cljs (fn [ts] (Python3Parser. ts)) :clj (fn [ts] (Python3Parser. ts)))
   :start       #?(:cljd (fn [^p/Python3Parser p] (.file_input p)) :cljs (fn [p] (.file_input p)) :clj (fn [^Python3Parser p] (.file_input p)))})
```

`:cljd` first in every conditional (recorded trap). The Dart branch types the parser
argument so the entry call is static. `make-worker`, `parse-source`, `open-stage`,
`step-stage` unchanged.

### 4.2 Hosts without a target: explicit composition (new, finding 3)

A language's `parser.cljc` has exactly one `:require` branch and one profile branch per
target in its manifest `:targets`. For a host whose target is absent, the branch is
**absent from the require form** (`#?@(:cljd [] ...)`, so no import is attempted; on Dart a
missing `package:` import is a compile failure, which is why nil-valued vars are not
enough) and `profile` is `nil` on that host:

```clojure
(def profile #?(:cljd nil :cljs nil :clj {...}))     ; a Java-only language
```

The catalog installs through one helper in `frontends.cljc`:

```clojure
(defn- antlr-install [catalog manifest profile binding]
  (frontend/install catalog manifest (if profile binding (dissoc binding :parse))))
```

A binding without `:parse` is not usable, so selecting the language answers
`unavailable-parser` through the existing path (`repl.cljc:1696-1706`); the engineer
confirms `usable-binding` answers nil for it (otherwise install `{}`). The frontend
manifest's `:yang.grammar/targets` set is **derived** from the grammar manifest's
`:targets` keys at generation time (the generator writes `build/antlr/<g>/targets.edn`,
read by a macro or copied as data; engineer's call), and a per-language test asserts on
each host `(= (contains? targets <host-target>) (some? parser/profile))`, so the three
sources (manifest, parser branches, catalog) cannot disagree silently.

**Demonstration (M2 acceptance)**: a test-only Java-only language,
`test/yang/javaonly/antlr/parser.cljc`, whose `:clj` branch reuses the Python3 Java
recognizers and whose other branches are absent, installed beside Python in a test
catalog on all three hosts: the JVM parses through it; Node and Dart compile, build, and
answer `unavailable-parser` for it while `:yang.python/antlr` parses. No new grammar is
needed for the demonstration.

## 5. CST equivalence: the parity story (rewritten, findings 1, 2)

### 5.1 Genuine goldens, exported by the JVM

`src/dev/yang_antlr_goldens.clj` (the `dht-import-v1.edn` pattern) writes
`test/yang/antlr/fixtures/python3-goldens.edn`: for each fixture source, the **complete**
packet the JVM exporter produces (every node field, edges, spans, synthetic flags,
`:yang.cst/grammar`, errors), plus `python3-vocabulary.edn` (the JVM's rule-name and
token-name sets). The corpus: the four session lines of `python_antlr_test`, the sources
of every `cst_export_test` case (`x = 1\n`, the `é𝄞` line, the indentation case, the two
missing-final-newline cases, `def f(:` and `x = $`), the owner's comprehension line, and
an empty source. Regenerating is a `bb` task; a drift in the committed file is a
reviewable diff.

### 5.2 Three layers

1. **Exporter conformance (per target, language-free)**: `yang.antlr.conformance` (cljc
   test helper) takes a profile, the goldens, and the vocabulary. On the running host, for
   each fixture: `validate!` and `conform` (against the source and the host's own
   `cst/vocabulary`) pass; the host's packet **`=` the full golden packet**; the host's
   vocabulary sets `=` the golden sets. Plus the interleaved-worker test (§2.3) and the
   UTF-8 fixture asserting root end `= utf8-bytes` where the fixture ends in a real token.
   `cst_export_test.clj` becomes `.cljc` and runs on three hosts through it.
2. **Packet parity (per language)**: `precompiled-packets-are-the-parsers-test` is split:
   (a) the hand-built lowering fixtures' `node-shape` equals the golden packets'
   `node-shape` (fixtures stay what they are, lowering inputs; this pins them to real
   parses); (b) on every host the installed parser's packet `=` the golden for each
   session line. `langs` becomes `[:yang.python/cst :yang.python/antlr]` on all hosts.
3. **Program parity (end to end)**: the REPL session scenarios, the prompt-suppress
   regression and the owner's comprehension line run through `:yang.python/antlr` on Node
   and Dart; expected output is the JVM's. Layer 2 implies 3 because the lowering is
   portable and already proven on packets; layer 3 is what the owner sees.

### 5.3 Diagnostic parity rules (new, finding 2)

For error packets the cross-target contract is `=` on the whole packet after
**message normalization in the exporter**: `:kind`, `:line`, `:column`, `:span`, error
count and order are compared exactly; `:message` is compared after each exporter applies
`packet/normalize-message` (trim, collapse internal whitespace, normalize the runtime's
token-display quoting to ANTLR's Java form `'x'`/`<EOF>`). Where a runtime's
`DefaultErrorStrategy` wording still differs, the exporter rewrites to the Java wording
(invariant 7: the target does not appear in the packet). A fixture pair exists for a parser
error (`def f(:`) and a lexer error (`x = $`), so both listener paths are covered. If a
wording cannot be normalized mechanically, that is an M1 finding to the Architect, not a
relaxed fixture.

### 5.4 Fixtures kept as they are

`lower_portable_test.cljc`'s `packet` helper and `python_antlr_test`'s `packets` remain
lowering fixtures (zero spans, `"hand"`); nothing asks `conform` to pass them. v1's claim
"conform passes every precompiled packet" is withdrawn.

## 6. Catalog and SPI changes

### 6.1 Gate removal (as v1)

`frontends.cljc` loses the `#?(:cljd [] :clj ...)` wrappers; the ANTLR frontend installs
on every host through `antlr-install` (§4.2). Revision "f3" bumps; the grammar map gains
`:yang.grammar/targets` (derived, §4.2). No SPI bump. Docstrings flip. Doc amendments as
v1 §6 plus `yin.repl.md:48-70` (the "JVM only" paragraph).

### 6.2 Browser inclusion: decided (new, finding 7)

The browser builds (`:demo`, `:local`) reach the catalog via `driver.cljc:22`, so after
6.1 they include the npm runtime and the generated JS parser. **Decision: include it.**
Reasons: R1 asks for ANTLR on JS; the compilation demo must stop calling the legacy
compiler before M3 (§10), and an in-browser parser is the simplest retarget; the repo is
dev-only. Acceptance at M2: `clj -M:cljs -m shadow.cljs.devtools.cli release demo` compiles
and the main module's minified size before and after is recorded in the M2 report.
Fallback, chosen before merging if the delta exceeds about 1.5 MB minified: a separate
composition, where `driver` takes the catalog from the host and the browser passes one
without the ANTLR entry (the frontend is then installed with no `:parse`, §4.2, so the
browser REPL answers `unavailable-parser` honestly) while the demo keeps the legacy
path until a packet-based retarget (PHP's B8.9 pattern) lands.

## 7. Adding a language after this lands (the mechanical recipe; findings 3, 9)

**Eligibility condition** (stated up front): the grammar's embedded actions are
target-portable or absent, and a helper base exists and is tested for each target you
claim; grammars-v4 does not guarantee cross-target compatibility (`yang.antlr.md:535-540`),
so a helper without upstream CI (Dart today) is accepted only through the goldens of §5.

For language L with grammar G:

1. `antlr/<G>/manifest.edn`: vendor the `.g4` files (and any helper that carries its own
   notice, §3.1); list helpers per target, patches, licence rows, runtime pins. A target
   without a helper is **omitted from `:targets`**.
2. `bb gen:antlr --grammar <G>`; record digests; the pin check passes.
3. `src/cljc/yang/<L>/antlr/parser.cljc`: one require branch and one profile branch per
   target in `:targets`; absent targets have no branch and `profile` nil there (§4.2).
4. The lowering over the IR through `packet/*` only (§8).
5. Goldens: sources for `yang_antlr_goldens` and a vocabulary file; the targets test of
   §4.2.
6. `frontends.cljc`: one `antlr-install` line.
7. Optional: a probe (`scan` in cljc over `cst/first-failure`).

Steps 2-6 are the same for every language; the generator, exporter, packet reader, REPL
and catalog are not edited. A Java-only language coexists with Python on Node and Dart
builds by construction (demonstrated in M2). TS and PHP gain a host the moment a helper
for that target is added to their manifests.

## 8. Boundary (unchanged from v1)

Per language: lowering, scope, prelude, publication, primitives, probe rules, refusal and
diagnostic namespaces, entry rule, trailing-newline normalization. Generic: generator,
`packet.cljc`, the three `yang.antlr.cst` files, `yang.antlr.conformance`, `yang.frontend`.
The IR carries names only; no language-specific packet fields; more needs an IR version.

## 9. Reconciliation with the TS and PHP designs (corrected, finding 9)

- (a) Already covered: manifest and vendoring discipline (`yang.typescript.md` 5.1,
  `yang.php.md` 6), the worker profile contract, per-target helper versioning
  (`yang.antlr.md` 4.2), lifts L1-L4 (section 17). This plan edits none of them.
- (b) New for Python on JS/Dart: multi-target generation, the exporters, the IR
  formalization (§1), the conformance helper and goldens, the probe split, the gate
  removal and availability mechanism, the pins and their enforcement.
- (c) Generalizes: everything in (b) except Python's manifest rows and profile. For TS
  the remaining per-target work is the helper port **plus** its dialect flag and
  prediction-mode pins per target (`yang.typescript.md:504-518`); for PHP, the upstream
  JavaScript base makes Node a helper-port problem and Dart a new-helper problem
  (`yang.php.md:1319-1331`). "Add the helper to the manifest" is the mechanical part; the
  helper itself is not.
- Collision to sequence: L2 edits `packet.cljc` (D1); this plan edits `validate!` and adds
  `conform`. Whichever lands second rebases; different functions.

## 10. Legacy retirement (revised, finding 6)

Preconditions, all demonstrable before the round opens:

1. M2 landed with layers 1-3 green on three hosts and the comprehension line evaluating
   on Node and Dart (R1).
2. **Consumer inventory**: a fresh `grep -rn 'yang\.python\b'` over `src/` and `test/`,
   excluding `yang.python.antlr`, with every hit dispositioned. Today's inventory:
   `compilation_pipeline.cljs:50,158` (demo, `py/compile`); `frontends.cljc:15,68-69`
   (legacy install); `repl.cljc:894` and `yin.repl.md` (docs naming `:yang.python/legacy`);
   `src/cljc/yang/docs/{python,architecture,README}.md`; `test/yang/python_test.*` and
   every test selecting `:yang.python/legacy` (for example
   `python_antlr_test.cljc:329-334`); `subsystems.edn:50-51` (prefix stays, covers antlr).
3. **Demo migration landed** (in M2 under §6.2, or as its own slice if the fallback is
   chosen): the demo's `:python` branch parses through the ANTLR frontend and lowers
   through `yang.python.antlr`; the engineer verifies the pipeline's AST consumer
   (`vm/ast->datoms`) accepts the ANTLR lowering's output, or the demo takes checked-in
   packets (PHP's B8.9 pattern).
4. Every legacy test scenario ported or recorded as dropped; docs name the retirement.

The round then deletes `src/cljc/yang/python.cljc` and its tests, the `:yang.python/legacy`
install, and amends the docs. Not part of the first engineer round.

## 11. Implementation slices (resequenced; findings 4, 8)

| Slice | Scope | Acceptance |
|---|---|---|
| M0 build | manifest `:targets` and structured `:runtime`; generator loop; JS and Dart generation with ported patches; digests; `gen:antlr` attached to `build:yin-repl-node`/`build:yin-repl-peer` and the JVM test tasks; both cljs classpaths; `package.json`+lock, `pubspec.yaml`+lock; pin-consistency check | Java digests byte-identical; JS/Dart artifacts generated, ASCII, digest-pinned; `bb gen:antlr` reproducible from clean; clean direct `bb build:yin-repl-node` and `bb build:yin-repl-peer` succeed; **executable smoke on Node and Dart**: a test namespace requires the generated recognizer and runtime, constructs lexer and parser, calls `file_input` on `"x = 1\n"` and reads back `ruleNames`; `CharStreams.fromString` code-point mode confirmed; `pub get` resolves `antlr4 4.13.2` under SDK `>=3.7.0`; Dart dynamic-interop policy: the cljd compile log's dynamic-call warning count for the smoke namespace is zero (typed constructor and entry calls) |
| M1 IR + exporters + profile | `validate!` rewrite (§1.2) and `conform` (§1.3) with the codex counterexamples as tests; `cst/vocabulary`; shared transform; `cst.cljs`, `cst.cljd` with instance-owned caches (§2.3); **`parser.cljc` for Python** (moved here from M2 so M1 has its own profile); `yang_antlr_goldens` and the committed goldens; `yang.antlr.conformance` with the interleaved-worker test; `cst_export_test` to cljc; probe split with portable strings (§2.4) | layer 1 green on JVM, Node, Dart: full-packet `=` to goldens including the UTF-8 fixture, indentation, both error fixtures under the §5.3 rules; vocabulary sets equal; interleaved-worker test green; `validate!` rejects both counterexamples and accepts error packets |
| M2 catalog + REPL + browser | gate removal, `antlr-install`, derived `:yang.grammar/targets`, targets test, Java-only fixture language demonstration (§4.2); revision bump; layers 2-3 on all hosts; browser release build and bundle measurement, demo `:python` retarget (§6.2, §10.3); doc amendments (§6.1) | layers 2 and 3 green on three hosts; `(lang :yang.python/antlr)` evaluates the comprehension line at the Node and Dart prompts; Node and Dart answer `unavailable-parser` for the Java-only fixture while Python parses; `release demo` compiles, size delta recorded, decision A confirmed or fallback B applied; quick tiers green per host; kondo + cljstyle clean |
| M3 legacy retirement | §10 | its own round, opens only with the §10 inventory dispositioned |

Lanes per slice: `bb test:clj` per iteration; full three-host quick tier at each landing,
one lane set at a time, foreground; focused ANTLR namespaces with `DATOM_SLOW_TESTS=1` at
M2.

## 12. Risks (v1 list, amended)

- Index units (JS/Dart UTF-16 vs code points): the `é𝄞` fixture and `conform`'s boundary
  check are the guards; highest-probability parity defect.
- Dart helper untested upstream; bounded to one patched file; pub SDK bound verified in M0.
- Circular ESM import between the JS lexer base and generated parser under shadow-cljs
  classpath JS; verified by the M0 smoke (fallback `:js-options :resolve`).
- Diagnostic wording across runtimes may resist normalization (§5.3): escalate, do not
  relax.
- Licence: four more helpers without notices; `:blocked-until` stays; pub licence recorded
  in full.
- Browser bundle growth (§6.2): measured, with a named fallback.
- Generated static caches reachable past the worker on some runtime (§2.3): blocking M1
  finding if found.
- Generation needs network on first run; ClojureDart traps in the exporter walk.

## Verification (end to end)

1. `bb gen:antlr` from clean `build/` and `lib/antlr`; Java digests vs
   `git show HEAD:antlr/python3/manifest.edn`; pin check passes.
2. Clean direct `bb build:yin-repl-node`, `bb build:yin-repl-peer`; `clj -M:cljs-yin-repl`
   starts.
3. `bb test:clj`, `bb test:cljs`, `bb test:cljd` quick tiers, foreground, one at a time;
   `Testing yang.antlr.conformance-test` and `yin.repl.python-antlr-test` appear in the
   Node and Dart output.
4. Node prompt and Dart peer: `(lang :yang.python/antlr)` then the comprehension line;
   output equals the JVM's. The Java-only fixture answers `unavailable-parser` there.
5. `clj -M:cljs -m shadow.cljs.devtools.cli release demo`; size delta recorded.
6. `DATOM_SLOW_TESTS=1` focused ANTLR namespaces.
```
