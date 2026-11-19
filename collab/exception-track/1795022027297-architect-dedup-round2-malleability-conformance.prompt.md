Created-GMT: 2026-11-18 17:13:47 GMT
Created-Local: 2026-11-19 00:13:47 +07 (Asia/Ho_Chi_Minh)

# Task: yang-compilers-dedup-round2-malleability-conformance

Role: Architect (malleability conformance judgment — continuation of your round-2 audit)

The owner accepted your audit's shape and proposes the following execution plan. Judge it
against docs/agents/malleability.md specifically — does executing this plan IMPROVE
malleability (bounded complexity, loose coupling, high cohesion, evolutionary fitness),
or does any part of it violate the doc's principles (premature abstraction, unbounded
API growth, coupling introduced, naming)?

## The proposed plan

**Batch 1 (zero-risk class, proposed first):**
- C1: lift `read-at`/`absent?`/`missing` into `yang.core.siteset-store` (2→5 publics)
  and `site-set-of`/`publish!` into `yang.core.siteset-publication` (3→5 publics),
  both new namespaces ending at exactly the 5-entry-point bound.
- C2: `yang.core.module` delegates `declare-free-name`/`free-primitives` to
  `yang.core.prelude/host-declaration`/`free-name-vec`; drops its `yin.vm` +
  `yin.vm.module` requires (out: 5→3).
- C3: wire the 6 missed call sites to the already-landed helpers (zero new API).

**Batch 2:**
- C4: `src/clj/yang/io.clj` + `src/cljs/yang/io.cljs` → `src/cljc/yang/io.cljc`
  (reader conditionals for the 2 host-I/O lines).
- C5: `own-lexer!`/`own-parser!` in `cst.clj`; `lexer-span`/`export-tree` →
  `yang.antlr.export` (CST per-host files kept separate per your ruling).
- C6: `correlation`/`rejected` → `yang.php.antlr.eval-transport` (stays php-owned).
- P2-1 hygiene: the 6 caller-less publics → `defn-`; and the `core.prelude` SPLIT you
  judged for (admission trio → `core.prelude-admission`).

**Deferred:** C7 (`host-double`/`radix-digits`) rides with a future `core.literals`
extraction, per your conditional trigger.

## Questions to answer

1. Does each batch, executed in this order, improve malleability per the doc's own
   measures? Name the measure for each (e.g. C1: bounded complexity held at the 2-5
   bound while coupling drops; C2: accidental coupling inside yang.core removed).
2. Any ordering hazard? (e.g. does C1 before the prelude-admission split leave
   `core.prelude` transitively heavier? does C2's delegation create a module→prelude
   edge that the later split then has to move?)
3. Does anything in the plan violate the doc's emergence rule or the naming rule?
4. Is anything MISSING from the plan that the doc would require in the same pass (e.g.
   should the C2 delegation and the prelude-admission split be ONE unit rather than
   batch 1 + batch 2, to avoid touching core.prelude twice)?

Read-only. Begin exactly:
Completed-GMT: <YYYY-MM-DD HH:MM:SS GMT>
Completed-Local: <YYYY-MM-DD HH:MM:SS +07>

End with exactly one line:
CONFORMANCE VERDICT: ALIGNS | ALIGNS WITH AMENDMENTS — <one line> | VIOLATES — <why>
