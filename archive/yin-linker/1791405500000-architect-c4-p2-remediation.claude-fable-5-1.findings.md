# C4 Track A Slice P2: remediation ruling on findings F1 and F2

Architect: Claude Fable 5.1. Date: 2026-10-08.
Branch: `yang-python-c4-p2` (master @ 66756d20).
Inputs: the engineer's step 0 report
`collab/1791403000000-engineer-c4-p2.claude-opus-5-5.findings.md`, the
original P2 spec
`collab/1791403000000-architect-c4-p2-spec.claude-fable-5-1.findings.md`,
[`docs/design/yin.vm.linker.md`](../docs/design/yin.vm.linker.md) (sections
4.1, 4.2, 6.4, 8.1), [`docs/design/yin.vm.linker.dht.md`](../docs/design/yin.vm.linker.dht.md)
(section 4.1, the bounds table), [`docs/design/yin.vm.code-as-tuples.md`](../docs/design/yin.vm.code-as-tuples.md)
(section 4.5, section 7.7), and the code named below, each point read in
the tree.

The engineer stopped correctly. Both findings are below the prelude and
both are real. Neither is a defect in the P2 design's layout claim: with
the bound raised, the wide layout links `:ok` on the three vector formats
and retains exactly the declared primitives and host exports. The
findings are a linker-seat sizing default (F1) and a linker-seat
complexity defect plus one design-conformance gap (F2). P2 is split: a
linker slice lands first, then P2 proceeds under an amended spec.

## 0. Rulings

```text
+-----+--------------------------------------------------------------------------------------------------------------------------------+
| Id  | Ruling                                                                                                                         |
+=====+================================================================================================================================+
| R1  | `linker/default-bounds` becomes `{:max-parts 65536, :max-depth 256, :max-bytes 16777216}`. The parts bound is the protocol's   |
|     | honest-image envelope, shared by every fetch, closure walk and DHT load; it is raised at the default, not routed around by      |
|     | caller bounds, because a receiver that fetches the tree under the default would refuse `:parts-limit` exactly as the publisher  |
|     | did. The byte bound already caps the worklist at about 160k rows, so the walk stays finite. The depth bound is unchanged: the   |
|     | wide layout's deepest occurrence is 115, and 256 now doubles as a guard against a `then`-chain module layout regressing in.     |
+-----+--------------------------------------------------------------------------------------------------------------------------------+
| R2  | `publish-module!` and `link-local` accept caller bounds (`{:bounds {...}}`), forwarded to `closure/walk` and into the local      |
|     | runtime's link state, as section 4.2 step 2 says the composition supplies them. Default behaviour is R1's default.              |
+-----+--------------------------------------------------------------------------------------------------------------------------------+
| R3  | Under `:verifying`, the derivation check's fetch of the manifest tree runs steps 2 to 4 only (fetch, identity, validate), as     |
|     | section 8.1 states. Today `verified-policy-outcome` fetches the tree through a full by-identity link, which runs step 5a's      |
|     | three scanners and then discards their result. That is the three scanner runs the sampler saw under the semantic, stack and    |
|     | register links. The fix is a design-conformance fix, not a policy change: verifying still verifies the tree by content.         |
+-----+--------------------------------------------------------------------------------------------------------------------------------+
| R4  | `linker/tree-free-name-occurrences` becomes a direct walk over the occurrence relation: one pass from the root, the bound       |
|     | parameter set threaded down each path, `:lambda` params added on entering slot 3, every `:variable` occurrence whose name is    |
|     | not in the set reported at its own path. This computes the same relation as `vm/occurrence-rules` (occurrence-scoped by         |
|     | construction, never row-scoped, so section 4.5's shared-row hazard cannot arise) in linear time. The Datalog rule set stays    |
|     | the normative definition and stays in `vm/free-names`; a conformance test holds the walk to it on every linker fixture and on  |
|     | a synthetic module of the prelude's size. Measured on the prelude tree: 26 ms for the walk against 8 to 15 minutes for the     |
|     | query.                                                                                                                         |
+-----+--------------------------------------------------------------------------------------------------------------------------------+
| R5  | `tree-definition-occurrences` and `tree-application-sites` stay Datalog unless measurement shows either above one second on   |
|     | the synthetic module; neither has a `not` clause, and the sampler put under 3% of the time outside the free-name scan. If one  |
|     | is converted, the same conformance obligation applies. `undischarged` may precompute, once per definition, whether the         |
|     | definition precedes every application site; it is permitted, not required, under the same measurement rule.                  |
+-----+--------------------------------------------------------------------------------------------------------------------------------+
| R6  | The serving composition's `:derivation` policy becomes a composition option: `yin.repl.link/composition` takes `:derivation`    |
|     | (`:verifying` default, unchanged) and `attempt` forwards it. The design already makes the policy the composition's choice      |
|     | (section 8.1); the code hard-wires the default. A test harness that publishes `py` into its own store and serves it is the     |
|     | publisher, and may serve its corpus legs `:trusted`; P2's A1 is the `:verifying` evidence, once per host.                      |
+-----+--------------------------------------------------------------------------------------------------------------------------------+
| R7  | No query-engine change in this remediation. A prefix index over occurrence paths that would make `occ-bound?`'s `not` cheap    |
|     | is a `dao.space.query` item, deferred (section 7). The linker's scanners are consumers of the relation; making one of them a  |
|     | direct walk is the same choice the vector formats already made in `semantic-free-occurrences`.                                 |
+-----+--------------------------------------------------------------------------------------------------------------------------------+
| R8  | No modular decomposition of `py` to fit a bound or to speed a scanner. The prelude is one module by ruling 1 (one Python       |
|     | process is one task, one namespace). Splitting it into packages is I2's concern and must be motivated by Python semantics,     |
|     | never by linker cost. A size-budget test (A12) makes growth visible before it meets the bound again.                           |
+-----+--------------------------------------------------------------------------------------------------------------------------------+
| R9  | P2 splits into L-f (the linker slice holding R1 to R6, its own branch from master, its own review and landing) and P2 proper   |
|     | (the original spec with section 6's amendments, rebased on L-f). The prelude-side steps of P2 that depend on neither finding  |
|     | may proceed now on `yang-python-c4-p2`; the harness and the linked legs wait for L-f.                                          |
+-----+--------------------------------------------------------------------------------------------------------------------------------+
| R10 | P2's rule "any change to a scanner, a format record, the manifest schema or key set, `publish-module!`, the install phases,    |
|     | `lift-slice` or `receive-module` is prohibited" stands for the P2 engineer. The scanner and publication changes above are     |
|     | L-f's, under a separate brief, and L-f changes no format record shape, no step order, no refusal shape and no manifest key.   |
+-----+--------------------------------------------------------------------------------------------------------------------------------+
```

