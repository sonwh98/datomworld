Created-GMT: 2026-10-20 15:10:00 GMT
Created-Local: 2026-10-20 22:10:00 +07
Coding-Agent: claude
Model: claude-fable-5-1
Session-ID: 36134bec-9cbd-4852-b78c-4901af66fd02
Task: host-boundary design r3, scope narrowed to the rule plus H0-H4
Role: Architect
Deliverable: docs/design/dao.stream.host-boundary.md revised in place (r3); no src edits, nothing staged.
Labels: V verified by reading, R computed by script or read, P proposed. No lane run. Non-Claude sign-off of the narrowed scope required.

# Rationale

**Scope.** Per "yes, proceed with the split", the commissionable scope is §2
plus H0-H4. H5-H8 form a new §9 "DEFERRED scope", not commissionable, with
their r2 intent kept and astra's R2, R5, R6 and A7 recorded as required
inputs for a separate design, proposed as `dao.stream.host-effects.md`. Slice
ids are reserved so no citation moves.

**R1.** §2.1 defines the machine (the instance and the runtime state it owns)
against the world; §2.3's properties are stated with respect to the world, so
a weak-reference read is owned state, neither IO nor a profile entry, and the
r2 contradiction is withdrawn. §2.4 gives ownership and mutation laws: entries
are P or M; M entries mutate exactly the named owned argument; `slice`,
`asub`, `aclone`, `from-vector` are fresh; `copy!`/`acopy` are memmove;
ownership transfers to a seam on handoff. §12 is relabelled an owner proposal
with the owner's words marked and the rest marked proposed.

**R3.** A script walked `ns` forms from `yin.repl.main` per lane: 141 JVM,
140 Node, 139 Dart namespaces, 133 shared; lane-only members listed; labelled
R with its limits. Every namespace with host contact is classified with a
slice or a deferral. Corrections: 27 `random-uuid` lines, 26 in scope;
`coordinate.cljc:80` selects the JVM blocking driver (clock, sleep), deferred;
`sign.cljc:75-87` is deterministic seed replay and moves in H3 while
`generate` stays deferred; regex sites are ASCII grammars.

**R4, A4-A6.** Table A is 76 entries with a new `dao.host.number` (10, from
`cbor.cljc:483-632`, carriers moving with them); `debruijn_code.cljc:232-242,
315` assigned to H2; table C corrects the B-tree default to `:soft` on the
JVM. Table D is the per-entry oracle matrix with file and deftest names;
counts corrected (integer 25, sign 4, base64 3 existing). H2's gate: the grep
lists exactly the shim's own file before deletion, nothing after. H0's final
list 1 names the retained arms, so the gate promises "no arms outside list
1", not "none in src/cljc".

**Disjointness (V).** H0-H4 source and test files are listed against the
codec track's; intersection empty with one deferred one-line edit
(`codec.cljc:45`). The host-lane probe track's narrow source adaptations
touch exactly the H1-H4 files: paused under OQ3, file-level coordination
needed. Cost and risk restated for H0-H4; base64 and the writer are
replacements, not code motion.

# Disposition

+-----+---------------+----------------------------------------------------------------------------------------------------------+
| Id  | Disposition   | Where in r3                                                                                              |
+=====+===============+==========================================================================================================+
| R1  | accepted      | §2.1, §2.3, §2.4, §2.5 (weak refs); §12 owner proposal.                                                  |
+-----+---------------+----------------------------------------------------------------------------------------------------------+
| R2  | deferred      | §9.1 required inputs: allocation, resumable consumption, partial batches, bootstrap identity, UUID       |
|     |               | spelling, DHT synchronous draws, completions as data.                                                    |
+-----+---------------+----------------------------------------------------------------------------------------------------------+
| R3  | accepted      | §3.1 per-lane closure and classification; §3.2 counts and sites; §2.5 corrections.                       |
+-----+---------------+----------------------------------------------------------------------------------------------------------+
| R4  | accepted      | §4.2 table A and C; H2 assignment of `debruijn_code`; H0 list 1 reconciled; H2 greps.                    |
+-----+---------------+----------------------------------------------------------------------------------------------------------+
| R5  | deferred      | §9.2: pre-rename failure, post-rename visibility, sync uncertainty (`fs.cljc:352-387`).                   |
+-----+---------------+----------------------------------------------------------------------------------------------------------+
| R6  | modified      | §8.1 source and test allocation and the codec-test dependency; §9.1 H5 re-estimate and UCF migrations;   |
|     |               | §10 cost and risk for H0-H4.                                                                             |
+-----+---------------+----------------------------------------------------------------------------------------------------------+
| A4  | accepted      | Table A complete for H0-H4; table C with slices or "none".                                               |
+-----+---------------+----------------------------------------------------------------------------------------------------------+
| A5  | accepted      | Table D matrix; P suites named; corpus errata preserved in H4.                                           |
+-----+---------------+----------------------------------------------------------------------------------------------------------+
| A6  | accepted      | H2 consumer list; one-file grep before, empty after; H1 direct tests.                                    |
+-----+---------------+----------------------------------------------------------------------------------------------------------+
| A7  | deferred      | §9.2 lower operations and outcomes as required inputs.                                                   |
+-----+---------------+----------------------------------------------------------------------------------------------------------+

Not verified: no lane run; the closure is from require forms, not a compiler;
H4 performance open until measured.
