Completed-GMT: 2026-10-21 05:48:50 GMT
Completed-Local: 2026-10-21 12:48:50 +07

# Architecture Review: "A Tale of Six Engines" (glm-5.3, Lead System Architect)

Target: `public/chp/blog/a-tale-of-six-engines.blog` (working tree, uncommitted modifications)

## Verdict: SIGNED OFF WITH CHANGES

The post is architecturally coherent and doctrinally faithful to datom.world — the observer topology, the Yang/Yin split, and "restrictions as declared data" map one-to-one onto the axioms and non-negotiable invariants. The two claims most central to the thesis — deterministic rollback and the hot/cold tier — are directionally sound but underspecified in ways that matter (F1, F2). F3/F4/F6 are editorial corrections that should land before publication; F1/F2/F5 are spec obligations to record for the blueprint, not blockers for the post.

## Verification performed

- Read `datom.world.md`, `yin.vm.streams-all-the-way-down.md` (determinism doctrine), `dao-scene/{dao.scene,scene-vocabulary}.md`, `yang.antlr.md`, `datom.md` (retraction semantics), `dao.gui.md` scope note.
- Benchmark cross-reference: "~27 ms → ~4.4 ms" and "50% faster than the AST Walker" match `a-tale-of-four-vm-benchmarks.blog`; 4.38 ms quoted correctly.
- Subsystem names match tree/docs: `dao.gui`, `dao.postgraphics` (cljc/cljs/cljd), `dao.space.index`, `scene.world` (reserved 3D vocabulary), `dao.scene` (design-only per its scope note).
- "Clojure and Python supported today": verified — `src/cljc/yang/clojure.cljc` + `src/cljc/yang/python/antlr/` (legacy compiler retired in `3dd59171` in favor of the ANTLR frontend).
- Five-engine historical facts spot-checked (mapthing_t 10-byte 5-tuple, TICRATE=35, 256-entry P_Random, 4-byte demo ticcmds, id Tech 6/7 fibers, UHT, Boehm GC, DOTS bakers, Godot RID servers, Bevy archetype/sparse-set): no errors found.

## Findings

