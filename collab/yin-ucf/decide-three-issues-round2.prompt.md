Created-GMT: 2026-08-31 07:00:00 GMT
Created-Local: 2026-08-31 14:00:00 +07

# Role: Design authority, reconsidering three decisions with fuller context

You were asked three questions about the DaoStream v2 design. Four models
answered independently. You are now asked to reconsider, having read the
`dao.space` cluster — documents none of the four consulted, and which bear
directly on at least one of the three questions.

## The three questions, and how the four answered

**Q1 — Is `ok` from a still-connecting `attach!` a false success?**
- 1A: keep `ok`, rescope its wording so it claims the *attachment*, not the resolution.
- 1B: add `:dao.stream/pending`; reserve `ok` for transports that attach synchronously.
- Answers: **sol → 1B. fable, gemini, glm → 1A.**

**Q2 — May the WebSocket slice deposit boundary events into a ring buffer?**
ADR 0003 says "There is no system event bus. `dao.space` is the medium."
- 2A: use a conforming `dao.space` writer as the deposit destination.
- 2B: keep the ring buffer, amend ADR 0003 and the ws spec with an explicit, terminating exception.
- 2C: keep the ring buffer, no amendment — the ADR governs production composition, not a migration slice.
- Answers: **sol → 2A. fable, glm → 2B. gemini → 2C.**

**Q3 — Does the ws deposited-event envelope violate `datom.world.md`?**
The authority says host events "need no bespoke envelope, version field, or fact
taxonomy to be read." The ws spec mandates
`{:dao.stream/attachment <id> :ws/event <class> :ws/value <decoded>}`.
- 3A: amend `datom.world.md` to permit minimal transport-owned event maps.
- 3B: drop the wrapper; correlation as fields on ordinary event values.
- 3C: keep it, add a reconciling clause — this is attribution plus the event
  classes the socket itself distinguishes, not a taxonomy of meaning.
- Answers: **sol → 3A. fable, gemini, glm → 3C.**

## Read now, in addition to the earlier reading list

- docs/design/dao.space.md            (the tuple space)
- docs/design/dao.space.index.md      (transactor-side indexing library)
- docs/design/dao.space.query.md      (reader-side Peer library)
- docs/design/dao.jing.md             (the storage boundary)
- docs/design/dao.jing.dht.md         (the distributed content backend)

Earlier reading list (re-read as needed): docs/design/datom.world.md,
docs/design/dao.stream.md, docs/design/dao.stream.ws.md,
docs/design/dao.stream.implementation-plan.md,
docs/design/adr/0003-dao-space-is-the-event-medium.md.

Precedence: datom.world.md > dao.stream.md > {ws spec, plan}. ADRs bind the
decisions they record.

## What this new material may change

Consider at least the following, and say explicitly whether each moves you:

1. **What "dao.space" denotes.** `dao.space.md` opens by saying dao.space is
   "not a thing you store or a component you deploy" but the tuple space that
   *emerges* when agents index their own streams and match over the result.
   If dao.space is emergent rather than instantiable, what does "deposit
   through a conforming dao.space writer" (2A) actually name, and is it a
   thing a composition can wire?

2. **The stigmergy example's own intake stream.** `dao.space.md`'s canonical
   worked example opens its DaoJing intake stream as
   `(ds/open! {:dao.stream/type :ringbuffer})`. Does the authority's own
   example license a ring buffer in this role, or is an intake stream a
   different role from a boundary deposit destination?

3. **The two stigmergy invariants.** `dao.space.md`, *Coordination: Stigmergy*
   states two: "Nothing registers" and "A new interpreter starts reading
   without anything being rewired," with the test that an interpreter must be
   able to tap a stream *without being aware of its source*. Apply that test
   to a per-boundary deposit medium the host composition wires explicitly.
   Does an explicitly wired deposit destination pass or fail it? Does the
   answer differ between a ring buffer and a dao.space writer?

4. **`dao.space.transactor`'s existing stream type.** It is registered as a
   `dao.stream` type taking `:local-stream` and `:intake-pool`, and its
   `append!` writes one atomic transaction record. Is that the "conforming
   writer" 2A wants, does it need porting to the v2 protocols first, and does
   the answer change 2A's feasibility?

5. **Whether Q3's envelope survives contact with the datom convention.**
   `dao.space` accepts tuples of any finite dimension and treats the d5 datom
   as one canonical interpretation; `dao.jing` "materializes any opaque payload."
   Does that make the ws envelope more defensible (it is just another tuple
   shape an interpreter may read) or less (a map with reserved keys is
   precisely the fact taxonomy the authority declines)?

6. **Anything in the new documents bearing on Q1** — in particular whether
   any existing dao.space consumer would have to branch on a `pending`-vs-`ok`
   distinction, or conversely is harmed by `ok` that does not assert resolution.

## Output

For each of the three questions: **your answer now** (the option letter), and
whether that is a **change** from the position attributed to you above. If you
change, say precisely which evidence changed it. If you do not change, say
which of the six items above you considered and why none moved you — do not
simply restate your earlier reasoning.

Then: the single strongest argument *against* your own Q2 answer, stated as
fairly as you can, and why you still reject it.

Answer decisively. Read-only. Do not edit files.
