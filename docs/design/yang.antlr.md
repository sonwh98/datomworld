# yang.antlr: compiling languages with ANTLR and Yang into the Universal AST

Status: Architecture and implementation roadmap, implementation under way
(the Python line; see "Implementation status" below). Drafted
2026-09-24 from the Lead System Architect's plan of 2026-09-23
(`collab/1790185837428-architect-yang-antlr-plan.gpt-6-astra.plan.md`)
and the orchestrator's standard-library discussion.

Implementation status, 2026-10-04 (commits on master). Landed: the Python
pilot pipeline (parser stage, scope analysis, lowering, prelude;
`bf6c5544`); Python phase C1 (`34c3986b`); the float-address slice of
section 8.5.5 (`1f8b7e37`, with the JavaScript carrier's coercion refusal in
`ac9ecb8b`) and the guarded O(1) range element path (`a932bb55`); C2
generators S1 (`7654c2d0`), S2 (`c3f2da8f`) and S3, `yield from` and `iter`
(`a4efc99a`); C3 S1, the exact-integer module (`54536317`), and the
numeric-key, `hash()` and `is` work of rulings 6 to 8 (`be1f8d06`,
orchestrated as C3-S2, the S5 row's scope); safepoint insertion slices 1
and 2, signals and recursion with generator admission (`cf6ed9ad`,
`e2a80eef`). Not started: C2 S5, the remaining C3 slices, safepoint
slices 3 and 4 (tracing, threads), C4 (section 8.5.6 records the design
only), and every phase after Phase 1 (the JavaScript pilot, Java, PHP, the
language SDK, multi-host benchmarks). The "none of it has landed" and
"implementation pending" statements in the dated paragraphs below were
true when written; sections 8.5.2 to 8.5.6 and section 12 carry the
per-slice state.

Updated 2026-10-01 with the Architect rulings of 2026-09-30 and
2026-10-01, each accepted by the owner. Later sections cite them by the
short names given here:

- the cell ruling:
  `collab/1790773810605-architect-cell-primitive.claude-fable-5-1.findings.md`;
- mob D1 to D10 (round 2):
  `collab/1790776815400-architect-mob-outstanding-decisions.claude-fable-5-1.findings-r2.md`
  and `collab/1790776815400-architect-mob-outstanding-decisions.gpt-6-astra.findings-r2.md`;
- the mutable-objects ruling:
  `collab/1790778866412-architect-mutable-objects.claude-fable-5-1.findings.md`;
- the Python mappability ruling:
  `collab/1790797984227-architect-python3-mappability.claude-fable-5-1.findings.md`.

They amend sections 8.1, 8.5, 9.3, 9.4, and 9.5, add sections 8.5.1 and
8.11, and add cross-references from sections 8.6 and 8.9. Landed on
master since: captured continuations are invocable on all four VMs
(`8f9f90b0`); effects are an unforgeable host type and callee profiles
bound the effects they may raise (mob D4, `9a69e58f`); task-heap cells,
slice 1 (`5e790683`); and the pure `data` host module (`yin.vm.data`,
`fe8bce4a`).

Updated 2026-10-02 with three further ruling sets, cited by these short
names:

- the safepoint design, whose seven owner decisions the owner accepted
  on 2026-10-01 ("accept all recommendations"):
  `collab/1790849347715-architect-safepoint-interpreter.claude-fable-5-1.findings.md`;
- the C2 generator design and the C2 cross-ruling:
  `collab/1790874900000-architect-python-c2-generators-design.claude-fable-5-1.findings.md`
  and
  `collab/1790875890000-architect-c2-generators-crossruling.gpt-6-astra.findings.md`;
- the C3 integer design and the C3 cross-ruling:
  `collab/1790874940000-architect-python-c3-bignum-design.gpt-6-astra.findings.md`
  and
  `collab/1790875860000-architect-c3-bignum-crossruling.claude-fable-5-1.findings.md`.

The two cross-rulings are the converged rulings of the architect pair
(gpt-6-astra and fable-5.1), to which the owner delegated decision
authority. Where a design and its cross-ruling differ, the converged
ruling governs. These rulings amend sections 8.5, 8.5.1, 8.11, 9.3, 11,
and 12 and add sections 8.5.2 to 8.5.4. They build on Python phase C1
and on heap reclamation, both landed (`34c3986b`, `60b60898`). When
written, none of them had landed; each was recorded with its
implementation pending in the slices it names. The implementation
status above gives what has landed since.

Updated 2026-10-03 with the float-address ruling, the converged ruling
of the same architect pair: gpt-6-astra's ruling with fable-5.1's
concurrence, whose three corrections bind:
`collab/1790968830636-architect-float-address-mob.gpt-6-astra.findings.md`
and
`collab/1790968830647-architect-float-address-mob.claude-fable-5-1.findings.md`.
It amends sections 8.5 and 8.5.1 and adds section 8.5.5. Its
implementation is pending in one preparatory slice; none of it has
landed.

Updated 2026-10-03 with the C4 design (fable-5.1) and gpt-6-astra's C4
cross-ruling, whose nineteen converged rulings govern where they differ
from the design:
`collab/1790974400000-architect-python-c4-design.claude-fable-5-1.findings.md`
and
`collab/1790975400000-architect-c4-crossruling.gpt-6-astra.findings.md`.
They amend section 8.5.1 and add section 8.5.6. None of them has landed:
each is recorded with its implementation pending in the slices it names.

This document is subordinate to
[`datom.world.md`](./datom.world.md) (axioms and invariants),
[`dao.stream.md`](./dao.stream.md) (the passive stream substrate),
[`yin.vm.code-as-tuples.md`](./yin.vm.code-as-tuples.md) (the Universal
AST encoding), [`dao.stream.remote.md`](./dao.stream.remote.md) (mirror,
reflection, and channel transport), and
[`dao.jing.dht.md`](./dao.jing.dht.md) (content-addressed segment
distribution). `dao.jing.content` provides the content service over
reflections. Where this document restates a rule from those contracts,
it cites the rule; it supersedes none of them.

Owner directive, verbatim: "the list of language is not exhaustive". Every
language named below (Java, Python, PHP, JavaScript, Go, Rust, Clojure,
ClojureScript, ClojureDart, and the rest) is a reference example of a
contract, never an entry in a registry. Yang core contains no enumeration
of supported languages.

---

## 1. Overview and purpose

This document covers how source programs written in arbitrary languages
are compiled, through ANTLR-generated parsers hosted behind a stream
boundary, into Yin's Universal AST, and how the resulting code is encoded,
published, diagnosed, and executed under the datom.world invariants.

The architectural decision is to make Yang an **open compiler composition
framework**. Each language contributes a versioned grammar package, a
syntax export contract, semantic analysis and lowering, and any required
runtime prelude. Java, Python, PHP, and JavaScript are reference
implementations of this contract. Yang core contains no enumeration of
supported languages.

ANTLR operates behind a stream boundary. Its generated parsers and
runtime objects remain host-local. Parsing produces portable syntax data;
language interpreters lower that data to Yin's existing Universal AST;
the established encoder produces canonical content-addressed rows.
Evaluation, indexing, persistence, diagnostics, and tooling remain
independently composed observers.

Remote compiler and content services use `dao.stream.remote` mirror and
reflection handles. WebSocket channels compose `ws-project` at each end.
Content puts and fetched bytes pass the shared `dao.jing/accept-bytes!`
ingress check through `dao.jing.content`; transport does not validate
their content. These service boundaries do not change the frontend SPI.

**An ANTLR grammar supplies syntax recognition. Executable language
support additionally requires a semantics-preserving lowering and runtime
profile.** The framework must make this distinction observable.

### 1.1 Overall composition

```text
Source events on dao.stream
  |
  v
Decode, frame, and seal source unit
  |
  v
Parse request stream
  |
  v
Host parser interpreter / isolated ANTLR worker
  |
  v
Portable CST packets and diagnostics
  |
  v
Language analysis and lowering interpreter
  |
  v
Ephemeral local Universal AST medium
  |
  v
Encoder: canonical rows and occurrence side tables
  |
  v
Validation and publication interpreter ----> Reference and provenance
  |                                          ledger
  v
Admitted program row stream --------------> Independent AST indexer
  |                    |
  |                    v
  |            Optional macro expansion stage
  |                    |
  v                    v
Evaluator observer <---+
```

The direct evaluator route and the macro route are composition
alternatives. A composition must not feed both into one evaluator and
execute the program twice.

Every substantial stage communicates through a supplied stream. Pure
helper functions inside a stage may share algorithms; they do not become
a hidden route between stages or across hosts.

Diagnostics and lifecycle records use separate supplied streams or
explicitly framed topics. `dao.stream` does not route records by language
or payload type.

---

## 2. Governing decisions

### 2.1 Repository baseline

The plan follows the axioms and six invariants in `datom.world.md`, the
passive substrate contract in `dao.stream.md`, and the representation
rules in `yin.vm.code-as-tuples.md`.

The inspected implementation establishes these starting points:

- `yang.clojure` (`src/cljc/yang/clojure.cljc`) already emits named
  Universal AST maps and lowers definitions to explicit `yin/def`
  applications.
- `yang.python` (`src/cljc/yang/python.cljc`) and `yang.php`
  (`src/cljc/yang/php.cljc`) contain handwritten tokenizers, parsers, and
  limited lowering. Replacing their parsing does not automatically repair
  their semantic limitations.
- JavaScript is described as future work in the Yang documentation; no
  corresponding frontend was found in the inspected Yang source
  directory.
- `yin.vm` (`src/cljc/yin/vm.cljc`) already defines the canonical row
  grammar, projection, reconstruction, validation, occurrence relations,
  and requirement extraction.
- `yin.vm.encoder` (`src/cljc/yin/vm/encoder.cljc`) already separates
  frontend maps from row output. Its forwarding path currently throws on
  unsuccessful appends; a general compiler pipeline needs explicit
  staging and outcome handling.
- The current macro pipeline consumes
  `[:yin.program/batch trees run-index declaration-rows harvest-rows]`.
  Row loaders and a row-native macro expander exist despite older design
  passages describing them as future work.
- The REPL dispatcher (`src/cljc/yin/repl/core.cljc`, `compile-source`)
  currently selects Clojure, Python, and PHP with a closed `case`.
  Migrating this consumer to the SPI is part of the work.

The historical Yang implementation summary
(`src/cljc/yang/docs/yang_implementation.md`) supplies context, not proof
of present feature completeness or portability.

### 2.2 Representation decision

The term "Universal AST datoms" must preserve the repository's
distinction between semantic code, stored code, and references:

1. **Universal AST maps** are the frontend's semantic representation and
   the walker's reconstructed image.
2. **Flat rows `[id tag & slots]`** are canonical code content exchanged
   across durable or remote compiler boundaries.
3. **Datoms `[e a v t m]`** record naming, publication, provenance,
   diagnostics, and optional derived EAV projections.

Do not introduce a second authoritative AST schema in which each language
persists its own node attributes.

The design text contains conflicting descriptions of map ASTs traveling
on streams. This document reconciles them as follows: the existing
frontend-to-encoder map medium may remain an **ephemeral, host-local
staging stream**. Persistent or remote program exchange carries canonical
rows. Phase 0 must document this interpretation and verify it against the
existing encoder and macro pipeline.

### 2.3 Invariant enforcement

The six non-negotiable invariants of `datom.world.md` bind the compiler
pipeline as follows:

- **No hidden global state:** frontend catalogs, installed
  implementations, configuration, allocation counters, and caches belong
  to an explicit composition or worker.
- **No implicit control flow:** admission, parse completion, retries,
  cancellation, publication, and execution are represented by state
  transitions and stream records.
- **No application callbacks:** host events are classified into data and
  deposited. Portable interpreters poll their own cursors.
- **No shared mutable state:** compiler state is immutable and threaded
  explicitly. ANTLR's mutable machinery is confined to an exclusively
  owned host worker.
- **No collapse of interpretation and execution:** streams do not parse,
  compile, schedule, or evaluate; compilation does not execute the
  user's program.
- **No assumed graphs:** CST child edges, module dependencies, object
  references, and code relationships are explicit data. Interpreters
  construct the corresponding relations.

---

## 3. Language frontend SPI

### 3.1 Portable declarations versus installed implementations

A frontend consists of four independently versioned parts:

1. **Grammar package:** lexer/parser grammars, imports, token vocabulary,
   entry rules, and required parsing helpers.
2. **Syntax export profile:** how the generated parser's CST becomes
   portable data.
3. **Semantic frontend:** scope analysis, validation, desugaring, and
   Universal AST lowering.
4. **Runtime profile:** prelude code, value representations, calling
   convention, primitive requirements, and permitted effects.

A proposed portable manifest might contain:

```clojure
{:yang.frontend/id :example.ruby/frontend
 :yang.frontend/spi 1
 :yang.frontend/revision "<immutable-package-revision>"
 :yang.frontend/language :example.ruby/language

 :yang.frontend/grammar
 {:yang.grammar/package "<content-address>"
  :yang.grammar/lexer "RubyLexer"
  :yang.grammar/parser "RubyParser"
  :yang.grammar/entries
  {:module "program"
   :expression "expression"}
  :yang.grammar/export-profile :yang.cst/v1}

 :yang.frontend/options-schema "<content-address>"
 :yang.frontend/lowering-profile "<content-address>"
 :yang.frontend/runtime-profile "<content-address>"
 :yang.frontend/support-profile "<content-address>"}
```

These names are proposed SPI vocabulary, not existing repository APIs.
The strings identify grammar declarations; they are not classes to
instantiate from untrusted input.

Host installation separately binds an admitted package revision to:

- Generated JVM classes, JavaScript modules, or Dart libraries.
- The corresponding ANTLR runtime.
- Any target-specific lexer/parser helper implementation.
- A portable lowering implementation, or an explicitly selected lowering
  service.
- Supplied request, response, control, and diagnostic streams.

Portable manifests contain no constructors, function objects,
classloaders, callbacks, or live handles.

### 3.2 Protocol shape

The contract a frontend satisfies, written as Clojure pseudocode. Every
name here is proposed vocabulary that organizes the four parts of 3.1 and
the lowering step of 3.5; none of it exists in the repository today.

```clojure
;; A frontend is a value: its manifest plus the installed binding the
;; composition attached to it. Nothing here is a namespace or a global.

(defprotocol YangFrontend
  ;; 3.1 part 1 and 2: grammar package and syntax export profile.
  (grammar-manifest [this]
    "The :yang.frontend/grammar map: package address, lexer and parser
     declarations, entry rules, export profile.")
  (support-profile [this]
    "The 3.4 claims: syntax, semantics, lowering, stdlib, host and effect
     requirements, REPL completeness, incremental parsing.")
  ;; REPL completeness probe (3.4, 5.3). Returns
  ;; :complete, :incomplete, or :invalid. Optional; absent means the
  ;; composition must use an explicit submit action.
  (completeness [this source-snapshot options])
  ;; 3.1 part 3: the semantic frontend as a stepped interpreter.
  ;; Consumes a sealed, validated CST packet and an explicit compilation
  ;; environment. Returns the 3.5 transition result.
  (lowering-step [this state observed-input budget])
  ;; 3.1 part 4: the runtime profile, resolved to prelude addresses.
  (runtime-profile [this]))

;; The transition result of lowering-step (3.5):
;; {:yang.lower/state     successor-state
;;  :yang.lower/staged    [ephemeral Universal AST maps or packets]
;;  :yang.lower/status    :running | :done | :rejected | :blocked}

;; Registration constructs a new catalog value (3.3):
;; (install catalog validated-manifest installed-binding) => catalog'
```

The Clojure frontend implements this same contract through its existing
reader. The open SPI does not require every frontend to use ANTLR.

### 3.3 Registration and selection

Registration means constructing a new catalog value:

```clojure
catalog' = install(catalog, validated-manifest, installed-binding)
```

This is not namespace-load registration or mutation of a global
registry. There is no closed language registry anywhere in Yang core.

The composition supplies the catalog to the compiler coordinator. A
request selects a frontend revision and dialect explicitly. Filename
extensions and content detection can propose a frontend, but ambiguous
detection produces a diagnostic or a choice record rather than silently
choosing.

In-flight requests pin a catalog snapshot. Installing a newer frontend
does not change their behavior.

A missing frontend, missing entry rule, incompatible SPI, unknown required
option, or unavailable parser implementation produces a qualified
compiler outcome. None causes automatic dependency downloading or
executable plugin loading.

### 3.4 Support profiles

Each frontend publishes separate claims for:

- Syntax recognition by language edition and entry rule.
- Semantic analysis.
- Executable lowering.
- Standard-library compatibility.
- Host and effect requirements.
- REPL completeness detection.
- Optional incremental parsing.

Features can be declared supported, intentionally restricted, or
unsupported, with test identifiers attached to supported claims.

A grammar may recognize generators, reflection, or ownership syntax
before Yang can execute them. Such constructs must produce an
unsupported-feature diagnostic at the semantic gate, never a
superficially valid AST with altered meaning.

### 3.5 Lowering contract

The frontend consumes a sealed, validated CST packet and an explicit
compilation environment. Its logical transition is:

```text
step(state, observed-input, budget)
  -> successor-state
   + staged-output
   + status
```

State includes the work stack, scope environment, module facts,
deterministic name supply, intermediate values, and pending outputs.

Lowering can use:

- Declarative patterns over rule names and child roles.
- An explicit stack-based tree walker.
- Pure visitor functions over normalized data.

Generated ANTLR visitors may help **export syntax inside the host
boundary**. They must not invoke portable lowering with
`ParserRuleContext` objects.

Unknown or unhandled CST constructs are errors. A default visitor that
silently drops children is not acceptable compiler behavior.

If a frontend separates analysis and lowering into independently driven
stages, it introduces a stream boundary between them. If they share one
interpreter, their intermediate representation remains private,
regenerable state.

### 3.6 Adding a language without core changes

A third-party author must be able to:

1. Package a grammar and audited dependencies.
2. Declare entry rules, dialect options, and syntax export profile.
3. Supply a lowering and runtime profile.
4. Pass the frontend conformance kit.
5. Add the package to a composition's catalog.

This changes deployment configuration and adds a plugin package. It does
not add a language-specific branch to Yang core, DaoStream, the canonical
AST grammar, or evaluator dispatch.

Clojure should implement the same frontend contract through its existing
reader. The open SPI should not require every frontend to use ANTLR.

### 3.7 Versioning

Four things are versioned independently and pinned together per request:

- The SPI revision (`:yang.frontend/spi`).
- The frontend package revision (`:yang.frontend/revision`), which is
  immutable once published.
- The grammar package content address and its export profile.
- The lowering, runtime, support, and options-schema profiles, each by
  content address.

Two dialect packages, or two revisions of one frontend, coexist in one
composition without registration collisions (Phase 4 exit criterion).

---

## 4. ANTLR host boundary

### 4.1 One protocol, several deployments

Use a **stream-based parsing service contract**, implemented locally when
a suitable runtime is available and through an explicitly configured
service otherwise.

ANTLR has a Java-based generation tool and target runtimes including
Java, JavaScript, TypeScript, and Dart. Target feature parity is not
automatic. Generation and runtime execution are separate concerns
(ANTLR target documentation,
https://github.com/antlr/antlr4/blob/dev/doc/targets.md).

The proposed deployment policy is:

```text
+---------------+-------------------------------+--------------------------+
| Consumer host | Local parser implementation   | Alternative              |
+===============+===============================+==========================+
| JVM / CLJ     | Generated Java parser in an   | Configured parsing       |
|               | isolated worker or worker     | service                  |
|               | process                       |                          |
+---------------+-------------------------------+--------------------------+
| Node / CLJS   | Generated JavaScript parser   | Local sidecar or remote  |
|               | in a worker; TypeScript       | service                  |
|               | compiled at build             |                          |
+---------------+-------------------------------+--------------------------+
| Dart / CLJD   | Optional generated Dart       | Sidecar where supported, |
|               | parser in an isolate          | or a remote service;     |
|               |                               | otherwise unsupported    |
|               |                               | outcome                  |
+---------------+-------------------------------+--------------------------+
```

A CLJD application can compile through a service without bundling ANTLR.
It can also consume previously compiled rows without any parser.

Full offline local compilation on all three hosts requires validated
parser artifacts for all three. Service-based portability must not be
advertised as offline portability.

Source is sent only to an endpoint supplied by the composition.
Unavailability does not trigger an undisclosed remote fallback.

### 4.2 Grammar discipline and per-host generation

Grammars live in `.g4` files. Prefer grammars with **zero
target-specific embedded actions**, so one grammar source generates for
every host. Where indentation or lexical ambiguities require helpers,
version and test those helpers per target. The grammars-v4 project
expresses an action-free expectation, not a guarantee that every grammar
works unchanged on every target (https://github.com/antlr/grammars-v4).

Generate parsers during reproducible builds, not while admitting source
programs. One grammar is generated once per host target:

```text
+-----------+------------------------+----------------------------------+
| Host      | Generation flag        | ANTLR runtime package            |
+===========+========================+==================================+
| CLJ       | -Dlanguage=Java        | org.antlr:antlr4-runtime (Maven) |
+-----------+------------------------+----------------------------------+
| CLJS      | -Dlanguage=JavaScript  | antlr4 (npm)                     |
+-----------+------------------------+----------------------------------+
| CLJD      | -Dlanguage=Dart        | antlr4 (pub.dev)                 |
+-----------+------------------------+----------------------------------+
```

Pin, per grammar package:

- Grammar source revisions and digests, including imported grammars.
- Tool and runtime versions.
- Generation flags and target.
- Helper implementations and patches.
- Syntax export and diagnostic normalization profiles.
- Generated artifact digests.

ANTLR releases coordinate the tool and runtimes; upgrades should
regenerate and retest the complete parser package
(https://github.com/antlr/antlr4).

### 4.3 What remains host-local

ANTLR's mutable machinery is confined to an exclusively owned host
worker. The worker exclusively owns:

- Lexer and parser instances.
- Character/token streams.
- ANTLR contexts, tokens, ATN/DFA structures, and prediction caches.
- Target-specific indentation, lexical-mode, or predicate helpers.
- Exceptions, worker handles, and process resources.

Generated recognizers may use shared caches. The implementation must
audit these and isolate them by worker, process, or classloader, or
provide instance-owned caches where supported. Merely constructing a
fresh parser is insufficient evidence that mutable runtime state is
isolated.

Read-only generated tables may be shared. No semantic state may depend
on a previous request.

The boundary adapter itself remains a map of host-event-to-data
transformations supplied with a deposit operation, as `datom.world.md`
(Host Boundaries) defines an adapter. The host parser interpreter owns
execution; the depositing adapter does not schedule lowerers or call
clients.

ANTLR's internal parsing methods and semantic predicates are internal
library execution. They are not a license to introduce application
callbacks across the stream boundary.

### 4.4 Non-blocking interfaces versus synchronous parsing

ANTLR parsing is not inherently a resumable, fuel-metered computation.
Its parser entry methods perform synchronous work; an unbuffered
character stream is not a portable asynchronous continuation protocol.

Therefore:

- A portable compiler step never invokes an unbounded parse on the
  caller's event loop.
- It appends a request and later observes response events.
- Parsing runs in an isolated worker, process, or service.
- The host completion handler deposits data and returns.
- Cancellation is an explicit control record. Hard termination requires
  a host mechanism that can actually stop the worker; thread
  interruption alone is not assumed sufficient.

The portable compilation state can checkpoint at request and packet
boundaries. A native ANTLR call stack is not a Yin continuation and is
not migrated. Recovery replays the immutable source snapshot in another
worker.

### 4.5 ASCII policy and licensing

For the ASCII policy:

- Keep maintained grammar syntax, generated textual parser sources,
  manifests, and patches ASCII.
- Express non-ASCII grammar characters through ANTLR Unicode escapes.
- Reject non-ASCII generated text in CI unless an audited, target-aware
  transformation preserves behavior.
- Preserve upstream notices exactly; do not silently transliterate or
  remove legally required text.
- Keep source programs Unicode-capable. An ASCII grammar file does not
  imply an ASCII language.

License review covers each grammar, imported grammar, helper, runtime,
and fixture independently. Retain source and binary notices, record SPDX
identifiers where known, and block redistribution when licensing is
missing or incompatible. ANTLR's own license does not license every
community grammar.

---

## 5. Source ingestion

### 5.1 Source units are explicit

Source is read via `dao.stream`. Use a versioned source vocabulary with
events such as:

```clojure
{:yang.source/event :yang.source/chunk
 :yang.source/unit ["session-token" 7]
 :yang.source/revision 3
 :yang.source/ordinal 0
 :yang.source/text "def f(x):\n"}

{:yang.source/event :yang.source/seal
 :yang.source/unit ["session-token" 7]
 :yang.source/revision 3
 :yang.source/chunk-count 1}
```

The complete protocol also defines begin, abort, byte-chunk, and
snapshot-reference forms.

Rules:

- Every chunk belongs to one unit revision and has an explicit ordinal.
- Duplicate identical chunks can be deduplicated; conflicting duplicates
  fail.
- A seal fixes the source snapshot. Missing chunks prevent admission.
- Stream `blocked` means no event yet, never language EOF.
- Stream `end` before a required seal is truncated input.
- A shared REPL stream remains open between submissions.
- Source edits append new revisions; they do not mutate previous source.

File readers and interactive input adapters deposit source events.
Lowering never reads a filesystem or terminal directly.

### 5.2 Encoding and source coordinates

Default new text submissions to a documented encoding, normally UTF-8.
Language-specific source encoding declarations require a versioned
decoding policy.

Raw byte chunks must use an agreed portable representation, such as
vectors of byte values or content references, rather than host
byte-array objects. Incremental decoding retains incomplete multibyte
sequences between chunks.

Canonical spans use half-open offsets into the original source bytes.
Host character offsets, code-point positions, UTF-16 indexes, and
inclusive token end positions are converted at the boundary. Line and
column values are presentation projections.

Preserve original source and transformation maps for language
preprocessing, including Unicode escapes, indentation processing, or
mixed-code regions. Do not normalize line endings or Unicode text
silently.

Large source units may spool through a supplied content service. Content
lookup and materialization must themselves use stepped request/response
interfaces where they can wait.

### 5.3 Chunk boundaries: streaming by complete compilation unit

Baseline ANTLR integration buffers or spools a sealed unit before
parsing. It does not equate a chunk with a parseable statement.

This matters for indentation, multiline strings, comments, lexical
modes, automatic semicolon insertion, and syntax that depends on later
tokens.

The architecture supports:

- Streaming source ingestion.
- Concurrent compilation of independent sealed units.
- Chunked CST and code delivery.
- Bounded compiler turns.

It does not initially promise arbitrary mid-rule parser suspension or
incremental reparsing.

REPL frontends can publish a completeness probe returning complete,
incomplete, or invalid. A blanket "error at EOF means incomplete" rule is
unsound. Where a reliable probe is unavailable, use an explicit submit
action.

### 5.4 Backpressure, cancellation, and recovery

Backpressure is the stream's own `full` and `blocked` outcomes, handled
by the compiler as section 6.2 specifies: a `full` append retains the
identical staged value and yields; a `blocked` read preserves state and
cursor and yields. No stage buffers without bound to hide either.

Cancellation travels on a control stream. The coordinator records
cancelled state, requests worker termination, and declines to admit late
results. It closes only resources it owns.

Recovery replays the immutable source snapshot. Because a seal fixes the
snapshot and every chunk has an explicit ordinal, a lost parse is
replayed in another worker under a new attempt identity (section 6.4)
without re-reading the original file or terminal.

### 5.5 Portable CST packets

The syntax exporter emits a versioned packet containing:

- Request, source revision, and grammar profile identity.
- A root occurrence identifier.
- Flat rule and terminal records.
- Ordered child references.
- Source spans and token vocabulary names.
- Explicit recovery/error records where applicable.
- A completion record identifying the complete packet.

CST identifiers are deterministic within a packet, for example preorder
ordinals. They are not ANTLR object identities.

Rule names and declared labels are preferable to target class names. Raw
numeric token IDs require the pinned vocabulary. Alternative labels are
emitted only where the exporter can obtain them reliably; the contract
must not assume every runtime preserves an alternative number.

Lexemes can be recovered from source spans and normally need not be
copied. Synthetic recovery tokens require explicit text and an insertion
coordinate.

The CST is a **regenerable compiler artifact**, not a new canonical
Universal AST. Retention for debugging is an explicit composition policy.
Child edges are data; tree structure is validated before traversal.

---

## 6. DaoStream integration

### 6.1 Explicit state and bounded work

Each interpreter in the pipeline owns:

- Opaque cursors for its inputs.
- Pinned profiles and dependency snapshots.
- An explicit work queue or traversal stack.
- Staged output and the next output index.
- Request/attempt identities.
- Resource counters and terminal status.

A proposed driver-facing interface is:

```text
compiler-step(stream-handles, state, work-budget)
  -> {:yang.compile/state ...
      :yang.compile/status ...
      :yang.compile/progress ...}
```

Handles stay in host-local composition. Portable state carries
identities and descriptors where needed; it does not serialize handles.

Budgets cover reads, transitions, writes, and work on large structures.
Existing synchronous encoding, hashing, validation, or query helpers
must be made stepped or run in workers when their input can be large.
Adding a `budget` argument around an unbounded recursive function is
insufficient.

The caller drives steps. Cadence or `dao.stream.waitset` can reduce idle
polling, but no interpreter requires a readiness extension.

A compilation request is itself an effect descriptor appended by
portable code: it names the source unit revision, the frontend revision
and dialect, and the options, and it carries a request identity. The
compiler coordinator consumes those descriptors and appends outcomes.
Portable code never calls the compiler as a function.

### 6.2 Respect the existing outcome algebra

Compiler outcomes and DaoStream outcomes occupy different layers:

```clojure
;; Successful observation of a failed compilation.
{:dao.stream/outcome :dao.stream/ok
 :dao.stream/value
 {:yang.compile/outcome :yang.compile/rejected
  :yang.compile/request ["client-token" 12]
  :yang.compile/reason :yang.compile/syntax-error}
 :dao.stream/cursor successor}
```

Do not invent `:dao.stream/syntax-error` or extend DaoStream's seven
operations.

The compiler handles stream outcomes as follows:

```text
+------------------------+----------------------------------------------+
| Outcome                | Compiler action                              |
+========================+==============================================+
| next: ok               | Incorporate input into explicit state and    |
|                        | record disposition                           |
+------------------------+----------------------------------------------+
| next: blocked          | Preserve state and cursor; yield             |
+------------------------+----------------------------------------------+
| next: end              | Apply source/stage framing rules; never      |
|                        | invent a seal                                |
+------------------------+----------------------------------------------+
| next: gap              | Fail the affected attempt or replay a        |
|                        | verified snapshot                            |
+------------------------+----------------------------------------------+
| next: cursor defect    | Report wiring/protocol failure               |
+------------------------+----------------------------------------------+
| append: ok             | Commit this staged write locally             |
+------------------------+----------------------------------------------+
| append: full           | Retain the identical staged value; yield     |
+------------------------+----------------------------------------------+
| append: closed or      | Record delivery failure; do not discard or   |
| invalid-value          | rerun lowering                               |
+------------------------+----------------------------------------------+
| transport-error        | Preserve the result and apply explicit       |
|                        | recovery policy                              |
+------------------------+----------------------------------------------+
```

Cursor advancement follows the repository's disposition rule: either
output has been accepted, or the successor state fully owns the consumed
input and its pending work. A crash-safe variant additionally needs
durable checkpointing or replay with deduplication.

Retries of rejected appends preserve payload and identity. They do not
rerun parsing, allocate fresh names, or duplicate diagnostics.

### 6.3 Admission and publication are distinct

Compilation stages use candidate media. Evaluators observe only the
admitted program medium.

Publication requires:

1. A sealed source snapshot.
2. Complete parser output and diagnostic status.
3. Successful semantic analysis and lowering.
4. Complete canonical row closure.
5. Structural and content-integrity validation.
6. A pinned runtime/dependency profile.
7. A terminal successful admission decision.

For large artifacts, stage chunks with a manifest and completion record.
An admission interpreter reconstructs or resolves the complete artifact
before making it executable. A partially delivered prefix is never a
program.

Persisting candidate rows before success is permissible, but creates no
execution authorization or published name.

There is no cross-stream transaction supplied by DaoStream. If code,
provenance, and refs must all be retained before publishing a name, the
publication interpreter waits for their explicit receipts. It does not
infer remote durability from `append!` returning `ok`.

### 6.4 Retention, duplication, cancellation, and restart

Source, candidate, and required-diagnostic media need declared retention
sufficient for their correctness obligations. Do not substitute a large
evicting ring buffer for complete history.

A host callback deposit destination must admit every event allowed by
the boundary's admission budget. Use explicit capacity reservation or a
suitable complete-history destination; never silently drop a diagnostic
when a callback cannot retry.

Request identity and attempt identity are different:

- Retransmitting one request retains its request ID.
- Replaying a lost parse may create a new attempt ID.
- Semantic output identity excludes both.
- Diagnostic and lifecycle records retain attempt identity.

Application-level acknowledgments and deduplication handle uncertain
remote delivery. DaoStream does not promise exactly-once remote
execution or durable append.

Cancellation travels on a control stream. The coordinator records
cancelled state, requests worker termination, and declines to admit late
results. It closes only resources it owns.

Cursors remain opaque. Cross-host restart must not assume
transport-independent cursor serialization, which the current stream
contract leaves unresolved. Portable recovery uses snapshot identities
and explicit event ordinals, then obtains valid cursors from the
destination composition.

---

## 7. Universal AST encoding and provenance

### 7.1 Three layers, one authoritative content

Code exists in three forms, each with one job (`yin.vm.code-as-tuples.md`
section 1):

1. **Ephemeral Universal AST maps**: the frontend's output and the
   walker's reconstructed image. Never hashed, stored, or shipped.
2. **Canonical flat rows** `[id tag & slots]`: one row per node, content
   addressed by `dao.jing/segment-key` over the row body. What streams
   carry, what `dao.jing` stores, what names resolve to.
3. **Datoms** `[e a v t m]`: naming, publication, provenance, diagnostics,
   and optional regenerable EAV projections.

There is no second authoritative AST. Lowering constructs only nodes
admitted by `yin.vm/semantic-bytecode-grammar`. Class, generator,
prototype, pointer, and language-specific scope nodes are not added
merely for frontend convenience.

For example, an ephemeral map:

```clojure
{:type :application
 :operator {:type :variable :name 'example.runtime/add}
 :operands [{:type :literal :value 1}
            {:type :literal :value 2}]
 :tail? false}
```

projects to flat rows:

```clojure
[A :application B [C D] false]
[B :variable example.runtime/add]
[C :literal 1]
[D :literal 2]
```

`A` through `D` are schematic addresses computed from row bodies.

The encoder owns saturation, projection, and occurrence side tables.
Frontends do not implement competing hash schemes.

### 7.2 Existing integration points

Reuse:

- `yin.vm/ast->semantic-bytecode`.
- `yin.vm/validate-rows` plus content-address verification.
- `yin.vm/semantic-bytecode->ast`.
- `yin.vm/ast-side-tables` and `occurrences`.
- `yin.vm.encoder` batch construction.
- `yin.vm.linearize/lower-rows`.
- Existing row loaders and macro packet conversion.

Shape validation alone is not hash verification. Both are required
before accepting artifacts from a service.

Phase 0 must settle the explicit conversion between provenance-bearing
encoder envelopes and current macro packets. In particular, origin, batch
member index, harvest order, and side-table ownership must survive
conversion. Do not introduce an undocumented third program format.

Macro expansion remains optional topology. Python decorators and
JavaScript calls are not automatically Yin macros.

### 7.3 Derive rather than duplicate

Per the "Derive, don't persist" principle of `datom.world.md`, do not add
canonical fields for:

- Source language or filename.
- Parent links and depth.
- Free/bound classification.
- A repeated root marker.
- Cached call graphs.
- Types erased by lowering.
- Dependencies already derivable from rows and runtime profiles.

Free-name queries must remain root- and occurrence-scoped. A shared
variable row can be bound at one occurrence and free at another.

Facts not recoverable after lowering, such as original source spans or
source-level type annotations, belong in occurrence side tables. Facts
required for execution, such as a class's runtime dispatch data, belong
in executable prelude values or program data. "Derive" must not erase
required semantics.

### 7.4 Provenance and fingerprinting

Use the repository's occurrence coordinate:

```clojure
[[:source program-medium batch-token member-index]
 root-address
 structural-path]
```

This is separate from the original source-file revision. Record the link
from the program admission occurrence to the source snapshot and
frontend profile.

A compilation derivation record pins source, grammar, normalization,
lowering, runtime profile, dependency snapshot, and output roots. Its
address is published through ordinary ledger datoms. This record is the
fingerprint of a compilation: two compilations with the same pinned
inputs produce the same record address and the same output root
addresses.

Content addresses occupy `v`, not `e`. The transactor owns persistent
local IDs and transaction time. Optional AST datom projections remain
regenerable query views and never become the load identity.

---

## 8. Semantic lowering per paradigm

The language list in this section is non-exhaustive. Each subsection is
a reference mapping from one paradigm to the Universal AST; a new
paradigm, or a new language within a paradigm, adds its mapping through
the SPI of section 3 and changes nothing here.

### 8.1 Shared semantic foundation

The common target is a small computation language with explicit language
runtime libraries.

The reference semantic ABI should define:

- Evaluation order.
- Argument binding and arity checks.
- Language value encodings.
- Identity and aliasing.
- Scope and initialization states.
- Normal and abrupt completion.
- Effect requests and responses.
- Suspend/resume behavior.

Do not inherit Clojure truthiness, equality, integer arithmetic, or
argument binding merely because the frontend is implemented in Clojure.

The baseline is a task-owned heap of cells updated through persistent
VM-state transitions, with continuation-based control flow (mob D3,
amending the earlier baseline of a threaded immutable heap; cell
ruling Q1 to Q5):

- Mutable guest locations are cells. A cell is an ordinary sealed
  reference value `{:type :cell-ref :id :cell-N :seal s}` into a
  task-scoped `:heap` field of the VM value, reached through the `cell`
  host module: `cell/new`, `cell/get`, and `cell/set!`. A write yields a
  new VM value; nothing host-mutable is introduced. No AST tag is added,
  and `yin/def`, Rule R, `:vm/store-put`, and the canonical grammar are
  unchanged.
- Cells have box semantics: they are shared across continuation
  re-entries and never rolled back, so mutations made before a `raise`
  persist at the handler.
- Explicit state threading stays an allowed per-frontend choice. Either
  lowering can be implemented by a stream-consuming interpreter.
- References are logical identities: a `:cell-ref` is data, and a forged
  or foreign ref fails closed at the evaluator.
- Loops lower to recursive lambdas. Continuations are used for the
  escapes (`break`, `continue`, `return`), because a continuation takes
  exactly one argument.
- Abrupt completion (return, throw, break, continue, yield) lowers to
  captured continuations, not explicit completion records (cell ruling,
  owner decision 5). Locals assigned inside `try` bodies and
  continuation-exited loops therefore need cells.
- Suspended state consists of portable code references and data.

In this document a "store cell" or "binding cell" means a `cell` module
ref over the task heap. It is not a location in the named store: the
named store stays statically keyed under Rule R and holds `yin/def`
definitions.

Every function-local binding and every parameter is a cell, allocated at
function entry with an unbound sentinel (mob D5). A continuation
captures its environment, so an env-bound local assigned between capture
and invocation would rewind; a closure that refers to a name before its
first assignment needs a pre-established location. Binding collection
(which names are local to a function, and where `global` and `nonlocal`
apply) is part of the required lowering stage. Liveness analysis and
un-boxing are a separately attached interpreter that reads the naive
lowering's stream and writes its own; the composition chooses which
stream the evaluator reads, and correctness never depends on an optional
observer.

Cells cross task boundaries by copy (mob D1). Aliases and cycles are
preserved within one transferred value graph, and the receiver mints
fresh ids and re-seals. Cell slice 1 refuses every cell-bearing lift.
There is no cross-task shared cell; cross-task shared state requires an
explicit stream protocol.

Aliased guest objects share logical identity, not host mutable objects.
Concurrent access to a guest heap has one owning interpreter or an
explicitly defined stream protocol. Section 8.11 states how mutable guest
objects and collections are represented over cells.

Generated names come from a deterministic, capture-avoiding name supply
in compiler state. Namespace-global `gensym` counters, request IDs,
source positions, and host map iteration order must not affect canonical
code.

The existing AST's `:vm/store-put` value is data, not an evaluated child.
Dynamic assignment must use a supported effectful application, such as
`cell/set!`, or the explicit state ABI; inserting an AST under `:val`
would store syntax.

Runtime values must also avoid accidental interpretation as engine
effects. Effects are an unforgeable host type minted only by
`module/make-effect`, and a map is data whatever keys it carries, so a
guest object containing a property named `effect` stays data. When a
primitive's result is an effect, the engine checks its kind against the
callee's declared effect set, so a callee profile bounds the effects it
may raise (mob D4, landed in `9a69e58f`). The effect constructor is not
exposed as a guest primitive, and its host wrapper is not the
representation emitted onto compilation or effect streams. Language
values still use a defined tagged representation per runtime profile.

### 8.2 Placement of semantics

**Compiler desugaring** handles syntax-directed control, binding
analysis, static checks, evaluation order, and construction of runtime
operations.

**Portable prelude code** handles object systems, dispatch, coercions,
collections, language exceptions, argument binding, and other
language-visible behavior. Prefer Universal AST code so behavior is
inspectable and portable.

**Profiled pure primitives** can accelerate expensive value operations
when implementations have equivalent behavior across hosts.

**Yin FFI/effects** handle actual host interaction: files, sockets,
timers, processes, external libraries, and native resources. They are
stream request/response protocols with declared capabilities.

Calling a host's native Python, JVM, or JavaScript evaluator is a
separate compatibility service profile. It does not establish that the
program was compiled into portable Yin semantics.

### 8.3 Paradigm summary

```text
+------------------------+----------------------------+------------------+
| Paradigm               | Universal AST target       | Reference        |
|                        |                            | examples         |
+========================+============================+==================+
| Object-oriented,       | Records (runtime           | Java, PHP,       |
| class-based            | descriptors plus fields)   | Python classes   |
|                        | and method code; dispatch  |                  |
|                        | in prelude                 |                  |
+------------------------+----------------------------+------------------+
| Functional             | Direct lowering to lambda, | Clojure,         |
|                        | application, if, literal   | ClojureScript,   |
|                        |                            | ClojureDart,     |
|                        |                            | Haskell-style    |
+------------------------+----------------------------+------------------+
| Prototype / dynamic    | Closures plus store cells  | JavaScript       |
|                        | for mutable bindings and   |                  |
|                        | property maps              |                  |
+------------------------+----------------------------+------------------+
| Systems                | Explicit ownership state,  | Go, Rust, C      |
|                        | value copying, and effect  |                  |
|                        | descriptors for memory and |                  |
|                        | scheduling                 |                  |
+------------------------+----------------------------+------------------+
| (any further paradigm) | Added via the SPI; no core | non-exhaustive   |
|                        | change                     |                  |
+------------------------+----------------------------+------------------+
```

### 8.4 Object-oriented: Java, static typing and class-based objects

The Java frontend performs declaration collection, resolution, type
checking, overload selection, access checks, and relevant
definite-assignment checks before executable lowering.

Classes become runtime descriptors plus explicit fields, method code,
constructor logic, and initialization state. Interfaces contribute type
constraints and dispatch contracts. Virtual calls use runtime dispatch;
overload resolution remains a compile-time responsibility. Evaluation
order must follow the selected Java edition (Java Language
Specification, chapter 15).

The prelude must define:

- Primitive numeric widths, overflow, conversions, and comparisons.
- Object identity, null behavior, arrays, and bounds failures.
- Inheritance, interface dispatch, and method invocation.
- Static members and class initialization lifecycle.
- Language exceptions and cleanup behavior.

Typed variables do not require a new canonical node tag. Erased checking
information remains analysis data; runtime-observable type information
becomes explicit program values.

Reflection, class loading, monitors, threads, and JNI require additional
support profiles. A class-based subset must not be labeled full JVM
compatibility.

### 8.5 Dynamic: Python, indentation and dynamic values

Python is dynamically typed, but ordinary name resolution is based on
lexical scopes with explicit `global` and `nonlocal` rules. It is not
generally dynamically scoped. Whole-block binding analysis is needed to
distinguish local variables and unbound-local errors (Python execution
model reference).

The frontend and prelude divide responsibilities as follows:

- Indentation, line joining, and lexical state belong to the grammar
  package and its parsing helpers.
- Scope analysis precedes lowering.
- Every function-local binding and parameter is a cell (section 8.1,
  mob D5), and assignments are `cell/set!`; nested lambdas alone do not
  implement mutable captured variables.
- A module namespace is a dict object in a heap cell, Python's own
  `__dict__` model, not a set of `yin/def` store keys (Python mappability
  ruling, owner decision 1). Global reads and writes go through that
  dict, and an undefined global is a dict miss that raises `NameError`.
  `yin/def` is reserved for the prelude and builtins. `exec` with a
  namespace is a compilation request whose lowering targets a given dict
  object. For Python this replaces the cell ruling's Q5 statement that
  module-level variables and `global x` stay `(yin/def x v)` into the
  module store. Open: the rulings do not say whether other languages'
  module-level variables stay in the store or move to heap namespaces.
- `and` and `or` preserve short-circuiting and return operand values.
- Comprehensions preserve evaluation order and their dialect-specific
  scope.
- Decorator expressions and application order are explicit; execution
  occurs when the definition is evaluated.
- Default arguments, keyword arguments, and variadic binding use
  Python's calling rules. Defaults, `*args`, `**kwargs`, `__name__`,
  function identity, and arity checking need a function-object wrapper
  in a cell; a bare closure nil-fills missing arguments (Python
  mappability ruling Q4).
- Generators lower to explicit resumable state, including completion and
  injected exceptions: the generator is a mutable identity whose saved
  resume continuation lives in a cell, and `throw` resumes that
  continuation with a tagged "raise here" value the yield site checks
  (cell ruling Q1 and Q4; Python mappability ruling). The lowering invokes
  each captured continuation at most once. Section 8.5.3 records the C2
  design that realizes this.

Truthiness, arbitrary-precision integers, division, equality, attribute
lookup, descriptors, and iteration belong to the Python runtime profile.
The Python value encoding tags floats on every host, since JavaScript
cannot distinguish `2` from `2.0` and ints are the common case (Python
mappability ruling, owner decision 3); `print(4/2)` belongs in the Node
parity set. Integers stay untagged at every magnitude; section 8.5.4
records the C3 integer rulings. The tag is Python type semantics only:
it does not fix the payload's numeric kind, which must be Jing float64
content on every host for addresses to agree (float-address ruling,
section 8.5.5).

Generator support is incomplete until `send`, `throw`, `close`, cleanup,
and suspension are tested. It is not established by parsing `yield`.

#### 8.5.1 Python 3 mappability

Headline (Python mappability ruling): with multi-shot continuations,
cells, closures, and effects, the Universal AST is a complete target for
the Python 3 language reference. Nothing in the language reference is
inexpressible. Every "cannot" is one of: cost, host coupling, a
datom.world invariant, or a CPython implementation detail the language
reference does not promise. The residue is exactly:

- Nondeterminism: hash randomization, `id()` layout, `random`, and time.
  These lower to effects or are forbidden by determinism, and the
  reference never promises their values.
- Arbitrary-point asynchrony: signals delivered anywhere. Forbidden by
  "no implicit control flow"; safepoints are the faithful-enough form.
- CPython internals: refcounts, the C API, and bytecode bytes.
- Preemptive shared-memory threads: forbidden by "no shared mutable
  state"; green threads within one task are the mapping.

Python itself needs no multi-shot re-entry: everything it does is
one-shot escape or generator resume. Multi-shot is the VM's generality,
not a Python requirement.

The corrected bucket table follows. The ruling labels bucket 1 as
expressible at a cost and bucket 3 (in the analysis it corrects) as
needing VM infrastructure. Open: the ruling does not define buckets 2
and 4 in words; its usage attaches 2 to host-coupled constructs and 4 to
invariant-forbidden constructs and CPython internals.

```text
+----------------------------------------------+------------------------+------------------------------------------------------------------------------+
| Construct                                    | Bucket                 | Reason                                                                       |
+==============================================+========================+==============================================================================+
| Frame introspection: `sys._getframe`, live   | 1 (cost)               | The lowering can maintain an explicit frame record (a cell stack of frames   |
| `locals()`, `f_back`, tracebacks             |                        | whose locals are already cells), which is Python's own model. VM-exposed     |
|                                              |                        | continuations are only needed to get it for free, and D7 is moving the other |
|                                              |                        | way.                                                                         |
+----------------------------------------------+------------------------+------------------------------------------------------------------------------+
| Line numbers in tracebacks                   | 1, with an owner       | Derived by default at the boundary from the position side tables of          |
|                                              | decision               | `yin.vm.code-as-tuples.md` section 2.5 (see the decisions below).            |
+----------------------------------------------+------------------------+------------------------------------------------------------------------------+
| `RecursionError`, `setrecursionlimit`        | 1                      | Continuations are heap data on all four VMs, so nothing overflows; a depth   |
|                                              |                        | counter in a cell is prelude code. An escape restores the depth saved at     |
|                                              |                        | capture rather than decrementing by one (section 8.5.2).                     |
+----------------------------------------------+------------------------+------------------------------------------------------------------------------+
| `weakref`, `__del__`, `gc`                   | 1 conforming, 3        | The reference says `__del__` is not guaranteed to run and a weakref may stay |
|                                              | faithful               | alive; never collecting conforms. Faithful behavior needs reclamation.       |
+----------------------------------------------+------------------------+------------------------------------------------------------------------------+
| `KeyboardInterrupt`, signals                 | 2 plus safepoints;     | Delivery at an arbitrary point is implicit control flow and                  |
|                                              | arbitrary-point        | nondeterministic. The signal is an event on a stream, polled at safepoints.  |
|                                              | delivery is 4          | CPython itself delivers only between bytecodes.                              |
+----------------------------------------------+------------------------+------------------------------------------------------------------------------+
| `sys.settrace`, `setprofile`                 | 1, via safepoints      | A trace hook calls back into guest state, so it cannot be a stream observer. |
|                                              |                        | Same safepoint mechanism as signals and thread switches.                     |
+----------------------------------------------+------------------------+------------------------------------------------------------------------------+
| GIL atomicity                                | 1                      | Green threads switch only at safepoints, so every operation between them is  |
|                                              |                        | atomic, which is stronger than the GIL. Preemptive shared-memory threads     |
|                                              |                        | stay 4.                                                                      |
+----------------------------------------------+------------------------+------------------------------------------------------------------------------+
| Code objects, `__code__`, `compile()`        | 1 for code objects; 4  | Code is content-addressed rows; a closure already carries its lambda row id. |
| results                                      | only for CPython       | Programs reading `co_code` or `dis` output want CPython bytes, which are an  |
|                                              | bytecode fidelity      | implementation detail.                                                       |
+----------------------------------------------+------------------------+------------------------------------------------------------------------------+
| `globals()` writes, `del` of a global,       | 1, but not with        | Inexpressible against the store by design of the store (Rule R literal keys, |
| `exec` with a namespace, `setattr(module,    | store-based globals    | no delete); expressible once a module namespace is a heap dict (see the      |
| ...)`                                        |                        | decisions below).                                                            |
+----------------------------------------------+------------------------+------------------------------------------------------------------------------+
| Hash randomization                           | 4 by invariant,        | Nondeterministic by design; the reference makes no ordering promise that     |
|                                              | harmless               | depends on it. Deterministic (insertion) set order is within spec.           |
+----------------------------------------------+------------------------+------------------------------------------------------------------------------+
| Generators, including `send`, `throw`,       | 1, with 3 for          | `throw` resumes the saved continuation with a tagged "raise here" value the  |
| `close`, and `try`/`finally` inside          | close-on-collection    | yield site checks. `close()` triggered by garbage collection needs           |
|                                              |                        | reclamation.                                                                 |
+----------------------------------------------+------------------------+------------------------------------------------------------------------------+
| `match`, walrus, comprehension scopes, class | 1                      | Desugaring, scope analysis, or prelude. `__class__` is literally a cell      |
| body execution, `__set_name__`,              |                        | captured by methods. `sys.exc_info` is a handler-stack cell.                 |
| `__init_subclass__`, `__class_getitem__`,    |                        |                                                                              |
| zero-argument `super`/`__class__`,           |                        |                                                                              |
| descriptors, `__slots__`, `__radd__`         |                        |                                                                              |
| fallback, `except*`,                         |                        |                                                                              |
| `__context__`/`__cause__`                    |                        |                                                                              |
+----------------------------------------------+------------------------+------------------------------------------------------------------------------+
| `async`/`await`, async generators, `async    | 1 for the machinery, 2 | The job scheduler is prelude over continuations reading a completion stream. |
| with`/`for`                                  | for what is awaited    |                                                                              |
+----------------------------------------------+------------------------+------------------------------------------------------------------------------+
| `importlib` hooks, `sys.modules`, `reload`   | 2                      | Python module objects must be heap objects; the yin.vm linker delivers code, |
|                                              |                        | not namespaces.                                                              |
+----------------------------------------------+------------------------+------------------------------------------------------------------------------+
| Float semantics, IEEE, NaN, `-0.0`           | 1, with a tag          | All three hosts are binary64, but JS cannot distinguish `2` from `2.0`, so   |
|                                              | requirement            | the value encoding must tag floats (or ints) explicitly on every host.       |
+----------------------------------------------+------------------------+------------------------------------------------------------------------------+
| `str` as code points, normalization          | 1                      | Hosts are UTF-16; the data module's code-point operations are required, and  |
|                                              |                        | normalization tables are Layer 1 data.                                       |
+----------------------------------------------+------------------------+------------------------------------------------------------------------------+
| C extensions, `ctypes`, refcount timing,     | 4                      | Agreed by the ruling: CPython internals.                                     |
| `sys.getrefcount`, interning seen by `is`    |                        |                                                                              |
+----------------------------------------------+------------------------+------------------------------------------------------------------------------+
```

Owner decisions on the ruling:

1. A Python module namespace is a heap dict, not `yin/def py.g/*` store
   keys (section 8.5). The cost is a dict lookup per global read; an
   un-boxing interpreter can later recover static reads where no dynamic
   write is reachable. It unlocks `globals()`, `del`, `exec`, module
   attributes, and `sys.modules`.
2. Traceback line numbers are derived by default at the boundary from
   the position side tables (not guest-visible), per derive-don't-persist.
   Line literals are embedded in frame records only under a profile that
   demands a guest-visible `tb_lineno`; embedding makes code addresses
   sensitive to whitespace changes.
3. The Python value encoding tags floats (section 8.5), and the tagged
   payload is Jing float64 content on every host (section 8.5.5).
4. Safepoint insertion (signals, tracing, thread switches, recursion
   accounting) is a separately attached interpreter over the row stream,
   not part of the naive lowering. The safepoint design fixes its shape:
   sites come from frontend marks; a safepoint is an ordinary application
   of a hook function defined in a per-language hook prelude; the engine
   gains only the generic `:stream/poll` effect; names refer to the
   canonical tree while the evaluator runs the derived one (decisions 5
   to 7 and section 8.5.2).

The owner accepted the safepoint design's seven decisions on 2026-10-01.
This document numbers them 5 to 11, continuing the list above:

5. One new generic effect, `:stream/poll`, exported as `stream/poll!`: a
   non-parking stream read that returns `:dao.stream/blocked` as a value.
   It exposes `blocked` to guest code. A dedicated safepoint effect,
   which would put safepoint knowledge in the engine, is rejected.
6. Sites come from frontend marks, not structural derivation. Revisit
   when the prelude becomes a linked module. C4 ruling 15 (section 8.5.6)
   settles the revisit: marks stay.
7. Identity: names, the ledger, and publication refer to the canonical
   tree; the evaluator runs the derived tree; any guest-visible code
   identity reports the canonical address through the derivation.
8. Signal delivery timing is an operational event and is not journalled
   in slice 1. Journalling each delivery with a safepoint ordinal is the
   rejected alternative.
9. Green threads switch count-based, every N safepoints, never
   time-based.
10. `sys.settrace` under a profile without tracing raises an explicit
    unsupported error rather than being accepted silently.
11. Slice order: signals, recursion, tracing, threads.

Further interactions stated by the ruling:

- A suspended generator, a green thread, or a frame record holds a
  reified continuation in a cell. The encoder refuses those, so a task
  with one reachable is non-migratable until reified continuations
  encode.
- The prelude must not test the continuation representation, since
  host-typed continuations (mob D7) would break such a test. The
  representation-independent form is a per-capture flag cell allocated
  before the capture, set on the first pass, that tells the passes apart.
  Frame introspection must not read `:env` out of a continuation map; the
  explicit frame record avoids it.
- Python threads share one heap and module objects are shared
  identities, so one Python "process" is one task. `multiprocessing` maps
  to tasks and streams.
- Python module identity lives in the heap. The linker delivers
  content-addressed code images (prelude, standard library), never
  Python namespaces.
- Exceptions carrying tracebacks retain frames and continuations, and
  generators retain their whole activation, so Python programs raise the
  priority of heap reclamation.
- Until the prelude is a linked module, each unit bundles its own prelude
  and so its own builtin class identities, and `isinstance` across units
  fails. A linked prelude copies no class objects: install delivers only
  code, and each task allocates its own through `py/init!` (C4 ruling 1,
  section 8.5.6). This supersedes the per-receiving-task copy (mob D1)
  stated here before.

#### 8.5.2 Safepoint insertion

This section records the safepoint design under owner decisions 4 to 11
above. Its implementation is pending, in the slices of decision 11; none
of the parts below has landed.

A generic stage reads the canonical program, inserts ordinary
`:application` rows calling per-kind hook functions at frontend-marked
sites, and writes a derived program to its own stream. All semantics
live in a per-language hook prelude. The evaluator never learns what a
safepoint is, the same shape as macros as stream topology.

```text
+------------------+----------------------------------+------------------------+
| Part             | Job                              | Knows about            |
+==================+==================================+========================+
| Frontend         | Marks sites as `:yang/site`      | Which lambda is a loop |
| lowering         | metadata on map-AST nodes.       | or a function; where   |
|                  | Projection strips the marks into | statements start       |
|                  | the frontend-metadata side       |                        |
|                  | table, so the naive rows are     |                        |
|                  | unchanged.                       |                        |
+------------------+----------------------------------+------------------------+
| `yang.safepoint` | Rewrites tree A into tree A' by  | The Universal AST and  |
| stage            | inserting hook applications at   | the side table only    |
|                  | marked sites, per a profile      |                        |
|                  | `{kind hook-symbol}`. A pure     |                        |
|                  | function of tree, sites, and     |                        |
|                  | profile.                         |                        |
+------------------+----------------------------------+------------------------+
| Hook prelude,    | Defines the hook functions:      | The language's         |
| per language     | signal delivery, depth           | semantics              |
|                  | accounting, trace calls, thread  |                        |
|                  | switch.                          |                        |
+------------------+----------------------------------+------------------------+
| Engine           | `stream/poll!`: like             | Nothing about          |
|                  | `stream/next!`, but `blocked` is | safepoints             |
|                  | a value, not a park.             |                        |
+------------------+----------------------------------+------------------------+
```

A safepoint is an ordinary `:application` whose operator is a
`:variable` naming a hook, for example `(py.sp/loop)`. There is no new
tag: one would change the grammar on four VMs and the codec for a fact
that is only placement. There is no dedicated `:safepoint` effect:
effect handling cannot apply a guest closure, which is why `cell/swap!`
was rejected (section 8.11), and `settrace` needs exactly that.

The one engine addition (decision 5) exists because `stream/next!` parks
on `blocked` and the stream module has no other read, so a hook polling
with it would stop the program at the first safepoint with no signal
pending. `stream/poll!` returns `:dao.stream/blocked` without parking,
and `ok` advances the cursor. It is its own effect kind with a declared
profile, so a callee without it is refused and a profile that omits it
cannot observe timing. The prelude's async and thread schedulers need
the same primitive.

Sites come from frontend marks (decision 6):

```text
+----------------+------------------------------+------------------------------+
| Kind           | Mark                         | Inserted                     |
+================+==============================+==============================+
| `:loop`        | On the loop lambda           | Hook at the head of the body |
+----------------+------------------------------+------------------------------+
| `:call`,       | On the function code lambda  | Hook at the head; the body   |
| `:return`      |                              | is wrapped so the exit hook  |
|                |                              | runs after it                |
+----------------+------------------------------+------------------------------+
| `:line`        | On the node that begins a    | Hook before it, with the     |
|                | statement                    | line as a literal operand    |
+----------------+------------------------------+------------------------------+
```

- Structural derivation ("every closure-valued lambda") is rejected. It
  cannot tell a function from a loop, which recursion accounting and
  `:return` need. And while each unit bundles its prelude, it would
  instrument the prelude and break the get/set atomicity section 8.11
  relies on ("a task switches only at park points"). The prelude carries
  no marks, so marked insertion leaves it untouched.
- A kind absent from the profile is not inserted.
- The `:line` literal lives only in the derived tree, so the canonical
  program stays insensitive to whitespace (owner decision 2 holds).
- The lowering emits an `encoder/source-envelope` with the map AST as
  its member, so the marks reach the side table.

Rewriting preserves addressing:

- The stage reconstructs the map AST from rows, inserts, and
  re-projects through `vm/ast->semantic-bytecode`. Every row id is
  recomputed, so `id = segment-key(body)` holds by construction.
  Ancestors of a site get new ids; untouched subtrees keep theirs and
  are shared with the canonical tree.
- The canonical program A is never modified. Names, the ledger, the
  index, publication, the linker, and diffs refer to A (decision 7).
- The link from A to the derived program A' is an existing `:derive`
  ledger record: input A, output A', function `:yang.safepoint/insert`,
  and a profile pinning the hook map and the address of the sorted site
  set. Insertion is deterministic, so a repeat writes the same record
  and no attempt identity is needed. No new ledger op is added;
  tree-to-tree derivations are distinguished by `:yin.ledger/function`.
- A' is admitted on the stage's own output medium with its own batch
  token, so its occurrence origin is an ordinary `[:source medium' batch'
  j]`. No new origin kind.
- Side tables are not copied or re-keyed. Insertion only prefixes and
  wraps, so the path map from A' back to A is a pure function of the
  site set; positions for an A' node join through it to A's occurrence.
- Inserted binders use a reserved `yang.safepoint/` namespace, with no
  gensym counter.
- Tail marks are stripped and recomputed over the whole derived tree. A
  tail call pushes no frame, so a body wrapped by an exit hook whose
  applications kept `tail? true` would skip the hook.
- The evaluator reads exactly one stream, the canonical one for a naive
  run or the stage's output, never both (section 1.1).

The stage places hooks; the semantics are prelude code:

```text
+---------------------+-----------------+--------------------------------------+
| Consumer            | Sites           | Hook prelude behavior                |
+=====================+=================+======================================+
| Signals,            | `:loop`,        | A host adapter appends a plain event |
| `KeyboardInterrupt` | `:call`         | to a stream the composition          |
|                     |                 | supplies. The hook calls             |
|                     |                 | `stream/poll!`; on an event it runs  |
|                     |                 | the registered Python handler, or by |
|                     |                 | default raises `KeyboardInterrupt`   |
|                     |                 | (a class under `BaseException`)      |
|                     |                 | through `py/raise`, an explicit      |
|                     |                 | continuation invoke at an explicit   |
|                     |                 | program point.                       |
+---------------------+-----------------+--------------------------------------+
| `sys.settrace`,     | `:call`,        | The trace function is a guest value  |
| `setprofile`        | `:line`,        | in a cell; the hook reads it and     |
|                     | `:return`       | applies it with `py/call`. Guest     |
|                     |                 | code applies a guest function        |
|                     |                 | through an ordinary row, so nothing  |
|                     |                 | on the host calls back. Needs frame  |
|                     |                 | records and a re-entrancy flag.      |
|                     |                 | Observation-only profilers and       |
|                     |                 | coverage stay telemetry stream       |
|                     |                 | observers.                           |
+---------------------+-----------------+--------------------------------------+
| Green threads       | `:loop`,        | A run queue of continuations in a    |
|                     | `:call`         | cell. The hook counts safepoints and |
|                     |                 | switches every N, capturing with the |
|                     |                 | flag-cell pattern of `py/call-ec`.   |
|                     |                 | All threads live in one task and one |
|                     |                 | heap. `py/raise` itself emits the    |
|                     |                 | `:exception` trace event.            |
+---------------------+-----------------+--------------------------------------+
| `RecursionError`    | `:call`,        | Entry increments and checks the      |
|                     | `:return`       | depth; normal exit decrements it; an |
|                     |                 | escape restores the depth saved at   |
|                     |                 | capture.                             |
+---------------------+-----------------+--------------------------------------+
```

Depth, handlers, and the current frame form one dynamic-context record
in one cell, generalizing `py.rt/handlers`. An exception can unwind many
frames, so an escape restores the record saved at capture (slice 2
keeps the current `:base`, below), as
`py/try` and `py/call-ec` already do for the handler stack; a thread
switch swaps the whole record. Without that, thread B's raise would
invoke thread A's handler. Hooks never test the continuation
representation, so mob D7 does not affect them.

Slice 2 implements this as `py.rt/ctx`, `{:handlers :depth :base
:frame}` (`:frame` stays nil until tracing). Depth measures the current
continuation: the absolute depth is `:base` plus `:depth`. `:base` is 0
outside generators. Every crossing into a generator sets its `:base` to
the resumer's absolute depth plus one for the generator's own frame. A
later resume from a shallower frame therefore rebases the generator, as
CPython counts it. Every crossing back restores the caller's record.
Escapes restore activation-relatively: the record saved at capture comes
back with the current `:base` kept, so only crossings rebase. A thread
scheduled out keeps its whole record. The stage wraps the `:return` hook
around the body of each `:call` site, applying it to the body's value,
so it runs on normal exit only. With limit n, n nested Python calls run
and the next raises `RecursionError`; the module body is not counted. A
`:finally` frame records the depth it was pushed at, and its thunk runs
at that depth. A generator start or resume is admitted only when the
generator's frame fits: the base prelude compares the resumer's absolute
depth plus one with the limit before writing anything, so a refusal
raises `RecursionError` on the caller's stack and leaves the generator
created or suspended and the caller's record unchanged. This holds even
for a body that calls nothing between yields. The base prelude owns the
dynamic-context record, the limit cell `py.rt/limit` (1000 by default)
and admission at generator crossings, because the crossing is prelude
code that no site mark reaches, and it enforces admission in every
execution mode. The hook prelude owns counting function frames. Without
recursion hooks the effective depth counts nested active generator
frames only, which CPython also bounds; the recursion profile
additionally counts ordinary Python function frames. Naive mode is
therefore not complete CPython recursion accounting. The limit lives
outside the
escape-restored record, so a valid change survives escapes. A limit at
or below the current absolute depth raises `RecursionError` and keeps
the old limit.
`sys.setrecursionlimit` is not reachable while the lowering refuses
`import`; the hook prelude exposes `py.sp/set-recursion-limit!` to the
composition.

Composition and cost:

- There are three switches: which stream the evaluator observes, which
  kinds the profile maps, and whether the hook prelude is loaded. An
  empty profile is the identity, A' = A. A derived program run without
  its hook prelude fails closed on an unresolved hook name.
- A `:loop` site with signals on costs one application plus one effect
  dispatch; `:call` and `:return` with depth accounting cost a cell get
  and set each; `:line` sites cost one cell read per statement and are
  inserted only under a trace profile. None of this has been measured.
- Safepoints are not collection points; allocation stays the only
  collection trigger. A run queue of continuations in a cell is traced
  like any cell content.
- Insertion is pure and host-independent, processing sites in sorted
  path order. Depth, tracing, and count-based switching (decision 9) are
  deterministic. Signals are the one nondeterministic input, and
  `stream/poll!` is the only place where "was it blocked" becomes
  program-visible. A signal is an operational event (section 11), not a
  deterministic language error, and slice 1 does not journal it
  (decision 8).
- Under a profile without tracing, `sys.settrace` raises an explicit
  unsupported error (decision 10).

The stage is generic over the Universal AST and the frontend-metadata
side table and lives at `yang.safepoint`, not under `yang.python`. Each
language supplies its marks, its hook prelude, and its profile map. PHP
reuses it directly: `declare(ticks=N)`, `register_tick_function`, and
`pcntl_signal` are statement-level safepoints. JavaScript uses `:loop`
and `:call` for interruption and debugging only; its jobs run to
completion, so it never switches at a safepoint. Go and Java green
threads use the same sites. The generic stage machinery now under
`yang/python/antlr/stage.cljc` moves to a language-neutral namespace
before the second user.

Slice 1 is the mechanism plus signals, end to end: the `stream/poll!`
export and `:stream/poll` arm in the shared effect handler with a
declared profile; `:loop` and `:call` marks and the source envelope in
the Python lowering; `yang.safepoint` with pure insertion, tail
re-marking, the stage over the row medium, and the derive record; and
the Python hook prelude, `py.sp/loop` and `py.sp/call` polling a signal
cursor, with `KeyboardInterrupt` under `BaseException`. Later slices, in
order: recursion (the dynamic-context record, the `:return` wrap,
`RecursionError`), tracing (frame records, `:line` marks), threads.

Slice 1 acceptance runs on all four VMs:

- Transparency: the end-to-end corpus through the stage with no-op hooks
  gives the naive output.
- Identity: an empty profile yields the same root and rows.
- Canonical untouched: the input batch is unchanged, every derived row
  validates, and prelude rows keep their ids.
- Insertion determinism: the same input gives the same A' and record
  address on CLJ, CLJS, and CLJD.
- Interrupt: `while True: pass` with one pre-appended signal ends with
  `KeyboardInterrupt`; wrapped in `try/except KeyboardInterrupt`, it
  prints from the handler.
- No park: with an empty signal stream the derived program finishes
  without blocking, with the naive output.
- Tail preservation: a 100,000-iteration safepointed loop grows no
  continuation on the VMs that honor tail marks.
- Atomicity: no hook application appears under any prelude definition.
- Fail closed: the derived program without the hook prelude reports the
  unresolved hook name.
- `stream/poll!`: on an empty stream it returns `:dao.stream/blocked`
  without parking; `ok` advances the cursor; a callee without the
  declared effect is refused.

Open, outside slice 1 (stated by the design): a task parked on a
blocking read cannot receive a signal, and several threads waiting on
different streams need an any-of park.

#### 8.5.3 Generators (phase C2)

This section records the C2 generator design as amended by the C2
cross-ruling's nine converged rulings. Slices S1 to S4 below have landed
(S1 `7654c2d0`, S2 `c3f2da8f`, S3 `a4efc99a`, S4 generator expressions);
S5 is pending.

A generator is one heap cell holding a suspended continuation plus its
own handler stack. `yield` and resume are two explicit continuation
invocations that swap control and handler stack together. Everything is
prelude code plus lowering arms: no VM change, no new AST node, no wire
or ledger change.

The load-bearing decision is that a generator owns its handler stack,
with a boundary frame at its base (ruling 1). C1's `py/try`,
`py/try-finally`, and `py/call-ec` restore absolute snapshots of
`py.rt/handlers`; were generator frames on the caller's stack, a resume
from a different caller depth would restore a stale stack. With a
per-generator stack every snapshot taken in a generator body is of that
generator's own stack, so the C1 machinery works unchanged. Every
crossing restores the receiving context before it delivers a value or
raises, and a validation failure leaves the generator unchanged.

The generator cell (identity is the ref, section 8.11) holds:

```text
+--------------------------+--------------+------------------------------------+
| Key                      | Present when | Content                            |
+==========================+==============+====================================+
| `:py/type` `:generator`, | always       | Tag and function name              |
| `:name`                  |              |                                    |
+--------------------------+--------------+------------------------------------+
| `:state`                 | always       | `:created`, `:suspended`,          |
|                          |              | `:running`, or `:closed`           |
+--------------------------+--------------+------------------------------------+
| `:body`                  | `:created`   | Closure `(fn [%gen] ...)`          |
+--------------------------+--------------+------------------------------------+
| `:resume`                | `:suspended` | The continuation captured at the   |
|                          |              | yield                              |
+--------------------------+--------------+------------------------------------+
| `:ctx`                   | `:suspended` | The generator's own handler stack  |
+--------------------------+--------------+------------------------------------+
| `:return`, `:caller-ctx` | `:running`   | The active resume call's           |
|                          |              | continuation and the caller's      |
|                          |              | handler stack                      |
+--------------------------+--------------+------------------------------------+
```

- Parameters and locals are cells allocated when the generator function
  is called; the body does not run until the first resume.
- Slots are cleared on every transition, so a suspended generator does
  not hold its last caller in a named slot and a closed one holds
  nothing.
- The two crossings are `py/gen-switch` on the caller side and
  `py/yield-raw` on the generator side, each an explicit invoke of a
  continuation stored in the cell. A flag cell allocated before each
  capture tells the two passes apart (ruling 2), the pattern C1 already
  uses; each captured continuation is invoked once, and the prelude
  never inspects the continuation representation. `py/gen-start`
  installs a fresh stack whose only frame is the boundary.
- The boundary frame needs no change to `py/raise` or `py/unwind-to`,
  which already pass the exception to any non-`:finally` frame's
  payload. `py/gen-fail` applies PEP 479 and exits; the caller side
  re-raises in its own control and handler context.
- `py/gen-exit` is the single exit of a finishing generator: it marks
  the cell `:closed`, restores `:caller-ctx`, and invokes `:return`.
- No escape crosses the boundary, since a function value resets its
  `:loop` and `:ret` escapes; only exceptions and yields cross, both
  explicitly. Guest locals are cells and generated temporaries are never
  reassigned, so environment rewind is not a risk. Already-evaluated
  operands, as in `f(a(), (yield x), b())`, ride in the captured operand
  frame, which is why continuations are preferred to a state-machine
  transform.

Outcomes are tagged and translated only at protocol boundaries (ruling
3). `py/gen-switch g msg` takes `[:send v]` or `[:throw e]` and answers
`[:yield v]`, `[:return v]`, or `[:raise e]`; it never raises from the
generator's side, and loops consume the outcome directly with no
exception per item.

- `next`, `send`, and `throw` turn `[:return v]` into
  `raise StopIteration(v)`; `next(g, default)` consumes completion
  directly. `py/iter-at` gains a `:generator` arm that maps
  `[:return _]` to `:py/stop`, so the `for` lowering and its goldens do
  not change. A `:closed` generator answers `[:return None]` forever.
- A `StopIteration` escaping the body becomes
  `RuntimeError("generator raised StopIteration")` (PEP 479). A
  delegate's termination is consumed by `yield from` first, preserving
  `.value`.
- `send` of a non-None value to a `:created` generator raises
  `TypeError` in the caller before any switch. `throw` raises at the
  yield site on the generator's stack, so an enclosing `try` in the
  generator catches it; on a `:created` generator it closes it and
  raises in the caller.
- `close` throws `GeneratorExit`, a new class under `BaseException`.
  `[:return _]` or a raised `GeneratorExit` gives None; any other raise
  propagates. If the generator yields instead, `close` raises
  `RuntimeError` and the generator keeps its resulting suspended state;
  it is not marked closed.
- `finally` and `with` around a yield need no change: their frames are
  saved in `:ctx` at suspend and reinstalled at resume, and a thrown
  exception, `GeneratorExit`, or `return` unwinds them through the
  existing code. A consumer's `break` unwinds only the caller's stack.
- `StopIteration` is a new builtin class under `Exception` whose
  `__init__` sets `args` and `value`. The guest surface adds `next` and
  `iter`, and `__next__`, `__iter__`, `send`, `throw`, and `close`
  through a `:generator` arm in `py/getattr`.
- `py/yield-from` is the PEP 380 loop as a tail-recursive prelude
  function over `py/yield-raw`. A non-generator delegate goes through a
  stateful sequence iterator, the object `iter()` returns: a cell
  `{:py/type :iterator :src it :i n}` over `py/iter-at`.

Generator expressions lower to anonymous generators (ruling 4). The
outermost iterable is evaluated and `iter()`-checked at creation in the
enclosing scope; every other clause is lazy. The C1 inlining of a
generator expression consumed by `sum`, `any`, or `all` is removed: it
was a placeholder, it deviates under PEP 479, and eager materialization
can run expressions a lazy consumer never reaches. Any later
optimization belongs to an attached optimizer and must preserve these
observations and runtime rebinding of the consumer. List, set, and dict
comprehensions stay eager. `yield` at module or class level, or inside
any comprehension or generator expression, is a syntax diagnostic.

`py/iter-at` keeps its index signature as an internal compatibility
interface, not the public iterator protocol (ruling 5). A stateful
iterator advances once per call and ignores the index; iterable
acquisition happens once per loop; `iter(iterator)` preserves identity;
exhaustion handling covers only the advancement, never the loop body.
The loop counter must not overflow or round under long consumption: it
uses exact increment once C3 lands, without narrowing generator
payloads.

User-defined iterator classes are part of C2, in S5 (ruling 8).
Implicit `__iter__` and `__next__` lookup uses the type's special-method
path, since ordinary `py/getattr` prefers instance attributes; it
validates the returned iterator and catches `StopIteration` only around
advancement. Termination values are preserved for delegation, other
exceptions propagate, and optional `send`, `throw`, and `close` are
forwarded per PEP 380.

No generator flag is persisted on function specs (ruling 9). Generator
status is decided during scope-aware lowering, excluding nested function
bodies and including unreachable `yield` and `yield from`, and is
encoded in ordinary code. Future introspection, such as
`inspect.isgeneratorfunction`, derives status from the scoped
generator-construction pattern; a query for direct applications of
`py/yield` alone is insufficient, because a body using only `yield from`
need not apply it.

Heap, identity, and wire:

- Tracing needs no VM change: cell content is traced as data and a
  continuation is walked through its payload.
- An unreachable generator is reclaimed without an implicit `close()`
  (ruling 6). Python specifies a `close()` on generator finalization, so
  this is an explicit finalization restriction of the support profile,
  not conformance. The landed collector has no finalizers; explicit
  `close()` stays supported. Pinning roots the generator cell and traces
  its current content, not every historical continuation.
- `%capture` is undelimited, so `:resume` holds the frames of the first
  `next()` call beneath the generator's own (stale base). This fixed
  retention is accepted for C2; growth per yield is rejected (ruling 7).
  Delimited capture is a separate VM question.
- A suspended generator does not migrate: cells refuse lift and
  continuations refuse as `:non-canonicalizable`. Once heap lift and a
  continuation encoding land, generators migrate with no
  generator-specific wire form; until then the refusal stays.
- `is` is `=` on the ref, `iter(g) is g`, and a generator is a dict key
  by ref through the non-numeric arm of `py/key`. `py/snapshot-obj`
  renders `{:py/generator name}`, diagnostic data, never a resumable
  wire form. The prelude compares only `:state` keywords, never
  generator contents, since continuation equality and hash are
  structural.
- The canonical tree changes only by new prelude functions: `yield` is
  an `:application` of `py/yield`, and `%capture` stays inside the
  prelude. The prelude grows, so every bundled unit's address changes,
  a prelude-profile bump under section 11.

For safepoints, generator switches happen only at applications of
`py/gen-switch`, `py/yield-raw`, and `py/yield-from`, which an attached
inserter can find by query; `sys.settrace` sees nothing in C2. Future
per-thread dynamic state (the frame-record stack, the recursion counter,
`exc_info`) joins `:ctx` and swaps with the handler stack.

```text
+--------+---------------------------------------------------------------------+
| Slice  | Scope                                                               |
+========+=====================================================================+
| S1     | Core: `py/make-generator`, `py/gen-switch`, `py/yield`,             |
|        | `py/gen-exit`, `py/gen-fail`; `StopIteration`; `next`; the          |
|        | `iter-at` and `iterable` arms; lowering arms for `yield_stmt` and   |
|        | `yield_expr`, a `:gen` binder reset in every nested scope, and      |
|        | removal of the three `yield` guards.                                |
+--------+---------------------------------------------------------------------+
| S2     | `send`, `throw`, `close`, and the dynamic context: `GeneratorExit`, |
|        | `finally` and `with` around a yield, the "generator already         |
|        | executing" check, PEP 479.                                          |
+--------+---------------------------------------------------------------------+
| S3     | `yield from`, `iter`, and stateful sequence iterators.              |
+--------+---------------------------------------------------------------------+
| S4     | Generator expressions, with removal of the C1 consuming-builtin     |
|        | inlining. Landed.                                                   |
+--------+---------------------------------------------------------------------+
| S5     | Heap, wire, and hosts: collection of suspended and dropped          |
|        | generators, lift refusal, determinism, snapshot rendering;          |
|        | user-defined iterator classes.                                      |
+--------+---------------------------------------------------------------------+
```

The cross-ruling strengthens acceptance:

- Every slice runs on all four evaluators on JVM, Node, and Dart;
  "wherever the parity lane runs" does not support a portability claim.
  Expected output for source programs is CPython 3.9.6's.
- Ruling 1: nested generators, different resuming callers, and
  suspension during exception unwinding.
- Ruling 2: flags become collectible when unreachable, not necessarily
  right after re-entry, so reachable heap is measured after repeated
  suspension and collection.
- Ruling 4: short-circuiting and side effects, not only PEP 479.
- Ruling 7: the complete reachable continuation and heap graph across
  many yields, changing callers, nested delegation, and forced
  collections; top-level continuation length alone is insufficient, and
  clearing named slots does not prove that references in captured
  environments vanished.
- Ruling 8: shared iterators, invalid `__iter__` results, subclassed
  `StopIteration`, and exceptions from loop bodies.

Deferred: close-on-collection, async generators, migration of suspended
generators, `settrace` events, `gi_*` introspection, and the
three-argument `throw`.

#### 8.5.4 Integers (phase C3)

This section records the C3 integer design as amended by the C3
cross-ruling's fourteen converged rulings. Of its slices S0 to S7 below,
S1 (the exact-integer module, `54536317`) has landed, and so has the
numeric-key, `hash()` and `is` work of rulings 6 to 8 (`be1f8d06`,
orchestrated as C3-S2, the S5 row's scope), with its S2b follow-up
(content `is` and hex keys), as have S3, S4 (conversions and the
eleven conversion builtins), S6 (heap and portability) and S7 (the
integration gate, profile admission and the sequence-size limit). C1
bounded integer arithmetic at +/-2^53; S3-A replaces that bound with
exact promotion.

Python integers are untagged exact scalars of any magnitude (ruling 1).
A bignum is an immutable value, possibly the payload of an existing
cell; there is no per-integer cell, no `:py/bigint` wrapper, no digit
table, and no intern table. Bignums are immutable leaves: they hold no
cell references and need no `gc-children` expansion, and a numeric
result crossing a host boundary allocates no task cell. Aliasing is
ordinary: after `x = 10**100; y = x; x += 1`, `y` keeps the old value.

Each value has exactly one carrier per host (ruling 2):

```text
+------------+----------------------------------+------------------------------+
| Host       | Native carrier                   | Bignum carrier               |
+============+==================================+==============================+
| JVM        | Signed `long`, [-2^63, 2^63-1]   | `clojure.lang.BigInt`; a raw |
|            |                                  | `BigInteger` never leaves    |
|            |                                  | the integer module           |
+------------+----------------------------------+------------------------------+
| JavaScript | `Number` only within [-(2^53-1), | Native `BigInt`              |
|            | 2^53-1], so 2^53 and -2^53 are   |                              |
|            | bignums                          |                              |
+------------+----------------------------------+------------------------------+
| Dart VM    | Signed `int`, [-2^63, 2^63-1]    | `BigInt`                     |
+------------+----------------------------------+------------------------------+
| Dart to JS | Not claimed                      | Not claimed                  |
+------------+----------------------------------+------------------------------+
```

Promotion happens before an operation could overflow, wrap, or round;
demotion after it is mandatory, not optional. On ClojureScript
`(= 1 (js/BigInt 1))` is false and on Dart `int` and `BigInt` are
unequal, so an undemoted result would split equality, `is`, and every
prelude test against a native literal. The carrier must not affect
Python type, equality, truthiness, rendering, hash, code addresses,
serialized bytes, task-cell allocation counts, guest exceptions, or
effect traces.

Values use Jing's existing CBOR forms unchanged, with no new payload
kind and no AST tag (ruling 3):

```text
+----------------------+-------------------------------------------------------+
| Integer n            | Canonical CBOR (existing Jing `int-wire`)             |
+======================+=======================================================+
| 0 <= n < 2^64        | Major type 0, shortest argument width                 |
+----------------------+-------------------------------------------------------+
| -2^64 <= n < 0       | Major type 1, argument -1-n, shortest width           |
+----------------------+-------------------------------------------------------+
| n >= 2^64            | Tag 2 over a byte string holding n                    |
+----------------------+-------------------------------------------------------+
| n < -2^64            | Tag 3 over a byte string holding -1-n, not abs(n)     |
+----------------------+-------------------------------------------------------+
```

- Digits are unsigned base-256, most significant byte first, minimal
  length. A bignum tag is noncanonical when the value fits major type 0
  or 1. Ingress refuses leading zeros, unnecessary tags, malformed
  payloads, and trailing bytes; existing Jing fixtures stay unchanged.
- A source literal within +/-(2^53-1) stays a native `:literal`. A
  larger literal lowers to an application of the integer module's parse
  function over its canonical radix-16 string, because the de Bruijn
  canonical value table declares `:bigint` out of domain and the image
  encoder refuses a JS or Dart bigint. Every literal thus stays inside
  the existing domain on all four kernels, and every spelling of one
  value (decimal, hexadecimal, binary, octal) has one address.
  Widening the de Bruijn value domain is a separate kernel ruling.
  `lower/open-stage` takes an explicit composition digit budget that
  fills only a packet declaring no `:yang.python.antlr/max-digits` of
  its own (the packet's declaration wins); with neither, a decimal
  literal above 2^53-1 is a diagnostic, and hexadecimal, octal and
  binary literals are exempt from the budget.
- `42` and `42.0` stay distinct content although numerically equal.

Exact-integer carriers are recognized as scalars in the encoder, the
heap trace, `pin-refs`, `values/kind-of`, `data/number?`, and
`vm/machine-data?` (ruling 4). On JS and Dart a bignum fails host
`number?`. UCF gains no marker: its version-1 numeric arm and handoff
decoder carry Jing exact-integer content unchanged. Four-kernel image
and Python heap tests exercise these boundaries on all three hosts.
The cell-lift refusal is unchanged, so C3 does not establish Python-task
migration.

A separately installed, versioned `:pure` integer module carries the
exact kernels (ruling 5). It follows `yin.vm.data` (section 9.3): the
composition installs it explicitly, the Python runtime profile requires
it, and a composition without it is refused at admission. Admission is
`prelude/admit` (S7): it checks every `prelude/host-names` symbol
against the registry and refuses, before any program runs, with
`:yang.python.antlr/refusal :yang.python.antlr/host-names` and the
missing names in `:yang.python.antlr/missing`, so a version-3 `integer`
module is refused naming `integer/decimal->float`,
`integer/float-digits` and `integer/max-digits`, and a `data` module
without limits naming `data/max-items`. Every Python composition calls
it after its registrars; linking replaces it with requirement discovery
(section 8.5.6), refusing by the same names. An image carries no
profile: a version-1 lift of a halted 2^100 under the wide composition
resumes under `small` with the value intact, and what that value then
does under `small` is the S0 `small` column. Profile identity in a
checkpoint record is L-a's to add. It declares
arities, raises no effects, holds no host state, never calls back into
Python, and never touches the heap or store; expected arithmetic
failures are returned as qualified data for the prelude to translate.
`vm/primitives`, the grammar, opcodes, and evaluator dispatch do not
change. Python dispatch, sign rules, and exceptions stay in the
prelude:

```text
+--------------------------------------+---------------------------------------+
| Python prelude                       | Pure integer module                   |
+======================================+=======================================+
| Python type dispatch and bool        | Exact integer recognition and         |
| coercion                             | normalization                         |
+--------------------------------------+---------------------------------------+
| Guest exception construction         | Exact add, subtract, multiply,        |
|                                      | negate, compare                       |
+--------------------------------------+---------------------------------------+
| Floor quotient and modulo sign       | Truncating quotient and remainder     |
| adjustment                           | pair                                  |
+--------------------------------------+---------------------------------------+
| Operator result-type selection       | Unbounded signed bit operations       |
+--------------------------------------+---------------------------------------+
| Integer power loop and special cases | Checked shifts and bit length         |
+--------------------------------------+---------------------------------------+
| Parsing syntax, bases, underscores,  | Exact digit accumulation and radix    |
| whitespace                           | formatting                            |
+--------------------------------------+---------------------------------------+
| Numeric key and guest hash policy    | Exact binary64 decomposition and      |
|                                      | conversion; correctly rounded integer |
|                                      | ratio to binary64                     |
+--------------------------------------+---------------------------------------+
| Float text syntax, sign, whitespace, | Shortest round-trip digits of a       |
| notation                             | binary64; decimal digits and exponent |
|                                      | to binary64, rounded once             |
+--------------------------------------+---------------------------------------+
```

The module is at version 4 (`yin.vm.integer/module-version`): version 2
returns limit reasons as data, version 3 adds the float conversions, and
version 4 adds `float-digits`, `decimal->float` and `max-digits`, every
version 3 semantic unchanged. The boundary renderer's float `repr`
formats `float-digits` by CPython's rule and no longer uses host text.

- Guest integers go only through the module, since host `+` on mixed
  carriers throws on JS and Dart. Internal counters, including C2's
  `iter-at` index and the sequence iterator's `:i`, stay VM primitives
  and never become guest values without normalization.
- The conversion and rounding kernels are written once in portable code
  over a minimal per-host bignum shim, not three host implementations.
  The shim does not reach into `dao.jing.cbor` privates; Jing stays a
  passive codec.
- Floor division and modulo derive from the truncating pair: if the
  remainder is nonzero and its sign differs from the divisor's, the
  quotient decrements and the divisor is added to the remainder. Zero
  division is checked before the primitive is invoked.
- Bit operations follow an infinite signed two's-complement model:
  `~a = -a-1`, `a << k = a * 2^k`, `a >> k = floor(a / 2^k)`. A negative
  count raises `ValueError`; counts are never narrowed before that
  check; a huge right shift returns 0 or -1 from the sign, and a huge
  left shift of a nonzero operand passes result-size admission first.
- Non-negative integer powers are exact, by squaring through exact
  primitives (ruling 10). Float-result powers use the prelude's existing
  squaring loop, `py/fpow`, as the pinned contract: bit-identical across
  hosts and allowed to differ from CPython's `pow` in the last place,
  documented. Fractional exponents, complex results, and three-argument
  `pow` are deferred. As amended in S4 (items a to d):
  - The result is a float when either operand is a float or the
    exponent is negative. Then, as CPython's `float_pow`, the base and
    then the exponent convert through the checked conversion before the
    zero-base check, so a huge base, or an exponent at or past
    2^1024 - 2^970, raises `OverflowError` ("int too large to convert to
    float"), and `0 ** -(2**1024)` is that error, not
    `ZeroDivisionError`. The power is of the exponent as a double:
    `(-1.0) ** (2**64 + 1)` is `1.0`, since 2^64 + 1 rounds to an even
    double.
  - An integral float exponent of any size goes through
    `integer/from-float`, never `py/floor`. A NaN or infinite exponent
    follows CPython's table: `1.0 ** nan` is `1.0`, `x ** nan` is NaN,
    and `x ** inf` goes by `|x|` against 1.
  - A negative exponent computes `1 / fpow(x, n)` while `fpow` is
    finite, so every existing exact case holds (`10 ** -2` is `0.01`).
    Only when it overflows does it use the reciprocals of the two halves,
    `n >> 1` and the rest, so `2 ** -1074` is `5e-324` and `2 ** -1075`
    is `0.0`.
  - A finite base and exponent with an infinite result raise
    `OverflowError` with args `(34, 'Result too large')`, the macOS errno
    pair CPython 3.9.6 gave when measured; an infinite base passes
    through.
- Float division by zero raises `ZeroDivisionError` with CPython's
  per-operator text (S4, item f): "float division by zero",
  "float floor division by zero", "float modulo", "float divmod()".
  Integer messages are unchanged.

Conversions are acceptance conditions (ruling 9):

- Integer text is parsed and formatted exactly, never through a double
  or a native-width parser. Source-literal syntax and
  `int(string, base)` validation are separate rules. `str` and `repr`
  give exact decimal text with no suffix or exponent, and the guest
  builtins and the boundary snapshot renderer share that contract.
- `int(finite_float)` truncates the binary64 value exactly; infinity
  raises `OverflowError` and NaN raises `ValueError`.
- `float(integer)` rounds once, to nearest with ties to even, and raises
  `OverflowError` outside the finite range.
- Integer `/` is correctly rounded from the exact ratio, so
  `(10**400) / (10**400)` is `1.0`.
- Mixed arithmetic converts the integer through the checked conversion
  and keeps the tagged float result; C1 float tags and signed-zero
  behavior are preserved. Comparison between an integer and a finite
  float is exact over the float's binary rational, never by rounding the
  integer.
- `int`, `float`, `str`, `repr`, `divmod`, and `hash` do not exist in
  C1; each is a named C3 deliverable. S4 adds eleven builtin function
  objects, `int float str repr bool abs pow hex oct bin round`, beside
  `len`, `divmod` and the others; there are no builtin type classes, so
  `isinstance(x, int)` stays unsupported. `min`, `max`, `format`, and
  `round(float, n)` are later.
- `int(str, base)` and `float(str)` strip `str.isspace` whitespace less
  code points 28 to 31, which CPython 3.9.6 keeps (measured). Syntax is
  validated in the prelude before any kernel call: one sign, a prefix
  matching the base (any prefix for base 0, where a nonzero value may
  not start with `0`), single underscores between digits or after a
  prefix. Digits are ASCII only, a recorded departure: CPython accepts
  other Unicode decimal digits. `float(str)` reads `inf`, `infinity` and
  `nan` in any case, and otherwise rounds the whole decimal once through
  `integer/decimal->float`, with no digit limit. An error quotes the
  unstripped text through `py/str-repr`, except that `float()` of
  whitespace alone quotes `''`, as CPython does.
- `str` and `repr` cover scalars only: an int is exact decimal under the
  digit limit, a float is the renderer's `repr` rule over
  `integer/float-digits`, `True`, `False` and `None` are their names. A
  container, function, class, instance or range raises
  `NotImplementedError`; container text needs builtin type objects.
  `py/str-repr` escapes as `render/string-repr` does, which is only the
  ASCII controls: a non-ASCII non-printable character such as U+200B
  is shown raw where CPython writes `\u200b` (a recorded departure).
  `hex`, `oct` and `bin` format the magnitude in base 16, 8 or 2 with no
  digit limit. `round(x)` of a float is exact half to even; `round(n,
  k)` of an int with negative `k` is half to even through exact powers.
  As in CPython 3.9.6, `round` first finds `__round__` on the number,
  so `round('a', 1.5)` is "type str doesn't define __round__ method",
  and then converts `ndigits`, so `round(1.5, 'x')` is the index
  `TypeError` before the `round(float, n)` deferral (S7). `pow` and
  `round` take keywords (`pow(base=2, exp=10)`, `round(number=x,
  ndigits=k)`), as CPython 3.9.6's do; `int(x=...)` is the recorded
  divergence. The int-conv-v1 rows measure both (S7).
- The digit-limit `ValueError` reads "Exceeds the limit (N digits) for
  integer string conversion", N from `integer/max-digits`, for `str`,
  `repr`, `print` and `int(str)` alike. `print` checks every integer it
  will show, in the shapes `py/snapshot` walks, before anything is
  appended to the output, so a breach is catchable and prints nothing.
- `x in range(...)` for a float answers in constant time: only an
  integral finite float can be an element (S4, item i).

Python numeric equality, dict-key normalization, and canonical storage
identity stay three distinct contracts. Numeric dict and set keys are
reduced-rational keys in one form (ruling 6), `[:py.numeric/finite
numerator-hex denominator-hex]`, replacing C1's double normalization.
Both components are lowercase hex through `(integer/format n 16)`, with
no prefix and no leading zeros, `-` only on a negative numerator, and
`"0"` for zero; the denominator is positive, `"1"` for integers. A
power-of-two radix is exempt from `::max-digits`.

- `True`, `1`, and `1.0` share the key 1/1; `False`, `0`, and `+/-0.0`
  share 0/1; `1.5` is 3/2; 2^53 and 2^53+1 stay distinct.
- Infinities get their own signed keys. Every NaN shares one key,
  `[:py.numeric/nan]` (pinned in C3-S2). A float here has no object
  identity to tell two NaNs apart, and Jing float64 content already makes
  every NaN one value, so a per-object NaN key cannot survive addressing;
  keying by the host double is not deterministic either (on the JVM host
  `=` answers true for one boxed NaN and false for two). This departs from
  CPython, where distinct NaN objects are distinct keys. For dict/set
  key normalization, all NaNs belong to one equivalence class,
  recursively inside tuple keys; this does not change numeric comparison
  semantics. Reinsertion retains the first stored key and updates its
  value. In Python terms: `d[x]` after `d[x] = 1` works as in CPython;
  `d[float('nan')]` returns that entry where CPython raises `KeyError`;
  and `len({nan, nan2})` is 1 where CPython gives 2. This is the
  float-identity departure, the same family as ruling 8's value-based
  `is`, and a tuple containing a NaN inherits it through `:py/tuple-key`.
  Container membership and equality (`in`, `==` on lists and tuples)
  take CPython's identity-then-`==` step through `py/same?` (S4, item
  h), so `x in [x]` and `[x] == [x]` are true for a NaN, as in CPython;
  under content identity two separately made NaNs match too, where
  CPython says false. `float('-nan')` is this one NaN as well, where
  CPython keeps the sign bit (fff8000000000000): no portable host
  operation sets a NaN's sign, an arithmetic NaN takes the CPU's
  default sign, Jing writes every NaN as 7ff8, and no C3 guest operation
  can observe the sign. The prelude builds NaNs as `inf - inf`, never
  from a `##NaN` literal, whose row is not equal to itself on JS.
  Jing's private `exact-key` is a storage-layer helper, not the guest key
  contract, and is not reused.
- Tuple elements normalize recursively under the existing tuple-key
  convention; the non-numeric arm (generators and other identity
  objects by ref) and the unhashable arm are preserved. The
  insertion-order vector keeps the first inserted original key.
- Known limits. Keys are hex, so the digit limit never applies to
  keying, as CPython applies it to `str()` and not to hashing or keying:
  an integer past `::max-digits` is a valid dict or set key. The
  smallest subnormal keys with a 1075-bit denominator, so a
  composition's `integer` limits must admit at least 1075 bits for float
  keys. Under a smaller bit limit the key is refused, never
  approximated: the refusal must surface as the documented guest
  failure (ruling 11: `MemoryError`). Since S3a the prelude translates
  it through the single translator `py/int-result`: the breach raises a
  catchable `MemoryError`, and a failed key normalization leaves the
  container unchanged.

Guest numeric `hash()` uses P = 2^61-1 on every host (ruling 7):
`h(n) = sign(n) * (abs(n) mod P)`, with -1 replaced by -2. A finite
float hashes its reduced rational with the same modulus and the modular
inverse of the denominator; a bool hashes as 0 or 1. Host hash, Jing's
`num-hash`, content digests, and cell ids are never exposed. Dicts do
not consume `hash()`; they use the keys above. `hash()` of an identity
object (generator, instance, function) is unsupported in C3, since the
only available identity is the cell id, which is not exposed and not
stable across lift.

`is` on non-cell values is content identity: `py/is` is
`data/content=`, Jing's kind-strict content equality, so two values are
`is`-equal exactly when content addressing gives them one identity, on
every host (ruling 8, section 8.11 "same type and value"). Integers are
`is`-equal at any magnitude regardless of carrier; `True is 1` and
`1 is 1.0` are false. For floats the value is the binary64 content:
`0.0 is -0.0` is false, as in CPython, and every NaN is one value, so
`float('nan') is float('nan')` is true where CPython answers false. This
is the float-identity departure, the same family as the one-NaN key.
Host `=` is not used: it merges signed zeros on the JVM and splits NaNs
by box. `==` is unaffected. No CPython allocation or interning fidelity
is promised.

Numeric limits are explicit Python-profile data in bits and digits, with
no implicit default and nothing inherited from environment variables or
host library defaults (ruling 11). A bit-length breach is a guest
`MemoryError` and a digit-limit breach is a guest `ValueError`, both
catchable and deterministic; neither is `OverflowError`. Real host
exhaustion, timeout, and cancellation stay operational (section 11).
Calling a large synchronous kernel `:pure` does not make it
interruptible, so C3 makes no mid-primitive safepoint or latency claim.

The sequence-size limit is the same kind of datum (S7, item j): the
`data` module's `:yin.vm.data/max-items`, a positive native integer, the
largest item or character count one repetition may produce, exported
as `data/max-items` only by the two-arity `register-data-module`; there
is no default, and the one-arity registration, which exports no
`max-items`, is refused by `admit`. Python test compositions use
1048576. `py/repeat` (`*` and `*=` on str, list and tuple) keeps
CPython's order: a non-int count is `TypeError`, a count outside
Py_ssize_t `OverflowError` ("cannot fit 'int' into an index-sized
integer"), a negative count is 0; then the exact size `len * count`,
through `integer/mul`, is compared with `data/max-items`, and a larger
one is `MemoryError` with empty args before anything is built (a size
past the bit limit is the same `MemoryError`). Only repetition is
checked: `list(range(n))`, appends and comprehensions grow
incrementally, are interruptible at safepoints, and stay operational.
Three divergences are accepted: a left shift past allocation scale is
`MemoryError` where CPython raises `OverflowError` or fails to allocate
(the profile's bit limit is the allocation model); `float('-nan')` is
the one NaN, its sign unset (revisited only with a bits-to-float `data`
export); and CPython's Unicode printable escapes in `repr` are later,
beside ruling 13's unclaimed `format`, `%` and `round(x, n)`.

The stream codec is not widened and C3 builds no new adapter (ruling
12). A raw remote put of a bignum refuses with a qualified outcome;
canonical Jing bytes through the existing `dao.jing.stream` adapter are
the only remote form, and printing and re-reading EDN is not a bignum
transport. Version-0 handoff of a bignum returns `:yin.k/non-portable`
of kind `:host-object` on every host. The version-1 scalar body bytes
and address are pinned in `yin/vm/ucf/scalars-v1.txt`, minted once on
the JVM and verified on Node and Dart. CPython NaN object-identity
fidelity remains unsupported;
deterministic NaN key behavior is now specified. Float text parity is
claimed for `repr`, `str` and `print` of every double, as amended in S4
(ruling 13): the guest `py/float-repr` and the boundary renderer's
`float-repr` are one rule, bound by a parity law over the CPython
`float-text-v1` rows on every host. `format`, `%` formatting and
`round(x, n)` remain unclaimed.

```text
+--------+---------------------------------------------------------------------+
| Slice  | Scope                                                               |
+========+=====================================================================+
| S0     | Freeze contracts: profile, expected bytes, literals, hashes,        |
|        | outcomes, and boundary values around 2^53, 2^63, and 2^64. No       |
|        | existing canonical fixture changes. Landed.                         |
+--------+---------------------------------------------------------------------+
| S1     | Exact carriers and the pure module, installed explicitly; a         |
|        | registry without it refuses.                                        |
+--------+---------------------------------------------------------------------+
| S2     | Literal and boundary integration: equal spellings give equal rows,  |
|        | bytes, and hashes; malformed encodings refuse. Landed.              |
+--------+---------------------------------------------------------------------+
| S3a    | Limit reasons as guest exceptions: `integer` version 2 returns      |
|        | them, and `py/int-result` raises MemoryError or ValueError.         |
|        | Landed.                                                             |
+--------+---------------------------------------------------------------------+
| S3     | Integer operators, including augmented forms, through the prelude.  |
|        | Landed.                                                             |
+--------+---------------------------------------------------------------------+
| M4     | `integer` version 4: `float-digits`, `decimal->float`,              |
|        | `max-digits`; the renderer's float `repr` without host text.        |
|        | Landed.                                                             |
+--------+---------------------------------------------------------------------+
| S4     | Conversions: `int float str repr bool abs pow hex oct bin round`,   |
|        | float text both ways rounded once, the digit-limit message and      |
|        | `print` check, power items a to d, float zero-division messages,    |
|        | NaN membership, float in range. CPython fixture `int-conv-v1`.      |
|        | Landed.                                                             |
+--------+---------------------------------------------------------------------+
| S5     | Numeric dict and set keys and guest hashes. Landed, with its S2b    |
|        | follow-up: content `is` and hex keys.                               |
+--------+---------------------------------------------------------------------+
| S6     | Heap and portability: collection, pinning, scalar UCF round trips,  |
|        | cell-lift and raw-transport refusals, version-0 refusal. Landed.    |
+--------+---------------------------------------------------------------------+
| S7     | Integration gate over the full C1 and C3 corpus, profile mismatch,  |
|        | and resource-limit fixtures: corpus `c3-corpus-v1`, `admit`,        |
|        | `data/max-items`, the `round` argument order. Landed.               |
+--------+---------------------------------------------------------------------+
```

C3 is complete only with (ruling 14):

- All four VMs on JVM, Node, and Dart VM, twelve lanes; Dart compiled to
  JavaScript is not claimed. Source-level tests run on the JVM and
  parserless forms on Node and Dart.
- Golden byte fixtures checked on each host, in place of directed
  host-pair runs, and unchanged Jing fixtures.
- The full C1 corpus.
- Generated operands from a checked-in table with CPython-computed
  expectations, not a host RNG. The corpus covers the boundaries around
  2^53, 2^63, and 2^64, promotion followed by cancellation, the
  `divmod`, shift, power, conversion, key, and `hash(-1)` detectors, and
  the C1 signed-zero cases.
- Mutation evidence, each mutation shown failing its detector once and
  recorded in the engineer's report: disabled promotion, skipped
  demotion, double-coerced keys, omitted floor adjustment, `abs(n)` for
  tag 3, host `number?` as the only scalar gate, and a prematurely
  narrowed shift count.

The detectors are source programs (S7): `c3_programs.cljc` holds ten,
one per theme (promotion, demotion, keys, divmod, shifts, power,
conversions, signed zero, and the `wide` and `small` limits), as CST
packets the JVM parser makes, and `c3-corpus-v1.txt` their stdout,
CPython 3.9.6's from `c3-corpus-v1.generate.py` except the two limit
programs, which are labelled hand pins. `c3_gate_test` runs every packet
on the four VMs on every host; `c3_gate_parser_test` binds each packet
to the parser and its docstring to the corpus source, and runs every
source naive and under no-op hooks. The mutation evidence is in the S7
engineer's sign-off pack (`collab/1791379500000-engineer-s7-signoff-pack.md`,
archived with the slice); S6's report holds the scalar-gate and decoder
mutations (with codec-level tag-3 detection verified by S6 T4 `scalar-round-trip-test`
and the int-contract recompute and round-trip tests).

The module slices S0 and S1 may run alongside C2; the prelude and
lowering slices land after C2 and audit its arithmetic sites. The
contracts strengthen laws 3 to 5, 7 to 9, 11, and 12 of section 13.1.

#### 8.5.5 Float addresses

This section records the float-address ruling and the converged sign-off
rulings that followed it. The float-fix slice implements them, as the one
preparatory slice that lands before any float-bearing cross-host pin;
"Implementation" below records what it does.

The codec conforms; the producers are defective. On JavaScript,
`dao.jing.cbor` classifies an integral Number other than negative zero
as an integer, exactly as `dao.jing.cbor.md` (*Numeric identity*)
requires: JavaScript callers must use the float64 carrier for integral
floats. The Python producers lose the float kind before encoding:

- Source literals. Lowering wraps a bare host number as
  `{:py/float v}`. The wrapper carries Python type semantics only; Jing
  classifies the nested number by host, so `2.0` is integer 2 on Node
  and float64 on JVM and Dart.
- Prelude constants. The quoted prelude holds integral float literals
  such as `1.0`, which the ClojureScript reader collapses to `1` before
  any code runs.
- Runtime values. `py/float` builds `{:py/float (* 1.0 x)}`, so `4/2` is
  `{:py/float 2}` on Node, and any addressed image holding it diverges.
- The loud variant: an integral float at or above 2^53 hits Node's
  unsafe-integer refusal.

Both rejected alternatives fail for the same reason, that the kind is
gone before the codec sees the number. Normalizing integral floats to
integers already describes Node; extending it to JVM and Dart violates
"`1` and `1.0` have different addresses". Tagging row inputs alone
leaves runtime values diverging and gives Node two representations,
since a JVM-minted float row already decodes to the carrier on Node.

The normative rule, astra's text as widened by fable's correction 1 to
runtime values:

> Every floating-point value entering canonical rows or any addressed
> image, including source literals, prelude constants, nested Python
> float payloads, and runtime float values held by snapshots and
> continuations, must preserve float64 kind before host representation
> erases it. Addressed values use Jing's existing float64
> representation. A Python float wrapper alone does not establish the
> numeric kind of its payload. Row projection and transformation must
> preserve that kind.

The mechanism is Jing's existing `dao.jing/float64` carrier (tag 27),
inserted at the producer, under these constraints:

- One representation. Every `:py/float` payload is Jing float64 content,
  `cbor/float64` of the value: the identity on JVM and Dart, the carrier
  on JavaScript. On JavaScript every payload is a carrier, non-integral
  values included, since a carrier equals only another carrier.
- Kind is fixed where it is still known. Lowering constructs the carrier
  while literal syntax still identifies a float; the prelude marks its
  float constants explicitly before JavaScript collapses them. A later
  scan of bare numbers cannot recover the distinction.
- The prelude seam. `py/float` wraps and `py/num` unwraps through two
  new pure functions in the `data` module's table (section 9.3);
  arithmetic between them stays host-native on bare numbers. The quoted
  prelude carries no integral float literal: `(* 1.0 x)` coercions and
  constants such as `1.0` and `0.0` go through the data functions, while
  a non-integral constant such as `0.5` stays. A JVM test fails on any
  integral-valued double in a prelude literal row.
- No capture inside the seam: no yield or safepoint sits between an
  unwrap and its rewrap. Whether the safepoint stage marks sites inside
  prelude bodies is unverified, so a test pins this.
- No new tag, payload kind, or row shape. Tag 27, signed-zero
  preservation, NaN normalization, and the refusals of equal-value,
  different-kind collection collisions are unchanged. The generic codec
  learns no Python map semantics; its canonical encoding contract and
  frozen fixtures are not edited. The converged refusal ruling amends
  this for the carrier's behavior only (below).

Carrier insertion is not a drop-in runtime fix. An execution bridge
admits the carrier at all three scalar gates, `plain-data?` and
`machine-data?` in `yin.vm` (the row and machine-payload gates, which
otherwise refuse to build a carrier-bearing row) and `scalar?` in
`yin.vm.engine`, and audits the kind classifier in `yin.vm.values`.
The exact-integer carrier is the precedent (section 8.5.4), and
`yin.vm.debruijn` already sees through the float carrier. Values keep
their float kind whenever they return to addressed data. Two boundary
audits follow: `float-repr` in the renderer unwraps the carrier on
JavaScript, and Node test expectations comparing bare numbers move to
constructors or `content=`.

Implementation. The lowering builds `{:py/float (float64 x)}` while the
literal syntax still says float. Two pure exports join the `data`
module: `float64` wraps a number as float64 content, and `float-value`
answers the host double of a number or carrier. The prelude's `py/float` wraps
through `data/float64` and `py/num` unwraps through `data/float-value`,
with host arithmetic on bare numbers in between. The quoted prelude holds
no integral float literal, and every float constant, `0.5` and `##Inf`
included, reaches arithmetic only as an operand of `data/float-value`,
which is also the bridge back from a carrier decoded out of a row; a test
walks the base and hook preludes for both rules. The carrier is a scalar
at `plain-data?`, `machine-data?`, the engine's lift and pin `scalar?`,
`values/kind-of`, and the de Bruijn executable-image encoders, whose
double encoding reads the payload directly so -0.0 keeps its sign. The
renderer's `float-repr` unwraps the carrier. No capture sits inside the
seam: the safepoint stage marks no site under the bundled prelude (a
test pins that its id is unchanged in `A'`), and the prelude bodies
between an unwrap and its rewrap hold no yield; this reliance stands
until C2 yields inside prelude bodies.

The dict key. `numeric-key` is the interim key (converged sign-off,
Q1): the integer when the value is integral within +/-(2^53 - 1), so
`1`, `1.0`, `True` and `-0.0`/`0` key alike, and float64 content
otherwise. C3-S2 has replaced this numeric arm with ruling-6
hex-string keys built by exact decomposition from inputs unwrapped
through `data/float-value`, pinned one NaN key (section 8.5.4), and
deleted `numeric-key`.

Generic arithmetic (converged refusal ruling). The standard primitive
bindings are unchanged: `+ - * / < > <= >=` stay the host functions (`/`
is `checked-divide`), `= == !=` stay host `=`, and nothing in `yin.vm`
unwraps or refuses a carrier. Instead the JavaScript carrier itself
refuses numeric and default coercion: its `valueOf` throws Jing's
`:carrier-coercion` refusal (`dao.jing.cbor.md`), so a decoded float
under a bare `+` throws on Node rather than concatenating text or
returning a bare Number, and computes `3.0` from `2.0` on the JVM and
Dart. Generic arithmetic over decoded carriers is therefore not
portable, a disclosed limitation: only a profile with an explicit seam,
Python's `data/float-value`, computes on floats portably.
`decoded-float-under-bare-plus-test` pins the asymmetry, and
`dao.jing.cbor-test/float64-carrier-refuses-coercion-test` pins every
operator, operand position and the preserved printing.

With identical float bits and hash algorithm, corrected rows have
identical bytes and addresses on every host. Migration:

```text
+----------------------------------+-------------------------------------------+
| Artifact                         | Effect                                    |
+==================================+===========================================+
| Python literal rows, JVM and     | Bytes unchanged                           |
| Dart                             |                                           |
+----------------------------------+-------------------------------------------+
| Python literal rows, Node        | Re-minted to match JVM and Dart           |
+----------------------------------+-------------------------------------------+
| Prelude rows                     | Change on every host, so every bundled    |
|                                  | unit's address changes; a prelude-profile |
|                                  | bump (section 11)                         |
+----------------------------------+-------------------------------------------+
| Parent rows, A and A' roots,     | Re-minted wherever a changed row is       |
| address-bearing references       | reachable                                 |
+----------------------------------+-------------------------------------------+
| Jing canonical fixtures          | Unchanged                                 |
+----------------------------------+-------------------------------------------+
```

Affected artifacts are rebuilt from source. An integer address is never
aliased to a float address.

"Float-free" describes the entire addressed payload, including the
bundled prelude, not merely the user's Python source. Before the slice,
the prelude itself carried the divergent literals, so no bundled unit was
float-free. Host-specific goldens do not establish portability. Until the
preparatory slice lands, none of the following is allowed (the
`cbor.cljc` item as amended above):

- a cross-host pin of a bundled-unit root, A or A', even for a
  float-free user program;
- a cross-host pin of a snapshot or continuation holding a runtime
  float;
- a Node-specific golden for such trees;
- a C2-S5 cross-host golden on a float-bearing tree;
- an edit to `cbor.cljc` or its fixtures.

The gate on float-bearing address acceptance releases only when tests
on JVM, Node, and Dart establish identical canonical bytes and A and A'
roots for float-bearing inputs and the full prelude; preservation
through projection and decoding; distinct integer and float
identities; signed-zero behavior; and working execution across the
required evaluators. `test/yang/python/antlr/float_address_test.cljc`
carries those tests: JVM goldens for the canonical bytes, the bundled
and hook preludes, and a float-bearing program's `A`, `A'` and
derivation record, asserted unchanged on Node and Dart; NaN repr, the
one quiet NaN in canonical bytes and a NaN-computing program; and lift,
pin, heap and closure round trips, and continuation admission and
payload bytes (a captured continuation cannot be lifted), that keep
integral floats, both zeros, NaN and both infinities byte for byte.

What may proceed meanwhile:

- Safepoint slice 1's float-free golden stands: its tree is hand-built
  with no numeric literals and proves that restricted transformation's
  parity.
- C2-S5 (section 8.5.3): heap collection, lift refusal, user-defined
  iterator classes, and snapshot rendering, with behavioral parity and
  same-host determinism.
- C3-S2 (section 8.5.4): integer-only literal rows, bytes, hashes, and
  malformed-encoding refusals, with cross-host pins at the row or
  subtree level.

The float slice and C3's integer carrier both pass through the scalar
gates in `yin.vm` and `yin.vm.engine`. If C3-S2 edits those gates, the
two land serially, not concurrently.

#### 8.5.6 Imports, the linked prelude, and the frontend catalog (phase C4)

This section records the C4 design as amended by the C4 cross-ruling's
nineteen converged rulings; where they differ from the design's own
recommendations, the converged rulings govern. Its implementation is
pending, in slices F2, F3, P1 to P3, and I1 to I7 below; F1 and P1 have landed.

Install delivers code; instantiation is the importing task's own
evaluation (ruling 1). A linked Python-side module (the base prelude,
the hook prelude, any Python module) exports only closures and immutable
specification data. Its install child defines lambdas and halts. Python
namespaces, class objects, hook state, and runtime cells are allocated
later by an explicit initialization in the consuming task. This is
section 8.5.1's "the linker delivers content-addressed code images,
never Python namespaces" taken literally. Running a module body inside
the install child fails four ways against the landed linker:

- The lift refuses. A heap cell ref is non-portable, and every Python
  function, class, dict, and module is a cell, so no body-executed
  module could reach `linked`.
- Output vanishes. Import-time `print` writes the child's output, which
  is never published.
- Identities duplicate. The child lowers its own copy of each
  dependency, so builtin classes in the export slice would be fresh cells
  and `isinstance` would fail.
- First link wins. The parent discards the child's writes to a
  dependency's store.

So C4 needs no heap-slice lift, and nothing is copied per receiving task
(section 8.5.1, amended). `py/init!` initializes the prelude's own
active module store once, idempotently, with defined failure behavior;
entry wrappers call it and install children never do. An imported body
runs under the importing task's declared effects and may itself suspend:
"no code-delivery wait inside `py/import`" is not a promise that body
execution cannot park.

Module shape. A Python unit compiled as module `a.b` becomes one linker
module:

```clojure
(do (require 'py)              ; hoisted: code delivery only
    (require 'pym.c)           ; one per static import in the unit
    (yin/def spec {:name "a.b" :package? false})
    (yin/def body (fn [%globals %globals-fn] <lowered module body>)))
;; :yin.module/exports #{spec body}
```

- Hoisted, pinned requires are an explicit eager-dependency restriction,
  not an invisible transformation (ruling 4). `if False: import
  missing`, a caught failed import, and a pre-populated `sys.modules`
  all tell it apart from Python's statement-time import, and the support
  profile says so. The required module names are derivable from rows;
  their manifest addresses additionally need the pinned resolution
  snapshot.
- Body execution stays lazy. `import c` lowers at the statement to
  `(py/import "c" pym.c/spec pym.c/body)` plus the binding, and the body
  runs in the importing task, in statement order.
- `__package__` comes from `spec`: package-ness is source layout, not
  derivable from rows.

Names (ruling 3). The registry nests dotted names, so a module `a`
presumably shadows `a.b` (inferred by the design, untested). Until the
linker seat fixes that, no linker module name is a dotted prefix of
another:

```text
+---------------------+--------------------------------------------------------+
| Module              | Linker module name                                     |
+=====================+========================================================+
| Base prelude        | `py` (reserved)                                        |
+---------------------+--------------------------------------------------------+
| Hook prelude        | `pysp` (reserved)                                      |
+---------------------+--------------------------------------------------------+
| Python module       | `pym.a$b$c`; `$` is valid EDN and never in a Python    |
| `a.b.c`             | identifier, so the mapping is injective. The original  |
|                     | Python name stays in module metadata. Nothing is ever  |
|                     | installed at the `pym` root.                           |
+---------------------+--------------------------------------------------------+
```

The registry-prefix defect is filed with the linker seat, with tests for
both installation orders.

Resolution, in order:

1. `sys.modules`, a heap dict in prelude state; a hit returns the module
   object.
2. Runtime-synthesized modules, `sys` and `builtins`, pre-seeded by
   `py/init!`.
3. The linker: a task registry hit, else name environment, manifest,
   verify, pure install, receive.
4. Refusal (see catchable imports below).

There is no `sys.path` and there are no finders; the search path is the
reader's snapshot set and declared principals, and a dependency edge
names a content address (section 9.6).

`py/import`, prelude code, on a miss creates the module object, sets
`__name__` and `__package__`, inserts it into `sys.modules` before
running the body, and runs the body; on an exception it removes the entry
and re-raises. `py/run-main` instantiates the same code image as
`"__main__"`, so the code address does not depend on the role and
`if __name__ == '__main__'` needs nothing else. `__file__` is absent;
`__spec__` and `__loader__` are `None`.

The module store does not back the namespace:

- The module store holds `spec` and `body`, immutable after install.
- The module object is a heap cell `{:py/type :module :name s :dict d}`,
  where `d` is the dict the body receives as `%globals`.
- `setattr`, `del`, and `globals()` writes are heap dict operations; the
  linker's store model (literal keys, no delete, first link wins) is
  untouched.
- Two tasks importing one module get independent namespaces, one Python
  process being one task. The linker's open cross-task module-store
  decision is not needed, and module state migrates only when heap lift
  lands.

Builtins (ruling 2). `py/init!` builds one builtin namespace dict per
Python task, shared by that task's modules. A global lookup checks the
module namespace, then falls back to the builtins, Python's own model, at
the cost of a second lookup on a global miss. Interpreter-generated
exceptions keep canonical builtin class references even when guest code
rebinds the name. Tests cover initialization twice, builtin mutation,
global shadow and delete, and cross-module exception identity.

Import forms:

```text
+---------------------------+--------------------------------------------------+
| Form                      | C4 treatment                                     |
+===========================+==================================================+
| `import a.b.c`            | Imports `a`, `a.b`, `a.b.c` in order, each body  |
|                           | once, and sets each as an attribute of its       |
|                           | parent (ruling 13).                              |
+---------------------------+--------------------------------------------------+
| `from pkg import name`    | Succeeds when `pkg` already exposes the          |
|                           | attribute. Raises only when lookup fails and the |
|                           | unsupported submodule auto-import would be       |
|                           | needed (ruling 13).                              |
+---------------------------+--------------------------------------------------+
| `from m import *`         | Respects `__all__`; without it, excludes         |
|                           | underscore-prefixed names. Copying every key is  |
|                           | insufficient (ruling 13).                        |
+---------------------------+--------------------------------------------------+
| Relative imports          | Resolved statically at lowering from the         |
|                           | declared module name and package status:         |
|                           | package `a.b` and module `a.b` have different    |
|                           | bases. A packageless or beyond-top-level case    |
|                           | raises `ImportError` at the executed statement.  |
|                           | Rebinding `__package__` or a different runtime   |
|                           | package does not retarget them, a disclosed      |
|                           | restriction (ruling 12).                         |
+---------------------------+--------------------------------------------------+
| `importlib.import_module` | Only statically recognized calls with a literal  |
|                           | name; dynamic forms get an explicit diagnostic.  |
|                           | Rebinding or aliasing `import_module` must not   |
|                           | make the compiler recognize the wrong callable   |
|                           | (ruling 10).                                     |
+---------------------------+--------------------------------------------------+
| `importlib.reload`        | Re-runs the same image against the same dict;    |
|                           | the module object and dict are retained, names   |
|                           | not overwritten survive, external bindings are   |
|                           | unchanged, and a failure propagates without      |
|                           | being treated as a failed first import (ruling   |
|                           | 11).                                             |
+---------------------------+--------------------------------------------------+
| Finders, loaders,         | Refused with an explicit unsupported error       |
| `meta_path`,              | (ruling 11).                                     |
| `path_hooks`, `sys.path`, |                                                  |
| `__import__` override     |                                                  |
+---------------------------+--------------------------------------------------+
```

The literal-name restriction is a C4 scope restriction, not a proof that
a computed name must be an unpinned dependency; a future resolver could
interpret computed names against an immutable catalog. Reload cannot
replace a live task's cached image, since the registry hit answers for
the task's lifetime and switching a name snapshot is insufficient; a code
upgrade needs a fresh process or a separately designed replacement
protocol (ruling 11).

Future plan: a virtual POSIX guest library (`yang.posix` or guest-level
`os`/`io` adapters) may emulate paths, file descriptors, and stream-backed
directory trees over `dao.space.store` and `dao.stream` without introducing
ambient host syscalls or new host seams, preserving continuation mobility
and replay determinism.

Catchable imports (ruling 8). A link refusal is raised as the effect's
error, which the VM has no guest-catchable form of. Refusal-as-data
linking (`module/try-require`, following the `stream/poll!` precedent of
decision 5) is adopted, but alone it does not make
`try: import x / except ImportError` work: a hoisted eager dependency
can fail before the body reaches the `try`. The claim also needs
deferred dependency admission with statement-time resolution: check
`sys.modules` first, attempt delivery at the import statement under the
pinned resolution environment, and read exports only after success. I4
stays gated until publication and free-name validation can represent
such deferred dependencies, absent names included, without weakening
address verification or admission. Until then an absent dependency
refuses the dependent's link before any Python runs, and the support
profile records it.

Cycles (ruling 9). Python-level cycle semantics follow from
insert-before-execute, including "cannot import name" on a partially
initialized module. A code-delivery cycle cannot be pinned by content,
and the linker refuses `:require-cycle`:

- First, the publisher refuses a delivery cycle with a diagnostic naming
  it.
- Later (I5), it publishes one deterministic unit per strongly connected
  component. Intra-SCC `require` edges are eliminated in favor of
  internal body references; one thin alias module per member re-exports
  its body and depends on the SCC unit, never the reverse. Member order
  is deterministic, export names are collision-free, and outgoing pins
  are explicit. Members keep separate Python module objects and lazy
  initialization. No SCC identity primitive is needed, but the canonical
  construction needs a published contract with byte and address
  fixtures.

The linked prelude:

- One source, two emitters. The definition list in `prelude.cljc` stays
  the single source; the bundled emitter is today's, and the module
  emitter strips the module's own namespace from keys and internal
  references, because export keys are bare.
- Runtime state moves from module-level definitions into `py/init!`,
  which writes one state slot (`py.rt/state`, a literal `:py/uninit`
  until `py/init!` flips it to `:py/ready` as its last write; the cells
  and classes stay store keys the body defines).
- The linked entry wrapper is
  `(do (require 'py) (py/init!) (py/run-main (fn [%globals %globals-fn]
  ...)))`.
- The lowering drops `builtin-names` and direct `py.b/*` reads, so a new
  builtin class no longer touches the lowering, and the hand-kept host
  name sets become derivable from the tree.
- Imports require the linked prelude. A module closure's free reads never
  see the ambient store, so a bundled importer's `py/*` definitions are
  invisible to an imported body.

Addresses. Every golden moves once, since the wrapper and builtin reads
change. Afterward a linked user program's code root stays stable across
prelude revisions only while its emitted rows and ABI references stay
unchanged. Its published module manifest does not: `:yin.module/requires`
is part of the addressed manifest, so a changed prelude pin changes the
package identity. Code-root stability and executable package identity
are distinct, and both the float carrier correction and the linked
migration preserve the distinction. The note in section 8.5.3 that each
prelude change moves every bundled unit's address stays true of the
bundled profile.

Host-export profiles (ruling 7). Publish refuses the prelude today: a
free name bound by a host module is refused, and the prelude calls the
`cell`, `data`, and later `integer` modules. Linker prerequisite L-a has
the prelude manifest declare its host modules (cell, data, integer, and
stream poll) by semantic profile address, and covers publication,
requirement discovery, installation matching, effect declarations, and
qualified host-export resolution, not merely a manifest field. Integer
limits and semantic versions are part of the matched profile identity;
the descriptive `module-version` (section 8.5.4) is insufficient. The
ability to execute and re-encode float64 carriers is checked without
changing Jing's numeric bytes or adding Python-aware codec logic.

The AST walker (ruling 6). A module whose exported lambda reads a sibling
definition is refused `:undeclared-free` in the tree format, so the
walker cannot link the prelude today. L-b admits the linked prelude's
declared sibling reads through the same verification and store-isolation
contract as the other evaluators. Three-VM intermediate development is
acceptable; three-VM completion is not. Python raises across module
boundaries by continuation invoke, so I1's cross-module raise test probes
store-context restoration, and D7 slice C is a prerequisite wherever that
test exposes incorrect restoration on any VM.

Safepoints. Linked, `pysp` requires `py`, inverting today's load order.
Its cursor cannot be created at install and a module closure cannot read
an ambient signal stream, so the wrapper passes the stream to
`(pysp/attach! signals)`.

- Frontend marks stay (ruling 15; decision 6 settled). Occurrence-scoped
  marks are preserved through publication and transformation, and loop
  or function meaning is never inferred from generic lambda structure.
  Imported Python bodies are marked; runtime internals are excluded under
  the selected policy.
- Atomicity: prelude internals stay unmarked, so section 8.11's
  runtime-internal atomicity holds, but an import runs guest code that
  can contain effects and safepoints. The import operation as a whole is
  not atomic.
- Site marks live in the frontend-metadata side table, not rows, so a
  published tree carries none. Ruling 14: keep sites outside canonical
  rows, but publish a content-addressed site set with a pinned
  association to the exact source root, frontend revision, and
  transformation profile, with retrieval and verification obligations,
  as datoms. A separate publication record may avoid a module manifest
  schema change, conditional on complete discovery; a schema change is
  not ruled out in advance.
- Until site sets are published, an imported `while True` is not
  interruptible. Uninstrumented imports are permitted only under an
  explicitly noninterruptible profile; a profile requiring hooks refuses
  a missing site set.

Bundled mode (ruling 5). The bundled profile stays selectable until two
gates pass, then it is deleted, with no compatibility shim:

- The migration gate: the linked corpus on all four evaluators on JVM,
  Node, and Dart, twelve lanes, covering publication, parserless
  consumption, runtime initialization, and exception parity, not merely
  local evaluation; the AST walker is included (ruling 6).
- The float64 address gate of section 8.5.5. Carrier preservation and
  the execution bridge complete first; affected rows, derived images,
  manifests, and references are then rebuilt. The linked migration
  changes bundled roots separately, and neither transition aliases old
  integer-misclassified content to corrected float content.

The float fix is also a hard prerequisite for P2: the prelude manifest
must have one address on every host.

The REPL frontend catalog (ruling 16). `yin/repl.cljc` today has a
closed `case` over three languages, language special cases, and static
requires of all three frontends; its `:python` is the legacy
`yang.python`, and the ANTLR parser exists only on the JVM. The design
realizes sections 3.3 and 3.6 for the REPL:

- `yang.frontend`, new and pure cljc: manifest validation,
  `install catalog manifest binding => catalog'`, and selection by
  `[id revision]`. The catalog is a value; a manifest carrying a
  function or handle is rejected.
- The REPL takes the catalog from its composition as a `create-state`
  option; each host's `main` builds it and `yin.repl` requires no
  frontend. A session pins an immutable catalog snapshot, and each
  request pins the selected revision and installed binding. Shell
  commands stay the shell's own syntax.
- The installed binding is a pair of stages over supplied streams, parse
  and lower, plus an optional completeness probe; the REPL steps them to
  an outcome. Language-specific parsing and completeness logic stay out
  of REPL core. An explicit parser service, worker or remote, has the
  same shape; a slow one needs a pending-compile state like the existing
  pending-require.
- No fallback between frontends: `:yang.python/antlr` and
  `:yang.python/legacy` are distinct ids, and legacy retires at its own
  migration gate. Selecting a frontend answers unavailable-parser only
  when neither a local parser nor an explicitly configured service is
  available, on any host (section 4.1).
- Support claims derive from explicit semantic and runtime declarations
  and tests, not solely from the syntactic `unsupported-rules` list.
- A second language (JavaScript, parked) installs as parser artifacts,
  a lowering namespace, a published prelude module, a manifest, and one
  `install` call in the composition, with no edit to `yin.repl`, Yang
  core, or the evaluator.

Python's frontend profile pins:

```text
+-----------+------------------------------------------------------------------+
| Part      | Pin                                                              |
+===========+==================================================================+
| Grammar   | `grammar-id`, entry rules, export profile `:yang.cst/v1`         |
+-----------+------------------------------------------------------------------+
| Lowering  | An immutable implementation revision tied to source or           |
|           | build-artifact digests, its dependencies and options, and a      |
|           | content-addressed lowering-profile descriptor (ruling 17). A     |
|           | golden-corpus digest is supplementary evidence, not identity;    |
|           | host code needs no executable UAST to have artifact identity.    |
+-----------+------------------------------------------------------------------+
| Runtime   | `py` and `pysp` manifest addresses, the safepoint profile map,   |
|           | host-module profile addresses (cell, data, integer with limits,  |
|           | stream poll), and permitted effects                              |
+-----------+------------------------------------------------------------------+
| Support   | Explicit declarations and tests (ruling 16); reference runtime   |
|           | CPython 3.9.6                                                    |
+-----------+------------------------------------------------------------------+
```

The REPL `__main__` persists across submissions (ruling 18): its module
object, globals dict, builtin namespace, and import cache. `(reset)` is a
new Python process: fresh task-owned runtime state, handler stacks and
hook attachments included, while immutable verified code may be reused.
Runtime state never merges across frontend revisions, and late results
from the old process cannot mutate the new one.

Foreign-principal imports are not claimed until D7 slice B lands with
its tests (ruling 19), verifying closure origin and store ownership
recursively through exports, re-exports, and transitive dependency
slices before any executable value is exposed. B is necessary, not
sufficient: the claim also needs authenticated publication,
dependency-pin checks, primitive and effect admission, and working
store-context transitions on all four VMs. Until B lands, a forged
`:store-of` could reach the prelude's state. Corrected float publication
rebuilds affected derivations, manifests, and dependency pins before any
cross-principal address is accepted.

Linker prerequisites, the linker seat's to build:

```text
+------+-----------------------------------------------------+-----------------+
| Item | Scope                                               | Blocks          |
+======+=====================================================+=================+
| L-a  | Host-export profile requirements in published       | P2              |
|      | manifests, enforced end to end (ruling 7)           |                 |
+------+-----------------------------------------------------+-----------------+
| L-b  | Module-level sibling reads in the tree format       | The walker      |
|      | (ruling 6)                                          | under the       |
|      |                                                     | linked profile  |
+------+-----------------------------------------------------+-----------------+
| L-c  | Registry nesting fix                                | Optional;       |
|      |                                                     | mangling covers |
|      |                                                     | it              |
+------+-----------------------------------------------------+-----------------+
| L-d  | `module/try-require` plus deferred dependency       | I4              |
|      | admission (ruling 8)                                |                 |
+------+-----------------------------------------------------+-----------------+
| L-e  | D7 slice B, the origin and store check (ruling 19)  | Foreign-        |
|      |                                                     | principal       |
|      |                                                     | imports         |
+------+-----------------------------------------------------+-----------------+
| L-f  | Large-module linking: default bounds, derivation    | P2              |
|      | fetch at steps 2 to 4, linear free-name scan        |                 |
+------+-----------------------------------------------------+-----------------+
```

```text
+-------+----------------------------------------------------------------------+
| Slice | Scope                                                                |
+=======+======================================================================+
| F1    | `yang.frontend` catalog and manifest validation; landed in           |
|       | `src/cljc/yang/frontend.cljc`                                        |
+-------+----------------------------------------------------------------------+
| F2    | The REPL selects through the catalog; `yin.repl` drops its frontend  |
|       | requires                                                             |
+-------+----------------------------------------------------------------------+
| P1    | Single-source prelude, `py/init!`, builtins dict; still bundled      |
+-------+----------------------------------------------------------------------+
| P2    | Module emitter, publication, linked profile; needs L-a, L-f and the  |
|       | float fix                                                            |
+-------+----------------------------------------------------------------------+
| P3    | `pysp` linked, `attach!`                                             |
+-------+----------------------------------------------------------------------+
| F3    | The Python ANTLR frontend in the catalog (JVM); persistent           |
|       | `__main__`; needs P2                                                 |
+-------+----------------------------------------------------------------------+
| I1    | Static absolute imports of single modules, `py/import`, module       |
|       | objects, `sys.modules`, `__name__`                                   |
+-------+----------------------------------------------------------------------+
| I2    | Packages, mangling, relative imports, `as`, `*`, `__package__`       |
+-------+----------------------------------------------------------------------+
| I3    | Publication and DHT import; needs L-e for foreign principals         |
+-------+----------------------------------------------------------------------+
| I4    | Catchable `ImportError`; needs L-d                                   |
+-------+----------------------------------------------------------------------+
| I5    | SCC units and alias modules                                          |
+-------+----------------------------------------------------------------------+
| I6    | Literal `import_module`, same-image `reload`; hooks refused          |
+-------+----------------------------------------------------------------------+
| I7    | Published site sets for imported modules                             |
+-------+----------------------------------------------------------------------+
```

Acceptance, each on all three hosts unless stated (Node and Dart consume
precompiled CST packets or rows, since the parser is JVM-only):

- F1: two revisions of one id installed into an empty catalog are both
  selectable and the original catalog is unchanged; a manifest holding a
  function is rejected with a qualified outcome.
- F2: a toy frontend defined in a test namespace installs, is selected,
  and evaluates on every VM with no `src` edit; a newer revision
  installed under a live session leaves it unchanged; on Node and Dart
  with no parser or configured service, the ANTLR id answers
  unavailable-parser with no legacy fallback.
- P1: the full corpus output is unchanged on every VM and host; a
  composition with no cell module halts ok on `functions-uast`;
  `py/init!` twice yields one set of class cells; shadowing then
  deleting a builtin name makes the builtin visible again.
- P2: `py` publishes to one pinned manifest address on each host; the
  linked corpus output equals bundled; one source under two prelude
  revisions keeps one program root; an exception raised in one unit is
  `isinstance` of `Exception` read in another unit of the same task; an
  install child at `validated` has an empty heap and no `:cell` lift
  refusal; a wrong host-module profile is refused by name; a missing
  name-environment entry is refused with no bundled fallback.
- P3: safepoint slice 1's acceptance re-runs under the linked profile on
  every VM; stage input contains no prelude row; a derived program with
  no `pysp` binding reports the unresolved hook name.
- F3: at the REPL, `x = 1` then `print(x)` prints `1`; the probe answers
  incomplete until a multi-line `def` closes.
- I1: `print("a"); import m; print("b")` orders `a`, `m`'s output, `b`;
  a second import does not re-execute; `m.x = 5; del m.y` is visible
  through `m.__dict__` and to `m`'s functions; a raising body removes
  its entry and propagates; a handler in the importer catches an
  exception from a function in `m` on every VM; one image as main and as
  import sees different `__name__` values.
- I2: `import a.b.c` runs three bodies once, in order, with attributes
  set; a relative import in a packageless unit raises an `ImportError`
  caught by `except`; `from pkg import sub` with no explicit import and
  no attribute raises the documented error.
- I3: a JVM-published `pym.m` imports parserless on Node and Dart with
  the same output; a republished dependency is refused as a dependency
  binding with no install; a cyclic pair is refused at publication by
  name.
- I4: an absent module inside `try` runs the `except
  ModuleNotFoundError` branch; an ambiguous name raises `ImportError`
  carrying the refusal.
- I5: mutually importing `a` and `b` both import; `from b import x`
  during partial initialization gives CPython's error text.
- I6: `reload(m)` re-runs the body against the same dict with `m`'s
  identity unchanged; registering a finder raises the explicit
  unsupported error.
- I7: an imported `while True` with one signal ends with
  `KeyboardInterrupt`.

Order: F1 and F2 now. The float fix and the prelude slices in flight at
design time (C2-S2, safepoint slice 2, C3-S2) land before P1, serially,
since each reshapes `prelude.cljc`; later slices add definitions to the
single source and are profile-agnostic. Then P2 after L-a; then P3, F3,
and I1; then I2; then I3; then I4 to I7 as their gates allow. C2-S3 to S5, C3-S3
onward, safepoint slices 3 and 4, and linker hardening are independent
of the I-track.

Open:

- The legacy frontend id's naming is the owner's (F2).
- The registry-prefix shadowing is inferred and needs a failing test
  first; it affects Clojure-named modules too.
- `yang.antlr.packet`, which is language-neutral, emits a Python-named
  malformed-CST diagnostic, so a second ANTLR language would report
  Python-named diagnostics.

### 8.6 Object-oriented and web: PHP, mixed text and ordered maps

The lexer uses modes to preserve HTML/text and PHP regions in source
order. Text outside code lowers to ordered output effects; it is not
discarded by stripping tags.

PHP arrays require ordered-map behavior, including key conversion and
iteration rules. A plain host hash map is not a sufficient
representation.

The semantic profile must cover:

- `$var` binding and function/global/static scope.
- Capture by value versus capture by reference.
- References and aliasing.
- Ordered arrays and observable copy behavior.
- Coercion, comparisons, missing values, and error behavior.
- Function and class calls, including supported type declarations.

Section 8.11 maps PHP arrays, objects, and references onto cells.

Superglobals are explicit request-context values supplied by the
composition. They do not read ambient process or HTTP state.

`include` and `require` perform module-resolution effects with recorded
inputs. `eval` is a new compilation request, subject to the same
profiles and diagnostics.

The existing frontend's ignored type hints and limited closure handling
are migration limitations, not semantics to preserve as the new default.

### 8.7 Prototype: JavaScript, lexical environments and jobs

The frontend distinguishes script and module goals and pins an
ECMAScript edition.

It lowers:

- `var` bindings and initialization.
- `let` and `const`, block environments, and temporal dead zones.
- Per-iteration bindings where required.
- Ordinary functions versus arrow functions, including `this` behavior.
- Property access, receiver binding, and prototype lookup.
- Short-circuiting and completion propagation.

The prelude defines property descriptors, prototype chains, coercion,
equality, `undefined`, numeric behavior, and other supported object
operations. ECMAScript environment records and execution contexts supply
the semantic reference.

Promises and `async`/`await` require an explicit job interpreter. A
language-level callback is represented as guest callable data scheduled
by that interpreter. It is never installed directly as a host callback.

Observable job ordering, rejection propagation, and suspension must be
tested separately from successful value computation. Browser APIs and
Node APIs are effect packages, not properties of the JavaScript grammar.

### 8.8 Functional: Clojure and its hosts

The Clojure frontend already exists (`yang.clojure`) and lowers directly:
forms become `:lambda`, `:application`, `:if`, `:literal`, and
`:variable` nodes, and definitions become explicit `yin/def`
applications. It implements the section 3 contract through its existing
reader rather than through ANTLR. ClojureScript and ClojureDart are
dialects of the same frontend distinguished by dialect options and
runtime profile, not by separate core branches.

### 8.9 Systems: Go, Rust, C, and further extensions

Future system-language frontends reuse the SPI but contribute additional
semantic analysis and runtime profiles:

- Structures and value copying.
- Logical references, explicit memory regions, and layouts where
  observable.
- Pattern matching and exhaustiveness checks.
- Ownership, borrowing, moves, and destruction where applicable.
- Defined panic/unwind or error behavior.

Go's value and reference-bearing types need their specified copying
behavior; goroutines and channels require a language scheduler above
streams rather than treating a broadcast log as a destructive channel.
Section 8.11 maps Go values, pointers, slices, maps, and channels onto
cells.

Rust requires ownership and lifetime analysis beyond parsing, plus
explicit drop behavior. Those analyses may be an additional service
stage without changing the frontend SPI.

Raw native pointers are host capabilities, never portable literals.
Unsafe/native operations need a separately declared profile.

C preprocessing, Rust macro expansion, SQL dialect resolution, TypeScript
checking, and Ruby dynamic dispatch are plugin-owned stages. Each has
explicit inputs, outputs, and dependencies. None requires Yang core to
recognize its language by name.

### 8.10 Cross-language calls

A common AST does not imply a common object model.

Interop exports need an explicit ABI describing scalar conversion,
strings, numeric ranges, callable arguments, exceptions, object handles,
ownership, and asynchronous results.

Start with a small portable-value interop profile. Rich object sharing
requires adapters; JavaScript `null`, Python `None`, PHP `null`, and Java
null references must not be conflated accidentally.

### 8.11 Mutable objects and collections over cells

This section records the mutable-objects ruling. A mutable guest object
is one cell holding one persistent value, and the object's identity is
the cell:

- The guest reference is the bare `:cell-ref`. The type tag and payload
  live inside the cell's content, so `type(x)` answers the same through
  every alias. The content is a tagged header plus payload, for example
  `{type-tag, items}` for a list or `{class-ref, attrs}` for an instance;
  the exact encoding belongs to each language's runtime profile.
- Immutable guest values get no cell. Numbers, strings, tuples, `None`,
  and the like are plain tagged persistent values.
- A slot gets its own cell only when the language lets that slot itself
  be aliased. PHP reference slots are the case. Go interior pointers need
  no extra cell: they are a `(cell, path)` pair.
- Classes are objects too. Class attributes are assignable, so a class
  is a cell, and an instance holds a ref to it.
- A mutation is `cell/get`, then a pure update, then `cell/set!`.
  Persistent structures make the copy a path copy, not a whole-value
  copy. No atomic update form is needed: a task switches only at park
  points, and the prelude evaluates operands before the get/set pair.
  `cell/swap!` is rejected, because applying a guest closure inside
  effect handling would need continuation frames the engine does not
  build there.

```text
+--------------------------------+-------------------+----------------------------------------------------------------+
| Option                         | Ruling            | Why                                                            |
+================================+===================+================================================================+
| One cell per object,           | Adopt             | `assoc` and `conj` are path copies, so a mutation costs two    |
| persistent value inside        |                   | effect dispatches plus one pure call regardless of size.       |
+--------------------------------+-------------------+----------------------------------------------------------------+
| One cell per field or element  | Reject as default | A list of n elements means n allocations, n seals, and n heap  |
|                                |                   | entries. Dicts with dynamic keys need a container anyway.      |
+--------------------------------+-------------------+----------------------------------------------------------------+
| Host mutable objects           | Forbidden         | Violates "no shared mutable state" and the section 8.1 rule    |
|                                |                   | that aliased guest objects share logical identity, not host    |
|                                |                   | mutable objects.                                               |
+--------------------------------+-------------------+----------------------------------------------------------------+
```

Aliasing and identity:

- Aliasing falls out. After `a = []; b = a; b.append(1)`, both variable
  cells hold the same ref, so both see the update.
- Identity comparison is `=` on two refs, which is true exactly when they
  name the same cell, because a ref is data. It never touches host object
  identity, so it is portable across CLJ, CLJS, and CLJD. Python `is`, JS
  `===` on objects, and Java `==` on references all lower to it. This is
  an invariant: if refs later become host types (mob D7), `=` on refs must
  still mean same-cell, and refs must stay usable as map keys, on every
  host.
- Guest value equality is prelude code. Python `==` on lists recurses
  through cells; it is never host `=` on a value containing refs, which
  would compare elements by identity.
- `is` on immutable values is same type and value, confirmed per
  language profile (owner decision 1). Python's reference permits it. A
  Java profile cannot use it: boxed objects and `new String` require
  distinct identity, so a Java profile gives them cells. For Python,
  value means content identity, so floats compare by binary64 content
  (section 8.5.4).
- The ref itself is a valid host map key, so default instance hashing
  needs no id extraction.
- A guest-visible `id()` is not stable across a lift, because cell ids are
  task-local and re-minted on lift. This is accepted and documented as a
  limitation (owner decision 2).
- Functions: two closures from one lambda with equal environments compare
  equal structurally, while JS and Python require them distinct and allow
  attributes on functions. Full fidelity wraps a function as an object in
  a cell; the Python spike uses bare closures and leaves `is` on functions
  unsupported.

Value-semantics languages map as follows. PHP reference binding cannot be
a lexical rebinding of the name, since a continuation captured earlier
would still see the old binding; that is why the reference marker lives
in the variable's cell.

```text
+--------------------------------------+----------------------------------------------------------------------------+
| Language feature                     | Mapping                                                                    |
+======================================+============================================================================+
| PHP array                            | Plain persistent value stored directly in the variable's cell. Assignment  |
|                                      | copies in O(1); the observable-copy rule is free.                          |
+--------------------------------------+----------------------------------------------------------------------------+
| PHP object                           | Handle = cell ref.                                                         |
+--------------------------------------+----------------------------------------------------------------------------+
| PHP `&$x`, `use (&$x)`, `f(&$x)`     | A variable cell holds either a value or a reference marker pointing at a   |
|                                      | shared cell; reads dereference one level. `$b = &$a` moves `$a`'s content  |
|                                      | into a fresh shared cell and sets both variables to the marker.            |
+--------------------------------------+----------------------------------------------------------------------------+
| PHP reference to an array element    | The same marker sits in the array slot. It survives array copy, which is   |
|                                      | PHP's real behavior.                                                       |
+--------------------------------------+----------------------------------------------------------------------------+
| Go struct, array                     | Plain persistent value; assignment copies.                                 |
+--------------------------------------+----------------------------------------------------------------------------+
| Go `&x`                              | The variable's existing cell ref, since every local is boxed.              |
+--------------------------------------+----------------------------------------------------------------------------+
| Go `&s.f`, `&a[i]`                   | A `(cell, path)` pair: read is a nested get, write is a nested update plus |
|                                      | `cell/set!`.                                                               |
+--------------------------------------+----------------------------------------------------------------------------+
| Go slice                             | Header by value (`backing-ref`, offset, length, capacity); the backing     |
|                                      | array is in a cell.                                                        |
+--------------------------------------+----------------------------------------------------------------------------+
| Go map, channel                      | Cell.                                                                      |
+--------------------------------------+----------------------------------------------------------------------------+
| Java object, array                   | Cell each.                                                                 |
+--------------------------------------+----------------------------------------------------------------------------+
```

Cycles and graphs:

- Persistent values stay acyclic. `a.append(a)` stores a's ref inside a's
  content, so the cycle exists only through heap ids and host printing,
  hashing, and equality never loop. Guest `repr` and `==` need their own
  guards, as in Python.
- Copy-on-lift pulls the heap slice by reachability, with the portable id
  reserved before contents are traversed; aliases and cycles inside one
  lift are preserved. One reference to one object pulls its whole
  reachable graph, including its class and the class's methods.
- Every temporary list is a heap entry for the life of the task, and cell
  slice 1 never frees cells. A collector must trace refs nested inside
  cell contents. Reclamation moves ahead of lift and lower as the first
  work after the Python spike (owner decision 4). Finalizers and weak
  references are out of scope.

Where the semantics lives:

- Prelude (Universal AST): object layout, attribute lookup, method
  resolution, guest equality and hashing, list, dict, and set operations,
  and reference markers.
- The `cell` module: only allocation, read, and write.
- The runtime profile per language: value encoding. Each language's null
  is its own tagged sentinel, never host `nil` shared across languages.
- Cross-language calls: a cell ref crosses as an opaque object handle
  under the section 8.10 interop profile. The receiver may hold it and
  pass it back but not read its content without an adapter.
- Grammar: unchanged. Every operation is an ordinary `:application`.
- Inline caches, field-slot layouts, and un-boxing are separately attached
  interpreters writing their own stream; the lowering emits naive prelude
  calls.

Two constraints bind every prelude:

- Never iterate a host map. Python dicts and PHP arrays are ordered; host
  maps are not, and iteration order differs by host. An ordered dict is
  an index map plus an order vector.
- Normalize dict keys in the runtime profile. Host `=` on numbers differs
  by host: `1` and `1.0` are distinct on the JVM and identical on JS and
  Dart, while Python requires `1`, `1.0`, and `True` to be one key. The
  profile defines key normalization and guest equality explicitly. For
  Python, C3 replaces normalization through a double, which merges
  distinct large integers, with exact reduced-rational keys (section
  8.5.4, C3 ruling 6).

`obj.method()` costs roughly three or more effect dispatches (instance,
class, bases), each on the slow effect path. This is recorded as a
measurement for the spike; promotion to tags or opcodes stays the later
remedy.

---

## 9. Standard library and FFI architecture

Every language arrives with a standard library, and most of what a
program observes as "the language" is that library. This section places
each part of a language's standard library and host interface in one of
four layers. The placement follows the semantic placement rule of
section 8.2 and the host-boundary rule of `datom.world.md`: portable
code appends effects, host interpreters perform them and append outcomes,
and no host function is ever called from portable code.

### 9.1 The four-layer model

```text
+-------+-----------------------------+-------------------------------+
| Layer | Contents                    | How it reaches the VM         |
+=======+=============================+===============================+
| 1     | Pure modules: library code  | Compiled by Yang, exactly as  |
|       | written in the language     | user code; no runtime         |
|       | itself                      | special-casing                |
+-------+-----------------------------+-------------------------------+
| 2     | Language intrinsics and     | Universal AST prelude per     |
|       | preludes: the initial       | language, compiled once,      |
|       | environment                 | content-addressed, fetched    |
|       |                             | from the dao.jing DHT         |
+-------+-----------------------------+-------------------------------+
| 3     | Object system desugaring:   | Records, store cells, and     |
|       | classes, prototypes, traits | closures; no object concept   |
|       |                             | enters the VM kernel          |
+-------+-----------------------------+-------------------------------+
| 4     | System I/O and effects:     | Yin VM effect descriptors;    |
|       | files, sockets, timers,     | Local dao.stream.apply FFI;   |
|       | processes, native libraries | host interpreters append      |
|       |                             | outcomes                      |
+-------+-----------------------------+-------------------------------+
```

### 9.2 Layer 1: pure modules

Source in the language itself, compiled to Universal AST via Yang
directly. A Python `itertools` written in Python, a Java collections
class written in Java, a Clojure `clojure.core` function written in
Clojure: each is an ordinary compilation unit that passes through the
section 5 ingestion and section 6 pipeline and is admitted like any
other program. There is no runtime special-casing: the evaluator cannot
tell library code from user code, and nothing in Yang core names a
library module.

### 9.3 Layer 2: language intrinsics and preludes

A Universal AST prelude per language seeds the initial environment. It
carries what section 8.2 assigns to portable prelude code: value
encodings, truthiness, equality, arithmetic semantics, argument binding,
coercions, collections, and language exceptions.

A prelude is compiled once and content-addressed; its root addresses are
named in the frontend's runtime profile (section 3.1, part 4). It is
distributed via the `dao.jing` DHT, so every host that runs a language
resolves the same prelude by the same address and never rebuilds it. A
composition that pins a runtime profile pins its prelude addresses; two
compositions running different prelude revisions do not interfere.

Preludes are Universal AST code, so they are inspectable, queryable by
`dao.space.query/q` over their rows, and portable across CLJ, CLJS, and
CLJD without per-host reimplementation.

The pure data primitives a prelude needs (length, deletion, removal,
string operations) live in a `:pure` host module named by the language's
runtime profile, not in the standard `vm/primitives` registry, whose
change would affect every composition (mutable-objects ruling, owner
decision 3). The module is `yin.vm.data`, module name `data`, landed in
`fe8bce4a`: a composition whose runtime profile names it installs it
explicitly with `register-data-module`. It is a registry addition, not a
grammar change. Its strings are code-point indexed on every host, and no
export iterates a host map.

Python's exact integers follow the same pattern with a second module: a
versioned `:pure` integer module that the Python runtime profile
requires and the composition installs explicitly (C3 ruling 5, section
8.5.4). It has landed in C3 slice S1 (`54536317`).

### 9.4 Layer 3: object system desugaring

Class, prototype, and trait hierarchies are desugared to records, store
cells, and closures:

- A class becomes a runtime descriptor record plus field layout and
  method code (section 8.4).
- A prototype chain becomes property maps in store cells linked by
  logical identity (section 8.7).
- A trait or interface becomes a dispatch contract the prelude resolves
  at call time.

No object system concept crosses into the VM kernel. The canonical
grammar of `yin.vm.code-as-tuples.md` section 2.3 has no class, method,
prototype, or trait tag, and this document adds none. Dispatch, method
resolution, and inheritance are prelude code (layer 2) operating on data
that desugaring produced.

"Store cells" here are `cell` module refs over the task heap (section
8.1). A mutable object, including a class, is one cell holding one
persistent value, and a mutable collection is likewise one cell; section
8.11 gives the representation, identity, and aliasing rules.

### 9.5 Layer 4: system I/O and effects

System I/O is expressed as Yin VM effect descriptors. Portable code
appends a descriptor naming the operation, its arguments, and a
correlation identity; a host interpreter consumes that stream, performs
the operation, and appends the outcome; portable code reads the outcome.
A host with no implementation for an effect emits a qualified
unsupported result, which is a correct outcome.

FFI calls use `dao.stream.apply` as the VM's local FFI bridge. The call
is a request record on a stream, the result is a response record, and
correlation is by identity in the data. The apply wire envelope is
retired from the network path; remote service traffic uses request and
answer streams over `dao.stream.remote` reflections. No direct host
function calls from portable code. Browser APIs, Node APIs, JVM
libraries, and Dart platform channels are effect packages declared in
a frontend's runtime profile, not properties of any grammar.

Capabilities are declared: the runtime profile lists the effects a
language's standard library may request, and a composition grants or
withholds them. Inside the VM, effects are an unforgeable host type and
the engine checks an effect raised by a primitive against the callee's
declared effect set (section 8.1, mob D4).

### 9.6 Module distribution

Standard-library modules, preludes, and any compiled package are
content-addressed packages in the `dao.jing` DHT. A package is a manifest
naming the root addresses it exports plus the rows those roots reach;
equal content is equal address across hosts. Module resolution consumes
requests and emits pinned module artifacts (section 11); a dependency
edge names a content address, never a search path or a network location.

---

## 10. Diagnostics and error reporting

ANTLR's default behavior combines error reporting and recovery. Console
reporting is supplied by a listener, while recovery may insert/delete
tokens, continue, or raise recognition errors. The integration must
control both mechanisms.

### 10.1 Boundary policy

For lexer and parser:

1. Remove default console listeners.
2. Install boundary transformations that classify diagnostics as plain
   data.
3. Select a pinned recovery strategy.
4. Catch recognized parsing failures at the host boundary.
5. Export recovery markers and terminal parse status.
6. Retain no exception, recognizer, token, or host stack object in
   payloads.

A default strict profile refuses executable publication after any
lexical or syntax error, even when ANTLR constructed a recovered CST. An
IDE profile may publish partial syntax for tooling on a separate medium.

Worker crashes, timeouts, resource exhaustion, grammar incompatibility,
and internal defects remain distinct from invalid source.

### 10.2 Qualified outcome maps

Every stage reports through qualified outcome maps in the shape of
section 6.2: a stream outcome wrapping a compiler outcome. The compiler
outcome names its phase and reason with qualified keywords:

- Parse errors: `:yang.compile/syntax-error` from the parse phase.
- Semantic errors: unbound names, type errors, unsupported features,
  from the analyze phase.
- Lowering failures: unhandled CST constructs, grammar/profile mismatch,
  from the lower phase.
- Operational failures: worker crash, timeout, cancellation, resource
  limit, reported as operational events and never as language errors.

### 10.3 Proposed diagnostic record

```clojure
{:yang.diagnostic/code :yang.python/unbound-local
 :yang.diagnostic/phase :yang.compile/analyze
 :yang.diagnostic/severity :yang.diagnostic/error
 :yang.diagnostic/request ["client-token" 12]
 :yang.diagnostic/attempt ["worker-token" 4]
 :yang.diagnostic/source ["source-unit" 3]
 :yang.diagnostic/span {:start-byte 41 :end-byte 42}
 :yang.diagnostic/arguments {:name "x"}}
```

Messages are rendered from stable codes and arguments. Native ANTLR
wording may be retained as supplemental text, but it is not the
cross-host diagnostic identity.

Normalize expected-token sets, synthetic-token positions, EOF spans, and
diagnostic ordering. Use stable source and phase order, with a
deterministic tie-breaker. Streaming arrival order remains operational
history, not semantic ordering.

### 10.4 Diagnostic datoms on the stream

Persist diagnostic datoms through a diagnostic-ledger interpreter. The
ledger allocates local entity IDs and timestamps. The parser does not
place UUIDs or content hashes in datom `e`. Diagnostics are structured
data on a supplied diagnostic stream; any observer may index, render, or
count them, and none is required.

Cross-target recovery can differ. A frontend advertises diagnostic
parity only for the conformance level it passes; exact recovery parity
may require a shared strategy or a designated parser service.

---

## 11. Determinism and isolation

A reproducible compilation is a function of:

```text
source snapshot
+ source decoding and preprocessing profile
+ grammar and syntax export profile
+ language edition and options
+ semantic lowering profile
+ runtime/prelude profile
+ explicit dependency snapshot
+ target execution contract
```

Host identity, request IDs, wall-clock time, source location metadata,
and incidental worker scheduling are excluded from code identity.

There is no hidden global state in the compiler pipeline: every catalog,
counter, cache, and name supply is a value in explicit compiler state or
an exclusively owned worker resource (section 2.3).

Module resolution consumes requests and emits pinned module artifacts.
Dependency edges are explicit; linking computes reachability and
strongly connected components from those edges. Environment variables,
filesystem search paths, and network responses enter only as supplied or
recorded inputs.

Workers enforce limits on source size, token count, nesting, parse time,
CST size, diagnostics, and outstanding requests. Grammar packages
containing executable helpers are admitted artifacts, not untrusted data
to execute automatically.

Timeout and cancellation facts are operational events. They must not be
presented as deterministic language errors. Signal delivery timing,
which guest code observes only through the `:stream/poll` effect, is
likewise operational and is not journalled in safepoint slice 1
(section 8.5.2, safepoint decision 8). Host allocation failure is
operational too; a profile-pinned numeric limit breach is not, and
surfaces as a deterministic guest exception (C3 ruling 11).

The runtime/prelude profile pins language semantics as explicit data.
For Python under C3 it names the integer and binary64 conversion
semantics, the numeric hash modulus, the integer-text conversion
policy, the admitted numeric limits, and the required pure modules and
boundary adapters; resource checks use portable quantities such as bit
lengths and digit counts, never host object sizes or available memory
(section 8.5.4). A prelude that grows, as C2's does, changes every
bundled unit's address and is a prelude-profile bump.

Cross-host content-address claims remain conditional on the canonical
encoding actually used. The disclosed `dao.jing` encoding residuals
require explicit conformance tests and profile restrictions or fixes;
this document does not silently replace its addressing scheme.

---

## 12. Phased roadmap

### Phase 0: SPI, ANTLR boundary, and stream pipeline

Deliverables:

- Versioned frontend manifest, catalog, support profile, and option
  validation.
- Source-unit, parse-request, CST, diagnostic, cancellation, and
  publication protocols.
- A JVM reference parser worker behind supplied streams.
- Explicit compiler state, staged writes, replay identities, and
  resource accounting.
- Integration with existing encoder, row validators, and loaders.
- A generic replacement for the REPL's closed language dispatcher.
- Documented conversion between encoder envelopes and macro packets.
- ASCII, license, and reproducible-generation checks.

Exit criteria:

- A small external grammar plugin compiles and executes without
  language-specific core changes.
- Two compositions can install different frontend versions without
  interference.
- Host objects are rejected at serialization boundaries.
- Blocked/full/gap/closed/error paths preserve state and framing.
- Recovered syntax cannot reach the evaluator.
- No test relies on a reserved readiness extension or fabricated cursor.

### Phase 1: Dynamic pilot, Python followed by JavaScript

Start with Python to migrate an existing frontend. Add JavaScript
through the same extension surface.

Deliverables:

- ANTLR parsing and explicit dialect profiles.
- Scope analysis, argument binding, truthiness, operators, sequencing,
  closures, and mutation.
- Portable runtime value and calling conventions.
- Python indentation and REPL framing.
- JavaScript script/module distinction, lexical environments, and
  prototype operations.
- Separate milestones for generators and asynchronous jobs.

Python's milestones within this phase are named C1, C2, and C3:

- C1 (`finally` and `with`, tuples and slices, full operators,
  comprehensions, keyword arguments) landed in `34c3986b`.
- C2, generators (section 8.5.3), is decided and recorded. S1 (`7654c2d0`),
  S2 (`c3f2da8f`) and S3, `yield from` and `iter` (`a4efc99a`), and
  S4, generator expressions, have landed; S5 is pending.
- C3, exact integers (section 8.5.4), is decided and recorded. S1 (the
  `integer` module and carrier recognition) has landed. The numeric-key,
  guest `hash()` and integer `is` work of rulings 6 to 8 (orchestrated
  as C3-S2; the table's S5 scope) is implemented: exact keys, `hash()`
  for int, bool and finite float, and value-based `is`, tested at
  prelude level on all four VMs on every host and from source on the
  JVM. Big integers do not yet reach guests from source, since literals
  past 2^53 and operator promotion are later slices; the remaining
  slices are pending.
- Safepoint insertion (section 8.5.2) is decided and recorded, in four
  slices, in order: signals, recursion, tracing, threads. The first two
  have landed (`cf6ed9ad`, and `e2a80eef` with generator admission against
  the limit); tracing and threads are pending.

Exit criteria:

- Differential tests against pinned reference runtimes for the claimed
  subsets.
- Closure mutation, early returns, shadowing, short-circuiting, and
  arity behavior pass.
- Unsupported constructs produce stable diagnostics.
- Different chunk boundaries yield identical semantic output.
- The legacy Python frontend remains selectable until the replacement
  profile passes its migration gate.

### Phase 2: Java static and class-based pilot

Deliverables:

- Declaration and dependency analysis.
- Type checking, overload resolution, and access checks for the
  supported subset.
- Class, interface, constructor, field, static initialization, and
  dispatch runtime support.
- Java numeric and exception semantics.

Exit criteria:

- Differential tests against a pinned Java implementation.
- Tests distinguish overload selection from virtual dispatch.
- Constructors, initialization order, aliasing, null, and numeric edge
  cases pass.
- JVM libraries, reflection, native methods, and concurrency are
  explicitly supported or rejected.

### Phase 3: PHP web and associative-array pilot

Deliverables:

- Mixed HTML/code modes.
- Ordered-array semantics and key conversion.
- Closure capture and reference behavior.
- Request-context injection and stream-based output.
- Module inclusion effects and supported type enforcement.

Exit criteria:

- Mixed text and code preserve output order.
- Arrays preserve ordering and alias/copy semantics.
- Superglobals are reproducible from explicit request input.
- Type declarations are not silently ignored.
- Cross-request state leakage tests pass.

### Phase 4: Language extension SDK and documentation (open SPI)

Deliverables:

- Package template, manifest validator, exporter helpers, lowering
  examples, and prelude conventions.
- Conformance runner and support-matrix generator.
- Guides for dialects, parsing helpers, preprocessing, runtime
  dependencies, and diagnostics.
- License and regeneration tooling.
- A documented external installation flow.

Exit criteria:

- Add a fifth language package, such as a clearly bounded Go or Ruby
  subset, outside Yang core.
- Its implementation changes no core language dispatch, AST tags, or
  evaluator cases.
- At least one additional grammar can be installed in syntax-only mode
  and honestly refuses unsupported executable lowering.
- Two dialect packages coexist without registration collisions.

### Phase 5: Multi-host verification and integration benchmarks

Host checks begin in Phase 0; this phase expands and gates the complete
matrix.

Deliverables:

- CLJ, CLJS, and CLJD compilation/evaluation tests.
- Native and service-backed parser tests where implementations exist.
- Parserless consumers loading precompiled rows.
- Fault injection for service restart, duplicate delivery,
  backpressure, cancellation, and incomplete packets.
- Benchmarks for ingestion, parsing, export, lowering, encoding,
  publication, and evaluation separately.

Exit criteria:

- Identical admitted inputs and profiles produce equivalent normalized
  syntax, canonical ASTs, and semantic traces.
- Address equality is demonstrated wherever the pinned encoding profile
  claims it.
- No source or diagnostic loss occurs under admitted load.
- Caller-step latency remains within a declared budget while parser
  work runs independently.
- Resource use and service overhead are measured rather than hidden in
  end-to-end timing.

---

## 13. Verification laws

### 13.1 Laws

The conformance suite establishes these properties for every language
plugin:

1. **Open extension:** adding a language package requires composition
   changes, not Yang core branches.
2. **Chunk invariance:** changing source chunk boundaries does not
   change a sealed unit's meaning.
3. **Deterministic compilation:** repeated compilation under the same
   pinned inputs produces the same canonical code.
4. **Representation round trips:** canonical `map -> rows -> map` and
   `rows -> map -> rows` are identities.
5. **Occurrence separation:** identical code in two source occurrences
   shares content identity while retaining distinct provenance.
6. **Explicit delivery:** retries preserve staged identities; partial
   packets never execute.
7. **Diagnostic integrity:** expected failures become data without
   stderr leakage or host objects.
8. **Host equivalence:** native and service-backed implementations agree
   within their declared profiles.
9. **Semantic preservation:** observable values, mutation, exceptions,
   output, and scheduling agree with the supported source-language
   specification.
10. **Independent observers:** compilation and evaluation work without
    an indexer, and indexing works without evaluation.
11. **Portable suspension:** lowering checkpoints and guest
    continuations resume from explicit state; native parser stacks are
    recovered through replay.
12. **Honest unsupported outcomes:** unavailable hosts, runtime
    capabilities, and language features fail explicitly.

### 13.2 SPI compliance test matrix

Each frontend package is tested against every law above, on every host
it claims, in every deployment it claims:

```text
+---------------------+---------------------------------------------+
| Dimension           | Values                                      |
+=====================+=============================================+
| Host                | CLJ, CLJS, CLJD                             |
+---------------------+---------------------------------------------+
| Parser deployment   | local worker, configured service,           |
|                     | parserless (precompiled rows)               |
+---------------------+---------------------------------------------+
| Support claim       | syntax-only, semantic analysis, executable  |
|                     | lowering, stdlib compatibility              |
+---------------------+---------------------------------------------+
| Input shape         | many small files, large units, deep         |
|                     | nesting, malformed input, Unicode split     |
|                     | across chunks, indentation-heavy programs,  |
|                     | mixed PHP text, large object hierarchies,   |
|                     | asynchronous jobs, slow consumers           |
+---------------------+---------------------------------------------+
| Fault               | service restart, duplicate delivery,        |
|                     | backpressure, cancellation, incomplete      |
|                     | packets                                     |
+---------------------+---------------------------------------------+
```

A claim a frontend does not make is not tested for it; a claim it makes
is tested on every host and deployment it names. Measure throughput,
peak memory, allocation, output amplification, cancellation latency, and
p50/p95/p99 caller-step latency.

### 13.3 Completion criteria

A pilot is complete only for its published support profile. The
framework is architecturally complete when an independently packaged
fifth frontend passes the same contracts and tri-host consumers can use
it through local parsing, a configured service, or precompiled rows
without changing Yang core.
