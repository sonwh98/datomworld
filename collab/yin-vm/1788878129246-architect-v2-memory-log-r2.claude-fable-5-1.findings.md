---

Completed-GMT: 2026-09-08 14:52 GMT
Completed-Local: 2026-09-08 21:52 +0700 (Asia/Bangkok)
Coding-Agent: claude (Opus 5)
Session-ID: 0dae45ea-4204-49d9-bf35-c56e24cce14e

# A complete-retention DaoStream v2 transport (r2)

Revised against `collab/1788877651728-review-v2-memory-log-plan.gpt-5.6-sol.findings.md`. Six findings resolved: §3 rewritten on the observable/fatal distinction and the `full` rationale replaced (P1-1, P2-1); `next` made total (P1-2); `run-retention-laws` specified concretely and its falsification test committed (P1-3); rotation withdrawn as an available answer (P2-2); the handoff sentence and the creation-spec rule settled (P2-3). Scope, manifest partitions, absent `attach!`, the dense-vector model, anchors, close semantics, linearization shape and portability stand as confirmed.

## 0. The scoping judgement: confirmed

Verified against the tree, not taken on report.

- v1 local streams are opened bare: `(ds/open! {:dao.stream/type :ringbuffer})` with no `:capacity` — `test/dao/space/transactor_test.cljc:219` and fifteen further sites, `test/dao/space/query_test.cljc:569`, `test/dao/space/stigmergy_test.clj:103`. `dao.space` itself never creates one; the transactor takes it as `:local-stream`, "supplied, never created, registered, or closed" (`src/cljc/dao/space/transactor.cljc:8-14`).
- v1 eviction is guarded by `(and capacity …)` (`src/cljc/dao/stream/ringbuffer.cljc:76-91`), so a nil capacity short-circuits the branch: `:head` never advances, nothing is evicted, `:full` is unreachable and `:daostream/gap` is unreachable.
- Nothing persists that stream. `publish-index!` (`src/cljc/dao/space/index.cljc:540-604`) snapshots it from position 0 (`snapshot-datoms`, `:442-468`) and emits b-tree blobs to a **v2** DaoJing intake stream; durability is entirely on the jing side. The code already says why complete retention is load-bearing (`index.cljc:561-564`). `derive-next-t` (`transactor.cljc:125-139`) and `schema/proposed-schema` (`schema.cljc:926`) rescan from zero for the same reason.

So the local stream is in-memory, unbounded, append-only, and dies with the process; a restarted process today derives `next-t` = 0 from an empty stream. **The faithful v2 counterpart is an in-memory, unbounded, append-only log.** A file-backed durable log would add semantics `dao.space` does not have and has not specified — it would silently change what a restart means — and durability across restarts is `dao.jing`'s job, which `dao.space` already composes with for publication.

What `dao.space` actually requires, exhaustively: reader and writer surfaces; a cursor at the origin that stays at the origin; `next` returning values in order and never `gap`; `append!` returning `ok`. Nothing else. It needs no attachments (one process, handle passed directly), and its `ds/closed?` guards (`schema.cljc:318-326`, `:1069`, `:1127`) are the transactor plan's problem — v2 has no `closed?` predicate and this transport must not grow one.

## 1. Name, namespace, and the creation specification

- Namespace: **`dao.stream.memory-log`** → `src/cljc/dao/stream/memory_log.cljc`
- Transport type: **`:dao.stream/memory-log`**

The name states both halves of its nature, medium and retention, so it is mistaken for neither the ring buffer nor a durable log. v1's `dao.stream.file` was the durable one and this is not that; a later durable transport takes `dao.stream.file-log` / `:dao.stream/file-log`. Bare `:dao.stream/log` was rejected: it says complete retention and says nothing about surviving a restart, which is exactly the confusion §6 exists to prevent.

**The specification rule, settled (P2-3).** The contract gives both halves of it: every non-`:dao.stream/type` key is "transport-owned, qualified under the transport's namespace … and a handler that rejects its own keys returns `:dao.stream/invalid-spec`" (`dao.stream.md:248-251`), while envelopes are open and "a consumer ignores qualified keys it does not understand" (`:302-304`). So:

- A key in **this transport's own namespace** — anything `:dao.stream.memory-log/…` — is **rejected** with `invalid-spec`. The transport owns that namespace and has nothing in it, so a key there is a caller believing in a knob that does not exist. The one they will reach for is a capacity, and that is precisely the mistake this transport exists to make impossible; failing loudly at creation is better than ignoring it and appearing to honour it.
- Any **other** qualified key is **ignored**, per the open-map rule. A host that wants to carry a label or provenance alongside the spec may.

`valid-spec?` is therefore: a map, `:dao.stream/type` equal to `transport-type`, and no key namespaced `dao.stream.memory-log`.

## 2. The manifest

Declared operations are `create!`, `descriptor`, `cursor`, `next`, `append!`, `close!`. **`attach!` is absent, not excluded** — the transport has no attach implementation, so there is no operation whose outcomes to declare. `validate-manifest` (`conformance.cljc:24`) requires only `:descriptor` plus surface-licensed operations, so an absent `:attach!` key is valid. A host dispatch table supplies no `:dao.stream/attach` for this type and `host-dispatch-attach!` answers `:dao.stream/not-found` — the contract's own answer for a descriptor with nothing behind it (`dao.stream.md:233-239`).

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
      "creation allocates one in-memory state value: it either completes and returns, or does not return"}}

    :descriptor {:produces #{:dao.stream/ok} :exclusions {}}

    :cursor
    {:produces #{:dao.stream/ok :dao.stream/invalid-anchor}
     :exclusions
     {:dao.stream/closed
      "there are no attachments: every handle is the logical stream's owner, and an owner mints cursors after close so retained history stays readable"
      :dao.stream/transport-error
      "minting reads one in-memory state value and has no failure it could observe and return from"}}

    :next
    {:produces #{:dao.stream/ok :dao.stream/blocked :dao.stream/end
                 :dao.stream/cursor-mismatch :dao.stream/invalid-cursor}
     :exclusions
     {:dao.stream/gap
      "retention is complete: no position is ever dropped, so no cursor can point at one that was"
      :dao.stream/transport-error
      "a read is one indexed lookup into retained state; there is no failure it could observe and return from"}}

    :append!
    {:produces #{:dao.stream/ok :dao.stream/closed}
     :exclusions
     {:dao.stream/full
      "no capacity is declared: the transport is logically unbounded and refuses nothing"
      :dao.stream/invalid-value
      "the log holds host values by reference and encodes nothing, so no value is uncarriable"
      :dao.stream/transport-error
      "the append either completes its one state transition and returns, or does not return"}}

    :close! {:produces #{:dao.stream/ok} :exclusions {}}}

   :fixtures
   {:create! {:dao.stream/ok #(log/create! spec)
              :dao.stream/invalid-spec
              #(log/create! (assoc spec :dao.stream.memory-log/capacity 1024))}
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

The `invalid-spec` fixture is the own-namespace-key case because it is the one that encodes the §1 decision; the transport test covers all four rejection cases and the ignore case (§4).

`gap`'s exclusion reason is the contract's first honest reason in substance: the outcome's precondition is impossible by the transport's nature (`dao.stream.md:442-445`). Every `transport-error` reason is now phrased as the contract's own test (`:735-737`) rather than as a claim about the heap — see §3.

## 3. Unbounded, `full`, and `transport-error`

**Decision: logically unbounded; `full` and `transport-error` are both excluded.** The decision is what the review confirmed; the reasoning below is the corrected one.

### `transport-error` (P1-1)

The line is not between the transport's medium and the host's. The vector does live on the host heap, and pretending otherwise was the weak part of r1. The line the contract now draws is between **a failure an operation can observe and return from** and **fatal host or runtime exhaustion from which the operation produces no result at all** — the first is inside the outcome algebra, the second is outside it entirely (`dao.stream.md:729-737`, amended in the working tree).

Every operation of this transport sits on one side of that line. Each is a single state transition over in-memory values — one `swap!`, one deref, one indexed lookup — and there is no intermediate condition it could detect and report: it completes and returns a result, or it does not return. That is the contract's own stated test for excluding the outcome ("a transport whose every operation either completes its state transition or does not return therefore has no firing condition for `transport-error`"), and it holds identically on clj, cljs and cljd, which is what an exclusion must do.

Nor is heap exhaustion ever converted into eviction: there is no code path in this namespace that removes an element (§5), so the contract's "never … the eviction of acknowledged history" is structurally satisfied rather than promised.

### `full` (P2-1)

A finite complete-history log returning **permanent** `full` is perfectly legitimate — the contract says `full` may be transient or permanent, and the writer decides what to do with any non-`ok` outcome. The r1 objection ("retry never comes") was therefore not a valid objection, and is withdrawn.

The actual reason to exclude it is a migration reason. v1's capacity-less local ring buffer never refused an append, so declaring a capacity here would introduce a write failure `dao.space` has never seen and has no policy for: the transactor would have to abort a transaction or roll the log over, and neither policy exists (see below). A migration that changes what `append!` can answer is not a migration. This transport declares no capacity, so it refuses nothing, and the contract permits the exclusion on exactly that basis (`dao.stream.md:727-729`).

### What happens when the heap really is exhausted, and what is not available (P2-2)

Nothing in this transport, and — stated plainly rather than delegated upward — **nothing in the composition either, today.** r1 offered "publish a checkpoint and start a fresh stream" as the place a policy could live. That is not available:

- A new transactor over an empty log derives `t = 0` and collides with the prior history's transaction numbers.
- Publishing only the new log omits everything before the rotation.
- Queries would need a snapshot chain or a merge policy across rotations, and neither exists.

Rotation is future work and requires a checkpoint that carries causality forward, plus an index and query composition designed around it. Until then, unbounded growth over a long-lived process is a **known, named limitation of this migration**, inherited unchanged from v1 — v1's capacity-less ring buffer has exactly the same property — and not something this transport either introduces or can fix.

No transport-owned size or depth operation is added. The contract permits one (`dao.stream.md:400-405`) and it would be the first thing contract-generic code reached for.

## 4. Conformance

### What the existing suite covers, and what changes

The manifest passes `validate-manifest`: `produces ∪ exclusions` equals `v2/operation-outcomes` per declared op, the sets are disjoint, every exclusion has a non-empty reason.

Three law blocks behave differently from the ring buffer's run:

- `run-reader-laws` (`conformance.cljc:171`) mints `:oldest` on a fresh handle and reads it twice. Here `:oldest` is position 0 and stays there forever, so the block passes for a stronger reason than on the ring buffer, where the anchor tracks an advancing watermark.
- `run-induction-coverage` (`:112`) needs no `gap` inducer, since `gap` is excluded — the ring buffer's `gap` fixture (`ringbuffer_test.cljc:134-138`) has no counterpart and must not be reproduced by contrivance.
- `run-close-laws` (`:231`) closes the logical stream, not an attachment.

**The abstract model needs no change.** `make-abstract-stream-model` (`:382`) already takes `capacity nil` and guards its trim with `(and (:capacity state) …)` (`:481-485`). With no attachments, `visible-tail` is `:next-pos` and `retained-first` is 0 once anything is appended (`:next-pos`, also 0, when empty), so the `:oldest` expectation `(min (retained-first state) (visible-tail state inv))` (`:509`) is 0 at every state — the contract's "a fresh `:oldest` *is* the origin", already encoded. Supply a `:cursor-projector` for this transport's cursor keys and `capacity nil`; nothing else.

Linearizability histories, mirroring `bounded-concurrent-linearizability-histories` (`ringbuffer_test.cljc:299`): concurrent append/append, append/next, append/close, and two cursors at position 0 read concurrently after one completed append (reader independence, here through two cursors on the owner handle rather than two attachments). `run-concurrently` is private to `ringbuffer_test`; duplicate the twelve-line helper rather than promote it — consolidating the copies is a separate cleanup.

### Why an addition is needed at all

`run-induction-coverage` verifies that every **declared** outcome is inducible, and its "no observed outcome falls outside `:produces`" check only inspects results from fixtures the manifest itself supplies. **No fixture can be written for an outcome the manifest excludes**, so an exclusion is today an unchecked assertion, and nothing in the harness can catch a transport that declares complete retention and evicts.

"Never evicts" is a negative over unboundedly many appends and is not testable in general. What is testable is the observation a kept origin cursor makes, which is the mechanism the whole *Complete history* section rests on.

### `run-retention-laws` (P1-3)

Added to `conformance.cljc` and run from `run-conformance-suite`. It runs **only when the manifest carries a `:retention` key**, so every existing manifest is unaffected.

**Declaration checks**, before any operation:

- `:retention` must be a known value — currently `#{:complete}`. An unrecognized value is a violation (`:unknown-retention-declaration`), never a silent skip, so a typo cannot disable the laws.
- `:complete` requires the `:reader` surface (`:complete-retention-without-reader`). Completeness is a promise to a cursor; a transport with no reader has no one to make it to.
- `:complete` requires `:next` to exclude `gap` (`:complete-retention-declares-gap`). The two declarations may not disagree.

**Population.** The law needs a populated history and a cursor kept from before it existed:

- If `:writer` is declared: mint `:dao.stream/oldest` on a fresh `:handle-factory` handle **first**, then append 256 distinct values. The cursor is a true pre-mutation origin cursor, which is the whole point.
- If `:writer` is not declared — the contract explicitly permits a reader-only complete-history transport, "a finite immutable history is a valid one" (`dao.stream.md:722-724`) — the manifest must supply `:retention-fixture`, a thunk returning `{:handle h :expected [v …]}` for a prepopulated handle. Its absence is a violation (`:missing-retention-fixture`), not a pass. A reader-only manifest therefore cannot receive a vacuous certification, and the shape the contract blesses is not banned.

**Replay checks.** A bounded helper follows a cursor to its terminus, collecting values and the set of outcomes seen, and reporting `:did-not-terminate` past `(+ (count expected) 8)` iterations. Each check asserts three things and names them separately:

- no outcome in the replay was `gap` → `:gap-on-complete-retention`
- the collected values equal `expected` → `:incomplete-replay`
- the terminal outcome is the expected one → `:unexpected-terminal-outcome`

**Comparing observations, not representations (P1-3.2).** The shared law never compares cursors structurally. It replays the kept origin cursor and, separately, a freshly minted `:oldest`, and requires the two **observed sequences** to be equal to `expected`. Structural cursor equality is asserted only in the memory-log-specific test, where the representation is the transport's own.

**Including close (P1-3.3).** Where `:closable` is declared, the law then closes and repeats both replays, now expecting terminal `end` rather than `blocked`. Completeness lasts the logical stream's lifetime, and the existing `run-close-laws` only closes a fresh empty instance. If post-close `cursor` answers `closed` — a transport whose handles are attachments rather than owners — the fresh-`:oldest` half is skipped as inapplicable while the kept-origin-cursor half still runs, so nothing is waived.

Sketch of the core, to pin the shape:

```clojure
(def ^:private retention-declarations #{:complete})

(defn- replay-from
  "Follow a cursor to its terminus, bounded.  Observations only."
  [handle cursor limit]
  (loop [c cursor n 0 vs [] seen #{}]
    (if (> n limit)
      {:values vs :terminal ::did-not-terminate :outcomes seen}
      (let [r (v2/next handle c)
            o (:dao.stream/outcome r)]
        (if (= o :dao.stream/ok)
          (recur (:dao.stream/cursor r) (inc n) (conj vs (:dao.stream/value r)) (conj seen o))
          {:values vs :terminal o :outcomes (conj seen o)})))))
```

### The falsification test, committed (P1-3.1)

`test/dao/stream/retention_falsification_test.cljc` — a dedicated namespace, because this is a test of the harness, not of either transport.

The adversary must be a manifest that **lies**, not one that is merely inconsistent: if it declared `gap` in `:produces`, the declaration check would fire and the behavioural replay would never run. So the test builds, from the ring buffer's real manifest, a manifest with `:retention :complete` **and** `:next` moved to exclude `gap` with the reason `"false declaration under test"`. Every declaration check then passes and only the replay can catch it. Against a capacity-2 ring buffer, the kept pre-eviction origin cursor's first `next` answers `gap`.

The test asserts `(false? (:passed? result))` and that some failure carries `:check :gap-on-complete-retention` — the specific P0 mechanism, observed through a cursor held from before the eviction, not merely "something failed". Removing the unused `gap` fixture entry is unnecessary: induction only looks up outcomes in `:produces`.

### The transport's own tests (`memory_log_test.cljc`)

- `complete-retention`: origin cursor, 1000 appends, full replay with no `gap`; a freshly minted `:oldest` is **structurally equal** to the origin cursor (permitted here, where the representation is ours); the same after `close!`, terminating in `end`.
- `next-is-total` (P1-2): a fabricated cursor with the right identity and position `-1` → `invalid-cursor`; position `(inc tail)` → `invalid-cursor`; position `tail` → `blocked` open, `end` closed; a non-integer position → `invalid-cursor`; `nil` → `invalid-cursor`; a foreign identity → `cursor-mismatch`. All three hosts — this is a `.cljc` test with no host conditional, so all three run it.
- `creation-spec-rule`: non-map, missing `:dao.stream/type`, wrong type, and a `:dao.stream.memory-log/…` key each → `invalid-spec`; a foreign qualified key (`:my.host/label`) → `ok`.
- Conformance suite and linearizability histories as above.

## 5. Cursors and reading

State is the minimum that can express this transport:

```clojure
{:identity (str (random-uuid))   ; portable data, as in ringbuffer.cljc:27
 :values   []                    ; dense from position 0
 :closed?  false}
```

A **vector**, not the ring buffer's map: positions are dense and nothing is ever removed, so `conj` and `nth` suffice and the tail is `(count values)`. There is no `:first` watermark because there is nothing for one to watermark, and no `:attachments` because there are none. The absence of eviction is structural — the namespace contains no expression that removes an element — rather than a policy chosen at each append.

- **Positions** are integers from 0, dense, absolute, never reused, never dropped.
- **Cursor value**: `{:dao.stream.memory-log/identity <id> :dao.stream.memory-log/position <n>}`. The identity is the logical-stream identity — the same value `descriptor` projects under `:dao.stream/identity` and the same one `cursor-mismatch` compares — under a transport-owned key, because cursor representation belongs to the transport.
- **`:oldest`** is position 0, always, for the life of the stream, before the first append and after close. A fresh `:oldest` *is* the origin, which is what `dao.space`'s from-zero rescans need.
- **`:newest`** is `(count values)`.
- **Attach and frozen tails: none.** Every handle is the logical stream's owner, so `cursor` never answers `:dao.stream/closed` and no handle has a frozen visible tail.
- **After close**, `cursor` keeps minting; `:oldest` is still 0, `:newest` still the tail, and a `next` at the tail answers `end` instead of `blocked`.

### `next` is total (P1-2)

r1's validation accepted every integer position and reached `nth` with it. A cursor carrying the right identity and position `-1` would have thrown on the JVM and behaved differently on each host — a defect, and exactly the kind the outcome algebra exists to eliminate. The corrected order, with the range checked **before** any indexed read:

1. not a map → `invalid-cursor`
2. no `:dao.stream.memory-log/identity` key → `invalid-cursor`
3. identity ≠ this stream's → `cursor-mismatch`
4. position not an integer, or `< 0`, or `> tail` → `invalid-cursor`
5. `0 <= pos < tail` → `ok` with the value and the successor cursor
6. `pos = tail` → `end` if closed, else `blocked`

Step 4's upper bound is not merely defensive: because nothing is evicted and the tail only grows, the largest position the stream can ever have minted is the current tail, so `pos > tail` is a position no cursor from this stream can hold. Treating it as `blocked` would accept a fabricated cursor, which the contract's cursor-provenance rule forbids. (This differs from the ring buffer, which answers `blocked` there; `dao.stream.ringbuffer` is not modified.)

The `pos < first` branch that produces `gap` has no counterpart and is simply absent.

**`append!`** takes the ring buffer's single-linearization-point shape (`ringbuffer.cljc:117-132`): one `swap!` whose function checks `:closed?` and either records `closed` or `conj`s the value, with the outcome carried out in a `volatile!`. A deref before the swap would let an append that began before a close land after it.

### Host portability

The transport source needs **no reader conditionals at all** — `deftype`, protocol implementation, `atom`/`swap!`, `volatile!`, `random-uuid`, `conj`, `nth`, `count` are all used unguarded by `ringbuffer.cljc` across clj, cljs and cljd today. That is the safest position with respect to the `#?(:clj …)` trap, since the trap only bites where a conditional is added. The one `#?` in this work is the JVM-thread block in the test, written `#?(:cljd nil :clj …)` with `:cljd` **first**, as `ringbuffer_test.cljc:252,277,297` already does.

## 6. What it does not do

- **No durability across process restart.** The log is one atom; the process dying takes it. A restarted host sees a new, empty logical stream with a new identity.
- **No eviction, no `gap`, no `full`, no capacity key** — and a `:dao.stream.memory-log/…` key in a creation spec is rejected rather than ignored, so a caller cannot quietly ask for one.
- **No attachments**: no `attach!`, no attachment identities, no frozen tails, no `:dao.stream/closed` from `cursor`.
- **No encoding.** Values are held by reference, so this is not a serialization boundary; a live handle can pass through it, and no value is uncarriable.
- **No size, depth, or retention query**, and no `closed?` predicate.
- **No readiness or waiter mechanism** — the contract has none.
- **No rotation, and no support for it** (§3).

A later **durable** transport is a different transport, not this one grown. It would add: a medium that survives restart, and therefore an identity recovered from the medium rather than minted at creation; `attach!` resolving against the filesystem rather than a host directory; `:dao.stream/transport-error` on `cursor`, `next` and `append!` — a durable read is exactly a failure an operation can observe and return from, which is the amended contract's own line, and locating the earliest retained position on a durable log is itself such a read (`dao.stream.md:483-488`); `:dao.stream/invalid-value`, because it encodes and is therefore a serialization boundary; a real `:dao.stream/full`, because a disk is genuinely bounded; and a `close!` that flushes, hence a named channel where flush failure surfaces (`dao.stream.md:583-586`). Nothing in `dao.stream.memory-log` should be shaped to anticipate any of that.

## 7. Phasing and the handoff

**Phase A, two commits.**

1. `docs(stream): distinguish observable exhaustion from fatal host failure` — the *Complete history* amendment already sitting uncommitted in the working tree (`dao.stream.md:729-737`). r1 said the contract was untouched; that is no longer true, and this is the change.
2. `add the memory-log complete-retention transport` — `src/cljc/dao/stream/memory_log.cljc`, `test/dao/stream/memory_log_test.cljc`, `test/dao/stream/retention_falsification_test.cljc`, and `run-retention-laws` in `test/dao/stream/conformance.cljc`. `dao.stream.ringbuffer` is untouched.

No new design doc: the namespace docstring and the manifest are this transport's specification, and `dao.stream.file.md` documents a far larger thing.

**Phase B.** The transactor plan is revised against it. The sentence it can quote:

> The host composition supplies `dao.space` a local handle created by `dao.stream.memory-log/create!`. Its declared complete retention makes fresh `:oldest` cursors true origin cursors for `derive-next-t` and `publish-index!`; supplying an evicting transport is a host-assembly defect.

Ownership is preserved deliberately: `dao.space` does not create its local stream and must not start. What that plan must **not** ask of the transport: durability across restart, a size or retention query, a `closed?` predicate, a capacity, or rotation — which §3 records as unavailable, not as delegated.

## Verification

- `bb test:clj` — JVM: conformance suite including the new retention laws, the falsification test, the totality tests, the creation-spec rule, and the concurrent linearizability histories.
- `bb test:cljs` — Node; confirm `Testing dao.stream.memory-log-test` and `Testing dao.stream.retention-falsification-test` both appear in the output, since the shadow `:node-test` build discovers namespaces rather than reading an allowlist.
- `bb test:cljd` — Dart; the same, minus the thread-based block. The negative and beyond-tail position tests carry no host conditional and must run on all three.
- Regression: `dao.stream.ringbuffer-test` must pass unchanged — `run-retention-laws` runs only for a manifest carrying `:retention`, and the real ring manifest carries none.
