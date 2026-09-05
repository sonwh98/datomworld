---
description: Orchestrator handover — driving the DaoStream v2 / Yin REPL v2 / Yin VM v2 slice to completion
---

# HANDOFF: Orchestrator seat

Branch `dao.stream-redesign-v2`, HEAD `488b497` when written (2026-09-04).
**Re-derive before acting** — the user commits to this branch directly, so HEAD
moves without an orchestrator turn.

Read [`orchestrator.md`](./agents/team/orchestrator.md) and
[`team.md`](./agents/team.md) first; this document does not restate them. It
says where the work stands, what remains, and what will cost you time.

---

## 1. The three plans and their real status

The migration is one slice across three plans. Two are nearly done; the third has
not been started, and it gates the completion of the second.

| Plan | Status |
|---|---|
| [`dao.stream.v2.implementation-plan.md`](./design/dao.stream.v2.implementation-plan.md) | Phases 1–5 implemented and reviewed. **Complete.** |
| [`yin.repl.v2.implementation-plan.md`](./design/yin.repl.v2.implementation-plan.md) | R1–R5 implemented and signed off. **Complete.** |
| [`yin.vm.v2.implementation-plan.md`](./design/yin.vm.v2.implementation-plan.md) | Implemented, architect-signed-off, and parity-tested. **Complete.** |

What is built and working: the v2 stream contract, ring buffer, descriptors, the
ws transport with forwarding and serving, the v2 RPC layer, and a complete v2
REPL client and server with host WebSocket adapters composed on all three hosts.

`yin.repl.v2.host` (`.cljc`/`.cljs`/`.cljd`) holds only `websocket` — the one
thing that differs per build. Everything portable lives in
`yin.repl.v2.host.common`. A `.cljs`/`.cljd` shadow replaces the portable
namespace *wholesale*, so anything portable put back into it will drift silently;
that has already happened once.

---

## 2. The remaining work, in the order it should be done

### A. `dao.stream.v2` Phase 5 — the slice, end to end

**Two processes, not two compositions in one process.** Every test in the tree
today is in-process. The plan is explicit that a same-process socket test "cannot
show that a descriptor is self-contained or that no accidental shared state
carries identity across — and those are central contract promises." Do not accept
an in-process fake for this.

Process A serves a ring buffer over ws and appends. Process B receives the
descriptor *as data*, attaches, and appends outbound. **How B obtains A's
descriptor is part of the test, not an assumption.** Kill the connection, then
verify five separable facts (the plan's prose says "four" and then lists five —
the mismatch is a typo in the plan, not a missing item):

1. Detachment is observable on both sides — B's handle answers `closed`, A's
   boundary deposits the departure.
2. The served stream survives on A, and `attach!` with the same descriptor
   rejoins the same logical stream.
3. Two simultaneous attachments carry distinct `:dao.stream/attachment` values,
   and every event for one — payload, resolution, lifecycle — carries that value
   under `:ws/attachment`.
4. B's cursor **on B's own deposit medium** still resumes; that medium outlives
   the socket. ws-level resumption is explicitly deferred and is *not* tested here.
5. Serving A's stream through a second endpoint produces different reachability
   descriptors whose `:dao.stream/identity` values are equal.

### B. `yin.repl.v2` Phase R5 — end to end

Per host first, then across hosts. `clj -M:clj-yin-repl-v2 --port 8080
--headless`; from a second process `(connect "daostream:ws://localhost:8080/repl")`.
Verify four facts:

1. Evaluation round-trips, and two clients each get their own answers.
2. Killing the connection is observable on both sides, and every outstanding
   request is **reported lost rather than timing out**.
3. Reattaching with the same descriptor reaches the same served stream, and the
   client's cursor on its own deposit medium resumes across the socket's death.
4. `stop!` closes the service stream and the client deposits `:ws/ended`, **not**
   `:ws/closed`.

Then the cross-host pairs: a cljd client against a JVM server, and a Node client
against a cljd server. The plan's reasoning is worth handing to whoever
implements it — "the descriptor crossed a codec and the wire is the same wire; if
that fails, the contract was implemented three times rather than once."

