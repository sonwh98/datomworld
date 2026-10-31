Created-GMT: 2026-10-09 20:10:00 GMT
Created-Local: 2026-10-10 03:10:00 Asia/Bangkok

# Task: Round 4 -- numbering defined by procedure; shared children; FFI envelope; ordering; validation order

Role: Lead System Architect (continuing thread 01a121aa-4d3b-7bb1-90d7-eeb2b04e5d75)

All seven round-3 findings taken; §10.10 adopted as you specified; a
"proposals, not consequences" paragraph added to §10. New HEAD is one
commit on top of `d819f641`. Run `git diff HEAD~1 HEAD` and re-read §§3,
4, 5 (preamble, 5.1, 5.3, 5.4), 6, 8, 10 from disk.

Per finding:

1+2. §6 no longer claims the unrestricted minimum or that refinement
   classes are orbits; both counter-arguments are stated in the text.
   The canonical numbering is DEFINED as the output of the procedure:
   class encodings (structural, hash-free, full value-union tags),
   monotone refinement with the (n-k)+1 bound stated as a partition
   bound only, then exhaustive individualization over every member of
   the first non-singleton class with no pruning, leaf key = root Merkle
   bytes under that leaf's numbering, least key wins, equal keys
   immaterial by equal commitment under the digest assumption.
3. §3 gate uses an active recursion-path set; off-path revisits are
   legal (shared identical child rows); completed content validation
   cached per row; §8 step 12 restores one native task instance per
   install entry.
4. §4/§5.3: new closed `:ffi-envelope` row (call-id D, op, args as a
   `:vec` row or `[:lit []]`); `:ffi-request` carries `envelope-cref`;
   lower reconstructs without recomputing args; link envelopes stay D.
5. One ordering rule in the §5 preamble: canonical bytes of the key as
   spelled in the emitted row under the final numbering; skeletons are
   refinement-only; `:set`/`:map`/`:store` rows updated; SHA-256 pinned
   as the carrier's one digest (§3), no per-address dispatch.
6. §4: any row-backed collection with retained metadata refuses
   (labelled a proposed carrier limitation).
7. §8 reordered: non-recursive structure -> graph (reference
   completeness, active-path acyclicity, reachability, census) ->
   hashes -> recursive canonical form (now safe) -> numbering under the
   work bound; set-element uniqueness added.

Check, read-only:
A. §6 as now written: is the procedure fully specified (every input to
   every step fixed), deterministic, and renaming-invariant? Is the
   claim "leaf key = root Merkle bytes under that numbering; equal keys
   immaterial" sound as stated? Any remaining claim that outruns what
   the procedure establishes?
B. Findings 3-7: each does what was asked without a new conflict with
   UCF §7.2.1, §7.4.3 r5, §7.5, §7.6, amendment §§1, 2, 3, 6, 7, 8,
   `handoff.cljc`'s envelope handling, or the six invariants.
C. §8: is step 12 now justified as safe for every accepted input?
D. §10's "proposals, not consequences" list: complete?

Begin the final response exactly with:
Completed-GMT: <YYYY-MM-DD HH:MM:SS GMT>
Completed-Local: <YYYY-MM-DD HH:MM:SS Asia/Bangkok>

Then: severity | file:line | evidence | correction, for anything
remaining; the properties that passed; and end with one line:
VERDICT: READY_TO_MERGE | CHANGES_REQUESTED (merge publishes a Draft).
