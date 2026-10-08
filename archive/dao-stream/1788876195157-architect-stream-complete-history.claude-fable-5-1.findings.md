Completed-GMT: 2026-09-08 14:41 GMT
Completed-Local: 2026-09-08 21:41 +0700 (Asia/Bangkok)
Coding-Agent: claude (Opus 5)
Session-ID: 6378e1b0-1c5d-4e10-8ea0-61551c03c828

# Contract: how a consumer requiring complete history says so

## The defect is confirmed

Verified against the tree, not taken on report:

- `v2/ringbuffer.cljc:62` — `:dao.stream/oldest` mints
  `{:dao.stream.ringbuffer/position (:first s)}`.
- `:126` — `append!` advances `first'` to `(max (:first s) (- n capacity))`, so
  `:first` *is* the eviction watermark.
- `:87-96` — `next` answers `gap` only when `pos < (:first s)`.

A cursor minted at `:oldest` after an eviction therefore has
`pos = (:first s)`, which is never less than itself. It reads the surviving
suffix and reports nothing, because nothing is wrong with the cursor. The
review's conclusion holds: a reopened transactor can derive `next-t` from a
truncated history and `publish-index!` can publish a truncated index, both
silently, and no test written against a ring buffer can catch either.

The plan was wrong about this, and wrong in a way worth naming precisely: it
stated a completeness invariant and then defended it with a *capacity* rule
(r2 §6). Capacity is the wrong knob. A larger window makes loss less likely
and never makes it reported. Section 5 below says what replaces that rule.

## The judgement asked for: both mechanisms stand, and only one gives completeness

They answer different questions and are not alternatives:

- **A transport that declares it excludes `gap` gives completeness.** The
  history is complete because the transport cannot lose it.
- **A kept origin cursor gives detection.** The history may be incomplete, but
  the consumer is told so and applies its own policy.

Where completeness is a *correctness* requirement, only the declared transport
delivers it; a kept origin cursor is then the honest failure mode, not a
substitute. Where a consumer can proceed on a suffix but must know it is on
one — a viewer, a monitor, a resumable forwarder — the origin cursor is right
and sufficient. On a gap-excluding transport a freshly minted `:oldest` *is*
the origin, so keeping one costs nothing and buys nothing.

Both are already in the contract's vocabulary; neither needs new surface.

---

# The edits

## 1. `## Cursors` — two new bullets

Insert immediately after the bullet beginning "A composition that intends to
observe events caused by an operation mints its `:dao.stream/newest` cursor
**before** invoking that operation" (currently :484-487), so the two
anchor-timing rules read together.

> - **A freshly minted `:dao.stream/oldest` is a position, not a claim about
>   completeness.** The anchor names the earliest *retained* position, which on
>   an evicting transport advances as values are lost. A `gap` is reported to a
>   cursor that spans an eviction; a cursor minted after one is a valid cursor
>   onto the surviving suffix and reports nothing, because nothing is wrong
>   with the cursor. Retained history is not complete history, and no anchor
>   can make it so. A consumer that requires complete history gets it from the
>   transport's declared retention (see *Retention and Gaps*), never from an
>   anchor.
> - A composition that intends to observe a stream's complete history mints an
>   **origin cursor** — `:dao.stream/oldest` before the first append — and
>   keeps it, exactly as a composition that intends to observe events caused by
>   an operation mints `:dao.stream/newest` before invoking it. A kept origin
>   cursor converts a silent loss into a reported `gap`. It cannot be minted
>   afterwards and cannot be reconstructed: a consumer that arrives later holds
>   no evidence about what preceded it.

## 2. `## Retention and Gaps` — replace the closing sentence

The sentence at :655-657 currently reads:

> Silently skipping evicted history is forbidden — a reader must be able to
> know it missed values.

As written this is the sentence a reader would rely on and it is misleading:
it promises more than the mechanism delivers. Replace with:

> Silently skipping evicted history is forbidden — a reader holding a cursor
> across an eviction is told it missed values. This is a promise to a
> **cursor**, not to a handle: it is precisely what a kept cursor is worth, and
> it is why a consumer that must know mints one at origin rather than asking
> later (see *Cursors*).

## 3. `## Retention and Gaps` — new subsection at the end of the section

> ### Complete history
>
> Some consumers require *complete* history rather than *retained* history: an
> interpreter that derives state by replaying a log from its beginning is
> wrong, not merely stale, if it replays a suffix. The contract already carries
> what such a consumer needs, in two mechanisms that answer different
> questions.
>
> **A transport that excludes `gap` gives completeness.** This is the existing
> declaration in its existing form — the outcome's precondition is impossible
> by the transport's nature, an unbounded log never evicts, so never reports
> `gap` (see *Surfaces*). Retention is declared through the transport's nature
> and, for a locally created stream, through its creation specification; it is
> configuration provenance. There is no way to ask a handle about it and none
> is added. A consumer requiring complete history therefore requires *a
> transport of that declared nature*, and the composition that wires it is what
> supplies one.
>
> **A kept origin cursor gives detection.** On a transport that can evict, an
> origin cursor turns a loss that would otherwise be invisible into a reported
> `gap`, and the consumer aborts or recovers on its own policy. It does not
> make the history complete; it makes incompleteness observable.
>
> Both stand, and they are not substitutes. Where completeness is a correctness
> requirement, only the declared transport delivers it, and a kept origin
> cursor is the honest failure mode that remains when completeness was not
> wired. Where a consumer can proceed on a suffix but must know that it is on
> one, the origin cursor is right and sufficient.
>
> **Wiring a log onto an evicting transport is a host assembly defect**, of the
> same kind as wiring a deposit destination that can refuse. A durable log is
> not a window: if a consumer treats a stream as the record of everything that
> happened, the transport under it must declare that it retains everything.
> Capacity is the wrong knob for this — a larger window makes loss less likely
> and never makes it reported — so sizing never substitutes for the
> declaration.
>
> A complete-history transport declares the reader and writer surfaces, that
> its retention is complete, and therefore that it excludes `gap`. One
> consequence is worth stating so that whoever builds one owes it up front:
> **a transport that excludes `gap` cannot also exclude `full` unless its
> capacity is genuinely unbounded.** Refusing and evicting are the two answers
> to the same condition, and a transport that has given up one owes the other.

