Completed-GMT: 2026-10-09 05:06:49 GMT
Completed-Local: 2026-10-09 12:06:49 Asia/Ho_Chi_Minh

# Architect ruling: subsystem-first tri-host test architecture

Deliverable: `docs/design/test.subsystems.md` (proposed, 2026-10-09). No code,
`bb.edn` or build script was changed. Read-only analysis of the tree; no test
lane was run. Evidence came from loading `src/dev/affected.clj` under
babashka and querying its index (section 9 of the design doc).

## Ruling

1. **Subsystems are selections over one build, not isolations.** No split of
   `deps.edn`, `shadow-cljs.edn` or the cljd build; no test file moves. A
   subsystem is a longest-prefix table over namespaces
   (`src/dev/subsystems.edn`), fourteen entries, with two named residual
   buckets (`dao.base`, `yang.base`) so nothing drops silently.
2. **Hosts are never a scope axis above the edit loop.** One-lane runs are G0
   only. G1 (subsystem, three lanes), G2 (`bb test:changed`, three lanes), G3
   (`bb test` on master) are all tri-host. G3 stays mandatory and is not
   replaced by any union of subsystem runs.
3. **Prerequisites and serialization are derived, not declared.**
   `affected/prerequisites` and `affected/serialized-pairs` already compute
   them from text reach. A per-subsystem prerequisite list is rejected on
   the derive-don't-persist rule.
4. **One runner, two selectors.** The subsystem selector is a `--subsystem`
   mode of `affected.clj` reusing `lane-tests`, `prerequisites`,
   `serialized-pairs`, `run-lanes!`. Extraction of a shared runner
   namespace is deferred until a third selector exists (minimal diff).
5. **Foundation tracks get G0 and G1 only.** For `dao.stream`, `dao.jing`,
   `dao.data` the landing gate G2 degrades to nearly the whole suite. That
   is the correct consequence of being a foundation, not a defect to
   engineer around.

## Findings (severity | file:line | evidence | correction)

- medium | src/cljc/dao/stream/journal/file.cljc (ns form) | `dao.stream.journal.file` requires `dao.space.store.fs`: the only upward source edge from layer 0 to layer 1 in the repository | Architect ruling needed against ADR 0001; recorded as open question 1, not blocking.
- low | test/yang/python/antlr/int_contract_fixtures.cljc | required by `dao.jing.stream-test`, `dao.stream.journal-test`, `yin.vm.ucf.scalar-round-trip-test`; three layer-0/2 tests name a layer-3 namespace; the fixture reaches no ANTLR class so no build cost | move to a neutral `test/` home; open question 2.
- low | bb.edn:28-30 (`test:clj :depends`) | `bb test:clj` builds the Node REPL and generates the ANTLR parser unconditionally; only `yin.repl.dht-process-test` and eight `yang.python.antlr.*` namespaces need them | the subsystem path derives prerequisites instead; `bb test:clj` itself is unchanged by this design.
- low | src/cljc/yin/vm/semantic.cljc, src/cljc/yin/vm/ffi/remote_serve.cljc | `yin.vm` requires `yin.vm.ucf` (2 edges) while `yin.vm.ucf` requires `yin.vm` (47): one structural cluster, two ownership tracks | keep two subsystems in the table; no code change.
- info | test/yin/vm/linker/*_test.cljc, test/yin/vm/linker_require_test.cljc, test/yin/vm/host_typed_values_test.cljc | nine `yin.vm` tests require `yin.repl` source (layer 2 → 3) | class B seam tests; selected by home; open question 4 proposes a derived both-homes flag.
- info | src/dev/affected.clj:614-631 | wide-change list includes `src/dev/`, so every migration phase lands under G3 | accepted; stated in the plan.

## Properties confirmed

- The require graph gives four clean layers (0: dao.stream/jing/data/base;
  1: dao.space/gui/postgraphics; 2: yin.vm/ucf; 3: yin.repl/yang.*;
  4: demo/world) with exactly the leaks listed above.
- Every one of the 260 test namespaces maps to one table prefix; the
  orphan check in Phase 0 keeps that true.
- Track C (`yin.vm.ucf`, 40 ns on all three lanes) needs no build
  prerequisite and no lane serialization.
- Track A (`yang.python`, 21/12/12 ns) needs `gen:python-antlr` on the
  JVM lane only and holds 12 of the 16 guard namespaces.
- Track B (`dao.stream`, 32/32/30 ns) needs no build but serializes clj
  then cljd (`ws-project-cross-jvm-test` runs the cljd compiler).
- The existing `cljd_agg --only` already compiles only the selected
  namespaces, so the Dart lane is where subsystem scoping saves most.
- No invariant of `datom.world.md` is touched; the design introduces no
  shared mutable state, no hidden global and no new control flow: it is a
  second pure selector over the same index feeding the same runner.

## Next

Phase 0 (table + orphan check + baseline timings) is a small Engineer
slice; it is a wide change and lands under G3. Open questions 1 to 4 are
Architect rulings that can run in parallel with Phases 0 to 3.
