Created-GMT: 2026-11-21 14:13:12 GMT
Session-ID: pending (new thread; P1e probe)
# Task: P1e — dao.jing.dht capability probe on the jolt Gambit backend

Role: VM Runtime Engineer (probe round; evidence-only)
Implementers:
- Model: gpt-6.2-sol | Assigned: see Created-Local

## Mission

Turn "does dao.jing.dht work on the Gambit backend?" from unknown into a
documented capability row. Evidence-only: probe, record, classify — implement
nothing beyond a probe fixture/gate if useful.

## Context

- Fork branch gambit-ffi-p1c @ f22df3c7 (pushed): yin.repl runs under Gambit
  (gambityinrepl PASS both platforms, 12-reply session), stage-1 FFI tier
  qualified (loader/memory/ordinary calls/errno; blocking/callables refuse).
  Worktree /Users/sto/workspace/jolt-ffi-p1c exists (branch checked out).
- The boot trace shows dao.jing namespaces LOAD (dao.jing.cbor ready etc.).
- UNTESTED: a dht: index store on Gambit. Constraints from the consumer
  inventory (p1b-inv/CONSUMERS.md in the fork... it lives on branch
  gambit-ffi-inv at p1b-inv/CONSUMERS.md — read via
  /Users/sto/workspace/datomworld/collab/jolt/1795020000000-engineer-p1b-inventory.glm-5.3-flash.findings.md
  or the worktree /Users/sto/workspace/jolt-ffi-inv (branch gambit-ffi-inv)):
  fork stdlib socket/native bindings include blocking+capture rows that REFUSE
  on Gambit. DHT's UDP transport path may hit them.
- The Yin session corpus format: test/gambit/yin-repl-session.sh drives the
  real entry via stdin with state-derived assertions; the gate is
  `make gambityinrepl DATOMWORLD_ROOT=...`.
- C4 constraints binding: no blocking/foreign-thread qualification claims; a
  refusal must be the documented message-carrying error, and the session must
  survive it.

## Probe plan (sequential; record verbatim output at each step)

1. **Static**: which dao.jing.dht / dao.stream transport rows does the
   loaded-source path actually bind? Trace the dht: store construction path
   (index store -> datagram/socket) and classify each FFI row it touches as
   qualified / blocking-refused / not-yet-implemented on the Gambit target
   (cite file:line + the shim registry).
2. **Session probe**: drive the real yin.repl session (same harness as
   gambityinrepl, scripted stdin) against a `dht:` store (local dir): open
   the store, attempt put/get, attempt a network operation (loopback UDP if
   reachable), capture per-operation outcomes. Classify each: WORKS /
   refuses-with-documented-message / crashes / hangs (watchdogged).
3. **Classification**: one capability row per probed operation, plus an
   overall verdict: dao.jing.dht on Gambit = WORKS (fully/partially with the
   exact working subset) / REFUSES-HONESTLY (all rows raise documented
   errors, session survives) / MIXED / BROKEN (crash or hang = defect).

## Rules

Probe fixtures/changes live in the worktree; commit them on a NEW branch
gambit-ffi-p1e off gambit-ffi-p1c (git worktree add
~/workspace/jolt-ffi-p1e -b gambit-ffi-p1e gambit-ffi-p1c). Never push.
Foreground + explicit timeouts; watchdog every session run; C4 constraints
binding (a hang or crash is a recorded DEFECT, never papered over). If the
probe reveals fixable small defects (e.g. a missing binding that should
refuse), record them as findings — do NOT fix in this round unless trivial
and self-evident.

## Deliverable

/Users/sto/workspace/datomworld/collab/jolt/1795213000000-engineer-p1e-dht-gambit-probe.gpt-6.2-sol.findings.md
(standard header; the static classification, the session probe transcript
excerpts, the capability table, the overall verdict, ends DONE). Final
response: Completed-* header + the one-line verdict.
