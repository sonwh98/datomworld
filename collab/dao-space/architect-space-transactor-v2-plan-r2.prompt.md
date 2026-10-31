Created-GMT: 2026-09-08 13:28:12 GMT
Created-Local: 2026-09-08 20:28:12 +0700 (Asia/Bangkok)
Coding-Agent: claude
Session-ID: 6378e1b0-1c5d-4e10-8ea0-61551c03c828
# Task: fold section 7 into the transactor plan as Phase 5 (r2)
Role: Lead System Architect

**The owner has taken section 7: index closes in this sweep.** The schema
constraint is relaxed exactly that far — you may move
`:dao.space.schema/published` onto `index/read-datoms` and delete index's
published adapter. Schema's own migration remains its own plan.

The owner did not overrule `create!`, so that name stands.

Revise the plan as follows. Emit the **whole revised plan** to stdout, not a
diff — it is going to `docs/design/` and then to review.

1. **Promote section 7 to Phase 5**, with the same rigour as Phases 1-4: what
   is built, what is deleted, what proves it, and which invariants each test
   now pins. Name the ten `index_test` `ds/open!` adapter tests you are
   deleting and say, per test, which surviving test or invariant covers the
   property — the index plan's P-table is the reference. A deleted test whose
   property is not covered elsewhere is a defect, not a saving.
2. **Restate D7.** It currently says index keeps `[dao.stream :as ds]`. With
   Phase 5 that is false. Say precisely what index requires at the end, and
   confirm against the tree that no other `ds/` use survives in
   `index.cljc` — I measured `snapshot-datoms` (:451-468) and the published
   adapter (:344-404) as the only two, but verify rather than trust me.
3. **Update the end condition** to include index: it must state that
   `dao.space.index` and `dao.space.transactor` both require no
   `dao.stream`, that `dao.stream.md`'s consumer list drops both, and that
   `dao.space.schema` is the only `dao.space` namespace left on v1, with its
   remaining surface named. I measure schema's v1 surface as 21 sites across
   seven APIs: `ds/open!` ×4, `ds/closed?` ×4, `ds/close!` ×4,
   `ds/strict-vec` ×2, `ds/realization?` ×2, `ds/defopen` ×2
   (`:dao.space.schema/current`, `:dao.space.schema/published`), and the
   `SchemaWrapper` protocol implementations — verify and correct.
4. **Phase 5's schema edit is bounded.** It replaces one `defopen` body. Say
   explicitly what it must *not* touch: `:dao.space.schema/current`, the
   `SchemaWrapper`, `ds/strict-vec` elsewhere, and schema's own closedness
   model beyond the six edits Phase 3 already forces.
5. **The ring-buffer capacity hazard deserves its own subsection**, not a
   note. I verified it: v1's `{:dao.stream/type :ringbuffer}` with no
   `:capacity` never evicts (`ringbuffer.cljc:82`, `(and capacity …)`),
   while v2's `valid-spec?` requires a **positive integer** capacity
   (`v2/ringbuffer.cljc:15-20`) and evicts. There are **28** such unbounded
   opens across `src` and `test`. The transactor is the worst possible place
   for it, since `derive-next-t` and `publish-index!` both require complete
   retained history, so an undersized capacity is a silent causality break at
   the next open or publish rather than a compile error. State the rule the
   implementer must follow for every one of the 28 sites it touches, and how a
   test would catch a violation rather than pass by luck.

Everything else stands as written.
