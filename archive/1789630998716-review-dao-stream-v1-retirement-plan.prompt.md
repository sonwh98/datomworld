Created-GMT: 2026-09-17 07:43:18 GMT
Created-Local: 2026-09-17 14:43:18 +0700 (Asia/Ho_Chi_Minh)
Coding-Agent: agy
Session-ID: 83ed7b39-c4f2-42dd-8896-107ce34ffd9f

# Task: Adversarial review of docs/design/dao.stream.v1-retirement.implementation-plan.md

Role: Adversarial Review

Implementers:
- Model: gemini-3.1-pro-high | Rationale: cross-family review of a Claude-family architect draft

## Context

This is a brand-new implementation plan (not yet committed, working-tree
file), the first draft of the last retirement plan in a series (v1 VM, v1
REPL/telemetry, v1 scheduler already done; this is v1 `dao.stream` itself).
It gates a rename wave four other design docs already agreed to
(`dao.stream` -> `dao.stream`, and four sibling `.v2` namespaces with
it) once the last v1 consumer migrates.

Read `docs/design/dao.stream.v1-retirement.implementation-plan.md` in full.
For grounding on what v2 promises and forbids, read `docs/design/
dao.stream.md` in full — it is the authority the plan claims to be
subordinate to. Skim `docs/design/yin.vm.v1-retirement.implementation-plan.md`
and `docs/design/dao.runtime.implementation-plan.md` for the established
structure/tone this plan is following.

The orchestrator independently spot-checked several of the plan's
load-bearing factual claims before commissioning this review and confirmed
them: the v1 implementation file count (21 files, 4354 lines); that
`yin.vm.engine`'s `module` require is `yin.vm.module`, not v1
`yin.module` (i.e. the plan's claim that v1 `yin.module`'s only readers are
its own writers plus `test/yin/module_test.cljc` holds — a different test,
`test/yin/vm/module_test.cljc`, covers the unrelated v2 registry and is
not a v1 consumer); `agent.tools`' only `src/` consumer is
`agent/tzu.cljc:5`; `flutter.cljd:361` and `web.cljs:82` do call
`terminal/bind-stream!`; `datomworld.continuation-transport`'s only
consumer is its own test, with `continuation_transport.cljc` as a
separate live v2 twin; the telemetry viewer requires v1 `dao.stream`/`apply`/
`ws`, fabricates `{:position 0}` cursors, and dials ports 8090/8091, whose
servers (`src/clj/yin/vm/telemetry_server/*`) are confirmed absent from the
tree; `dao.jing.file`'s docstring does say "no dao.stream namespace is
required"; the `:bench`, `:ws-client-demo`, `:telemetry-viewer` shadow
builds and the `:atzu` deps.edn alias all exist as named. Treat this as
verified groundwork, not authority — re-derive independently where you judge
it matters, especially anything NOT in this list.

## Task

This plan is unusually large and carries several owner-facing judgment
calls (D1-D8). Focus your review where a wrong call is expensive:

1. **The two real design units, D4 (terminal) and D5 (gui.event).** Is the
   proposed v2 shape actually consistent with `dao.stream.md`'s invariants
   (no waiter registration, no callback-from-inside-an-operation, no
   fabricated cursor positions, cadence owned by the driving runtime)? Does
   D4's step/binding surface actually eliminate the v1 waiter mechanism, or
   does it sneak a version of it back in under a different name? Does D5's
   claim that `dao.gui.event`'s *code* was "already written to the v2
   discipline" hold up against `event.cljc` itself, or is that an
   understatement of the port's risk?
2. **The three "brief was wrong, actually dead code" corrections** (`yin.io`/
   `yin.module`, `agent.tools`+`agent.tzu`, the continuation-transport/
   WS-demo pair). These claims, if wrong, would delete live capability.
   Independently verify each has truly zero live consumers beyond what the
   plan names (its own tests, its own dead registry, its own launcher). Grep
   for anything the plan might have missed — a dynamic `require`, a
   `resolve`, a data-driven dispatch table, a bb.edn/shadow-cljs.edn
   reference not yet accounted for.
3. **D7's wire-keyword question** — is "clean break" actually the lower-risk
   choice, or does renaming `:dao.stream.apply/*` wire keywords risk
   breaking something outside this repo's control (e.g. a persisted format,
   a cross-version compatibility promise) that the plan doesn't consider?
4. **Completeness of the census** — spot-check a few files NOT in the
   plan's tables (pick your own) to see if anything got missed entirely,
   rather than mis-classified.
5. **Whether the ordering/parallelism claims in "Dependency order and
   parallelism" are actually sound** — do U3 and U4 truly commute on the
   two `artifact.*` demo files as claimed, or is there a real conflict?
6. **Whether Phase 0's grep sweeps are sufficient** to catch every v1
   reference as later units land, given the plan's own admission that a
   namespace grep misses two files (the `bind-stream!` callers).

## Deliverable

A findings list, most severe first, and an explicit verdict: ready for
Architect/owner sign-off, or not (with what must change first). Do not edit
any file.
