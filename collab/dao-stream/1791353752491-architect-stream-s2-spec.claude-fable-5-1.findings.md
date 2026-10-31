Completed-GMT: 2026-10-07 06:23:42 GMT
Completed-Local: 2026-10-07 13:23:42 Asia/Ho_Chi_Minh
Coding-Agent: claude (claude-fable-5-1)
Session-ID: 37b589c2-a09f-4b91-899a-bbcd2e188e06

# Lead System Architect: Slice S2 specification and implementation recipe

Read-only pass on worktree `datomworld-stream-s2` @ `65f635d0`. No code, design or test file was modified. Only this file was written. No other worktree was touched.

Read firsthand: `src/cljc/dao/stream/remote.cljc` (mirror-step, chase, drain!, link state), `src/cljc/dao/stream/ws.cljc` (deliver! pending queue, WsHandle append!, accept-connection!, endpoint-step), `src/cljc/dao/stream/ws_project.cljc` (adopt!, accept-step!, dial, dial-step!), `src/clj/dao/stream/ws/jvm.clj` (send chain, fragment reassembly), the Node/browser/Dart seams, `docs/design/dao.stream.remote.md` §2.3–2.5 and §3.0–3.1, `docs/design/dao.stream.ws.md` Serving / Deposit Admission / Deferred, the mob consensus (`collab/1791304310868-...gpt-6-astra.findings.md` D3/D5), the S0/S1 sign-off (`collab/1791308475375-...glm-5.3.findings.md` §2–3), and the fixtures of `remote_test.cljc`, `ws_project_test.cljc`, `ws_test.cljc`.

