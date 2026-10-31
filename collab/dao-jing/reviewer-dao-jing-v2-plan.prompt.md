Created-GMT: 2026-09-06 15:52:38 GMT
Created-Local: 2026-09-06 22:52:38 +07 (Asia/Bangkok)
Coding-Agent: codex
Session-ID: pending (provider-generated)
# Task: dao.jing.v2 migration plan review
Role: Routine Reviewer (correctness, invariants, portability)
Implementers:
- Model: gpt-5.6-sol | Assigned: 2026-09-06 22:52:38 +07 | Status: active | Rationale: Routine Review primary per team.md; GPT family is independent of the Claude author

Perform a **read-only review of a draft migration plan** — not a code diff.
Nothing has been implemented; no file under `docs/design/` or `src/` has
changed. The deliverable under review is the plan document at:

  collab/architect-dao-jing-v2-plan.claude-fable-5-1.findings.md

It was drafted by `claude-fable-5-1` in the Lead System Architect role. Treat
it as untrusted: it is an argument, and your job is to find where the argument
is wrong, unsupported, or would produce a defective migration if executed.

Read first:
- collab/architect-dao-jing-v2-plan.prompt.md (the brief it was given)
- collab/architect-dao-jing-v2-plan.claude-fable-5-1.findings.md (the plan)
- docs/design/dao.stream.md (the v2 contract — superior to the plan)
- docs/design/dao.jing.md (the storage-observer design)
- docs/design/dao.stream.implementation-plan.md ("What v1 callers lose",
  "Boundary of this plan", "End condition")
- docs/design/datom.world.md (axioms and the six non-negotiable invariants)
- src/cljc/dao/jing.cljc, src/cljc/dao/jing/file.cljc,
  src/cljc/dao/jing/remote.cljc, src/cljc/dao/jing/coordinate.cljc
- src/cljc/dao/stream.cljc, src/cljc/dao/stream/rpc.cljc,
  src/cljc/dao/stream/apply.cljc, src/cljc/dao/stream/log.cljc
- test/dao/jing_test.cljc, test/dao/jing/file_test.cljc,
  test/dao/jing/remote_test.cljc

## Already verified by the orchestrator — do not spend budget re-checking

Each of these was confirmed against the tree and holds:
`dao.stream/outcomes-next` is exactly seven keywords; `apply/dispatch-request`,
`apply/serve-once!`, `rpc/request!`, `rpc/poll!`, `rpc/unsent?`, `rpc/rebind`,
`rpc.ws/rebind` all exist as described; `dao.stream.log` uses a 4-byte
big-endian length prefix over opaque byte arrays and `jing.file/encode-record`
supplies the `pr-str` of `[address payload]`; `dao.jing.coordinate/open!` is a
closed `case`; `dao.space.index` line 382 really is `ds/defopen
:dao.space.index/published`. One known slip, already caught, needs no report:
the plan cites `jing_test.cljc` "lines 322-561" but the file is 560 lines.

The full local suite is green on this revision (user-run). You are not asked
to run tests, and this task grants no authority to do so.

## What to attack

Spend your budget on the reasoning, the boundaries, and what the plan does not
say. In particular:

1. **Decision 2 — removing `dao.stream` from `dao.jing.file` entirely**
   rather than building a v2 append-log transport. It argues the log has one
   reader who is its only writer, that `{:dao.stream/type :append-log}` had
   made DaoJing's on-disk framing a public transport type, and that
   `dao.jing.mem`'s private atom is the precedent. Is that consistent with
   Axiom 1 ("all IO and data flow through append-only streams") and with
   `dao.jing.md` §Storage ignorance? Does copying ~100 lines of three-host
   framing out of `dao.stream.log` by copy-not-require create a real
   duplication hazard, and does the golden-byte compatibility test actually
   cover the truncate-incomplete-tail path?
2. **Decision 3 — `jing/materialize!` and `jing/get` become uncallable
   against a remote store under v2**, replaced by a caller-stepped client
   state machine. Is the claim that a synchronous handle is unimplementable
   over the polling RPC actually true given what `dao.stream.rpc` provides?
   Does the `busy` outcome on `request-*` while an envelope is unsent leave a
   caller able to make progress, or can it livelock? Are the completion
   decodings total over what `rpc/poll!` and `take-diagnostics` can produce?
3. **The namespace layout (Decisions 1 and 4)** — only the observer and remote
   go to new `dao.jing.v2*` namespaces, `dao.jing.file` is rewritten **in
   place**, and `dao.jing` core is untouched. Every prior consumer plan built
   a wholesale parallel namespace and promised no existing implementation is
   modified. Is "avoiding two content-address minting paths" a sufficient
   reason to break that pattern, and does the in-place rewrite endanger
   `dao.space.*` or `test/dao/data/btree_durability_test.cljc`, whose suites
   the plan says J1 must run?
4. **Phase boundaries J0-J5.** Does each phase have completion criteria a
   reader could fail it against? Is anything left as a phase that "will
   decide" rather than settled up front? Is the ordering right given that
   `dao.space` still drives the v1 observer?
5. **The end condition and the deletion gate.** The plan defers deleting the
   v1 observer and v1 `dao.jing.remote` until `dao.space` migrates. Does that
   leave a coexistence the stream plan calls "a defect of the migration, not a
   steady state"?
6. **Invariants and portability.** Hidden global state, implicit control flow,
   layer collapsing, cljd reader-conditional discipline
   (`#?(:cljd nil :clj ...)` with `:cljd` first; `#?(:clj ...)` does NOT
   exclude code from the cljd build), and whether the three-host framing
   branches in J1 are actually achievable on cljs (Node) and cljd.
7. **Anything the plan omits** that would block execution, or any place its
   own contested list understates the risk.

Cite repository evidence (file:line) for every finding. Distinguish an
architectural defect from an implementation gap from intentionally deferred
work; the plan is allowed to defer things it names. Do not edit any file. Do
not propose implementing the plan. Produce the complete review in this run
without waiting for approval.

Begin the final response exactly with:
Completed-GMT: <YYYY-MM-DD HH:MM:SS GMT>
Completed-Local: <YYYY-MM-DD HH:MM:SS local-timezone-name>
Coding-Agent: codex
Session-ID: <the exact thread id of this run>

Then report actionable findings as:
P0-P3 | file:line or plan-section | evidence | concrete fix
State "No actionable findings" where that is the honest answer, and separately
confirm which of the plan's decisions you reviewed and consider sound.
