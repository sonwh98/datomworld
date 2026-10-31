Created-GMT: 2026-09-21 18:07:30 GMT
Created-Local: 2026-09-22 01:07:30 +07 (Indochina Time)
Coding-Agent: codex
Session-ID: 01a0bacb-3b91-7190-8412-3f1e85bb552a (resumed — your reference-by-hash turn)
# Task: architect follow-up — the reference-by-hash linker as a stream topology
Role: Architect
Implementers:
- Model: gpt-5.6-sol | Assigned: 2026-09-22 01:07:30 +07 | Status: active | Rationale: same architect continues the same subject (resume rule); the owner supplied a constraint from the project's axioms

Work in /Users/sto/workspace/datomworld. You MAY edit exactly ONE file:
`docs/design/yin.vm.debruijn-vm.md`, only as described below. Give the complete
answer now; do not wait for approval and do not promise one.

## The owner's constraint

You ruled that reference-by-hash is a later epic and added section 7.1. The
orchestrator described it as "essentially a distributed linker". The owner
replied: datom.world already has a solution to that in its axiom "everything is
a stream". Verified in docs/design/datom.world.md: axiom 1 "Everything is a
Stream: All IO and data flow through append-only streams"; axiom 4 "Everything is
a Continuation: ... serializable, portable continuations that can pause, travel
across streams, and resume anywhere"; the invariants forbid callbacks (every
callback is an event on a stream), hidden global state, shared mutable state, and
collapsing interpretation and execution; the Streams section says "No direct
function-to-function coupling without a stream boundary" and "Side effects must
appear as stream emissions".

The orchestrator's proposed reading, for you to accept, correct or reject:

- The linker is NOT VM machinery. It is a process between streams, exactly as
  the macro expander is a process on the syntax side of a medium boundary
  (docs/design/yin.vm.macro.md; the VM knows nothing about macros). The VM stays
  link-agnostic.
- A `:call-hash H` (your section 7.1 instruction) on a hash that is not loaded
  parks the continuation and causes a REQUEST emission on a stream; the
  definition arrives as datoms on another stream, is verified against its hash
  (`yin.vm.content`, Jing), loaded, and the continuation resumes. This would
  reuse what exists: `:vm/park` and resume, `dao.stream` blocked outcomes (a
  blocked input is an outcome, never hidden execution), and the dependency
  completion design's `:yin.k/requires` fixed point.
- A global mutable name registry is a poor fit under the invariants; the module
  registry is a VALUE carried in VM state. A name environment should likewise be
  a value or a stream, not a global.

## Answer

1. **Is the stream-topology framing sound** under the axioms, the invariants, and
   the merged code? Verify against src/cljc/yin/vm/semantic.cljc (park/resume),
   src/cljc/yin/vm/completion.cljc and docs/design/yin.vm.dependency-completion.md,
   docs/design/yin.vm.universal-continuation-format.md, and docs/design/
   dao.stream.md. Say precisely what already exists that this reuses, and what
   would be new (for example a request-emission convention, a definition-arrival
   consumer, the name environment as a stream). Correct anything in the
   proposed reading that is wrong.
2. **Which of the four open points each stream mechanism covers, and which it
   does not.** The four: (a) cycles and mutually recursive definitions, which
   still need strongly-connected-component identity; (b) authority, meaning which
   name environment or ledger is authoritative and how trust is expressed;
   (c) verification of fetched code; (d) failure semantics when a stream gaps or
   the definition never arrives (`:dao.stream/gap`, timeouts as stream events,
   no callbacks). For each: covered by streams, covered by Jing/content
   addressing, or a decision that remains, and what the decision is.
3. **Effect on this design's phases.** Does the stream-topology constraint change
   anything in B0-B6 (for example, must the VM already expose park-on-missing-
   code or a hash-addressed resume key so the future linker needs no VM change)?
   Say whether "no VM change later" is achievable and what the minimal
   provision now would be. Keep it minimal and additive.
4. **Edit section 7.1.** Integrate the ruling: the linker as a stream process, the
   `:call-hash` park/request/resume shape, which parts are new versus reused,
   the coverage of the four open points, and any provision required in B0-B6.
   Keep the section's existing completion criteria unless they are now wrong.
   Update the owner-decision entry in section 8 if the decision changes. Touch no
   other section unless a provision in B0-B6 is genuinely required; if so, name
   it and keep it additive. List every section changed.

Final response beginning exactly with:
Completed-GMT: <YYYY-MM-DD HH:MM:SS GMT>
Completed-Local: <YYYY-MM-DD HH:MM:SS local-timezone-name>
Coding-Agent: codex
Session-ID: 01a0bacb-3b91-7190-8412-3f1e85bb552a
then the four numbered sections, the list of sections changed, and any owner
decisions this raises.
