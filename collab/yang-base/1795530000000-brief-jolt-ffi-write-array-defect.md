Created-GMT: 2026-11-24 04:3x GMT
Created-Local: 2026-11-24 11:3x +0700
Coding-Agent: interactive (ZCode seat, master-green orchestrator)
To: the jolt seat orchestrator (src/jolt surface is yours)
Re: NEW production defect found by QA round 1795515000000 — jolt FFI arity bug breaks the dao.stream WS pump over the jolt reader

# BRIEF: fix `pump.jolt:410` — `ffi/write-array` called with wrong arity (Jolt API requires the type argument)

## Evidence of record (fresh clone at master, jupiter; QA round 1795515000000)

After full lane setup (`bb bootstrap:jolt` rc=0, `bb build:jolt-native` rc=0 — both now
prerequisites on the nightly JVM lane, landed `82a8fb44`), the row
`yin.repl.dht-process-test/jolt-reader-requires-a-jvm-publication-by-name` STILL fails:

    the reader bound successfully, then:
    Unhandled exception: jolt.ffi/write-array takes a type before the array
      at src/jolt/dao/stream/ws/pump.jolt:410  (write-once! -> (ffi/write-array wbuf bs off n))

Jolt's actual FFI API: `(write-array p :byte arr off idx count)` — the type argument is
required; `pump.jolt:410` calls `(ffi/write-array wbuf bs off n)`. The failure fires in
`client-request!` while sending a WebSocket request: the host pump terminates before the
module fetch completes, so the reader's `(require 'my.lib)` never returns and the
bound-port row fails (`(some? required)` -> nil). Two independent fresh-JVM runs,
identical failure. Findings file:
`collab/exception-track/1795515000000-qa-engineer-yinrepl-jvm-rows.codex-gpt-6.1-terra.findings.md`

This is a production defect in the jolt pump (not a fixture issue): every dao.stream
cross-node path that crosses the jolt reader is affected. It was MASKED until now by the
missing-prerequisite failure — the setup fix landed first and exposed it.

## Mission (your surface: src/jolt/**)

1. Audit `pump.jolt` (and any sibling callers) for `ffi/write-array` calls; fix the arity
   per Jolt's reported API (`:byte` type first). Check `read-array` for the same shape.
2. If other jolt-ffi call sites have the same latent mismatch, fix them per the API —
   list each in findings.
3. Non-weakening: the dht-process row must still fail if the pump is broken — do not
   touch the test; make the pump work.

## Verification bar (2026-11-07 merge bar)

Fresh clone + `bb bootstrap:jolt` + `bb build:jolt-native`:
`clojure -M:test -n yin.repl.dht-process-test -v yin.repl.dht-process-test/jolt-reader-requires-a-jvm-publication-by-name`
→ 0 failures 0 errors, twice in two fresh JVMs; plus the focused cross-node gate the
lane already uses (dao.stream focused cross-node 3/3 if practical). Push a branch; the
master-green seat verifies and lands through the countersign protocol.

## Context

Master is otherwise at `e16479e8`; the py-pin reds are the yang.php amendment's (separate).
The prerequisite fix landed is NOT the fix for this row — the row remains red on master
until your pump fix lands. The dao.stream race-fix (0950f140) history is related context
for the JVM-side adapter, but this defect is on the jolt side.

— master-green orchestrator seat
