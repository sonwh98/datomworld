# ANTLR multi-target plan: one grammar, one parse-tree IR, N hosts, any language

Architect plan answering `collab/1792289000000-architect-antlr-multi-plan.prompt.md`.
This plan gates the engineer round `collab/1792282000000-engineer-antlr-multi-target.prompt.md`
(no worktree `datomworld-antlr-multi` exists yet; nothing of that round has started).

## Context

`(lang :yang.python/antlr)` works on the JVM only. The generator
(`src/dev/yang_antlr_gen.clj`, `bb gen:python-antlr`) produces the Java target
only, `src/clj/yang/antlr/cst.clj` is the only parse-tree exporter, and
`src/cljc/yin/repl/frontends.cljc` installs the ANTLR frontend under
`#?(:cljd nil :clj ...)`. Node and Dart answer `unavailable-parser`.

Owner rulings (verbatim): "don't remove legacy until antlr works for JS and dart
too"; "future languages will be added too and whatever is design should make it
easy to add new antlr supported languages"; "maybe an antlr intermediate
representation is needed if antlr doesn't have that concept already."

Facts established during exploration (all verified against the pinned sources):

- grammars-v4 `python/python3` at the pinned revision `8af0d4c2` ships helper
  bases for **Java, JavaScript, Dart**, C#, C++, Go, Python3, TypeScript. Its
  `desc.xml` lists tested targets `Cpp;CSharp;Java;JavaScript;Python3`: the
  **Dart helper is not exercised by upstream CI**.
