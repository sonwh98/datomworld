# S2b reconcile report (claude-opus-5-5)

Worktree `/Users/sto/workspace/datomworld-stream-s2`, branch `stream-crossmachine-s2` @ `e9645a2d`, uncommitted. This fixes both P1 findings in `collab/1791357300000-stream-s2b-review.gpt-6.1-sol.findings.md`.

## Finding 1: refused attach probes exceed max-outstanding and deadlock (fixed)

`src/cljc/dao/stream/remote.cljc`:

- **New `room?`.** It is true when there is no cap, or when `count(:outstanding) + count(:pending without this id) < max-outstanding`. Both sending (`send!`) and keeping a refused probe in `:pending` now require it.
- **Invariant.** `outstanding + pending <= max-outstanding` always holds:
  - every new entry needs room;
  - a kept probe moving to outstanding leaves the sum unchanged.
- **No deadlock.** Given the invariant, a kept probe's retry (which excludes itself) always sees a count of at most cap − 1, so it always has room. Only the writer itself can refuse it. This is exactly the deadlock the review reproduced: with the old unconditional keep, each pending probe counted the other.
- **Recovery path for a probe with no room.** `send-request!` stores the probe on its own reflection (`:probe`), not on the link, so the link's state stays within the cap. A new `admit-probe!` runs right after the drain in every reflection operation (`descriptor`, `cursor`, `next`, `append!`). It offers the probe again, and the probe is then sent, kept, or left waiting by the same rules.
  - At most one waiting probe exists per reflection handle, so this state is held by the local caller.
  - `close!` clears the waiting probe.
  - A sent or kept probe clears it.
- Trade-off: a reflection that is never operated on never gets its waiting probe admitted, so it never confirms. This is acceptable because confirmation is only observable through that reflection's own operations or the event writer.

## Finding 2: prefetch eviction blocks an ordinary next forever (fixed)

`src/cljc/dao/stream/remote.cljc`:

- **The prefetch only fills free retained capacity.** `install-more!` installs `min(stamped-budget − 1, max-filed − filed − installed)` outcomes, so a prefetch never evicts anything, including its own answer.
- **The stamped budget is capped.** A new `stamped-budget` gives `min(budget, max-filed)` when both are set. `wire-request` stamps that value, and the clamp uses it, so the peer isn't asked to chase outcomes the link would drop.
- **Explicit change to the architect's eviction order.** Retained entries are now aged by *arrival* through a link counter (`:filed-seq` / `next-age!`), not by answer id.
  - New `file!` files the answer as the newest entry, then evicts the oldest down to the bound. So filing an answer never evicts that answer.
  - The spec's "lowest id" order could evict a freshly filed answer whenever an entry with a higher id was already retained. A re-asked answer gets a lower id than entries filed after it, so this is the same starvation pattern the review found, by a second route.
  - Arrival order still evicts "the oldest" in the spec's sense, measured by when the link retained the entry.
- **Updated test row.** `more-is-clamped-to-the-links-own-budget`, case budget 4 with max-filed 2, now expects 1 filed answer and 1 installed outcome. The old 0 and 2 was the defect.

## Docs

`docs/design/dao.stream.remote.md` §2.4 bounds bullets:
- `max-outstanding`: the room rule for kept probes, the invariant, and the probe waiting on its reflection and being offered again.
- `max-filed`: arrival-order eviction, filing never evicting the answer just filed, prefetch filling only free capacity, and the stamped budget being at most `max-filed`.

## New tests (`test/dao/stream/remote_test.cljc`)

- **`attach-probes-share-a-capped-link`**, with three reflections sharing one capped link.
  - Accepting writer, cap 1:
    - After the attaches, exactly 1 request is held and 1 probe has crossed.
    - Answering the first probe lets b's waiting probe in, while c's waits behind it.
    - Then c is admitted, all three confirm, and 0 are held.
    - This is the review's three-attach scenario, and it recovers after the first accepted answer.
  - Full writer, cap 2 (`full-then-forward-writer` refuses 3 appends):
    - Two probes are kept, at the cap, and the third waits on its reflection.
    - Subsequent drains send both kept probes, so neither blocks the other.
    - c is admitted once room frees, all three confirm, and 0 are held.
    - Held stays at or below the cap throughout.
- **`a-prefetch-never-evicts-its-own-answer`**: one reader with max-filed 1 and budget 2.
  - The stamped budget on the wire is 1.
  - `next 0`, then `next 1`, then `next 2` each go blocked, then serve, then return `:a`, `:b`, `:c`.
  - Retained entries stay at 1 or fewer throughout, and the end reads the source's own `blocked`.
  - This is the review's reproduction (`[:a :b :c]` with max-filed 1 and budget 2), which used to stay blocked forever.

## Verification

- `clojure -M:test -n dao.stream.remote-test -n dao.stream.ws-project-test`: Ran 71 tests containing 565 assertions, 0 failures, 0 errors.
- `mise exec -- cljstyle check` on the 4 changed source and test files: clean.
- `clj -M:kondo --lint` on the same 4 files: errors 0, 1 warning (`remote_test.cljc:661:13 unused binding peer2`). That warning was already there.
- Not run: `bb test:clj` and the full 3-host `bb test` (Node and Dart).
- Not addressed (the review lists it as useful but not blocking): drain-budget coverage for malformed values and gap continuation, and a test that dial propagates the new link options.
