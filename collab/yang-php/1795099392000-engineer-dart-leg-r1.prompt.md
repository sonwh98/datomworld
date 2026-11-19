Created-GMT: 2026-11-19 (dispatch time)
Coding-Agent: codex (gpt-6.1-terra, jupiter POOL 2)
Session-ID: 01a1f443-2e10-7390-84fc-ef315cefb3c6 (codex thread.started, jupiter POOL 2)

# Task: dart-leg r1 — cross-host acceptance for the linked fixtures on the dart backend

Worktree: ~/ci/dw-dartleg (the seat pre-creates it from origin/master; branch
feat/dart-leg-linked-fixtures). No commits — leave edits, the seat publishes.
Scope draft: collab/yang-php/<see origin>/orchestrator-dart-leg-scope-draft.md (filed on
origin/master — read it).

## The acceptance question
The linked composition (publication -> SCC bundle -> alias link -> include delivery;
constants; the eval service path) has JVM-lane evidence; the dart backend compiles the same
shared cljc. Prove what the dart lane can run and what its acceptance rows are.

## Steps (the scope draft's order — follow it)
1. **cljd compile check (cheap first)**: compile the touched namespaces
   (yang.php.antlr.lower, prelude, siteset, eval_* — everything the recent landings touched)
   with `clojure -M:clojuredart:cljd compile <namespaces>`. A reader-conditional gap crashes
   here (the 5ae34f5c/795f8d85 precedent: a missing :cljd branch crashed lower.cljc's compile).
   Record every failure with the exact reader-conditional fix needed.
2. **The harness seam**: the linked-fixture harnesses are .clj (JVM-only). Port the minimal
   seam to shared cljc (or gate dart rows with the #?(:cljd nil :default ...) shape per the
   4d0d3e70 precedent — dart-gate-shape-fix is the landed precedent for the conversion).
   Target: the include/const/dyn rows run on dart, JVM-only plumbing stays JVM.
3. **Acceptance bar**: the fixture rows (mutual-include inactive+active ["ab" [] nil], the
   const rows per the ruling's decision table, the dyn rows) green on the dart lane on THIS
   host (mise flutter current; one dart lane per host; check `mise ls flutter` if the compile
   dies in dart:ui).
4. **Non-goals**: no dart-only features; no manifest changes; the jolt lane out of scope;
   the eval service path's dart acceptance is a SEPARATE later unit (the harness is
   synchronous-JVM today) — just record what compiles.

## Gates and custody
- bb src/dev/cljd_agg.clj [--slow-only] for the dart lane; --slow-regex for the namespace list.
- rm -rf .clojuredart test/cljd-out lib/cljd-out build/cljd-agg* before proving a checkout
  took effect (the stale-cache lesson).
- Per-run log custody in target/dartleg-r1/.
- Findings: collab/yang-php/<ts>-engineer-dart-leg-r1.gpt-6.1-terra.findings.md (worktree root
  if blocked): compile results, the seam diff, per-row dart evidence, what remains for full
  cross-host acceptance. No commits.