## 4. `## Explicitly Absent` — one new bullet

> - **a retention or completeness predicate** — retention is declared, through
>   the transport's nature and the creation specification. Asking a handle
>   would be the same defect as asking whether it is closed: the answer is
>   configuration the composition already holds, and reading it back at runtime
>   invites code that branches on what it should have been wired with.

---

# 5. The `dao.space` ruling

**Yes — it is a wiring defect, and it is `dao.space`'s, not the contract's.**
An agent's local stream is its durable log; `derive-next-t` calls it the
causality boundary and `dao.space.index.md` already states that the local
stream must retain its complete history. A ring buffer's declared nature is to
evict. Putting the one on the other is the assembly defect the new text names,
and it has been latent since the local stream was first wired, independent of
the v2 migration.

**What `dao.space` must wire instead**, stated as a declaration rather than a
design: a transport carrying the reader and writer surfaces whose creation
specification declares **complete retention**, and which therefore excludes
`gap` for the first of the three honest reasons. Whether that is a new
append-only log transport or an explicitly unbounded mode on an existing one
is a transport decision and is out of scope here; the existing text already
permits either, since "a locally created stream records them through its
creation specification" makes the declaration per-stream.

**What the transactor/index plan must now require**, replacing r2 §6 entirely:

- `transactor/create!`'s spec requires a local stream on a complete-retention
  transport. That requirement is **declared by the composition and stated in
  the design — never checked**. There is no predicate, and adding one would
  violate edit 4.
- The plan's tests wire that transport, not a generously sized ring buffer. A
  ring buffer sized above a test's own history is not a passing test of this
  invariant; it is a test that cannot fail.
- r2 §6's capacity constant and its
  `retained-history-survives-reopen-at-capacity` test are **deleted**. That
  test would have pinned a defect as though it were a feature: it asserts that
  a reopened transactor throws on a retention gap, which is correct behaviour
  on a wiring that should not exist.
- T4 (a gap or malformed history fails the open) and index's S5 (a gap aborts
  publication before any emission) both stand unchanged, and their status
  changes: on a correctly wired transactor they are **unreachable**, and that
  is the point. They stay as the honest failure mode for a misassembled
  composition. `index_test:379`'s scripted gap fixture remains the way to
  exercise them, which is what it was always for.

# 6. What this costs

**v2 has no transport that can serve a complete-history consumer today.**
Measured: `dao.stream.ringbuffer` is the only namespace under
`src/cljc/dao/stream/` defining `create!`, so it is the only transport a
composition can locally create a stream on. Its `valid-spec?` requires a
positive integer capacity and its `append!` evicts oldest. v1 had
`dao.stream.file`; v2 has no counterpart.

So the honest accounting:

- **The transactor/index plan is blocked on a transport, not on prose.** The
  contract text below is necessary and is not sufficient. Until a
  complete-retention v2 transport exists, no `dao.space` local stream can be
  wired correctly, and implementing the plan against a ring buffer would ship
  the defect the review just found, now with a design document asserting it
  cannot happen.
- **This is not new breakage.** The v1 local streams are ring buffers opened
  with no capacity, which never evict — `ringbuffer.cljc:82`'s eviction test
  is `(and capacity …)`. The current system is accidentally correct: it
  satisfies complete retention through an unbounded instance that the contract
  had no way to describe and the code never declared. The migration is what
  turns an undeclared accident into a declared requirement, and it is better to
  discover that here than after the capacity was chosen.
- **For the ring buffer, nothing changes and nothing should.** Its declared
  nature is a bounded window; it is correct for what it is and stays right for
  intake pools, telemetry, demos, and every consumer that tolerates a suffix.
  What changes is that it stops being an acceptable substrate for an agent's
  log. The two ways forward each cost something, and both are the transport
  owner's call: a separate append-only log transport is more code and a second
  implementation of the reader surface; an explicitly unbounded ring-buffer
  mode is smaller but trades `gap` for unbounded memory, and its honest
  declaration — excludes `gap`, never reports `full` — is only true while
  memory lasts.
- **Scope of the contract edits is four**: two bullets in *Cursors*, one
  replaced sentence and one new subsection in *Retention and Gaps*, one bullet
  in *Explicitly Absent*. No invariant in the *Invariants* list changes;
  "Retention may be bounded. A cursor over evicted history receives a gap
  outcome as data" was true as written and remains so. Nothing in the operation
  tables, the outcome sets, or the surface definitions moves. This is a
  clarification of what the existing mechanisms already mean, which is why it
  needed no new surface to settle.
