# Orchestrator handoff — DaoStream v2 migration

Created-GMT: 2026-09-02 18:43:23 GMT
Created-Local: 2026-09-03 02:43:23 Asia/Shanghai

Role: Lead Engineering Orchestrator ([`docs/agents/team/orchestrator.md`](./agents/team/orchestrator.md))

Implementers:
- Model: claude-opus-5 (Claude Code, driving seat) | Status: rotated out at credit exhaustion | Rationale: held the seat through the dao.stream v2 planning arc
- Model: glm-5.3 | Assigned: 2026-09-03 02:43:23 Asia/Shanghai | Status: active | Rationale: last fallback in orchestrator.md, "systems-heavy coordination"; cross-family from Architect

Read `orchestrator.md` for the seat's rules. This document is only the state
that is not in the repository.

---

## 1. What this work is

Redesigning `dao.stream` (v1 → v2) and migrating its consumers, as **parallel
v2 namespaces**. Nothing existing is modified: v1 keeps running with all its
consumers, and the two trees coexist until each consumer migrates.

Four design documents are in play. Three are plans, one is a contract.

| Document | State |
|---|---|
| `docs/design/dao.stream.md` | The v2 contract. **Committed** (9178dbc). Authority. |
| `docs/design/dao.stream.ws.md` | WebSocket transport spec. **Modified, uncommitted.** |
| `docs/design/dao.stream.v2.implementation-plan.md` | The transport slice. **Modified, uncommitted.** |
| `docs/design/yin.vm.v2.implementation-plan.md` | The VM slice. **New, uncommitted. Architect-signed.** |
| `docs/design/yin.repl.v2.implementation-plan.md` | The REPL slice. **New, uncommitted. NOT signed.** |

`docs/design/dao.lease.md` and `dao.lease.rationale.md` were committed in
9178dbc and are done for now — flow control was deferred out of the stream
slice, which is what put the lease work on hold.

## 2. The single most important fact

**`src/cljc/dao/stream/v2*` does not exist.** No v2 code has been written at
all. Every plan is blocked on the transport plan's Phase 1, and both the VM and
REPL plans say so explicitly. Nothing is buildable today.

The order is: stream Phase 1 (protocols) → stream Phase 2 (ring buffer) →
yin.vm.v2 → yin.repl.v2. The VM plan needs only Phase 1 for its own namespaces;
Phase 2 is needed by its tests and compositions.

## 3. What is decided — do not re-litigate

These cost multiple review rounds each. Reopening them without new evidence
wastes the team's quota.

- **The VM slice builds `ast-walker` only**, not `semantic`. `semantic.cljc`
  calls `dao.space.query`/`transact`, which would drag 1,885 lines of a
  separate subsystem into a VM port. Four evaluators remain to port
  (`semantic`, `register`, `stack`, `space`), not three.
- **The host supplies streams.** `create-vm` takes `:make-stream`, exactly as it
  takes `:primitives`. **No default** — a default smuggles a hardcoded transport
  and its require back in. This removed `dao.stream.v2.ringbuffer` from the VM's
  closure entirely.
- **Telemetry is a stub** in the VM slice. Every `emit-snapshot` is already a
  no-op when no stream is installed. A non-nil `:telemetry` opt is a
  **construction error**, not silence.
- **`:stream/take` is removed**, not reinterpreted. A v2 `take!` would need an
  implicit per-stream reader position, which is what the contract retired.
- **Reject-mode ring buffers were requested and the request withdrawn.** v1
  backpressure is reject-mode *plus destructive take* — only the drain frees a
  slot (`ringbuffer.cljc:161`), `next-outcome` never advances `:head`. Under v2,
  a reject-mode buffer once full is full forever. This is the single best catch
  of the whole arc; do not re-request it.
- **Totality is the VM's, retention is the composition's.** The engine is total
  over `append!`'s five outcomes including `full`; a ring-buffer composition
  simply never sees `full`.
- **`dao.stream.v2.apply` owns the request/response envelope.**
  `dao.stream.v2.rpc.*` requires it. Two coordinated-but-distinct vocabularies
  was the defect both plans named.
- **Flow control (4d) is deferred** out of the transport slice, with its
  dependency chain named: pause → lease → `dao.space` on v2 → not built.

## 4. What is open

**The REPL plan has never been architect-signed.** It was reviewed by five
models and rewritten, but only the VM plan went through sign-off. That is the
obvious next architect task.

**Three ws-spec amendments block REPL phases R3-R5.** No document answers them:

