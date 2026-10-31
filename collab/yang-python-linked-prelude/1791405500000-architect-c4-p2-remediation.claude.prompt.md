Created-GMT: 2026-10-08 07:35:00 GMT
Created-Local: 2026-10-08 14:35:00 ICT

You are the Lead System Architect for datom.world.
Model: Claude Fable 5.1.
Repository: /Users/sto/workspace/datomworld-p1
Branch: yang-python-c4-p2 (based on master @ 66756d20)

Read the Implementation Engineer's findings from Step 0 of Track A Phase C4 Slice P2:
`collab/1791403000000-engineer-c4-p2.claude-opus-5-5.findings.md`
And review your original spec:
`collab/1791403000000-architect-c4-p2-spec.claude-fable-5-1.findings.md`

The engineer adhered strictly to §5 Rules ("If step 0 or the linked corpus fails on any VM, stop and report with the output; the design decides") and uncovered two fundamental, empirical architectural findings when attempting to publish the prelude `py` module:

1. Finding F1 (Bounds):
   `publish-module!` refuses the `py` module with `:yin.link.publish/incomplete-closure` because the closure walk of its own manifest hits `:max-parts` (`linker/default-bounds` is 4096). The wide tree has 5648 rows; the chain tree has 6322 rows.

2. Finding F2 (Scanner performance / Datalog complexity on large row graphs):
   When `:max-parts` was temporarily raised to verify D5, `publish-module!` took 2750 seconds (46 minutes) on JVM! Profiling/stack-sampling revealed 97% of the execution time was in `yin.vm.linker/tree-free-name-occurrences` (which runs a `dao.space.query` Datalog query with a `not` clause over all 5648 tree rows via `verify` in `checked-derivation` for every format). Each `link-local` took 8 to 15 minutes!

Notice that the core mathematical/layout claim of D5 held: with bound raised, wide layout links `:ok` on the three vector formats and retains only the 14 declared primitives and 40 host exports.

As Lead System Architect, address these findings and deliver an Architectural Ruling / Revised Specification:
1. Provide decisions and guidance on F1 (`:max-parts` and module sizing/bounds).
2. Provide decisions and guidance on F2 (`tree-free-name-occurrences` scanner performance vs recursive tree walk or indexer optimization vs modular decomposition).
3. Determine how Track A Phase C4 Slice P2 should be structured, split, or modified to maintain our non-negotiable invariants:
   - Green multi-host test suites (JVM, Node, Dart) within standard test timeouts.
   - Clean architectural boundaries.
   - Strictly follow format.md (no em dashes, no first-person pronouns, github-style links).

Write your findings to:
`collab/1791405500000-architect-c4-p2-remediation.claude-fable-5-1.findings.md`
