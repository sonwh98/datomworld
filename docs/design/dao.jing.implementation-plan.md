# dao.jing on DaoStream v2

Status: implementation plan, revision 9, subordinate to
[`dao.stream.md`](./dao.stream.md) (the contract) and
[`dao.jing.md`](./dao.jing.md) (the design). `dao.stream.v2` is implemented
and stable on clj, cljs (Node) and cljd. This document says how to **build
`dao.jing` on it and delete what is there**. The existing implementation and
its tests have no authority; two things do: the contract, and the invariants
listed below. Revisions 1–6 are the record of how those invariants were
argued out (`cdb871c` is revision 5). Revision 8 introduced a shared
observation step; this revision corrects where that step comes from. This
document is transient.

## What DaoJing is, in one paragraph

A `dao.jing` observes an explicit pool of `dao.stream.v2` reader handles and
materializes every payload into content-addressed storage:
`key = :segment/sha256-<hash(canonical-encode(x))>`, `KV[key] = x`,
insert-if-absent. It knows nothing about what a payload means. Content-store
handles are plain maps of three backend effects, `{:put-content-fn
:get-content-fn :close-fn}`; `materialize!`, `get` and `close!` dispatch
through them. Two backends: `dao.jing.mem` and `dao.jing.file`. That is the
whole of `dao.jing.md`'s Definition, and nothing here changes it.

## The invariants

Each is marked with its source: **[D]** stated in `dao.jing.md`; **[T]** pinned
by an existing test; **[T→D]** pinned by a test, not stated in the design, and
judged a real invariant that P3 adds to `dao.jing.md`; **[T✗]** pinned by a
test, judged an accident of the old implementation, and dropped. Every
**[T✗]** is named so the implementer knows it was seen, not missed. Each group
maps to one test namespace. The E group additionally says which side of the
observation seam guarantees it (Decision 0).

### A. Content addressing — `jing_test`

- A1 [D] The address is derived from the payload alone:
  `(segment-key x)` is `:segment/sha256-<64 lowercase hex>` and nothing else
  enters it — not the source stream, not arrival order, not pool position.
- A2 [D] Equal values address equally on every host. `content-hash` is
  order-insensitive over maps and sets and total over every value, `nil`
  included. Pinned by SHA-256 known-answer vectors on all three hosts
  (`jing_test.cljc:56-77`), the only check that cljd's hand-rolled digest
  mints the same addresses as the JVM and Node.
- A3 [T→D] Distinct values address distinctly, both across types (`42` and
  `"42"` do not collide) and within one type (`{:a 1}` and `{:a 2}` do not).
  The design's "equal supported values produce the same bytes" leaves the
  converse implicit; it belongs in *Canonical encoding*.
- A4 [T→D] Minted addresses are readable EDN: the name never starts with a
  digit, and a key survives `pr-str` → `read-string`. Addresses cross every
  EDN boundary in the system; the `sha256-` prefix is load-bearing for that.
- A5 [D] `segment-address?` is the strict test, and `segment-hash` throws on
  anything that is not a valid address — foreign namespace, wrong length,
  non-hex, uppercase.
- A6 [D] The encoder is transitional (order-normalized `pr-str`), and
  `content-hash`, `segment-key` and every address change together when the
  canonical byte encoding lands. Unchanged, still open.

### B. Materialization — `jing_test`, `mem_test`

- B1 [D] `materialize!` derives the address itself, calls
  `(put-content-fn address payload)`, and returns the address only after the
  backend reports success.
- B2 [D] A backend answers exactly `:inserted` or `:present`; any other
  answer — `true`, `nil`, `:ok`, `"inserted"`, a map — is an invalid backend
  result and throws.
- B3 [D] On `:present` the stored value is read back and compared. An absent
  read-back or an unequal value is an integrity failure that throws; nothing
  is ever overwritten. Re-materializing an equal value is a no-op that
  returns the same address.
- B4 [D] What is stored is exactly the payload. No provenance, no source
  identity, no stamp. Equal payloads arriving through different pool members
  land on exactly one entry.
- B5 [T→D] `nil` is a legal payload distinct from absence: `get` takes a
  caller-supplied not-found and returns stored `nil` as `nil`.
- B6 [T→D] A backend validates before it writes: the address must be a
  segment address and must hash to the payload, else it throws and stores
  nothing. This is the integrity precondition every backend, including a
  future remote server, enforces at its own door.
- B7 [T✗] `:dao.jing/content-missing` is a legal payload. This test exists
  because an earlier sentinel was that keyword. The general rule — any value
  is a payload, and the not-found sentinel is an opaque per-host object no
  payload can equal — is B5; the specific keyword case is dropped. It is
  dropped as a *case*, not as coverage: B5's executable test must include
  keyword payloads, the value formerly used as a sentinel among them, so a
  regression to a keyword sentinel is caught by a test rather than by prose.

### C. Reads — `jing_test`

- C1 [D] `get` accepts only segment addresses; arbitrary keys and mutable
  roots throw **before the backend is consulted**.
- C2 [D] `get` returns the caller-supplied not-found for an absent address
  and only for that address.

### D. Handle lifecycle — `jing_test`, `mem_test`, `file_test`

- D1 [D] A handle is plain data: `:put-content-fn`, `:get-content-fn`, an
  optional `:close-fn`. `materialize!` and `get` throw on a handle missing
  the function they need.