Scope is exactly D3's remote/ws bullets and the S0/S1 sign-off's S2 table rows. Everything here keeps D1/D2/S0: no new registry, no new core stream operation, no public `closed?` on handles, no ambient clock or scheduler, dao.stream remains the sole boundary. Every new bound defaults to nil (today's behaviour) at the dao.stream layer, so S2 is zero-breakage by construction; S3a's production composition is where non-nil values are set (recorded as an S3a input, as the S1 sign-off did for the S1 keys).

## 0. Design stance in five rules

1. **Policy is composition data; enforcement follows ownership.** Answering-side bounds live in `remote/mirror-step` and are passed by ws-project from acceptor/dial config. Asking-side bounds live on the link as `links` policy keys. Byte/frame bounds live in `dao.stream.ws`, with host seams supplying what only a host can know.
2. **Exhaustion preserves continuation state.** A budgeted loop that stops returns exactly the cursor it would return on `blocked`. It never answers `end`, never skips, never invents progress. The next tick resumes.
3. **Counting is uniform.** Every `ok` read counts one, including malformed and dropped values; every `gap` counts one; `blocked` and `end` count nothing. This is the S1 `step!` rule the sign-off verified (`ws_project.cljc:111-137`).
4. **Clamp peer-requested work to local allowance.** A peer's `:dao.stream.remote/budget k` on `next` is clamped by the mirror; a peer's `:dao.stream.remote/more` vector is clamped by the link to what it asked for.
5. **Deadlines are driver-supplied `now`, fixed at first send, expiring through existing loss semantics.** The link still reads no clock (2.5). A driver step hands it `now`; expiry is channel loss via the existing `channel-loss!` path (`remote.cljc:542-555`); the connection-owning composition (the dial) tears the socket down. Nothing new is observable by yin except ordinary `channel-gone`.

## 1. Signatures and configuration keys

### 1.1 `dao.stream.remote`, answering side (S2a)

```clojure
;; new 6-arity; the 4- and 5-arity forms delegate with bounds nil
(mirror-step table names chan-reader cursor chan-writer bounds) -> cursor'

bounds ::= nil | {:dao.stream.remote/mirror-budget <pos-int or nil>
                  :dao.stream.remote/chase-budget  <pos-int or nil>}
```

- Validation at entry, same idiom as `ws-project/step!` (`ws_project.cljc:107-109`): a non-nil map value that is not a positive integer throws `ex-info "invalid DaoStream remote mirror bounds" {:bounds bounds}`. A non-map non-nil `bounds` is the same error.
- `mirror-budget`: the request loop at `remote.cljc:269-282` becomes `(loop [cursor cursor remaining ...])`; decrement on `:dao.stream/ok` (well-formed or not) and on `:dao.stream/gap`; when `remaining` reaches zero return the current cursor. The `:blocked`/`:end` returns are unchanged. Nil means unbounded, exactly today.
- `chase-budget` (the local maximum allowance): `answer!` (`remote.cljc:206-245`) computes the effective k as `(if chase-max (min k chase-max) k)`, only when the request carries a valid budget (`valid-budget?` unchanged); a request without a budget still never chases. Work per tick is then at most `mirror-budget × chase-budget` handle operations, which is the number to size a session's tick by.
- **Writer back pressure.** Needed once S2d lets a ws writer answer `full`; today only the `:connecting` phase gate does (`ws.cljc:207`). `write-answer!` (`remote.cljc:154-168`) returns the writer's outcome. In `mirror-step`, a `:dao.stream/full` on the answer of an idempotent op (`descriptor`, `cursor`, `next`, named descriptor) stops the step and returns the cursor *preceding* that request, so the same request is re-read and re-answered next tick (the source op recomputes an equally true answer, per 2.5). A `full` on an `append!` answer does not rewind: the source append already ran and re-applying it would duplicate; the answer is dropped as today and the asker's append stays unknown (2.5). `invalid-value` keeps today's oversize replacement; any other refusal leaves the request unanswered as today.

### 1.2 `dao.stream.ws-project` wiring (S2a)

```clojure
(make-acceptor {... :step-budget n :mirror-budget n :chase-budget n})   ; each pos-int or nil
(dial {... :step-budget n :mirror-budget n :chase-budget n
           :dao.stream.remote/drain-budget n
           :dao.stream.remote/give-up-after ms
           :dao.stream.remote/max-outstanding n
           :dao.stream.remote/max-filed n})
(dial-step! dial)        ; unchanged arity: clock-less, no expiry
(dial-step! dial now)    ; new: budgeted projection, link step at `now`, budgeted mirror
```

- `make-acceptor` validates the two new keys in its existing `when-not` block (`ws_project.cljc:198-212`) and stores them; `accept-step!` (`ws_project.cljc:369-373`) calls `(remote/mirror-step table names ring cursor handle {:dao.stream.remote/mirror-budget .. :dao.stream.remote/chase-budget ..})`.
- `dial` validates `:step-budget`/`:mirror-budget`/`:chase-budget` the same way; its `policy` `select-keys` (`ws_project.cljc:449-451`) grows by the four link keys above so they reach `remote/links`. `dial-step!` passes `(step! project step-budget)` and the bounds map to the mirror. Consumers (`yin/repl/connect.cljc:438`, `yin/vm/linker/head/ws.cljc:336`) keep calling the 1-arity and change nothing until S3a.
- `session-end` and `sessions` are unchanged.

### 1.3 `dao.stream.remote`, asking side: link policy (S2b, S2c)

New `links`/`attacher` opts, all nil by default, validated at `links` construction (`ex-info "invalid DaoStream remote link policy" {:policy policy}` for a non-nil value that is not a positive integer; `resend-after` and `budget` keep their lenient reading for zero breakage):

| key | slice | meaning |
|---|---|---|
| `:dao.stream.remote/drain-budget` | S2b | max channel reads per `drain!` (ok and gap both count); exhaustion leaves `:cursor` where it stopped, the next operation's drain continues |
| `:dao.stream.remote/max-outstanding` | S2b | max entries in `:outstanding` + `:pending` per link; a send that would exceed it is refused locally *as though the writer answered `full`* |
| `:dao.stream.remote/max-filed` | S2b | max entries in `:filed` + `:filed-cursors` per link; exceeding evicts the lowest id / oldest installed entry |
| `:dao.stream.remote/give-up-after` | S2c | ms after a request's first accepted-or-kept send at which the channel is declared lost if the request is still unanswered |

- **more clamp (S2b):** `install-more!` (`remote.cljc:457-471`) installs at most `(:budget @link)` outcomes; with no stamped budget it installs none, because the link asked for none. A peer cannot grow `:filed-cursors` beyond what the link requested.
- **Retained-state eviction is safe** because everything filed is idempotent-recomputable: an evicted cursor answer is minted again on the next ask; an evicted next answer or installed more is re-asked (`send-or-count!` sends again since nothing is outstanding for it). `append!` answers are never filed (they are emitted), so nothing with unknown effect is ever evicted. Today a filed cursor answer is kept forever (`filed-cursor`, `remote.cljc:639-645`); `max-filed` is the first bound on it.
- `retry-pending!` and the resend scan at the top of `drain!` (`remote.cljc:578-583`) are bounded by `max-outstanding` once it is set; no separate budget.

New `links` entry (S2c):

```clojure
(links opts) -> {:attach f :resolve g :step h}
(h channel-descriptor now) -> nil                                   ; channel not reached
                           | {:dao.stream.remote/channel-gone? bool
                              :dao.stream.remote/expired <id or nil>}
```

The link step, in order: record `now` on the link (`:now`); `drain!` (budgeted); then if any outstanding or pending entry has `:deadline <= now`, emit `{:dao.stream.remote/event :dao.stream.remote/channel-expired :dao.stream.remote/id id :dao.stream.remote/op op}` for the least such id and run `channel-loss!`, which already abandons every outstanding id, emits `append-unknown` per abandoned append, and sets `:channel-gone?`. Deadline bookkeeping:

- `:deadline` is `(+ (:now @link) give-up-after)`, stamped in `send-request!` (`remote.cljc:354-374`), `send-named!` and `refl-append` when the writer answers `ok` (registered) **or** `full` for a kept probe (pending): the first moment the request exists on the link. A later `count-ask!` resend never moves it; the mob rejected the "retry resets :asks" design for exactly that reason. With no `:now` recorded yet (the driver never stepped with now) or `give-up-after` nil, no deadline is stamped and nothing ever expires: a clock-less composition is unchanged.
- Including kept probes deviates from the mob's "first *accepted* send" deliberately: a writer that only ever answers `full` is the ws Deferred establishment-stall gap (`dao.stream.ws.md` Deferred, first bullet), and a deadline from the first attempt is the only place that stall is bounded. Recorded here for the Architect record; reviewers may hold the letter instead at the cost of leaving that stall unbounded.
- `give-up-after` is a liveness statement about the channel, not the request: on an ordered reliable channel an unanswered request past the deadline means the peer is not serving. On UDP (3.2) compose `resend-after` small and `give-up-after` generous or nil; the two keys are the channel's composition data as 3.0 already frames them. D2 keeps UDP out of this milestone.

### 1.4 `dao.stream.ws` byte and frame bounds (S2d)

`make-endpoint` and `make-attacher` config, all nil by default:

| key | enforced where | on breach |
|---|---|---|
| `:ws/max-frame-bytes` | `deliver!`, before decode, on the raw payload size | protocol failure: close 1009 `"dao.stream/frame-too-large"`, `:ws/error` reason `:ws/frame-too-large`, phase closed |
| `:ws/max-pending-frames` | the `:pending`/`:replaying` branch of `deliver!`'s swap (`ws.cljc:314-321`) | close 1013 `"dao.stream/pending-overflow"`, `:ws/error` reason `:ws/pending-overflow`, phase closed; the endpoint's pending-release doseq (`ws.cljc:609-612`) returns the slot on the next `endpoint-step` |
| `:ws/max-pending-bytes` | same swap, summed raw payload sizes of the queued frames | same as above |
| `:ws/outbound-high-water` | `WsHandle.append!` after encode, against the seam's `:queued-bytes` | `append!` answers `:dao.stream/full` (transient; the mirror rewinds per 1.1) |
| `:ws/max-outbound-bytes` | same place | teardown: close 1008 `"dao.stream/outbound-overflow"`, `:ws/error` reason `:ws/outbound-overflow`, phase closed, `append!` answers `closed` afterwards |

- Payload size is a private `payload-size` in `ws.cljc`: `count` on strings; `alength` / `.-length` on host bytes under a reader conditional with `:cljd` first (the known trap: a `:clj` branch is also read by the cljd host-eval pass). Queued pending frames stay stored as today (decoded values) but are counted by raw size, so the bound is on the bytes the peer actually sent.
- The pending bounds are decided inside the existing single `swap!` in `deliver!` as a fourth `action`, `:overflow`, keeping the atomic phase/queue decision the sign-off relied on.
- **Outbound accounting is a seam key.** The `{:send! :close!}` seam may carry `:queued-bytes`, a `(fn [] -> number)`. Where a host has it the two outbound bounds are exact; where it is absent `WsHandle` falls back to a cumulative encoded-byte quota since open against `:ws/max-outbound-bytes` with the same teardown, and that host stays gated for the loopback lift (D5: each host remains gated until its bounds and teardown are demonstrated). Per host:
  - JVM client (`client-socket`, `jvm.clj:125-203`): add a `:queued` counter to the connection atom, incremented under the submission lock by payload size in `chain!`, decremented in the `whenComplete` observer; `:queued-bytes` reads it. JVM server (`server-socket` over http-kit, `jvm.clj:289-301`): http-kit exposes no high-water; fallback only, gated.
  - Node (`ws` package) and browser: `:queued-bytes` is `(.-bufferedAmount socket)`. One line each in `node.cljs:182` and `browser.cljs:73`.
  - Dart `io.WebSocket` (`dart.cljd` `raw-socket`): no signal; fallback only, gated.
- Host fragment reassembly (`jvm.clj:215-253`, `StringBuilder` / `ByteArrayOutputStream`) is bounded by the same `:ws/max-frame-bytes` passed through `connect!` / `listen!` options: abort the socket with 1009 when the assembled size exceeds it. Node's `ws` takes `maxPayload` at construction; Dart has no option (gated).
- Repeated admissions through accumulated closing sockets (D3) are an OS-level host gate; the stream layer's contribution is that `release-slot!` drops the handle and `:connections` entry at release, so nothing is retained here.

### 1.5 Adoption-path isolation (S2d, `ws-project/adopt!`)

Wrap the `make-media` call and the ack `append!` (`ws_project.cljc:315-322`) in the three-host try/catch (`#?(:clj Throwable :cljs :default :cljd Object)`). On a throw: close the offered handle (`stream/close!`, idempotent), register no session, return nil. The offer cursor was already advanced by `accept-step!`, so the tick proceeds to the next slot and to the session loop; healthy sessions answer in the same tick. A `make-media` that throws after allocating a ring is the composition's own leak to avoid (say so in the docstring); the acceptor retains nothing.

## 2. Failure semantics and outcome mappings

| situation | where | answer / effect |
|---|---|---|
| mirror budget exhausted | `mirror-step` | returns the cursor at the stop point; no answer dropped; the next tick resumes |
| peer asks `next` with budget k > chase-budget | `answer!` | chase runs min(k, chase-budget) times; the answer carries that many `more`; the peer sees fewer prefetched outcomes, nothing else |
| channel writer answers `full` on an idempotent answer | `mirror-step` | step stops, cursor rewinds to before the request; re-answered next tick |
| channel writer answers `full` on an `append!` answer | `mirror-step` | answer dropped; the asker's append stays unknown (2.5), as today |
| drain budget exhausted | `drain!` | `:cursor` kept at the stop; this operation answers from what was filed so far (possibly `blocked` / retry); the next operation drains further |
| a send would exceed `max-outstanding` | `send-request!`, `send-named!`, `refl-append` | treated as writer `full`: cursor answers `transport-error` with `:dao.stream/retry? true`; next answers `blocked`; resolve answers `retry-read`; append! answers `{:dao.stream/outcome :dao.stream/full}` (nothing crossed, effect known) |
| `max-filed` exceeded | `absorb!`, `install-more!` | oldest entry evicted; the operation it answered re-asks; no outcome invented |
| peer sends more `more` than asked | `install-more!` | surplus dropped |
| request past its deadline | link step | `channel-expired` event, then `channel-loss!`: outstanding abandoned, `append-unknown` per append, later cursor/next/resolve answer `transport-error` reason `channel-gone` (not retryable), `append!` answers the writer's own outcome; `dial-step!` closes the ws handle, the projection sees `:ws/closed` and closes the ring |
| flood keeps the drain budget saturated while a deadline passes | link step | false channel loss; costs a redial, never invalidates anything (D3 accepts this) |
| inbound frame over `max-frame-bytes` | `deliver!` | protocol failure before decode; the peer sees close 1009 |
| pending queue over count or bytes | `deliver!` | teardown before acceptance; slot released by `endpoint-step`; the client resolves `:ws/transport-error`, as for a full slot pool |
| outbound queued at or above high-water | `WsHandle.append!` | `full` |
| outbound queued at or above max | `WsHandle.append!` | teardown; `closed` afterwards; the acceptor reaps the session on its next tick |
| `make-media` or ack append throws | `adopt!` | offered handle closed, no session, tick continues |

Invariants checked against: no path invents a source `gap` (D2); `append!` never answers the source's `full` (2.4); `descriptor` on a reflection still answers `ok` after expiry (a name outlives what it named); yin observes only the `channel-gone` / `transport-error` it already handles; no new head value (D3).

## 3. Test plan

Per-iteration verification is `clojure -M:test -n <ns>` then `bb test:clj`; one full `bb test` (JVM, Node, Dart; one lane set at a time) plus `clj -M:kondo` before each sub-slice commit (`docs/agents/build-n-test.md`). Fixtures to reuse: `remote_test.cljc` `ring`, `toy`, `served-peer`, `capped-writer`, `full-then-forward-writer`, `counting-writer`, `link-of`; `ws_project_test.cljc` `acceptor-over` (its `config` merge), `offer!`, `dialed`, `deposit!`, `payload`; `ws_test.cljc` `buffer`, `one-slot-endpoint`.

**S2a: `remote_test` and `ws_project_test`**
- `mirror-budget-bounds-requests-per-step`: 5 requests on the ring, budget 2: 2 answers, returned cursor precedes request 3; a second call answers 2 more; nil budget answers all (continuation proof).
- `malformed-values-and-gaps-count-against-the-mirror-budget`: interleave junk and an evicting-ring gap; the count follows rule 3.
- `an-invalid-mirror-bound-is-a-composition-error`: `{:dao.stream.remote/mirror-budget 0}`, `1.5`, `-1`, and `bounds 7` throw with `ex-data` equality; nothing answered.
- `chase-budget-clamps-a-peer-requested-budget`: request budget 10, chase-budget 3: `more` has at most 2 entries (the first outcome is the answer itself); chase-budget nil leaves `the-budget-chase` unchanged.
- `a-full-writer-rewinds-idempotent-answers-and-drops-append-answers`: `full-then-forward-writer`; the next request is answered on the second tick with the same id; an append request's answer is not re-sent.
- `ws_project`: `mirror-budget-and-chase-budget-reach-the-session-mirror` (acceptor config, two ticks); `dial-bounds-reach-projection-and-mirror` (dial config, 1-arity `dial-step!`); invalid rows join `invalid-bounds-are-a-composition-error`.

**S2b: `remote_test`**
- `drain-budget-bounds-reads-per-operation`: 6 answers queued, drain-budget 2; one `next` files 2; three calls file all; cursor kept.
- `max-outstanding-refuses-a-send-as-full`: with 2 outstanding, a third `next` on a fresh cursor answers `blocked` with nothing sent (`counting-writer`), and an `append!` answers `full`.
- `max-filed-evicts-the-oldest-and-the-reflection-re-asks`: file 3 with max 2; the evicted one's `next` sends again (the writer's count rises by one), the answer arrives, nothing is duplicated.
- `more-is-clamped-to-the-links-own-budget`: the peer answers 5 `more`, link budget 2: 1 installed; a link without a budget installs none.

