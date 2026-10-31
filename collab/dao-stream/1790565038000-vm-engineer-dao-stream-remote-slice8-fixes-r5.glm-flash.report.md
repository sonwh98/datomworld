# Report: dao.stream.remote slice 8 — fix round r5 (verbatim request envelope)

Role: yin.vm engineer (glm-flash). Implements the single unmet point of gate
confirm r9: the retained FFI request envelope must travel lift→lower VERBATIM.

## Changes

Only `src/cljc/yin/vm/ucf/remote.cljc` and `test/yin/vm/ucf/remote_test.cljc`
were touched. `dao.stream.apply` and everything else untouched.

### src/cljc/yin/vm/ucf/remote.cljc

1. **Lift carries the envelope verbatim** — `lift-retained-request`
   (:328-366):
   - The pending gains `:yin.k/request-envelope envelope` (:355), where
     `envelope` is `(:datom entry)` — the retained envelope already in hand.
   - `:yin.k/request-op` (:356-357) and `:yin.k/request-args` (:358) are kept,
     now explicitly derived views (the op remains the source of the lowered
     entry's `:op`, asserted by existing tests).
   - Docstring (:328-348) rewritten to state the constraint: the envelope
     rides VERBATIM, op/args are derived views, the request protocol is open
     to keys beyond op and args (`dao.stream.apply`'s `request?`), so the
     lower must never rebuild it from the views.

2. **Lower uses the carried envelope; refuses a malformed pend** —
   `:ffi-request` branch of `lower-one` (:635-657):
   - Binds `envelope (:yin.k/request-envelope pending)` (:640).
   - The lowered entry's `:datom` is `envelope` itself (:645) — the previous
     `(apply2/request ...)` reconstruction is gone; `lower-one` no longer
     constructs envelopes at all.
   - A `:ffi-request` pend missing `:yin.k/request-envelope` refuses as
     `:yin.k/unsatisfied` naming the request identity
     (`(unsatisfied req-id)`, :656) — no silent reconstruction. Since lift
     always sets the key, a missing key is a malformed pend.
   - `lower-one`'s docstring states the constraint (:593-600): the `:datom`
     is the carried envelope itself, never a rebuild, and an envelope-less
     pend refuses naming the request identity.

Checked: no other code path constructs or consumes `:ffi-request` UCF pends
(`src/cljc/yin/vm/ucf.cljc`'s `:ffi-request` is the unrelated safepoint
parking-mnemonic list; `test/yin/vm/debruijn_register_effects_test.cljc` is
the engine seam, not the UCF facade). The `dao.stream.apply` require alias
remains used (`request-op`/`request-args` views in the lift).

### test/yin/vm/ucf/remote_test.cljc

1. **Real-engine migration test** `a-retained-ffi-request-migrates-and-the-
   retry-resumes`:
   - The parked engine entry's retained envelope is dressed with a producer
     key an op/args constructor would not have: `entry (assoc-in entry [:datom
     :producer/nonce] "n-1")` (:526), with a comment citing the open request
     protocol.
   - Lift pin (:575-577): `(= (:datom entry) (:yin.k/request-envelope
     pending))` — the envelope rides verbatim, extra key included, beside the
     op/args views.
   - The existing retry pin (:614-617) now reads "the retained request
     VERBATIM -- the producer's extra key included": the engine's own sweep
     appends the carried envelope through the real request reflection, so the
     full envelope including `:producer/nonce` survives the real transport.

2. **Stub round-trip test** `ffi-retained-round-trips`:
   - The constructor-made envelope carries the extra key: `(assoc
     (apply2/request :call-7 :op/add [1 2]) :producer/nonce "n-1")`
     (:999-1000).
   - Lift pins (:1014-1018): `:yin.k/request-envelope` `=` the retained
     envelope, plus an explicit `:producer/nonce` `"n-1"` pin.
   - Round-trip pin (:1043-1045): the lowered entry's `:datom` is `=` to the
     original envelope — additional keys intact, not merely op/args equal
     (wording updated from "rebuilt verbatim", which is no longer what
     happens).

3. **Refusal pin** — new `retained-lower-refuses-a-pend-without-its-envelope`
   (:1270-1288): a lifted `:ffi-request` pend with `:yin.k/request-envelope`
   dissoc'd lowers to `:yin.k/unsatisfied` naming the request identity
   (`:dao.stream/identity` = the request endpoint's identity). No silent
   rebuild from the op/args views.

4. `retained-entry` (:1203-1209) — the constructor-made envelope the gate
   cited — also carries `:producer/nonce "n-1"`, so every pin downstream of
   it sees a key an op/args rebuild would drop.

All existing assertions kept; the existing `:request-op`/`:request-args`
assertions (:1012-1013) still pass — the views remain.

## Tests

JVM lane via mise, from the repo root:

```
mise exec -- clojure -M:test -n yin.vm.ucf.remote-test
  Ran 22 tests containing 212 assertions.
  0 failures, 0 errors.

mise exec -- clojure -M:test -n yin.vm.ucf-test
  Ran 23 tests containing 123 assertions.
  0 failures, 0 errors.
```

(22 = the previous 21 + the new refusal test.) `cljstyle check` on both files
passes clean. Note: the first compile caught a stray paren from my edit
(remote_test.cljc:1019, `Unable to resolve symbol: pending`) — fixed before
the green runs above; the green output is from the final state of both files.

## No other file modified

I touched only the two allowed files. `git status --short` (final state;
nothing staged, nothing committed, per the round protocol):

```
 M docs/orchestrator-log.md
 M src/cljc/yin/vm/semantic.cljc
 M test/yin/vm/semantic_test.cljc
?? collab/1790565038000-vm-engineer-dao-stream-remote-slice8-fixes-r5.prompt.md
?? src/cljc/yin/vm/ucf/remote.cljc
?? test/yin/vm/ucf/remote_test.cljc
```

The three ` M` entries (`docs/orchestrator-log.md`,
`src/cljc/yin/vm/semantic.cljc`, `test/yin/vm/semantic_test.cljc`) were
already modified before this round started — they are not mine and I did not
touch them. The two slice-8 files are new/untracked on this branch; my
changes live entirely in them.
