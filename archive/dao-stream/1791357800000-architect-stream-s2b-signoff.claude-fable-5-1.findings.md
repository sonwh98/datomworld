Completed-GMT: 2026-10-07 07:34:14 GMT
Completed-Local: 2026-10-07 14:34:14 Asia/Ho_Chi_Minh
Coding-Agent: claude (claude-fable-5-1)
Session-ID: 37b589c2-a09f-4b91-899a-bbcd2e188e06

# Lead System Architect: Slice S2b sign-off

**Architectural Sign-Off: GRANTED.** The uncommitted S2b diff on `stream-crossmachine-s2` @ `e9645a2d` (four files: `docs/design/dao.stream.remote.md`, `src/cljc/dao/stream/remote.cljc`, `src/cljc/dao/stream/ws_project.cljc`, `test/dao/stream/remote_test.cljc`) is architecturally approved for staging and committing, subject to the landing gate in §5. No architectural change is required. Two deviations from my S2 specification are accepted and recorded in §3.

Read-only pass. I read the full working-tree diff, the implementer report, the reconcile report, the gpt-6.1-sol round-1 findings and the qwen3.8-max round-2 findings, and re-verified every claim below at its line in the diff. No code, test or design file was modified; no other worktree was touched.

## 1. Verification runs (this pass, my own)

```
clojure -M:test -n dao.stream.remote-test -n dao.stream.ws-project-test
Ran 71 tests containing 565 assertions.
0 failures, 0 errors.

clj -M:kondo --lint src/cljc/dao/stream/remote.cljc src/cljc/dao/stream/ws_project.cljc test/dao/stream/remote_test.cljc
errors: 0, warnings: 1   (remote_test.cljc:661 unused binding peer2, pre-existing)
```

The round-2 reviewer could not run the suite in its session; this run closes that caveat. 71/565 matches the implementer's post-reconcile count; the delta from round 1 (69/537) is exactly the two added tests.

## 2. Compliance with the S2 specification (§1.3, §2) and `dao.stream.remote.md`

| spec item | implementation | status |
|---|---|---|
| three keys, nil default, composition error at `links` for a non-nil non-positive-integer | `links` policy `select-keys` plus a `pos-int-or-nil?` check throwing `ex-info "invalid DaoStream remote link policy" {:policy policy}`; `resend-after`/`budget` stay lenient | verified |
| `drain-budget`: ok and gap count, blocked/end do not; cursor kept at the stop; the next operation continues | `drain!` loop counts down on `ok`; a gap already ends the drain so it counts trivially; `:cursor` is written per read, so the stop point is the continuation | verified |
| `max-outstanding` refused locally as writer `full`: cursor/resolve retry-read, next blocked, append! `full` | one private `send!` used by `send-request!`, `send-named!` and `refl-append`; the outcome mapping falls out of the existing full handling, no new branches | verified; `max-outstanding-refuses-a-send-as-full` covers all four |
| `max-filed` bounds filed plus installed together; eviction safe because everything filed is idempotent-recomputable; append answers never filed | `file!` and `evict-filed!`; `:filed-cursors` entries now carry `:age`; append answers still go through `emit!` only | verified |
| `more` clamp to the link's own budget; none without a budget | `install-more!` takes `(dec k)` and none when no budget | verified, see §3 (a) |
| `retry-pending!` and the resend scan bounded by `max-outstanding` | `:pending` can only be entered with `room?`; invariant outstanding + pending <= cap holds on every path (`send!` for new entries, the kept-probe branch of `send-request!`, and a kept probe moving to outstanding leaves the sum unchanged) | verified |
| `dial` passes the keys to `remote/links` | `ws_project.cljc` policy `select-keys` and docstring | verified |
| design doc amended: §2.4 link-state list and Drain paragraph, §3.0 link-bounds bullet | all three present and consistent with the code, including the arrival-order eviction and the probe-on-reflection rule | verified |

