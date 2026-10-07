Completed-GMT: 2026-09-28 11:30:17 GMT
Completed-Local: 2026-09-28 18:30:17 Asia/Ho_Chi_Minh
Coding-Agent: codex
Session-ID: 01a0e7c5-6b35-7e01-9081-541da3b78e92
# Task: Architect design — Slice 3 production FFI serving composition
Role: Lead System Architect
Implementers:
- Model: gpt-6-sol | Assigned: 2026-09-28 18:27:42 +07 | Status: completed | Rationale: Architect route per routing-status.md

## Verdict

**Implementable, with one required remote-core correction.** The existing apply envelope, remote reflection, VM wait, and UCF formats fit the ruling. The production composition is missing. In addition, `remote/mirror-step` can consume a request when its answer append is `full`; that must be fixed before the end-to-end case can claim reliable completion. Slices 1 and 2 are already committed as `afa01710` and `84bfb74d`.

## Design

### 1. Placement and public API

Add **`yin.vm.ffi.remote-serve`** as a CLJC composition namespace. It owns the FFI export binding and drive; `dao.stream.remote` remains the generic stream transport, `dao.stream.apply` remains the envelope and server step, and `yin.vm.ucf.remote` remains a lift/lower facade.

Expose:

- `open! opts → binding`: accept the VM’s actual call-in and call-out handles, a channel end and descriptor, declared capacity and surface policy, and lease wiring. Mint every reading cursor through its own handle. Refuse incomplete wiring before publishing descriptors.
- `serve! binding handle → {:dao.stream/identity id :dao.stream/channel descriptor} | nil`: the callback passed to UCF. It admits only handles this binding can keep reachable and serve.
- `step binding → binding′/outcomes`: one bounded drive pass over mirror traffic, apply response work, and lease work, with the new state returned to its owner.
- `retire! binding identity` and `close! binding`: explicit, idempotent retirement. Reclaim uses the same retirement transition.

Keep the implementation portable across CLJ, CLJS, and CLJD: ordinary maps, vectors, `dao.stream` handle operations, and reader conditionals only where an existing host facility requires them. The channel adapter and scheduling clock are injected by the host composition.

### 2. Registry, ownership, and linearization

One export binding owns a private registry of **live handle references**, compared by reference identity, not descriptor equality. Its entry records the handle, minted served identity, channel descriptor, declared `#{:reader}` or `#{:writer}` surface, mirror-table entry, lease ID and status, and retirement status. The binding separately owns the channel reader/writer, its mirror cursor, the call-in request cursor, `apply/server-state`, and reflection/attachment bookkeeping. The mirror receives a snapshot map of `{served-identity {:handle h :surface S :dao.lease/lease L}}`.

Repeated `serve!` calls for the same live handle **within that binding** return the same identity and channel and use one mirror-table entry. A retired handle may receive a new identity only through a new export tenure; never reuse the old identity. This is necessary because `lift-frame` can ask for the same handle while minting a cell and again while lifting an entry.

The registry is composition-owned state with one drive owner. Serialize registration, mirror lookup, and retirement at that boundary; do not expose its mutable cell as a process registry or let concurrent drivers mutate it. A mirror step uses one table snapshot for its pass. Retirement linearizes by removing the identity from the published table before releasing its handles. A refusal returns `nil` to UCF before any lift value is published; any entries provisionally made during a failed whole-frame lift must be retired explicitly.

### 3. Lease lifecycle

A handle exported for a remote continuation is a leased resource under [the remote design’s lease rule](/Users/sto/workspace/datomworld/docs/design/dao.stream.remote.md:508). The possessing peer grants and judges it. Wire `dao.lease/make-judge` to explicit tick and fact media, attribution resolver, writer, cadence, and an idempotent reclaim procedure whose subject resolves to this binding’s served identity. The remote holder uses `dao.lease/make-holder` and appends renewals and release facts on its attributed medium. The host drive supplies tick **data** and calls the lease steps at the declared cadence; neither fetch nor the stream core reads a clock.

A successful reclaim removes the served identity and its renewal entry together. Later remote operations receive `not-found`. A detached channel alone does not retire the table entry or lease: reattachment within the live tenure uses the same identity and source cursors. On reclaim, an in-flight retained request has no delivery guarantee. If its append was unacknowledged, report the existing `append-unknown`/terminal-loss outcome; do not silently retry it against a new tenure. If a response was already appended, the caller may consume it. The holder stops at its bound even if a renewal acknowledgement is delayed. Keep reclaim pending until its procedure reports success, as `dao.lease` requires.

### 4. Responder and VM resumption

The VM emits its original `apply/request` on call-in and parks with its own call ID. Export call-in as a remote **writer** and call-out as a remote **reader**. A remote responder attaches both reflections, mints its response cursor from call-out before sending work, and advances them through a bounded polling drive. The call-in surface transports the **whole original map**, including extra keys; no `op`/`args` reconstruction is permitted.

At the possessing peer, `apply/serve-once!` reads that request using the cursor minted from call-in, dispatches one handler, and appends an `apply/success-response` or `apply/error-response` with the request ID to call-out. Its returned state retains a computed response and successor across `full`. The remote responder’s `rpc` driver may be used for its own request/response service and polling policy, but **`rpc/request!` must not be used to reissue the VM call**: it allocates a new ID and would violate the unchanged-request requirement. The VM’s ordinary response wait reads call-out and resumes through `yin.vm.ffi/call-result`, which checks the ID, returns a successful value (including `nil`), or raises the portable apply error.