- D2 [D] `close!` delegates to `:close-fn` and returns `nil`; a handle without
  one has nothing to release.
- D3 [T→D] A backend's close is idempotent; after close, every entry point —
  `materialize!`, `get`, and the raw fns — throws, and stored content is
  neither cleared nor rewritten.
- D4 [T✗] The handle carries a `:state` atom of shape `{:closed? :content}`,
  and carries no `:stream` key. The observable is B4; the atom shape is
  implementation. The rewrite exposes what B4 needs — a test-visible content
  view for the mem backend, `records` (F5) for the file backend — and pins
  neither the atom nor the absence of a key that guarded a design that no
  longer exists.
- D5 [T] Under JVM contention, N concurrent puts of one payload yield exactly
  one `:inserted`, N−1 `:present`, and one stored entry — for both backends.

### E. The intake pool and the observer — `dao/stream/v2/observe_test` (core step), `jing_test` (pool), plus the pool drains in `mem_test`, `file_test`, `dht_test` and the five `dao.space` tests

Each carries the side of the seam that guarantees it: **[step]** the core
`dao.stream.v2.observe/step`; **[pool]** `dao.jing`'s coordination over it;
**[both]** guaranteed by the step and re-asserted by the pool.

- E1 [D] [pool] The pool is supplied explicitly as `{:stream :cursor}`
  members; the observer registers nothing, discovers nothing, and holds no
  atom. Its state is plain data, `{:members [...] :next i}`, and
  `(observer-state (:members st))` rebuilds it.
- E2 [D] [both] The cursor is minted by the composition from an anchor of its
  choosing and retained exactly as the stream returns it. The step guarantees
  that a single observation advances only to the successor `next` returned,
  or leaves the cursor untouched; the pool guarantees that `adopt-cursor` is
  the only other way a member's cursor changes, and it takes a value the
  stream handed out. No arithmetic, no inspection of shape, no fabrication,
  on either side.
- E3 [D] [pool] A member without a cursor, or whose stream lacks the reader
  surface, is a composition defect: `observer-state` throws before any
  operation.
