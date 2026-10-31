Created-GMT: 2026-09-21 21:11:22 GMT
Created-Local: 2026-09-22 04:11:22 +07 (Indochina Time)
Coding-Agent: codex
Session-ID: 01a0bacb-3b91-7190-8412-3f1e85bb552a (resumed — your dormant-projection turn)
# Task: fold in fable's architectural findings N1-N6; make the shortest path to the owner's invariant explicit
Role: Architect
Implementers:
- Model: gpt-5.6-sol | Assigned: 2026-09-22 04:11:22 +07 | Status: active | Rationale: you authored the design; fable-5-1's post-reset architectural check (SOUND WITH CHANGES, no P1, nothing blocks B0) found six P2 issues on the hash-sharing path. One batched turn (GPT weekly budget about 19 percent, resets 2026-09-23 20:50 +07); the orchestrator verifies by grep and commits afterwards.

Work in /Users/sto/workspace/datomworld. You MAY edit exactly ONE file:
`docs/design/yin.vm.debruijn-vm.md` (committed as a853dafa; this is a follow-up edit).
BE ECONOMICAL: read fable's review and the design only. Give the complete answer now;
do not wait for approval and do not promise one.

## Input

collab/1790024873260-architect-debruijn-vm-design-fable-r2.claude-fable-5-1.findings.md
(read it in full; verify each claim against the design). The owner's invariant
(verbatim): "I want de Bruijn projection so that a yin.vm can easily share code over
dao.stream linker." The team (orchestrator) proposes these rulings; ADOPT, MODIFY or
REJECT each with a reason, then edit:

- **N1 (P2) B6 must not wait for the frame VM.** Invariant I needs only B1 (`image-hash`),
  B2 (lowering) and the declared lift; a receiver can run a fetched image as
  `lift(image, synthesized names)` on the EXISTING semantic VM. Make the dependency
  graph explicit: B6 depends on B1, B2 and the lift, NOT on B3 or B4; B6 may complete
  against the semantic VM via the lift, and its "equal normalized results" test compares
  that execution with the original. Recommend the working order B0, B1, B2, B6, then
  B3-B5, then B7 (keep the numbering; state the order and the dependencies). In section 1
  Benefit say honestly that the frame VM's own benefit is positional lookup performance
  only and is unmeasured, and that sharing does not need it. Update the compliance row.
- **N2 (P2) define "closed" against the receiver's resolve order.** Today `:load-free`
  resolves through free-env, store, primitives and the module registry, so `+` is free
  and "no `:load-free` operands" excludes nearly every program; worse, a receiver whose
  store or free-env has a key equal to a primitive's name gives the SAME H a different
  meaning. Define a receiver-side CLOSURE CHECK, done before execution: the free-name
  set is DERIVED by scanning the image's `:load-free` operands (no manifest field); the
  image is accepted only if every free name resolves in the receiver's primitive or
  module table and NONE is shadowed by the receiver's free-env or store; otherwise it
  is REFUSED with a qualified outcome (name a distinct rule, for example
  `:unresolved-free` and `:shadowed-free`). Change "may refuse" to "refuses unless".
  Say what "closed" now means in D11 and in section 1 (closed = passes the check
  against a given receiver; an image with no `:load-free` operands passes trivially).
- **N3 (P2) the side table is outside H, so it is unverified but the lift consumes it.**
  Any lift whose output is executed or fed to completion uses SYNTHESIZED binder names
  that are fresh against the image's free-name set; a supplied side table is accepted
  only if `adapt(lift(image, table)) = image` (round-trip check). Fix B2's garbled
  sentence ("With synthesized names, the equality holds ..." should say "With the
  side table ...").
- **N4 (P2) verify the wire bytes, not a re-encoding.** Define verification as hashing
  the RECEIVED canonical bytes before decoding, so the wire bytes are exactly H's
  preimage. State this in section 2 and B6. Declare integral-valued doubles OUTSIDE the
  common scalar domain for CLJS (a CLJS receiver refuses an image containing a float64
  whose value is integral, with the unsupported-class outcome, since JS cannot tell it
  from a long); state the rule and the B5 consequence (the same-H-on-every-host lane
  applies to programs whose scalars are in the common domain).
- **N5 (P2) H stability.** The descriptor carries a LOWERING-CONTRACT VERSION; B1 pins
  GOLDEN H fixtures (frozen image bytes and hashes for a small corpus, checked by all
  three host lanes) so a change to `lower`'s layout fails a test instead of forking
  identity. Also settle the internal contradiction: section 2 says the descriptor HASH
  is inside H while D9 says the descriptor BYTES; choose ONE (recommend the descriptor
  hash, as the projection and datom.md do) and make sections 2, 3 and D9 agree.
- **N6 (P2) the serving side of B6.** State that a responder holds an explicit VALUE
  mapping H to image bytes, or an H-to-address datom it queries (the composition
  chooses), and add the responder process to B6's file box and completion criteria.
- **P3s**: section 3 "The projected reader's existing tuple-shape check remains useful"
  is stale (no projected reader is on this path; remove or reword); section 7.1 says
  "name-table entries", should say "side table"; B6 mentions `:call-hash` but nothing
  emits one before B7, so say B6 tests use explicit fetch; B0's completion criteria can
  only be self-parity and idempotence of the normalizer on the named VM (no second VM
  exists yet), say so; state whether committed golden bytes satisfy B5's cross-runtime
  stream transfer or a cross-process fixture is required (recommend golden bytes for B5
  and a real cross-process transfer only in B6); the private-helpers wording ("rules are
  reproduced or exposed" beside "Existing edits: none") must pick one; B1's "mirrored
  opcode table" should say "derived opcode table"; the section 4 state map should say
  "plus the semantic VM's non-environment registers, unchanged".

## Do

1. Make the edits; keep the conventions (numbered sections, ASCII box tables kept
   ALIGNED (every row the same length), no em dashes, 80 columns, status "design; not
   implemented", no operational routing text).
2. Section 8: record the rulings as DECIDED (D13 and following, one line of rationale
   each and the invariant each serves); keep DEFERRED honest.
3. **Your sign-off**: READY or NOT READY to commit and to begin B0; and one line on
   whether B1 can freeze `image-hash` now.
4. Report briefly: dispositions of N1-N6 and the P3s, sections changed, anything that
   needs the owner.

Final response beginning exactly with:
Completed-GMT: <YYYY-MM-DD HH:MM:SS GMT>
Completed-Local: <YYYY-MM-DD HH:MM:SS local-timezone-name>
Coding-Agent: codex
Session-ID: 01a0bacb-3b91-7190-8412-3f1e85bb552a
then: the sign-off line, the N1-N6 and P3 dispositions, sections changed, and anything
needing the owner.
