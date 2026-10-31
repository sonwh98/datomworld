# Guest-host ABI vs dao.stream — Fable position

(Provenance: delivered via plan-file by claude-cloud/claude-fable-5-1 — plan mode
could not write files, so the orchestrator archived this draft verbatim from
/Users/sto/.claude/plans/read-collab-1792350400000-architect-gues-luminous-owl.md.
Session 9f2c1a44-77b2-4e0a-8d31-2b6f0c5a1e33, 2026-10-19.)

---

# Guest-host ABI vs dao.stream — Fable position

**Verdict.** The separate guest-host ABI is justified. The one indispensable
reason is **shared value custody**: the wasm guest holds references (i32
`ValueRef` handles, tag-13 arena refs) into a value heap the host owns, and a
reference is categorically not a stream value. dao.stream carries only
canonical plain values and crosses trust domains; the guest-host seam is an
*executor split inside one trust domain* over one heap. Those are different
seams and need different contracts. Everything else people cite (synchrony,
GC roots, exchange count, wasm's import-only egress) is downstream of this.

I disagree with the framing in two places, and with the doctrine's own
justification in one.

## 1. Where the question's framing is wrong

**(a) The wasm module is not a host.** The doctrine sentence "a host boundary
is a stream boundary" is about *hosts*: nodes with their own trust, their own
heap, no shared code. The emitted wasm module is a compiled *body* of the
engine: it is produced by our compiler from an admitted image (A is the code
identity), it has no heap of its own, its values are handles into the
composition's arena, its effect authorization is done by the composition
(`engine/check-callee-effect!`, trusted-constructor channel), and it cannot
even be instantiated without the composition supplying `memory` and
`functions`. It is to the CLJS engine exactly what the LLVM-lowered body
(§3.1.1) is to the Rust engine. Nobody proposes that the JVM engine's
`engine/resolve-var` map lookup should be a stream append; the wasm guest's
`:load-free` is the same lookup, just issued from a different executor.
Today's reference engine is the proof: `engine/resolve-var`
(`src/cljc/yin/vm/engine.cljc:115`), `gc-roots`/`collect` (lines 2541/2587),
primitive application and `handle-effect` for `:cell/*` and `:vm/store-put`
are all function calls over in-process maps; only `:stream/*` effects
(`observe-put` → `stream/append!`) and the FFI two-step touch dao.stream.
The wasm lane preserves exactly that partition; it does not add a new
function-shaped crossing, it relocates existing ones to a mailbox.
Calling the module a "guest" and then applying host doctrine to it is the
category error that makes the question feel compelling.

**(b) "Different ABI" conflates two things.** The question treats "typed
imports mirroring append/read/park" as *not* a different ABI. It is: any
wasm import is `(i32 ...) -> i32` over linear memory, so a stream-shaped
import surface is still a private byte protocol in the guest's memory, with
its own framing, ownership, retry and versioning rules. Renaming the
operations after stream verbs does not make the bytes stream values or the
seam a trust boundary. What would actually make the guest's ABI *be*
dao.stream is a guest that owns its heap and speaks jing CBOR over a dumb
transport — see §5, which is a different product.

## 2. Why the shared-heap seam cannot be a stream (the decisive points)

**References.** dao.stream's defining property (datom.world.md: "what crosses a
trust domain is a strict subset of what exists inside a process") is that
nothing on it is a pointer. The guest's entire register file is pointers.
`resolve-var` returns a primitive, a closure, a continuation or a typed
effect: three of those are capability-bearing trusted values whose identity
is *owner + arena*, and the fourth (closure) is portable only as a payload
that loses sharing. A stream can carry a capability *token* (ShiBi) but not
the capability; so a stream-shaped `:load-free` of `+` yields a token the
guest cannot apply without another crossing. The representation problem does
not survive transport redesign — it *defines* the seam. Wire form is a
projection (loses sharing, identity, host types, capabilities); the custody
gates exist to perform that projection once, at the trust boundary. Putting
the stream at the executor seam means projecting on every register-level
service, and for primitives there is no projection at all.

**GC is the anti-stream operation.** The stream is append-only history and
is explicitly what GC must never touch ("reclaim only unreachable
implementation storage, not stream history"). Root materialization for GC is
traffic whose purpose is to make things *disappear*. If roots are appended as
stream history, garbage becomes permanent, and the observers that are
supposed to be ignorant of the engine (dao.space.index) now index the engine's
register file. This is "derive, don't persist" inverted: the CESK state is
*derived* from the program stream by one observer; recording its internals as
history persists derived facts, and a replaying evaluator can no longer tell
whether to recompute or to trust the recorded reads. This alone kills
"machine-internal traffic as stream history".

**No-privileged-reader cuts the other way.** An exchange has exactly one
reader: the composition that owns the arena, because only it can resolve
tag-13 references. That is fine *because it is not a stream*. If the service
traffic were a stream, the arena owner would be a privileged reader — the
only observer able to make sense of the batches — which is the rule's precise
violation. Trying to stream-ify the shim does not remove a privileged reader;
it creates one.

**Synchrony is not the problem; identity of the answer is.** "Append then
block on the correlated outcome" is mechanically fine — `:ffi-call` already
does exactly that (park under a call id, append request, enter reader wait).
Note also that dao.stream itself never waits or parks ("No operation waits";
the engine owns the wait-set and polls), so a stream-shaped resolve-var would
have to be a park-and-poll of the *whole guest activation* per free-name
load, inside the engine's own scheduler round.
So the sync-within-one-instruction objection is weaker than the question
assumes. The real cost is semantic: an effect's outcome is a *fact about the
world* that any interpreter may supply; a register read's outcome is a
*machine invariant* that exactly one party may supply. Making the latter a
stream outcome turns execution into a negotiated trace and changes the step
granularity (`step` counts one guest instruction even when several host
transitions implement it; stream appends would each be observable steps).

## 3. Per-bullet answers

- **Sync services vs append-observe:** not decisive on its own (see above);
  decisive once combined with references and single-reader. Keep
  resolve-var/frame-materialization/GC on the exchange; never on the stream.
- **Machine-internal traffic as history:** no. It breaks GC, derive-don't-
  persist, observer ignorance, replay, and step accounting. Mailbox bytes are
  ephemeral by design and must stay so.
- **Value representation:** two representations are necessary, not
  accidental: in-machine (handles, sharing, capabilities, cycles via arena)
  and wire (canonical jing, content-hash identity, no capabilities). Any
  design has both; the only choice is *where* the projection happens. Current
  design: once, at the composition, per effect. Stream-at-the-seam: on every
  crossing, and impossible for primitives.
- **One `exchange` vs N typed imports:** this is not where doctrine lives.
  Given the signature constraint (i32 only), N imports just move the operation
  tag from the message into the import name. The one real benefit of N
  imports is a static capability manifest in the module's import section
  (which services this module may call, checkable at instantiation, as WASI
  does). That is worth having *as metadata*, but it should not be bought by
  giving the guest a direct stream handle. Keep one data-plane entry; if a
  manifest is wanted, emit it as a custom section the composition checks.
- **Load-bearing or accident?** Load-bearing, but **mis-explained**. The
  doctrine justifies the shim by wasm's limitation ("its only outgoing
  capability is an imported function"). That is the accident of starting
  from wasm. The real reason is shared custody, and the doctrine should say
  so, because the wasm-limitation argument invites exactly this question and
  also fails for the LLVM sibling, a JIT, or a worker thread, all of which
  are executor splits with no wasm limitation and still must not be streams.

## 4. What I would not build

- **Guest with direct stream-handle imports (append/read/park) for effects,
  exchange for the rest.** Two seams with mixed custody: a trust-domain peer
  for effects, a shared-heap child for values. Effect authorization
  (`check-callee-effect!`, ShiBi tokens, the rule that guest literal data
  never enters the trusted-constructor channel) lives in the composition; a
  direct append import lets guest bytes mint appends. Reject.
- **Interpreter loop in the host.** That is the existing CLJS/JVM reference
  engine; the wasm lane exists precisely to get compiled bodies. Reject as a
  non-answer.
- **Effect-only guest with no synchronous services, from a register image.**
  The guest would then have to own name resolution, store, closures,
  continuations and GC — i.e. it is the engine, not a lowered image. That is
  §5, and it should be named as such rather than treated as a variant of the
  body lane.

## 5. What I would build (recommendations)

1. **Keep `yin.vm.engine.host.v1`; restate its justification.** Amend
   datom.world.md Host Boundaries: "A *trust-domain* boundary is a stream
   boundary. An *executor* boundary inside one trust domain (a lowered body
   sharing the composition's heap) is a shared-custody protocol, never a
   stream: it carries references, is not history, has one reader, is
   collectable, and is versioned with the engine, not the system." Drop the
   wasm-limitation sentence as the primary reason; keep it as an example.
2. **Name the second product: node-lowering.** The intuition behind the
   question is right for a different artifact: the *whole engine* (the Rust
   kernel, §1.1) compiled to wasm, owning its heap, its jing codec, its GC,
   with the only import being a stream transport (bytes in / bytes out, which
   is a dao.stream handle operation — the one sanctioned function-shaped
   contact). That guest *is* a trust domain and its ABI *is* dao.stream. Body
   lowering (fast, shared heap, per-instruction services, this design) and
   node lowering (isolated, stream-only, portable across embedders without a
   composition) are both legitimate and should be two named lanes, not one
   argued over. Node-lowering needs no new ABI at all.
3. **Shrink the shim rather than stream-ify it.** The smell that provoked the
   question is real: every `:load-free` crosses the seam carrying a full
   machine snapshot, so the shim already looks like a bus. Reduce crossings
   by moving more *pure* work into the guest over W-gates (constant and
   plain-closure-payload minting, read-only store-get of immutable module
   stores, cached free-name resolution keyed by store revision), keeping
   anything that mints trusted values or can collect on the host. The right
   endpoint is a shim that is crossed for effects, GC and capability minting
   only.
4. **Secondary, lower confidence: one codec, not two.** The private TLV is a
   parallel wire format to jing CBOR with its own ordering rule. If jing's tag
   space can carry one private tag for arena references and one for trusted
   descriptors, the mailbox should be jing CBOR + those tags, with the
   private ordering rule retained. Fewer canonicalization rules to get wrong
   across CLJS and Rust. This is orthogonal to the stream question and I have
   not verified jing's tag budget.

**One-line answer.** The guest-host ABI is justified because the guest shares
the composition's heap and therefore traffics in references, which no stream
may carry; the doctrine should say that instead of blaming wasm, and the
"make it dao.stream" instinct should be redirected to a separate
whole-engine-in-wasm lane where it is correct.
