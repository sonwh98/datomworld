# yang.antlr: compiling languages with ANTLR and Yang into the Universal AST

Status: Proposed architecture and implementation roadmap. Drafted
2026-09-24 from the Lead System Architect's plan of 2026-09-23
(`collab/1790185837428-architect-yang-antlr-plan.gpt-6-astra.plan.md`)
and the orchestrator's standard-library discussion. No repository code was
changed by this document.

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
  each captured continuation at most once.

Truthiness, arbitrary-precision integers, division, equality, attribute
lookup, descriptors, and iteration belong to the Python runtime profile.
The Python value encoding tags floats on every host, since JavaScript
cannot distinguish `2` from `2.0` and ints are the common case (Python
mappability ruling, owner decision 3); `print(4/2)` belongs in the Node
parity set.

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
|                                              |                        | counter in a cell, decremented by escapes, is prelude code.                  |
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
3. The Python value encoding tags floats (section 8.5).
4. Safepoint insertion (signals, tracing, thread switches, recursion
   accounting) is a separately attached interpreter over the row stream,
   not part of the naive lowering.

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
  fails. A linked prelude's class objects are copied per receiving task
  (mob D1), which is fine within one Python task.

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
  distinct identity, so a Java profile gives them cells.
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
  profile defines key normalization and guest equality explicitly.

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
presented as deterministic language errors.

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
