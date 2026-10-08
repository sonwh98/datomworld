Created-GMT: 2026-09-13 08:43:53 GMT
Created-Local: 2026-09-13 15:43:53 +0700 (+07)
Coding-Agent: codex
Session-ID: pending (provider-generated)

# Task: Architecture & Invariant Review — dao.space.index as a dao.stream Observer
Role: Lead System Architect (review)

Implementers:
- Model: gpt-6-astra | Assigned: 2026-09-13 15:43:53 +0700 | Status: active | Rationale: Independent architecture review per team.md; strongest record on stream-protocol failure modes and identity/provenance defects (found the r2–r7 defects in yin.vm.macro.md).

**Read-only architecture review. Print your complete structured findings to stdout; write no files.**
Repository `/Users/sto/workspace/datomworld`, branch `dao.stream-redesign-v2`, HEAD `5296ee5`.

## Target
- `docs/design/dao.space.index.as-observer.md` (466 lines, committed). A design note, not yet implemented, and not yet reviewed by anyone.

## Read first
- `docs/design/datom.world.md` — §Streams now states the invariant this note serves: a `yin.vm` evaluator and `dao.space.index` are peer observers of one medium, each ignorant of the other.
- `docs/design/dao.space.index.md` — the library as it exists ("Status: implemented"); its *Open items* → "Incremental indexing" points at the note.
- `docs/design/dao.space.md` — §Three Boundaries, §The Write Path, §Fault Tolerance.
- `docs/design/dao.space.transactor.md` — *Open items* (the O(history) watermark the note claims to relieve).
- `docs/design/dao.stream.md` — §Composition, §Retention and Gaps.
- `src/cljc/dao/space/index.cljc` — `element-datoms`, `datoms-from-elements`, `snapshot-datoms`, `index-datoms`, `publish-index!`, `recording-content-handle`.
- `src/cljc/dao/data/btree.cljc` — `conj`, `from-sequential`, `store-tree`, `restore-tree`; the docstring on `store-tree` is load-bearing for the note's §4.1.
- `src/cljc/dao/stream/observer.cljc` — `run-on-stream` (the loop the note composes with; `{:observer :consumer}` session, `ready? load run`).
- `src/cljc/dao/stream/observe.cljc` — `step`.
- `src/cljc/dao/space/query.cljc` — `snapshot`, `current`, `history`, `flatten-datoms`.
- `src/cljc/dao/datom.cljc` — `local-datom?`, `first-user-id`, `default-op`.
- For the pattern the note borrows: `docs/design/yin.vm.macro.md` §5 (per-medium staging, `full` retry, failure-as-data) and decision 11.

## What the note claims
1. `dao.space.index` is *already* the dao.stream observer on the dao.space side (`snapshot-datoms` + `publish-index!` = one stateless observer run); the note makes it stateful, driven by `run-on-stream`, over any medium. No new namespace.
2. The library is payload-agnostic: d5 shape and a supplied ref schema only. The `yin.vm` co-observation is motivation and composition tests, never spec.
3. **Two modes fixed at construction** — `:resolved` (writer-allocated ids pass through, nothing allocated) vs `:unresolved` (tempids only; the observer owns every positive id). Rationale §3.1: writer-minted and observer-minted positive ids cannot share one number line. Cross-mode joins must not equate `?e`.
4. An **admission rule** looser than `local-datom?` (negative `e`, negative declared-ref `v` admitted as tempids in `:unresolved` mode), strictness restored after resolution; bad input is a per-batch defect, not a throw.
5. **Resolution facts** `[δ :dao.space.index/batch n n default-op]`, `[δ :dao.space.index/tempid τ n default-op]` — `t` = batch ordinal, `m` = `default-op`.
6. **Incremental publication** via the trees' stored-address marks (dirtiness in the tree) and a recording handle drained at each flush; `publish!` is an explicit composition step, only the `full` retry lives in `run`.
7. **Checkpoint** `{:manifest-address :cursor :ids :batch}` — meaningful only over a medium whose cursors survive the process; over a memory-log a restart is a fresh session, by design.
8. The cross-session ordinal offset for provenance (§3.2) is void after any `gap`.

## What to evaluate
- **Invariants**: the six non-negotiables in `datom.world.md`, and specifically *symmetric ignorance* — does anything in §2–§4 leak knowledge of what is being indexed or who else reads the medium?
- **The two-mode decision (claim 3).** Is the collision argument airtight? Is there a partitioned-allocator alternative that does not require coordination the architecture forbids, and would it be better? Is "never equate `?e` across modes" enforceable or merely advisory? What happens to a ref-valued `v` that is positive on an `:unresolved` medium?
- **Admission and resolution (claims 4–5).** Is admission total (every element classified as row, record, or defect, never a throw)? Can a defect in one element leave a batch half-folded? Do resolution facts interact badly with `current`'s greatest-`t`-wins or with `history` (their `t` is the observer's ordinal, the rows' `t` is the writer's)?
- **Incremental publication (claim 6).** Verify against `store-tree`'s actual semantics in `btree.cljc` that "stores only the dirty subgraph" holds for a tree grown by `conj` against a persistent storage, and that draining the recording handle after a flush cannot lose a blob the manifest references. Trace `out=full` on the intake, retry, and a second `publish!` after more batches: exactly-once per blob and per manifest?
- **`run-on-stream` integration.** Trace `ready?`/`load`/`run` for: a defective batch (cursor advances? state consistent?), a staged-but-`full` publication across two rounds, `blocked`, `end`, and `gap`. Does the observer's known progress-publication defect (documented in `yin.vm.macro.md` §5 prerequisite: a throw after a forwarded batch loses the session) bite the indexer, and if so how?
- **Checkpoint and durability (claim 7).** Is the caveat correct and complete given `dao.space.md` §Fault Tolerance and the transactor's "durability begins at publication"? Does the note's checkpoint conflict with the transactor doc's "one truth" preference?
- **The "live" claim.** The note's motivation says `q` answers "over the program as it grows". What exactly is guaranteed — that the index is current with every batch the observer has *loaded*, and nothing more? Is that stated precisely enough to test?
- **Phases and tests**: are Phase 0′ → 3 correctly sequenced and are the acceptance tests sufficient to catch the defects you find?

Distinguish architectural defects from implementation gaps from intentionally deferred work (§8 Open questions).

## Output Format
Begin your output exactly with:
```text
Completed-GMT: <YYYY-MM-DD HH:MM:SS GMT>
Completed-Local: <YYYY-MM-DD HH:MM:SS local-timezone-name>
Coding-Agent: codex
Session-ID: <your thread id>
Role: Lead System Architect (review) | Model: gpt-6-astra
```
Then findings by severity (`[P1 — blocking]`, `[P2 — must address]`, `[P3 — suggestion/alignment]`), each with file:line, the invariant or evidence, and the recommended correction; then the properties that passed; then an explicit verdict: APPROVE or REQUEST CHANGES.

- Status-Event: 2026-09-13 15:50 +0700 | Model: gpt-6-astra | Status: failed | Rationale: codex turn.failed — ChatGPT usage limit ("try again at 4:13 PM"); thread 01a099f1-87d6-7811-8a69-b3336b956fac captured; three partial leads in .findings.md. Resumed in the same thread as -r2 after the limit lifted.
