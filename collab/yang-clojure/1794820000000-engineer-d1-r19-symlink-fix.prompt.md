Created-GMT: 2026-11-13 23:0x GMT
Created-Local: 2026-11-14 06:0x +0700
Coding-Agent: codex
Session-ID: (fresh thread on the laptop; the by-state brief carries the context)

# Task: D1 continuation r19 — fix the runtime archive's absolute symlink; continue the reconstruction package

Role: VM Engineer (compiler provenance)

Implementers:
- Model: gpt-6.1-terra | Assigned: 2026-11-14 06:0x +0700 | Status: active | Rationale: continuity

## State resume (by state — everything is in the worktree)

Worktree: `/Users/sto/workspace/datomworld-d1`, branch d1-correspondence @ `e4ac2114` + the UNCOMMITTED r9b-r18 work. Read first: the findings chain r1-r18 (`collab/yang-clojure/1794422563000-engineer-d1-correspondence-r{1..8}...findings.md`, the r9b continuation + continuation-4 findings, the r14 findings, the r15 findings, the r17 findings `1794810000000-engineer-d1-r17-reconstruction-package.gpt-6.1-terra.findings.md` — the worktree mirror), the r18 findings (`1794815000000-engineer-d1-r18-package-continuation.gpt-6.1-terra.findings.md` — the partial below checkpoint), the THIRD astra ruling (`1794805000000-architect-d1-executable-closure-adjudication.gpt-6-astra.findings.md` — RULING (other): the reconstruction-package contract with the execution-manifest component table and the §4.1 replacement text), the TWO earlier rulings that bind the design (`1794524600000-...findings.md` RULING (A); `1794422563000-architect-d1-profile-amendment-r2...findings.md` RULING (b)), the bounds-declaration DRAFT (`1794671000001-d1-bounds-declaration-draft.md`), and the cc3 v2-declaration format precedent (`1794390804000-cc3-bounded-run-declaration-v2.md`).

The r18 partial delivered: the ordered 154-entry classpath and the Java 21 runtime captured as SHA-256-addressed archives (verified by a fresh process) — BUT the runtime archive contains an ABSOLUTE SYMLINK to the original JDK location (not portable), and the dependency closure, launch configuration, durable retention, expansion applicability, and isolated replay remain.

## The work (in order; deliver the next coherent partial if the turn cannot finish all)

1. **Fix the runtime archive's absolute symlink**: per the ruling's runtime clause — "Retain the distribution bytes or an addressed file manifest sufficient to restore its layout, modes and links" — replace the absolute symlink with either the retained distribution bytes or an addressed file manifest that restores layout/modes/links without the original location. Red-first for the portability test (restore on a path where the original JDK does not exist).
2. **The dependency closure**: resolve the closure to actual artifact bytes with content identities (not just coordinates).
3. **The launch configuration**: entrypoint, ordered classpath/module path, JVM options, working-directory mapping, properties, environment values, locale/encoding, initialization rules.
4. **Durable retention**: the package in a reproducible location (the r7 proposal pattern; scratch-copy-before-open).
5. **The expansion-applicability resolution** and **the isolated replay** (the ruling's step 3 entrypoint test): fetch and verify the package, start a fresh process, reconstruct assembly and compilation, compare all claimed outputs — resolving compiler code from the PACKAGE.

If the turn cannot complete all five: deliver the next coherent partial (items 1-3 the natural checkpoint) with the same honesty standard.

## Bounds/invariants/deliverable

Bounded files: the correspondence machinery + the manifest/package/entrypoint files + tests + latte_oracle_test.clj. Forbidden set unchanged (macro.cljc, semantic.cljc, ast_walker.cljc, yang/clojure.cljc, core/prelude.cljc, dht.cljc). The v1 bytes frozen. Origins only from retained coordinates. kondo 0 errors. The full oracle suite is shiva-scale — do NOT run it.

Deliverable: `collab/yang-clojure/1794820000000-engineer-d1-r19-symlink-fix.gpt-6.1-terra.findings.md` (worktree mirror + say-so). Commit NOTHING.

Begin the final response exactly with:
Completed-GMT: <YYYY-MM-DD HH:MM:SS GMT>
Completed-Local: <YYYY-MM-DD HH:MM:SS local-timezone-name>
