Created-GMT: 2026-11-13 23:5x GMT
Created-Local: 2026-11-14 06:5x +0700
Coding-Agent: codex
Session-ID: (fresh thread on the laptop; the by-state brief carries the context)

# Task: D1 continuation r27 — the launch configuration, the complete dependency closure, durable retention, the expansion-applicability resolution, and the isolated replay

Role: VM Engineer (compiler provenance)

Implementers:
- Model: gpt-6.1-terra | Assigned: 2026-11-14 06:5x +0700 | Status: active | Rationale: continuity

## State resume (by state — everything is in the worktree)

Worktree: `/Users/sto/workspace/datomworld-d1`, branch d1-correspondence @ `e4ac2114` + the UNCOMMITTED r9b-r26 work. Read first: the findings chain r1-r26 (`collab/yang-clojure/1794422563000-engineer-d1-correspondence-r{1..8}...findings.md`, the r9b continuation + continuation-4 findings, the r14 findings, the r15 findings, the r17 findings, the r19 findings, the r20 findings, the r21 findings, the r22 findings, the r23 findings, the r24 findings, the r25 findings, the r26 findings `1794815000000-engineer-d1-r18-package-continuation.gpt-6.1-terra.findings.md` — the worktree mirror), the THIRD astra ruling (`1794805000000-architect-d1-executable-closure-adjudication.gpt-6-astra.findings.md` — RULING (other): the reconstruction-package contract with the execution-manifest component table and the §4.1 replacement text), the TWO earlier rulings that bind the design (`1794524600000-...findings.md` RULING (A); `1794422563000-architect-d1-profile-amendment-r2...findings.md` RULING (b)), the bounds-declaration DRAFT (`1794671000001-d1-bounds-declaration-draft.md`), and the cc3 v2-declaration format precedent (`1794390804000-cc3-bounded-run-declaration-v2.md`).

r26 delivered: the snapshot-probe inputs packaged into a 427 MiB content-addressed scratch package; a separate process copied and verified its 152 objects and five launch files, then ran the package-local guarded test (1 test, 6 assertions, no failures or errors). The package remains under target/ with no retention owner; filesystem and network access were not isolated; the test generated a fresh admission and its expected results in the same process. The package-pinned expansion determination and retained-input replay remain unfinished.

## The work (in order; deliver the next coherent partial if the turn cannot finish all)

1. **The launch configuration**: entrypoint, ordered classpath/module path, JVM options, working-directory mapping, relevant properties, environment values, locale/encoding, initialization rules — per the ruling's launch-configuration component. Inputs affecting results explicit; undeclared ambient inputs disallowed.
2. **The complete dependency closure**: resolve the closure to completion — the r22 empirical result (the reconstruction works without the generated-parser directories and the Netty natives) is the necessity evidence; record it and complete the closure to what the reconstruction actually requires.
3. **Durable retention**: the package in a reproducible location (the r7 proposal pattern; scratch-copy-before-open).
4. **The package-pinned expansion determination**: the r23 probe's throw-arm test established the bypass empirically on the local path — convert it to the package-pinned determination (the declared composition + the checked execution path substantiate the bypass, per the ruling's §4 clause), or capture the actual expander context.
5. **The isolated replay** (the ruling's step 3 entrypoint test): fetch and verify the package, start a fresh process, reconstruct assembly and compilation, compare all claimed outputs — resolving compiler code from the PACKAGE, not the invoking checkout.

If the turn cannot complete all five: deliver the next coherent partial (items 1-3 the natural checkpoint) with the same honesty standard.

## Bounds/invariants/deliverable

Bounded files: the correspondence machinery + the manifest/package/entrypoint files + tests + latte_oracle_test.clj. Forbidden set unchanged (macro.cljc, semantic.cljc, ast_walker.cljc, yang/clojure.cljc, core/prelude.cljc, dht.cljc). The v1 bytes frozen. Origins only from retained coordinates. kondo 0 errors. The full oracle suite is shiva-scale — do NOT run it.

Deliverable: `collab/yang-clojure/1794920000000-engineer-d1-r27-launch-closure-retention-expansion-replay.gpt-6.1-terra.findings.md` (worktree mirror + say-so). Commit NOTHING.

Begin the final response exactly with:
Completed-GMT: <YYYY-MM-DD HH:MM:SS GMT>
Completed-Local: <YYYY-MM-DD HH:MM:SS local-timezone-name>
