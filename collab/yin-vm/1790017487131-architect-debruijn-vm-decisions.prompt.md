Created-GMT: 2026-09-21 19:04:47 GMT
Created-Local: 2026-09-22 02:04:47 +07 (Indochina Time)
Coding-Agent: codex
Session-ID: 01a0bacb-3b91-7190-8412-3f1e85bb552a (resumed — your final revision turn)
# Task: rule on every outstanding owner decision under the owner's invariant and datom.world's invariants
Role: Architect
Implementers:
- Model: gpt-5.6-sol | Assigned: 2026-09-22 02:04:47 +07 | Status: active | Rationale: the owner delegated the outstanding decisions to the orchestrator and the team; you are the team's architect and author of the design. One batched turn (GPT weekly budget about 20 percent, resets 2026-09-23 20:44 +07). Independent reviews by other model families (fable-5-1, qwen, deepseek) follow your turn.

Work in /Users/sto/workspace/datomworld. You MAY edit exactly ONE file:
`docs/design/yin.vm.debruijn-vm.md`. BE ECONOMICAL: read the design and
docs/design/datom.world.md only (plus anything you must verify). Give the complete
answer now; do not wait for approval and do not promise one.

## The governing invariant (owner, 2026-09-22, verbatim)

"I want de Bruijn projection so that a yin.vm can easily share code over
dao.stream linker."

The owner does NOT want to answer the individual open design questions; they
delegated them to the team with two constraints: (1) stay inside the non-negotiable
invariants of docs/design/datom.world.md (no hidden global state, no implicit
control flow, no callbacks, no shared mutable state, do not collapse interpretation
and execution, no assumed graphs; "derive, don't persist"; host boundaries are
stream boundaries; interpretation creates semantics) and (2) serve the invariant
above. Reading of it, for you to confirm or correct: the LOSSY projection (records
with canonical scalars, no tail flags, no binder names) is the alpha-equivalence
IDENTITY layer; what a yin.vm executes and shares is the EXECUTABLE IMAGE derived
from the named datoms with the same de Bruijn resolution, whose identity is
alpha-invariant for binders (binder names outside its hash), so equivalent programs
share one content address that any yin.vm can obtain, verify and run through the
stream linker (a process between streams; local or remote is just a stream). If
you believe the invariant requires the projection RECORDS themselves to be the
shared code, say so and what that would cost (reviewers already showed the records
cannot execute exactly: `1.0` becomes `1`, NFC, no tail flags, no parameter names).

## My proposed decisions (the orchestrator's; rule on each: ADOPT, MODIFY, or REJECT with reason, and check each against every datom.world invariant and the owner invariant)

- **D1 normalizer (B0 blocker).** Adopt: closures compare as `{:type :closure
  :arity n}`; continuations and parked records by type only; stream and cursor refs
  by id; the store normalized (no host handles); telemetry excluded; errors by
  message plus ex-data with the value normalizer applied recursively.
- **D2 optional lossless DAG (section 7.3).** DROP: do not build it in B0-B5, make
  it a non-goal. Rationale: datom.world "Derive, don't persist" (a second
  structure with authority over facts the named datoms and `resolve-name` already
  give), fable's finding that no consumer reads it, and the owner invariant needs
  sharing of EXECUTABLE code, not an inverse view. Named datoms stay the lossless
  truth; binder names and provenance live in the diagnostic side table. This
  removes the tempid-identity and non-`:yin`-attribute questions.
- **D3 scalar domain and descriptor.** Approve the descriptor (arity, slots, encoding,
  lift to `:yin.code/*`, exact-spelling slots typed raw Bytes). The cross-host
  SHAREABLE domain is the common scalar domain; the image records the scalar classes
  it uses so a receiving host that lacks a class (ratio, char, bigint) REFUSES it
  before execution with a qualified unsupported outcome, which datom.world's Host
  Boundaries section calls a correct outcome, not a gap.
- **D4 named-VM environment leak (B5).** It is a defect in the NAMED VM (state
  carried between runs violates "no hidden global state"); fix it as a separate,
  small change OUTSIDE the B-phases; until then keep the stated fixture restriction.
  This design does not change the semantic VM (its section 9).
- **D5 what `:call-hash H` identifies.** H is the content hash of the canonical
  executable image (canonical positional vector plus descriptor hash) of a definition
  UNIT; a mutually recursive component is hashed as one unit; the projection
  fingerprint is never H.
- **D6 linker authority, trust, cycles, failure.** (a) The name environment is a
  VALUE or STREAM supplied by the composition, never a global registry (invariant:
  no hidden global state); (b) trust and provenance are composition policy, and the
  receiver ALWAYS verifies content against H before loading (the address is the
  proof of integrity; authority only decides which name maps to which H); (c)
  strongly connected components are hashed as a unit with members ordered by their
  canonical image hashes; (d) retry, timeout and permanent absence are STREAM EVENTS
  (host timers append events; the VM has no timer or callback), with a terminal
  qualified `:absent` outcome after a composition-chosen bound; the VM only parks and
  emits a request. These are decided as PRINCIPLES now; their mechanics belong to a
  future B6 design and must not block B0-B5.
- **D7 end state after B3.** Both VMs coexist; no default flip is decided in this
  design; the semantic VM is never retired without its own design; the benchmark
  report is informational.

## Also do

1. **State the owner's invariant** at the top of section 1 as design invariant I,
   and add a COMPLIANCE TABLE (ASCII box) mapping each datom.world non-negotiable
   invariant and design principle, and invariant I, to the mechanism in this design
   that satisfies it (or "strained: ..." with the section). Be honest about strains
   (frame environment, loaded-image state, the diagnostic side table).
2. **Close the gap the orchestrator found**: the benefit section names the network
   benefit but B5 has no test of it, and B5 says alpha-equivalent programs' images
   "may differ" (true only for spelling, tail flags and free names, not for binder
   names). Add to B5's completion criteria: programs that differ only in binder
   names produce the same image hash on every host lane for the common scalar domain,
   while programs differing in exact scalar spelling, front-end tail flags or free
   names produce different hashes; and an image sent over a `dao.stream` from one
   lane loads, validates and executes on another lane with the same normalized
   results as local execution (the verified-by-hash receive path). Rewrite the
   "may differ" sentence accordingly.
3. **Rewrite section 8** into: DECIDED (D1-D7 with one-line rationale and the
   invariant each serves), then DEFERRED (only things that are truly undecidable now,
   e.g. B6 mechanics), and remove decisions that no longer need the owner. B0 must
   no longer be blocked by any owner decision; if you believe something still blocks
   it, say exactly what and why.
4. Keep the document's conventions (numbered sections, ASCII box tables, no em
   dashes, 80 columns, status "design; not implemented", no routing text). Update
   sections 1, 6 (B0 title and criteria if D2 changes them), 7.3, 7.2, 8 as needed
   and remove or reduce anything that depended on the DAG.
5. **Your sign-off as author**: READY or NOT READY to commit as a design document and
   to begin B0.

Final response beginning exactly with:
Completed-GMT: <YYYY-MM-DD HH:MM:SS GMT>
Completed-Local: <YYYY-MM-DD HH:MM:SS local-timezone-name>
Coding-Agent: codex
Session-ID: 01a0bacb-3b91-7190-8412-3f1e85bb552a
then: the sign-off line; a per-decision ruling table D1-D7 (ADOPT/MODIFY/REJECT with
reason); your reading of the invariant (confirmed or corrected); the compliance
table's strained rows; sections changed; anything still needing the owner.
