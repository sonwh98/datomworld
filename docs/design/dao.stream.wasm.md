# DaoStream Wasm: the wasm implementation of the dao.stream abstraction

Status: design, owner-authorized planning; not implemented. Subordinate to
[dao.stream.md](./dao.stream.md): that document is the contract; this one
specifies the wasm import-surface implementation. Where they disagree, the
contract wins. It is a sibling implementation spec to
[dao.stream.ws.md](./dao.stream.ws.md).

Provenance (2026-10-19): moved without changing the adapter contract from
[yin.vm.engine.md](./yin.vm.engine.md) §E.3 by owner-approved decomposition.
Doctrine and the dated supersessions have one home,
[datom.world.abi.md](./datom.world.abi.md). The prior service-protocol tables
remain dissolved; this move does not revive them.

The import surface exists because wasm reaches outside through imported
functions over linear memory. These adapters implement dao.stream operations.
The specification is language-neutral: CLJS hosts and native embedders implement
the same surface. The Rust host-side implementation plan lives in
[yin.vm.rust.md](./yin.vm.rust.md) §6.4; the guest engine is not
implemented by those host adapters.

With co-resident producers, an in-instance ring-buffer implementation may
service stream operations with zero host crossings. The signatures and Jing
bytes below specify the import-surface implementation, not a requirement that
every implementation serialize or cross into the host. Canonical encoding is
owned by [dao.jing.md](./dao.jing.md); engine codec conformance is specified in
[yin.vm.engine.md](./yin.vm.engine.md) §E.2.2 and §E.5.

## 1. Imported adapter surface

Use import module `dao.stream` with the following proposed import names.
All parameters and single results are i32. `p,n` address one canonical Jing
CBOR value; `out,cap` denote a disjoint writable response region. The returned
i32 is the **encoded response byte length**, never a status number. Decode
that response into the operation's exhaustive outcome map. Zero-copy views
must not outlive the synchronous call or memory growth.

| Import | Parameters → result | Existing stream operation |
|---|---|---|
| `create` | `(p,n,out,cap) → length` | `create!` on a creation specification (VM make-stream adapter). |
| `attach` | `(p,n,out,cap) → length` | `attach!` on a portable descriptor. |
| `descriptor` | `(handle,out,cap) → length` | `descriptor`. |
| `cursor` | `(handle,p,n,out,cap) → length` | `cursor` with decoded anchor. |
| `next` | `(handle,p,n,out,cap) → length` | `next` with decoded cursor; the poll operation. |
| `append` | `(handle,p,n,out,cap) → length` | `append!` with decoded value. |
| `close` | `(handle,out,cap) → length` | `close!`. |

Make/poll are VM-facing terminology, not additional stream operations. Park,
resume, resolve-var, primitive application, state reads, roots, collection and
module handling have **no imports**: they execute in-instance. Imports never
name arbitrary FFI operations. Memory/table provision and module instantiation
are runtime linkage/lifecycle mechanics, not semantic service operations.

Operational stream handles cannot themselves be canonical data. The import-surface implementation
represents the handle field in a successful create/attach outcome by a checked,
task-local i32 slot, encoded as a Jing integer; decoding reconstructs the local
handle resource before the engine consumes the outcome. This is the physical
representation of an existing handle, not a guest stream identity or a portable
capability. Keep this adapter handle namespace distinct from ValueRefs, check
ownership/lifetime, and never publish it in a stream value, continuation or
module payload. Portable descriptors/cursors and all other outcome fields use
their actual canonical data. Repeated reads remain non-destructive; the adapter
has no privileged reader and introduces no callbacks.

## 2. Outcome and buffer discipline

The exhaustive outcome sets and required keys are those of [dao.stream.md](./dao.stream.md),
Creating and Attaching, Cursors, Reading, Writing and Closing. Preserve optional
qualified diagnostics and retry declarations. Preserve the stream contract’s
unknown-outcome rule: not ok, nothing observed, write acceptance unknown, no
automatic retry without its declared retry permission. In particular append returns
ok/full/invalid-value/closed/refused/transport-error; next returns
ok/blocked/end/gap/cursor-mismatch/invalid-cursor/refused/transport-error;
close has only ok. Do not turn ordinary blocking or refusal into a trap.

The engine allocates request/response memory before invoking an adapter. The
composition declares supported payload/output bounds; W0 establishes a response
reservation sufficient for **every** admitted result, including diagnostic and
handle outcomes. Validate pointers, lengths, overflow, overlap and reservation
before executing an operation. Invalid adapter calls are implementation faults,
not new stream statuses. Allocation failure is a qualified local resource limit
before invocation. No operation may commit and then ask the guest to resize and
fetch its response; no retained-response service or semantic replay exists.

A full append buffer uses the stream's **full** outcome: nothing appended, keep
the exact pending value and retry through the existing writer wait discipline.
Response capacity is reserved separately; full is not invented for reads or
close. Read-side inability to carry an oversized value follows the stream's
transport-error outcome without advancing the immutable cursor. Admitted limits
must make ordinary outcomes deliverable; test limit failures before mutation.
An append transport-error can mean acceptance is unknown: do not automatically
retry unless that transport declares clean failure. Blocking waits stay as pure
data in the in-instance engine; the composition later drives execution again,
without retaining a wasm activation or application callback.

## 3. Canonical bytes and authority

In the import-surface implementation, requests and outcome data use exactly Jing's canonical CBOR ([yin.vm.engine.md](./yin.vm.engine.md) §E.2.2), with its
own ordering, numeric carriers, validation and decode/re-encode rules. Length
parameters delimit one CBOR value; no TLV or alternate framing wraps it.
Canonical maps/sets use repository ordering, never task handles or arena ids.
Register image checksums still use the register codec. Buffer access grants no
permission to pass internal Values, private engine snapshots or trusted wrappers
across custody gates. Stream history records effects/facts, not register loads,
root materialization or other machine-internal traffic.

