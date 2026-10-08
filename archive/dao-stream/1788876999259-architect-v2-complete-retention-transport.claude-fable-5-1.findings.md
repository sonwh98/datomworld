---

Completed-GMT: 2026-09-08 14:35 GMT
Completed-Local: 2026-09-08 21:35 +0700 (Asia/Bangkok)
Coding-Agent: claude (Opus 5)
Session-ID: 0dae45ea-4204-49d9-bf35-c56e24cce14e

# A complete-retention DaoStream v2 transport

## 0. The scoping judgement: confirmed

Verified against the tree, not taken on report.

- v1 local streams are opened bare: `(ds/open! {:dao.stream/type :ringbuffer})` with no `:capacity` — `test/dao/space/transactor_test.cljc:219` and fifteen further sites, `test/dao/space/query_test.cljc:569`, `test/dao/space/stigmergy_test.clj:103`. `dao.space` itself never creates one; the transactor takes it as `:local-stream`, "supplied, never created, registered, or closed" (`src/cljc/dao/space/transactor.cljc:8-14`).
- v1 eviction is guarded by `(and capacity …)` (`src/cljc/dao/stream/ringbuffer.cljc:76-91`), so a nil capacity short-circuits the branch: `:head` never advances, nothing is evicted, `:full` is unreachable and `:daostream/gap` is unreachable.
- Nothing persists that stream. `publish-index!` (`src/cljc/dao/space/index.cljc:540-604`) snapshots it from position 0 (`snapshot-datoms`, `:442-468`) and emits b-tree blobs to a **v2** DaoJing intake stream; durability is entirely on the jing side. The code already says why complete retention is load-bearing: "Because the build starts at cursor position zero and reconstructs complete indexes, local-stream must retain its complete datom history" (`index.cljc:561-564`). `derive-next-t` (`transactor.cljc:125-139`) and `schema/proposed-schema` (`schema.cljc:926`) rescan from zero for the same reason.

So the local stream is in-memory, unbounded, append-only, and dies with the process; a restarted process today derives `next-t` = 0 from an empty stream. **The faithful v2 counterpart is an in-memory, unbounded, append-only log.** A file-backed durable log would add semantics `dao.space` does not have today and has not specified — it would silently change what a restart means — and durability across restarts is `dao.jing`'s job, which `dao.space` already composes with for publication. The judgement stands.

What `dao.space` actually requires, exhaustively: reader and writer surfaces; a cursor at the origin that stays at the origin; `next` returning values in order and never `gap`; `append!` returning `ok`. Nothing else. It needs no attachments (one process, handle passed directly), and its `ds/closed?` guards (`schema.cljc:318-326`, `:1069`, `:1127`) are the transactor plan's problem — v2 has no `closed?` predicate and this transport must not grow one.

## 1. Name and namespace

- Namespace: **`dao.stream.memory-log`** → `src/cljc/dao/stream/memory_log.cljc`
- Transport type: **`:dao.stream/memory-log`**

The name states both halves of its nature, medium and retention, so it is mistaken for neither the ring buffer nor a durable log. v1's `dao.stream.file` was the durable one and this is not that; a later durable transport takes `dao.stream.file-log` / `:dao.stream/file-log`. Bare `:dao.stream/log` was rejected: it says complete retention and says nothing about surviving a restart, which is exactly the confusion §6 exists to prevent.

The creation specification is `{:dao.stream/type :dao.stream/memory-log}` with **no transport-owned keys**. There is deliberately no capacity key: capacity is the knob the contract just disowned for this purpose (`dao.stream.md:707-709`), and offering one invites the sizing mistake back in.

## 2. The manifest

Declared operations are `create!`, `descriptor`, `cursor`, `next`, `append!`, `close!`. **`attach!` is absent, not excluded** — the transport has no attach implementation, so there is no operation whose outcomes to declare. `validate-manifest` (`conformance.cljc:24`) requires only `:descriptor` plus the operations its declared surfaces license, so an absent `:attach!` key is valid. A host dispatch table supplies no `:dao.stream/attach` for this type and `host-dispatch-attach!` answers `:dao.stream/not-found` — the contract's own answer for a descriptor with nothing behind it (`dao.stream.md:233-239`).