**S2c: `remote_test` and `ws_project_test`**
- `a-request-past-its-deadline-is-channel-loss`: give-up-after 10; `:step` at now 0 then send; `:step` at 9 changes nothing; at 10: `channel-expired` event, `append-unknown` for an outstanding append, `channel-gone?` true, a later `next` answers `transport-error` `channel-gone`, `descriptor` still `ok`.
- `a-resend-does-not-move-the-deadline`: resend-after 1, asks across ticks; expiry stays at first send plus give-up-after.
- `an-answer-before-the-deadline-clears-it`: answered at 5, step at 20: no loss.
- `a-kept-probe-has-a-deadline`: writer always `full`; expires.
- `without-a-stepped-now-nothing-expires`: give-up-after set, `:step` never called; reflections keep retrying.
- `flood-cannot-defer-expiry`: drain-budget 2, 100 unrelated values queued, the deadline passes: loss (bounded snapshot, D3).
- `ws_project`: `dial-step-with-now-closes-the-handle-on-expiry`: `dialed` fixture, give-up-after, `(dial-step! dial now)` past the deadline: ws handle closed, projection closed, ring ended, resolve answers `channel-gone`; the 1-arity `dial-step!` never expires.

**S2d: `ws_test`, `ws_project_test`, `ws/jvm_test`**
- `a-frame-over-max-frame-bytes-is-a-protocol-failure-before-decode`: the decoder counts calls; close code 1009 captured by the seam.
- `pending-frames-over-count-tear-down-before-acceptance` and `pending-frames-over-bytes-tear-down-before-acceptance`: extend `a-frame-before-acknowledgement-is-queued-and-replays-in-order`; `:ws/error` reason on the control medium; `endpoint-step` releases the slot; a late ack is stale.
- `outbound-high-water-answers-full-and-max-tears-down`: a seam with a settable `:queued-bytes`; append answers `full` at high-water, `closed` after max with close 1008.
- `a-seam-without-queued-bytes-uses-the-cumulative-quota`: the fallback path.
- `jvm_test`: `client-socket-accounts-queued-bytes-across-the-send-chain`: pending futures completed by hand; the counter rises on submit and falls on completion.
- `ws_project`: `a-throwing-make-media-is-isolated` and `a-throwing-ack-append-is-isolated`: two offers, one throws; the other is adopted and answered in the same tick; the throwing offer's handle is closed and no session exists.

