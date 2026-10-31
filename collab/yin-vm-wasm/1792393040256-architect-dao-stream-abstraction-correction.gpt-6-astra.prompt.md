Created-GMT: 2026-10-19 06:57:20 GMT
Created-Local: 2026-10-19 13:57:20 +07

OWNER CLARIFICATION (2026-10-19, verbatim — a category correction to the wording
you just wrote in the restructure):

"dao.stream is an abstraction boundary. It is not a transport implementation.
It can be implemented using a ring-buffer, a websocket or UDP"

Your restructure landed (the five files, supersessions, node-lane W0–W6 — good).
But the framing vocabulary you used is wrong in category: you (and the brief you
followed) called the wasm adapter surface a "transport binding" of dao.stream,
with "the same status as the ws/UDP bindings." The owner's correction:

- dao.stream is an ABSTRACTION BOUNDARY — the contract (operations, exhaustive
  outcome maps, canonical values, append-only batch semantics, observer
  discipline). It is not a transport and not a wire protocol.
- Ring-buffer, websocket, and UDP are IMPLEMENTATIONS of that abstraction — not
  "transport bindings" of it. A ring-buffer is not a transport at all, which is
  what breaks the "transport" framing.
- Therefore the wasm import surface (the adapters) is one more IMPLEMENTATION of
  the dao.stream abstraction — the one that exists because wasm's only door is
  imported functions over linear memory. The guest's stream operations can also
  be serviced by an in-memory (ring-buffer) implementation inside the instance
  with zero host crossings when the producer is co-resident — the abstraction's
  implementation-agnosticism is what permits that.

Apply this correction across the tree (dated owner-clarification note, folding
into the existing 2026-10-19 ruling notes):

1. docs/design/datom.world.md — Host Boundaries: record the taxonomy verbatim in
   the owner's words (abstraction boundary; implementations = ring-buffer,
   websocket, UDP, wasm import surface). Replace any "transport binding of the
   ABI"/"same status as ws/UDP bindings" phrasing.
2. docs/design/yin.vm.engine.md — §E.3: retitle/reframe from "the dao.stream
   wasm binding" to the wasm IMPLEMENTATION of the dao.stream abstraction
   (adapters implement the abstraction's operations; the section specifies this
   implementation). Same for any "binding" language elsewhere in §E.
3. docs/design/yang.wasm.md — the target-interface section: the module imports
   an implementation of dao.stream (the wasm import-surface implementation
   specified in engine.md §E.3); fix "binding" phrasing.
4. Sweep all five files for the words "transport binding", "binding of the ABI",
   and any framing that makes dao.stream a wire protocol layered on transports;
   replace with the abstraction/implementation taxonomy. (Legitimate residual
   uses of "binding": the compiler's "target-interface binding" of a module to
   its imports — that is a different, correct sense; keep those.)
5. Also check the one-ABI ruling paragraph: it should now read cleanly as "one
   abstraction boundary (dao.stream), many implementations (ring-buffer,
   websocket, UDP, wasm imports)" — no second-ABI vocabulary anywhere.

Constraints: wording/framing correction only — no structural change (the
adapter shape, outcome maps, jing bytes, W-schedule are all correct as you built
them). No src/ or test/ changes. rust-kernel.md coordinates 1–1334 unshifted.
Reply with: each replacement made (old phrase → new phrase), per file, and
confirmation the sweep found no residual category errors.