```clojure
(def memory-log-manifest
  {:dao.stream/type log/transport-type
   :surfaces #{:reader :writer :closable}
   :retention :complete
   :handle-factory handle
   :operations
   {:create!
    {:produces #{:dao.stream/ok :dao.stream/invalid-spec}
     :exclusions
     {:dao.stream/not-found
      "host dispatch selects the transport; a call reaching this handler has already matched it"
      :dao.stream/transport-error
      "creation allocates one in-memory state value and has no operational failure channel"}}

    :descriptor {:produces #{:dao.stream/ok} :exclusions {}}

    :cursor
    {:produces #{:dao.stream/ok :dao.stream/invalid-anchor}
     :exclusions
     {:dao.stream/closed
      "there are no attachments: every handle is the logical stream's owner, and an owner mints cursors after close so retained history stays readable"
      :dao.stream/transport-error
      "minting reads one in-memory state value and cannot fail operationally"}}

    :next
    {:produces #{:dao.stream/ok :dao.stream/blocked :dao.stream/end
                 :dao.stream/cursor-mismatch :dao.stream/invalid-cursor}
     :exclusions
     {:dao.stream/gap
      "retention is complete: no position is ever dropped, so no cursor can point at one that was"
      :dao.stream/transport-error
      "coherent in-memory state has no failure channel"}}

    :append!
    {:produces #{:dao.stream/ok :dao.stream/closed}
     :exclusions
     {:dao.stream/full
      "capacity is genuinely unbounded: the transport imposes no bound of its own, and the host heap is not a bound it can observe or refuse at"
      :dao.stream/invalid-value
      "the log holds host values by reference and encodes nothing, so no value is uncarriable"
      :dao.stream/transport-error
      "coherent in-memory state has no failure channel"}}

    :close! {:produces #{:dao.stream/ok} :exclusions {}}}

   :fixtures
   {:create! {:dao.stream/ok #(log/create! spec)
              :dao.stream/invalid-spec #(log/create! {:dao.stream/type :other})}
    :descriptor {:dao.stream/ok #(stream/descriptor (handle))}
    :cursor {:dao.stream/ok #(stream/cursor (handle) :dao.stream/oldest)
             :dao.stream/invalid-anchor #(stream/cursor (handle) ::invalid-anchor)}
    :next {:dao.stream/ok #(let [h (handle) c (cur h :dao.stream/oldest)]
                             (stream/append! h :value)
                             (stream/next h c))
           :dao.stream/blocked #(let [h (handle)]
                                  (stream/next h (cur h :dao.stream/newest)))
           :dao.stream/end #(let [h (handle) c (cur h :dao.stream/newest)]
                              (stream/close! h)
                              (stream/next h c))
           :dao.stream/cursor-mismatch #(let [l (handle) r (handle)]
                                          (stream/next r (cur l :dao.stream/oldest)))
           :dao.stream/invalid-cursor #(stream/next (handle) nil)}
    :append! {:dao.stream/ok #(stream/append! (handle) :value)
              :dao.stream/closed #(let [h (handle)]
                                    (stream/close! h)
                                    (stream/append! h :value))}
    :close! {:dao.stream/ok #(stream/close! (handle))}}})
```

`gap`'s exclusion reason is the contract's own first honest reason in substance: the outcome's precondition is impossible by the transport's nature (`dao.stream.md:442-445`). `:retention :complete` is a new manifest key; §4 says what it buys.

## 3. The `full` obligation

**Decision: genuinely unbounded, and `full` is excluded too.**

"Genuinely unbounded" can honestly mean exactly one thing here: *the transport imposes no bound of its own.* It never drops a value and never refuses one on account of a policy it chose, because it has chosen none — no capacity in the specification, no watermark in the state, no arithmetic anywhere in the namespace that could produce a refusal. That is a structural property of the code, not a promise about the machine.

The real bound is the host heap, and the heap is not the transport's to report:

- It is shared by everything in the process. An append that exhausts it was not refused by the log; the process ran out of memory while allocating, exactly as it would have in any other data structure.
- `:dao.stream/ok` followed by an OOM is indeed not an outcome — and it is not what happens. Heap exhaustion occurs *during* the append, so that call returns no result at all. There is no state in which this transport answers `ok` and then fails to have appended.
- `transport-error` is not the honest dodge either. Catching `OutOfMemoryError` on the JVM to return data would assert a recoverability that does not exist, and JS and Dart offer no portable signal to catch. The exclusion is the same across all three hosts, which is what an exclusion must be.

The bounded alternative was considered and rejected on the merits. A configured maximum would make `full` **permanent**, not transient: a log never frees space, so the writer's only contract-sanctioned recourse — retry later — never comes. A setting whose only correct value is "big enough" is the sizing fallacy in a different costume, and it converts a heap risk into a transaction failure `dao.space` has no policy for.

**What the transport does when the heap bound is reached: nothing.** It contains no code for that condition, and adding some would be fiction. Where a composition needs a policy it has one place to put it, above the log: publish a checkpoint and start a fresh stream. For `dao.space` that checkpoint already exists — `publish-index!` — but wiring rotation is the transactor plan's business, out of scope here and named so it is not mistaken for an oversight.

No transport-owned size or depth operation is added. The contract permits one (`dao.stream.md:400-405`) and it would be the first thing contract-generic code reached for.

## 4. Conformance

### What the suite already covers, and what changes