D5 acceptance evidence covered by the above: "continuous producer cannot defeat step budget" (S1 plus S2a), "request-but-never-read peer cannot grow memory without bound" (S2d outbound plus S2a rewind), "unrelated-traffic flood cannot defer expiry" (S2c), "malformed/oversize traffic" (S2a counting plus S2d frame bound). "Idle healthy board versus blackholed request" is S3a's, over the S2c primitive.

## 4. Sub-slicing and landing order

Each is one commit (`feat(stream): ...`), each gated by review sign-off, green lanes and kondo, each amending the design docs it touches, and each able to land alone.

| slice | code | design amendments | risk |
|---|---|---|---|
| **S2a** answering-side loop budgets | `remote.cljc` (`mirror-step` 6-arity, `answer!` clamp, `write-answer!` return plus rewind), `ws_project.cljc` (acceptor/dial keys, `accept-step!` and `dial-step!` pass-through) | `dao.stream.remote.md` §2.3 ("bounded by a composition budget" becomes the mirror-budget / chase-budget rule plus the `full` rewind rule); §3.0 step-event-budget bullet gains the two keys | low: nil defaults; all existing mirror tests unchanged |
| **S2b** link-side drain and retained-state bounds | `remote.cljc` (`drain!` budget, `max-outstanding` refusal, `max-filed` eviction, `install-more!` clamp, `links` validation) | §2.4 Drain paragraph and the link-state list; §3.0 gains a "link bounds" bullet | low to medium: the `more` clamp changes behaviour only for a link without a stamped budget, which today installs everything |
| **S2c** request liveness | `remote.cljc` (`:deadline` stamping, `:step` entry, `channel-expired` event), `ws_project.cljc` (`dial-step!` 2-arity, handle close on loss) | §2.4 new "Expiry" paragraph beside Channel loss; §2.5 amended ("The reflection reads no clock" stays true: the driver supplies `now`); §3.0 gains the give-up-after bullet; `dao.stream.ws.md` Deferred "Liveness" bullet rewritten to point here | medium: a new event kind and a new `links` entry; consumers untouched |
| **S2d** ws byte/frame bounds plus adoption isolation | `ws.cljc` (five keys, `payload-size`, `deliver!` overflow action, `append!` gate, seam `:queued-bytes`), `jvm.clj` (queued accounting, reassembly cap), `node.cljs` / `browser.cljs` (`bufferedAmount`), `ws_project.cljc` (`adopt!` try/catch) | `dao.stream.ws.md` Serving (pending bounds), Deposit Admission (outbound bound plus a host matrix: which hosts are exact, which fallback and gated), Deferred (drop "no outbound high-water" where now false); `dao.stream.remote.md` §3.1 "frame budget unbounded" becomes "frame budget `:ws/max-frame-bytes`" | medium: touches every host seam; Dart and the http-kit server stay gated and say so |

