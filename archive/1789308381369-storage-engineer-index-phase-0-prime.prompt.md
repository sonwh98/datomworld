Created-GMT: 2026-09-13 14:06:21 GMT
Created-Local: 2026-09-13 21:06:21 +07
Coding-Agent: glm
Session-ID: ba9385b9-24c0-4e4a-a5db-4e3376b72636

# Task: index-phase-0-prime

Role: Storage Engineer

Implementers:
- Model: glm-5.3 | Assigned: 2026-09-13 21:06:21 +07 | Status: active | Rationale: long-horizon storage implementation; exceptional at complex programming

## Governing design

Read in full before writing a line of code:
- `docs/design/dao.space.index.as-observer.md` — every section, especially:
  - §2 (state model)
  - §3 (fold-batch grammar, element rules, mode matrix, resolution facts)
  - §4 (session lifecycle: attach, fold, publish!, flush-staged, drain, db-value, checkpoint, restore)
  - §5 (run-on-stream integration)
  - §6 (allocator, watermark)
  - §7 Phase 0′ acceptance criteria (lines 676–721) — this is the acceptance checklist
  - Appendix A (resolved findings, especially A2, A6, A9)
- `docs/design/datom.world.md` §Streams (symmetric peer observer axiom)
- `src/cljc/dao/stream/observer.cljc` (the updated attach 3-arity and run-on-stream carry-session)
- `src/cljc/dao/space/index.cljc` (current state — read completely)
- `test/dao/space/index_test.cljc` (current test suite — read completely)

## File authority

Primary files (you own these completely):
- `src/cljc/dao/space/index.cljc`
- `test/dao/space/index_test.cljc`

You may READ but NOT edit without prior stop-and-request:
- Any file in `src/cljc/dao/stream/`
- Any file in `src/cljc/dao/space/` other than `index.cljc`
- Any btree namespace

If a required dependency demands a change in an out-of-scope file, stop and
describe what you need; do not edit it.

## Acceptance criteria (Phase 0′)

Implement the following in `src/cljc/dao/space/index.cljc` and test in
`test/dao/space/index_test.cljc`. All tests must pass on JVM. Kondo must
report 0 errors, 0 warnings.

### Functions to implement

1. **`fold-batch`** — admits one batch through the outer grammar and element
   rule, allocates ids, records resolution facts, and accumulates into the
   staged tree. Outer grammar: a batch value must be a vector of records or d5
   rows; a bare record or bare d5 row at the top level is admitted (Decision
   §3.x); a malformed outer value is rejected wholesale (`:rejected` +1, one
   defect event, `:batch` advanced, trees/`:ids`/`:max-t` unchanged). Element
   rule: apply the mode matrix (§3.1) — declared-ref `v` tempids resolve in
   `:resolved` mode; undeclared `v` is left alone; `e` tempids allocate; `m`
   tempids resolve and join; reserved eids pass. Admission failure on any
   element is a reject of that element only (not the whole batch unless
   specified). Resolution facts are produced for each allocated tempid.

2. **`publish!`** — flushes the staged tree to the underlying btree store as
   a new manifest + blobs. Returns `{:status :ok}` or `{:status :full …}` if
   the store is full; on `:full`, state is unchanged (the staged tree is still
   held). Carries `:next` in the throw on a terminal outcome after blobs are
   accepted, so the caller can resume publication from a restored session.

3. **`flush-staged`** — returns the current staged tree and clears it (ready
   for the next fold cycle). Called after `publish!` succeeds.

4. **`drain`** — discards the staged tree without publishing. Used after a
   checkpoint to drop in-flight work.

5. **`db-value`** — returns a db snapshot over all published blobs up to the
   current cursor. Queries over it must see every folded row.

6. **`checkpoint`** — produces a **checkpoint candidate**: a plain-data value
   (survives `pr-str`/`read-string`) capturing the current cursor, gap count,
   rejected count, ingress-gaps, and all btree addresses. A candidate is *not*
   promoted to a live checkpoint until it is verified (every address present in
   the store). Verification: walk the candidate's address list and confirm each
   blob is readable; a missing blob → candidate is not promoted. A candidate
   whose manifest is present but one leaf absent is not promoted.

