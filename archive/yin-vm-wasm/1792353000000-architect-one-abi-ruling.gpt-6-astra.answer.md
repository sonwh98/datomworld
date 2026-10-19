I’ll read the recorded ruling and its engine/lowering notes, then reassess my position and what it implies for W0–W6.


**The ruling changes my recommendation, but it does not make “performance is the sole valid justification” a sound general architectural law.** I now favor engine-in-guest as the planned destination, with the split-machine service interface as an explicitly temporary alternative. I would move the node-lane feasibility work and comparison into W0, rather than first investigate it in W6.

My round-1 argument established why a private interface is needed **after choosing to split the machine**. It did not establish why that split should be chosen. The owner is right to challenge that circularity.

**1. What I retain, and what I revise**

I retain three conclusions:

- Machine-internal operations should not automatically become stream history.
- Rich internal values still require an internal representation; canonical wire encoding cannot replace it wholesale.
- Imported functions can implement `dao.stream` operations. Wasm’s use of imports does not require a different semantic protocol.

I revise the status of the “indispensable reason.” Shared state and richer values justify the interface *conditional on host ownership of that state*. Move ownership into the guest, and the justification disappears. The representation problem remains, but it becomes an internal implementation problem rather than a guest-host protocol problem.

I also qualify the argument that “FFI yields but primitive application does not.” That is a property of the current instruction contract, not a fundamental distinction between arithmetic and the world. Internal suspension could be hidden inside a logical instruction if the implementation preserves its observable atomicity, ordering and refusal behavior. That would require careful scheduling and buffering; it is not impossible.

However, appending every primitive request to an observable stream would introduce new history. “Any operation can use request/response” does not mean “doing so preserves the existing observable contract.” The stronger route to one ABI is **moving internal execution inside the guest**, not converting internal execution into external effects.

**2. Where I disagree with the ruling**

The [recorded ruling](/Users/sto/workspace/datomworld/docs/design/datom.world.md:96) combines two different claims:

1. Prefer one semantic interoperability contract.
2. Runtime performance is the only legitimate reason for any other interface.

I support the first. The second is too narrow.

| Candidate reason | My assessment |
|---|---|
| Capability/security granularity | A genuine reason for an isolation boundary, but usually **not** for a second semantic ABI. Stream handles, capability restrictions and authorized interpreters can provide the granularity. A generic service interface may actually expose more authority. |
| Determinism and cross-embedder parity | Acceptance requirements, not reasons to split the engine. Shipping the same engine inside wasm may reduce host-dependent variation. |
| ISA freeze versus extensibility | Usually not a second-ABI justification. Versioned primitives and effect vocabularies can evolve without making every operation an opcode. A service interface moves the compatibility obligation; it does not remove it. |
| Failure isolation | Can independently justify a component or process boundary. It does not inherently require a second operation vocabulary: streams can connect isolated components. Fault containment itself is not reducible to throughput. |
| Independent versioning and existing-host compatibility | Can genuinely justify a private binding when independently supplied components must interoperate through an already fixed interface. Replacing that integration with stream messages may be infeasible or alter its contract. This is a compatibility/lifecycle reason, not necessarily a crossing-performance reason. |
| Implementation and deployment cost | A legitimate reason for an interim split: runtime availability, debugging, binary size, startup, integration effort and delivery risk matter. These are engineering costs, but they are not all “measured per-instruction boundary crossings.” |

For this project, I do **not** see a demonstrated compatibility requirement that makes the broad engine service ABI permanently necessary. The shared Rust crates and explicit state model make engine-in-guest a credible destination. But that is a project-specific conclusion, not proof that all private ABIs are justified only by runtime speed.

“Every removal is progress” also needs qualification. Removing a documented interface by introducing implicit shared-memory layout dependence is regression. Removing it by colocating the owning implementation is progress. Count independently maintained contracts and coupling, not just imported function names.

There is another important measurement issue: the broad service ABI is potentially faster than **streaming every internal operation**, but potentially slower than **executing those operations inside wasm**. It cannot justify itself solely by winning against the first, unattractive alternative. The performance comparison must include the node lane.