- E4 [D] [both] Outcomes are exactly `dao.stream.v2/outcomes-next`. The step
  classifies all seven as data; a read answer outside the set, or not an
  outcome map, is classified as a `transport-error` defect with the raw
  answer retained — the rule `forward-step`'s `malformed-result` already
  applies — never thrown. The pool's signals are the same seven keywords.
  Checked declaration-driven on both sides. (Revision 8 had the step throw
  on an out-of-set outcome; `forward` had already got this right, and the
  VM's throw-on-unexpected is its own policy above the seam.)
- E5 [D] [step, structurally] `materialize!` runs before the cursor advances;
  if it throws, the exception propagates and the caller's state is
  untouched, so the same payload is reprocessed from the same cursor once
  the backend succeeds. This is not a rule the pool remembers. The core step
  runs the effect between the read and the advance and advances only on the
  effect's success; a throwing effect propagates before any advance exists.
  `forward-step` has had this shape since it was written — `recur
  next-cursor` appears only in the write-`ok` branch (`forward.cljc:130`) —
  and the VM has it from `run-on-stream`'s publish ordering
  (`stream_observer.cljc:141-143`). One place, three callers.
- E6 [D] [pool] Round-robin fairness: `observe-step!` starts at `:next`, walks
  the pool once, processes at most one payload, and yields that member's
  turn. A continuously ready member cannot starve another; blocked and ended
  members never prevent later members from being checked.
- E7 [T✗] Two equal-length ready members drain in strict `A B A B` order. One
  schedule that satisfies E6; the design says scheduling never affects the
  content set. The test may keep it as an illustration; it is not owed.
- E8 [D] [pool] Empty pool → `blocked`; a pool whose every non-ended member
  answers `blocked` → `blocked`. All members ended → `end`. A closed member
  still yields its retained payloads, then `end`.
- E9 [D] [both] `gap` and the three defect outcomes are reported immediately
  with `:member i` — `gap` with its recovery cursor, the defects with the
  raw outcome map under `:result` — leave the member's cursor unchanged,
  move the scheduling index past the member, and are reported again on that
  member's next turn. Nothing is auto-resynchronized. The step supplies the
  raw read and the recovery cursor as data and moves nothing; the pool
  decides to report and not adopt.
- E10 [D] [both] The observer never interprets a payload. The step hands the
  value to the effect untouched; the pool hashes and stores.
- E11 [T] [composition] What the `dao.space` drains pin, and nothing more:
  drain until `blocked` or `end`; a `gap` is fatal to that test composition.
- E12 [T✗] [pool] A reader answering a malformed map — missing `:ok` or
  `:cursor` — makes `observe-step!` **throw**, rather than content-address
  `nil` or install a nil cursor. The property it protects survives; the throw
  does not. E4 classifies a malformed answer as a `transport-error` defect and
  reports it as data, so nothing is content-addressed and no cursor moves,
  which is what the old test was defending. Recorded because the behaviour
  change is deliberate: a caller that relied on the exception gets an outcome
  map instead.

### F. The file backend — `file_test`

- F1 [D] Each record is `[address payload]` appended to a file; a put is
  acknowledged `:inserted` only after the write is flushed.
- F2 [D] On open, the file is replayed into the content map. An equal
  duplicate record is tolerated; an unequal record at an existing address
  is a collision and the open fails.
- F3 [D] An incomplete tail is truncated before replay — shorter than the
  length prefix, a negative length, a length past end of file. After
  truncation the surviving records replay and a subsequent put lands clean.
  Correctness evidence for the truncation code; there is no stored content
  to be compatible with, and the framing is free.
- F4 [D] A complete record that cannot be decoded, is not a two-element
  vector, carries a non-segment address, or does not hash to its payload
  fails the open — closed, loudly.
- F5 [T] Acknowledged inserts survive close and immediate reopen; a
  `:present` put writes no record. The backend exposes `records`, the
  decoded record vector of a path, as the test's view of the file.
- F6 [T→D] D3 and D5 for this backend: idempotent close, throw after close,
  serialized concurrent puts with exactly one record written.
- F7 [T✗] The length-prefix layout, the `pr-str` encoding, the handle's `:log`
  and `:write-lock` keys. Implementation, owed nowhere.

### G. Deferred: the remote store

Invariants for a remote content store are Decision 3's. None is built here.

## Decisions

### Decision 0 — One core step, with `forward` as its first caller

**The correction to revision 8.** Revision 8 cited `dao.stream.v2.forward` as
"the precedent for a transport-agnostic interpreter step living under the
contract's namespace" — a claim about where a file may live — and never asked
whether `forward` made the new file unnecessary. It also took the step's
design from `yin.vm.v2.stream-observer`, the caller that hardcoded its
policies, when the stronger design was in `forward`: outcomes as data under
`:status` (`forward.cljc:135-138,163-166`), gap policy as a caller-supplied
parameter (`61-67,86-92`), and the cursor advanced only after the effect
succeeds (`130`). `forward` got it right; revision 8 proposed writing it
again beside it. That was wrong.

**The test applied, and where it lands.** A core is real only if it stays
parameter-light: if serving three callers needs `:gap-policy` *and*
`:batch-budget` *and* a terminal-naming function, three clear implementations
were right. Walked honestly against all three callers, the core needs
**three parameters and no policy**: a source, a cursor, and an effect. The
gap policy is a decision the caller makes on the returned `:gap`; the budget
is a loop the caller writes around the step; terminal naming
(`:source-ended`, `:destination-closed`) is a rename the caller applies to
`:status` and `:outcome`. Every one of `forward`'s subtleties — the resume
allowance, the fixed-point rule, terminal-state re-stepping as a no-op — is
loop-level and stays in `forward`. The factoring passes. It is not
over-abstraction; it is the shape all three already have, written once:

```
read one value at cursor
  ok       → run the effect on the value
               ok           → advance to the successor
               not yet      → keep the cursor, retry later
               failed       → stop
  blocked  → keep the cursor
  end      → keep the cursor
  gap      → hand back the recovery cursor; the caller decides
  defect   → stop, raw answer retained
```

**The core: `dao.stream.v2.observe/step`.** A new namespace requiring only
`dao.stream.v2`. Stateless, no loop, one function:

```clojure
(observe/step source cursor effect)
;; effect: (fn [value] -> outcome-map) answering with the writer outcome set —
;;   :dao.stream/ok, /full ("not yet"), /invalid-value, /closed, /transport-error.
;;   Any other answer is classified as /transport-error with the raw answer
;;   retained (forward's malformed-result rule). A throwing effect propagates
;;   before any advance exists.
;;
;; returns, always with :cursor and :read (the raw next result):
;;   {:status :advance :cursor successor :effect e}                 ; effect ok
;;   {:status :retry   :cursor cursor :outcome :dao.stream/blocked}
;;   {:status :retry   :cursor cursor :outcome :dao.stream/full :effect e}
;;   {:status :ended   :cursor cursor :outcome :dao.stream/end}
;;   {:status :gap     :cursor cursor :outcome :dao.stream/gap :recovery c'}
;;   {:status :defect  :cursor cursor :outcome k}                   ; source: cursor-mismatch, invalid-cursor, transport-error, or a malformed read
;;   {:status :failed  :cursor cursor :outcome k :effect e}         ; effect: invalid-value, closed, transport-error, or a malformed effect answer
```

`:retry` from the effect is produced only by an effect with a "not yet"
answer — today only `forward`'s `append!` answering `full`. A function
effect that wraps `materialize!` or `load-program` answers `ok` or throws, so
the branch costs it nothing. The effect-answer convention — the writer
outcome set — is the one real coupling the core imposes; both function
callers meet it with a two-line wrapper, and it is what lets `forward` pass
`append!` through unwrapped.

**`forward` refactors onto it with no behavioural change.** `forward-step`
keeps its namespace, signature, options, `initial-state` and
`default-options`, and becomes the loop it already is over `observe/step`
with `(fn [v] (stream/append! destination v))` as the effect: `:advance` →
`recur next-cursor (dec remaining) resumes (inc forwarded)`; `:retry` →
`result-state cursor :retry forwarded outcome` for both `blocked` and `full`,
exactly as `blocked-and-full-do-not-advance-cursor` pins; `:gap` → the
existing `cond` — policy via `gap-action`, fixed point when
`(= recovery cursor)` terminates as `:source-gap`, resume while the
allowance lasts, else `:continue` at the recovery cursor; `:ended` and
`:defect` → `terminal-status :source`; `:failed` → `terminal-status
:destination`. The terminal-state no-op on re-step (`108-111`) and the
budget-zero early return stay in front of the loop. The `malformed-result`
guard moves into the core, where both `next` and the effect answer are
classified, and `forward` keeps its `:transport-error` `:outcome` on the same
inputs. Its five tests — `forwards-a-bounded-batch`,
`blocked-and-full-do-not-advance-cursor`, `gap-policy-is-explicit`,
`gap-resume-is-bounded-and-total`,
`terminal-outcomes-are-explicit-and-no-close-is-implied` — change no
assertion. Its one production consumer, `dao.stream.v2.serving`, calls
`forward-step`, `initial-state` and `default-options` (`serving.cljc:131,255,312`)
and is untouched.

**The VM refactors onto it with no behavioural change.**
`yin.vm.v2.stream-observer` keeps `attach`, `observe-next` and
`run-on-stream` with their shapes. `observe-next` is `observe/step` with a
null effect (`(fn [_] {:dao.stream/outcome :dao.stream/ok})`): `:advance` →
`{:status :ok :batch (get-in r [:read :dao.stream/value]) :observer observer'}`;
`:retry` (blocked) and `:ended` retain the cursor; `:gap` adopts `:recovery`
and increments `:ingress-gaps`; `:defect` throws with the *original* read
outcome from `:read` — so `observe-next-throws-on-terminal-and-unexpected-outcomes-test`
still sees the unexpected keyword it scripted, not the core's classification
— plus `:cursor` and `:ingress-gaps` as today. `run-on-stream` uses
`observe/step` with `(fn [batch] {:dao.stream/outcome :dao.stream/ok :vm
(load-program vm batch)})` as the effect, runs `run-vm` on the loaded VM, and
publishes the session after both; a throwing loader propagates inside the
core before any advance, which is `a-failing-loader-leaves-the-old-cursor-for-a-retry-test`
unchanged. The gap auto-advance and the terminal throw are now visibly the
VM's policy, applied to the core's data. None of its 17 tests changes an
assertion; `yin.repl.v2.core` and `continuation_handoff_v2` consume the VM
namespace only.

**What is not shared**, as in revision 8: attachment (the anchor is the
composition's policy; the VM keeps `attach`, DaoJing has none); state (the
core has none, so `:ingress-gaps` never enters the seam); coordination
(`forward`'s budget loop, the VM's `run-on-stream`, DaoJing's round-robin
pool — three different loops over one step).

**Layering.** `dao.stream.v2.observe` sits where `forward` sits: an
interpreter over the contract, owned by neither storage nor the VM, requiring
only `dao.stream.v2`. `forward` keeps its namespace as the thin layer that
adds the loop, the resume allowance and the naming, because that is what it
is now and what its consumer imports.

### Decision 1 — DaoJing's observer, built on the step

DaoJing's observer is pool coordination over `observe/step` with
`materialize!` as the effect. Entry shape `{:stream <v2 reader handle>
:cursor <opaque> :status s}`; `observer-state` validates E3 and holds
`{:members [...] :next i}`; `adopt-cursor` is the one other cursor mutation.
One call of `observe-step!` walks the pool once from `:next`:

```clojure
;; per member, in turn:
(observe/step stream cursor
              (fn [payload] {:dao.stream/outcome :dao.stream/ok
                             :address (jing/materialize! handle payload)}))
;; :advance → member cursor := successor, status :dao.stream/ok, :next := after this member;
;;            return {:signal :dao.stream/ok :address (get-in r [:effect :address])}
;; :retry   → status :dao.stream/blocked, continue the walk
;; :ended   → status :dao.stream/end, continue the walk
;; :gap     → status recorded, :next := after this member;
;;            return {:signal :dao.stream/gap :member i :cursor (:recovery r)}
;; :defect  → status recorded, :next := after this member;
;;            return {:signal (:outcome r) :member i :result (:read r)}
;; :failed  → impossible: the effect answers ok or throws (E5)
;; walk exhausted → {:signal :dao.stream/end} if every member ended, else {:signal :dao.stream/blocked}
```

Signal table:

| signal                                                    | when                                                       | extra keys                                      | member cursor            |
| --------------------------------------------------------- | ---------------------------------------------------------- | ----------------------------------------------- | ------------------------ |
| `:dao.stream/ok`                                          | a payload was materialized                                 | `:address`                                      | successor, from the step |
| `:dao.stream/blocked`                                     | empty pool, or every non-ended member blocked              | —                                               | unchanged                |
| `:dao.stream/end`                                         | every member has ended                                     | —                                               | unchanged                |
| `:dao.stream/gap`                                         | member `i`'s position was evicted                          | `:member i`, `:cursor` (the step's `:recovery`) | unchanged                |
| `/cursor-mismatch`, `/invalid-cursor`, `/transport-error` | member `i`'s read failed, or answered outside the contract | `:member i`, `:result` (the step's `:read`)     | unchanged                |

