Created-GMT: 2026-09-23 16:56:09 GMT
Created-Local: 2026-09-23 23:56:09 +07:00

# Task: reviewer-debruijn-type-preservation -- Adversarial Review of De Bruijn Type Preservation Migration (Contract Version 2)

Role: Adversarial Code Reviewer and Security Auditor

Implementers:
- Model: gpt-5.6-sol | Assigned: 2026-09-23 23:56:09 +07:00 | Status: active | Rationale: Independent adversarial review of Contract Version 2 AST canonicalization, scalar classification, and host boundary isolation across model families (implementer: claude-sonnet-5).

Work in /Users/sto/workspace/worktree-debruijn-type-preservation (read-only; do NOT edit files or run git add/commit).
Evaluate git diff against master (HEAD 1275a5df).

## Read First, In Full

1. `docs/design/datom.world.md`, foundational axioms and non-negotiable invariants (especially "Host Boundaries: host types stay in transforms/adapters; no host type or host quirk crosses onto the stream or contaminates universal representations").
2. `docs/design/yin.vm.debruijn-projection.md`, specifically Section 5 (Canonical value table, Contract Version 2) and Section 6.
3. `src/cljc/yin/vm/debruijn.cljc`, the modified de Bruijn projection and value table.
4. `test/yin/vm/debruijn_test.cljc`, contract tests, distinct int/double pins, and collections tests.
5. `public/chp/blog/yin-vm-vs-unison.blog`, documentation update.
6. `docs/agents/roles/reviewer.md`.

## Review Mandate & Verification Obligations

Perform an adversarial defect hunt on the diff between master and branch debruijn-type-preservation.
Treat prior reports as untrusted and cite repository evidence for every finding.

In particular, examine:

1. **Contract Version 2 & Disjoint Numeric Types:**
   - Are `:int64` and `:double` strictly disjoint on JVM and Dart?
   - Does `descriptor` define `(def contract-version 2)` and include `[:yin.debruijn/dimension :dim/contract-version 2]`?
   - Does `canonical-value-table` set `:integral-double-folding false` and remove `:int64-integral-double-collision` from `:declared-limits`?
   - Is there any silent regression where integral doubles (e.g. `1.0`) fold into integer (`1`) on JVM or Dart?

2. **Host Boundary Isolation & JS Quirk Confinement:**
   - In JavaScript, runtime numbers cannot natively distinguish `1` from `1.0`.
   - Does `js-number-class` confine this limitation strictly to the CLJS host adapter?
   - Does any host quirk cross onto the universal projection stream or contaminate JVM/Dart canonicalization?
   - Are safe integers, non-integral doubles, and unsafe integers classified soundly on CLJS?

3. **Merkle Fingerprints, Hashing, & Collection Boundaries:**
   - Do `1` and `1.0` project to distinct Merkle fingerprints and distinct records on JVM and Dart?
   - Do `{1 :a, 1.0 :b}` and `#{1 1.0}` maintain distinct keys and elements under `canonical-value` without collision or count changes?
   - Are the pinned descriptor hash (`22f16c962e83cd5e683c2d5fc32847b49974280749fcf123d88398d0ca0abbda`) and essay fingerprint (`88f891572e7f151cfc46363b903a95bbc2fd2d3a2fcd8470467efc6eda9f333a`) mathematically sound and stable?

4. **Code Quality, Formatting, & Portability:**
   - Pure ASCII only (verify absence of non-ASCII unicode characters in diff).
   - Line length strictly <= 80 columns across all modified files.
   - Zero Kondo warnings (`clj -M:kondo`).
   - `cljstyle check` clean.
   - Note: The orchestrator ran the local test matrix and verified 0 failures across JVM (`bb test:clj`: 1,940 tests, 179,276 assertions), Node/CLJS (`bb test:cljs`: 1,856 tests, 46,433 assertions), and ClojureDart (`bb test:cljd`: 1,818 tests passed). Focus your budget on static analysis, contract soundness, edge cases, and architectural regressions.

Begin your final response exactly with:
Completed-GMT: <YYYY-MM-DD HH:MM:SS GMT>
Completed-Local: <YYYY-MM-DD HH:MM:SS local-timezone-name>

Report actionable findings as:
P0-P3 | file:line | evidence | concrete fix
State "No actionable findings" when appropriate.
Follow with your verdict: [APPROVED | REQUEST CHANGES] and a summary rationale.
