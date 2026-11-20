# D1 r13 — static C1 continuation completed; declaration remains open

Worktree mirror; no commit. The existing r12 progress driver was resumed from
20/155 with the documented command. It exited normally, and the saved EDN has
**155 passes, zero refusals, zero not-run**, all marked `:pre-declaration true`.
Its SHA-256 is `2cfca729308e1544ccc1c60ccce768ef8f52950816ddf0767132352dafd50b4b`.
Outcomes include the retained admission origin and both individual samples.
All 155 pairs have equal code roots and marker addresses. This is a static
comparison result, not a D1 verification result.

The continuation opened the retained store in 281,890 ms and sampled
3,944,340,192 bytes process used heap at 5 ms. Across the combined progress
file maximum markers and marker CBOR bytes were 12,085 and 6,493,504;
independent per-phase maxima and the measurement limitations are recorded in
the r13 supplement to `1794671000001-d1-bounds-declaration-draft.md`.
The final sample covers this continuation only: the earlier interrupted batch
has no final sampled peak. A sample is not a hard heap or RSS bound.

The comparison uses the same retained tree in separate baseline and C1
in-memory stores, reconstructs and checks markers and queries the resulting
relation. Its repeated call shares the JVM and stores. It neither executes a
guest nor produces semantic instruction provenance; relation construction is
not publication of an index. Therefore it does not complete the design's B/S/T
experiment or establish instruction/trace provenance or zero guest-instruction
change. Nor do these pilot numbers complete the claimed executable recipe.

**Remaining:** implement and measure the actual same-input B/S/T walker and
semantic configurations, including semantic instruction provenance production,
persistence, fetch, query, independent verification and the T trace/attribution
closure; collect the §6.1/6.2 phase, byte, capacity, memory and per-lane cost
samples and distinguish cache/repetition conditions. Retain and independently
verify the source/environment and recipe/image/derivation closure. Complete and
red-first test live quantity enforcement plus per-fixture process time/memory
supervision. Then derive every declaration ceiling from the measured maxima,
freeze the complete laptop-scoped declaration, and only afterward run both C
lanes and scoped attribution under it, preserving each failure without raising
bounds. The prior 155/155 full-claim tagged pilot and 23/23 independent carrier
agreement remain pre-declaration evidence with the limitations in r12; they do
not substitute for this work. The store's retention location remains an owner
decision. No bounded tagged verification, result/companion publication, or
complete B/S/T measurement was performed in r13.

The frozen v1 table SHA-256 remains
`e435ac4072ba0a3c278fbe91918d22184b43f0a8d6c1e397c09f68dd629beb75`.
No code was changed, so no new red-first test or kondo run was needed; r12's
reported focused tests and kondo result are historical, not rerun here. The
full oracle suite was not run. No commit was made.