| ID | Severity | file:line | Invariant / Evidence | Recommended correction |
|----|----------|-----------|----------------------|------------------------|
| F1 | MAJOR | blog L99-101, L765-767 | Rollback "effortless" + "Determinism Restored" is necessary but not sufficient. (a) **Numeric determinism**: hot kernels (movement/damage/AI steering) need trig/sqrt; `+ - * / sqrt` are reproducible across JVM/JS/Dart, transcendental libms are not — and datom.world runs three host lanes. Codec-level determinism rulings exist (dao.jing.cbor.md float min/max) but no simulation-arithmetic profile. (b) **Cold-tier scheduling**: continuations woken by wall-clock UI events make replay interleavings host-timer-dependent, contradicting "nondeterminism is exactly what you must store". | Scope the claim in one sentence; record the obligation: fixed arithmetic profile for hot kernels (integers/fixed-point, or whitelisted float ops with per-host conformance goldens); all wakeups, cold-tier included, are stream-sourced and recorded. Determinism applies to the simulation stream only; rendering need not replay. |
| F2 | MAJOR | blog L776-794 | **Hot/cold commit protocol unspecified.** Cold continuations run "outside the frame budget" yet write world state; when they commit (epoch? tick?), same-attribute same-epoch conflict resolution, and interaction of async drain with the "fixed merge order" are all unstated. As written, either cold code blocks the frame or t-ordering becomes scheduler-dependent — implicit interleaving reintroduced between tiers. Also: fixed-size ring buffers that overwrite on overflow silently destroy datoms, violating unitarity. | Specify: cold effects buffer and commit at epoch boundaries in canonical order; tier-precedence rule for conflicting writes, stated as data; ring overflow raises a deterministic budget-violation event, never a silent drop. |
| F3 | MINOR | blog L710-717 | "Undo … simply querying the state as of transaction t-1" is wrong under the datom.world model: retraction is itself a datom (m=0, folded at the index layer — datom.md "Entity 0: :db/retract"). As-of time travel only hides a retracting edit from a view, and in a multi-observer stream it would rewind other observers' concurrent appends. | Reword: undo is a compensating transaction computed from history; as-of queries serve inspection/time-travel. Qualify "Free". |
| F4 | MINOR | blog L107-112, L120-122 | "Any grammar supported by ANTLR4 … run natively … zero foreign-function boundary penalties" + "without … surrender silicon-grade execution speed". (a) Per yang.antlr.md §1, Yang is an open compiler **composition framework** — each language needs a grammar package, syntax export, semantic lowering, and runtime prelude; a grammar alone is not a frontend. (b) The post's own hot/cold section concedes unrestricted scripts "cannot run at DOOM speed" — internal contradiction. Zero-FFI is true; silicon-grade holds only for the verified hot subset. | Attach the qualifier at first mention: any language with a Yang frontend contract, executing under hot/cold tier discipline. Keep "zero FFI boundary"; drop the general silicon-speed claim for scripts. |
| F5 | MINOR | blog L48, L756-761 | **Intra-epoch authority ambiguous.** The stream is titled "World Truth" while the hot path writes mutable t+1 columnar buffers and drains the delta log off the frame thread; which representation wins for a mid-drain rollback snapshot (t-3) is unstated, and the "Appends Batches" loop doesn't name the append custody (single-writer t assignment) that makes "fixed merge order" real. | Record the epoch-seal ruling: the datom stream is authority at epoch boundaries; columnar buffers are an intra-epoch projection; an epoch seals only when its drain is durable (or buffer-snapshotted); t assignment is single-writer. |
| F6 | ADVISORY | blog L673, L811, L872, L710-735 | Unhedged aspirational numbers/hyperbole: "under twenty milliseconds" recompile (target, unmeasured), "zero memory overhead … infinite malleability" (row indirection is overhead; malleability is bounded by schema/budgets — "infinite" contradicts the post's own thesis), "hitch-free", "Free" for undo/save/replication. Also `dao.scene`/`scene.world` are reserved/design-only vocabularies. | Mark Dao-row numbers as design targets; soften "zero/infinite/free" to "structural consequences of the substrate"; note scene vocabularies are reserved design work. |

## Confirmed properties (passed)

1. **Observer topology faithful to the commitments** — the diagram is exactly "peer observers, one stream"; "the game loop does not know the editor exists" follows from observer independence/optionality. No layer collapse: authoring never executes; execution never reflects.
2. **Graphics path separates interpretation from execution** — dao.postgraphics is an isolated interpreter; live edits append datoms; lowering is a pure function over deltas, upstream of execution.
3. **"Discipline as Data" is doctrinally exact** — restrictions as declared compiler-checked data ("Restrictions are a feature" made architectural); write-set-declared scheduling builds the dependency graph explicitly from tuples (no assumed graphs); randomness as input stream matches the store-nondeterminism doctrine; budgets as datoms answer both Bevy's rigidity and id Tech's tribal knowledge with one mechanism.
4. **The hot/cold split is sound** — same language, compiler-verified tier, actionable diagnostics, no rewrite-by-another-engineer; only the commit boundary is unspecified (F2).
5. **Honesty markers load-bearing** — "does not exist yet" (L16) and "the lowering compiler … is the central engineering bet" (L794).
6. **Cross-references check out** (benchmarks, subsystem names, Clojure+Python today, engine history).
7. **The engines-as-VMs isomorphism is substantive** — the same dual-representation move already proven in yin.vm (code-as-tuples, de Bruijn VMs), so it inherits an existence proof rather than promising one.

## High-risk assumptions / open questions

1. The lowering compiler's hardest unsolved case: row-index stability under entity churn (Bevy's archetype-migration problem in datom clothing) — tombstones + epoch-boundary compaction needs a spec before "zero memory overhead" is repeated.
2. Numeric determinism profile (F1) — precondition for rollback/multiplayer claims.
3. Epoch seal and append custody (F5) — precondition for unitarity under async drain.
4. Explicit scoping: only the simulation stream must replay bitwise.
5. 20 ms recompile target has no invalidation strategy behind it.
6. Multiplayer needs the dao.stream.remote mapping plus the single-writer t ruling.

**Required for full sign-off (editorial, in the post):** F3 reword, F4 qualifier at L107-112, F6 hedging; F1/F2/F5 recorded as blueprint spec obligations (design doc or ADR) rather than blog expansion.

No source or blog files were modified.
