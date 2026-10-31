I’m applying the repository review checklist to the prose contractcode contract: first the exact P2 obligations, then each changed paragraph against committed `query.cljc` and the remaining v1 consumers. I won’t run tests or modify files.

The substantive P2 promotions are present: L4 is explicit under Index realization, S1–S3 are stated as properties in the new Snapshots section, ownership is caller-side, and both Decisions are recorded. I found a small amount of remaining/incorrect descriptor language, including one sentence that contradicts `open-published!`’s validation, so I’m checking whether it is isolated or systemic before giving the verdict.

P2 is not complete yet. The rewrite covers the required architecture, but several documentation defects remain before the plan can be considered consumed.

## Findings

### P1 — The coordinate comment contradicts `open-published!`

**File:** `docs/design/dao.space.query.md:62-64`  
**Blocks P2:** yes

The document says query “dispatches on neither” `:dao.stream/type` nor `:dao.stream/bound`. However, `query/open-published!` explicitly checks `:dao.stream/type` at `query.cljc:235-238` and validates the complete coordinate shape.

Concrete correction: distinguish `q` from the caller-facing opener:

> The keys belong to `dao.space.index`. `open-published!` validates that exact coordinate shape; `q` does not dispatch on either key.

### P2 — Source polymorphism does not state Decision 1’s complete view-source list

**File:** `docs/design/dao.space.query.md:94-100,114-119`  
**Blocks P2:** yes

“A datom view over one” reads as a view over a relation value. Later, the document says the nested source may be “a snapshot’s relation,” but the committed API also accepts the snapshot result itself:

```clojure
(query/current (query/snapshot handle))
```

`db-source` recognizes the snapshot map and extracts its relation.

Concrete correction: state that a datom view may wrap a relation value, a snapshot result, or an opened published index. That is Decision 1’s actual list.

### P2 — Some v1 descriptor/bound residue remains

**File:** `docs/design/dao.space.query.md:84-85,445-447,487-490`  
**Blocks P2:** yes

Three passages still use the retired model:

- “which bounded streams a reader names independently”
- “a published index descriptor names a manifest”
- the generic positional-index item’s “bounded tuples, their exact bound”

Concrete corrections:

- Replace “bounded streams” with “relation values and opened indexes.”
- Replace “published index descriptor” with “published index coordinate.”
- Replace the future generic-index wording with finite relation/value terminology; it should not retain an exact stream-bound requirement.

The other bound language found by the scan is legitimate: the published coordinate’s unchanged index-owned shape, temporal `as-of` bounds, snapshot closed-stream outcomes, and the required Decision heading.

### P2 — The shareability paragraph contradicts the relation-transport decision

**File:** `docs/design/dao.space.query.md:139-149`  
**Blocks P2:** yes

The document says “a local value is by definition not shared,” but the newly recorded relation-transport decision correctly says a relation value is plain data that can travel through any stream.

The categorical statement that a result value “is not serializable” is also stronger than the implementation. The result is a plain map containing ordinary result data; the code gives it no reopenable transport identity, but does not prohibit serialization.

Concrete correction: say that query values do not themselves constitute a shared medium or reopenable transport identity. Relation/view/result data may be carried by an ordinary stream when its contents are serializable; coordination still belongs to that medium.

### P2 — `dao.stream.md` adds more than the stated consumer-list correction

**File:** `docs/design/dao.stream.md:695-716`  
**Blocks this commit:** yes, unless separated or explicitly authorized

The remaining-consumer statement is accurate:

- production `dao.space` v1 consumers are `index`, `schema`, and `transactor`;
- `query` no longer requires v1;
- `dao.stream.relation` is absent from the committed tree.

However, against `HEAD`, the diff adds the entire “The v2 namespace is transient” section, including the broader decision that v2 will eventually be renamed to `dao.stream`. It is not merely updating a pre-existing consumer list, and P2 does not authorize recording that broader decision.

Concrete change: either:

- move this whole `dao.stream.md` section to its owning migration/documentation commit and leave P2 scoped to `dao.space.query.md`; or
- obtain explicit scope for the broader decision and review it independently.

## Required items successfully present

The core P2 work is otherwise sound:

- Status and executable-contract pointer are updated.
- The caller opens and owns published indexes; `q` borrows and closes nothing.
- Result DaoStream language was replaced with result-value/`collect` language.
- Snapshotting is a dedicated section naming `snapshot` and `dao.stream.observe/step`.
- S1 is explicit: mint at oldest and snapshot explicitly.
- S2 is explicit: ended, blocked, gap, and defect are data.
- S3 is explicit: effect-before-commit prevents advancing past an unretained value, and the handle is never closed.
- L4 is explicitly promoted under Index realization.
- After-close loudness and lifetime responsibility moved to the caller.
- Both required Decisions are recorded.
- The K-way merge item received only the intended current-capability note.
- Lazy/eager path descriptions match the committed implementation.
- `git diff --check` is clean.

No tests were run, as requested.

**Final verdict:** P2 needs the documentation corrections above. The migration plan is not yet fully consumed; P1 is complete, but P2 is not ready to commit in its current form.
