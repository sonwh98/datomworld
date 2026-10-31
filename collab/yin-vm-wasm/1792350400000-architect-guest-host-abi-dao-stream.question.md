# Design question (opinion requested; read-only — do not edit any file; reply with your analysis)

You are one of four independent architect models asked the same question. Answer
independently; do not hedge toward consensus.

Context: datom.world is a datom/log architecture in which all IO and agent
communication flows through `dao.stream`: effects are appended as canonical values,
correlation is by identity in the data, blocked effects park and are resumed with
plain data, there are no callbacks, and any number of interpreters may observe the
same stream (no privileged reader; the medium's only coupling is the stream
itself). The system's stated doctrine: a host boundary is a stream boundary,
never a function call; `dao.stream` is the system ABI — the only contract that
crosses a trust domain.

Separately, the project has designed a WebAssembly lane: register VM bytecode
images are lowered to wasm modules. A wasm module cannot express a stream
operation — its only outgoing capability is an imported function. The current
design gives the guest a process-local service ABI, `yin.vm.engine.host.v1`: a
single imported function `exchange(control_ptr) -> i32` over linear memory, with
a byte-framed request/outcome mailbox (TLV framing, transport statuses, a
NEED_SPACE resize-and-refetch retry, canonical-bytes ordering rules). The host
side — a CLJS adapter or a Rust service library — implements the protocol and
translates guest service requests into real operations, including `dao.stream`
appends for the guest's effects. Doctrine names this a shim: the guest's effects
do reach `dao.stream`, via the composition.

The question: **Why should there be a different ABI at all? Why can't the
guest-host interface also be dao.stream?** Concretely: typed imports mirroring
stream operations (append/read/park) instead of a separate engine service ABI —
or more radical reshapings (an effect-only guest with no synchronous services at
all; moving the interpreter loop into the host; anything you consider better).

Consider, at minimum (and anything else you find decisive):
- Synchronous service needs within one guest instruction (resolve-var, frame
  materialization for GC) versus dao.stream's append-observe model and its
  no-privileged-reader rule.
- Whether machine-internal traffic (register reads, root materialization) should
  become stream history, and what that does to the effects/observation layer.
- Value representation: in-machine fidelity (handles, machine records, private
  encodings) versus canonical wire bytes (jing CBOR) — does the representation
  problem survive any transport redesign?
- Import-surface design: one generic `exchange` versus N typed imports; host-call
  signature constraints (no i64/externref/host types in wasm host calls).
- Whether the shim is load-bearing doctrine or an accident of starting from wasm.

Give your position with reasoning, including where you disagree with the framing
of the question itself. State clearly: is the separate guest-host ABI justified,
and if so what is the one indispensable reason; if not, what would you build
instead?
