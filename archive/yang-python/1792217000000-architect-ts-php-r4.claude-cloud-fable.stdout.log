Completed-GMT: 2026-10-17 07:50:31 GMT
Completed-Local: 2026-10-17 14:50:31 +0700

# Revised designs, round 4: exhaustive lifecycle specifications

Role: Lead System Architect. Findings only; nothing edited. This round answers the round-3 codex review (`collab/1792216000000-reviewer-ts-php-r3.gpt-6.1-sol.findings.md`): three P1s and one P2, all accepted. Per the instruction, each lifecycle is now specified exhaustively (every operation against every state), with the fixture set enumerated per row, instead of fixture-by-fixture patches. The reviewer's closing remark is also accepted: the three instances are separate drivers; §0 of round 3 is reduced to the design rules the tables below obey, and no shared abstraction is proposed.

## 1. PHP references: the complete model

### 1.1 Why round 3 failed, and the one change that fixes the class

The engine's reference refcount counts **hashtable instances**. Rounds 2 and 3 tried to count holders over persistent values, which have no instance identity, so every copy/remove/separate case fell between the rules. The fix is to give an array an instance identity **exactly when the engine's bookkeeping needs one**, and never otherwise:

- A **plain array** is a persistent ordered-map value, stored directly in its holder (the §8.11 rule). Invariant: **a plain array contains no reference marker and no instance handle, transitively.** Copying it is O(1) and needs no counting because there is nothing inside it to count.
- An **instance array** is a heap cell `{:php/type :arr :rc n :value <ordered-map> :deps #{keys}}`: `:rc` is the engine's hashtable refcount, `:deps` the set of keys whose slots hold a marker or a handle (small; usually empty). A holder stores the handle `{:php/arr h}`.
- A **reference** is a heap cell `{:php/type :ref :holders n :value x}`; a marker `{:php/ref c}` sits in a variable cell, a property slot or an **instance** slot. `:holders` is the engine's `zend_reference` refcount exactly: one per variable/property holder plus one per instance slot holding the marker.
- **Instantiation**: an array becomes an instance the moment a marker or a handle is installed into one of its slots. Installing at a path converts every plain array on the path to an instance (rc 1 each, the parent slot takes the handle, `:deps` updated). So every container between a variable and a marker is an instance, and every copy of one is a counted operation.
- **Separation** is explicit and happens exactly where the engine duplicates: a write through a handle whose `:rc > 1`. Its rule is `zend_array_dup`'s.
- **Unwrapping** happens only at separation (a holders-1 marker is unwrapped *in the copy*; the source keeps it). There is no "demotion at write": an in-place write through a holders-1 marker writes into the cell, as the engine does, which is observably identical.
- **Scope exit** is a counted release under the profile `:yang.php/exact-refcount true` (default on): the lowering wraps every function body whose scope declares a local in `php/try-finally` with `php/release-locals`, run on normal return and on every escape. Off, scope exit releases nothing and the departure D-scope below applies.

The one *unavoidable* departure: an object that dies without `unset` releases nothing (no finalizers), so handles and markers held only by a dead object's properties stay counted (D-object). An unneeded separation caused by an over-count is **unobservable**: with the marker's holders at 1 the unwrapped copy and an in-place write read the same; with holders above 1 the copy keeps the marker and writes through, as the in-place write would. Over-counting therefore costs allocation, never semantics. Under-counting is impossible because every decrement corresponds to an explicit acquire.

### 1.2 Contents and operations

Content kinds a location can hold: `S` scalar/string/null/object handle, `P` plain array, `I1` handle to an instance with `rc = 1`, `In` handle with `rc > 1`, `R1` marker to a cell with `holders = 1`, `Rn` marker with `holders > 1`. A location is a variable cell, an object property slot, or an instance slot. "Release" and "Separate" are the two internal procedures:

```text
Release(content):   S, P -> nothing (P is marker- and handle-free by invariant)
                    I(h) -> rc(h) -= 1; if 0 then Destroy(h)
                    R(c) -> holders(c) -= 1; if 0 then Release(value(c)); c is garbage
Destroy(h):         for k in deps(h): Release(slot k of h); h is garbage
Separate(h) [rc>1]: h' := new instance {rc 1, value(h), deps(h)}; rc(h) -= 1
                    for k in deps(h), slot content x:
                      I(g)              -> rc(g) += 1                         (dup addrefs nested ht)
                      R(c), holders>1   -> holders(c) += 1                    (both hashtables hold it)
                      R(c), holders=1, value(c) is handle to h itself
                                        -> holders(c) += 1                    (self-reference exception)
                      R(c), holders=1   -> slot k of h' := value(c) as a copy  (unwrap in the copy:
                                           P shares; I(g) rc(g)+=1; S copies); deps(h') drops k
                                           unless the value is a handle
                    the writer's location now holds h'
```

