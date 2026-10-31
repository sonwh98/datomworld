Completed-GMT: 2026-09-28 14:55:05 GMT
Completed-Local: 2026-09-28 21:55:05 +07
Coding-Agent: claude
Session-ID: 7d111381-2e90-40d2-9519-0a15d8ccca19

# Report: Slice 3b, lease wiring for the FFI export binding

Role: Yin.VM Runtime Engineer. Model: claude-opus-5-5. Base: master at ada3f200. Nothing is staged or committed.

## Changed files

- `src/cljc/yin/vm/ffi/remote_serve.cljc`: lease wiring added in place. I did not create a separate `remote_serve/lease.cljc`, because the lease state has to share the binding's state cell with `retire!`.
- `test/yin/vm/ffi/remote_serve_test.cljc`:
  - The 3a tests now include the lease options. Their table-count and table-shape assertions look only at served identities, because the table now also holds renewal entries.
  - 9 new deftests were added, listed below.

No other file was touched. No authorization was needed. `dao.lease`, `dao.stream.*` and `ucf.remote` are unchanged.

## What was built

**Lease options.** `open!` validates these before it builds anything, publishes anything or mints any cursor:

- Required: `::lease-duration`, `::lease-tolerance`, `::lease-cadence`, `::lease-ticks`, `::lease-media`, `::lease-writer`, `::lease-renewal-capacity`.
- Optional: `::lease-max`, `::lease-drain-budget`.

Validation runs in two stages:

1. The binding's own shape checks run first.
2. `dao.lease/make-judge` then runs the deeper assembly checks: cadence, ticks, the media declarations, and resolver compatibility. If it refuses, the `:refused` key it names is mapped back to the matching option. The result is `::refused` with that option, `::malformed`, and `::lease-refusal <ex-data>`.

There are no policy defaults. The unit table `lease-units` is `{:ms 1 :s 1000}`, the value `dao.stream.remote.md` S6 fixes.

**Grant per served identity (S6 mechanism).** When `serve!` serves a new handle, it:

- mints the served identity;
- creates a fresh renewal ring buffer (capacity `::lease-renewal-capacity`) and enters it in the table under its own identity with surface `#{:writer}`;
- marks both table entries with `:dao.lease/lease L`;
- queues an unsolicited grant on the judge. The subject is the served identity and the holder is the renewal identity;
- wires the renewal medium to the judge (`lease/wire-facts`), with the renewal identity as its source.

Attribution is per-author media: the resolver returns the source, as S7 says. The judge's `:self` is the constant `rs/grantor`. Repeated `serve!` of a live handle gives no second grant.

**The judge runs in `step`.** Each `step` is one bounded mirror pass followed by one `judge-step` pass. The judge's "now" comes only from ticks the host deposits on `::lease-ticks`, so no clock is read. The mirror goes first so that a renewal it has just delivered counts as evidence in the same pass.

**Reclaim uses the same `retire!` transition.** The reclaim procedure calls `retire-in!` and then reports `true`:

- It is idempotent: once it returns, the subject is certainly out of the table.
- Order: the subject and its renewal entry leave the table, then the registry entry is released and the renewal medium closed. Only after that does `dao.lease` append `:lapsed`.
- A reclaim whose `:lapsed` append fails stays `:pending` and is retried by `dao.lease` itself.
- After a lease leaves the ledger, its renewal medium is unwired from the judge's `:facts`, so the judge state does not keep growing.

**Retirement not caused by the judge.** An explicit `retire!`, `close!`, or a refused `lift-frame` cleanup ends the lease with the grantor's `:policy` at the next pass: the policy is "subject no longer live". `step` after `close!` still runs the judge pass (and only that pass) and still answers exactly `{:dao.stream/outcome :dao.stream/closed}`. This way a closed binding's leases still lapse and are recorded.

