Created-GMT: 2026-09-07 08:30:07 GMT
Created-Local: 2026-09-07 15:30:07 +07 (Asia/Bangkok)
Coding-Agent: claude
Session-ID: e425d8bd-ad4c-44f7-aaed-54cb3196fd0f
# Task: dao.jing migration plan — revision 6, the in-place cutover
Role: Lead System Architect (author seat)
Implementers:
- Model: claude-fable-5-1 | Assigned: 2026-09-07 15:30:07 +07 | Status: active | Rationale: resumed author session; owns every decision this revision keeps or retires

Revision 5 is committed as `cdb871c` and is now the historical record. This is
a **reshaping, not a splice**: the user has ruled that the migration's shape
changes, and the plan follows.

Produce the complete revision 6 as the body of your final response. Do not
edit files. The orchestrator overwrites
`docs/design/dao.jing.v2.implementation-plan.md` with it.

## The ruling, and why

**`datom.world` has nothing in production.** There is no compatibility
constraint, no stored content anyone depends on, and no user to protect during
a migration. The `.v2.` namespace shape exists — by the stream plan's own words
— "to protect the running system during migration". With no running system, it
is protecting only the test suite, and it is not worth its cost.

So: **an in-place cutover, with no `.v2.` namespace anywhere in `dao.jing*`.**

The user's exact words: "you can write dao.jing.v2 from scratch with no
dependencies on v1 … anything you need from v1, just copy it over", then, on
being shown that a parallel core costs ~675 duplicated lines: "I prefer path b"
— rewrite in place and migrate the dependent tests in the same change.

## What revision 6 does

- **`dao.jing` core and observer: rewritten in place.** The observer takes the
  v2 design from Decision 1 — composition-minted opaque cursors, totality over
  `dao.stream/outcomes-next`, `adopt-cursor`, defects as data. The
  content-addressing core is unchanged code; only its `dao.stream` require and
  the observer's body change. No `dao.jing.observer`, no `dao.jing.v2.*`.
- **`dao.jing.file`: rewritten in place**, stream coupling removed, private
  framing, three host branches with `:cljd` first.
- **`dao.stream.log`: deleted.** Its last consumer is gone. No scope question
  now arises — nothing is orphaned across a boundary, it simply dies with its
  only caller. (`dao.stream.file`, the live-tail `:file` transport consumed by
  `yin.io.file`, is a different transport and is untouched.)
- **The nine observer test files and `btree_durability_test` are migrated in
  the same phase as the code that breaks them.** No `dao.jing.observer` to
  repoint to; they move to the v2 observer directly.
- **`dao.jing.remote`: left on v1, untouched, and deferred with its reason.**
  This is the one piece that cannot cut over here: deleting `connect-content!`
  removes the synchronous handle that `dao.space.index:389` reaches through
  `jing-coordinate/open!`, and that `test/dao/space/stigmergy_test.clj:80,150,
  405,411` and `test/dao/space/index_test.cljc:571-574` drive directly.
  Replacing it forces the async B-tree hydration that `dao.data.btree.md` §5.4
  defers, which belongs to `dao.space`'s plan. Say so explicitly.

## What is retired from revision 5

The parallel-namespace machinery and everything that existed to serve it: the
`dao.jing.v2.*` namespaces, `dao.jing.observer`, the J5 rename phase, the
end-state naming decision, the transitive-dependency gate and its
documentation-route fallback, both scope-contingent items and their
alternatives, and the `dependent` lifecycle row's reason for being a *plan*
concern. Also retired: the byte-for-byte on-disk guarantee and its
golden-record compatibility test — with no stored content, the framing is free.
The torn-tail recovery tests **stay**, reclassified: they are correctness
evidence for the truncation code, not compatibility evidence.

## What must survive, and be preserved rather than deleted

- **Decision 1 in full** — it is unaffected by the shape change.
- **Decision 2's reasoning** for why the durable log was never a stream. Keep
  the argument, including the no-wait analysis and the recorded `gpt-5.6-sol`
  dissent; drop only the byte-compatibility clause it no longer needs.
- **The Host-Boundaries open item** — "the content write path as an effect
  stream" — unchanged; it predates all of this.
- **Decision 3 in full, explicitly deferred rather than dropped.** The stepped
  client, `request-materialize`, the step order, and the verify-hop lifecycle
  table cost four review rounds and are correct. They are the design the
  future `dao.jing.remote` migration implements, under `dao.space`'s plan.
  Carry them as a recorded design with that home named. Note that its
  `dependent` row is now moot: the `dao.stream.rpc` fix it waited on landed
  in `39ad69e` — `allocation-failure` now discharges `:outstanding`, so that
  cell is an ordinary loss row whenever the work is picked up.

## Constraints on the phasing

- **Every phase boundary must be green on clj, cljs (Node) and cljd.** An
  in-place cutover has no parallel twin to fall back on, so a phase that
  leaves the suite red is not a phase. Say which files move together and why.
- Name explicitly what remains on v1 after this plan and why, per namespace:
  `dao.jing.remote` (blocked on `dao.space`), `dao.jing.coordinate`'s JVM
  remote branch (via the above), `dao.jing.dht*` (UDP, deferred by the stream
  plan). The end condition should claim only what the plan delivers.
- Keep the phase-level test lists. They are what makes a red-in-the-middle
  cutover recoverable, and they are the implementer's actual contract.

Be shorter than revision 5. Most of its length was machinery this revision
retires. End with a short list of what changed from revision 5 and why.

Begin the final response exactly with:
Completed-GMT: <YYYY-MM-DD HH:MM:SS GMT>
Completed-Local: <YYYY-MM-DD HH:MM:SS local-timezone-name>
Coding-Agent: claude
Session-ID: e425d8bd-ad4c-44f7-aaed-54cb3196fd0f