R5 also owes `src/cljc/yin/vm/docs/yin.repl.v2.md`, which does not exist. It
states what differs from v1: `connect` returns immediately and reports its
outcome when known, `(vm :type)` offers `:ast-walker` only, and there is no
`(telemetry)` command. `yin.repl.md` is left alone.

Entry points are already wired — `deps.edn` has `:clj-yin-repl-v2`,
`:cljs-yin-repl-v2`, `:cljd-yin-repl-v2` and `:cljd-yin-repl-v2-build`, each with
a `-main`. Ctrl-C shutdown is wired on all three hosts.

### C. `yin.vm.v2` — unstarted, and it gates B's completion

**This is the part most likely to be missed.** The REPL plan's end condition
requires `yin.repl.v2` to need *no v1 namespace*, "which running on `yin.vm.v2`
makes true rather than aspirational." That is not satisfied today, and R5 will
not satisfy it:

```
yin.repl.v2.core  →  yin.vm, yin.vm.ast-walker      (v1)
yin.vm            →  dao.stream                      (v1)
yin.vm.ast-walker →  dao.stream, dao.stream.apply, yin.module   (v1)
```

`src/cljc/yin/vm/v2/` does not exist. The plan is written and architect-signed
off (see `archive/architect-yin-vm-v2-signoff-r*` and
`architect-vm-v2-round.claude-fable-5-1.findings.md`), but nothing implements it.
Its own end condition: `yin.vm.v2.ast-walker` evaluates a macro-free corpus and
exercises ingress and FFI over v2 streams with telemetry disabled, at parity with
v1 except where the divergence register says otherwise, with no namespace under
`yin.vm.v2` or `dao.*.v2` requiring v1 `dao.stream`, `dao.runtime`,
`dao.stream.apply` or `yin.module`.

So "finish the phases" is three tracks, not two. Decide with the user whether
`yin.vm.v2` is in scope for this push or is a separate one; do not silently
declare the REPL plan complete after R5 while `yin.vm.v2` is missing.

### Sequencing

Do **A before B**. If the descriptor / identity / attachment contract is broken
at the stream level, REPL failures will be misattributed to the REPL layer. C is
independent of both and can run in parallel with either, in its own worktree —
but only one process may own the CLJD lane, because `bb test:cljd` writes shared
generated output.

This is a recommendation. The user has not ruled on ordering.

---

## 3. What you inherit

R3/R4 was signed off *with* known deferrals. Four Mediums flagged "to fix before
R5" were all closed across verification rounds r2–r5. What those rounds recorded
as standing risk is what you inherit. Re-checked 2026-09-04 where cheap:

| Item | Status |
|---|---|
| Pending pre-accept sockets not closed by `serving/stop!` | **open** — bears directly on R5 fact 4 |
| Canonicalizers aligned but duplicated | **open** — `serving.cljc`, `ws.cljc`, `connect.cljc` |
| Local shell and served shell are distinct (D4) | **open by design**, not a defect |
| CLJD compile-verified but not test-executed | closed — the Dart suite executes |
| Node host seam unwired | closed |
| Ctrl-C shutdown with `--port` unwired | closed on all three hosts |

Two plan-level residual risks bear directly on the facts you must test:

- **Attachment identities are unique only within one boundary lifetime.** A
  process restart may reuse a representation. Phase 5 is precisely a two-process
  test that kills and rejoins, so an assertion assuming *global* uniqueness would
  be testing something the contract never promised.
- **The linearizability oracle is principally the clj run.** Single-threaded
  cljs/cljd parity tests are not equivalent concurrency evidence; a green Node or
  Dart run does not stand in for the JVM one.

One thing to check rather than assume: `dao.stream.v2`'s end condition says the
slice is "explicitly incomplete on cljd until the cljd ws transport lands." That
transport landed in `6422c8b`, so the blocker looks cleared — but nobody has
verified it against Phase 4's full criteria.

---

## 4. Definition of done

