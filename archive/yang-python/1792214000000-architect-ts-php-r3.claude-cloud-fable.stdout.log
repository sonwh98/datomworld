Completed-GMT: 2026-10-17 07:41:23 GMT
Completed-Local: 2026-10-17 14:41:23 +0700

# Revised designs, round 3: one lifecycle mechanism on the cell and linker substrate

Role: Lead System Architect. Findings only; nothing edited. This round answers the fresh-thread codex review (`collab/1792212000000-reviewer-ts-php-designs-r2-fresh.gpt-6.1-sol.findings.md`): three P1s and one P2. All four are accepted. Per the instruction, the three P1s are solved as one mechanism revision (§0) and then re-woven into the PHP reference model (§B1), the ES module lifecycle (§A8) and the claims coordinates (§A10); the P2 rescopes T10 (§A11). Everything the reviewer confirmed closed (two-form strings, canonical `%` renaming, the pinned emit profile with `const enum` as object, the grammar patch gate, the three-VM linked qualification, the PHP tick dependency) is unchanged.

I re-checked the committed `scc-module-specs` (`lower.cljc:2193-2285`): the alias module's tree is `(require bundle)` plus `(yin/def 'spec bundle/m<i>-spec)` and `(yin/def 'body bundle/m<i>-body)`, so the reviewer is right that a structural path from an alias root cannot reach a binder; the binders live under the bundle root. I also re-derived the PHP reference cases against the engine's rule (`zend_array_dup` unwraps a reference whose refcount is one, except a slot referencing its own containing array; array assignment addrefs the hashtable, not the inner reference).

## 0. The mechanism: declare, commit, release

The three findings have one cause. In each, round 2 let an identity or a lifecycle state be decided by an *executing* step (a write, a body finishing, a lowering emitting a literal) when the substrate fixes it at a *declaring* step. The correction is one record shape, used three times:

```clojure
;; a lifecycle record: always a cell (identity = the cell), never a value
{:lc/status   :declared | :committed | :released | :failed
 :lc/group    <the set of records that commit or fail together>
 :lc/holders  <explicit count of declared holders; nothing implicit>
 ...payload}
```

Rules, each binding all three instances:

1. **Identity is minted at declaration.** The declaring operation allocates the cell (`cell/new`) and fixes every coordinate that names the thing (a shared reference's cell id; a module's environment cells; a binder's owning root and path). No executing step re-mints.
2. **Commitment is per group, atomic.** A member of a strongly connected group is `:committed` only when the whole group is; a failure anywhere in an unfinished group marks every unfinished member `:failed` with the same cause. Nothing is committed "as it finishes".
3. **Release is explicit.** A holder leaves by an explicit operation (`unset`, rebinding, an explicit import drop); a copy never implicitly adds a holder and a scope exit never implicitly removes one. What cannot be observed is not counted; the departures this causes are named.
4. **Content reads look through the record; content writes consult its state.** A read dereferences; a write to a record whose holders say "unshared" demotes; a write to a shared record writes through.

The instances: the PHP shared-reference cell (declared by `=&`, holders counted explicitly, released by `unset`, demoted when unshared), the ES module record (declared by link, committed per evaluation SCC, failed per SCC), and the published binder table (declared by the publisher, owned by the root that textually contains the binder, grouped by the publication SCC). The same shape also describes what the linker already does for code (install declares, first link wins, nothing is re-minted by running), which is why this composes with the store model rather than beside it: the module store holds only declared immutable code (`spec`, `init`, `body`, bundle exports); every lifecycle record lives in the task heap as a cell; and the claims tables are datoms declared at publication. No row shape, codec, engine or linker change.

---

## B1. PHP references, re-ruled (P1-1)

The engine's model, stated once so the mapping is checkable: a reference is a refcounted box; its count is the number of *locations* pointing at it, where a location is a variable, a property, or a slot of one hashtable instance. Array assignment addrefs the **hashtable**, not the inner reference. Copy-on-write separation duplicates the hashtable; during duplication a slot holding a reference with count one is **unwrapped in the copy** (the source keeps its reference), except when the reference's content is the array being duplicated.

On the persistent-value substrate there are no hashtable instances and no duplication event, so the count is defined over what the substrate can observe, and separation is replaced by rule 4:

- **The record.** `$x =& loc` allocates, or reuses, a shared cell `{:lc/status :declared :lc/holders n :value v}`. A marker `{:php/ref c}` sits in a variable cell, a property slot or an array slot. **Markers never nest**: rebinding to a location that already holds a marker reuses that marker's cell and increments `:lc/holders`; otherwise the location's content moves into a fresh cell with `:lc/holders 2` and both locations receive the marker.
- **What a holder is.** A holder is one *installation* of the marker by `=&` (or by `global`, `static`, by-reference parameter binding, `foreach (… as &$v)`, `use (&$x)`): one per variable or property location, and one per array slot the marker was installed into. **An array copy adds no holder** (the engine addrefs the hashtable, not the reference; rule 3).
- **Release.** `unset($x)`, rebinding `$x =& other`, `unset($a[k])` of a slot holding the marker, and reassignment of a *variable* that holds a marker to a non-reference value by `=&`-free means do **not** release except as the engine does: plain assignment `$x = v` through a marker writes **through** (the engine writes into the reference), so it is not a release. Release happens on `unset`, rebinding, slot removal, and `foreach` rebinding of `&$v` to the next slot. A function return releases nothing (rule 3; the departure stated below).
- **Reads** dereference one level, at every path level.
- **Writes** (`php/path-set`): walking a path, a marker at any level is dereferenced into its cell; at the terminal slot, if `:lc/holders` is 1 the slot's marker is **replaced by the written value** (demotion; the engine would have unwrapped it at the next separation), the cell is released; if greater than 1 the value is written **through** the cell. Replacement `$a[k] =& $y` releases the old marker and installs the new. Removal `unset($a[k])` releases.
- **The self-reference exception.** `$a[0] =& $a` makes `$a` itself a holder (its variable cell receives the marker), so `:lc/holders` is at least 2 and the slot is never demoted: the engine's exception falls out.
- **Renderer**: `var_dump` prints no `&` for a `holders 1` slot (unwrapped on display, as the engine's dup would have).

Checked against the cases:

| Program | Engine | This model |
|---|---|---|
| `$a=[1]; $r=&$a[0]; $b=$a; $b[0]=2; echo $a[0];` | 2 (count 2 at separation) | holders 2 (`$r`, slot): write-through, 2 ✓ |
| `$a=[1]; $r=&$a[0]; unset($r); $b=$a; $b[0]=2; echo $a[0];` | 1 (unwrapped in copy) | holders 1: demote in `$b`'s value; `$a[0]` reads the cell, 1 ✓ |
| `$a=[1]; $r=&$a[0]; $b=$a; unset($r); $b[0]=2; echo $a[0];` | 1 (hashtable addref only; count 1 at separation) | holders 1 at the write: demote, 1 ✓ (round 2's `:refs` increment gave 2, which was wrong against the engine too) |
| `$b=&$a; $c=&$a;` | one reference, count 3 | one cell, holders 3, no nesting ✓ |
| `$a=[1]; $a[0]=&$a; $b=$a; $b[0][0]=5;` | reference kept through dup | `$a` is a holder: holders 2, write-through ✓ |
| `$r=&$a[0]; $b=$a; $b[1]=9; unset($r); $b[0]=2; echo $a[0];` | 2 (both separated hashtables hold the reference, count 2) | holders 1: demote, **1** ✗ |

The last row is the **one departure**: two already-separated arrays sharing a reference that no variable holds. The substrate cannot observe the separation that would have added the second slot holder (rule 3). It is pinned as a departure fixture in P3, beside the scope-exit departure (a reference whose extra holder left scope without `unset` stays shared longer than the engine's). Both are listed in the support profile. Round 2's `:refs` set on array values is withdrawn: it counted the wrong event and broke the second row.

P3 fixtures: the six rows above (five parity, one departure), nested-path references, `foreach` by reference leaving `$v` bound to the last slot and the classic second-loop overwrite, `global` and `static` as holders, by-reference parameter binding released at the callee's `unset`, `var_dump` of a `holders 1` slot without `&`.

---

## A8. ES modules, re-ruled (P1-2)

Round 2 marked a module `:evaluated` when its own body returned. The specification's `InnerModuleEvaluation` keeps unfinished members of a strongly connected component on the DFS stack and commits or fails them together; the reviewer's Node probe confirms both members rethrow the same error object. Rule 2 applied:

**Module record** (a cell in `js.rt/modules`, keyed by resolved specifier):

```clojure
{:js/type :module :spec s
 :lc/status :unlinked | :linking | :linked | :evaluating | :committed | :failed
 :env {name cell}                 ; declared at link, rule 1
 :namespace ref | nil
 :dfs-index n :dfs-ancestor n     ; the spec's two fields, during evaluation only
 :error e}                        ; :failed only
```

**Phase 1, `js/link-module` (declare).** Over the static graph from `spec` (`:imports`, `:star-exports`), depth first:
1. Deliver each module through the I4-a thunk (a refusal is a link-time `SyntaxError`-class error before any body); create records `:linking`.
2. **Declare environments** for every module of the graph before resolving anything: one cell per top-level binding (`:js/tdz` for `let`/`const`/`class` and for function bindings until step 3; `:js/undefined` for `var`).
3. Run every module's `init` to fill hoisted function cells (functions close over their own `%env`, so a cyclic call into a hoisted export of a module whose body has not run resolves).
4. Resolve imports: an import binding's env entry **is the exporter's cell** (live binding by shared cell); `import * as ns` gets the exporter's namespace object (an exotic object reading env cells, frozen; declared here); `export * from` resolves transitively with the spec's ambiguity rule; a **missing export is a link error raised here**, so no body has run (fixture: no side effect observed, no record left `:linked`). Records become `:linked`.

**Phase 2, `js/evaluate-module` (commit per SCC).** The spec's algorithm, with an explicit stack in a cell (`js.rt/eval-stack`):
- Entering a `:linked` module: status `:evaluating`, assign `dfs-index` and `dfs-ancestor` from a counter, push.
- For each required module in order: if `:failed`, rethrow its `:error`; if `:committed`, continue; if `:evaluating` (a cycle), lower this module's `dfs-ancestor` to the min of both; else recurse and then take the min of ancestors.
- Run `body`. If it throws: every module on the stack from this one upward **and every module still `:evaluating` above it** (the unfinished component) becomes `:failed` with **the same error object**; the stack is cut there; the error propagates.
- After `body`, if `dfs-index` equals `dfs-ancestor`, this module is the SCC root: pop the stack down to and including it and mark every popped member `:committed`. Otherwise leave it on the stack: it commits with its root.

Consequences, as fixtures (T7): `a → b → a` where `b`'s body completes and `a`'s body then throws: both `:failed`, a later `import "./b"` and a later `import("./b")` both rethrow or reject with the **same** error object, neither body runs again; `a → b`, `b` fails, `a` fails with `b`'s error, `b`'s record is `:failed` not `:evaluating`; a committed SCC's members all read `:committed` after the root pops; hoisted-function call across a cycle before the exporter's body (works); a TDZ read across a cycle (`ReferenceError`, which fails the component); dynamic `import()` of a `:committed` module resolves without re-running; `import()` from inside a body of a module whose SCC is still `:evaluating` sees the in-progress component (the spec's behaviour; pinned by fixture).

**Top-level `await`** is refused in Phase 1 (`:yang.ts/unsupported :top-level-await`): the asynchronous evaluation variant of the algorithm (`async-parent-modules`, `pending-async-dependencies`) is a later slice; with it refused, evaluation is synchronous and the algorithm above is complete.

**Over the store model.** The module store holds `spec`, `init`, `body` (or bundle exports), immutable after install, first link wins. Every record above is a heap cell declared and driven by prelude code in the importing task (C4 ruling 1: install delivers code, instantiation is the task's own evaluation). The publication SCC (I5 bundles) and the evaluation SCC are computed from the same static edges and so coincide for static roots, but evaluation never assumes it: a dynamic `import()` root starts its own DFS over the records, and a bundle member is just a module whose code lives in a bundle. Two tasks importing one module have independent records, as Python's module objects do.

---

## A10. Claim coordinates, re-ruled (P1-3)

Round 2 put a binder table into `spec` and said alias specs would carry "paths into the bundle root". The alias tree holds references to bundle exports, not the bodies, so a path from an alias root reaches no binder. Rule 1 applied: **the owning root of a binder is the root of the tree that textually contains it**, declared by the publisher, and the mapping is a published table, not a row.

- **Owning root.** For a standalone module, its module tree root `A`. For an SCC member `m_i`, the bundle root `A_scc`: its binders are under the `(yin/def 'm<i>-init …)` and `(yin/def 'm<i>-body …)` definitions. For the `js` prelude, the prelude module tree root. An alias module owns no binders.
- **Occurrence path vocabulary** (defined, closing the reviewer's `:syms` point). A path is a vector of row-grammar slot steps as `yang.safepoint` walks them (`pos` for a `:node` slot, `[pos i]` for a `:nodes` element) **extended by one terminal step for binders**: `[:params i]` names the i-th parameter symbol of the `:lambda` the path reaches; `[:def-key]` names the literal key operand of a `yin/def` application. Node occurrences stay bare paths. The typecheck observer indexes claims by this reified pair; `[:params i]` and `[:def-key]` are the two binder forms.
- **The binder table** is a publication artifact, declared by the publisher beside the manifest (datoms, like the site sets of C4 ruling 14): `[<manifest-address> :yang.ts/binders {"x" [A_owner path] ...}]`, plus `[<manifest-address> :yang.ts/owning-root A_owner]` and, for a member, `[<manifest-address> :yang.ts/bundle <bundle-manifest-address>]`. The publisher computes it because only the publisher knows the bundle layout and the member order. The I5 construction is deterministic, so the table is reproducible. Nothing goes into `spec`; round 2's `:binders` key in `spec` is withdrawn, which also removes the address movement it caused.
- **Resolution.** Compiling a `.d.ts` against a module: the pinned input is the target's manifest address; the compiler reads its binder table; each declaration's claim is minted as `[[A_owner path] :yin.sig/type v]`; the type environment claim goes on `[A_owner [:def-key]]` of the owning root's `spec`... for a member, of the member's `m<i>-spec` definition (a binder occurrence that exists in the bundle tree). `js-lib.d.ts` resolves the same way against the prelude's manifest.
- **Which packet, which watermark.** The observer evaluates tree packets. For a standalone module the packet is `A`'s; for an SCC the packet is the **bundle's** `A_scc`, which contains every member's binders, and the alias packets carry no claims and need no watermark (they contain no binders; their verdict is trivially `:accepted` or `:dynamic` per checker parameter). `claims-t` is attached to the envelope of the packet that owns the binders: the publisher appends the bundle's claims, then the bundle envelope with `:yang.claims/t`, then the alias envelopes. Claims minted later by a `.d.ts` compiled against an already-published module arrive after that watermark; they bind on the **next** packet carrying that root (a republication or a checker re-run over the stored packet), never retroactively: this is the typecheck design's own causal rule, stated here so T10 tests it rather than assumes it.
- **Identity.** No claim or table touches rows; annotating moves nothing; changing the export set no longer moves `A` either (the table is beside the manifest).

**T10 validation on cyclic modules**: for a two-member SCC, every entry of each member's binder table resolves into `A_scc` and the terminal `[:params i]` lands on a parameter whose symbol is the canonicalized binder of the named export (the test walks the tree); a `.d.ts` compiled against a member mints claims whose root is `A_scc`; the observer, fed the bundle packet and the member's claims, cites those claims in its verdict; the alias packets pass with no claims.

---

## A11. T10 rescoped (P2)

The T1 checker of `yin.vm.typecheck.md` §4 is a claims-consistency checker: arity at a meeting point and conflicting declarations of one binder. Argument-type checking is a type system and out of its scope. T10 now claims exactly what that checker can decide:

- **Arity**: `f` declared `(n: number) => void` on its binder, call `f(1, 2)` and call `f()`: refused with the arity rule, citing the one claim.
- **Conflicting declarations**: `export let x: number` in the module and a `.d.ts` against the same module claiming `x: string`: refused as a binder declared twice with unequal types, citing both claim refs. This is the fixture that exercises the binder table and the `.d.ts` resolution end to end.
- **Dynamic boundary**: a free name with no claim yields `:dynamic`, exercised both ways through the checker's `:dynamic` parameter.
- **Identity and topology**: unchanged from round 2 (annotating changes no address; `report` leaves evaluator output unchanged; the `claims-t` barrier delays evaluation of a packet whose claims are late).

Argument-type refusal (`f("x")`) is explicitly **deferred to a checker slice outside both frontends** (typecheck.md "Deferred: any real type language"); the TS design supplies claims and coordinates, not a checker.

---

## Updated slices and owner decisions

| Slice | Change in this round |
|---|---|
| T7 | link phase as declared (unchanged), evaluation as the SCC-committing algorithm above; TLA refused; the seven cycle and failure fixtures |
| T10 | binder tables as publication datoms; the path vocabulary with `[:params i]` and `[:def-key]`; cyclic-module validation; the rescoped checker fixtures |
| L3 | the lifted publisher gains the binder-table emission hook (a function the frontend supplies; Python supplies none) |
| P3 | the holder model above; the six-row fixture table; `:refs` withdrawn |

Owner decisions added or changed:

- **A12.11** Binder tables as publication datoms beside the manifest (recommended) versus inside `spec` (withdrawn: cannot name bundle binders, moves `A`).
- **A12.12** Top-level `await` refused in Phase 1 (recommended) versus implementing the asynchronous evaluation variant in T7.
- **B8.8** (revised) The holder model counts installations, not array copies, with the two named departures (separated-arrays sharing with no variable holder; scope exit without `unset`). Alternatives: per-array-instance tracking (not expressible on persistent values without an instance identity, which would reintroduce the host-object aliasing §8.11 forbids) or refusing references inside arrays (a large semantic hole).

---

## What I expect round 3's reviewer to probe

1. **The PHP departure row**: whether "two separated arrays sharing a reference with no variable holder" is really the only miscount, in particular with `foreach` by reference over a copied array and with references installed through `array_walk`/`extract` paths; and whether `global $x` inside a function called twice counts two holders (it does, and the second call's `unset`-less exit is the scope-exit departure).
2. **The SCC evaluation algorithm**: whether `import()` from inside an `:evaluating` component is pinned to the spec (it is: the dynamic import observes the in-progress records and either waits on the same stack or, synchronously, sees `:evaluating` and treats it as the cycle case) and whether cutting the stack at the failing module marks the right set (every record with `dfs-index` at or above the failing module's, plus the component's members below it that have not yet popped, which the spec handles by failing everything still on the stack from the failing module upward and letting the exception propagate through each ancestor, which is what the algorithm above does).
3. **Binder tables and late claims**: whether "claims arriving after the watermark bind on the next packet" is acceptable or whether T10 needs a republication-free re-check path; and whether `[:def-key]` is accepted by the typecheck design's authors as a binder occurrence.
4. **Whether `init` as a third export and the binder table as datoms disturb the linker's publication contract** (`publish/requirements` pins operator `require` only; the table adds datoms, no requires).
5. **The composition argument of §0 itself**: a reviewer may ask whether the three instances share code. They share a shape and four rules, not a namespace; the shared-reference cell, the module record and the binder table have different payloads and different drivers, and I do not propose a `yang.core.lifecycle` abstraction, because the invariant "identity at declaration, commitment per group, explicit release" is a design rule the tests pin, not a library.
