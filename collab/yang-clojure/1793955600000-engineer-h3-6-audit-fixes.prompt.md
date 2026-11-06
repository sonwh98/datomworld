You are an expert Clojure engineer tasked with fixing two specific issues identified in an independent audit (`collab/yang-clojure/1793953025937-reviewer-h3-6-audit.gpt-6.1-sol.findings.md`) for the `yang.clojure` differential oracle test harness (`test/yang/latte_oracle_test.clj`).

### The Two Audit Findings to Fix

1. **P1: Deterministic Canonical Source Hashing across Fresh Processes (`test/yang/latte_oracle_test.clj:2404-2415`)**
   - **Problem**: `kernel-canonical-source-hash` computes SHA-256 over `(pr-str [forms call])`. The forms loaded from `latte_kernel.nbe` contain syntax-quote auto-gensyms (`p1__<num>#`, `p2__<num>#`). The reader's auto-gensym counter increments across calls and starts from different values across runs/processes, causing `(pr-str [forms call])` to vary. 60 of 155 fixtures change their hash on a fresh run, triggering `:refused-by-hash` and dropping credited arities from 165 to 46 when checked cold.
   - **Requirement**: In `kernel-canonical-source-hash`, normalize auto-gensyms in `[forms call]` before serialization and hashing.
     - Specifically, replace reader-generated auto-gensym symbols (e.g. matching `#"^([a-zA-Z0-9_.-]+)__\d+(?:__auto__)?#?$"` or similar Clojure syntax-quote / gensym patterns) with deterministic positional or canonical names (e.g. `$gensym_1`, `$gensym_2` based on their first appearance order in the form tree).
     - Ensure that reading the same file in fresh processes produces the exact same canonical-source-hash.
     - Add a regression test verifying that evaluating the hash on fresh repeated reads of `latte_kernel.nbe` forms yields identical hashes.

2. **P2: Case 6 Dynamic-Site Refusal Assertion Execution (`test/yang/latte_oracle_test.clj:3090-3100`)**
   - **Problem**: Case 6 tests that an entry through an indirect site refuses attribution unless site-linked. It uses fixture `f2` (`:h3-3-registered-definition-ambient`), which has zero dynamic sites in its site table. As a result, `(when dynamic ...)` never evaluates, silently skipping the refusal assertion.
   - **Requirement**: Use a fixture that actually contains an executed indirect call with a `:dynamic` callee in its site table (for instance, `:h3-3-unregister-unparser-absent` or `:beta-eq-false`).
   - Ensure the assertion asserts `(some? dynamic)` and tests `(is (not (kernel-record-credits? ...)))` unconditionally.

### Operational Guidelines & Constraints
- Work strictly in `test/yang/latte_oracle_test.clj`.
- Do not make breaking architectural changes to the compiler or kernel protocols.
- Once the canonicalization is implemented and tests pass, update or provide instructions to regenerate `test/yang/latte_oracle/slice9_site_tables.edn` and `test/yang/latte_oracle/slice9_evidence.edn` so all 165/165 arities remain credited deterministically.
- Output your analysis, proposed diff, and verification results cleanly.
