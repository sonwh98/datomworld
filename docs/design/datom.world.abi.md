# datom.world ABI: the one abstraction boundary

Status: design/doctrine; owner rulings, 2026-10-19.

[dao.stream.md](./dao.stream.md) owns **what** the contract is: operations,
exhaustive outcome maps, canonical values, batch and observer semantics. This
document owns **why** that contract is the only boundary: role, rulings,
implementation taxonomy, floor and custody. It does not redefine the operations.

Provenance (2026-10-19): distilled from this session's owner rulings and moved
from datom.world.md, Host Boundaries. The five-round wasm consensus lineage
is recorded in [yang.wasm.md](./yang.wasm.md); subsequent owner rulings below
supersede its former service-protocol framing. This is the single doctrine home.

Authorities:
- [datom.world.md](./datom.world.md): master axioms and commitments.
- [dao.stream.md](./dao.stream.md): the abstraction contract.
- [yin.vm.engine.md](./yin.vm.engine.md): execution, custody and conformance method.
- [yin.vm.debruijn.register.md](./yin.vm.debruijn.register.md): concrete machine state and fixtures.

A host boundary is a stream boundary, never a function call. The one
sanctioned function-shaped contact is a `dao.stream` handle operation, whose
exhaustive outcome map is stream protocol, not an application callback.

## 1. The system ABI

One abstraction boundary (`dao.stream`), many
implementations (ring-buffer, websocket, UDP, wasm imports). `dao.stream` is
the contract for datom.world and `yin.vm`: operations, exhaustive outcome maps,
canonical values, append-only batch semantics and observer discipline. It is
not a transport implementation or a wire protocol. The wasm import-surface
implementation is specified once in [dao.stream.wasm.md](./dao.stream.wasm.md);
its canonical Jing CBOR representation does not define the abstraction itself.

## 2. Implementations and the taxonomy ruling

**Owner clarification, 2026-10-19 (verbatim):** “dao.stream is an abstraction
boundary. It is not a transport implementation. It can be implemented using a
ring-buffer, a websocket or UDP”

The wasm import surface is another implementation: adapters implement the
abstraction through imported functions over linear memory. A co-resident
producer can instead use an in-memory ring-buffer implementation inside the
instance with zero host crossings. Both preserve the same abstraction contract.

## 3. Custody at the boundary

What crosses a trust domain is a strict subset of in-machine values: closures
and continuations cross only as admissible portable plain data; host types,
resources and code artifacts never do. The custody gates retain this distinction
([yin.vm.linker.md](./yin.vm.linker.md)); an implementation grants no additional
portability or authority.

## 4. Boundary ruling and supersession

**Owner ruling, strengthened 2026-10-19: the boundary is drawn, not found;
one abstraction boundary is the principle.** The boundary between wasm and datom.world is
`dao.stream`; its implementation uses whatever wasm exposes to the outside
world. The exposed functions are **adapters to dao.stream**, which is the ABI
to `yin.vm` and the whole ecosystem. Machine-internal work executes inside the
wasm instance: the Rust engine crates and compiled register bodies share memory
and direct calls. Only effects/facts cross through the stream adapters. World
facts still require interaction with the world; moving engine implementation
inside the machine does not manufacture those facts.

**Dated supersession (owner boundary clarification, 2026-10-19):** This replaces
the earlier same-day “sole justification … performance” allowance for
process-local services and the service-protocol interpretation of
`yin.vm.engine.host.v1` (historically renamed from `yin.register.host.v1`). Its private operation vocabulary, TLV codec,
expanded-canonical-bytes comparator and service-status protocol are dissolved.
Performance work optimizes internal execution or the stream implementation; it does
not reintroduce a semantic service interface. [yang.wasm.md](./yang.wasm.md)
is the compiler implementation plan for this in-instance architecture.

## 5. Naming and the floor

“The system ABI” names only `dao.stream`. Earlier qualified guest-host service
names are historical provenance for the dissolved protocol, not names for a
second contract. Implementation specs name their implementation (WebSocket,
wasm import surface), never “the wasm ABI”. Internal compiler linkage does not
create an ecosystem boundary.

The floor is the need to obtain world facts that shipped code cannot compute.
That seam is immovable; the engineering frontier is movable: machine-internal
operations can move inside the instance without changing the abstraction.
An in-instance stream between co-resident producers and observers needs no host
crossing. Changing physical placement does not remove external facts or custody.

Implementations include ring-buffer, [WebSocket](./dao.stream.ws.md),
[wasm imports](./dao.stream.wasm.md), and UDP. These implement one abstraction;
none adds an ABI. The import-surface representation and buffer rules belong
only to its implementation spec.

## 6. Effects out and events in

**Effects out.** Portable code appends an effect value describing what must
happen. A host interpreter consumes that stream, performs the operation, and
appends the outcome. Portable code reads the outcome. Correlation is by
identity in the data; neither side holds a reference to the other. A host with
no implementation for an effect emits a qualified unsupported result. That is a
correct outcome, not a gap to be filled.

**Events in.** When the host initiates (a socket receives, a connection drops,
a timer fires) the adapter appends one plain-data event and returns. It invokes
nothing. An interpreter reads that stream and decides what the event means.
Constructively: **a callback is replaced by a function that transforms an event
to data on a stream.** The host's demand for a callback stops at that function.

## 7. Adapter discipline

**Adapters.** An adapter is a map of those transform functions. That is all an
adapter is. It is a value, not a namespace, and it is general: sockets, files,
timers and windows all deliver events by calling something, and an adapter is
what they call. Each entry takes the host's arguments and returns one value;
the append is the adapter's, not the entry's, so an entry cannot return a value
to the host and cannot invoke application code. The adapter is handed only the
deposit operation and its own host resource. If its deposit channel fails, it
may close that resource to make the boundary observably gone; this is teardown,
not an upward callback.

Host types are classified into plain data in the transform: it is the last
place holding a host error object, and no host type crosses onto the stream. A
transport may use a qualified envelope to preserve correlation, lifecycle, and
additive evolution; that envelope remains plain data and creates no separate
event mechanism. A composition may likewise define its own qualified vocabulary
for facts it owns.

Which stream an adapter writes to is the caller's composition, never the
adapter's knowledge. An adapter cannot tell whether it deposits into one
transport's private stream or into a medium many interpreters read. This keeps
the layering one-way: a boundary depends on nothing above it, because whoever
constructs the adapter supplies the stream.

A transform deposits every event admitted by the boundary protocol and judges
none of them semantically. Wire framing, decoding, and protocol validation
happen below the transform: malformed wire input is transport machinery, not an
event. Once the transport has produced a valid host event, data is syntax
(axiom 2): an event that is noise to one interpreter is signal to another that
does not exist yet, and filtering at the depositing layer destroys perspectives
not yet taken. Retention is the medium's operational policy; meaning is
interpretation. Neither is the depositor's decision.

Two things are not adapters. One that exposes a function for portable code to
call isolates the host library but keeps the coupling, and the effect never
appears as an emission. One that takes a function to invoke has relocated the
callback, not removed it, even when every value it passes is plain data; if
that callback has a meaningful return value, it is a synchronous call in both
directions wearing the shape of an event.

