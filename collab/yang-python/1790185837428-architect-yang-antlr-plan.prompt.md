Created-GMT: 2026-09-23 17:54:00 GMT
Created-Local: 2026-09-24 00:54:00 +07:00

# Task: yang-antlr-stream-plan -- Architectural Plan for Extensible ANTLR-Powered Multi-Language Compilation in Yang Composable with DaoStream

Role: Lead System Architect

Implementers:
- Model: gpt-6-astra | Assigned: 2026-09-24 00:54:00 +07:00 | Status: active | Rationale: Architectural blueprint for an open, extensible ANTLR frontend framework in the Yang compiler, compiling arbitrary languages (with Java, Python, PHP, JavaScript as primary exemplars) into Universal AST via dao.stream.

Draft a comprehensive, rigorous Architectural Plan for integrating ANTLR into the Yang compiler to compile programming languages into Yin VM's Universal AST, ensuring clean composability with `dao.stream`.

**CRITICAL DIRECTIVE FROM REPOSITORY OWNER:**
The list of languages (Java, Python, PHP, JavaScript) is **NON-EXHAUSTIVE**.
The architecture must **NOT** be a hardcoded, ad-hoc 4-language silo. It must be an **open, extensible multi-language compiler framework** where any language with an ANTLR grammar (e.g. Go, Rust, Ruby, C, TypeScript, Kotlin, SQL) can be plugged in as a modular grammar-and-lowering frontend adapter.

Read first:
- docs/design/datom.world.md (Foundational axioms, 6 non-negotiable invariants, host boundaries)
- docs/design/dao.stream.md (Everything is a Stream, passive IO substrate, handle/cursor/interpreter model, outcome maps)
- docs/design/yin.vm.code-as-tuples.md
- src/cljc/yang/docs/yang_implementation.md
- src/cljc/yang/clojure.cljc
- src/cljc/yang/python.cljc
- src/cljc/yang/php.cljc
- src/cljc/yin/vm.cljc
- docs/agents/roles/architect.md

---

## Architectural Challenges & Invariants

### 1. Extensible Multi-Language Framework Architecture (Open SPI)
- Define the generalized Yang Language Frontend SPI/contract:
  a) How language frontends register grammars, lexer/parser entry points, and dialect options.
  b) How grammar definitions (`.g4`) and generated parsers are packaged and loaded across hosts.
  c) The contract for CST-to-Universal-AST lowering (e.g., declarative tree-walk / pattern-matching / visitor protocols).
  d) How new languages (e.g. Go, Rust, Ruby) can be onboarded modularly without modifying Yang core compiler code.

### 2. Architectural Invariants & Host Isolation
- Under `datom.world.md`, host quirks and host libraries must never contaminate universal representations or stream payloads.
- ANTLR (ANTLR4) has different runtime libraries on different platforms (Java on JVM, JavaScript/TypeScript on Node, Dart on ClojureDart).
- Address:
  a) Should ANTLR run natively per host, or should parsing be treated as a compilation service / adapter emitting pure data into `dao.stream`?
  b) How does the architecture maintain tri-host portability (JVM, CLJS, CLJD) without requiring heavy native bindings on every host?
  c) How does grammar generation keep pure ASCII and strict licensing hygiene?

### 3. Deep Composition with `dao.stream`
- The user explicitly requires: **"it must be composable with dao.stream"**.
- Address:
  a) **Source Ingestion Stream**: How source text enters `dao.stream` (chunked streams, file-backed streams, interactive REPL streams).
  b) **Stream Interpretation Pipeline**: How does parsing fit `dao.stream`'s passive substrate and non-blocking interpreter model?
     - Source Stream -> [Parse/CST Event Interpreter] -> [Lowering Interpreter] -> Universal AST Datoms.
  c) **Payload Representation on Stream**: What does each stage emit onto `dao.stream`?
     - Align with "derive, don't persist" rule and Universal AST datom schemas.
  d) **Non-blocking / Explicit State**: How does streaming compilation avoid blocking, inversion of control, and raw callbacks?

### 4. Universal AST Semantic Lowering Across Diverse Paradigms
- Formulate the universal semantic lowering strategy across major paradigm families, using Java, Python, PHP, and JavaScript as primary pilot exemplars:
  - **Class-based OOP / Static Typing (e.g. Java)**: Classes, interfaces, methods, static members, typed variables, inheritance/dispatch.
  - **Dynamic Indentation / Scripting (e.g. Python)**: Indentation blocks, dynamic scope, comprehensions, decorators, generators.
  - **Dynamic Web Scripting (e.g. PHP)**: Mixed HTML/code, associative arrays/hashes, `$var` scopes, superglobals.
  - **Prototype / Event-Driven (e.g. JavaScript)**: Closures, arrow functions, `var`/`let`/`const`, prototype chains, async/await.
  - **System / Value-Semantics (e.g. Go, Rust - future extensions)**: Structures, pointers/references, pattern matching.
- Address where language runtime semantics (object systems, dynamic dispatch, runtime builtins) live: in compiler desugaring, runtime prelude libraries, or Yin VM FFI/effects.

### 5. Diagnostics & Error Handling
- ANTLR's default error recovery prints to stderr and throws exceptions.
- How are syntax errors, lexer errors, and lowering failures captured deterministically as plain-data diagnostic datoms/outcomes conforming to `dao.stream` result conventions?

### 6. Phased Implementation Roadmap
- Propose a phased, testable engineering sequence:
  - Phase 0: Yang Frontend SPI, ANTLR runtime boundary, and `dao.stream` compiler pipeline contract.
  - Phase 1: Reference Pilot 1 (Python / JS - upgrading dynamic frontends).
  - Phase 2: Reference Pilot 2 (Java - static typed / class-based OOP frontend).
  - Phase 3: Reference Pilot 3 (PHP - associative array / web scripting frontend).
  - Phase 4: Language Extension SDK & Documentation for 3rd-party languages.
  - Phase 5: Multi-host verification & `dao.stream` integration benchmarks.

---

## Output Format
Begin the final response exactly with:
```text
Completed-GMT: <YYYY-MM-DD HH:MM:SS GMT>
Completed-Local: <YYYY-MM-DD HH:MM:SS local-timezone-name>
```
Then provide the full Architectural Plan in Markdown. Do not edit repository files.