1. **Server-side attachment identity** — the contract defines
   `:dao.stream/attachment` only in an `attach!` success map, but a server
   handle is minted from a handed-over socket with no `attach!` call.
2. **Accept notification** — how a composition learns a connection was accepted,
   and receives its writer handle, without the `:on-connect` callback the spec
   forbids. More basic than identity; nothing addresses it.
3. **The wire contract** — handshake presentation, disclaimer form,
   ended-stream close code, value codec, decode-failure behaviour.

These need amendments to `dao.stream.ws.md`, which the subordinate plans cannot
make. That is an Architect task.

**Four items block the transport plan's own Phases 1-3:**

- Nobody builds the ring buffer's `attach!`. Phase 2 delivers `create!` plus
  reader/writer/closable; Phase 3 calls `attach!` against a host-kept directory.
  No phase is assigned to produce it.
- The **transit codec is never named**, and whether it is the same codec as the
  ws value codec is undetermined.
- The **conformance suite has no concurrency oracle**; the contract declines to
  define one, so the harness author must choose.
- The ring buffer's **declared exclusion reasons** are unrecorded, and the
  manifest requires a reason for every exclusion.

**One constraint conflict**, unresolved: `:cljs-yin-repl-v2` cannot work from
`deps.edn` alone — the Node REPL is a `:node-script` build in
`shadow-cljs.edn`, an existing file. The REPL plan makes that its single
documented exception to "nothing existing is modified". The user has not ruled
on it.

## 5. Team routing reality

`docs/agents/team/TEAM.md` has the invocation forms. Corrections learned here:

- **Architect is rotated**: `claude-fable-5-1` → `gpt-5.6-sol`, recorded in
  `architect.md` with a dated note. Fable hit ~90% of a 5-hour quota.
  `collab/architect-yin-vm-v2-rotation-brief.md` carries sign-off rounds 3-7
  state, which Sol has not seen. Rotate back when the window resets.
- **Sol has standing on this work** — it was one of five reviewers of
  `review-v2-plans-r2` — but not on sign-off rounds 3-7.
- **DeepSeek buffers its entire run.** A small log means "running" *or*
  "aborted"; only the exit status separates them. Keep
  `CLAUDE_CODE_MAX_OUTPUT_TOKENS=96000`.
- **GLM and DeepSeek emit `[claude-code:unrecognized_model]` warnings** that are
  expected and non-fatal.
- The five-model review pattern (sol, fable, glm, gemini, deepseek on one
  prompt) worked well and is worth repeating for anything load-bearing.

## 6. Traps this seat actually hit

Recorded because they cost real rounds.

- **A closure of namespace *names* is not a closure.** The VM plan's dependency
  claim was wrong **twice** because I traced `:require` forms without checking
  whether the required code uses features v2 removed. It does — `drain-one!`,
  `closed?`, waiters, `:woke`. Verify semantics, not just names.
- **Fixes that relocate their defects** are the recurring failure mode across
  this whole arc — lease rounds, REPL rounds, and sign-off rounds 2 and 4. After
  applying a finding, grep for every other place that states the same thing.
- **Never fabricate timestamps.** Run `date`. This seat invented `Created-GMT`
  headers once and was caught.
- `grep` here is `ugrep`, and a variable-expanded file list does not
  word-split. Loop per file instead.
- **Verify delegated claims before acting.** Two reviewer findings in this arc
  were overstated, and one (a claimed "no blueprint for task parking") was
  contradicted by a documented fallback in the code.

## 7. Standing rules from the user

- **Never stage or commit without explicit instruction.** Commits: code uses an
  imperative verb (no `feat:`), docs use `docs:`. The user asked for **no
  `Co-Authored-By` trailer** on the last commit.
- **`collab/` is an audit trail** — 121 files. Never delete, truncate, or
  `git add` them.
- **Never put private source or diffs in a `-p "<payload>"`.** Point delegates
  at file paths; they read the repo themselves.
- Obtain explicit authorization before transmitting private repository content
  externally.

## 8. Suggested next actions

1. Get the **REPL plan architect-signed** by Sol, using the rotation brief.
2. Put the **three ws-spec amendments** to the Architect as a design task —
   they block R3-R5 and nothing else can answer them.
3. Settle the **four transport-plan items**, which are small and unblock Phases
   1-3 — the only work that can start today.
4. Ask the user about the **`shadow-cljs.edn` exception** and about committing
   the five uncommitted documents.
