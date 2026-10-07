Created-GMT: 2026-09-21 18:29:29 GMT
Created-Local: 2026-09-22 01:29:29 +07 (Indochina Time)
Coding-Agent: codex
Session-ID: 01a0bacb-3b91-7190-8412-3f1e85bb552a (resumed — your design revision turn)
# Task: architect follow-up — Unison's runtime as prior art for the de Bruijn VM design
Role: Architect
Implementers:
- Model: gpt-5.6-sol | Assigned: 2026-09-22 01:29:29 +07 | Status: active | Rationale: same architect continues the same subject (resume rule); the owner asked for verified Unison runtime facts to be handed to the design

Work in /Users/sto/workspace/datomworld. You MAY edit exactly ONE file:
`docs/design/yin.vm.debruijn-vm.md`, and only as described in item 3. Give the
complete answer now; do not wait for approval and do not promise one.

## The owner's claim and what was verified

The owner said: "Unison is able to linearize de Bruijn projection as a bytecode
and that's what I want for yin.vm." The orchestrator checked Unison's own
sources. VERIFIED (primary source: the Unison repository,
https://raw.githubusercontent.com/unisonweb/unison/trunk/unison-runtime/src/Unison/Runtime/docs.markdown):

- The runtime lists these phases: let-rec minimization ("breaks up recursive
  blocks into a mix of `let` and minimally-sized `let rec` blocks"); lambda
  lifting ("eliminates free variables from any lambdas in the term, by turning
  them into ordinary function parameters"); ANF, which moves "function calls or
  ability requests to the body of a `let` or `let rec`"; an IR described as "a
  function that takes a stack of values and returns a result"; evaluation; and
  decompilation "back to displayable terms".
- Variables use De Bruijn indices in the IR phase: "the nearest / innermost bound
  variable has an index of 0, the next nearest has an index of 1"; variables live
  on a stack and "their position on the stack at each usage site is their De
  Bruijn index".
- The directory https://github.com/unisonweb/unison/tree/trunk/unison-runtime/src/Unison/Runtime
  contains ANF, MCode, Machine, Serialize, Decompile, Canonicalizer and others;
  a Unison issue tracker thread describes the runtime as a bytecode interpreter
  written in Haskell.
- Unison's identity form (hash of the syntax tree with names as De Bruijn
  indices; dependencies replaced by hashes; names as separate metadata) is
  documented at https://www.unison-lang.org/docs/the-big-idea/ and the meetup
  writeup https://unison-lang.org/whats-new/writeup-of-our-first-unison-meetup.

NOT verified, and the docs say so or are silent: whether the runtime input is the
exact stored/hashed term (the docs summary says "not stated"); how the runtime
resolves references to other definitions; whether docs.markdown is current (it
describes an "IR" stage while the directory also has MCode and Machine, so it may
lag the source); anything in MCode.hs or Machine.hs, which nobody has read.
Unison is typed, so 1 and 1.0 are different terms there; nothing in the sources
says its identity form drops execution-relevant information such as tail
position. Do NOT assert any Unison fact beyond this list; mark all else
UNVERIFIED.

## Answer

1. **Which decisions in your revised design does this prior art support,
   contradict, or leave open?** In particular: (a) architecture B (lower from the
   full-information named term with the de Bruijn resolved as stack positions)
   versus architecture A (lower from the lossy identity form). Unison's runtime
   consumes a typechecked term, not a separate lossy projection. Say whether that
   is real support for B or only an analogy, and why. (b) Whether Unison's
   pipeline steps (lambda lifting, let-rec minimization, ANF) suggest anything
   your design should adopt, defer, or explicitly reject. Note the named
   linearizer already emits a linear stack-oriented form; say what lambda lifting
   would change about closures and frame capture in this design and whether it
   belongs in scope. (c) Whether "decompile back to displayable terms" is the same
   thing as your invertibility invariant `decode(encode(ast))`, or different, and
   why.
2. **The honest parity statement**, three or four bullets, restricted to the
   verified list above. What can the design now truthfully say about how its
   executable form relates to Unison's runtime, and what must it not claim?
3. **Edit the design (minimal).** If, and only if, the answer warrants it, add a
   short "Prior art" note to the design (a paragraph or a small ASCII table)
   citing exactly the verified facts and URLs above, with the UNVERIFIED items
   labelled, and adjust any sentence in the design that overclaims parity. Do not
   restructure anything. If nothing needs changing, say so and edit nothing. List
   every section you changed.
4. **Owner decisions** this raises, if any.

Final response beginning exactly with:
Completed-GMT: <YYYY-MM-DD HH:MM:SS GMT>
Completed-Local: <YYYY-MM-DD HH:MM:SS local-timezone-name>
Coding-Agent: codex
Session-ID: 01a0bacb-3b91-7190-8412-3f1e85bb552a
then the four numbered sections and the list of sections changed.
