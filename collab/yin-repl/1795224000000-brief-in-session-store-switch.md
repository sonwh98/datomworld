Created-GMT: 2026-11-22 09:1x:xx GMT
Created-Local: 2026-11-22 16:1x:xx ICT (+07)
Coding-Agent: interactive (ZCode / glm-5.3-flash, Gambit host-lane orchestrator seat)
Session-ID: not-applicable (interactive seat)

# BRIEF: in-session dao.jing store switching for yin.repl — design and implement the ability to change the store implementation inside a running REPL

## To

The receiving orchestrator seat (any seat picking this up; the yin.repl store
composition is the subsystem). This is a FEATURE brief: new scope, not a bug
fix. The first step is an Architect round — the swap semantics are a design
decision with real fork-in-the-road choices, and this brief deliberately
leaves them open with the options named.

## Feature statement (owner request)

`--index-store` selects the dao.jing implementation at launch and the handle
is fixed for the session's lifetime today. The owner wants the implementation
changeable INSIDE a running REPL: e.g. start on `mem`, then switch to
`file:<dir>` or `dht:` (or between durable stores) without exiting.

## Current state (verified this session; re-verify before building)

- Selection happens once at startup: `src/cljc/yin/repl/store.cljc`
  (`parse-arg` :55, `checked-spec` :109, `open` :140) → the handle is wired
  into the engine at boot. Grep of `yin/repl/main.cljc`, `store.cljc`,
  `yin/shell/store.cljc` finds NO in-session switch/reopen path (verified
  2026-11-22).
- In-session controls today: `(reset)` / `(vm ...)` rebuild session state;
  whether the index carries over is the store's declared
  `retain-on-reset?` policy (store.cljc:164-182 — durable stores retain;
  `mem` starts empty on reset by policy).
- `close!` exists on the handle contract (store.cljc:182) — the close half of
  a swap already has a primitive.
- Backends: JVM/Node/Dart/Chez admit `mem`, `file:<dir>`, `dht:`;
  **Gambit admits only `mem`** today (host/gambit/yin/jolt/gambit/yin.jolt:26-41;
  the durable open path is C4-blocking-refused by design — see below), and
  only on the P1c slice (branch gambit-ffi-p1c @ f22df3c7, yin.repl sessions
  verified both platforms; evidence collab/jolt/1795110000000-* through
  1795213000000-*).

## The design decisions (Architect round — options named, none pre-chosen)

1. **Swap semantics for the live VM**: what happens to the running session's
   references when the store swaps? Options: (a) quiesce — require an empty/
   reset session before swap (simplest, honest); (b) migrate — carry the
   index across (mem→file is effectively "persist the session"; file→dht is
   "publish to the ring"; each needs a defined copy protocol and failure
   semantics); (c) hybrid — user chooses per swap. Each changes the failure
   story.
2. **Ordering**: open-first (new store must fully open before the old closes
   — no window with no store; on open failure the old store stays and the
   session survives) vs close-first. Open-first is the P1e lesson (a session
   that dies on a failed operation is the defect class this track just
   closed).
3. **What the swap means for `dht:`**: joining a ring is not just storage —
   it's network identity (principal, follow, publish flags). A swap INTO dht:
   needs the dht join parameters; a swap OUT means leave the ring. This is
   the largest sub-design.
4. **The command surface**: `(use-store ...)` at the yin> prompt vs a
   launcher-level mechanism vs both; argument grammar; how it interacts with
   `state.edn` resume.
5. **Gambit boundary (owner ruling C4, 2026-11-19)**: blocking FFI refuses on
   Gambit — the durable file-open path is refused there BY DESIGN. The swap
   command on Gambit must degrade honestly (mem→mem is the only in-family
   swap today; durable targets refuse with the documented message and the
   session survives — the P1e-established pattern). Do NOT qualify blocking
   file ops to make the swap work.

## Where the work goes (expected, verify during design)

`src/cljc/yin/repl/{store.cljc,main.cljc}` (the swap command + handle
lifecycle), `src/cljc/yin/shell/store.cljc` (the composition),
`host/gambit/yin/jolt/gambit/yin.jolt` + the Gambit overlays (the honest
refusal path), `test/` rows on every backend, and the user-facing docs
(`src/cljc/yin/vm/docs/yin.repl.md`).

## Verification bar (suggest; the Architect may extend)

- Swap mem→file→mem on JVM/Node/Chez: session survives each swap, the file
  store persists what the design says it persists, reset semantics per the
  decided policy.
- Failed-swap control: swapping to a store that fails to open leaves the old
  store live and the session working (the P1e lesson, gate-held).
- Gambit: swap to a durable target refuses with the exact C4 message; the
  session continues (the P1e transcript pattern is the reference).
- Per-backend focused tests; tri-host landing gate for the merge (wide
  inputs likely).

## File ownership / landing

Feature branch off origin/master; worktree discipline; cross-family review
(codex implements / agy reviews or equivalent per routing-status.md); tri-host
gate; land via the normal merge. The Gambit slice rides the fork
(gambit-ffi-p1 lineage) and the datomworld-side pin follows through the
upgrade gate as usual.

## Evidence pointers

- Store lifecycle: src/cljc/yin/repl/store.cljc (the whole file is ~190 lines
  and readable in one pass).
- The DHT composition: docs/design/yin.repl.dht-composition.md and
  src/cljc/yin/shell/dht.cljc.
- The Gambit capability map + honest-degradation patterns:
  collab/jolt/1795213000000-engineer-p1e-dht-gambit-probe.gpt-6.2-sol.findings.md
  (the session transcript shows the refusal-then-survive pattern to
  replicate).
- The C4 ruling: collab/jolt/1795040000000-reviewer-p1a-confirm-codex.gpt-6.1-sol.findings.md.

— filed by the Gambit host-lane orchestrator seat at the owner's request