7. **`restore`** — rebuilds a live session from a verified checkpoint
   candidate. Refuses if: mode or schema mismatches; allocator is `:shared`
   (a sibling session allocated after the candidate was captured). On the JVM
   (where the read path is not already `:strong`), a restored session carries
   `bt/default-ref-type*`.

8. **Admitting element rule** — implement §3.1 mode matrix fully:
   - `e`: tempid → allocate; reserved → pass; user-positive → defect on
     `:resolved` (only transactor-assigned positives allowed in resolved mode)
   - declared-ref `v`: tempid → allocate (`:resolved`) / pass (`:unresolved`);
     reserved → pass; user-positive on `:resolved` → defect
   - undeclared `v`: any value untouched
   - `m`: tempid → allocate, join to `e`'s id; reserved → pass

9. **Outer grammar** — §3.x: a batch is a vector; elements are records
   (maps with `:e`) or d5 rows (`[e a v t m]`). A bare record or bare d5 row
   at the top level is admitted. A malformed outer value rejects the whole
   batch.

10. **Resolution facts** — for each allocated tempid: emit `[id :dao.space.index/batch t] [id :dao.space.index/tempid neg]`.

### Required tests (§7 Phase 0′ checklist — all must be present)

Cover every item in the §7 Phase 0′ acceptance criteria list (lines 676–721
of `dao.space.index.as-observer.md`), including:
- Enough rows and insertion orders to force btree splits
- Outer grammar cases: bare record, bare d5 row, vector of both, malformed
  outer value, malformed nested record, valid prefix + defect
- valid–invalid–valid batches; empty batch (ordinal only)
- reject → drain → checkpoint → restore with `:rejected` intact
- Mode matrix: one row per cell, both modes, reserved `e` passing on `:resolved`
- `m` tempids resolved and joined
- Deterministic allocation order (within-row `e`, `v`, `m`)
- `gap` immediately before `checkpoint` → candidate carries recovery cursor +
  incremented gap count
- `:max-t` nil on empty stream; reopened empty checkpoint → watermark 0
- `run-on-stream` blocked/end behaviour
- Publication state machine: ok/full/full/ok; refusal at manifest; terminal
  outcome after accepted blobs with `:next` preserved; successful resumption
  then read failure; second `publish!` after more batches appends only new blobs
- Checkpoint candidate round-trips through `pr-str`/`read-string`
- Candidate not promoted if manifest missing or leaf absent
- `restore` refuses mismatched mode/schema and `:shared` allocator
- F2 hazard: `:test`-ref storage → cleared refs → query throws "missing index
  segment"; `:strong` storage → no throw
- On JVM: restored session carries `bt/default-ref-type*`
- A `:resolved` session attached at `:oldest` over a transactor's local
  memory-log produces trees logically equal to `index-datoms` over
  `snapshot-datoms` of the same log

### Invariants (never violate)

- `dao.space.index` is a **symmetric peer observer** of `dao.stream`. It
  never evaluates; `yin.vm` never consults it. No import of any `yin.*`
  namespace.
- The index never creates, appends to, or closes any medium.
- The allocator contract: a `:shared` allocator is one shared with another
  session; restoring over it is a defect (two sessions would collide).
- Do not weaken any existing test.
- Preserve all existing comments and docstrings unrelated to your changes.

### Verification

After implementation, run:
```
clj -M:test -n dao.space.index-test
clj -M:kondo --lint src/cljc/dao/space/index.cljc test/dao/space/index_test.cljc
git diff --stat
```

Begin the final response exactly with:
Completed-GMT: <YYYY-MM-DD HH:MM:SS GMT>
Completed-Local: <YYYY-MM-DD HH:MM:SS local-timezone-name>

Report: changed files, exact test counts, kondo result, unresolved concerns,
and any items from the §7 checklist that are incomplete (with reason).
Do not claim edits or tests that did not occur.
