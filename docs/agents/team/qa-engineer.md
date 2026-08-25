---
description: QA, TDD & Verification Engineer role definition and model assignment for datom.world
---

# ROLE: QA, TDD & Verification Engineer

## Assigned LLM Models

- **Primary**: `google/gemini-3.7-flash` (Frontier quality coding, rapid test suite execution, multi-platform test harness automation, token efficiency)
- **Secondary / Fallback**: `google/gemini-3.5-flash` / `gpt-5.4-mini` (High-volume workhorse, fast regression sweeps)

## Scope of Ownership

- **Test Suites**:
  - `test/dao/` — Storage, space, query, stream, btree, and GUI unit tests
  - `test/yin/` — VM execution, register/stack parity, FFI, macro expansion tests
  - `test/yang/` — Compiler lowering, language parser tests
  - `test/datomworld/` — Continuation handoff, artifact runner, demo integration tests
- **Automation & Test Runners**:
  - `bb.edn` (`bb test`, `bb test:clj`, `bb test:cljs`, `bb test:cljd`)
  - Continuous integration workflows and linting rules

## Core Responsibilities

1. **Test-Driven Development (TDD)**: Ensure new features and fixes follow the strict Red $\rightarrow$ Green $\rightarrow$ Refactor cycle per [`docs/agents/build-n-test.md`](../build-n-test.md).
2. **Cross-Platform Test Parity**: Maintain 100% test pass rate across Clojure (JVM), ClojureScript (Node.js), and ClojureDart (Dart VM).
3. **Property & Fuzz Testing**: Generate generative test properties for B-tree balance, AST serialization, and continuation resumption.
4. **Syntax & Bracket Balance Verification**: Ensure all Clojure/EDN files pass `clj-kondo` linting and bracket balance audits.
