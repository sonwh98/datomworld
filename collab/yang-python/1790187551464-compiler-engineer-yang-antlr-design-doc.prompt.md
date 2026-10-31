Created-GMT: 2026-09-23 18:19:11 GMT
Created-Local: 2026-09-24 01:19:11 +0700
Coding-Agent: claude
Session-ID: d5692e97-efdf-424a-91dc-9b61187d1ffa

# Task: Draft docs/design/yang.antlr.md

Role: Yang Compiler Engineer

Implementers:
- Model: claude-fable-5-1 | Assigned: 2026-09-24 01:19:11 +0700 | Status: active | Rationale: Draft design document from completed architect plan

## Context

The Lead System Architect (`gpt-6-astra`) has produced a completed
architectural plan for extending Yang with ANTLR-based multi-language
compilation, composable with `dao.stream`. The plan is at:

  /Users/sto/workspace/datomworld/collab/1790185837428-architect-yang-antlr-plan.gpt-6-astra.plan.md

Your task is to draft `docs/design/yang.antlr.md` that faithfully
captures the architect's plan and the standard-library architecture
discussed by the Orchestrator. The document does NOT require architect
review before being written — it is a faithful transcription and
structural organization of material already approved.

## Owner Directives (verbatim — do not reinterpret)

> "the list of language is not exhaustive"

The language list (Java, Python, PHP, JavaScript, Go, Rust, Clojure,
ClojureScript, ClojureDart, etc.) is a set of reference examples, NOT
an enumeration. The architecture must be an open SPI with no closed
language registry.

> "this should be part of the design document of compiling x languages
>  using antlr with yang into the universal AST"

The standard-library and FFI architecture (described below) must be
included as a dedicated section inside `yang.antlr.md`.

## What to Read First

1. `docs/design/datom.world.md` — governing invariants and axioms
2. `docs/design/dao.stream.md` — stream substrate
3. `docs/design/yin.vm.code-as-tuples.md` — Universal AST encoding
4. `collab/1790185837428-architect-yang-antlr-plan.gpt-6-astra.plan.md`
   — the full architect plan (all 12 sections)
5. `src/cljc/yang/clojure.cljc` — existing Yang Clojure frontend
6. `src/cljc/yang/python.cljc` — existing Yang Python frontend (handwritten)
7. `src/cljc/yin/repl/core.cljc` — current closed-case REPL dispatcher

## Document Requirements

### Format

- Pure Markdown (`.md`), stored at `docs/design/yang.antlr.md`
- Lines <= 80 columns
- Pure ASCII (no Unicode, no em-dashes, no curly quotes)
- Section headers use `##` and `###`
- Code blocks use triple backtick fences with a language tag
- ASCII box-drawing tables (spaces/hyphens/pipes, no Unicode box chars)
- No HTML inside the document

### Required Sections (minimum)

The document must include all of the following, organized logically:

1. **Overview / Purpose** — what this document covers; Yang as an open
   compiler composition framework; ANTLR behind a stream boundary.

2. **Governing Decisions** — repository baseline; key representation
   decision (Universal AST maps vs. flat rows vs. datoms); invariant
   enforcement from datom.world.md axioms.

3. **Language Frontend SPI** — the open plugin contract; registration;
   versioning; syntax and semantic support profiles; no closed registry.
   Include the interface/protocol shape in Clojure pseudocode.

4. **ANTLR Host Boundary** — grammar discipline (.g4 files, zero
   target-specific embedded actions for portability); per-host generation
   (`-Dlanguage=Java`, `-Dlanguage=JavaScript`, `-Dlanguage=Dart`);
   ANTLR runtime packaging per host:
   - CLJ: `org.antlr:antlr4-runtime` (Maven/Java)
   - CLJS: `antlr4` (npm)
   - CLJD: `antlr4` (pub.dev Dart)
   ANTLR's mutable machinery confined to an exclusively owned host worker.

5. **Source Ingestion** — reading source via dao.stream; chunk
   boundaries; backpressure; cancellation; recovery.

6. **DaoStream Integration** — how the compiler pipeline composes with
   `dao.stream`; non-blocking stream interface over ANTLR's synchronous
   parsing; effect descriptors for compilation requests.

7. **Universal AST Encoding and Provenance** — the three layers
   (ephemeral AST maps, canonical flat rows, datoms for provenance);
   content-addressed rows; fingerprinting; no second authoritative AST.

8. **Semantic Lowering per Paradigm** — covers how each language
   paradigm maps to the Universal AST:
   - Object-oriented (Java, PHP, Python classes) -> records + methods
   - Functional (Clojure, Haskell-style) -> direct lowering
   - Prototype/dynamic (JavaScript) -> closures + store cells
   - Systems (Go, Rust) -> explicit ownership/effects
   Note explicitly that the language list is non-exhaustive and new
   paradigm mappings are added via the SPI.

9. **Standard Library and FFI Architecture** — this section is
   MANDATORY per owner directive. Include the four-layer model:

   Layer 1 — Pure modules:
     Source in the language itself, compiled to Universal AST via Yang
     directly; no runtime special-casing.

   Layer 2 — Language intrinsics / preludes:
     A Universal AST prelude per language that seeds the initial
     environment. Compiled once, content-addressed, distributed via
     `dao.jing` DHT.

   Layer 3 — Object system desugaring:
     Class/prototype/trait hierarchies desugared to records, store cells,
     and closures. No object system concept crosses into the VM kernel.

   Layer 4 — System I/O and effects:
     System I/O expressed as Yin VM effect descriptors; host interpreters
     consume those and append outcomes. FFI calls go through
     `dao.stream.apply`. No direct host function calls from portable code.

   Module distribution: content-addressed packages in the `dao.jing` DHT.

10. **Diagnostics and Error Reporting** — qualified outcome maps; parse
    errors, semantic errors, lowering failures; structured diagnostic
    datoms on the stream.

11. **Determinism and Isolation** — no hidden global state in the
    compiler pipeline; isolation guarantees; reproducibility of
    content-addressed output.

12. **Phased Roadmap** — the architect's phased plan (Phase 0 through
    the final open-SPI phase), with completion criteria per phase.

13. **Verification Laws** — the invariants that must hold across all
    language plugins; test matrix requirements for SPI compliance.

### Faithfulness

Transcribe the architect's plan faithfully. Where the architect's text
uses first-person ("I found...", "I'll..."), convert to third-person
design document voice. Do not add opinions or architectural decisions
not present in the source plan or the standard-library discussion above.

### No Implementation

Do not implement any code. Do not edit any `.cljc` files. Only create
`docs/design/yang.antlr.md`.

## Deliverable

`docs/design/yang.antlr.md` — complete, well-structured, <= 80 column
lines throughout, pure ASCII, covering all 13 sections above.

Begin your final response exactly with:
Completed-GMT: <YYYY-MM-DD HH:MM:SS GMT>
Completed-Local: <YYYY-MM-DD HH:MM:SS +0700 ICT>
Session-ID: d5692e97-efdf-424a-91dc-9b61187d1ffa

Then confirm the file was written and give the section count and
approximate line count.