**Detach and reattach.**
- `step` reports `::detached? true` when the channel reader answers end. Nothing is retired, and the judge keeps running.
- The new `reattach! binding end` rebinds the channel end. The table, identities, source cursors and leases are untouched. The mirror reads the new reader from its oldest value.
- The replaced writer is closed under the existing `::channel-exclusive?` ownership contract. A stale remote link therefore runs its own loss path, and its unresolved appends become append-unknown. Nothing is carried over to the new end.

**New public accessors.** `lease-of binding id` returns `{:dao.lease/lease L ::renewal {identity channel}}`. `judge binding` returns the judge's state as plain data.

## Acceptance tests (each maps to the prompt's list)

1. `live-renewal-keeps-the-export-served`: a `make-holder` holder on peer A renews through a real reflection of the renewal medium. It advances its bound only on the source's `ok` read from the link's event writer (the S6 rule). Over 59 lease ticks (about 5 durations) there is no `:lapsed`, and the export still answers under the same identity.
2. `expiry-reclaims-through-retire`:
   - At 12 ms (duration plus tolerance) the entry is still served; at 13 ms past the grant it is reclaimed with `:silence`.
   - A recording grantor writer shows the subject was already unpublished when `:lapsed` was appended.
   - The renewal entry is gone and its medium is unwired.
   - A fresh op answers not-found, and so does a late renewal.
   - Re-serving the handle mints a new identity and a new lease, and the old identity stays not-found.
3. `release-by-the-holder-reclaims`: the holder calls `lease/stop` and appends the release on its attributed medium. The same pass reclaims with `:release`, with the same ordering proof, then not-found and a new identity on re-serve. The holder sees the reclaim as not-found on its reflection.
4. `detach-without-expiry-keeps-entry-and-lease`:
   - The channel ends and `step` reports detached over 9 ticks; the entry, renewal entry, ledger lease and "no lapse" all hold.
   - `reattach!` works.
   - The source cursor minted before the detach reads the next value through a new reflection.
   - A renewal through the new end counts, and the export survives past the old bound.
5. `an-in-flight-append-at-reclaim-is-never-retried`, two cases:
   - **(a)** `:a2` is still unread on the channel when the lease is reclaimed. Its outcome is the terminal not-found. The gone reflection sends nothing more, and after re-serving, the target never receives `:a2`.
   - **(b)** `:b1` is unread when the lease is reclaimed and the channel is replaced. The peer gets `:dao.stream.remote/append-unknown`. The old identity answers not-found through the new end, and the target never sees `:b1`.
6. `open-refuses-each-missing-or-malformed-lease-option`: each of the 7 required lease options is refused when missing, and 18 malformed variants are refused, each under its own option. Nothing is written to the grantor's writer or the channel.
7. `reclaim-is-idempotent`:
   - A duplicate direct reclaim of one subject reports success again and retires nothing else.
   - A `:lapsed` refused with `full` leaves the lease pending, it is re-reclaimed harmlessly, and it is recorded exactly once after the writer recovers. A sibling that was explicitly `retire!`d lapses with `:policy`, also once.
   - A reclaim after `close!` is harmless, and the closed binding's lease still lapses with `:policy`, once.

Also added: `serving-grants-a-lease-to-a-served-renewal-medium`, which checks the grant's exact shape and that its seed reading is the deposited tick.

**Mutation proof.** Each mutation was applied temporarily, the namespace was run, and the source was restored. The restore was verified byte-identical.

| Mutation | Failures | Failing deftests |
|---|---|---|
| M1: reclaim reports success without retiring | 27 | expiry, release, in-flight, idempotent |
| M2: reclaim reports success only when something was live (not idempotent) | 8 | idempotent |
| M3: table removal deferred until after the judge records `:lapsed` (ordering) | 4 | expiry, release, idempotent |
| M4: `reattach!` leaves the replaced writer open | 2 | detach, in-flight (append-unknown) |
| M5: detach retires everything | 9 | detach |
| M6: re-serve reuses the retired identity | 15 | 7 deftests, including expiry and release |

## Verification (exact)

