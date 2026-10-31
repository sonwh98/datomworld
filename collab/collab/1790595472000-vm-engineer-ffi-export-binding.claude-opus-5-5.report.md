Completed-GMT: 2026-09-28 11:42:54 GMT
Completed-Local: 2026-09-28 18:42:54 +07
Coding-Agent: claude
Session-ID: 2fd38cae-ba0d-4d01-9d12-b9ee9af137e2

# Slice 3a report: FFI export binding (`yin.vm.ffi.remote-serve`)

## Changed files (both new, untracked, not staged or committed)
- `src/cljc/yin/vm/ffi/remote_serve.cljc`
- `test/yin/vm/ffi/remote_serve_test.cljc`

No other file was edited.

## API
- `open! opts` → binding, or `{::status ::refused ::refusals [{::option k ::reason ::missing|::malformed|::no-cursor}]}`. Required options: `::channel` ({:reader r :writer w}), `::channel-descriptor` (valid descriptor), `::surface` (handle → subset of #{:reader :writer} or nil), `::admit?` (the authority gate; no default, so a nil gate is refused as malformed), and `::capacity` (a positive integer bounding live exports). The mirror cursor is minted through the channel reader only after every option passes. A refusal publishes nothing.
- `serve! binding handle` → `{:dao.stream/identity :dao.stream/channel}` | nil. `serve-fn` returns the one-argument closure that UCF takes.
- `step binding` → `{:dao.stream/outcome ok ::advanced? bool}`, or `closed` once the binding is closed. It runs one `remote/mirror-step` against a single table snapshot, starting from the binding's own cursor.
- `retire! binding identity` → `{:dao.stream/outcome ok ::retired? bool}`, idempotent. `close! binding` is idempotent.
- `lift-frame binding resources pending [codec]` wraps `ucf.remote/lift-frame` and cleans up after a refused frame.
- `table` and `entries` are read-only views.

## Design choices where the design left latitude
1. **State cell.** The binding is a map holding a private `atom` (`::state`). UCF's `serve!` returns only a marker, so a pure `binding → binding′` threading cannot work through that callback. There is no namespace-level atom. The one drive owner holding the binding serializes serve!/step/retire!/close!. `step` returns outcome data and does not return a new binding.
2. **Registry lookup** uses `identical?` over a vector of entries, not a map keyed by handle, so two `=` handles become two exports. A test covers this with a value-equal defrecord handle. Lookup is linear, bounded by `::capacity`.
3. **Identity minting** uses `(str (random-uuid))`, the same portable call `remote/attacher` uses. Identities are never reused, whether within a binding or across bindings on the same channel descriptor.
4. **Retirement ordering.** Retirement takes two ordered `swap!`s. The first removes the identity from `:table` and marks the entry `:retiring`. The second releases the entry, dropping it from the registry. Retired entries are not kept, so the registry stays bounded, and `retired?` false covers idempotence. The `:status` field (`:live` / `:retiring`) is recorded on the entry. In 3b, lease reclaim should call `retire!`, and any lease release belongs in the second (release) phase.
5. **`close!`** sets closed, retires every entry, and then closes the channel **writer** if it is closable. It does **not** run a final mirror step, because applying remote appends during close would be surprising. The remote link then sees `end` and emits `append-unknown` for every outstanding append!, which is the existing loss path. This assumes the binding owns its channel end, as design §2 says. If a deployment shares one channel between a peer's mirror and its own reflections, closing the writer also ends those reflections. That needs owner or Architect confirmation (see concerns).
6. **Surface policy validation.** `serve!` refuses unless the policy answer is a non-empty set within #{:reader :writer}, the handle implements every declared surface, and the handle answers `descriptor?`. The last check matters because the mirror calls `stream/descriptor` on the handle directly.
7. **Refusal cleanup** lives in `rs/lift-frame`. It snapshots the registry's identities before the lift and retires every identity minted during a lift that returns `:yin.k/status`. Handles served before the lift stay served. A bare `serve!` → nil never creates an entry.
8. **Step bound.** The pass is bounded by what the channel retains when it runs, since `mirror-step` reads until blocked or end. I could not add a per-pass request cap without changing `dao.stream.remote`, which was out of scope.

## Acceptance tests (in `yin.vm.ffi.remote-serve-test`: 8 tests, 68 assertions)
- `open-refuses-an-incomplete-assembly`: each required option missing, nil gate, capacity 0, channel without a reader, and nothing written toward the peer.
- `serve-is-stable-per-live-handle`: identical results, one table entry in the mirror table shape, and reference identity rather than equality.
- `lift-frame-serves-one-handle-once-across-both-call-sites`: real `ucf.remote/lift-frame` over a retained `:ffi-request` frame. It asserts that call-out was requested more than once (lift-one plus mint-cell), that the response marker equals the cell's marker, that there are 2 table entries with exactly one for call-out, and that the declared surface is `#{:writer}` on call-in.
- `retire-unpublishes-and-never-reuses-an-identity`: a raw wire cursor request is ok before retirement and `not-found` after it. Retire is idempotent. The re-served handle gets a new identity while the old one stays `not-found` (next op), and the new identity serves.
- `close-retires-everything`: table and entries are empty afterwards, serve! returns nil, step returns closed, close! is idempotent.
- `an-unresolved-remote-append-ends-as-append-unknown`: a real `remote/attacher` reflection with an events ring. An append is accepted outbound with no step, then `close!`, and exactly one `append-unknown` event appears.
- `serve-refuses-what-policy-refuses`: the gate, a nil surface, a non-served surface (#{:closable}), a surface the handle lacks, and capacity (retirement frees it).
- `a-refused-frame-leaves-no-provisional-export`: the gate refuses call-out, so the lift is `:yin.k/unsatisfied` with no `:yin.k/pending`, and the call-in entry served first is retired. A handle served before the lift survives.

I also checked that the tests bite with mutations. With idempotence disabled (live-entry lookup bypassed) and the table-unpublish step removed from `retire!`, the new ns reported **12 failures**. Both mutations were then reverted with edits, and the rerun is green (below).

## Verification (exact)
- `clj -M:kondo --lint src/cljc/yin/vm/ffi/remote_serve.cljc test/yin/vm/ffi/remote_serve_test.cljc` gave `errors: 0, warnings: 0`.
- `clj -M:test -n yin.vm.ffi.remote-serve-test -n yin.vm.ucf.remote-test -n dao.stream.remote-test` gave `Ran 52 tests containing 444 assertions. 0 failures, 0 errors.` (new ns alone: 8 tests, 68 assertions, 0 failures).
- `bb test:cljs` (I ran it myself; not required) gave `Testing yin.vm.ffi.remote-serve-test` present, `Ran 2202 tests containing 50032 assertions. 0 failures, 0 errors.`
- **cljstyle check: NOT RUN.** `cljstyle check <two files>` was refused by the session's permission gate ("This command requires approval") on two attempts. The orchestrator needs to run it.
- **CLJD lane: NOT RUN** (left to the orchestrator, per the brief). Portability notes: there are no reader conditionals, no `#'` var access, and no host exceptions. The test uses `defrecord` with inline protocol impls and `random-uuid`, and `dao.stream.remote` already uses the latter on CLJD.

## Unresolved concerns
- **Channel ownership on close.** `close!` closes the injected channel writer. That is correct if the binding owns a dedicated channel end, as design §2 says. It is wrong if one peer channel carries both this mirror's answers and the peer's own reflection requests. Options are to make closing an explicit option or to confirm the dedicated-end rule.
- **Retired entries are not kept.** Registry growth is bounded by capacity, but there is no audit trail of retired identities. Non-reuse relies on UUID minting, not on a retired set.
- **`step` returns only `::advanced?`,** because `mirror-step` exposes nothing more. The apply/lease outcomes arrive in 3b/3c.

## Incomplete work
Lease wiring (3b) and the responder with the real-VM end-to-end (3c) are out of scope and were not started. cljstyle and CLJD verification remain for the orchestrator.