What the pool adds to the step is exactly E1, E3, E6, E8 and the reporting
half of E9; what it inherits is E2's successor discipline, E4's totality, E5
structurally, E9's raw read and recovery cursor as data, and E10.

### Decision 2 — The durable log is not a stream

`dao.jing.file` writes its own framed file. It does not use a stream
transport, and none is built for it.

**Why.** The file has one reader — the backend itself, once, at open — and
one writer — the backend's own put. No cursor is ever handed out, no
descriptor projected, nothing attaches, and the file never sits in a pool.
Every property the contract exists to guarantee is unused, and a transport
with one consumer would owe the conformance suite for nobody. The parallel is
`dao.jing.mem`: a private atom behind the backend effects; `dao.jing.md`
already says "backend effects are explicit functions, not a protocol or
hidden state." Wrapping the file as a public `:dao.stream/type` also made
DaoJing's storage format a transport anyone could open.

**Why a v2 append-log is not an alternative.** It is constructible —
`append!`'s `ok` "implies neither local readability nor remote delivery"
(`dao.stream.md`, Writing) — but it cannot supply what F1 needs: `:inserted`
means "durably stored now", and an `ok` that disclaims durability cannot
honestly become it. Either the transport grows a transport-owned durability
signal contract-generic code may not reach for, or `materialize!`'s contract
changes so durability arrives later as data, which reaches
`dao.data.btree.storage` and `dao.space.index`. That is the write-path
redesign, not a transport choice.