## 1. What the findings are, read against the code

### 1.1 F1, the bound

`publish-module!` (`publish.cljc` 189) walks the freshly minted closure
with `closure/walk` and no options, so `linker/default-bounds` applies;
each canonical row is one part (`closure.cljc` 77). The wide tree has
5648 rows (5688 with tail marks), the chain tree 6322; both exceed 4096.

The bound is not only the publisher's. The same default governs:

- step 2 of every by-identity fetch (`linker.cljc` 1334 to 1346,
  `enqueue-children`): a receiver linking a vector format under
  `:verifying` fetches the tree and would refuse `:parts-limit` at the
  4097th row;
- the DHT closure walk (`yin.vm.linker.dht.md` bounds table: "as the
  linker's own");
- `yin.repl.link`'s serving runtimes and `dht-link`, which build their
  link states with no bounds.

So a caller-bounds-only remedy would need every peer in a composition to
agree on raised bounds out of band. The default is the protocol-level
statement of "an honest image", and the design text at `linker.cljc`
1312 says so: "generous for every honest image the compositions hold,
finite for a hostile one". The prelude is an honest image. R1 raises
the default; R2 adds the plumbing the design already describes.

Why 65536: sixteen times the prelude, so growth has room; under the
unchanged byte bound of 16 MiB a 65536-row tree at the prelude's row
sizes is a few megabytes, so the byte bound remains the binding
envelope for a hostile image and the walk stays finite. The depth bound
stays at 256: the wide layout's deepest occurrence path is 115 steps
(measured), the chain layout's would be over 340, and D5 chose the wide
layout. A module that trips `:max-depth` from now on has regressed into
the chain shape.

### 1.2 F2, the scanner, and the conformance gap beside it

Measured on the wide layout with a diagnostic script
(`collab/p2probe-count.clj`, never staged):

```text
rows 5688  tags {:application 2955, :if 654, :lambda 807, :literal 770, :variable 501, :vm/current-continuation 1}
occurrences 13800 (44 ms)   variable occurrences 5926   lambda occurrences 825   max depth 115
direct walk: 3517 free occurrences, 346 distinct names, 26 ms
```

The 346 distinct free names are the sibling exports, the 14 primitives,
the 40 host exports, the 61 runtime keys and `yin/def`, as expected
before discharge; the engineer's 54 retained names are what survives
step 5a.

`tree-occurrence-query` (`linker.cljc` 309) is `vm/free-names`' query
with the path added to `:find`. Its `(not (occ-bound? ...))` is
evaluated by `query/eval-not` (`query.cljc` 1152) once per binding, and
each evaluation runs the rule body, whose first clause `[$occ ?root
?lam-path ?lam]` enumerates every occurrence tuple before the prefix
test narrows it. That is 5926 variable occurrences times 13800 tuples,
about 80 million clause unifications per scan, each keyed through CBOR
content keys for distinctness (`numeric-content-key` in the sampler).
Nothing is wrong with the rules; they are the right relation written for
a planner without a prefix index.

Three scanner runs out of four should not have happened at all.
`verified-policy-outcome` (`linker.cljc` 2160) fetches the tree with
`drive-link ... :yin.ast/code`, which is the full by-identity link:
`step` routes the completed fetch through `verify`, which runs
`:obligations-fn`, `:definitions-fn`, `:applications-fn` and
`undischarged` (`linker.cljc` 1355 to 1400). The outcome's obligations
are then dropped: `verified-policy-outcome` reads only `(get-in tc
[:image :value])`. Section 8.1 of the design says the verifying policy
fetches the tree "through the `:yin.ast/code` record (steps 2 to 4, so
the tree itself is verified)". The code overran the text. R3 brings it
back: the derivation path verifies the tree by content and shape and
never scans it. After R3 a publish scans the tree once (the walker's
own link), and a receiving task linking a vector format scans it never.

