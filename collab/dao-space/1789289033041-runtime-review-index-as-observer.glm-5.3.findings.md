[claude-code:unrecognized_model] {"model":"glm-5.3","query_source":"sdk"}
[claude-code:unrecognized_model] {"model":"glm-5.3-flash","query_source":"auto_mode"}
The assigned review is complete. Summary of what was delivered in the structured output above:

- **Claims 1–8 hold** against the code (most pinned by existing tests — notably `mixed-state-incremental-store-test`, which directly proves the node-granularity incremental-store claim); **claim 9 partially holds**.
- **2 P2 findings**: (F1) Phase 0′'s `pr-str`/`read-string` round-trip test premise is false — a live session isn't plain data (`BTSet` prints elements-only and reads back as a host set; `:storage` prints unreadably); (F2) draining the recording handle is unsafe on the resumed-checkpoint path on the JVM — `-store-tree!` overwrites the wrapper's restore source (`btree.cljc:1253`) with the recording handle, and a soft-ref'd root then refaults through the drained handle → "missing index segment".
- **4 P3 findings** (namespace reservation stated as contract, retraction rows on `t 0` media, two overstated wordings).
- **Verdict: REQUEST CHANGES** (narrow — no P1s; every load-bearing claim about today's code is true).

The coordination plan file records that this was a read-only deliverable with no implementation phase; nothing in the repository was modified.

---
Full structured review (recovered from the plan-mode plan file `/Users/sto/.claude-glm/plans/read-collab-1789289033041-runtime-review-witty-stroustrup.md`, since `--permission-mode plan` routed the deliverable there instead of stdout):

# Plan: complete collab/1789289033041 (runtime review of dao.space.index.as-observer.md)

## Context
The collab prompt asks for a **read-only runtime & code-contract review** of
`docs/design/dao.space.index.as-observer.md` (branch `dao.stream-redesign-v2`,
HEAD `5296ee5`): verify 9 claims the note makes about existing code, print
structured findings to stdout, write no files. There is nothing to implement —
the deliverable is the printed review.

## What was done (all read-only)
- Read the target note in full (466 lines).
- Verified every claim against source: `dao/space/index.cljc`,
  `dao/data/btree.cljc` (node representation, IStorage, conj, store-tree,
  restore-tree, make-store-ref, ref types), `dao/data/btree/storage.cljc`,
  `dao/stream/observer.cljc`, `dao/stream/observe.cljc`,
  `dao/space/query.cljc`, `dao/space/transactor.cljc`, `dao/datom.cljc`,
  `dao/jing.cljc`, plus `btree_durability_test.cljc` and the framing docs
  (`datom.world.md` §Streams, `dao.space.md` write path/fault tolerance,
  `dao.space.transactor.md` open items, `yin.vm.macro.md` §4.2/§5,
  `dao.space.index.md`).

## Result
- Claims 1–8 verified as holding (several with caveats), claim 9 partially.
- 2 P2 findings (pr-str round-trip test premise false; JVM root-refault
  through a drained recording handle on resumed checkpoint sessions),
  several P3s, no P1s. Verdict: REQUEST CHANGES (narrow).
- Full structured findings printed to stdout in the required format
  (Completed-GMT header, per-claim verdicts with file:line evidence,
  severity-tagged findings, verdict).

## Next steps
None in this repo — the review is the deliverable. The note's author fields
the P2/P3 findings before Phase 0′.

---
Full structured report (recovered from the session transcript; the plan-mode run surfaced only its final summary on stdout):

Completed-GMT: 2026-09-13 08:58:09 GMT
Completed-Local: 2026-09-13 15:58:09 +07 (ICT)
Coding-Agent: glm
Session-ID: 8fc82a93-61d0-4e6e-b53e-e8e9b90eba14
Role: VM Runtime Review | Model: glm-5.3
```

# Runtime & Code-Contract Review — `dao.space.index.as-observer.md`

Repo `/Users/sto/workspace/datomworld`, branch `dao.stream-redesign-v2`, HEAD `5296ee5`. Note read in full (466 lines); every claim below checked against the named source.

## Claim 1 — §2.2 step 1 / admission: **holds**

`element-datoms` rejects negative `e` through `datom/local-datom?` — `src/cljc/dao/space/index.cljc:384` (raw-row path) and `:378` (tx-record path); the rejection is `local-datom?`'s `(not (neg? (nth x 0)))` at `src/cljc/dao/datom.cljc:52`, with the 5-vector fallthrough throwing "malformed local datom" at `index.cljc:385-389`. `datoms-from-elements` (`index.cljc:397-405`) is `mapcat` over the same per-element rule, so its throwing contract is what `element-datoms` does.

The proposed admitting rule is expressible as a variant with no strain: it is exactly `local-datom?` minus the `e ≥ 0` clause (`integer? e`, namespaced keyword `a`, `t ≥ 0`, `integer? m` — datom.cljc:49-57). Notably *simpler* than the note implies: `local-datom?` never constrains `v`, so "negative declared-ref `v` admitted as tempid" needs no schema knowledge at admission — negative `v` is already admissible as a value (`datom.cljc` constrains only slots 0/1/3/4); the schema first matters at resolution (step 2). One unspelled edge: the tx-record path also enforces `every? local-datom?` + one shared `t` (`index.cljc:372-379`), so on an `:unresolved` medium a tx record's positive-`e` datoms hit the mode rule and defect the whole batch — consistent, but the note should say one sentence about record-shaped elements on `:unresolved` media.

## Claim 2 — §2.2 step 3 / persistent fold: **holds**

`bt/conj` (`src/cljc/dao/data/btree.cljc:1742-1778`) is a persistent insert: `UNCHANGED` returns the same set (duplicate no-op, `:1751` — this is also what makes "a duplicate row is a no-op" true); new wrappers carry `cnt+1`, `address nil`, sharing `sett` and unmodified children by reference; the single-replacement branch shares the keys array outright when the separator key is unchanged (`:696-699`). On **restored** trees: `conj` faults the root via `resident-root` (`:1227-1241`), descends via `node-child`'s fault path (`:1030-1050`), and new nodes carry the durable settings; pinned by `mixed-state-incremental-store-test` and the conj/disj-on-restored tests in `test/dao/data/btree_durability_test.cljc:228-262, 368-398`. On the **empty set**: `empty-root` is a 0-length Leaf (`:1527-1529`); insertion takes the `(< len bf)` leaf branch (`:520-526`); `from-sequential` of `()` yields cnt 0 (`:2039`), and `restore-tree` of a nil address is the documented way to get an empty set carrying a storage's settings (`:2096-2098`, used by the test fixture at `btree_durability_test.cljc:88-91`).

## Claim 3 — §4.1 / dirty-tracking: **holds**, with one unmodelled gap (→ finding F2)

Dirtiness is per-**node**, not per-root: `node-store` skips every child slot that already carries an address (`btree.cljc:1059-1064`; the comment there says outright: "this is what makes incremental re-publish proportional to the changed path"). The sentence the note quotes — "no-op returning the existing address when the set is already stored" — is a *second, coarser* no-op at **set** granularity (`-store-tree!`, `:1248-1256`; pinned by `store-tree-idempotent-test`, `btree_durability_test.cljc:267-270`). Both exist; the incremental claim rests on the per-slot skip, which is real and tested (`mixed-state-incremental-store-test`: one insert into a restored 1000-element bf-16 tree stores ≤ 4 nodes and clean siblings keep their original addresses). Marks live in the tree (branch `addresses` arrays + the wrapper's `address`), never in the handle, so **`store-tree` itself needs nothing from a drained handle** — the drain claim, as scoped to "the next `store-tree`", is true. `publish-index!` today does rebuild into a fresh recording handle per call (`index.cljc:559`) from mark-less `from-sequential` trees (`:561-563`) — hence O(tree) per publish, as the note says.

The gap the note never considers: draining is safe only while *nothing refaults through the drained handle*. See finding F2.

## Claim 4 — §4.1 / blob order: **holds**

`recording-content-handle` records first-insertion order, deduplicated by address (`:present` for repeats) — `index.cljc:449-470`, whose docstring already states "the recorded order is exactly the store-tree children-before-parent traversal". That traversal is enforced structurally: `node-store` stores all children before `(-store storage this)` (`btree.cljc:1064-1066`), and `-store` is `jing/materialize!` on the blob (`storage.cljc:43`), so addresses only exist after children do. Manifest-last is the caller's append discipline (`index.cljc:582-583`), which the note preserves. In the incremental composition clean nodes are never put at all, so the blobs recorded since the last flush are exactly the newly dirty payloads, in append-safe order. `jing/materialize!`'s verify-on-`:present` idempotence (`jing.cljc:251-281`) backs the "second line of defence" claim.

## Claim 5 — §2.3 / `current` and `history` over mixed `t`: **holds** under an unstated convention (→ finding F3)

`current-state-seq` (`src/cljc/dao/space/query.cljc:76-103`) keys on `[e a v]`: greatest `t` wins (`:91-92`), same `[e a v t]` with differing `m` throws (`:93-97`). Resolution facts put `a` in `:dao.space.index/{batch,tempid}`; observed rows put `a` wherever the medium puts it. With disjoint `a`, the `[e a v]` keys are disjoint — no cross-shadowing, and the `m`-conflict rejection is unreachable *between* the two kinds. Resolution facts never collide among themselves (per `δ`: one `batch` fact with unique `v = n`, one `tempid` fact with unique `v = τ`). In `:resolved` mode no facts are emitted at all (§2.2 step 2), so the question is empty there.

But the disjointness is a **convention, not a mechanism**. Nothing in admission inspects `a`'s namespace (and decision 6 says it shouldn't); in `:unresolved` mode an observed row with `a = :dao.space.index/batch` and a `v` that numerically equals a batch ordinal (resolved refs run from 16 up, and so do long sessions' ordinals) shares `[e a v]` with a resolution fact — greatest-`t` then shadows silently, and a same-`t` meet throws at query time. Related, pre-existing: on a `t 0` medium, an assert and a later retract of the same `[e a v]` both carry `t 0` → the `m`-conflict throw; the note scopes such media to "assertions with no retractions" but nothing checks it. Both belong in the note as stated contracts (F3/F4).

## Claim 6 — §2.1 / `run-on-stream` integration: **holds**

Traced against `src/cljc/dao/stream/observer.cljc:183-208` and `observe.cljc:82-126`:

- **Defective batch** — `fold-batch` is total (defects recorded, §2.2 step 1), so the load effect answers `:ok` (`observer.cljc:193-196`) → `:advance` → `run` (`flush-staged`, identity after a fold — folds never stage) → cursor advanced past the bad batch. ✓
- **Staged-`full` publication** — `ready?` false at round entry → `run` retries *before any read* (`:184-188`); still unready → session returned with cursor unchanged. ✓
- **`blocked`/`end`** — session returned, cursor retained (`:207`). **`gap`** — recovery cursor adopted, consumer untouched (`:204-205`): the skipped batch is never folded, ordinals stay dense over *folded* batches — exactly the offset-shift §3.2 warns about. ✓
- **The §5 progress-publication defect** (`yin.vm.macro.md` §5, "Prerequisite on the observer"): for *this* consumer it cannot fire mid-round. The trigger is a throw **after a forwarded batch**; the only such site is `run`-at-`:advance` (`:198`), and there `run` is identity (nothing is staged after a load — publication is an explicit between-rounds step, §2.1), while `load` never throws on input. A staged-retry throw at round *entry* propagates with the caller still holding exactly the session it passed — no successor ever existed, nothing is lost. Out-of-round, a throwing `publish!`/flush loses the staged list only if the composition drops the pre-publish state; re-derivation works because the recording handle keeps `:order` until manifest-acceptance and the tree marks survive in shared nodes (re-`store-tree` no-ops), and re-appended duplicates are benign under content addressing. The §5 fix (session in `ex-data`) is indeed still pending in code (`observer.cljc` carries no `:session`) and is correctly scheduled into Phase 0 (note §7).

## Claim 7 — §3.1 / mode collision: **holds — the rule is necessary, not cautious**

`datom/first-user-id` = 16 (`datom.cljc:27-39`); `local-datom?` admits *any* non-negative `e` (`:51-52`). The transactor allocates **no eids at all**: callers supply `:db/id` (`transactor.cljc:56-71`) or explicit `e` (`pad-datom`, `:74-90`); the watermark `derive-next-t` derives is over `t` only (`:117-131`). So writer-side ids are caller-chosen, uncoordinated non-negative integers, and the observer's allocations run from 16 upward on the same number line — collision is structural and no watermark rule addresses it (a `t`-watermark says nothing about `e`). If anything the note *understates* the exposure: its example ("the writer's next transaction emits 101") paints the writer as a sequential allocator, when in code the writer's ids are free-form caller choices. Two modes, fixed at construction: necessary. ✓

## Claim 8 — §4.2 / checkpoint resume: **holds**, with F2 living on exactly this path

`restore-tree` (`btree.cljc:2086-2098`) is lazy (nothing fetched until traversed; root restored only via `resident-root`), adopts the storage's Settings, and `conj` grows it: descent faults slots through `node-child`, new nodes carry the durable settings, and both conj branches preserve clean slots' addresses while leaving new slots dirty (`:703-712` single-replacement, `:713-745` two-node, `:746-800` split; pinned end-to-end by `mixed-state-incremental-store-test` and `disj-on-restored-tree-test`). `restore-tree` does need `cnt` (O(1) `count`; `conj` increments from it) and the branching factor via the storage's settings (`:2090-2093`; `storage.cljc:70` "pass the manifest's :branching-factor here"). The checkpoint shape carries enough **because the manifest does**: `valid-manifest?` requires exactly `{:indexes :count :branching-factor}` (`index.cljc:250-266`), `publish-index!` writes both (`:580-581`), and `restored-indexes` threads both into `restore-tree` (`:297-317`), reachable through `query/open-published!` (`query.cljc:227-258`) — which is the lazy open §4.2 names. Two caveats: F2 (below) is the resumed-session path, and the "watermark … is `(max t)` over the checkpointed index's rows — a query, not a scan" is rhetorically overdrawn — `t` is not a sort key in any covered order, so max-`t` is still O(rows); the real win is not touching the stream, not the asymptotics (F5).

## Claim 9 — cross-platform: **partially holds** (→ F1, F2)

The mechanisms are portable: `dao.space.index` is one `.cljc` requiring only `.cljc` deps (`index.cljc:35-39`); the additions are atoms, integers, keywords, btree values, and pure functions; `compare-vals` is already host-conditionaled (`index.cljc:57-74`); `kv-storage`'s default ref-type is per-host with `:strong` off-JVM (`btree.cljc:39-44`), so cljs/cljd never even see the soft-ref behavior behind F2; `dao.stream.observer` is one `.cljc`. What does **not** hold cross-platform (or anywhere): Phase 0′'s `pr-str`/`read-string` round-trip premise (F1).

---

## Findings

**[P2 — must address] F1. Phase 0′'s serialization test premise is false: a live session is not plain data.** `dao.space.index.as-observer.md:414-416` tests "ids stable across a `pr-str`/`read-string` round trip of the session (it is plain data plus btree values)". A `BTSet` prints as `#{…}` — elements only (`btree.cljc:1500-1512`) — and reads back as a *host* set: comparator, `:storage`, address marks and `cnt` are all gone (so `subseq-from` on the result fails); the session's `:storage` is a `KVStorage` over closures (`storage.cljc:38-62`) that prints as an unreadable `#object` on `:clj` (read-string throws), and the `:observer`'s stream handle is no more printable. No reader/tag exists for any of this. The test item needs redefining — round-trip the *checkpoint* value, or assert equality of `restored-indexes` against a re-opened manifest — before Phase 0′ can start.

**[P2 — must address] F2. Draining the recording handle is unsafe on one real path: JVM, resumed checkpoint session, root refault.** §4.1's drain claim is scoped to "the next `store-tree`" and that scope is safe (marks live in the tree). But `-store-tree!` **overwrites the set wrapper's restore source with the store argument** (`btree.cljc:1253`, `set! storage storage'`), while `resident-root` refaults the root through that wrapper field (`:1234`). In a resumed session the trees' settings come from the durable store (`restore-tree` → `-settings`, `:2095`), so `make-store-ref` wraps the root in the configured ref-type (`:227` → `make-ref`) — `:soft` on the JVM (`:39-44`). Sequence: resume → publish through the recording storage → flush accepted → handle drained → GC clears the root soft-ref → next `fold-batch` descent or query faults the root through the *drained* handle → "missing index segment" (`storage.cljc:48-50`). Child slots are safe (they fault through `settings-storage`, the durable store, `btree.cljc:1042-1050`), and from-` :oldest` sessions are safe (their settings carry no storage, so `make-store-ref` pins strong, `:227`); the hazard is exactly the checkpoint path §4.2 adds. The note should specify the invariant: after a flush, a tree's refaults must resolve against durable content, never a drained handle (pin the root strong for index sessions / re-restore after publish / keep content and drop only `:order` with the growth claim restated).

**[P3 — suggestion/alignment] F3. The `:dao.space.index/*` reservation is conventional only — state it as a contract.** Claim 5's disjointness (and with it both the no-shadowing and no-`m`-conflict conclusions) rests on observed media never writing the observer's attribute namespace. Nothing enforces this and decision 6 argues against inspecting `a`. One sentence in §3.2 — media must not assert `:dao.space.index/*` attributes; a medium that does owns the collision semantics — makes the claim 5 argument airtight.

**[P3 — suggestion/alignment] F4. Retractions on a `t 0` medium hit the `m`-conflict throw at query time.** Assert-then-retract of the same `[e a v]` on a `t 0` medium yields two rows with the same `[e a v t]` and differing `m` → `current-state-seq` throws (`query.cljc:93-97`). The note scopes such media to "assertions with no retractions" but neither admission nor the fold checks it. Say whether retraction rows on such media are defects or accepted-with-thrown-`current`.

**[P3 — suggestion/alignment] F5. Two wordings overstate.** (a) §4.2 "a query, not a scan": max-`t` over the checkpoint's rows is still O(rows) in any covered order — the honest win is not replaying the stream, not asymptotics. (b) §2.2 step 3's "dirty-tracked against the trees' `:storage`": there is no tracking *against* storage — dirtiness is the absence of address marks on nodes (`btree.cljc:1059-1064`), independent of any handle. Also worth one sentence: tx-record-shaped elements on an `:unresolved` medium defect via the mode rule (positive `e`), and `v`-side-only tempids (a ref to an entity never used as `e` in the batch) still get an id and resolution facts — both consistent with the text, both currently inferred.

## Payload-agnosticism

**Holds.** No mechanism in §2–§4 reads `:yin/*` or any attribute semantics: admission is d5-shape only (claim 1); ref-ness comes solely from the supplied `:schema`; resolution facts live in the observer's own namespace; publication and the checkpoint treat blobs as opaque. `yin.vm/schema` / `event-schema` appear only as composition-supplied examples, and the §3.2 provenance join is explicitly the composition's. This matches the peer-observer invariant as stated in `datom.world.md` §Streams (`:44-55`), which names this exact composition, and `dao.space.index.md`'s *Open items* already points at this note as the incremental-indexing design — the doc set is aligned.

## Verdict

**REQUEST CHANGES** — narrow. All nine claims hold against the code (claim 9 partially), and the architecture — one coordination loop, persistent incremental trees, publication as explicit composition — is verified sound by existing tests (`mixed-state-incremental-store-test` in particular pre-answers the incremental-claim question). What must be fixed before Phase 0′ is F1 (a test item that cannot pass as written) and F2 (a JVM-only, GC-timed crash path on exactly the checkpoint-resume feature Phase 3 promises), plus the F3–F5 contract/wording tightenings. No P1s: nothing in the note's load-bearing claims about today's code is false.

---

The review is complete and printed above — no files were written in the repository. Since this task was read-only with nothing to implement beyond the deliverable itself, the plan on file simply records that.
Confirmed — those two untracked docs files predate this session (present in the initial snapshot); no tracked or new files were touched. The review is printed above in the required format, per the collab prompt's "read-only, print to stdout, write no files" contract.
The assigned review is complete. Summary of what was delivered in the structured output above:

- **Claims 1–8 hold** against the code (most pinned by existing tests — notably `mixed-state-incremental-store-test`, which directly proves the node-granularity incremental-store claim); **claim 9 partially holds**.
- **2 P2 findings**: (F1) Phase 0′'s `pr-str`/`read-string` round-trip test premise is false — a live session isn't plain data (`BTSet` prints elements-only and reads back as a host set; `:storage` prints unreadably); (F2) draining the recording handle is unsafe on the resumed-checkpoint path on the JVM — `-store-tree!` overwrites the wrapper's restore source (`btree.cljc:1253`) with the recording handle, and a soft-ref'd root then refaults through the drained handle → "missing index segment".
- **4 P3 findings** (namespace reservation stated as contract, retraction rows on `t 0` media, two overstated wordings).
- **Verdict: REQUEST CHANGES** (narrow — no P1s; every load-bearing claim about today's code is true).

The coordination plan file records that this was a read-only deliverable with no implementation phase; nothing in the repository was modified.
