# Report: M4 A3 fix1 (claude-sonnet-5)

Worktree datomworld-m4-a3, uncommitted.

## Changed
- `src/cljc/yin/vm/linker/authority.cljc` `events-from-datoms`: proofs
  are collected per entity into a set of distinct values; an entity
  gets a proof only if the set has exactly one value. Two or more
  distinct values means no proof is attached, so the event is the
  existing :no-proof case (:unauthenticated diagnostic through the
  policy). Fail closed, no picking, no order dependence. Docstring
  updated.
- `docs/design/yin.vm.linker.md` (~line 1717): the "first proof datom"
  sentence now states one proof value per entity, two distinct values
  = no-proof, and an orphan proof joins an envelope that later appears
  on its entity.
- Test namespace `yin.vm.linker-authority-ingestion-test`, new tests:
  - `two-distinct-proofs-on-one-entity-fail-closed` (valid + forged
    on one entity via dao.space: one event, no proof, :no-proof discard)
  - `ingestion-is-invariant-under-datom-permutation` (compares event
    frequencies over reversed, rotated and shuffled inputs)
  - `orphan-proof-joins-a-later-envelope-deterministically`

## Decisions
- Reused the :no-proof diagnostic rather than adding a new kind, since
  section 8.2's diagnostic kinds are a closed set.
- Event order still follows the envelope datoms' order (unchanged);
  the permutation test compares as a multiset, and the proof choice
  is the order-independent part.
- Identical duplicate proof values collapse (set), so they still
  authenticate.

## Verification
- cljstyle check clean, kondo 0 errors/warnings.
- JVM: yin.vm.linker-authority-ingestion-test and
  yin.vm.linker-authority-test: 24 tests, 107 assertions, 0 failures.
- Full three lanes not run (orchestrator).