### 1.3 The transition table

Each row: the operation, then its effect for each content kind of the location it reads from or writes to. "walk" means: at each path step, a marker is dereferenced into its cell; a handle with `rc > 1` is separated and the parent location rebound to `h'`; a plain array is updated persistently.

| # | Operation | S | P | I1 | In | R1 | Rn |
|---|---|---|---|---|---|---|---|
| 1 | Copy `$y = $x` (also by-value param bind, return, array-literal element, `foreach` by-value snapshot) | copy | share O(1) | rc+1 → In | rc+1 | deref, then the row of value(c) | same |
| 2 | Bind `$y =& $x` where `$x` holds | new cell c{holders 2, value}; both locations ← marker | same (P moves into c; no instantiation of `$x`'s own container needed unless `$x` is a slot: then instantiate the path to it) | same, value is the handle | same | `$y` ← same marker; holders 2 | holders+1 |
| 3 | Bind into a slot `$y =& $a[k]` / `[&$x]` / `foreach as &$v` / by-ref arg `f($a[k])` | instantiate path to `$a[k]` (plain → I1 at each level, handles installed, deps updated); then row 2 on the slot; `deps(h) += k` | | | | | |
| 4 | Write `$x = v` (variable or property, not through `&`) | Release(old); store | same | same (rc−1 of old) | same | write **through**: value(c) := v (holders unchanged) | same |
| 5 | Path write `$x[k…] = v` | TypeError/auto-vivify per PHP | persistent update | walk; in place | **Separate** then in place | at terminal slot: write through | same |
| 6 | Store into a slot, terminal content kinds | S/P: set; if slot held I/R: Release(old), deps−k | storing a handle: rc+1, deps+k | | | | |
| 7 | `unset($x[k])` | — | remove | walk (separate if rc>1), remove; Release(slot content); deps−k | same after Separate | slot R1: holders→0, Release(value) | holders−1 |
| 8 | `unset($x)` (variable/property) | unbind | unbind | Release → rc−1 (Destroy at 0) | rc−1 | holders−1 → 0, Release(value) | holders−1 |
| 9 | Rebind `$x =& other` | Release(old) then row 2 | same | same | same | holders−1 then row 2 | same |
| 10 | Read `$x`, `$x[k…]` | value | value | value(h) (no count) | same | deref (no count) | same |
| 11 | Scope exit (exact-refcount on) | — | — | Release every local: row 8 per local | | | |
| 12 | Scope exit (off) | nothing (departure D-scope) | | | | | |
| 13 | `foreach ($a as &$v)` | per iteration: Release(`$v`'s old marker) then row 3 on slot i; after the loop `$v` keeps the last marker | | | | | |
| 14 | `global $x` / `static $x` | row 2 against the `$GLOBALS` entry / the function's static cell (install creates it as null if absent) | | | | | |
| 15 | Builtins consuming arrays | read (row 10); results are plain arrays (departure D-builtins: functions the engine documents as preserving references, such as `array_values` over a shared slot, return marker-free copies) | | | | | |
| 16 | `$a[0] =& $a` | row 3 instantiates `$a` (I1) and installs; `$a`'s own variable becomes a marker holder too; at separation the self-reference exception keeps it | | | | | |

Every count change in the table is an explicit lowering operation; no copy acquires a holder it does not count, and no removal releases one that was not acquired. The reviewer's three programs:

| Program | Engine | Table |
|---|---|---|
| `$a=[1]; $r=&$a[0]; unset($r); $b=$a; $b[0]=2; echo $a[0];` | 1 | row 3: `$a` I1, c{2}; row 8: c{1}; row 1: rc 2; row 5: Separate → unwrap (holders 1) into `$b`'s copy, write; `$a[0]` reads c, **1** |
| `$a=[1]; $r=&$a[0]; $b=$a; unset($r); $b[0]=2; echo $a[0];` | 1 | c{2}, rc 2, c{1}, Separate → unwrap, **1** |
| `$a=[1]; $r=&$a[0]; $b=$a; unset($b[0]); $a[0]=2; echo $r;` | 2 | c{2}, rc 2; row 7: Separate `$b` (holders>1 → c{3}), remove → c{2}; row 5 on `$a` (rc 1) in place, through c → `$r` **2** |

### 1.4 Fixture set (P3), `ref-lifecycle-v1`

- **One fixture per non-empty cell of the table** (rows 1 to 16 against each applicable content kind), each a PHP program whose expected output is produced by the pinned PHP 8.3 CLI; the generator script and the fixture file are checked in (the `c3-corpus-v1` pattern).
- **The permutation corpus**: over the operation alphabet `{C: $b=$a, U: unset($r), W: $b[0]=2, X: unset($b[0]), A: $a[0]=3, D: unset($b), N: $c=$b; $c[1]=9}` starting from `$a=[1]; $r=&$a[0];`, every sequence of length 2 to 5 (duplicates allowed), printing `$a`, `$b`, `$c`, `$r` with `var_dump`, oracle from the engine. This is the "every order" requirement made mechanical: roughly 20,000 programs, generated and compared in the slow tier; a sample of 200 in the quick tier.
- **Nested paths**: the same alphabet with `$a['x'][0]` and `$r =& $a['x'][0]`, exercising instantiation along the path and nested separation.
- **Self reference**: `$a[0]=&$a` then copy and write.
- **Scope exit**: a function that separates a copy holding a live reference and returns without `unset`, under both profile values; the exact profile matches the engine, the other is the D-scope fixture.
- **Objects**: a property holding an instance array and a marker, with `unset($obj->p)` (counted) and object death (D-object fixture).
- **Builtins**: `array_values`, `array_merge`, `array_slice` over an array with a shared slot (D-builtins fixtures).
- **Renderer**: `var_dump` prints `&` for a slot whose marker has `holders > 1` only (the engine shows the reference flag only for shared references; verified by the oracle, not assumed).

Owner decision **B8.8 (revised)**: instance arrays on demand with exact engine bookkeeping (recommended), `:exact-refcount` default on. Departures: D-object, D-builtins, and D-scope only when the profile is off. Cost: one cell per array that ever receives a reference, O(|deps|) per separation, one `try-finally` frame per call of a function with locals.

---

## 2. ES modules: the complete operation matrix

### 2.1 Phases and the job rule

Three phases over module records (cells in `js.rt/modules`, status `:unlinked | :linking | :linked | :evaluating | :committed | :failed`, plus `:error`, `:dfs-index`, `:dfs-ancestor`):

- **Link** (declare): deliver the static graph from the root, create records, **allocate every environment cell of every module in the graph**, run every `init` (hoisted functions), resolve import bindings to the exporters' cells, build namespace objects, detect missing or ambiguous exports. Link is idempotent: a record already `:linked` or beyond is skipped; a link failure leaves every record it created `:unlinked` again (no partial environments survive). Only `:unlinked` records are touched.
- **Evaluate** (commit per component): the DFS of round 3 with an explicit stack; members commit together when their root pops; a throw fails every record on the stack from the thrower upward, all with the same error object, and propagates.
- **Settle** (dynamic only): a promise is resolved with a namespace or rejected with an error, from the module's **final** status.

**The job rule** (P1-2): a dynamic `import(spec)` performs no linking or evaluation synchronously. It creates a promise and enqueues a **job** on the prelude's job queue; the job runs only when no evaluation is in progress, which is guaranteed because synchronous evaluation cannot suspend (top-level `await` is refused in Phase 1) and the job queue is drained by the scheduler between jobs, never inside a body. Round 3's synchronous `:evaluating` shortcut is withdrawn. The job then runs Link and Evaluate from `spec`'s record as a fresh top-level evaluation (new DFS stack) and settles from the record's status. Two consequences fall out: a module that calls `import("./self")` and then throws `e` has status `:failed e` when the job runs, so the promise rejects with **that `e`**; a module that imports itself and succeeds resolves to its own namespace after its component committed.

### 2.2 The matrix

Rows are the operation; columns the status of the target record **at the moment the operation reaches it**.

| Operation | `:unlinked` | `:linking` | `:linked` | `:evaluating` | `:committed` | `:failed` |
|---|---|---|---|---|---|---|
| Static import, link phase | deliver, create, allocate env, run `init`, resolve (recursively) | cycle in the static graph: use the record's env as is (already allocated); no recursion | skip | skip | skip | skip |
| Static import, evaluate phase (from an evaluating importer) | impossible (link completed first) | impossible | recurse: push, index, run; ancestor = min | **cycle**: importer's ancestor := min(importer, target); no recursion; proceed | continue | rethrow `:error` (fails the importer's component) |
| Body throws | — | — | — | every record on the stack from the thrower upward → `:failed` with this error; propagate | — | — |
| Body returns, this is the SCC root | — | — | — | pop to and including self; all popped → `:committed` | — | — |
| Body returns, not the root | — | — | — | stay `:evaluating` on the stack | — | — |
| `import(spec)` expression (synchronous part) | create promise, enqueue job | same | same | same | same | same |
| `import()` job runs | Link from this record (may add records; a link error rejects the promise, records restored to `:unlinked`), then Evaluate as a new top-level DFS, then settle | impossible (no link in progress between jobs) | Evaluate as a new top-level DFS; settle | **impossible by the job rule**; a defect if observed (asserted) | resolve with the namespace | reject with `:error` |
| Two `import()` jobs for one module | first job links and evaluates; the second sees `:committed`/`:failed` and settles accordingly; each has its own promise | | | | | |
| `import()` of a member of a component that failed elsewhere | reject with the component's error (the same object every member carries) | | | | | |
| Static import of a module previously evaluated by a dynamic job | link skips it; evaluate sees `:committed` or `:failed` | | | | | |
| Dynamic import of a module whose static graph includes already-committed modules | link skips them; the new DFS treats them as `:committed` and does not re-run bodies | | | | | |
| REPL `(reset)` | all records dropped with the task (one task per process, as Python) | | | | | |

**What is committed when.** Environment cells and hoisted functions: at link, before any body (so a cyclic call into a hoisted export works). Namespace objects: at link (reading env cells live). Status `:committed`: only when the component root pops. Status `:failed`: for every unfinished member on the stack above the thrower, at the throw. Promises: at the end of the dynamic job, from final status, never earlier.

**Why the dynamic path composes with the static rules.** Both run the same `link-module` and `evaluate-module` over the same records; the dynamic path differs only in its entry (a job after any synchronous evaluation has finished) and its exit (settlement from final status). Because link only touches `:unlinked` records and evaluate only recurses into `:linked` ones, a dynamic graph that overlaps a static one re-does nothing, and because no evaluation is in progress when a job runs, a dynamic import can never observe `:evaluating`. Publication SCC bundles are invisible to both: a member's record is an ordinary record whose code lives in a bundle.

### 2.3 Fixtures (T7)

Cycle commit and fail (round 3's set), plus: `import("./self")` then throw: the promise rejects with the identical error object, in the same microtask order Node gives; `import("./self")` then success: resolves to the namespace after commit, `.then` runs after the module's synchronous body completes; two `import()` of one module resolve to the same namespace object with the body run once; `import()` of a member of a failed component rejects with the shared error; `import()` whose link fails (missing export in a newly delivered module) rejects with the link error and leaves no partial record (a later correct import links afresh); dynamic import of a module whose graph contains committed modules does not re-run them (side-effect counter); an `import()` expression evaluated inside a body does not run before the body returns (ordering fixture against Node); top-level `await` refusal diagnostic.

---

## 3. Claims: the evidence-replay rule

The observer (typecheck.md §2.2 item 3, §7) indexes a packet's claims from its `claims` cursor up to `claims-t`, emits the verdict, frees the index and advances. A late `.d.ts` claim arrives after that cursor has moved on, so "bind on the next packet" (round 3) gave the observer no way back to the original claims. Rule:

- **What evidence.** Every claim row on the `claims` medium whose occurrence root is the packet's owning root, lying in the position interval `[from, t]`, where `from` is the position of the first claim ever appended about that root and `t` the watermark of the recheck. Both original and late claims are in that interval; nothing is reconstructed from memory or from a prior verdict.
- **Who records `from`.** The publisher, at first publication: `[<manifest-address> :yang.claims/from <position>]` beside the manifest and the binder table (§A10). A root with no claims has no `from`, and no recheck is ever requested for it.
- **Who requests a recheck, and when.** A composition-level **recheck requester** (not the frontend, not the observer): it appends a packet to `typecheck-in` that is **byte-identical** to the published tree packet (the observer's "forwarded bytes equal input bytes" law holds trivially) with an envelope carrying `:yang.claims/from` and `:yang.claims/t` (the current `claims` position). It is triggered by a late declaration publication (the `.d.ts` compile appends its claims, then the requester appends the recheck) or on demand.
- **How the observer replays.** On a packet carrying `:yang.claims/from`, the observer opens a **fresh cursor** on `claims` at that position (an explicit `stream/cursor` anchor; the medium's retention is a declared policy, §6.4), reads to `t`, indexes only rows for this root, evaluates the checker, emits the verdict citing exactly the `claim-refs` it used, frees the index, and discards the fresh cursor. Its ordinary cursor is untouched, so the per-packet bound of §2.2 item 3 and every §7 prohibition hold (no prior verdict, no `dao.space.index`, no unbounded buffer). A `gap` on the fresh cursor (retention lost) yields `:refused` with rule `:evidence-unavailable`: an honest verdict, never a silent accept.
- **Topology.** In `gate`, the recheck packet is forwarded or withheld like any other; evaluators that already hold the earlier forwarded copy are unaffected (a verdict cannot un-run a program; the recheck's refusal is a fact a composition reads). In `report`, the new verdict simply supersedes by time. This is the typecheck design's own "verdicts cite their evidence" (decision 10) applied to time.

**T10 sequence fixture** (exactly the reviewer's order): publish module with `export let x: number` (claims appended at `from`), first packet → `:accepted` citing one ref; compile a `.d.ts` against the module claiming `x: string` (late claims appended); recheck packet with `[from, t]` → `:refused` (binder declared twice with unequal types) citing **both** refs; a second recheck after retention is cut below `from` → `:refused :evidence-unavailable`.

---

## 4. T10 arity fixture rescoped (P2)

Every guest call lowers to `js/call f this args`, so no guest binder is the operator of a UAST application; the T1 claims-consistency checker's arity rule has no meeting point to see, and a guest-aware analysis would be a checker beyond its contract. Ruling: T10's **frontend acceptance** uses the conflicting-declaration fixtures (§3 above and round 3's `x: number` versus `x: string`), which need only binder claims and binder tables. The **generic arity rule** is exercised by a hand-built UAST fixture, a direct application `(f 1 2)` with an arity claim on `f`'s binder, in `typecheck_contract_test`'s territory, proving observer plumbing, not TS. Guest-aware meeting points (`js/call` as a call site, PHP's `php/call` likewise) are listed as the first requirement of a future checker slice and are out of both frontends' scope.

---

## 5. Updated slices and owner decisions

| Slice | Change |
|---|---|
| P3 | the §1 model; `ref-lifecycle-v1` fixture file and generator; the permutation corpus (slow tier); `:exact-refcount` profile and its `try-finally` wrap |
| T7 | the job rule; the §2 matrix fixtures; the ordering fixtures against Node |
| T10 | `:yang.claims/from` published beside the manifest; the recheck requester as a test composition; the four-step sequence fixture; the arity fixture moved to the contract test |
| L3 | the lifted publisher records `:yang.claims/from` when a frontend supplies claims (Python supplies none) |

Owner decisions: **B8.8** revised as above (instance-on-demand arrays, exact profile default on). **A12.13** new: dynamic import strictly as a job, top-level `await` refused in Phase 1 (recommended) versus implementing asynchronous module evaluation in T7. **A12.14** new: recheck by evidence replay from the `claims` medium with a declared retention (recommended) versus a persistent claims index inside the observer (forbidden by typecheck.md §7) versus no rechecks (late `.d.ts` would then be useless).

---

## 6. What round 4's reviewer should probe

1. **The invariant "plain arrays are marker- and handle-free, transitively"**: whether every operation that could place a handle or marker into a plain array goes through instantiation (array literals with `&`, `array_walk` by reference, `extract(EXTR_REFS)`, `list()` with `&`, `compact` of a reference variable), and whether row 6 (storing a handle into a slot) can leave a plain array holding a handle (it converts the parent at store time; the table should be read that way, and the reviewer may want it as an explicit row).
2. **Separation cost**: whether `:deps` stays small in realistic programs (an array with thousands of reference slots separates in O(thousands)); this is the engine's cost too, but a reviewer may want it measured in P3.
3. **The permutation corpus size** and whether `var_dump`'s `&` flag is the right oracle for holder counts (it shows the flag only for shared references; a holders-1 marker prints plain).
4. **The job rule's dependence on "no top-level await"**: whether refusing TLA is acceptable for Phase 1 and whether any other construct can suspend a synchronous module evaluation (generators and `await` inside functions cannot; a host effect park inside a body can, on this VM, which does not exist in the engine: a parked body is still "evaluating" and a job must not run meanwhile, so the scheduler must drain jobs only when the evaluation stack is empty. I expect this to be probed and it is stated here as the rule).
5. **Evidence replay retention**: who sets the `claims` retention and what the composition does when `:evidence-unavailable` appears in `gate`; and whether a fresh cursor on an append-only medium at an arbitrary past position is supported by every `dao.stream` backing medium (file and memory media support oldest-anchored cursors today; a position anchor is a `dao.stream` question the slice must confirm).
6. **The hand-built arity fixture** being outside the TS frontend: whether that is an honest scoping of T10 or a gap the reviewer wants listed in the known-gaps ledger (I recommend the ledger row "guest-aware call meeting points for the checker", owner seat: typecheck).