- `clj -M:kondo --lint src/cljc/yin/vm/ffi/remote_serve.cljc test/yin/vm/ffi/remote_serve_test.cljc`: errors 0, warnings 0.
- `cljstyle check <both files>`: **not run.** The permission gate blocked it ("This command requires approval").
- `clj -M:test -n yin.vm.ffi.remote-serve-test -n dao.lease-test -n dao.lease-composition-test -n dao.stream.remote-test -n yin.vm.ucf.remote-test`: 140 tests, 1222 assertions, 0 failures, 0 errors. `yin.vm.ffi.remote-serve-test` alone: 18 tests, 229 assertions, 0 failures.
- `bb test:cljs`: 2212 tests, 50193 assertions, 0 failures, 0 errors. "Testing yin.vm.ffi.remote-serve-test" appears in the output.
- `bb test:cljd`: not run, as instructed. The CLJC uses only `#?(:cljd Object :clj Exception :cljs :default)` catches, with `:cljd` first. It has no `#?(:clj …)`-only code and no cross-namespace `#'` access.

## Design choices where the design left room

- **Holder side is test-level.** S6 and `dao.lease.md` place the holder on the remote peer, which is the side that reads the reflection. This binding is the possessing peer's composition. So the holder composition lives in the test as the remote peer's control flow. It uses real `make-holder`, `observe-grant`, `due-to-renew?`, `observe-renewal` and `stop` over a real reflection. A production holder belongs with the 3c remote responder.
- **Grant carriage to the holder is test-level.** The test copies the grant from the grantor's writer into the holder's fact medium. `dao.lease.md` Carriage allows this. Serving the grantor's writer as a `lease-grants` `#{:reader}` entry, per S6, is not built.
- **Every served identity is leased.** S6 says a table entry served for a remote party is leased, and UCF `serve!` exports only for remote continuations. The binding has no "self-policy, unleased" entry kind.
- **Renewal media are ring buffers created inside the binding**, with a capacity the composition supplies. This follows S6 ("a ring buffer with surface #{:writer}"). They are declared evict-oldest, portable, per-author.
- **`::lease-media` is a required standing fact medium**, for example a grantor's lease-proposals stream. `make-judge` requires at least one medium at assembly, and renewal media only exist once a handle is served. There is no `:answer` hook, so proposals get no answer (the grantor owes none), and grants are unsolicited.
- **A retirement the judge did not cause ends the lease with cause `:policy`.** The alternative was reaching into the judge ledger, which I avoided.
- **Judge pass on every `step`.** `step` runs one judge pass each call, including after `close!`. The host calls `step` at least at the declared cadence.

## Unresolved concerns

- **Two unowned additions to the judge state.** Pruning a finished lease's renewal medium removes entries from the judge's `:facts` vector. `dao.lease` exposes `wire-facts` but no unwire operation, so the binding edits that plain-data key directly. Wiring renewal media after assembly also uses `lease/wire-facts` rather than the private `wire-declared-medium`, so those entries carry no `:medium` declaration. The judge step never reads that key. A public `unwire-facts` in `dao.lease` would be cleaner, but that file needs authorization.
- **Renewal-medium spoofing (already known).** Until ShiBi exists, anyone who learns a renewal descriptor can extend that lease. No renewal gate is composed.
- **Grantor-source collision is not checked.** A `::lease-media` source equal to `rs/grantor` would let facts on that medium count as the grantor's own. `open!` does not refuse it.
- **Growth left to `dao.lease`.** The judge's `:seen` map grows by one entry per lease. `dao.lease` already documents this.
- **Timing gaps visible to the holder.** A renewal still unread on the channel at a pass counts one pass late. An unread release is reclaimed a pass later. Tolerance sizing has to cover the mirror budget.

## Incomplete work

- cljstyle was not run (permission gate).
- The CLJD lane was not run (the orchestrator owns it).
- 3c is not started: the apply responder, the real-VM end-to-end test, and the UCF retained round trip.
