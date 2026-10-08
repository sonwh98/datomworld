Created-GMT: 2026-09-23 17:39:00 GMT
Created-Local: 2026-09-24 00:39:00 +07:00

# Task: debruijn-architect-reconciliation -- Reconcile Lead System Architect Review Findings

Role: Yang Compiler and Universal AST Engineer

Implementers:
- Model: claude-sonnet-5 | Assigned: 2026-09-24 00:39:00 +07 | Status: active | Rationale: Address Lead System Architect [REQUEST CHANGES] findings on de Bruijn type preservation migration.

Work in /Users/sto/workspace/worktree-debruijn-type-preservation (branch debruijn-type-preservation). Do NOT stage, commit, merge, or push.

---

## Background & Architect Review Verdict

The Lead System Architect (`gpt-6-astra`) reviewed the branch and issued a `[REQUEST CHANGES]` verdict with three specific findings:

| Severity | File:line | Finding | Required Correction |
|---|---|---|---|
| Medium | `debruijn.cljc:91` | **Retained host-boundary violation:** `canonical-value-table` still contains `:numbers :javascript` safe-integer policy. The descriptor embeds this table, so JS adapter policy participates in every host's dimension identity. Isolation is therefore incomplete despite moving classification into `js-number-class`. | Move JS admission policy outside the canonical descriptor `canonical-value-table`. Keep descriptor universal. Recompute dimension hash and essay fingerprint fixtures, retaining contract version 1. |
| Medium | `debruijn.cljc:1339` | **Foreign-type refusal contract is unmet:** A JVM-generated literal-double `1.0` record becomes `:int64` when read in Node and produces `:hash-mismatch`, rather than the promised `:unsupported-value`. Rejection prevents corrupted acceptance, but loses the distinction between unsupported representation and invalid content. | Preserve incoming numeric class through host decoding/admission; reject unsupported classes there with `:unsupported-value`. Add JVM-to-Node fixtures for integral doubles, including nested values. |
| Low | `yin.vm.debruijn-projection.md:196` | **Doc typo:** Documentation names `:yin.debruijn/contract-version`; the descriptor correctly uses `:dim/contract-version`. | Correct the documented attribute in `docs/design/yin.vm.debruijn-projection.md`. |

---

## Detailed Requirements

### 1. Fix Finding 1: Universal Canonical Value Table (debruijn.cljc:91)
- In `canonical-value-table` in `src/cljc/yin/vm/debruijn.cljc`:
  Remove `:javascript {:int64 :safe-integer-only, :unsafe-integer :diagnostic}`.
  Keep `:numbers {:out-of-domain [:bigint :ratio :char :other-numeric]}` or equivalent universal definition.
  JS safe-integer rules belong strictly in the CLJS host adapter (`js-number-class`, adapter constants, docstrings), never in the universal dimension descriptor.
- Maintain `(def contract-version 1)` and `[:yin.debruijn/dimension :dim/contract-version contract-version]`.
- Recompute the canonical `dimension-hash`: `(jing/sha256 (encode-value descriptor))`.
- Recompute the essay fingerprint: `(:fingerprint (project worked-example))`.
- Update the pinned hashes in `test/yin/vm/debruijn_test.cljc` and verify they match across all hosts.

### 2. Fix Finding 2: Foreign-Type Refusal Contract at Host Boundary (debruijn.cljc:1339)
- Section 5 of `docs/design/yin.vm.debruijn-projection.md` documents:
  "A foreign record claiming a class the host cannot represent is refused at the boundary with `:unsupported-value`."
- Address the cross-host decoding/admission gap:
  When reading or admitting incoming records or datoms on Node/CLJS, if an incoming literal or value represents/claims a class the host cannot represent without distortion (specifically, an integral double such as `1.0`, whether bare or nested in maps/sets/vectors), it must be refused at the host admission/reader boundary with `{:rule :unsupported-value}` rather than falling through to `:hash-mismatch`.
- Add test fixtures and tests in `test/yin/vm/debruijn_test.cljc` verifying:
  - JVM-generated `1.0` records/datoms admitted on Node/CLJS diagnose `{:rule :unsupported-value}`.
  - Nested values (e.g. `[1.0]`, `{:a 1.0}`, `#{1.0}`) are also caught and refused with `:unsupported-value`.
  - On JVM and Dart, they continue to be fully supported and project/read correctly.

### 3. Fix Finding 3: Correct Documentation Attribute Name
- In `docs/design/yin.vm.debruijn-projection.md` line 196:
  Update `:yin.debruijn/contract-version` to `:dim/contract-version`.

### 4. Non-Negotiable Quality Standards
- Pure ASCII (no non-ASCII unicode characters).
- All lines strictly <= 80 columns.
- Zero clj-kondo warnings/errors (`clj -M:kondo`).
- Formatted with `cljstyle`.
- Passing tests across JVM and Node (`bb test:clj`, `bb test:cljs`).

---

## Output Format
Begin your final response exactly with:
```text
Completed-GMT: <YYYY-MM-DD HH:MM:SS GMT>
Completed-Local: <YYYY-MM-DD HH:MM:SS local-timezone-name>
Coding-Agent: claude
Session-ID: <session-id>
```
Then summarize changed files, new pinned hashes, test results, and how each finding was addressed.