**The pre-existing question.** `datom.world.md:66-68`: an adapter that exposes
a function for portable code to call "keeps the coupling, and the effect
never appears as an emission." `:put-content-fn` is such a function, whatever
sits beneath it. Whether DaoJing's synchronous content handle conforms to Host
Boundaries is real, is not created here, and is cured by no transport. P3
records it in `dao.jing.md`'s open items as **"the content write path as an
effect stream"** — durability as data, `materialize!` no longer returning an
address synchronously — out of scope here because of its reach. The design
here is compatible with that future: `datom.world.md:57-59` puts raw host
operations inside the host interpreter, which is where this leaves the file
IO.

**Recorded dissent.** `gpt-5.6-sol` holds that direct file IO behind
`:put-content-fn` violates `datom.world.md:53-68` and a v2 append-log should
be built. The plan holds the violation, if any, is the synchronous handle
itself. `glm-5.3` and `gpt-6-astra` accept the plan's position.

### Decision 3 — The remote store: deferred, design recorded

**Why it is deferred, and why that is not legacy.** `jing/get` answers
synchronously; `dao.stream.v2.rpc` cannot answer synchronously on any host —
by contract, no operation waits, and on cljs and cljd there is nothing to wait
with. So a remote content store cannot present a local store's interface,
and that is true of code written from scratch today with no v1 anywhere. What
a remote store *is* — a client the caller steps, whose answers arrive as
completions — changes what `dao.space.index` does to restore a B-tree from a
remote coordinate, which is the async hydration `dao.data.btree.md` §5.4
defers, and belongs to `dao.space`'s plan. It is the one thing here that a
free rewrite does not dissolve. The current `dao.jing.remote`, its tests, and
`dao.jing.coordinate`'s JVM `:dao.jing/remote` branch stay as they are until
that plan; nothing else in `dao.jing*` depends on them.

**The design, for that plan.** Server side: `default-handlers` — `:jing/put-content`
returns `:inserted`/`:present`, `:jing/get-content` returns the exact envelope
`{:found? boolean :value v}` — served by `dao.stream.v2.apply/serve-once!`;
B6 holds at the server's door. Client side:

```clojure
(remote/client-state rpc-state)
(remote/request-put state address payload)   ; => {:outcome k :state s' :id n?}
(remote/request-get state address)
(remote/request-materialize state payload)
(remote/step state budget)                   ; => {:state s' :attempt k? :completions [...] :diagnostics [...]}
(remote/abandon state reason)
```

`request-*` validate as C1 and return the RPC layer's outcomes verbatim plus
`busy` while `rpc/unsent?` holds. `step` is the only path that clears `busy`,
in fixed order: (1) re-attempt `:unsent` through `rpc/request!` if not
terminal, folding the outcome under `:attempt`; (2) `rpc/poll!`; (3) if
terminal and `:unsent` remains, `rpc/abandon-unsent` with the terminal
reason; (4) issue unissued verify hops in put-id order until `full`, or if
terminal complete them `:lost`; (5) take completions and diagnostics exactly
once, routing each whose id a materialization record knows. Completion decode
is total: responses by op, malformed as `:error /malformed-response`, any
reason as `{:lost reason}` pass-through. `request-materialize` is B1–B3 over
the wire: derive the address, put, register a per-id record at any id-bearing
outcome, on `:present` issue the correlated get and register under its id
too, complete `:present` only on equality, else `:error /integrity-failure`
or `/present-but-absent`; one completion per materialization, carrying the
put id; the record's removal is the exactly-once guard. The verify hop is the
one obligation the client carries, in the shape `rpc`'s own `:unsent` and
`apply`'s `:pending-response` already sanction. Its lifecycle:

| record phase                                  | event                                                                                                                                                                                   | record                                                             | published                                   | routes by           |
| --------------------------------------------- | --------------------------------------------------------------------------------------------------------------------------------------------------------------------------------------- | ------------------------------------------------------------------ | ------------------------------------------- | ------------------- |
| *(none)*                                      | `request-materialize`: `requested` / `pending-request`                                                                                                                                  | created `:put`, registered under put id                            | nothing                                     | —                   |
| *(none)*                                      | `request-undeliverable`                                                                                                                                                                 | created, registered; completion already outboxed                   | next drain: `:lost reason`; removed         | put id              |
| *(none)*                                      | `allocator-error` / `terminal`                                                                                                                                                          | not created                                                        | nothing; no id                              | —                   |
| `:put`                                        | order 1 re-attempt: `requested` / `pending-request`                                                                                                                                     | unchanged                                                          | nothing                                     | —                   |
| `:put`                                        | order 1 undeliverable, `abandon` while unsent, order 3, or `lose-outstanding` (any terminal — `allocator-error` included since `39ad69e` — or non-terminal `gap` on an outstanding put) | removed                                                            | `:lost reason`                              | put id              |
| `:put`                                        | `:inserted`                                                                                                                                                                             | removed                                                            | `:result :inserted`                         | put id              |
| `:put`                                        | `:present`                                                                                                                                                                              | `:verify-unissued`                                                 | nothing                                     | put id              |
| `:put`                                        | `:error`                                                                                                                                                                                | removed                                                            | `:error e`                                  | put id              |
| `:put`                                        | a get-id completion                                                                                                                                                                     | impossible — none allocated                                        | —                                           | —                   |
| `:put` / `:verify-issued`, envelope `:unsent` | non-terminal `gap`                                                                                                                                                                      | no-op; order 1 re-attempts                                         | nothing                                     | —                   |
| `:verify-unissued`                            | order 4, `rpc/unsent?` true                                                                                                                                                             | unchanged; retried next step                                       | nothing                                     | —                   |
| `:verify-unissued`                            | order 4, get `requested`                                                                                                                                                                | `:verify-issued`, registered under get id                          | nothing                                     | —                   |
| `:verify-unissued`                            | order 4, get `pending-request`                                                                                                                                                          | `:verify-issued`, registered; issuing stops this step              | nothing                                     | —                   |
| `:verify-unissued`                            | order 4, get `request-undeliverable`                                                                                                                                                    | `:verify-issued`, registered; completion outboxed                  | order 5, same step: `:lost reason`; removed | get id              |
| `:verify-unissued`                            | order 4, get `allocator-error`                                                                                                                                                          | removed; other outstanding records complete via `lose-outstanding` | `:lost /allocator-error`                    | synthesized; put id |
| `:verify-unissued`                            | order 4, state terminal                                                                                                                                                                 | removed                                                            | `:lost terminal-reason`                     | synthesized; put id |
| `:verify-unissued`                            | `abandon` / non-terminal `gap`                                                                                                                                                          | no-op: no envelope or outstanding id of this record                | nothing                                     | —                   |
| `:verify-unissued`                            | any RPC completion                                                                                                                                                                      | impossible                                                         | —                                           | —                   |
| `:verify-issued`                              | order 1 re-attempt: `requested` / `pending-request`                                                                                                                                     | unchanged                                                          | nothing                                     | —                   |
| `:verify-issued`                              | order 1 undeliverable, `abandon` while unsent, order 3, or `lose-outstanding`                                                                                                           | removed                                                            | `:lost reason`                              | get id              |
| `:verify-issued`, get outstanding             | `abandon`                                                                                                                                                                               | no-op                                                              | nothing                                     | —                   |
| `:verify-issued`                              | get `:found? true`, `=` payload                                                                                                                                                         | removed                                                            | `:result :present`                          | get id              |
| `:verify-issued`                              | get `:found? true`, `≠` payload                                                                                                                                                         | removed                                                            | `:error /integrity-failure`                 | get id              |
| `:verify-issued`                              | get `:found? false`                                                                                                                                                                     | removed                                                            | `:error /present-but-absent`                | get id              |
| `:verify-issued`                              | get `:error`                                                                                                                                                                            | removed                                                            | `:error e`                                  | get id              |
| `:verify-issued`                              | order 4, or a put-id completion                                                                                                                                                         | impossible                                                         | —                                           | —                   |
| *removed*                                     | any completion for a former id                                                                                                                                                          | impossible — each id completes once                                | —                                           | —                   |

Its tests: one per reachable row, plus `busy` clears only through `step`,
unsent-at-detach, `abandon` before rebind, reason pass-through by
declaration, diagnostics once, and the wire cases over `dao.stream.v2.ws` on
all three hosts.

## What is built, in four phases

Each phase builds something checkable on clj, cljs (Node) and cljd, deletes
what it replaces, and leaves the suite green. Order is by dependency: the
core first, because three callers go through it and two of them ship today;
the file backend depends only on B and D; the pool depends on the core and on
both ends of the pool being v2; the prose depends on all three being real.

### P0 — The core step, and its two shipped callers

**Build** `src/cljc/dao/stream/v2/observe.cljc` per Decision 0: `step`,
requiring only `dao.stream.v2`. Refactor `dao.stream.v2.forward/forward-step`
into its loop over `observe/step`, moving `malformed-result` into the core;
refactor `yin.vm.v2.stream-observer/observe-next` and `run-on-stream` over
it; `attach`, every export and every result shape unchanged. This is one
phase, not three, because the proof that the core is right *is* that both
callers' suites do not move: landing the core without refactoring them would
prove nothing, and refactoring one without the other would leave the core
with a single caller and no evidence it generalizes.

**Delete** nothing outside the two refactored bodies.

