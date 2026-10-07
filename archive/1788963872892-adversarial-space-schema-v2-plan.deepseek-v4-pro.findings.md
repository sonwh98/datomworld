Completed-GMT: 2026-09-09 14:37:32 GMT
Completed-Local: 2026-09-09 21:37:32 +0700 (Asia/Bangkok)
Coding-Agent: deepseek
Session-ID: 7bf6a403-74d3-4a94-91a7-4b7eb55a7e72
Model: deepseek-v4-pro (39 assistant turns recorded on that model in the session transcript;
the CLI's `[claude-code:unrecognized_model]` line is a wrapper id warning, not a substitution)
Role: Adversarial Review

# Adversarial review — dao.space.schema v2 migration plan

Target: `collab/1788962937302-architect-space-schema-v2-plan.claude-fable-5-1.findings.md`
Verified against `4b9f0e7` (schema.cljc, schema_test.cljc, schema_fixtures.cljc,
transactor.cljc, index.cljc, query.cljc, dao/stream/{ringbuffer,memory_log,observe}.cljc).

Ordered by severity. Each finding names the interleaving, input, or omission that
makes it real. Where the plan is right is said plainly at the end.

---

## Finding 1 (severity: high) — D4's completeness policy is unenforceable; `query/snapshot` never reports `:gap` for an already-lost prefix, so V13(a) cannot be written as specified and the silent-degradation bug D4 claims to close stays open.

**The plan says** (D4): `schema/current` throws on a snapshot with status `:gap`/`:defect`
because "a snapshot that lost its prefix has, with high probability, lost the vocabulary."
V13(a): "a v2 ring buffer of capacity 4 receives schema-rows plus data past capacity;
`query/snapshot` answers `:gap`; `schema/current` on it throws naming `:gap`."

**The defect.** `query/snapshot` (query.cljc:275-322) mints a fresh
`(:dao.stream/oldest)` cursor at call time. On the ring buffer that anchor resolves to
`{:dao.stream.ringbuffer/position (:first s)}` — the earliest **retained** position, which
has already advanced past the evicted prefix (ringbuffer.cljc:61-64). `next` reports
`:dao.stream/gap` **only** when a held cursor's position is `< (:first s)`
(ringbuffer.cljc:88-98) — i.e. an eviction that happens *while that cursor is being
read*. A fresh `:oldest` cursor is never behind `:first`, so:

- fill a capacity-4 buffer with `schema-rows` (≈17 rows) + data → `:first` advances to
  ~13, the schema rows at origin are gone;
- `query/snapshot` then reads the surviving suffix and answers **`:blocked`** (open) or
  **`:ended`** (closed) — never `:gap`.

`:blocked`/`:ended` are exactly the two statuses D4 accepts, so `schema/current`
interprets a relation whose schema vocabulary was evicted and silently degrades to raw
pass-through with card-one collapse disabled — the precise "wrong answer that looks like
a right one" D4 exists to prevent. The check is structurally unsound: it keys on a signal
(`:gap`) that a post-eviction snapshot cannot emit, because `query/snapshot` mints
`oldest` rather than keeping an origin cursor.

**Why it's not fixable by the test as written.** V13(a) asserts `snapshot answers :gap`,
which is false on this transport. The implementer cannot write that test; the honest
rewrite asserts `:blocked`-accepted-and-pass-through — which *passes while the
vulnerability is live*. So the pin V13 was meant to be is not merely vacuous, it is
impossible, and the property it names ("a lost prefix is detected") is unprovable via
this mechanism.

**The right instrument is in the stream doc the plan itself cites.** `dao.stream.md`
*Complete history* gives exactly two mechanisms: a transport that excludes `gap` (complete
retention — already T18's requirement on the *local* stream, but not on an arbitrary
caller-held reader handle), or a **kept origin cursor** (minted before the first append
and held across the eviction). `query/snapshot` does neither. Two corollaries the plan
misses:

- **`:blocked` is not "genuinely safe" in general.** It is safe only on a
  complete-retention transport. On an evicting transport that has already evicted,
  `:blocked` is a truncated suffix, and the status carries no retention signal for
  `schema/current` to branch on. Accepting `:blocked` unconditionally is the hole.
- If the intent is "schema rejects an incomplete snapshot," the contract either needs
  `query/snapshot` to mint/keep an origin cursor (a query-side change the plan explicitly
  avoids, "query's stays private"), or `schema/current` must refuse to be a completeness
  arbiter at all and state that a complete snapshot is the *caller's* obligation — same as
  the transactor's T18 "declared, never interrogated." The current D4 wording claims to
  *enforce* completeness with a check that cannot detect incompleteness.

(For contrast: V13(b)/(c) on `memory-log` are correct — `:oldest` is pinned to position 0
for the stream's life (memory_log.cljc:101-105), so `:blocked`/`:ended` genuinely imply
completeness there. Only (a) is unsound.)

---

## Finding 2 (severity: medium) — V11's property ("`schema/current` never closes a borrowed source") leaves the suite; V14 does not pin it.

**The plan claims** (D6, §2.2 V14): V11 `borrowed-path-does-not-close-again` is `[T✗]`
"the value model has no `close!` to abstain from", and "for the one source that *is*
closable (an opened published index) V14 pins the real property: schema forces the rows
and leaves closing to the owner."

**The gap.** V14 (`schema-view-is-self-contained-after-close-published`) asserts: publish →
materialize → `open-published!` → `schema/current` → `close-published!` → `q` answers; a
second `q` answers identically. Every one of those assertions still holds if
`schema/current` *did* close the opened index: the store would close early, the caller's
subsequent `close-published!` is a no-op (`when-let` on the idempotent guard,
query.cljc:261-268), and `schema/current`'s result is a self-contained
`query/fact-relation` independent of the store. V14 therefore pins **"the rows are forced
before return"** — which is real and distinct from W11 — but it does **not** pin "leaves
closing to the owner."

That second half is the exact thing V11 pinned, via the `RecordingStream` close-count
atom (schema_fixtures.cljc), and it is the one clause of the ownership rule D6 itself
keeps ("leaves closing to the owner"). Deleting `borrowed-path-does-not-close-again` and
`schema_fixtures` removes the only close-count seam that could observe it. A future
`schema/current` that (wrongly) closed an opened published index — e.g. a later
"optimization" that owns the store it was handed — would pass the entire suite. So of the
seven deletions this is the one property that genuinely leaves the suite with no surviving
pin, and the plan's §2.2 `[T→D]` marker on V14 overstates what the new test proves.

(Minor sibling: `close-is-idempotent-and-closes-the-inner-value` has the same shape — its
name claims "closes the inner value" but its three assertions check the wrapper flag and
the local stream only; after D1/D7 the inner's `:closed` flag is unobservable through the
wrapper surface, so the `tx/close! :inner` step is vestigial and un-pinnable. Low
severity, worth a rename or a dropped clause.)

---

## Finding 3 (severity: medium) — D1's "closed check precedes argument validation" contradicts the inner transactor, and the justification "exactly as `tx/transact!` does" is false.

**The plan says** (D1): `transact!` on a closed wrapper "answers
`{:dao.stream/outcome :dao.stream/closed}` first, before any argument is looked at,
exactly as `tx/transact!` does", and the new test
`closed-check-precedes-argument-validation` asserts `(transact! w [])` and
`(transact! w [[:db/add 30 :db/foo :bar]])` both answer `closed`.

**The defect.** `tx/transact!` does **not** do this for the empty case: its
`(when (empty? tx-data) (throw …))` runs *before* the lock and before the closed check
(transactor.cljc:241-243, closed check at :248). So `(tx/transact! closed-log [])` throws
"requires at least one transaction item", while the plan's new test demands
`(schema/transact! closed [])` answer `closed`. The wrapper and its inner value diverge
exactly where the plan says they agree, and "exactly as `tx/transact!` does" is factually
wrong for the argument the test is built around.

This is not fatal — "closed → data before any diagnostic" is a defensible choice, and the
plan states it ("a closed wrapper never throws an argument diagnostic for a transaction it
would not have appended anyway"). But the plan is smuggling in a *new* precedence that the
inner transactor explicitly rejects (transactor.md: "Operational outcomes are data;
argument defects throw" — empty tx-data is an argument defect that throws even when
closed). The plan should own the divergence ("schema answers `closed` even for `[]`/invalid
tx-data, unlike `tx/transact!`, because …"), or drop the `[]` arm from the test and match
the inner. As written, the implementer following "exactly as `tx/transact!` does" and the
implementer following the test's assertion produce different code.

---

## Finding 4 (severity: low) — Phase 2's W38/W39/W41 rewrite binds `opened` where the `finally` cannot see it.

The plan says: replace `(schema/published store-coord manifest-address)` with
`(query/open-published! (index/published-index store-coord manifest-address))`, "bound in
the `let`, with `(query/close-published! opened)` added to the existing `finally` beside
`cleanup-file`."

In the current W38 (schema_test.cljc:1550-1560) the `let` that binds `store-coord` /
`pub-desc` is **inside** the `try`; the `finally` only reaches `path`. Binding `opened` in
that inner `let` leaves it out of scope in the `finally` → unresolved-symbol at compile
time. The fix is to hoist `opened` into the outer `let` (nil-init; `close-published!` is
nil-safe at query.cljc:265-266) and assign it after `materialize-to-file-store`. The plan
doesn't say this, so a literal implementation doesn't compile. Mechanical, but it's a
real defect in the test rewrite.

---

## Where the plan is right

- **D7's guard deletion is safe.** I constructed the interleaving the brief asked for:
  `publish!` (→ `tx/publish!` → `index/publish-index!` → `snapshot-datoms` of the local
  stream, enqueue into the intake pool) racing `close!` (→ `tx/close!` → swap of the inner
  `:closed` flag). `tx/close!` closes nothing but its own flag (transactor.cljc:257-268);
  the local stream and the intake pool are caller-owned and stay open (T7, L2). There is
  no use-after-close: the "different window" does not exist, and "the guard is what
  created the race" is right *once the contract is redefined to permit publish-after-close*.
  `tx/publish!` has no closed check (transactor.cljc:271-280), confirmed.
- **D5/P4/P6 properties survive, on the query/index side.** I checked the surviving pins:
  `index/published-index` validates all three coordinate arguments (index.cljc:332-337),
  and that is itself pinned by `published-index-constructor-validates-its-arguments`
  (index_test.cljc:592-611); extra-key rejection is pinned by
  `open-published-rejects-unresolvable-and-malformed-coordinates` (query_test.cljc:943-960);
  the store-close properties are pinned by `failed-open-closes-the-store-it-opened`
  (query_test.cljc:1076) and `close-published-closes-once-and-is-idempotent` (:716). So
  `published-descriptor-validation`'s four properties do **not** leave the suite.
- **T19 survives the deftype→map rewrite.** The state atom is still installed only on
  `:dao.stream/ok`, via `reset!` under the same lock; the atom is the unit of state, so no
  half-installed state is observable by a concurrent transaction. The encapsulation loss
  (`:inner`/`:state` become public map keys) is real but is the same shape the transactor
  already ships (`create!` returns `:next-t` and `:state` atoms), and a caller can already
  reach the raw local stream — so "reach `:inner` and bypass validation" is not a new
  capability.
- **D10's two-phase split and grep proofs are sound**, and the §0 five corrections are
  correct (per the brief's own verified list, which I spot-checked without contradiction).

## Net

The plan is a sound migration of the write side and a mostly-sound one of the read side,
but **D4's snapshot-completeness policy is the load-bearing defect**: it advertises a
guard (`throw on :gap`) that cannot fire for the case it was built for (a prefix evicted
before the snapshot), and its V13 pin is specified against a `:gap` the transport will not
produce. Fix D4 before anything else — either accept that completeness is the *caller's*
obligation (and state it), or give `query/snapshot` an origin-cursor variant — and the
remaining findings (V14's missing "doesn't close" pin, D1's ordering divergence, the
`finally` scoping) are small and mechanical.
