Created-GMT: 2026-10-02 20:00:00 GMT
Created-Local: 2026-10-03 03:00:00 +07 (+0700)
Coding-Agent: claude
Session-ID: 5face18f-26de-4829-962a-42049a75c13f

# Task: Float-address fix — preserve float64 kind at the producer, bridge the VM gates

Role: Yang Compiler and Universal AST Engineer

Implementers:
- Model: claude opus (opus-5.5) | Assigned: 2026-10-03 03:00:00 +07 (+0700) | Status: active | Rationale: the converged mob ruling is a precise implementation contract; the scalar-gate work sits next to the C3-S1 carrier recognition this engineer line landed

WORK TREE: work ONLY in /Users/sto/workspace/datomworld-py-floatfix (branch yang-python-floatfix, based on master
69e58662). Do not touch /Users/sto/workspace/datomworld, the datomworld-linker-* worktrees (another seat owns them),
or any other worktree. Do not stage or commit.

DESIGN (binding — the converged architect mob): read
collab/1790968830636-architect-float-address-mob.gpt-6-astra.findings.md (the ruling) and
collab/1790968830647-architect-float-address-mob.claude-fable-5-1.findings.md (the concurrence + three
corrections). Together they bind:

1. ROOT CAUSE: the codec conforms; the PRODUCER loses float kind before encoding. On JS an integral Number
   classifies as an integer (cbor.cljc:457 number path; int-wire at :1006; the float branch at :1012), and the
   quoted prelude carries bare 1.0 constants (e.g. prelude.cljc:89) while lower.cljc:279 wraps a RAW host number
   in {:py/float ...} — the Python wrapper does not fix the nested payload's Jing classification.
2. MECHANISM (no codec change): construct Jing's EXISTING float64 carrier (dao.jing/float64, cbor.cljc:494, tag 27)
   at every point where float kind is known and a value can reach addressed rows:
   a. Lowering: Python float literals build the carrier while literal syntax still identifies a float (never a
      bare integral JS Number).
   b. Prelude: every floating constant in the quoted prelude is marked/constructed explicitly BEFORE JS collapses
      it — scan the whole base + hook prelude; a later scan cannot recover the lost distinction. JS-safe literal
      discipline still applies (the (* 2 4503599627370496) pattern where needed).
   c. Runtime floats: any runtime float value entering an ADDRESSED IMAGE (snapshots, continuations, heap entries
      that get hashed) preserves the carrier — fable's correction 1.
3. EXECUTION BRIDGE (fable's correction 2): the scalar predicates/gates that must admit the float64 carrier are
   engine.cljc:632 AND the row and machine-payload gates at vm.cljc:962 and :981. Values preserve float kind
   through projection and decoding; when a value leaves addressed data into execution, bridge back to the native
   float explicitly, preserving kind both directions.
4. HARD CONSTRAINTS: dao.jing.cbor.cljc and its fixtures are NOT edited (fable correction 3); no new CBOR tag, no
   new row shape, no AST tag; keep tag-27 encoding, signed-zero preservation and NaN normalization exactly as
   landed; keep the equal-value/different-kind collection-collision refusals (dao.jing.cbor.md:177) unchanged; do
   not alias integer addresses to float addresses; already-correct JVM/Dart float payload bytes do not change, but
   a prelude change can move bundled-unit addresses — affected artifacts are re-minted from source.
5. ACCEPTANCE (astra's release gate, now implementable): for float-bearing inputs AND the full prelude,
   JVM/Node/Dart produce IDENTICAL canonical bytes and A/A' addresses; kind preserved through projection and
   decoding; integer/float identities distinct (1 vs 1.0 different addresses, per dao.jing.cbor.md:281); signed
   zero preserved; execution works across the four evaluators; the existing Jing fixtures unchanged.
   Add the cross-host byte/address tests (JVM vs Node vs Dart goldens over a float-bearing program and the full
   bundled prelude) that release the gate the mob imposed on C2-S5/C3-S2.

MECHANICS:
- Before any JVM lane: mise exec -- bb gen:python-antlr, then mise exec -- bb build:yin-repl-node. Lanes
  FOREGROUND, one at a time, waiting for each (a JVM lane can take ~45 min): mise exec -- bb test:clj,
  bb test:cljs, bb test:cljd; clj -M:kondo + cljstyle check on touched files. mise is trusted; node_modules
  installed. No background runs, no watchers: one turn, complete it.
- No background runs or watchers; if a lane cannot finish, say so in the report. Do not weaken tests.

Begin the final response exactly with:
Completed-GMT: <YYYY-MM-DD HH:MM:SS GMT>
Completed-Local: <YYYY-MM-DD HH:MM:SS +0700>

Report changed files, exact counts per lane, the cross-host golden results (byte-identical or not), unresolved
concerns, incomplete work.