**Prove** with the new `test/dao/stream/v2/observe_test.cljc`:
declaration-driven classification — every outcome in
`dao.stream.v2/outcomes-next` maps to exactly one `:status`, every answer in
`outcomes-append` from the effect likewise, an out-of-set or non-map read
answer is `:defect` with `:outcome :dao.stream/transport-error` and the raw
answer under `:read`, an out-of-set effect answer is `:failed` likewise under
`:effect`; the effect is called exactly once, only on a read `ok`, with the
observed value; a throwing effect propagates with no result returned; the
cursor is the successor on `:advance` and unchanged on every other status;
`:recovery` is present exactly on `:gap`. And, unchanged: `forward_test`'s
five, `stream_observer_test`'s seventeen, `serving_test`, the ws and slice
suites, the REPL v2 suites, and `continuation_handoff_v2` — on each host, as
the evidence that nothing observable changed. Confirm `Testing
dao.stream.v2.observe-test` in the Node output.

### P1 — The file backend

**Build** `dao.jing.file` from F1–F6: `create-content-file` returning
`{:put-content-fn :get-content-fn :close-fn}` over a private framed file —
open or create, truncate an incomplete tail, replay, append-and-sync — with
`records` for F5. Three host branches (`RandomAccessFile`, Node synchronous
`fs`, `dart:io` `RandomAccessFile`), `#?(:cljd … :clj … :cljs …)` with `:cljd`
first; `#?(:clj …)` alone does not exclude code from the cljd build.

**Delete** `src/cljc/dao/stream/log.cljc` and `test/dao/stream/log_test.cljc`
— its only consumer was this backend, and its `defopen :append-log` is a
load-time registration into the ambient v1 registry. `dao.stream.file`
(live-tail `:file`, consumed by `yin.io.file`) is a different transport and
is untouched. Delete the old `file_test.cljc`.

**Prove** with a new `file_test.cljc` written from F1–F6 and D1–D5: round trip
of every payload kind including `nil`; `:inserted` then `:present` with an
unchanged record count; durability across close and reopen; acknowledged
insert survives immediate close; equal duplicate records recover; collision
on replay fails the open; each fail-closed category (malformed EDN, wrong
shape, invalid address, hash mismatch) fails the open; truncation of an
overlong-length tail, a sub-prefix tail, and a negative length, each followed
by a clean put and reopen, with the torn bytes hand-written per host; put and
get throw after close, close idempotent; JVM contention writes exactly one
record. `btree_durability_test`'s `file-close-reopen-recovery-test` runs
unchanged as a consumer check (one stale comment fixed).

### P2 — The pool, its two ends, and the in-memory backend

**Build** in `dao.jing`: `observer-state`, `observe-step!`, `adopt-cursor`
per Decision 1 over `dao.stream.v2.observe/step`, requiring `dao.stream.v2`
and `dao.stream.v2.observe`; the content-addressing core (A, B, C, D) is
already what the invariants say and is kept. Keep `dao.jing.mem` (it
satisfies B and D), dropping the D4 pins. Move the pool's writer end to v2:
`dao.space.index/append-ok!` (`index.cljc:526-531`) appends with
`dao.stream.v2/append!` and requires `:dao.stream/ok`; the transactor's
intake validation (`transactor.cljc:197-201`) uses `dao.stream.v2/writer?`.
The pool is one thing with two ends, and `dao.jing.md` §Publication names
`publish-index!` as its writer; the rest of `dao.space` — the agent-local
stream, `snapshot-datoms`, the transactor's own protocols, the published-index
`defopen`, `dao.space.query` — is its own plan's.

**Delete** the old observer functions and their `dao.stream` require from
`dao.jing`; the observer section of `jing_test.cljc` and its v1 fixtures; the
v1 `open-stream` helpers in `mem_test.cljc` and `dht_test.cljc`; the
`open-intake` helpers in the five `test/dao/space/` files.

**Prove** with `jing_test.cljc` rewritten from A–E over v2 ring buffers
(`ringbuffer/create!`, cursors from `stream/cursor … :dao.stream/oldest`):
known-answer digests on every host; address determinism, order-insensitivity,
totality, cross-type distinctness, EDN readability, `segment-hash`
strictness; `materialize!` derivation, verdict vocabulary, `:present`
read-back with the absent and unequal failures, idempotence, no stamp; `get`
strictness before the backend and not-found; handle-fn presence and `close!`
delegation; the pool — plain-data state, missing cursor and non-reader throw,
empty pool blocked, all blocked, hashing and retrieval, convergence from two
members, blocked and ended members do not prevent later ones, all ended,
drains then end, fairness (a continuously ready member yields), the
poison-payload case showing E5 through the seam, `gap` on a capacity-2
buffer with three appends (reported with `:member` and recovery cursor,
cursor unchanged, other member progresses, reported again next turn,
`adopt-cursor` then materializes the live value), the three defect outcomes
and an out-of-set answer from a scripted reader, each carrying `:result`,
declaration-driven totality over `outcomes-next`, members round-trip through
`observer-state`. `mem_test.cljc` rewritten from B, D, D5 and one
pool-convergence case; `dht_test.cljc:402`'s pool case and the five
`dao.space` drains rewritten over v2 intakes with `:dao.stream/…` signals,
`gap` fatal as before (E11). Confirm `Testing dao.jing-test` in the Node
output.

### P3 — `dao.jing.md`