The manifest passes `validate-manifest` as written: `produces ∪ exclusions` equals `v2/operation-outcomes` per declared op, the sets are disjoint, every exclusion carries a non-empty reason.

Three law blocks behave differently from the ring buffer's run:

- `run-reader-laws` (`conformance.cljc:171`) mints `:oldest` on a fresh handle and reads it twice. Here `:oldest` is position 0 and stays there forever, so the block passes for a stronger reason than on the ring buffer, where the anchor tracks an advancing watermark.
- `run-induction-coverage` (`:112`) needs no `gap` inducer, since `gap` is excluded — the ring buffer's `gap` fixture (`ringbuffer_test.cljc:134-138`) has no counterpart and must not be reproduced by contrivance.
- `run-close-laws` (`:231`) closes the logical stream, not an attachment.

**The abstract model needs no change.** `make-abstract-stream-model` (`:382`) already takes `capacity nil`, and its trim is guarded by `(and (:capacity state) …)` (`:481-485`). With no attachments, `visible-tail` is `:next-pos` and `retained-first` is 0 once anything is appended (`:next-pos`, also 0, when empty), so the `:oldest` expectation `(min (retained-first state) (visible-tail state inv))` (`:509`) is 0 at every state. That is the contract's "a fresh `:oldest` *is* the origin" already encoded in the model. Supply a `:cursor-projector` for this transport's cursor keys and `capacity nil`; nothing else.

Linearizability histories to run, mirroring `bounded-concurrent-linearizability-histories` (`ringbuffer_test.cljc:299`): concurrent append/append, append/next, append/close, and two cursors at position 0 read concurrently after one completed append (reader independence, here through two cursors on the owner handle rather than two attachments). `run-concurrently` is private to `ringbuffer_test`; duplicate the twelve-line helper rather than promote it — consolidating the copies is a separate cleanup, not this transport's.

### Whether the suite needs an addition: yes

The observation in the brief is exact, and worth stating as the asymmetry it is: `run-induction-coverage` verifies that every **declared** outcome is inducible, and its "no observed outcome falls outside `:produces`" check only inspects results from fixtures the manifest itself supplies. **No fixture can be written for an outcome the manifest excludes**, so an exclusion is today an unchecked assertion. Nothing in the harness can catch a transport that declares complete retention and evicts.

"Never evicts" is a negative over an unbounded number of appends and is not testable in general. Its *observable consequence* is, and it is the one property that distinguishes this transport from every evicting one:

> A `:dao.stream/oldest` cursor minted at any time is at the origin, and an origin cursor kept from before the first append replays every value in order without a `gap`.

Add both, in two places:

1. **In `memory_log_test.cljc`** — a `complete-retention` deftest: mint an origin cursor, append N = 1000 values (past any plausible window), replay the kept cursor asserting every read is `ok`, values arrive in order, and no outcome is `gap`; then assert a freshly minted `:oldest` equals the origin cursor. This is `ringbuffer_test.cljc:23`'s `retention-and-gaps` inverted, and a ring buffer fails it.
2. **In `conformance.cljc`** — a `run-retention-laws` block, ~25 lines, run from `run-conformance-suite` only when the manifest declares `:retention :complete`. It checks (a) that `:next` excludes `:dao.stream/gap` — the two declarations may not disagree — and (b) the origin property above over `:handle-factory`. This makes the declaration mean something for the next complete-retention transport as well as this one, and it is falsifiable in this repo's existing style (`linearizability-oracle-rejects-an-invalid-history`, `:261`): bolt `:retention :complete` onto the ring buffer manifest in a local `let` and the block must fail.

`:retention :complete` is a **manifest** declaration — configuration provenance, which the contract permits — not a predicate on a handle, which it forbids (`dao.stream.md:764-767`). If a reviewer judges (2) harness creep, (1) alone suffices and (2) drops without changing the transport.

## 5. Cursors

State is the minimum that can express this transport:

```clojure
{:identity (str (random-uuid))   ; portable data, as in ringbuffer.cljc:27
 :values   []                    ; dense from position 0
 :closed?  false}
```

A **vector**, not the ring buffer's map: positions are dense and nothing is ever removed, so `conj` and `nth` suffice and the tail is `(count values)`. There is no `:first` watermark because there is nothing for one to watermark, and no `:attachments` because there are none. The absence of eviction is therefore structural — the namespace contains no expression that removes an element — rather than a policy the code chooses each append.