Recommended order S2a, S2b, S2c, S2d. S2a is the smallest and closes the sign-off's tracked mirror/dial items; S2d is last because the mirror's `full` rewind (S2a) should exist before any ws writer can answer `full`. S2c may run concurrently with S2b in a sibling worktree (disjoint functions in `remote.cljc`; rebase cost only).

Suggested production profile for S3a (implementation choices, not owner questions): mirror-budget 64, chase-budget 32, step-budget 64, drain-budget 256, max-outstanding 256, max-filed 1024, give-up-after 15000 ms, max-frame-bytes 4 MiB, max-pending-frames 64, max-pending-bytes 1 MiB, outbound-high-water 4 MiB, max-outbound-bytes 16 MiB.

## 5. Out of scope, recorded for later slices

- Correct `:answered` reporting in the yin follower (local `blocked` is not remote acknowledgement): S3a.
- Explicit stop and lifecycle-gap recovery: S3a.
- Aggregate shared-pool scheduling across sessions (one global per-tick budget rather than per-session): not required by D3's letter; revisit if S3a's profile shows per-session budgets insufficient.
- Accumulated closing sockets evading `:max-sessions` at the OS level: a host gate, not a stream-layer bound.
- Any UDP (3.2) application of these keys: D2 keeps UDP out of this milestone.

## 6. Verification of this pass

No runtime tests were run; this is a specification. `git status` after writing shows only untracked `collab/` files and `node_modules`; `git diff --stat` is empty.
