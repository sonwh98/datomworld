Created-GMT: 2026-09-08 14:59:03 GMT
Created-Local: 2026-09-08 21:59:03 +0700 (Asia/Bangkok)
Coding-Agent: glm
Session-ID: edf8ae2b-8d0d-4992-a621-530a10f74b27
# Task: implement dao.stream.memory-log
Role: Stream & Network Engineer
Implementers:
- Model: glm-5.3 | Assigned: 2026-09-08 21:59:03 +0700 | Status: active | Rationale: Stream & Network fallback per team.md; the primary (claude-opus-5) is the orchestrator and would be reviewing its own code

**Implementation task with write authority**, bounded to the files below.
Repo `/Users/sto/workspace/datomworld`, branch `dao.stream-redesign-v2`,
clean at `2e25b5c` **plus one uncommitted amendment to
`docs/design/dao.stream.md`** — read the contract from the working tree, not
from HEAD.

## Why this exists

`dao.space`'s local stream is its durable log, and v2 has no transport that
can hold one: `create!` exists only in `dao.stream.ringbuffer`, which
requires a positive capacity and evicts. A freshly minted
`:dao.stream/oldest` is the earliest *retained* position, so a consumer that
replays "from the beginning" after an eviction is silently handed a suffix —
which would let `derive-next-t` compute transaction time from incomplete
history. The contract now names wiring a log onto an evicting transport a host
assembly defect. This transport is what `dao.space` will be wired with.

## Read first, in order

1. `docs/design/dao.stream.md` — **working tree**. In particular *Complete
   history* under *Retention and Gaps*, the two new *Cursors* bullets, and the
   *Explicitly Absent* retention-predicate entry.
2. `collab/1788879179820-architect-v2-memory-log-r4.claude-fable-5-1.findings.md` —
   **your specification.** Four review rounds; §§1-3, 5-7 are settled and not
   yours to reopen.
3. `collab/1788879364937-review-v2-memory-log-plan-r4.gpt-5.6-sol.findings.md` — the
   review that scoped your work. Its *Recommended implementer brief* is
   authoritative where it differs from the plan.
4. `src/cljc/dao/stream/ringbuffer.cljc` — the only existing v2 transport;
   your structural reference, **not** your behavioural one.
5. `test/dao/stream/conformance.cljc` and `ringbuffer_test.cljc`.

## Scope — and the one thing the plan says that you must NOT do

**Build:** `src/cljc/dao/stream/memory_log.cljc` and
`test/dao/stream/memory_log_test.cljc`.

**Do NOT build `run-retention-laws`, the `:retention` manifest key
machinery, or the synthetic ring-buffer falsification manifest.** The plan's
§4 describes them; the review deferred them, and I concur: five review rounds
found the generic law still ungated on `cursor`, `next` and `close!`, and it
would have exactly one subject. It waits for a second complete-history
transport. Your transport's completeness is proven by its **own** tests.

You **may** add the exclusion-principle paragraph to `conformance.cljc`'s
namespace docstring (plan §4, and the review calls it independently sound):
induction proves declared outcomes inducible but cannot prove an excluded
outcome impossible, so each exclusion is a proof obligation discharged by a
structural argument, a property law, or a transport-specific falsification
test — and fixtures must never be required for excluded outcomes.

## What the transport is

In-memory, unbounded, append-only; process-lifetime, **not** durable across
restart. State is `{:identity <uuid-string> :values [] :closed? false}` — a
vector, dense from 0, nothing ever removed. Eviction is absent *structurally*:
the namespace must contain no expression that removes an element.

Manifest, cursors, anchors, and close semantics: plan §§2 and 5, verbatim.
`attach!` is **absent, not excluded**. `gap`, `full` and `transport-error`
are excluded with the reasons the plan gives — `transport-error` because an
operation either completes its in-memory transition or does not return at all,
which the contract amendment now covers explicitly.

`next` must be **total**, range-checked before any indexed read:
non-map → `invalid-cursor`; missing identity key → `invalid-cursor`;
identity mismatch → `cursor-mismatch`; position non-integer, `< 0`, or
`> tail` → `invalid-cursor`; `0 <= pos < tail` → `ok`; `pos = tail` →
`end` if closed else `blocked`. A negative position reaching `nth` throws on
the JVM; that is the defect this ordering exists to prevent.

## What your tests must prove

The review's list, which is the acceptance criteria:

- an origin cursor is minted **before** any append;
- every test append returns `ok`;
- a substantial sequence **including `nil`** is retained exactly and in order
  — `nil` is a legitimate value here, and a loop that treats it as
  end-of-input is a real bug the review caught in the plan's own sketch;
- both the kept origin cursor and a **fresh** `:oldest` replay the entire
  history;
- neither replay observes `gap`;
- the open tail returns `blocked`;
- both replays still work after `close!` and terminate with `end`;
- `next` totality: negative, non-integer, and beyond-tail positions on all
  three hosts;
- creation-spec policy per plan §1, including non-map, missing-type,
  wrong-type and extra-key cases;
- the manifest passes `validate-manifest`, and `run-conformance-suite` passes
  including linearizability histories (concurrent append/append, append/next,
  append/close, and two cursors reading concurrently after one append).

## Verification — run in full, report counts

`bb test:clj`, `bb test:cljs` (confirm `Testing dao.stream.memory-log-test`
appears in the Node output), `bb test:cljd`,
`clj -M:cljs -m shadow.cljs.devtools.cli compile demo`, and
`clj -M:kondo --lint` on both new files.
`dao.stream.ringbuffer-test` must pass **unchanged**; if it does not, you
modified the ring buffer, which is out of scope.

**Format before you report**: `mise exec -- cljstyle fix <your files>`, so
the tree you tested is the tree that gets committed.

Reader-conditional trap: `#?(:clj …)` alone does **not** exclude code from the
cljd build; use `#?(:cljd nil :clj …)` with `:cljd` first.

## Report

`collab/1788879543857-stream-v2-memory-log.glm-5.3.findings.md`, with
Completed-GMT/Local, Coding-Agent: glm, Session-ID: edf8ae2b-8d0d-4992-a621-530a10f74b27. Exact commands and
counts, any plan invariant you could not honour and why, anything left owing.
Do not stage or commit.
