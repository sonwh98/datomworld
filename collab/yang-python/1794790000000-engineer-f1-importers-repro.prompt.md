Created-GMT: 2026-11-05 21:20:00 GMT
Created-Local: 2026-11-06 04:20:00 +07 (+0700)
Coding-Agent: codex
Session-ID: pending (provider-generated; capture thread.started.thread_id)

# Task: F1 r2 — reproduce and diagnose the `dht-import-test` `:importers` mismatch on Node

Role: Yang Compiler and Universal AST Engineer
Implementers:
- Model: codex gpt-6-luna | Assigned: 2026-11-06 04:20 +07 | Status: active | Rationale: one bounded reproduce/diagnose round in the Python frontend's test surface

WORKTREE: /Users/sto/workspace/datomworld-f12 (at e4cf2d72, synced to origin/master acbf8e10 — the cljs compile is RESTORED, verified; branch from HEAD; the ANTLR
parsers are already generated under lib/antlr/python3 and build/). Commit to a new branch
`f1-importers-diagnosis-r2`. You are the only writer.

## Context — the open F1 row (docs/agents/known-gaps.md)

`dht-import-test` fails on Node and Dart: the test's own publication differs from the JVM
artifact in the `:importers` key; the JVM passes. Surfaced by the C3 completion run. F2
(the sibling) is CLOSED — merged 062c31b7; the compile-contract test now needs the packet
catalog entry (also merged). F1 is the last open row of that round.

IMPORTANT context from the r1 round (1794540000000, f12-diagnosis-fixes): F1 was left
unresolved because that sandbox had no parsers and no runners. This worktree HAS both (Node
only — this machine has no dart and no bb). Your job: capture the actual Node `:importers`
value and diagnose the divergence. A dart-leg verification is NOT this round.

## The test surface

- `test/yang/python/antlr/dht_import_test.cljc` — the slow acceptance over the
  `dao.jing.dht` mesh seam (in-process, so Node can run it).
- `test/yang/python/antlr/dht_import_fixture.cljc` — reads the JVM-generated artifact
  (`test/resources/yang/python/dht-import-v1.*`, written by
  `test/resources/yang/python/dht-import-v1.generate.clj`). The artifact carries guest
  blobs, manifest addresses, signed envelopes, and the importer packets. The docstring's
  contract: "Each host also publishes the same packets itself, into a store of its own,
  and must arrive at the artifact: a host-specific publication difference fails there."
- Around fixture line 170 you will find the `:importers` composition
  (`(pr-str {:importers {:d programs/d-packet, ...})` — that region is the pin to
  compare against.

## What to do, in order

1. **Reproduce on Node.** Run the namespace on Node (find the lane in deps.edn / bb.edn /
   docs/build-n-test.md — the cljs test path this repo uses; if a scoped run of
   `yang.python.antlr.dht-import-test` is possible, prefer it). CAPTURE the actual
   `:importers` value the Node publication produces and the artifact's expected value,
   verbatim, into your report. Do not proceed to a fix without the two values side by side.
2. **Diagnose the root cause with file:line.** Read the Node publication path the test
   exercises. Candidates: a host-conditional in the importer-packet builder; an ordering /
   serialization difference (pr-str of a set or map keyed differently); a signature or
   envelope field that Node fills differently; the `d-packet` shape differing per host.
   If the root cause lives OUTSIDE `yang/python/**` (dao.stream / dao.jing / dao.space.dht
   / the linker), STOP and report with evidence — out of scope for this round.
3. **Fix only if in scope.** If it is a genuine product defect in the Python publication
   path, fix it in `yang/python/**`. If it is a stale test-fixture expectation (the
   artifact is correct and the test's expected `:importers` is outdated), fix the
   expectation and say so explicitly in your report.
4. **Re-run** the namespace on Node (plus any focused JVM sibling that pins the same
   fixture, e.g. the JVM leg of dht-import-test) and report exact counts.

## Rules

- No master work; the branch only. No edits outside the diagnosis/fix + its tests.
- The full suite is NOT this round's job (owner rule: focused lanes only locally).
- If Node cannot load something (a missing generated file, a JS-only limitation), capture
  the exact error and stop that leg — do not paper over it.

## Report

Write your findings into the final agent message: the two `:importers` values, the root
cause with file:line, what you changed (or why you stopped), and the test counts.
