Created-GMT: 2026-09-16 07:47:41 GMT
Created-Local: 2026-09-16 14:47:41 +07 (Asia/Ho_Chi_Minh)
Coding-Agent: glm
Session-ID: e530f3f4-2cca-4c59-a0d0-8165a02c9fff

# Task: Fix the missing ClojureDart `measure-ms` in the semantic-VM bench

Role: Implementer

Implementers:
- Model: glm-5.3 | Assigned: 2026-09-16 14:47:41 +07 | Status: active | Rationale: small, mechanical, single-file fix; no architectural judgment required

## Context

`test/bench/yin_vm_bench.cljc` (untracked, never committed) is a
three-host micro-benchmark comparing the AST-walker and semantic-VM
evaluators (`docs/design/yin.vm.semantic.md` §8). It has been blocking
full-suite `bb test:cljd` runs tonight with a compile error, worked
around only by running scoped per-namespace CLJD tests instead
(`docs/orchestrator-log.md`, entries referencing "unrelated `measure-ms`
compile error").

Root cause, already diagnosed, re-verify before changing anything: the
file defines `measure-ms` across two separate `#?()` reader-conditional
top-level forms —

```clojure
#?(:cljd nil
   :clj
   (defn- measure-ms ... criterium ...))

#?(:cljd nil
   :clj nil
   :cljs
   (defn- measure-ms ... js/performance ...))
```

Both forms put `nil` in the `:cljd` branch — so on ClojureDart,
`measure-ms` is never defined at all, and `run-bench` (which calls
`(measure-ms loaded)` unconditionally at line ~113) fails to compile.
The ns form already imports `["dart:core" DateTime]` under `:cljd`
(line ~24), suggesting a Dart implementation was planned but never
written.

**Cross-host reader-conditional trap** (project memory, verify this
applies before you touch anything): on this project's ClojureDart build,
a `#?(:clj ...)`-only conditional (no `:cljd` branch) does NOT reliably
exclude the form from the cljd build — the host-eval pass sees `:clj`
too. The safe pattern is always an explicit `:cljd` branch, and when
`:cljd` should produce nothing, it must be the literal `nil` branch
placed in FIRST position (a `:cljd` branch in tail position has silently
failed to take effect before in this codebase). **Do not restructure
these two forms into fewer forms or reorder branches** — keep the
existing two-form, `:cljd`-branch-first shape, and only fill in the
`:cljd nil` placeholders with real implementations.

## Task

1. Read the file in full first.
2. Replace the first form's `:cljd nil` with a real Dart implementation
   of `measure-ms` matching the `:clj` branch's contract (single-call
   Criterium-style timing is not available on Dart — instead mirror the
   sampling discipline already used in the `:cljs` branch: 4 warmup
   samples, then 9 samples, each sample = 20 runs of `(vm/run loaded)`,
   returning mean ms/run). Use `DateTime/now` and
   `.-millisecondsSinceEpoch` (or an equivalent Dart-idiomatic
   millisecond clock read) for timing — the ns form already imports
   `DateTime` for this purpose.
3. Leave the second form's `:cljd nil` alone if your first-form fix
   fully satisfies `run-bench`'s single unconditional call to
   `measure-ms` per host — there should only be ONE real `measure-ms`
   definition per host after your fix. Re-check this: currently `:clj`
   is defined in form 1 and `:cljs` in form 2, with `:cljd nil` in both.
   Your fix must result in exactly one `:cljd` implementation existing,
   with no duplicate-def risk across the two forms.
4. Do not touch the `:clj` or `:cljs` branches, `check!`, `run-bench`,
   `tail-countdown-ast`, `load-walker`, `load-semantic`, or the ns form,
   except if the ns form's `:cljd` import needs a companion import you
   discover is actually required (verify against real Dart/ClojureDart
   `DateTime` usage before adding anything).
5. Verify: run `bb test:cljd` (full suite) and confirm it now compiles
   and runs past this file without the prior `measure-ms` compile error.
   Bench files are not part of `test/` proper in most Clojure repos —
   confirm whether this file is even picked up by `bb test:cljd`'s
   namespace discovery at all; if it is NOT picked up (i.e. the prior
   "blocking" claim in the orchestrator log turns out to be about a
   compile pass that includes `test/bench` for some other reason, e.g.
   `clj -M:cljd compile` scanning all of `test/`), say so explicitly
   rather than assuming — this affects whether the fix actually resolves
   the blocker. Also run `clj -M:kondo --lint test/bench/yin_vm_bench.cljc`.
6. Do not stage or commit. Do not touch any other file.

## Deliverable

Report back: the exact diff, why `:cljd`'s `measure-ms` was actually
unreachable before (confirm or correct the root-cause diagnosis above),
the verification commands run and their exact output/assertion counts,
and whether `test/bench/yin_vm_bench.cljc` is actually included in
`bb test:cljd`'s run (not just `clj -M:cljd compile`) — this determines
whether this fix is a precondition for full-suite CLJD verification or
a separate, lower-priority correctness fix to an unused bench file.