- **`dao.stream.v2`**: Phases 1–5 pass on clj and cljs, and on cljd now that its
  ws transport exists. Flow control is explicitly *not* part of this and does not
  hold it open.
- **`yin.repl.v2`**: on each of clj, cljs (Node) and cljd, a v2 REPL server
  accepts a connection from a second process, evaluates, and survives disconnect
  and reattach — with `yin.repl.v2` requiring no v1 namespace. R5's four facts
  plus the cross-host pair are the test.
- **`yin.vm.v2`**: as quoted in §2C.

Coexistence is the expected end state for the REPL: both REPLs ship, both alias
sets work. Deleting v1 is the *stream* plan's end condition, once its last
consumer has migrated — and when the slice is complete, one decision is taken
explicitly: `dao.stream.v2` is renamed to `dao.stream`, or keeps its name
permanently. An undecided coexistence of both namespaces is a defect of the
migration, not a steady state.

---

## 5. Verifying

```sh
bb test:clj     # clojure -M:test
bb test:cljs    # clj -M:cljs -m shadow.cljs.devtools.cli compile test
bb test:cljd    # clojure -M:cljd test
```

Baseline on `f1d76d5`'s tree, measured by the outgoing orchestrator — a
comparison point, not a current fact:

| Suite | Result |
|---|---|
| JVM | 1289 tests, 166531 assertions, 0 failures |
| Node | 1210 tests, 34209 assertions, 0 failures, 0 warnings |
| Dart | 1159 tests, "All tests passed!" |

Confirm a cljs suite actually ran by finding its `Testing <ns>` line in the node
output; discovery is automatic and a silently skipped namespace looks like a
pass. For cljd, confirm fresh generated output rather than trusting a stale
`cljd-out`.

---

## 6. Traps in this codebase

1. **ClojureDart reader conditionals**: the cljd host-eval pass *also* matches
   `:clj`. Put `:cljd` first — `#?@(:cljd [] :clj [[…]] :default [])` — or the
   build tries to require JVM-only namespaces. A `:cljd` branch in tail position
   fails silently.
2. **`.cljs`/`.cljd` shadows replace a `.cljc` namespace wholesale.** Anything
   portable placed in a shadowed namespace must be written once per build and
   will drift. Portable code belongs in a separate namespace all three require.
3. **A pre-commit formatter runs between staging and commit** and can reformat a
   whole file you only touched in three lines. Re-read the commit's own diff
   afterwards; the committed tree is not always the reviewed tree.
4. **The plan documents are the contract.** Where a test and a plan disagree,
   the plan wins unless the user amends it. Several plan amendments in `archive/`
   were negotiated with an architect before implementation — that is the pattern.

---

## 7. Where the history lives

`archive/` (300+ files, flat, gitignored, untracked) is the record of *why*, not
just *what*: phases 1–3 implementation, the DaoLease design rounds, the
adversarial reviews, every R3/R4 verification round, and the VM v2 design
rounds. Findings headers carry the reviewer session IDs, so a prior review can be
resumed instead of re-derived.

Start with `architect-phase5-r3-r4-signoff.fable.findings.md` and the four
verification rounds after it. `architect-dao-stream-v2-open-items{,-r2}` carry
the contract amendments that produced the `:ws/attachment` semantics Phase 5
tests. `architect-yin-vm-v2-signoff-r*` is the VM v2 design record.

Live artifacts sit in `collab/` until their work is committed, then move to
`archive/` under Responsibility 7.

---

## 8. Standing constraints

- **Never stage or commit without explicit user instruction.** Stage only the
  files for the requested work.
- **No `Co-Authored-By`**, no LLM attribution. (12 older commits carry one;
  they predate the convention and are not a precedent.)
- Commit subjects: `<type>[(<scope>)]: <lowercase imperative summary>`.
- `collab/` is never staged or committed.
- **This file is untracked deliberately** — the user keeps handover notes out of
  git. Do not `git add` it.
- Untracked and not to be touched: `public/chp/blog/*.blog`. Authored content,
  not build residue.