## Acceptance tests

1. **Real VM end to end:** run an AST `:dao.stream.apply/call` without a local bridge; inspect the call-in value; carry it unchanged through remote append; answer success and error through the production composition; show VM halt/value and raised error through `call-result`. Include an extra request key and verify identical ID and map in transit.
2. **Pending request append:** force `full`, retain the exact request and ID, then retry once capacity returns. Assert one accepted append and one handler invocation.
3. **Response `full`:** force call-out `full` after handler evaluation; repeat steps; assert retained response and successor, no second handler invocation, then one response append.
4. **Remote answer `full`:** force the channel answer writer `full`; assert the mirror does not consume the unanswered request and retry yields exactly the original outcome.
5. **Gap:** gap the call-in request cursor and verify the binding reports loss rather than leaving a parked VM indefinitely; gap a pair channel and verify its link ends.
6. **Detach and rebind:** end the channel, observe detached completion, keep the served entry and lease live, reattach, and complete a subsequent call under the same served identity.
7. **Terminal `not-found`:** reclaim the lease, verify removal precedes acknowledgement of retirement, and prove a fresh operation returns `not-found` and cannot rebind that identity.
8. **Retained UCF call:** force the VM’s call-in append to `full`; lift and lower the parked `:ffi-request`; verify its envelope, extra key, response cell, and cursor survive verbatim; retry over reflections and resume the VM.
9. **`serve!` idempotence:** exercise both call sites in `lift-frame` on one handle; assert identical marker identity/channel and one table entry.
10. **Refusal:** make either endpoint unservable or its cursor unportable; assert `:yin.k/unsatisfied`, no published lift, and no lingering provisional export entry.

Run focused CLJ, CLJS, and CLJD lanes for the new CLJC composition and its VM/UCF tests, followed by the project’s required full gates.

## Smallest committable order

1. **Mirror answer retention:** make `remote/mirror-step` retain an unanswered request and computed answer across channel `full`, advancing its channel cursor only after accepted answer append. Add the direct `full` and retry test. This is a prerequisite for reliable serving.
2. **Export binding:** add the production namespace, per-handle registry, channel/cursor ownership, stable `serve!`, retirement, and refusal cleanup. Accept with idempotence, surface, and terminal `not-found` tests.
3. **Lease wiring:** integrate the judge, holder renewal path, and idempotent reclaim into the drive. Accept with live renewal, expiry, detach without expiry, and in-flight reclaim tests.
4. **FFI responder and VM proof:** wire `apply/serve-once!` to the exported call pair and the remote reflection drive. Accept with success/error, both `full` cases, gap, and retained UCF round trip.

**Must not change:** Yin’s `dao.stream.apply/call` AST name; apply’s open predicates or opaque ID domain; the VM’s original request map; rpc’s allocation policy and local event vocabulary; remote’s independent operation envelope; UCF’s retained-envelope representation. Do not add a process-wide registry, core clock, upward callback, or a second application wire language.

**Open risks and decisions:** Transport delivery after an unacknowledged append remains uncertain and must surface as such. Channel payload size can reject an otherwise valid retained envelope as `oversize`. The owner must choose the deployment’s channel adapter, lease durations/cadence/tolerance, retention capacities, and whether exported FFI handlers may be invoked by every peer that learns a descriptor; these are policy and security decisions, not changes to the envelope architecture.

## Defects found

| Severity | File:line | Invariant/evidence | Recommended correction |
|---|---|---|---|
| **P1 architectural defect** | [remote.cljc:138](/Users/sto/workspace/datomworld/src/cljc/dao/stream/remote.cljc:138), [remote.cljc:185](/Users/sto/workspace/datomworld/src/cljc/dao/stream/remote.cljc:185) | `write-answer!` does not retain a refused `full` answer; `mirror-step` advances the request cursor regardless. An accepted remote request can therefore lose its only answer. | Retain request, computed answer, and successor in caller-owned mirror state; retry the same answer and advance only after its append succeeds. |
| **P2 implementation gap** | [ucf/remote.cljc:435](/Users/sto/workspace/datomworld/src/cljc/yin/vm/ucf/remote.cljc:435) | `lift-frame` may call `serve!` repeatedly and may later refuse the frame. The facade has no export registry or rollback contract. | Supply stable binding-scoped `serve!`; stage or retire provisional exports on refusal. |
| **P2 implementation gap** | [ffi.cljc:196](/Users/sto/workspace/datomworld/src/cljc/yin/vm/ffi.cljc:196), [apply.cljc:301](/Users/sto/workspace/datomworld/src/cljc/dao/stream/apply.cljc:301) | Local bridge and generic apply server exist, but neither owns the remote call-pair export, lease, and responder drive. | Implement the composition above and prove it with a real parked VM call. |
| **Design wording to resolve in implementation** | [rpc.cljc:279](/Users/sto/workspace/datomworld/src/cljc/dao/stream/rpc.cljc:279) | `rpc/request!` mints an ID; forwarding an unchanged VM request through it would replace correlation identity. | Use rpc for its separate client service where needed; carry the VM apply request verbatim over the remote call-in reflection. |
