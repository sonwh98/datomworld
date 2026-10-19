I’ll check the boundary doctrine and wasm service contract, then give my assessment of whether the separate ABI is necessary.


**A separate guest-host ABI is justified for the current split implementation. Its indispensable reason is that wasm and the embedder jointly implement one machine whose internal values and state are richer than its portable stream protocol.** It is not justified merely because wasm uses imported functions. Nor does that reason make the current generic `exchange` protocol indispensable.

I would keep the distinction between machine-internal services and stream communication, but simplify the former where possible. I would not turn register access, variable resolution or GC synchronization into stream history.

The premise “wasm cannot express a stream operation” is misleading. Wasm can express one through an imported function implementing that operation. That is no less a stream operation than calling a `dao.stream` handle from Rust. Indeed, the doctrine explicitly permits function-shaped stream-handle operations. The concrete stream contract returns immediate outcomes; `next` can return blocked and `append!` can return full. **Parking belongs to the interpreter/scheduler, not to the stream operation itself.** An `append/read/park` import surface would already combine two layers. See [dao.stream’s operation contract](/Users/sto/workspace/datomworld/docs/design/dao.stream.md).

Consequently, synchronous execution alone is not an argument against stream imports. A synchronous `append!` returning an exhaustive outcome fits the model perfectly. The difficult question is what belongs on that interface.

**A variable lookup is not an external effect.** Under the present implementation, `resolve-var` needs access to host-owned environments, stores, primitives and modules. Making that lookup an appended request would require one of two choices:

- Treat the request as real stream communication: give it identity, scheduling, retained history, response handling and an explicit interpreter responsible for resolving it.
- Immediately service the request through a designated host function and return its answer, while calling the interaction a stream.

The first is a substantial execution-model change. It creates observable events and potential suspension points where the guest instruction previously had none. The second retains the internal service interface under stream terminology.

The no-privileged-reader rule does not forbid request/response streams or an explicitly composed service interpreter. Other interpreters can observe the same requests. But it also does not supply a unique responder, authorize that responder to mutate this machine, or make every observer’s response interchangeable. Those responsibilities still need an explicit contract. A stream is not a substitute for specifying them.

GC makes the separation particularly clear. Before a host allocation can trigger collection, the host must see the current roots. Publishing “here are my roots” into a stream does not establish that the collector has consumed them before allocating. You would need acknowledgement and ordering machinery—or retain the synchronous materialization barrier. That barrier is an implementation obligation between parts of one evaluator, not an effect for independently observing agents. Optional tracing can record it; correctness should not depend on making it public history.

The representation problem also survives a transport redesign. Canonical Jing CBOR serves portable values and their identities. Internal execution additionally needs task-local handles, trusted wrappers, precise numeric carriers, immutable snapshot ownership and values whose external transport must be refused. Renaming `exchange` to `append` does not make those values portable.

There are two honest choices:

1. Keep the split machine and define a private representation binding.
2. Move enough runtime functionality into wasm that only portable effects and stream operations cross out.

Using Jing CBOR wherever values are already canonical could reduce codec duplication. It cannot eliminate the distinction without restricting in-machine behavior or defining additional private representations. Stream operations themselves still need a wasm binding for handles, buffers and outcomes.

**I would not defend one generic `exchange` as an architectural principle.** Its benefits are practical: one import signature, a common envelope, uniform validation and reusable response-buffer handling. Its costs are also real: another dispatcher, serialization on every service call, a large operation vocabulary and transport mechanics interwoven with execution.

Typed imports can use only i32 arguments and results: checked handles, offsets, lengths and status codes. They do not require i64, externref or host objects in function signatures. A typed interface can still centralize encoding and buffer management. Conversely, “typed” imports passing arbitrary request maps would merely distribute the generic dispatcher across several names.

For the current host-owned Value arena, I would build a small, explicit runtime binding with two distinguishable categories:

- **Internal machine operations:** value ownership, primitive execution, resolution, state materialization and other services needed to implement the guest instruction. These invoke the shared engine/kernel implementation.
- **Stream-handle operations:** preserve `dao.stream`’s existing operation and outcome semantics, with the same custody and authorization checks. Blocking outcomes return control to the scheduler; no imported function retains a suspended guest stack.

I would select generic versus typed calls by measured complexity and crossing cost, not by doctrine. Neither choice changes the system’s communication model.

An effect-only wasm runtime is the cleaner eventual boundary **if** it owns its values, resolution, frames, continuation machinery and collection. It can run until an effect, return explicit resumable state, and let the composition perform stream operations. But “no synchronous services” is not a thin interface change: it relocates the runtime. That is a valid competing architecture, subject to the same conformance obligations and shared-implementation constraint.

Moving the interpreter entirely into the host is simpler again, but then the host is executing the register images. Unless wasm still performs meaningful execution work, that abandons the purpose of this lowering rather than improving its boundary.

Finally, “process-local” must not be used as a synonym for “same trust domain.” If arbitrary independently supplied wasm is admitted, the import interface is a security boundary regardless of its name. The current justification depends on locally derived wasm being an implementation component of the evaluator, with checked capabilities and no authority to manufacture trusted values. Calling an interface a shim does not establish that condition.

My position is therefore: **retain a private runtime binding because the chosen implementation splits one machine across wasm and the host; let actual communication retain `dao.stream` semantics.** The separation is substantive. The generic mailbox, private TLV codec and broad service vocabulary are contingent engineering choices. I would challenge those choices on complexity and performance grounds, but I would not eliminate them by promoting machine-internal traffic into stream history.