**3. The floor is an information constraint, not a fixed architectural location**

The sound part is:

> A computation cannot produce information that is absent from its initial state and subsequent inputs.

That explains why genuinely external observations require ingress. It does not establish a permanently fixed list of “world-fact operations.”

A recorded clock, deterministic random generator, supplied filesystem snapshot or replayed network transcript can turn previously external queries into local computation. The operation’s name may remain unchanged while its meaning changes from “observe the current world” to “consult this recorded world.”

Replication does not abolish the information boundary either. Agreement requires messages from participants, unless their decisions are already part of the supplied state. Consensus can establish an agreed fact; it cannot locally reveal an unobserved external event.

Thus the floor moves when the system changes its inputs, authority or promised semantics. What remains immovable is the need for input when the required information is genuinely unavailable locally.

And information theory does not uniquely imply `dao.stream`. **Choosing `dao.stream` as the sole representation of external interaction is the project’s architectural principle.** It is a strong, coherent choice, but it is not a consequence of wasm or the impossibility of computing unknown facts.

An engine-in-guest node will still need a physical binding: imports, handles, buffers, outcome representation and lifecycle control. “One ABI” should mean that this binding implements the stream contract without adding a second engine-service vocabulary—not that all representation conventions disappear.

**4. I would change W0–W6**

The current body-lane-first schedule no longer fits the stated strategic endpoint without an early decision gate. Building the full TLV service system through W5 and only then testing whether it was needed risks making the provisional architecture permanent through investment.

Also, **compiled bodies versus interpreter dispatch and engine-in-guest versus engine-in-host are separate choices**. The node lane can eventually execute compiled body functions. It need not permanently replace lowering with an interpreter.

I would revise the schedule as follows:

| Slice | Change I recommend |
|---|---|
| **W0** | Preserve the shared K0 kit, exact codecs, native-runtime probes and feature profile. Add a minimal engine-in-guest feasibility prototype using the shared Rust implementation, alongside a minimal split-engine prototype. Exercise pure execution, snapshot/restore and one blocking stream cycle. Measure crossings, encoding, execution time, startup, memory and artifact size. Do not first build the complete service vocabulary. |
| **W1** | Establish pure register execution with guest-owned values and state in the node prototype. Use interpreter dispatch initially if that accelerates validation. Prove primitive semantics, allocation, refusal atomicity and bounded tail execution. Compile the same binary for Node and native-runtime testing. |
| **W2** | Implement continuations and restore against that ownership model. Preserve all existing payload, check-order and cross-lane tests. Private handles remain internal; export materializes portable data or refuses. |
| **W3** | Integrate stream operations, scheduler behavior, custody and FFI. Guest execution returns blocked state to the driver; imported operations do not retain suspended guest stacks. Keep every current once-only and terminal-outcome test. |
| **W4** | Add module growth, closure transport and artifact integration; retain Wasmer and bidirectional module-slice gates. Demonstrate compiled bodies calling the colocated engine implementation without a host service-message round trip for internal transitions. |
| **W5** | Qualify the node lane against the complete kit. A split-engine lane may ship too, but its retained private services require explicit evidence and scope—not inheritance from the original design. |
| **W6** | Optimize dispatch, spilling, batching and runtime placement using measured workloads. This is where optimization belongs; it should not be the first investigation of the strategic architecture. |

This is a proposal to amend the plan, not a claim that those shared crates are already wasm-ready. W0 may uncover substantial obstacles. Under the ruling, those obstacles should be reported explicitly; they should not be disguised as measured performance findings.

Finally, the statement still present in [yang.wasm §4](/Users/sto/workspace/datomworld/docs/design/yang.wasm.md)—that a wasm module cannot express a stream operation—should be revised. It contradicts the proposed destination. Wasm needs a binding to express stream operations; it does not need an engine service protocol to do so.

**My revised position is: plan for guest-owned execution and a `dao.stream`-only semantic boundary, and require the split-engine alternative to earn its place in W0.** The owner’s direction is persuasive. The absolute claim that performance is the only legitimate consideration is not.