Write the [T→D] invariants into the design — A3, A4 into *Canonical
encoding*; B5, B6 into *Materialization rule*; D3 into *Resource lifecycle*;
F6 into the file backend's entry. Rewrite *The intake pool* and *Cursor
tracking and recovery* to Decision 1's entry shape, cursor discipline and
signal table, and say that the observation step is `dao.stream.v2.observe`,
shared with `forward` and the VM, and that E5 is a property of that seam.
Rewrite *Implemented surface*: the file backend as "a content-addressed store
backed by a private framed append-only file" (Decision 2's design-document
change, routed as such); `dao.jing.remote` as awaiting `dao.space`'s plan
with Decision 3 as its target. *Open items*: durable checkpoints store
whatever cursor value the transport minted, serializability TBD in the
contract; add **"the content write path as an effect stream"** per
Decision 2. Untouched: *Definition*, *Publication*, *Storage ignorance*,
*Physical intake versus semantic composition*, *Reads*, *Lineage*,
`dao.jing.dht.md`.

## Host matrix

| phase | clj | cljs (Node) | cljd | notes                                                                                                      |
| ----- | --- | ----------- | ---- | ---------------------------------------------------------------------------------------------------------- |
| P0    | ✓   | ✓           | ✓    | pure `.cljc`; `forward`, VM, serving, ws and REPL suites unchanged; cljd lane regenerates `test/cljd-out/` |
| P1    | ✓   | ✓           | ✓    | three host branches in `dao.jing.file`; torn-tail bytes hand-written per host                              |
| P2    | ✓   | ✓           | ✓    | pure `.cljc`                                                                                               |
| P3    | —   | —           | —    | prose                                                                                                      |

## Invariants of the whole, checked

No hidden global state: `dao.stream.v2.observe` and `dao.jing` hold no atom;
backend state is per-handle; P1 removes a `defopen` from the ambient
registry. No implicit control flow: `step` and `observe-step!` return; the
composition calls again. No callbacks in any surface touched: the effect is
an argument the caller passes and the step calls once, synchronously, before
returning — the same shape as `forward-step`'s policies, not a continuation
handed to host machinery. No shared mutable state. Interpretation and
execution separate: the step observes, the effect executes, the caller
coordinates. No assumed graphs.

## Boundary

**Built or deleted here:** `dao.stream.v2.observe` (new), the loop bodies of
`dao.stream.v2.forward` and `yin.vm.v2.stream-observer` (exports unchanged),
`dao.jing`, `dao.jing.file`, `dao.jing.mem` (kept), `dao.stream.log`
(deleted), the two writer seams in `dao.space.index` and
`dao.space.transactor`, `observe_test` (new), `jing_test`, `mem_test`,
`file_test`, `log_test` (deleted), the pool cases in `dht_test` and the five
`dao.space` tests, one comment in `btree_durability_test`, `dao.jing.md`.

**Not here, each with its home:** `dao.jing.remote`, `remote_test` and
`dao.jing.coordinate`'s remote branch — Decision 3, `dao.space`'s plan;
`dao.jing.dht*` — the UDP transport, deferred by the stream plan; everything
else in `dao.space*` — its own plan; the content write path as an effect
stream — a DaoJing architecture item; a content-serving endpoint — whatever
product surface needs it; canonical encoding, durable checkpoints and a
runner, materialization acknowledgement, garbage collection — `dao.jing.md`'s
open items, unchanged.

## End condition

On clj, cljs (Node) and cljd: `dao.stream.v2.observe` exists, requires only
`dao.stream.v2`, and `dao.stream.v2.forward`, `yin.vm.v2.stream-observer` and
`dao.jing` all observe through it, with `forward`'s five and the VM's
seventeen tests unchanged and green; `dao.jing`, `dao.jing.file` and
`dao.jing.mem` require no `dao.stream*` namespace; every invariant A–F has a
test that exercises it, the E group on the side of the seam that guarantees
it, and the [T→D] ones are in `dao.jing.md`; `dao.stream.log` is gone;
`dao.space`'s pool writers accept v2 writers and every `dao.space` suite is
green over v2 intakes; `dao.jing.remote`, `dao.jing.coordinate`'s remote
branch and `dao.jing.dht*` are the only things under `dao.jing*` still on v1,
and this document says why.

---

## What changed from revision 8, and why

- **Decision 0 corrected, and the proposal accepted.** Revision 8 used
  `forward` as a location precedent and copied the step's design from the
  caller that hardcoded its policies. The challenge was right on both
  counts: `forward` already had the design — outcomes as data, gap policy
  as a parameter, advance only after the effect — and a second step beside
  it would have been the duplication this plan refuses elsewhere. The
  parameter-light test was applied and passed: source, cursor, effect, no
  policy. The core is `dao.stream.v2.observe/step`; `forward` is its first
  caller, refactored with no assertion changed; the VM its second, likewise.
- **E4 sharpened.** An out-of-set read answer is classified as a
  `transport-error` defect with the raw answer retained, as `forward`'s
  `malformed-result` already does, instead of thrown; the VM's throw is its
  own policy above the seam and its test still sees the original outcome.
- **E5's provenance widened.** Three callers, one place: `forward` had the
  ordering since it was written, the VM had it from publish ordering, and
  DaoJing inherits it.
- **P0 rewritten** as one phase landing the core with both shipped callers
  refactored, because each caller's unchanged suite is the evidence the
  other needs.
- Everything else — the invariants and their markings, Decisions 1 (over
  the renamed core), 2 and 3, P1–P3, the boundary — stands.
