Created-GMT: 2026-10-09 20:45:00 GMT
Created-Local: 2026-10-10 03:45:00 Asia/Bangkok

# Task: Round 5 -- byte grammar for the numbering procedure; envelope, gate, digest fixes

Role: Lead System Architect (continuing thread 01a121aa-4d3b-7bb1-90d7-eeb2b04e5d75)

All six round-4 findings taken. New HEAD is one commit on top of
`7e24fa3a`. Run `git diff HEAD~1 HEAD` and re-read §§3, 4, 5.3, 6, 8
(step 1, step 6), 10 from disk.

Per finding:

1+2. The closed `:ffi-envelope` row is gone. `:ffi-request` carries
   `envelope-V`: the COMPLETE envelope map recursively encoded under §4
   (`[:lit m]` when reference-free, a `:map` row otherwise), exactly as
   `handoff.cljc` carries it; unknown keys preserved; args `[1 2]` are
   simply part of the literal map. §10 states that preserving the
   envelope is inherited behaviour, not a proposal.
3. §8 step 1 now uses the active-path rule; off-path revisits are gated
   once and cached.
4. §6 is a byte grammar: synthetic vector structures only (no host
   maps/sets, multiplicity retained), canonical CBOR throughout, initial
   label `[:body stream position]`, class encoding CE defined per value
   tag and per row kind with sorted-vector encoding for unordered rows,
   context lookups unexpanded, install children numbered first and
   appearing as `[:child <root id>]` (task boundary), positions as
   step vectors with `:elem`/`:key`/`:val`/`:frame` discriminants,
   signature `[:sig old-label sorted-positions]`, individualization
   label `[:ind depth old-label]` (distinct by tag; the "ordered before
   all others" requirement is withdrawn as unnecessary), leaf key = root
   BODY bytes (digest preimage), zero-cell case stated.
5. Validation is by re-emission: decode to the abstract task, run the
   procedure, re-emit, compare root ids. No bijection is compared; every
   labeling yielding the selected commitment passes.
6. Row id spelled `segment-key body {:algorithm :sha256}`; the claimed
   equivalence with code addresses removed; code addresses keep
   `ucf/code-address`; digest pin added to §10's proposals.

Check, read-only:
A. §6: is every input to every step now fixed well enough to be a
   reproducible byte contract? Is CE's treatment of install children
   (`[:child <root id>]`, children numbered first) consistent with
   task-local cells and with §8's order? Any remaining claim that
   outruns the procedure?
B. §5 validation by re-emission: sound, and consistent with the
   fresh-receiver round-trip law in §9?
C. Findings 1-3, 6: done as asked, no new conflict.
D. Is step 12 now justified as safe for every accepted input?

Begin the final response exactly with:
Completed-GMT: <YYYY-MM-DD HH:MM:SS GMT>
Completed-Local: <YYYY-MM-DD HH:MM:SS Asia/Bangkok>

Then: severity | file:line | evidence | correction, for anything
remaining; the properties that passed; and end with one line:
VERDICT: READY_TO_MERGE | CHANGES_REQUESTED (merge publishes a Draft).
