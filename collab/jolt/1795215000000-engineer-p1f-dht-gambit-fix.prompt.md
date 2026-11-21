Created-GMT: 2026-11-21 17:10:21 GMT
Created-Local: 2026-11-22 00:10:21 ICT (+07)
Session-ID: pending (new thread)
# Task: P1f — make dao.jing.dht work on the jolt Gambit backend (the enumerable fix slice)

Role: VM Runtime Engineer (implementation)
Implementers:
- Model: gpt-6.2-sol | Assigned: see Created-Local

## Goal

Close the enumerated gaps from the P1e capability map so a `dht:` store works
under the jolt Gambit backend: memory-backed DHT sessions via the real
launcher, with the UDP transport functional for loopback.

## The P1e capability map (authoritative; findings at
/Users/sto/workspace/datomworld/collab/jolt/1795213000000-engineer-p1e-dht-gambit-probe.gpt-6.2-sol.findings.md
— READ FIRST, plus the probe commits on branch gambit-ffi-p1e @ 0aa7f8a4)

Five blockers, in dependency order:

1. **Missing shim rows** (stage-1 pattern; manifest + generated-shims + C
   fixtures per the established pipeline):
   - `inet_pton` (int, pointer, pointer -> int) and `inet_ntop`
     (int, pointer, uint -> pointer)
   - UDP `sendto` / `recvfrom` — six-argument errno-capture tuples, declared
     WITHOUT :blocking over a nonblocking fd (stdlib/socket/native.clj:284 area
     vs src/jolt/dao/stream/udp.jolt:102-109, 300-303, 330-336)
   - UDP payload copies `write-array` / `read-array` (udp.jolt:300-301,
     373-375) — currently public calls refuse in host-ffi.ss:391-399
2. **`close` re-classification**: the socket close is marked blocking+capture
   but operates on a nonblocking fd — re-classify non-blocking WITH EVIDENCE
   (the syscall cannot sleep; cite the basis in the findings), or keep refused
   if the evidence does not hold. Honest, evidence-backed either way.
3. **defcfn availability in session-evaluated code**: the booted-namespace
   shortcut skips stdlib/jolt/ffi.clj, so session code resolving ffi/defcfn
   dies ("No such var"). Make the declaration macro reachable in the
   loaded-source path (the r1 audit's booted-namespace shortcut at
   host/gambit/source-loader.ss:65-75 is the place to understand).
4. **java.io.File statics**: register the class shim (host-statics.ss) for the
   rows the store composition uses (at minimum the constructor the probe hit).
5. **The ClassCastException defect**: (vector-ref 16 0) arg-1 vector expected —
   caught in the probe's composed-store path; find and fix the actual type
   error (probe fixture: test/gambit/fixtures/yin-dht-probe.jolt on branch
   gambit-ffi-p1e @ 0aa7f8a4 reproduces it).
6. **Store admission + composition**: admit `dht:` in the Gambit launcher's
   parse-store (host/gambit/yin/jolt/gambit/yin.jolt:26-41) once the
   composition works. The DURABLE directory store's file ops are C4-blocking
   BY DESIGN — do NOT qualify blocking file ops; instead the memory-backed
   DHT profile is the supported configuration this slice delivers (the
   capability note states: durable dht: stores stay Chez-only; mem-backed DHT
   with UDP transport works on Gambit). If a non-blocking file-op row set
   falls out trivially from the existing qualified shapes, you may add rows,
   but qualifying the durable-store path is NOT required and NOT expected.

## Acceptance (gates, state-derived, watchdogged)

- Extended gambityinrepl corpus (or a new `gambitdht` gate): launch with
  `--index-store dht:` memory-backed profile via the REAL launcher; the
  session opens the store, completes a put/get cycle through the portable
  dao.jing.dht handle, performs one loopback UDP sendto/recvfrom round trip,
  and the session survives and quits cleanly.
- The blocking-refusal rows (durable file ops) still refuse with the
  documented message — negative controls.
- Stage-1 matrix green: gambitffi, gambitcheck, gambitkernel, gambiteval,
  seed/gen checks, emittedheads, deadhost.
- The probe fixture (yin-dht-probe.jolt) passes end-to-end including the
  previously-crashing composed-store path.

## Rules

Worktree: git worktree add /Users/sto/workspace/jolt-ffi-p1f -b gambit-ffi-p1f
gambit-ffi-p1e (the probe branch carries the fixture; base off it). Commit
there; never push; nothing outside the worktree. FOREGROUND with explicit
timeouts; watchdog everything; C4 constraints binding (no blocking
qualification claims; the durable-store note is the honest boundary). The
shim-row additions follow the stage-1 pipeline exactly (manifest, generated
shims, C fixtures, digests, provenance). Unit rows for each new binding
(both-platform semantics where applicable — Linux leg is the orchestrator's
follow-up). No datomworld changes.

## Deliverables

Findings: /Users/sto/workspace/datomworld/collab/jolt/1795215000000-engineer-p1f-dht-gambit-fix.gpt-6.2-sol.findings.md
(standard header; per-blocker fixed/not-fixed with file:line; the gate
transcript excerpt showing the dht session; ends DONE). Final response:
Completed-* header + per-blocker status + gate summary line.
