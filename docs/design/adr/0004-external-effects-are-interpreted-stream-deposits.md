# ADR 0004: External Effects Are Stream Deposits Discharged by Interpreters; There Is No Ambient FFI

**Status:** Proposed (2026-10-16)

**Relates to:** `yin.vm.streams-all-the-way-down.md` §6.4 (the monad boundary);
`datom.world.md` invariant 3 and its Host Boundaries section;
`yang.antlr.md` §8.5.6 (the I6 refusal posture);
`agent.tzu.dao.stream.md` §`execute-tool-call` (the interpreter pattern, worked example).

## Context

The yang.python C4 track forces the question. Once the module system is
complete — static publishing, DHT delivery, cyclic imports, reload — what
stands between the guest language and real programs is all external surface:
files, stdio, sockets, processes, C libraries. The import machinery refusals
that ship with C4 (I6: no finders, no loaders, no `sys.path`, no dynamic
names, no `__import__` override) look, from the outside, like missing FFI.
The obvious completion is therefore a foreign-function interface bolted onto
the guest: let Python code name a host resource and call it.

That answer is wrong here, for reasons the project has already recorded
separately. The guest must never hold an ambient capability: content
addressing means a program's meaning is fixed by its bytes, and an ambient
call site makes meaning depend on whatever the host happens to have linked
that day — unpublishable, unreproducible, unreviewable. And a guest-side FFI
would be a *second* effect system standing beside streams, with its own
ordering, its own failure semantics, and its own interpreters — the same
duplication error ADR 0003 forbids for event media, moved from coordination
to effects.

## Decision

**Every external surface is a lowering to stream effects plus a host-side
interpreter holding the capability. The guest never names a host resource.**

Guest code — Python included — deposits effect requests as data on a stream
and reads results back as data. An interpreter, composed above the guest and
holding the capability the guest may not have, discharges the requests
against the world and deposits the outcomes. This applies uniformly to file
and stdio, sockets, process spawn, C library calls, and (already worked)
LLM tool calls. There is no ambient FFI, for any guest, in any language:
only bound surfaces, each with a lowering on the guest side and an
interpreter on the host side.

## Consequences

- **The post-C4 roadmap becomes a binding backlog, not an architecture
  project.** Each stdlib module or C surface is one slice: enumerate its
  calls, lower each to an effect schema, bind an interpreter, pin the
  behavior in tests. `os.open`-scale surfaces are small slices;
  `numpy`-scale ones are large. No new mechanism is designed per surface.

- **`pip`'s mechanism stays refused while `pip`'s payloads become
  admissible.** Content addressing replaces resolution — there is no
  installer, no path, no finder to bind. What a package *delivers* (code
  plus native deps) arrives instead as bound, interpreted surfaces, each
  reviewed on its own merits. I6's refusals are therefore permanent
  architecture, not temporary gaps.

- **Unbound surfaces fail loudly.** Until a surface is bound, its guest-side
  call sites lower to the existing refusal behavior (I6-style explicit
  `ImportError`/`NotImplementedError` naming the surface), never to silent
  absence or a stub that pretends success.

- **Effect logs are the test fixtures.** Per streams-all-the-way-down §7, a
  failing run's log is already the fixture format: bound-surface tests pin
  the deposited request sequence and replay the deposited outcomes, so no
  test depends on the world answering twice.

- **Rollback past a discharged effect needs compensation, not
  recomputation** (streams-all-the-way-down §6.4). Bindings for
  non-idempotent surfaces must state their compensation story in the slice
  spec; interpreters for purely observational surfaces need none.

## Open Questions

- Effect schema versioning: what happens to a published guest when its
  surface's effect schema gains a field.
- Capability attenuation for interpreters: which interpreter holds the raw
  capability versus a narrowed one, and where that narrowing is specified.
- Ordering and linearity: which surfaces guarantee request order, which
  guarantee at-most-once discharge, and how a guest observes the difference.
- Prelude versus later binding: which surfaces (if any) must exist before
  yang.python is declared usable, versus bound on demand.
- Whether `dlopen`-style dynamic loading is ever bound or permanently
  refused alongside the I6 loader refusals.

## References

- `docs/design/yin.vm.streams-all-the-way-down.md` (§6.4, §7)
- `docs/design/datom.world.md` (invariant 3, Host Boundaries)
- `docs/design/yang.antlr.md` (§8.5.6)
- `docs/design/agent.tzu.dao.stream.md` (`execute-tool-call`)
- [ADR 0003](0003-dao-space-is-the-event-medium.md) (the no-second-medium argument this ADR extends to effects)
