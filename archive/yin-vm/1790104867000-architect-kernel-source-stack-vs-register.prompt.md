Created-GMT: 2026-09-22 16:41:07 GMT
Created-Local: 2026-09-22 23:41:07 +07 (Indochina Time)
Coding-Agent: claude
Session-ID: 7e07b6da-0624-4b87-8533-378b43c3d302 (resumed: your targets design session)
# Task: architect-kernel-source-stack-vs-register — pick a side, with reasons
Role: Lead System Architect

Implementers:
- Model: claude-fable-5-1 | Assigned: 2026-09-22 23:41:07 +07 | Status: active | Rationale: owner wants your own considered choice, not a neutral comparison

Work in /Users/sto/workspace/datomworld (your launch directory; branch
master). This is an analysis task, not necessarily a document edit --
decide at the end whether the answer belongs in
docs/design/yin.vm.debruijn.targets.md and where, or whether it is too
speculative to record yet (the register kernel, R4, is gated behind a
benchmark and may never exist). You may edit that document if you
conclude it should be recorded; do not edit anything else.

## The question

Hypothetical: both the stack image (H, committed, three hosts) and the
register image (R, gated behind R1 then R4, not yet built) exist and are
validated. For a NEW Direction B foreign-host KERNEL (a thin interpreter
in a new target language -- Rust, Python, or a future one -- the Ribbit-
style approach this document already commits to for reach), which format
should it interpret: the stack image or the register image? Give your own
considered choice and the reasons for it. Do not present this neutrally;
the owner asked specifically for what you would choose and why.

## Material already on the table, for you to engage with, not defer to

The owner and the orchestrator discussed this before asking you. Their
reasoning, given here so you can agree, disagree, or extend it -- treat
it as a position to test, not a conclusion to ratify:

1. Ribbit precedent: the RVM (the project's own named inspiration for
   Direction B, section 1.1 of the current document) is itself a STACK
   machine, chosen by its author for portability across ~25 hosts, even
   though the same author's OTHER project (Gambit) does native codegen
   differently. If reach across many cheap ports is Direction B's actual
   goal here (as the document states), the precedent argues for whichever
   format has fewer moving parts per instruction to decode and dispatch,
   which a stack instruction generally does (implicit operand position,
   no destination-register bookkeeping) versus a register instruction
   (explicit destination plus source operands, a register file sized per
   body).
2. Consolidation: CLJ, CLJS, CLJD, and the T0 conformance kit are already
   built against the stack image. A Direction B host that targets the
   OTHER format costs the project a second validator, a second
   conformance kit, and a second place every future B1-shape change or
   Clojure-semantics primitive fix must be kept in sync -- versus every
   interpreter, first-party and foreign, sharing one format and one kit.
3. The performance argument for register-as-kernel is real (fewer stack
   shuffles per instruction, the same rationale as Lua 4 to Lua 5) but
   was argued to be aimed at the wrong lever: if a target genuinely wants
   execution speed, Direction A (native codegen, ALREADY established in
   this document as preferring the register image once it exists, since
   it has no implicit stack to simulate) is the mechanism built for that;
   asking Direction B's interpreter to chase speed duplicates that goal
   in the wrong place.
4. The one point that actually favors register-as-kernel: a foreign host
   that plans to ALSO build a Direction A emitter later could share more
   runtime code (value model, primitive table, decode logic) between its
   interpreter and its emitter if both start from the register image,
   mirroring the "T1 is basically T4's whole runtime" synergy this
   document already claims for Rust on the stack image. This is a bet on
   a specific host's future plans, not a general argument, and it trades
   away the conformance-kit consolidation of point 2.

## What to do

Weigh these (and anything else you judge relevant -- do not treat the
list above as exhaustive) and give a definite choice: stack, register, or
a conditional answer (e.g. "stack unless X, in which case register") --
but if conditional, state the condition precisely enough that it is
actually decidable later, not a vague "it depends."

Consider explicitly, since you have the actual documents open: does the
register design's own text (`docs/design/yin.vm.debruijn.register.md`
section 5, the R4 kernel's gating) or the stack design's B3 give you any
evidence the owner's/orchestrator's framing above missed? Does the
interpreter-versus-emitter distinction this document already draws
(kernels consume formats, emitters lower resolved tuples) have any
bearing on which format a kernel should prefer that was not stated above?

Begin the final response exactly with:
Completed-GMT: <YYYY-MM-DD HH:MM:SS GMT>
Completed-Local: <YYYY-MM-DD HH:MM:SS local-timezone-name>

Then: your choice, stated plainly in the first sentence; your reasoning;
which of the four points above you agree with, disagree with, or would
revise; whether and where this belongs in the document, or why it should
stay unrecorded for now.