- The JS and Dart lexer bases carry the same NEWLINE/DEDENT `commonToken`
  construction the Java base has (the one the manifest's `:patches` fixes so
  every token's span is its source). The patch must be ported per target.
- JS helpers: `import antlr4 from 'antlr4'` (ESM), `Python3LexerBase extends
  antlr4.Lexer`, imports `./Python3Parser.js` (circular with the generated
  lexer; upstream ships it that way). Dart helpers: `import
  'package:antlr4/antlr4.dart'`, `abstract class Python3LexerBase extends
  Lexer`, plus `dart:io`. Neither JS nor Dart helper has a licence notice, the
  same unresolved situation as the two Java helpers.
- Runtimes: npm `antlr4` 4.13.2 (BSD-3-Clause, ESM, `engines node>=16`, no
  runtime deps); pub `antlr4` 4.13.2 (BSD-3-Clause, SDK `>=2.12.0 <3.0.0`,
  deps `logging`, `collection`). Both equal the pinned tool 4.13.2.
- ANTLR has **no target-neutral tree representation**: each target materializes
  its own `ParserRuleContext`/`TerminalNode` objects. The repo already has the
  neutral form: the CST packet v1 documented in `cst.clj` lines 16-35 and read
  by `src/cljc/yang/antlr/packet.cljc`. The lowering
  (`src/cljc/yang/python/antlr/lower.cljc`) reads only `:kind :rule :children
  :type :text :span :synthetic` through `packet/*`. Hand-built packets already
  drive the lowering on Node and Dart (`lower_portable_test.cljc`, the
  precompiled `packets` of `test/yin/repl/python_antlr_test.cljc`, whose
  `precompiled-packets-are-the-parsers-test` asserts they equal the JVM
  parser's output).
- The TS design (`yang.typescript.md` 5.3) and PHP design (`yang.php.md` 6)
  both say "the local parser is JVM-only; Node and Dart go through packets or a
  configured service" for a **concrete** reason: upstream TypeScript helpers
  exist for Java/C#/C++ only; PHP has a JavaScript base but no Dart one. The
  JVM-only statements are helper-availability facts, not architecture.

## The design in one paragraph

ANTLR already gives "one grammar, N generated recognizers". What the repo lacks
is the other half: **one target-neutral parse-tree IR** that every host's
exporter produces and every language's CST-to-UAST converter consumes. That IR
exists today as CST packet v1; this plan names it, moves its ownership to the
portable namespace, and makes the exporter a three-host namespace. A language
then is: a manifest (grammar + per-target helpers + patches + digests), a
~40-line `parser.cljc` with three reader-conditional constructor profiles, the
language's own lowering over the IR, one catalog `install` line, and the
conformance fixtures. Nothing per language touches the generator, the exporter,
the packet reader, or the REPL.

## 1. The IR: `:yang.cst/v1`, formalized (owner addendum)

Ruling: **the IR is the existing CST packet shape, formalized, not a new
schema.** Manifests already name it (`:yang.grammar/export-profile
:yang.cst/v1`), `packet.cljc` already validates its tree structure, and every
consumer already reads it. A second schema would move every Python golden for
no gain.

- **Home**: `src/cljc/yang/antlr/packet.cljc` becomes the IR's owner. The shape
  doc at `cst.clj:16-35` moves there verbatim as the normative statement (the
  `cst` namespaces reference it, not the reverse). `packet-version` moves there
  too. Nothing else in `packet.cljc` moves (D1, the diagnostic namespace, stays
  with lift L2 of `yang.typescript.md` 17 to avoid two edits of one function).
- **Normative invariants** (today implicit; written down so each target's
  exporter can be checked against them):
  1. ids are preorder indexes; `:root` is 0; child edges are a tree
     (`validate!` enforces).
  2. `:rule` is the grammar rule name from the parser's rule-name table, never
     a target class name; `:type` is the symbolic token name, else the literal
     name, else the decimal type, `"EOF"` for EOF.
  3. `:span` is half-open **UTF-8 byte** offsets into the sealed source. Each
     target's exporter converts its own index unit at the boundary (Java:
     code points; JS and Dart: UTF-16 code units unless the stream is opened in
     code-point mode; see risks).
  4. A token is `:synthetic true` with a zero-width span when its text is not
     the source at its span or it overlaps the previous real token.
  5. A rule's span covers its real children only; an empty rule is zero-width
     at its start token.
  6. Any syntax error yields an error packet with `:yang.cst/errors` and no
     nodes.
  7. `:yang.cst/grammar` names the grammar revision and tool; the target does
     not appear in the packet. Target-equivalence is the contract.
- **Add** a portable `packet/conform` (name final; the engineer picks) that
  checks invariants 2-5 over a packet against the source text: span
  monotonicity in preorder, byte-span inside `[0, utf8-length]`, synthetic
  tokens zero-width, rule spans covering children. It is the per-target
  conformance oracle (section 5) and costs nothing at runtime (tests only).
- **What the IR buys**: the UAST converter is written once per language against
  data; a new target is an exporter change only; parity across hosts is
  decidable by `=` on packets.

## 2. The exporter: `yang.antlr.cst` on three hosts

One namespace, three host files, one contract. `cst.clj` stays as is;
`src/cljs/yang/antlr/cst.cljs` and `src/cljd/yang/antlr/cst.cljd` implement the
same public names: `make-worker`, `parse-unit`, `parse-transform`,
`utf8-bytes`. The profile contract is unchanged:
`{:grammar-id :make-lexer :make-parser :start}`; only the constructor arguments
are host objects.

- **Node**: `antlr4` npm (`CharStreams.fromString` / `InputStream` with
  `decodeToUnicodeCodePoints`), `CommonTokenStream`, a `ErrorListener`
  subclass, `ctx.children`, `ctx.ruleIndex`, `parser.ruleNames`,
  `parser.symbolicNames`/`literalNames`, `node.symbol.{type,start,stop,text}`.
  `parse-transform` and `source-error` are host-free and should be shared: move
  them to a cljc helper (`yang.antlr.cst.transform` or inside `packet.cljc`;
  engineer's call, minimal diff) so each host file holds only the host walk.
- **Dart**: pub `antlr4` (`InputStream.fromString`, `CommonTokenStream`,
  `BaseErrorListener`, `ParserRuleContext.childCount/getChild`,
  `TerminalNode.symbol`, `parser.ruleNames`, `parser.vocabulary`). Recorded
  ClojureDart traps apply (reader-conditional ordering, `for` over chunked
  seqs, multi-key `assoc` on nil, unary minus, protocol `[_ _]`).
- **Worker isolation** (§4.3): the JVM builds fresh DFA arrays and prediction
  caches per worker. Do the same where the runtime exposes
  `LexerATNSimulator`/`ParserATNSimulator`/`PredictionContextCache` (both JS and
  Dart do); if a host cannot, document that the host is single-threaded and the
  shared cache is read-mostly, as §4.3 requires evidence not assertion.
- **Probe** (`src/clj/yang/python/antlr/repl_probe.clj`): its lexical `scan`
  and `probe` are pure; only `first-failure` touches the runtime. Split:
  `scan`/`probe` to `src/cljc/yang/python/antlr/repl_probe.cljc`; the
  host-specific `first-failure` becomes a `yang.antlr.cst` export
  (`first-failure worker source` answering `nil | :end | :early`) implemented
  per host. The probe then needs no reader conditional and a new language's
  probe reuses the same primitive.

## 3. The build: one manifest, N targets

`antlr/<grammar>/manifest.edn` grows a `:targets` map; the generator
(`yang_antlr_gen.clj`) loops over it. Java stays byte-identical (its generated
digests must not move).

```clojure
{:grammar {... :files {... "JavaScript/Python3LexerBase.js" {:blake3 ".."}
                       "JavaScript/Python3ParserBase.js" {:blake3 ".."}
                       "Dart/Python3LexerBase.dart" {:blake3 ".."}
                       "Dart/Python3ParserBase.dart" {:blake3 ".."}}}
 :tool {:antlr "4.13.2"}
 :targets
 {:java       {:runtime "org.antlr/antlr4-runtime 4.13.2"
               :flags ["-Dlanguage=Java" "-package" "yang.python.antlr.gen" "-no-listener" "-no-visitor"]
               :helpers ["Java/Python3LexerBase.java" "Java/Python3ParserBase.java"]
               :package "yang.python.antlr.gen"      ; the package-clause prepend stays Java-only
               :out "build/antlr/python3/java"       ; then javac -> build/antlr/python3/classes
               :patches {...}                        ; the existing four
               :generated {...}}
  :javascript {:runtime "antlr4 4.13.2 (npm)"
               :flags ["-Dlanguage=JavaScript" "-no-listener" "-no-visitor"]
               :helpers ["JavaScript/Python3LexerBase.js" "JavaScript/Python3ParserBase.js"]
               :out "build/antlr/python3/js/yang/antlr/gen/python3"
               :patches {"JavaScript/Python3LexerBase.js" [...]}   ; the ported positioned-token patch
               :generated {...}}
  :dart       {:runtime "antlr4 4.13.2 (pub)"
               :flags ["-Dlanguage=Dart" "-no-listener" "-no-visitor"]
               :helpers ["Dart/Python3LexerBase.dart" "Dart/Python3ParserBase.dart"]
               :out "lib/antlr/python3"
               :patches {"Dart/Python3LexerBase.dart" [...]}
               :generated {...}}}
 :license {...}            ; four more "no notice; UNRESOLVED" rows
 :redistribution {...}}    ; unchanged policy, extended to the new helpers
```

- The ANTLR tool is already on the `:antlr-gen` classpath; `-Dlanguage=` is the
  only per-target switch. Helper files are fetched by digest into
  `build/antlr/<grammar>/upstream` exactly as today (owner decision "vendor .g4
  only" stands; the earlier `5c-antlr-build.log` failure was a network-less
  first fetch and is unchanged by this plan).
- **Patches per target**: the Java `:patches` entries become the pattern; the
  JS and Dart ports replace `commonToken(NEWLINE, text)`/`DEDENT` construction
  with positioned tokens (`CommonToken` with explicit start/stop in both
  runtimes). Exact-text, applied once, digest-pinned, as today.
- **Digests**: `:generated` per target; `generated-digests.edn` per target
  under the target's out dir. ASCII check per target (§4.5).
- **Where generated code lands and how hosts load it**:
  - Java: unchanged (`deps.edn :paths` has `build/antlr/python3/classes`).
  - Node: `build/antlr/python3/js` is added to the `:cljs` alias
    `:extra-paths`; the generated ESM files sit under
    `yang/antlr/gen/python3/` so cljs requires them as classpath JS:
    `(:require ["/yang/antlr/gen/python3/Python3Parser.js" :as Python3Parser])`
    and the runtime as `["antlr4" :as antlr4]`. `package.json` gains
    `"antlr4": "4.13.2"` (exact pin, as `@noble/hashes` is pinned).
  - Dart: `lib/` is gitignored and is the Dart package root
    (`datomworld_cljd`), so generated files go to `lib/antlr/python3/` and
    cljd requires `["package:datomworld_cljd/antlr/python3/Python3Parser.dart"
    :as p3]`. `pubspec.yaml` gains `antlr4: 4.13.2`.
- **bb.edn**: `gen:python-antlr` becomes `gen:antlr` (all grammars, all
  targets, `--grammar`/`--target` filters); `test:cljs` and `test:cljd` gain
  `:depends [gen:antlr]` like `test:clj` has today. No compat alias (dev-only
  repo).
- **Versioning**: tool and three runtimes are one release; a bump regenerates
  all targets and re-pins all digests in one commit (§4.2).

## 4. The parser namespace per language

`src/clj/yang/python/antlr/parser.clj` becomes
`src/cljc/yang/python/antlr/parser.cljc`:

```clojure
(ns yang.python.antlr.parser
  (:require [yang.antlr.cst :as cst] [yang.stage :as stage]
            #?@(:clj [] :cljs [["antlr4" :as antlr4]
                               ["/yang/antlr/gen/python3/Python3Lexer.js" :default Python3Lexer] ...]
                :cljd [["package:datomworld_cljd/antlr/python3/Python3Lexer.dart" :as l] ...]))
  #?(:clj (:import (yang.python.antlr.gen Python3Lexer Python3Parser))))

(def profile
  {:grammar-id grammar-id
   :make-lexer  #?(:clj (fn [cs] (Python3Lexer. cs)) :cljs (fn [cs] (Python3Lexer. cs)) :cljd (fn [cs] (l/Python3Lexer cs)))
   :make-parser ...
   :start       #?(:clj (fn [p] (.file_input p)) :cljs (fn [p] (.file_input p)) :cljd (fn [p] (.file_input p)))})
;; make-worker, parse-source, open-stage, step-stage unchanged
```

`#?(:cljd ...)` must come first in each conditional (recorded trap). This file
is the entire per-language, per-host surface.

## 5. CST equivalence: the parity story

Three layers, all reusing existing tests by widening their host set:

1. **Exporter conformance (per target, language-free)**: a new cljc
   `yang.antlr.conformance` test helper takes a profile and a fixture list
   `[source expected-shape]` and asserts, on the running host: packet
   `validate!` and `conform` pass; the `shape` (rule/type/text/span) equals the
   golden; spans are UTF-8 bytes (`"s = 'é𝄞'\nt = 2\n"` → `[4 12]`,
   `[13 14]`, root end = `utf8-bytes`); syntax errors yield error packets with
   no nodes; EOF/DEDENT synthetic and zero-width. `cst_export_test.clj` becomes
   `.cljc` and runs on all three hosts through it. The goldens are the JVM's
   today; they become the cross-target oracle.
2. **Packet parity (per language)**: `precompiled-packets-are-the-parsers-test`
   in `test/yin/repl/python_antlr_test.cljc` loses its `#?(:cljd nil :clj ...)`
   wrap: on every host the installed parser's packets equal the precompiled
   ones. `langs` becomes `[:yang.python/cst :yang.python/antlr]` on all hosts.
3. **Program parity (end to end)**: the REPL session scenarios of that test
   plus the prompt-suppress regression and the owner's comprehension line run
   through `:yang.python/antlr` on Node and Dart; expected output is the JVM's.
   Because the lowering is already portable and already proven on hand-built
   packets, parity at layer 2 implies parity at layer 3; layer 3 is the
   acceptance the owner sees at the prompt.

Optional later corpus: grammars-v4's `python/python3/small` inputs as a
three-host packet-equality corpus (slow tier).

## 6. Catalog and SPI changes

- `frontends.cljc`: remove the `#?(:cljd [] :clj ...)` wrappers around the
  requires, `antlr-manifest` and the install; the ANTLR frontend installs on
  every host. The manifest bumps `:yang.frontend/revision` ("f3" → the next)
  and its grammar map gains `:yang.grammar/targets #{:java :javascript :dart}`
  (portable data, informational; `select` can pin on it). No SPI revision bump:
  the key is optional and `yang.frontend/grammar-checks` is unchanged.
- "Installed where its parser runs" docstrings flip. `unavailable-parser`
  remains the answer only when a host has no target generated for that grammar
  (future languages, section 7).
- `yang.antlr.md` amendments (dated): 4.1 table (Dart row: generated Dart
  parser is now the local implementation for Python), 8.5.6 F2/F3 acceptance
  lines ("since the parser is JVM-only" struck; replaced by the parity layers),
  5.5 points to `packet.cljc` as the IR owner; `yin-repl-design.md` Python
  section; `yang.typescript.md` 2.2 and 5.3 and `yang.php.md` 6: the sentence
  "the ANTLR parser is JVM-only for both" becomes "per-target helper
  availability decides the host matrix (yang.antlr.md 4.2); TS has Java-only
  helpers upstream, PHP Java and JavaScript".

## 7. Adding a language after this lands (the mechanical recipe)

For language L with grammars-v4 grammar G:

1. `antlr/<G>/manifest.edn`: vendor the `.g4` files, list helper files per
   target that upstream ships (or that you port), patches, licence rows. If a
   target has no helper (and the grammar needs one), omit that target: that host
   answers `unavailable-parser` for L until a helper is ported. This is the only
   non-mechanical step and it is bounded to the helper file.
2. `bb gen:antlr --grammar <G>`; record the printed digests into `:generated`.
3. `src/cljc/yang/<L>/antlr/parser.cljc`: the profile with three constructor
   conditionals (section 4). About 40 lines.
4. `src/cljc/yang/<L>/antlr/lower.cljc` and friends: the language's CST-to-UAST
   converter over the IR through `packet/*` only (section 8 boundary).
5. Conformance fixtures: `[source shape]` goldens for `yang.antlr.conformance`;
   precompiled packets for the parity test.
6. `frontends.cljc`: one `install` line with the manifest.
7. Optional: a probe (`scan` in cljc over `cst/first-failure`).

Steps 2, 3, 5, 6 are the same for every language and every target; the
generator, exporter, packet reader, REPL and catalog are not edited. TypeScript
and PHP (`yang.typescript.md` 5, `yang.php.md` 6) already follow steps 1 and 3
in their designs; they gain Node/Dart the moment a helper exists for the
target.

## 8. Boundary: what stays per language

- Per language, never generic: the lowering (`lower.cljc`), scope analysis,
  prelude, publication, primitives, the probe's lexical `scan` rules, the
  refusal and diagnostic namespaces, entry-rule choice (`file_input`), the
  trailing-newline normalization in the REPL `:parse` binding.
- Generic, never language-aware: the generator, `packet.cljc`, the three
  `yang.antlr.cst` files, `yang.antlr.conformance`, `yang.frontend`.
- The IR carries rule and token **names**; no language's exporter may add
  language-specific fields to packets. A language that needs more than the IR
  provides (for example alternative labels, §5.5) proposes an IR version bump,
  not a private field.

## 9. Reconciliation with the TS and PHP designs

- **(a) Already covered**: the manifest shape and vendoring discipline
  (`yang.typescript.md` 5.1, `yang.php.md` 6), the worker profile contract
  (`cst/make-worker`, "nothing in cst.clj changes"), the per-target helper
  versioning rule (`yang.antlr.md` 4.2), the lifts L1-L4 (section 17) that make
  the lowering side shared. This plan edits none of them.
- **(b) Genuinely new for Python on JS/Dart**: multi-target generation with
  per-target patches and digests; the Node and Dart exporters; the IR
  formalization and `conform`; the conformance helper; the probe split; the
  catalog gate removal; the dependency pins.
- **(c) Generalizes**: everything in (b) except the Python manifest rows and
  the Python parser profile. TS and PHP become "add the JS/Dart helper to the
  manifest" problems.
- **Collision to sequence**: L2 edits `packet.cljc` (D1). This plan edits
  `packet.cljc` too (the doc move and `conform`). Whichever lands second
  rebases; the edits touch different functions.

## 10. Legacy retirement (separate round, gated)

Preconditions, all demonstrable before the round opens: M2 landed (section
11) with layers 1-3 green on three hosts; the owner's comprehension line
evaluates on Node and Dart; every `yang.python` (legacy) test scenario is
either ported to the ANTLR frontend or recorded as intentionally dropped; the
`yang.python.md`-era docs name the retirement. The round then deletes
`src/cljc/yang/python.cljc` and its tests, the `:yang.python/legacy` install,
and amends the docs. Not part of the first engineer round.

## 11. Implementation slices (first engineer round builds M0, M1, M2 in order)

| Slice | Scope | Acceptance |
|---|---|---|
| M0 build | manifest `:targets`, generator loop, JS and Dart generation with ported positioned-token patches, digests, `gen:antlr`, `package.json`/`pubspec.yaml` pins, `:cljs` extra-path, lane `:depends` | Java `:generated` digests byte-identical to today; JS and Dart artifacts generated, ASCII, digest-pinned; `bb gen:antlr` reproducible from clean; `npm ci` and `pub get` resolve 4.13.2 |
| M1 IR + exporters | `packet.cljc` owns the v1 shape doc and `conform`; `cst.cljs`, `cst.cljd`; shared `parse-transform`; `yang.antlr.conformance`; `cst_export_test` → cljc | conformance goldens green on JVM, Node, Dart including the UTF-8 span fixture and error packets; `conform` passes on every golden and on every precompiled packet |
| M2 Python on three hosts | `parser.cljc`; probe split; `frontends.cljc` gate removed, revision bump, `:yang.grammar/targets`; `python_antlr_test` `langs` and parity test on all hosts; e2e and comprehension line on Node and Dart; doc amendments of section 6 | layers 2 and 3 green on three hosts; `(lang :yang.python/antlr)` at the Node and Dart prompts evaluates the comprehension line; quick tiers green per host; kondo + cljstyle clean |
| M3 legacy retirement | section 10 | its own round |

Lanes per slice: `bb test:clj` per iteration; full three-host quick tier at
each landing, one lane set at a time, foreground; focused ANTLR namespaces
with `DATOM_SLOW_TESTS=1` at M2.

## 12. Risks

- **Index units**: JS and Dart streams index UTF-16 code units by default; the
  JVM indexes code points. The exporter must open code-point streams or map
  units; the `é𝄞` fixture is the guard. Highest-probability parity defect.
- **Dart target untested upstream**: `desc.xml` excludes Dart; the Dart helper
  may lag the grammar (method names, token constants `TOKEN_NEWLINE`). Bounded:
  it is one file under patch control. Also the pub runtime's SDK bound
  `<3.0.0` (Dart 3 accepts null-safe `<3.0.0` packages, but verify `pub get`
  under the project's `>=3.7.0`).
- **Circular ESM import** between the JS lexer base and the generated parser;
  shadow-cljs classpath-JS handling of ESM plus an npm import must be verified
  in M0 (fallback: `:js-options :resolve` to the build dir).
- **Licence**: four more helpers without notices; `:redistribution :blocked-until`
  stays. Runtimes are BSD-3-Clause.
- **Bundle/CI cost**: `antlr4` npm (~0.3 MB) enters the `yin-repl` node-script
  and the test build only, not the browser `:demo` build unless required; the
  Dart lane recompiles the generated parser (large) per `flutter test` shard.
  Measure in M2; acceptable for a dev-only repo.
- **Generation needs network on first run** (helper fetch), as today; four
  more files.
- **Worker isolation evidence** on single-threaded hosts (section 2).
- **Chunked-seq and reader-conditional traps** on Dart in the exporter walk.

## Verification (end to end)

1. `bb gen:antlr` from a clean `build/` and `lib/antlr`; compare Java digests
   with `git show HEAD:antlr/python3/manifest.edn`.
2. `bb test:clj`, `bb test:cljs`, `bb test:cljd` quick tiers, foreground, one at
   a time; confirm `Testing yang.antlr.conformance-test` and
   `yin.repl.python-antlr-test` appear in the Node and Dart output.
3. At the Node prompt (`node target/yin-repl.js`) and the Dart peer:
   `(lang :yang.python/antlr)` then the owner's comprehension line; output
   equals the JVM's.
4. `DATOM_SLOW_TESTS=1` focused ANTLR namespaces.
