Created-GMT: 2026-11-18 (scope draft)
Coding-Agent: interactive (ZCode seat, yang.php orchestrator)
Status: SCOPE DRAFT — dispatch after evalb-r18 lands

# The dart leg: cross-host acceptance for the eval-bridge / ticks fixtures

## What is JVM-first today
- The P7-b ticks fixtures and the P6 linked include/const fixtures run on the JVM lane; the
  sol/astra rulings' contracts are lane-agnostic, but no dart-lane evidence exists for the
  linked composition path (SCC bundle -> alias link -> include delivery; the constant table).
- The dyn producer fix touches prelude.cljc (shared cljc) — the dart lane compiles it; a cljd
  compile break is the cheap first check (the 5ae34f5c lesson: a missing :cljd branch crashed
  the cljd compile of lower.cljc).

## The acceptance question
Does the linked composition (publication -> SCC -> link -> include; constants; the eval
service path once r18 lands) behave identically on the dart backend? The vms are shared cljc
(so semantic/stack/register run under cljd), but the harnesses are .clj today.

## Scope of a dart-leg round (when dispatched)
1. cljd compile of the touched namespaces (cheap, catches reader-conditional gaps first).
2. The shared-fixture JVM-free subset: fixtures that do not depend on the JVM-only harness —
   port the harness seam or gate the dart rows with #?(:cljd nil :default ...) shapes per the
   4d0d3e70 precedent.
3. Acceptance bar: the same ["ab" [] nil] / constant rows on all three backends under cljd,
   on one remote host (shiva or jupiter, one dart lane per host, mise flutter current checked).
4. Non-goals: no new dart-only features; no manifest changes; the jolt lane is out of scope.

## Prerequisites
- evalb-r18 landed (the harness seam may move).
- The 2026-11-06 note: the nightly cron entries are REMOVED; re-arming is one crontab line —
  not this round's job, but the dart leg should not rely on the nightly for its evidence.