R4 then makes the one remaining scan cheap. The design does not require
the scanner to be a Datalog evaluation: section 4.5 of the tuples
design requires the relation to be occurrence-scoped ("must be an
occurrence-relation query, not a row-only one") and says of its own
query "the shape here is illustrative, not the exact syntax". A walk
that threads the bound set down each occurrence path classifies each
occurrence by its own path, which is exactly the occurrence-scoped
relation; a shared `:variable` row bound at one occurrence and free at
another yields one free record at the free path and nothing at the
bound one. The vector formats already take this route
(`semantic-free-occurrences`, `linker.cljc` 219, is a scope walk over
closure spans). The rules stay as the specification and as
`vm/free-names`, which `publish/free-of` and `module-from-index` keep
using over small trees; the linker's record-producing scanner is held
to them by test.

### 1.3 The receiving side

The engineer asked whether `(require 'py)` pays F2 per task. Today, yes:
`yin.repl.link/attempt` (`link.cljc` 248) passes no `:derivation`, so
`link-manifest` defaults to `:verifying`, and every vector-format link
fetches and scans the tree. The receiving task never chooses: the
serving composition's outcome arrives as `(:derivation response)`
(`engine.cljc` 1383). After R3, a `:verifying` serve fetches the tree
(5688 parts over the content pair) and re-lowers it once per format per
task, with no scan; after R6, a composition that is its own publisher
may serve `:trusted`, and the link is one manifest fetch, one record
fetch and one image fetch. The install child still runs the module tree
once per task, as the bundled profile runs the prelude once per program
today; that cost is parity, not a regression.

## 2. F1 ruling: bounds and module sizing

- `default-bounds` per R1. Docstring: name the prelude-sized module as
  the honest image that set the parts figure, and the byte bound as the
  envelope that keeps the walk finite.
- `publish-module!` gains the arity `[handle spec opts]`, `opts`
  `{:bounds {...}}`, forwarded to both `closure/walk` calls and to
  `link-local`. `link-local`'s `opts` gains `:bounds`, placed into the
  `local-runtime` options so `link-state` receives it (`bounded` already
  fills nil entries from the default). The two-argument arities are
  unchanged.
- `linker_test/a-fetch-without-explicit-bounds-is-finite` and
  `exceeding-a-composition-bound-is-parts-limit` keep their meaning;
  any literal `4096` in a test moves to `(:max-parts linker/default-bounds)`.
- No module-sizing rule beyond A12's budget test. The bound is a
  protocol envelope, not a style target. A module that approaches it is
  a design conversation (packages, I2), never a scanner or bound
  workaround (R8).

## 3. F2 ruling: the scanner

### 3.1 Steps 2 to 4 for the derivation tree (R3)

`verified-policy-outcome` fetches the tree through a link that completes
at step 4. The mechanism is the engineer's choice within these limits:

- the request carries one extra key (for example `:yin.link/scan? false`)
  honoured at the one place `step` calls `verify`, or `verify` gains an
  arity that stops after `validation-defect`; whichever is smaller;
- `verify`'s exported two-arity behaviour is unchanged; the outcome shape
  of a scanned link is unchanged; a step-4 completion carries `:value`
  and `:parts` and no `:obligations` key;
- no format record changes; `fetch` and `step` still contain no branch
  on `:format`.

### 3.2 The direct walk (R4)

`tree-free-name-occurrences` becomes a walk over `{:root :rows}`:

- a worklist of `[path id bound in-body?]` from `[[] root #{} false]`;
  children enumerated from `vm/semantic-bytecode-grammar` exactly as
  `vm/occurrence-child-places` does (slot position for `:node`,
  `[position i]` for `:nodes`), so it is generic over every section 2.3
  tag and stays in step with `vm/occurrences`;
- entering a `:lambda` row's slot 3 adds the row's params to the bound
  set and sets `in-body?`; no other slot changes either;
- a `:variable` row whose name is not in the bound set and is not a
  reserved name yields `{:name sym :at [root path] :in-body? b}`;
- the result sorted by `path-order`, as today.

`tree-enclosure` stays for the definitions scanner. The private
`tree-occurrence-query` var is deleted from the linker; the conformance
test builds its oracle from `vm/occurrence-rules` directly.

Portability: `loop` with a vector frontier and a transient accumulator,
`keep-indexed` and `mapv` over slots, no `for` (ClojureDart's chunked
`for` trap), no multi-key `assoc` on a possibly nil value.

### 3.3 Conformance obligation

`linker_test`, new `tree-free-name-walk-equals-the-occurrence-rules`:
for every tree fixture the namespace already builds (`def-then-use`,
`branch-def`, `body-def`, the discharge fixtures of lines 1266 to 1600,
and a fixture with one structurally shared `:variable` row that is
bound at one occurrence and free at another, modelled on
`query_test/occurrence-aware-rules-resolve-mixed-free-and-bound-rows`),
the set of `[name path]` pairs from the walk equals the set from the
Datalog oracle (`vm/occurrence-rules` with the path in `:find`, reserved
names removed). The shared-row case additionally asserts exactly one
record, at the free path.

`linker_test`, new `a-prelude-sized-module-publishes-under-default-bounds`
(`^:slow`, `dao.test-slow/guard`): a synthetic module built in the test
(no `yang.python` require; the linker tests do not depend on the
frontend) in the D5 wide shape, about 1500 definitions each a lambda
reading three siblings and two primitives, about 6000 rows; it
publishes `:ok` on all four formats under the default bounds, and the
retained obligations are the declared primitives only. Its passing
inside the slow lane is the time evidence; no wall-clock assertion in
any test on any host.

The existing scanner tests (`ast-scanners-yield-position-bearing-records`
and the discharge tests) are unchanged and must stay green; they pin the
record shape and the dominance rules the walk feeds.

### 3.4 Measurement to report

The L-f report states, on the JVM, for the synthetic module: closure
walk time, each of the four `link-local` times, and the share of each
in the three tree scanners and `undischarged` (the engineer's sampler
`collab/p2probe-sample.clj` can be reused). Target: `publish-module!`
of the synthetic module under 60 s on the JVM. If the next hotspot is
the content-pair fetch of the tree, say so and report the figure; the
`local-runtime` ring capacity (64) may be raised through its existing
arity, and nothing else in the stream path may be tuned in this slice.

## 4. How Track A proceeds

```text
+-------+---------------------------------------------------------------------------------------------------------+-----------------------------+
| Slice | Scope                                                                                                   | Branch and gate             |
+=======+=========================================================================================================+=============================+
| L-f   | R1 to R6: default bounds, publish and link-local bounds plumbing, steps 2 to 4 for the derivation tree, | `linker-l-f` from master;   |
|       | the direct free-name walk with its conformance test, the serving `:derivation` option, the synthetic    | lint, three fast lanes, the |
|       | prelude-sized module test, the design edits of section 5.4. Linker seat; a separate brief and worktree  | linker slow set; Architect  |
|       | from P2; a non-author review family per the routing rules before landing.                               | sign-off; lands first       |
+-------+---------------------------------------------------------------------------------------------------------+-----------------------------+
| P2    | The original spec, amended by section 6. Steps 1 to 4 and 8 of its section 3 depend on neither finding  | `yang-python-c4-p2`; steps  |
|       | and may start now; step 0 is re-run after rebasing on L-f, and steps 5 to 7 follow it.                  | 5 to 7 after L-f lands      |
+-------+---------------------------------------------------------------------------------------------------------+-----------------------------+
```

The prerequisites table of `docs/design/yang.antlr.md` section 8.5.6
gains the row `L-f: large-module linking: default bounds, derivation
fetch at steps 2 to 4, linear free-name scan (blocks P2)`, and the P2
slice row reads "needs L-a, L-f and the float fix". L-f makes those
edits in its step 8; P2's step 8 edits are unchanged.

Why L-f is not folded into P2: P2's prohibition on scanner and
publication edits exists so that the prelude work never routes around a
linker defect, and the same engineer editing both halves in one slice is
exactly the mixing it prevents. The linker changes also need their own
review by someone reading the linker design, not the Python design.

## 5. L-f specification

### 5.1 Decisions

R1 to R7 above. Two more:

- L1. The step-4 completion of the derivation tree is the only place a
  link may complete without step 5a. Every image a task installs still
  passes step 5a over its own format's scanners; the walker's own link
  of the tree still scans it.
- L2. The walk and the rules are one relation. Any future scanner that
  departs from the rules changes the design's section 4.1 text first;
  the conformance test is the fence.

### 5.2 Invariants

- The outcome shapes of `verify`, `fetch`, `link-manifest`,
  `publish-module!` and every refusal are byte-for-byte what they are
  today for every existing test.
- No format record field, no manifest key, no step order, no engine or
  module change. `yin.vm.engine` and `yin.vm.module` are untouched.
- `vm/free-names`, `vm/occurrence-rules`, `vm/occurrences` and
  `vm/ast-requirements` are untouched.
- `default-bounds` changes value, never shape; the `bounded` fill-in
  rule is unchanged.
- `yin.repl.link/composition` without `:derivation` behaves exactly as
  today.
- Clean breaks only; no compatibility arity kept for the old scanner.

### 5.3 Steps for the Implementation Engineer

Worktree: a fresh sibling worktree on branch `linker-l-f` from master;
`mise trust` and `npm ci` first. Read `docs/agents/build-n-test.md`.
Lanes in the foreground. Nothing under `collab/` staged.

1. Tests first (red): the conformance test and the shared-row fixture
   (section 3.3), the synthetic prelude-sized module test (`^:slow` and
   guarded), a `publish-module!` and `link-local` bounds test (a module
   of 8 rows under `{:bounds {:max-parts 4}}` is refused
   `:yin.link.publish/incomplete-closure` with `:parts-limit` in the
   walk; the same under the default publishes `:ok`), a test that a
   `:verifying` serve of a vector format over `yin.repl.link` reaches a
   `:verified` outcome with the tree fetched and no tree scanner run
   (count calls through a wrapped `ast-format` record in the test's own
   format map), and a test that `composition {:derivation :trusted}`
   reports `:trust :composition` in the outcome. Run `linker-test`,
   `linker-manifest-test`, `linker-require-test`; they must fail on the
   missing behaviour only.
2. `default-bounds` (R1) and the publish and link-local plumbing (R2).
3. Steps 2 to 4 for the derivation tree (R3, section 3.1).
4. The direct walk (R4, section 3.2); delete `tree-occurrence-query`.
5. Measure (section 3.4); apply R5 only if the figures ask for it.
6. `yin.repl.link/composition` `:derivation` and `attempt` forwarding
   (R6); `serve`'s docstring names the option.
7. Lint (`clj -M:kondo --lint src/cljc/yin/vm/linker.cljc src/cljc/yin/vm/linker src/cljc/yin/repl/link.cljc test/yin/vm`),
   then `bb test:clj`, `bb test:cljs`, `bb test:cljd`, one at a time,
   then `clojure -M:test -i :slow -n yin.vm.linker-test` and the
   corresponding `bb test:slow:cljs` and `bb test:slow:cljd`. The known
   baseline failure `yin.vm.ucf.handoff-v2-census-test` is reported by
   name.
8. Design edits (section 5.4).
9. Report: `collab/<ts>-engineer-l-f.<model>.findings.md` with lane
   counts, the section 3.4 figures, and anything in this ruling found
   wrong. Commit on Architect sign-off: `fix(yin.vm.linker): default
   bounds, derivation fetch at steps 2 to 4, linear free-name scan (L-f)`,
   no `Co-Authored-By` line (`docs/agents/format.md` governs).

### 5.4 Design document edits (L-f's step 8)

- `docs/design/yin.vm.linker.md` line 318, "For the AST record all three
  scanners are Datalog over the rows": becomes "For the AST record the
  three scanners compute `yin.vm/free-names`' occurrence-scoped relation
  and the definition and application queries over the rows; the
  free-name scanner is a direct walk of the occurrence relation that
  threads the binder set down each path, held equal to
  `yin.vm/occurrence-rules` by a conformance test, because the rules'
  `not` clause is quadratic in the tree under the planner of today
  (measured: 8 to 15 minutes against 26 ms for a 5688-row module); the
  other two remain Datalog."
- Section 4.2 step 2's bounds sentence gains the default values and the
  reason for the parts figure (a prelude-sized module of about 6000
  rows is an honest image).
- Section 8.1's verifying bullet gains one sentence after "steps 2 to
  4, so the tree itself is verified": "and no further: the tree's own
  obligations are the walker's business, scanned only when the tree is
  the requested image."
- Section 6.4 or the `yin.repl.link` text: the serving composition's
  `:derivation` option, default `:verifying`.
- `docs/design/yin.vm.linker.dht.md` bounds table: no change (it refers
  to `default-bounds`).
- `docs/design/yang.antlr.md` section 8.5.6: the L-f row and the P2 row
  (section 4 above).

### 5.5 Rules for L-f

Permitted without asking: the mechanism of section 3.1; whether the
walk carries `in-body?` or recomputes it with `tree-enclosure`; the
synthetic module's exact size above 5000 rows; raising the ring capacity
through `local-runtime`'s arity; R5's conversions if measured.

Prohibited: any change to `vm/occurrence-rules`, `vm/free-names`,
`dao.space.query`; any scanner change for the vector formats; any
engine, module, format-record or manifest change; a row-scoped walk
(parent edges instead of occurrence paths); a wall-clock assertion in
any test; requiring `yang.python` from a linker test; serving `:trusted`
anywhere outside a composition that explicitly asks for it.

## 6. Amendments to the P2 spec

The P2 spec stands except as follows. Section numbers are the spec's.

- Status notes and section 3 step 0: step 0 is re-run after rebasing on
  L-f with the unchanged probe. Expected now: both layouts publish; the
  chain layout links `:refused :undeclared-free` (or `:ok` with a long
  `:retained` list) on the vector formats; the wide layout links `:ok`
  on the three vector formats retaining exactly the 14 primitives and
  40 host exports, and `:refused :undeclared-free` on `:yin.ast/code`
  naming `float?` or another sibling (L-b). The probe's printed timings
  go in the report. The engineer's step 0 output of 2026-10-08 is
  already the record of "stripped keys colliding with primitives" being
  empty, of the free-name set, and of the limits claim; those need no
  re-run but the report quotes them again from the new run.
- Section 1.2, I8 ("the linker learns one thing"): unchanged for the P2
  engineer; L-f's changes are already on master by then.
- Section 2.9 and section 3 step 5, the harness: the serving composition
  for every corpus leg (`every-vm=`'s linked leg, `c3_gate_test`'s
  `linked-equals-bundled-test`, A2 to A7) is `(link/composition
  {:content-store store :name-env {'py address} :derivation :trusted})`,
  because the test namespace is the publisher. A1 publishes with
  `publish-module!`, whose `link-local` runs `:verifying`, once per
  namespace; that is the verifying evidence on each host. One further
  test, `a-verifying-serve-links-py` (`^:slow`, guarded), runs one fixed
  program on the semantic VM over a `:verifying` composition and asserts
  output parity with the `:trusted` run and `:trust :verified` in the
  registry entry.
- Section 4, new A11, publication cost reported: the report states the
  JVM time of `publish-module!` for `py` and of one linked program run
  per vector VM against the same program bundled, from the slow set's
  output. Target: publish under 60 s; a linked run within twice the
  bundled run. Above target is a finding for the Architect, not a
  reason to tune.
- Section 4, new A12, size budget: `linked_prelude_test`,
  `module-tree-fits-the-default-bounds-with-headroom-test`: the row
  count of `(vm/ast->semantic-bytecode prelude/module-uast)` is below
  half of `(:max-parts linker/default-bounds)` and its deepest
  occurrence path is below half of `:max-depth`; both derived from the
  tree at test time, no hand-kept figure (I10).
- Section 4.10: the time report already asked for covers A11.
- Section 5, permitted: using `publish-module!`'s three-argument arity
  is not needed and not used; the default bounds carry `py`.
- Section 5, prohibited: unchanged, and now with a pointer: "a scanner,
  bound or publication finding is routed to L-f or to a new linker
  slice, never fixed here".
- Section 6, deferred: adds the items of section 7 below.

Work the P2 engineer may do now on `yang-python-c4-p2`, before L-f
lands: section 3 steps 1 to 4 (tests, `prelude.cljc`, `lower.cljc`, the
section 2.3 discharge arm) and step 8's text, iterating with
`clojure -M:test -n yang.python.antlr.prelude-parity-test` and the
lower and linker namespaces; `bb test:clj` must stay green with the
linked tests failing only on the missing harness. The branch rebases on
master once L-f lands; the discharge arm touches `declared-discharge`
and `discharge-defect`, which L-f does not, so the rebase is clean.

## 7. Deferred, none blocking L-f or P2

- `dao.space.query`: a prefix index over path-valued columns, or rule
  memoisation keyed by the bound variables, so `occ-bound?` under `not`
  is a lookup instead of a scan. Owner: the query seat. Until then the
  tree scanner is the walk, and the rules are the oracle.
- `undischarged`: per-definition dominance over application sites
  computed once (R5), if a later module makes it visible.
- Module packages for the Python runtime (I2) and the question of
  whether `py.b` and `py.rt` become their own modules; a semantics
  decision, not a sizing one (R8).
- The chain layout's depth: unmeasured and moot after D5; `:max-depth`
  256 now pins the wide shape.
- The content-pair cost of a `:verifying` tree fetch (5688 round trips)
  for a remote serve; the ring capacity is the only knob L-f may touch.
  A streaming multi-part fetch is a linker design item if the figure in
  the L-f report is large.

## 8. Risks named

- The direct walk changes a scanner's implementation, not its
  relation; the fence is the conformance test, and the shared-row
  fixture is the one case where a careless walk would silently
  undercount. L-f's reviewer reads that fixture first.
- R3 touches `step`'s completion path; the existing tree fetch tests
  (`a-tree-fetch-carries-its-parts-and-obligations` and the three
  refusal tests at `linker_test` 1037 to 1124) must stay exactly green,
  since they pin that a requested tree image still scans.
- Raising `:max-parts` widens what a hostile peer can make a node fetch
  before `:parts-limit`; the byte bound is unchanged and remains the
  envelope, and the DHT's own `:max-backlog` is separate.

## 9. Files

Read, not changed: `src/cljc/yin/vm/linker.cljc`,
`src/cljc/yin/vm/linker/publish.cljc`,
`src/cljc/yin/vm/linker/closure.cljc`, `src/cljc/yin/repl/link.cljc`,
`src/cljc/yin/vm/linker/dht.cljc`, `src/cljc/yin/vm/engine.cljc`,
`src/cljc/yin/vm.cljc`, `src/cljc/dao/space/query.cljc`,
`test/yin/vm/linker_test.cljc`, the three design documents named at
the top, `docs/agents/build-n-test.md`, `docs/agents/format.md`.

Diagnostic written under `collab/`, never staged:
`collab/p2probe-count.clj` (tree size, occurrence counts, the timed
direct walk and its distinct free names; loads
`collab/p2probe-walk-defs.clj`).

Nothing under `src/`, `test/` or `docs/` changed. Nothing staged, nothing
committed.
