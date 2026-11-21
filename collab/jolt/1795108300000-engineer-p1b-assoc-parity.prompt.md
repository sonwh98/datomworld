Created-GMT: 2026-11-19 17:22:40 GMT
Session-ID: 01a1f1f9-2d2b-71a2-b88a-1675b81dd975 (RESUME)
# Task: P1b-assoc-parity — carry the pinned assoc-deftype fix onto the FFI lineage

Role: VM Runtime Engineer (re-pin preparation; owner-directed re-pin)

## Situation

datomworld's jolt-toolchain.edn pins origin=sonwh98/jolt at b389f1a7 (branch
assoc-deftype-refusal, one commit on 697b91ee): "assoc on a deftype that is not
Associative throws ClassCastException, as on the JVM" (host/chez/records-coll.ss;
seeds unchanged at the remint byte-fixpoint). The owner directed re-pinning to
the FFI lineage (gambit-ffi-p1b @ 88630634) — but b389f1a7 is NOT an ancestor of
that lineage, and the diff confirms the refusal block exists only at b389f1a7
(gone at 88630634). Re-pinning without carrying the fix would regress JVM parity
silently.

## Task

1. Cherry-pick b389f1a7fb553f63a3494d71f8fe3251790bf4d3 onto gambit-ffi-p1b
   (worktree /Users/sto/workspace/jolt-ffi-p1b). Resolve conflicts per the
   CURRENT records-coll.ss structure (main lineage evolved ~920 commits); the
   SEMANTIC requirement is: assoc on a deftype that is not Associative throws
   ClassCastException naming the class, matching the JVM and jolt's
   associative? — preserve exactly that, nothing more.
2. Check the fix's test coverage: if b389f1a7 added unit.edn rows or test
   changes, they come with the cherry-pick; if not, add a unit.edn row
   asserting the behavior ((assoc (deftype...) ...) -> ClassCastException with
   the class-naming message) so the parity is gate-held.
3. Seeds: the original fix was "seeds unchanged at the remint byte-fixpoint".
   After the cherry-pick run gambitseedcheck AND gambitgencheck; if the seeds
   must be reminted on either host, do it and record it (deterministic,
   digests as before).
4. Full local regression: make gambitffi (ABI/public/errno/loader),
   gambitcheck, gambitkernel, gambiteval, emittedheads, deadhost, and the
   unit suite row(s) for the fix — all green, watchdogged.
5. Commit on gambit-ffi-p1b (message: "p1b: carry the pinned assoc-deftype
   refusal fix onto the FFI lineage (b389f1a7)"). Never push. Note in
   p1b-stage1 findings.

The orchestrator then: updates jolt-toolchain.edn :sha to your tip, runs
bb bootstrap:jolt (full verify incl. the new submodules/sha), the datomworld
tri-host gate, and files the relay note (cross-cutting file, owner-directed).

Return: Completed-* header + cherry-pick result (conflicts resolved how) +
behavior-parity evidence (the test row + its output) + gate line.