- **Positions** are integers from 0, dense, absolute, never reused, never dropped.
- **Cursor value**: `{:dao.stream.memory-log/identity <id> :dao.stream.memory-log/position <n>}`. The identity is the logical-stream identity — the same value `descriptor` projects under `:dao.stream/identity` and the same one `cursor-mismatch` compares — carried under a transport-owned key because cursor representation belongs to the transport.
- **`:oldest`** is position 0, always, for the life of the stream, before the first append and after close. On this transport a fresh `:oldest` *is* the origin — exactly the contract's statement and exactly what `dao.space`'s from-zero rescans need.
- **`:newest`** is `(count values)`.
- **Attach and frozen tails: none.** There are no attachments, so `cursor` never answers `:dao.stream/closed` and no handle has a frozen visible tail. Every handle is the logical stream's owner.
- **After close**, `cursor` keeps minting; `:oldest` is still 0, `:newest` still the tail, and a `next` past the tail answers `end` instead of `blocked`.
- **`next`** validates in the ring buffer's order — not a map → `invalid-cursor`; missing identity key → `invalid-cursor`; identity mismatch → `cursor-mismatch`; non-integer position → `invalid-cursor`; then `pos < tail` → `ok` with value and successor; else `end` if closed, `blocked` otherwise. The `pos < first` branch that produces `gap` has no counterpart and is simply absent.
- **`append!`** takes the ring buffer's single-linearization-point shape (`ringbuffer.cljc:117-132`): one `swap!` whose function checks `:closed?` and either records `closed` or `conj`s, with the outcome carried out in a `volatile!`. A deref before the swap would let an append that began before a close land after it.

### Host portability

The transport source needs **no reader conditionals at all** — `deftype`, protocol implementation, `atom`/`swap!`, `volatile!`, `random-uuid`, `conj`, `nth`, `count` are all used unguarded by `ringbuffer.cljc` across clj, cljs and cljd today. That is the safest position with respect to the `#?(:clj …)` trap, since the trap only bites where a conditional is added. The one `#?` in this work is the JVM-thread block in the test, written `#?(:cljd nil :clj …)` with `:cljd` **first**, as `ringbuffer_test.cljc:252,277,297` already does.

## 6. What it does not do

- **No durability across process restart.** The log is one atom; the process dying takes it. A restarted host sees a new, empty logical stream with a new identity.
- **No eviction, no `gap`, no `full`, and no capacity key.**
- **No attachments**: no `attach!`, no attachment identities, no frozen tails, no `:dao.stream/closed` from `cursor`.
- **No encoding.** Values are held by reference, so this is not a serialization boundary; a live handle can pass through it, and no value is uncarriable.
- **No size, depth, or retention query**, and no `closed?` predicate.
- **No readiness or waiter mechanism** — the contract has none.

A later **durable** transport is a different transport, not this one grown. It would add: a medium that survives restart, and therefore an identity recovered from the medium rather than minted at creation; `attach!` resolving against the filesystem rather than a host directory; `:dao.stream/transport-error` on `cursor`, `next` and `append!`, since locating the earliest retained position on a durable log is itself a read that can fail — the contract names precisely this case (`dao.stream.md:483-488`); `:dao.stream/invalid-value`, because it encodes and is therefore a serialization boundary; a real `:dao.stream/full`, because a disk is genuinely bounded and its bound *is* the transport's to observe; and a `close!` that flushes, hence a named channel where flush failure surfaces (`dao.stream.md:583-586`). Nothing in `dao.stream.memory-log` should be shaped to anticipate any of that.

## 7. Phasing and the handoff

**Phase A (this plan, one commit).** Add `src/cljc/dao/stream/memory_log.cljc` and `test/dao/stream/memory_log_test.cljc`; add `run-retention-laws` to `test/dao/stream/conformance.cljc`. `dao.stream.ringbuffer` and `docs/design/dao.stream.md` are untouched. No new design doc: the namespace docstring and the manifest are this transport's specification, and `dao.stream.file.md` documents a far larger thing.

**Phase B.** The transactor plan is revised against it.

The sentence the next brief can quote:

> `dao.space`'s local stream is created from `{:dao.stream/type :dao.stream/memory-log}` by `dao.stream.memory-log/create!`, whose declared complete retention is what makes `derive-next-t` and `publish-index!`'s from-origin rebuild correct; wiring any evicting transport there is a host assembly defect.

And what that plan must **not** ask of it: durability across restart, a size or retention query, a `closed?` predicate, or a capacity. If the transactor needs to bound growth, it rotates above the log after publishing; the transport has no opinion.

## Verification

- `bb test:clj` — JVM: conformance suite, retention laws, linearizability histories including the concurrent ones.
- `bb test:cljs` — Node; confirm `Testing dao.stream.memory-log-test` appears in the output, since the shadow `:node-test` build discovers namespaces rather than reading an allowlist.
- `bb test:cljd` — Dart; the same suite minus the thread-based block.
- Falsification: temporarily bolt `:retention :complete` onto `ringbuffer-manifest` in a local `let` and confirm `run-retention-laws` fails. Do not commit that.
- Regression: `dao.stream.ringbuffer-test` must pass unchanged — the conformance addition is licensed by a manifest key the ring buffer does not declare.