## 3. Deviations from my specification, accepted

(a) **`more` clamp is k minus one, not k.** The answer itself is the first of the k outcomes, as the mirror's chase already counts it (`chase` runs at most k minus one further times). My §1.3 prose was off by one against my own §3 test row (budget 2 installs 1). The implementation follows the test and the mirror; the prose was wrong.

(b) **Eviction is by arrival order, not lowest id.** Round 1 showed lowest-id eviction starving a re-asked answer, which is minted with a lower id than entries filed after it: the same starvation as the prefetch defect by a second route. Arrival order is still "the oldest" in the sense that matters (when the link retained it), and `file!` files newest-then-evicts, so an answer never evicts itself. Accepted; the reconcile report and §2.4 state it explicitly, which is what round 1 asked.

(c) **A probe without room waits on its reflection (`:probe`), re-offered by `admit-probe!` after every operation's drain.** This is new reflection-held state my specification did not name. It is bounded (one per reflection), owned by the local caller, never on the wire, cleared by `close!` and by any accepted or kept send, and guarded against `closed?` and `channel-gone?`. The alternative, dropping the probe, leaves a reflection unconfirmed forever; the alternative round 1 found, keeping it unconditionally, breaks the bound and deadlocks. This is the right shape. `descriptor` now may send a probe; it could already, since its drain ran `retry-pending!`, so the "local" wording of 2.4 is no less true than before.

(d) **Stamped budget capped at `max-filed`, and prefetch fills only free capacity.** Both are clamps of peer work to local allowance (rule 4 of the spec) and make round 1's perpetual-blocked reproduction structurally impossible: with max-filed 1 and budget 2 the wire budget is 1, the mirror chases nothing, and `a-prefetch-never-evicts-its-own-answer` reads `:a :b :c` with at most one retained entry throughout. Accepted.

## 4. Boundary and invariants

- `dao.stream` remains the sole boundary across machines: nothing in the diff names a transport, a socket, a clock or a scheduler; every new bound is link policy data the composition supplies, enforced at the existing handle-operation seam. No new core operation, no registry, no public `closed?`, no callback.
- No path invents a source outcome: a locally refused send answers the existing writer-`full` mappings; an evicted entry is re-asked; no `gap` or `end` is fabricated (D2).
- `append!` never answers the source's `full` (2.4): the local `full` is the channel writer's shape with a known effect (nothing crossed), as the spec's §2 table requires.
- Cross-host: no `for` over long seqs, no multi-key `assoc` on nil, no `:clj`-first reader conditional; `(.-state r)` on the reflection deftype and the `#?(:clj Exception :cljs :default :cljd Object)` catch both have committed precedent in `remote_test.cljc` / `observer_test.cljc`. The Node and Dart lanes still have to say so (§5).

## 5. Landing gate (conditions of commit, not of this sign-off)

As for S1 and S2a: `bb test:clj`, the Node and Dart lanes (`bb test`, one lane set at a time, `npm ci` first in a fresh worktree) and kondo on the whole tree remain the pre-commit gate (`docs/agents/build-n-test.md`). Commit message per format.md, e.g. `feat(dao.stream.remote): drain budget, outstanding and filed bounds, more clamp on the link (cross-machine stream slice S2b)`. Never stage `collab/`.

Non-blocking follow-ups, for S2c's brief rather than a new S2b round: a drain-budget test over malformed values and a gap; a `ws_project` test that `dial` propagates the three keys; the round-2 cosmetic notes (`:pending` surviving `channel-loss!` is inert and cleared by `close!`; the "source's own blocked" test message). S2c should also note that `:deadline` stamping belongs in `send!`'s accepting callers and in the kept-probe branch, and that a probe waiting on its reflection has no deadline until admitted, which is consistent with the spec's "first accepted-or-kept send".

Verdict: ACCEPT
Sign-off: GRANTED
