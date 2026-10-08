Created-GMT: 2026-09-10 06:20:54 GMT
Created-Local: 2026-09-10 13:20:54 +0700 (Asia/Bangkok)
Coding-Agent: codex
Session-ID: pending (provider-generated)
# Task: adversarial review of the revised yin.vm-consumers.implementation-plan.md
Role: Adversarial Review
Implementers:
- Model: gpt-6-astra | Assigned: 2026-09-10 13:20:54 +0700 | Status: active | Rationale: independent defect discovery on a large destructive deletion plan, different model family from the Claude-family author

## Context

`docs/design/yin.vm-consumers.implementation-plan.md` has just been
rewritten by an Architect round (claude-fable-5-1), promoted to that path.
Read the file now — it is the deliverable under review. It plans deleting
the experimental v1 VM models (`yin.vm.{semantic,register,stack,space}`),
the macro engine (`yin.vm.macro`), and eight consumer files across
src/test/deps.edn/docs, with six files migrated instead of deleted so the
build stays green, in one commit (D5).

The prior draft of this plan named only 2 files needing a reference check;
this round's consumer census claims 28. This is a big, destructive,
cross-host (clj/cljs/cljd) change and nothing has been deleted yet — no
implementation has started. Your job is to find defects in the plan itself
before it is executed, not to review a diff.

## What to challenge

- **Completeness of the census.** Run your own sweep (the plan gives one at
  Phase 0 step 1 — run it, and also try variants: namespace-qualified
  symbol usage without a matching require line, e.g. dynamic requires,
  `resolve`, `requiring-resolve`, docstrings/comments that alias differently,
  `:require-macros` in cljs, generated/emitted Dart under `lib/cljd-out` or
  `.shadow-cljs` that might indicate a live compiled consumer this grep-based
  approach would miss). Is any file that requires a deleted namespace absent
  from the plan's tables?
- **Each [J] judgment call** (D1-D4, and the per-row **[J]** marks in the
  census tables): is the evidence cited actually true, and does it actually
  support the disposition chosen? In particular:
  - D1 claims v1 `yin.repl` has live consumers with no v2 twin (Flutter
    widget, both telemetry servers) and therefore should be migrated, not
    deleted, here. Verify those consumer files actually require v1
    `yin.repl` and have no v2 counterpart.
  - D2/D3 claim several files (`continuation_handoff.cljc`, `demo.clj`, three
    cljs demos) have "twins" already wired in and are safe to delete outright.
    Verify each named twin exists, is wired into the build/picker as claimed,
    and is not itself missing something the v1 file uniquely provided (the
    plan admits this is unverified for the cljs demos and defers it to Phase
    0 step 2 — is deferring that check to implementation time, rather than
    resolving it now, an acceptable risk for a plan this size?).
- **The dependency-order argument** (macro as a "leaf below the VMs",
  collapsing two phases into one). Does deleting all 24+ files and migrating
  6 more in a single commit actually leave the build green at every
  intermediate state a reviewer or bisector might land on, or only at the
  final state? Is one giant commit the right call here, or does it trade
  bisectability for expedience in a way worth flagging?
- **The completion criteria and Phase 0 pre-checks** — are they sufficient
  to actually catch a missed consumer or a feature gap in a `-v2` twin
  before something breaks, or do they read well but not actually bind?
- **The Boundary section's claim** about what's left owed and to whom —
  does it accurately reflect `dao.runtime.implementation-plan.md` and
  `dao.stream.md` as they currently stand, or does it repeat the original
  draft's mistake of asserting scope without checking the source documents?

Read-only: Read and read-only Bash (grep/find/git diff/git status). Do not
edit anything — this plan has not been executed and nothing should change
on disk from this review.

Report file/line-anchored findings, most severe first. If the plan is
sound, say so plainly rather than padding with speculative nitpicks; a
clean verdict on a well-built plan is a legitimate outcome.
