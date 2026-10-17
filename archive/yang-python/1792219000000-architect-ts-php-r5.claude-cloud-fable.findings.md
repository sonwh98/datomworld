Completed-GMT: 2026-10-17 08:04:23 GMT
Completed-Local: 2026-10-17 15:04:23 +0700

# Revised designs, round 5: the engine's own mechanics on the cell substrate

Role: Lead System Architect. Findings only; nothing edited. This round answers the round-4 codex review (`collab/1792218000000-reviewer-ts-php-r4.gpt-6.1-sol.findings.md`): five P1s and one P2, all accepted. Per the instruction, the PHP reference model is re-derived from Zend's actual operations (zval kinds, `GC_ADDREF`/`GC_DELREF`, `SEPARATE_ARRAY` and `zend_array_dup`, `zend_reference`, the `FETCH_*_W` write-address path, `ASSIGN`, `ASSIGN_REF`, `UNSET_CV`/`UNSET_DIM`, `FE_RESET_R/RW`, `SEND_VAR`/`SEND_REF`, CV destruction), not from a holder abstraction; the table rows are the opcode handlers, and the fixture set is derived from php-src's own tests plus the handlers. On the TS side the evidence record gets a stream-minted cursor anchor, late acquisition gets a mechanism, and the microtask-ordering claim is narrowed.

One correction of my own round-4 claim, which the reviewer disproved: an **extra separation** is unobservable (a copy that unwraps a count-one reference and an in-place write through it read the same), but a **missing release** is observable, because a reference left over-counted stays shared where the engine would unwrap it. Exact releases are therefore mandatory, which removes the `:exact-refcount` profile (always on) and brings objects into the counted model.

---

## 1. PHP references and arrays: the engine model, mapped

### 1.1 What Zend actually does

- A **zval** is a typed slot in a CV, a hashtable bucket, a property table, or a temporary. Refcounted kinds carry a `zend_refcounted` header: strings, arrays (`zend_array`), objects, resources, and **references** (`zend_reference { refcount, val }`).
- **Copying a zval** (`$b = $a`, `SEND_VAR`, `RETURN`, `FE_RESET_R`'s temporary, array-literal elements) copies the slot and `GC_ADDREF`s the pointee: for an array the **hashtable**, never its elements. Literal arrays are `GC_IMMUTABLE` (no counting; the first write duplicates).
- **Writing into an array** goes through a write-address fetch (`FETCH_DIM_W`, `FETCH_OBJ_W`, `FETCH_DIM_RW`): the container is `SEPARATE_ARRAY`d if its hashtable refcount is above 1 (`zend_array_dup`, then `GC_DELREF` the old table), a missing key is auto-vivified (`null`), and the element's address is returned. This is the same path for `$a[k] = v`, `$r =& $a[k]`, `f($a[k])` by reference, `foreach ($a as &$v)`, `unset($a[k])`, `$a[k][j]…` (each level separated as reached), and `$a[] = v`.
- **`zend_array_dup`** copies buckets; per element: a reference whose refcount is 1 and whose value is not the array being duplicated is **unwrapped** (the copy holds the value, `ADDREF`ed); any other refcounted element, references included, is `ADDREF`ed. The source table is unchanged.
- **`ASSIGN`** (`$x = v`): if the destination is a reference, dereference it; `garbage := old value`; copy the new value in (`ADDREF` unless moved); then destroy `garbage` (`DELREF`; at zero, `zend_array_destroy` destroys each element: nested tables `DELREF`, references `DELREF` and, at zero, their values). The reference wrapper itself is retained.
- **`ASSIGN_REF`** (`$x =& y`): fetch the source **for write** (so a slot source separates its container and vivifies); if source and destination are the **same zval**, no-op; if the source is not yet a reference, wrap it (`ZVAL_MAKE_REF`, refcount 1) in place; `ADDREF` the reference; `garbage := old destination`; store the reference in the destination; then destroy `garbage`. Acquisition precedes release, so a source inside the old destination survives.
- **`UNSET_CV`**: `DELREF` whatever the CV holds (a reference: the wrapper; at zero its value is destroyed), then undef. **`UNSET_DIM`**: fetch container for write (separate), delete the bucket, destroy its zval.
- **`FE_RESET_R`**: `ADDREF` the array into an iterator temporary; `FE_FREE` `DELREF`s it. **`FE_RESET_RW`**: the array variable is made a reference (so the iterator shares it rather than copying), the array separated; each element is fetched for write and made a reference, assigned to `$v` by `ASSIGN_REF` semantics; after the loop `$v` keeps the last reference.
- **`SEND_REF`**: fetch the argument for write, make it a reference, `ADDREF`; the callee CV holds the reference. **Function exit**: every CV is `DELREF`ed (destroy at zero), on normal return and on unwinding.
- **Objects**: handles; property writes do not separate; the object is refcounted and destroyed at zero (`__destruct`, then property zvals destroyed). Cycles are reclaimed later by the cycle collector, with destructors at collection or shutdown.
- `global $x` and `static $x`: the CV is bound by `ASSIGN_REF` semantics to the symbol-table or static-table zval, which is made a reference.

### 1.2 The substrate mapping

Every Zend refcounted kind whose count is **observable** becomes a heap cell with an explicit `:rc`; kinds whose count is unobservable stay plain values.

| Zend kind | Substrate | Why |
|---|---|---|
| string | plain value | immutable semantics; separation never observable |
| array containing no reference, no array-instance handle and no object handle, transitively | **plain** persistent ordered map, stored directly in its holder | `zend_array_dup` of such a table is observable only through unwrapping, and there is nothing to unwrap or release inside it; COW is free |
| any other array | **instance** cell `{:php/type :arr :rc n :value <ordered map> :deps #{keys}}`, held through a handle `{:php/arr h}` | its duplication and destruction perform `ADDREF`/`DELREF`/unwrap on elements, which are observable |
| reference | cell `{:php/type :ref :rc n :value x}`, held through a marker `{:php/ref c}` | exact `zend_reference` |
| object | cell `{:php/type :obj :rc n :class :props}` (already a cell; gains `:rc`) | destruction releases properties, which is observable (round 4's D-object is closed) |

`:rc` counts zval slots holding the handle or marker: variables, property slots, instance-array slots, iterator temporaries, argument slots. A **plain** array is upgraded to an instance (`rc 1`, its parent slot takes the handle, parent's `:deps` gains the key, recursively up the write path) the moment a marker, an instance handle or an object handle is stored into it; this is the only conversion, and it happens inside the write-address fetch. The invariant is maintained by construction: every store of a counted thing goes through row S below.

### 1.3 Primitive procedures (one per Zend routine)

```text
ADDREF(x):     x is handle/marker/object -> rc += 1; plain/scalar -> nothing
DELREF(x):     x is handle/marker/object -> rc -= 1; if 0 -> DESTROY(x)
DESTROY(arr h):   for k in deps(h): DELREF(slot k);  h is garbage
DESTROY(ref c):   DELREF(value(c)); c is garbage
DESTROY(obj o):   run __destruct if defined (o is pinned during it); then DELREF every property; o is garbage
DUP(h):        h' := {rc 1, value(h), deps(h)};
               for k in deps(h), x := slot k:
                 marker c with rc(c)=1 and value(c) not the handle h -> slot k of h' := value(c); ADDREF(value(c));
                                                                         deps(h') keeps k only if that value is counted
                 otherwise -> ADDREF(x)
SEPARATE(loc):  loc holds handle h with rc(h) > 1 -> DELREF(h) (no destroy possible); loc := DUP(h)
                loc holds a plain array -> nothing (COW is implicit)
FETCH_W(path):  start at the variable or property; at each step:
                  marker -> continue inside value(c)
                  handle -> SEPARATE here; continue inside the instance's value
                  plain array -> continue (persistent update on the way back)
                  missing key -> vivify null (or [] when the next step is a dim)
                  scalar/null in dim position -> PHP's auto-vivification or Error, per the oracle
                returns the terminal slot (its container chain is now exclusively owned at every instance level)
STORE(slot, v): if v is counted and the container is plain -> upgrade the container chain to instances (1.2)
                slot := v; deps += k when v is counted
MAKE_REF(slot): slot holds marker c -> c ; else c := {rc 1, value <slot content moved>}; slot := marker c; deps += k
ASSIGN(loc, v):     target := loc; if target holds marker c -> target := c (the ref's value slot)
                    garbage := content(target); ADDREF(v); STORE(target, v); DELREF(garbage)
ASSIGN_REF(dst, src):  s := FETCH_W(src) ; d := FETCH_W(dst)   (both for write; dst's container separates too)
                       if s is d -> return
                       c := MAKE_REF(s); ADDREF(c); garbage := content(d); d := marker c; DELREF(garbage)
RELEASE_CVS(frame):    for every local and parameter slot: DELREF(content); undef
```

Order inside `ASSIGN` and `ASSIGN_REF` is the engine's: acquire the new content, store, then destroy the old. `MAKE_REF` on a slot that is already a marker reuses it (no nesting). `DUP` never changes the source.

### 1.4 The transition table, by opcode

Rows are the PHP constructs with the Zend handler they map to; columns are the content kind of the slot the operation acts on: `S` scalar/string/null, `P` plain array, `H` instance handle, `O` object handle, `R` marker. "rc" is the pointee's count.

| # | Construct (handler) | S | P | H | O | R |
|---|---|---|---|---|---|---|
| 1 | `$y = $x` (`ASSIGN` with CV source; also `SEND_VAR`, `RETURN`, literal element, `FE_RESET_R` temp) | `ASSIGN(y, copy)` | `ASSIGN(y, share)` | `ASSIGN(y, h)`: rc(h)+1, old y `DELREF` | same with o | source dereferenced first: the row of `value(c)` |
| 2 | `$x = v` where `$x` holds (`ASSIGN`) | replace | replace (old plain: nothing to release) | `DELREF(h)` after store (→ `DESTROY` at 0: slots released) | `DELREF(o)` | **through** the ref: `value(c)` replaced, old value `DELREF`ed, `c` retained |
| 3 | `$x[k…] = v` (`FETCH_DIM_W` + `ASSIGN`) | per oracle (string offset write / Error) | persistent update; upgrade chain if `v` counted | `SEPARATE` at every instance level with rc>1, then `ASSIGN` at the terminal slot | — | each marker on the path dereferenced; terminal marker: through |
| 4 | `$y =& $x` (`ASSIGN_REF`, variable source) | wrap `$x` in a new ref (rc 1), then +1; `$y`'s old content released after | same | same (the ref holds the handle) | same | reuse `c`; rc(c)+1; `$y`'s old content released after |
| 5 | `$y =& $x[k…]` / `[&$x[k]]` / `f($x[k])` by ref / `foreach ($x as &$v)` element (`FETCH_DIM_W` + `MAKE_REF` + `ASSIGN_REF`) | vivify then as row 4 | **container chain separated and upgraded**, slot wrapped, then as row 4 | **container separated** (rc>1), slot wrapped | — | marker reused |
| 6 | `$x =& $x` or same slot both sides | no-op (identity check before any release) | | | | |
| 7 | `$y =& $x` where the source lies inside `$y`'s old content (`$a =& $a[0]`) | acquisition first: source separated and wrapped, rc+1; then `$a`'s old array `DELREF`ed → destroyed if 0 → its slot marker `DELREF`ed (rc back to 1, held by `$a`) | | | | |
| 8 | `unset($x)` (`UNSET_CV`) | undef | undef | `DELREF(h)` | `DELREF(o)` | `DELREF(c)` (destroy value at 0) |
| 9 | `unset($x[k])` (`FETCH_DIM_UNSET` + `zend_hash_del`) | — | remove | `SEPARATE` chain, remove bucket, `DELREF` its content, deps−k | — | marker on the path dereferenced; terminal bucket's marker `DELREF`ed |
| 10 | read `$x[k…]` (`FETCH_DIM_R`) | value | value | no count change | | dereference, no count change |
| 11 | `foreach ($x as $v)` (`FE_RESET_R`) | iterate a temp holding the array: plain share or rc+1; `FE_FREE` `DELREF`s | | | | |
| 12 | `foreach ($x as &$v)` (`FE_RESET_RW`) | `$x` made a reference (`MAKE_REF` on the variable, rc 2 with the iterator); array separated; per element row 5 into `$v` (old `$v` marker released after acquisition); `FE_FREE` `DELREF`s the array ref; `$v` keeps the last marker | | | | |
| 13 | by-value parameter (`SEND_VAR` + `RECV`) | row 1 into the callee CV | | | | |
| 14 | by-ref parameter (`SEND_REF` + `RECV`) | row 4 or 5 into the callee CV | | | | |
| 15 | return (`RETURN`) | row 1 into the caller's temp; then row 16 | | | | |
| 16 | function exit, normal or unwinding (`zend_free_compiled_variables`) | `RELEASE_CVS`: the lowering wraps every body that declares locals or parameters in `php/try-finally` with `php/release-cvs` | | | | |
| 17 | `global $x` / `static $x` (`BIND_GLOBAL`, `BIND_STATIC`) | row 4 with the symbol-table or static-table slot as source (vivified null) | | | | |
| 18 | `$a[0] =& $a` (`ASSIGN_REF`, self) | row 5 with row 7's ordering; `DUP` keeps a marker whose value is the table being duplicated | | | | |
| 19 | `$o->p = v`, `$o->p =& x`, `unset($o->p)` (`ASSIGN_OBJ`, `ASSIGN_OBJ_REF`, `UNSET_OBJ`) | property slot as a location: rows 2, 4, 8; **no separation** (objects are handles) | | | | |
| 20 | `$o = null`, object leaving all slots | `DELREF(o)` → `DESTROY(o)`: `__destruct`, then properties released | | | | |
| 21 | builtins taking arrays (`array_*`, `count`, …) | arguments by row 13; results built by `STORE` (so a returned array holding counted things is an instance); the engine-documented reference-preserving functions are followed by the oracle | | | | |
| 22 | script end | `RELEASE_CVS` of the global scope in symbol-table order, then remaining objects' destructors in creation order (PHP's shutdown sequence, pinned by oracle) | | | | |

### 1.5 The reviewer's three programs, traced

| Program | Zend | Table |
|---|---|---|
| `$a=[1,2]; $r=&$a[0]; $b=$a; $s=&$b[1]; $s=9; echo $a[1];` | 2 | row 5: `$a` upgraded (`rc 1`), `c1{2}`; row 1: `rc 2`; row 5 on `$b[1]`: **`SEPARATE`** → `DUP` (`c1` rc>1 → `ADDREF`, 3), `$b` holds `h'`, slot 1 wrapped `c2{2}`; row 2 through `c2`; `$a[1]` reads `h`'s slot: **2** |
| `$a=[1]; $r=&$a[0]; $z=&$a; unset($z); $a=&$a;` | no-op | row 4 made `$a` a marker to `c2{2}`; row 8 → `c2{1}`; row 6: same slot → **no-op**; nothing destroyed |
| `$a=[1]; $r=&$a[0]; $x=&$a; $a=0; $b=[&$r]; unset($r); $c=$b; $c[0]=2; echo $b[0];` | 1 | `c1{2}` (slot, `$r`), `$a` → `c2{2}` holding `h`; row 2 through `c2`: `garbage h`, store 0, `DELREF(h)` → `DESTROY` → slot marker `DELREF` → `c1{1}`; `$b=[&$r]`: `c1{2}`; row 8: `c1{1}`; row 1: `rc 2`; row 3: `SEPARATE` → `DUP` unwraps `c1` (rc 1) into the copy, write 2; `$b[0]` reads `h_b`'s slot through `c1`: **1** |

Every earlier fixture (rounds 1 to 4) also passes, since the rows are the handlers those fixtures exercised.

### 1.6 Fixture derivation (P3), `ref-lifecycle-v1`

Three sources, none intuition:

1. **php-src tests.** From the pinned php-src tag (the oracle's own version), every `.phpt` under `Zend/tests/` and `tests/lang/` whose `--FILE--` matches the regex `&\$|&\s*\$|foreach\s*\([^)]*&|unset\(|=&|\bglobal\b|\bstatic\s+\$` and whose source uses only the supported surface (no `preg_`, no extensions, no `goto`), with its `--EXPECT--`/`--EXPECTF--` as the oracle (`%d`-style patterns normalized; object ids `#n` compared by order). The selection script and the resulting list are checked in; the count is reported, not guessed. This is the "zend tests" source the instruction asks for and covers `tests/lang/foreachLoop.*`, `passByReference_*`, `engine_assignExecutionOrder_*`, the `Zend/tests/bug*` reference regressions, and `Zend/tests/list/`.
2. **One program per table cell** (rows 1 to 22 against each applicable content kind), each written as the minimal program that reaches the named handler, oracle from the PHP CLI.
3. **The permutation corpus**, with the alphabet extended for the gaps the reviewer named: `{C: $b=$a, U: unset($r), W: $b[0]=2, X: unset($b[0]), A: $a[0]=3, D: unset($b), N: $c=$b; $c[1]=9, B: $s=&$b[1]; $s=9, Z: $z=&$a; unset($z), T: $a=0, L: $q=[&$r]; unset($r), G: f($a) (a function that copies and writes), E: $a=&$a}` from `$a=[1,2]; $r=&$a[0];`, every sequence of length 2 to 5, printing `var_dump($a,$b,$c,$q,$r,$s)`; oracle from the CLI; slow tier in full, a fixed sample in the quick tier.

Departures that remain, all pinned as fixtures: **D-cycles**: objects in reference cycles are never destructed before script end (the engine's collector runs them at collection time, which its documentation does not promise); at script end row 22 runs them. **D-builtins**: narrowed to the oracle; any builtin whose result disagrees is a fixture failure, not a departure, so this row is expected to empty out. D-scope and D-object are closed by rows 16 and 20.

Cost, stated: a cell per array that ever holds a counted thing (arrays of objects included), one `try-finally` frame per call of a function with locals, `O(|deps|)` per separation. Owner decision **B8.8 (final)**: this exact model, no profile switch.

---

## 2. Claims: the evidence anchor and late acquisition (P1-4, P1-5)

The stream contract mints cursors only from `:dao.stream/oldest`, `:dao.stream/newest` or a transport-specific anchor, forbids fabricated positions, and keeps cursors opaque. The replay rule therefore uses a **stream-minted cursor saved before the first relevant append**, never a position:

**The evidence record** (a ledger datom in the composition that owns the `claims` medium; not content-addressed, since a cursor is not content and is valid only for its attachment):

```clojure
[<root A> :yang.claims/evidence
 {:yang.claims/attachment <the claims medium's attachment identity>
  :yang.claims/cursor     <cursor minted by (stream/cursor claims :dao.stream/newest)
                           immediately before the first claim about A was appended>
  :yang.claims/root       A}]
```

**The appender protocol** (`yang.core.claims/append-claims!`, used by every claims producer: the TS frontend under `:claims`, a `.d.ts` compile, a future inference observer): before appending claims about root `A`, look up `A`'s evidence record in the composition ledger; if absent, mint the `newest` cursor, append the evidence datom, then append the claims; if present, append the claims. Minting precedes the first claim, so the cursor's position is at or before it; a crash between minting and the first claim leaves a harmless anchor with no claims behind it. This answers P1-5: an **initially unannotated module** has no record at publication and acquires one when its first `.d.ts` arrives; nothing about publication order matters. Round 4's "recorded only at first publication" is withdrawn.

**Recheck**: the requester appends the byte-identical tree packet to `typecheck-in` with the envelope `{:yang.claims/evidence <the record> :yang.claims/t <current newest cursor>}`. The observer reads from the saved cursor to `t` on a fresh handle to the same attachment, indexes rows for `A`, evaluates, cites, frees; its own cursor is untouched (§7 of the typecheck design holds). A `gap` on the saved cursor (eviction) yields `:refused :evidence-unavailable`; the stream's own gap detection is preserved because the cursor is the stream's. **Cross-composition replay is not claimed**: another composition re-mints evidence by recompiling declaration units (claims are regenerable from source; derive, don't persist).

**T10 fixtures**: (a) claim-free JS module published → first verdict without evidence record; late `.d.ts` → record minted, claims appended, recheck cites that declaration; second conflicting `.d.ts` → recheck cites **both**, refused; (b) round 4's four-step sequence on an annotated module; (c) eviction: a ring medium evicting below the saved cursor → `:evidence-unavailable`; (d) two appenders racing on a root: both find no record, both mint; the rule is that the **earlier** evidence datom wins (ledger order), and the later appender's claims still lie after the earlier cursor, so replay is complete either way (fixture).

---

## 3. Dynamic import settlement ordering (P2-6)

Node v26's observed order (`t1,t2,t3,t4,import` for an already-loaded module against a four-step promise chain) comes from its host loading hook and internal promise plumbing, which the specification leaves to the host. Ruling: **narrow the promise**. The frontend claims, and T7 tests: the import promise settles in a job that runs after the current synchronous evaluation completes; settlement reflects the module's final status (same error object for failures); `.then` handlers on it run after all synchronous code of the current job. The frontend does **not** claim the tick count relative to user promise chains; the support profile records "dynamic-import settlement tick count differs from Node" as a departure with the reviewer's program as its fixture (asserting our order and documenting Node's). Reproducing Node's four ticks with a fixed number of chained jobs is rejected as brittle against both Node versions and the specification. Owner decision **A12.15**: narrowed ordering (recommended) versus a pinned four-job settlement chain.

---

## 4. Slices and owner decisions (changes)

| Slice | Change |
|---|---|
| P3 | the §1 model replaces round 4's; `RELEASE_CVS` wrap unconditional; object `:rc` and `__destruct` at zero (moves `__destruct` from "never called" to supported for acyclic graphs); the three fixture sources; the extended permutation alphabet |
| P4 | `__destruct` acceptance (acyclic) and the D-cycles fixture |
| L3 / T10 | `yang.core.claims/append-claims!` with the evidence record; recheck requester fixture set (a) to (d) |
| T7 | ordering claim narrowed; departure fixture |

Owner decisions: **B8.8 (final)** exact engine model, no profile. **A12.14 (revised)** evidence by saved stream-minted cursor in a composition ledger, cross-composition replay by regeneration. **A12.15 (new)** narrowed dynamic-import ordering.

---

## 5. What round 5's reviewer should probe

1. **`DUP`'s `ADDREF` of an unwrapped value**: the engine `ADDREF`s the unwrapped value into the copy while the source's reference still holds it; the table does so too (nested table rc rises), and the reviewer may trace a nested unwrap followed by a write through the source to confirm the nested instance separates correctly.
2. **Row 12's `MAKE_REF` on the iterated variable**: after `foreach ($a as &$v)`, `$a` itself is a reference in the engine (visible through `debug_zval_refcount`-free means only via later `$b =& $a` reuse and `unset($a)` behaviour); the fixture from `tests/lang/foreachLoop*` should settle whether a subsequent `$c = $a` copies the array through the ref as the table says.
3. **Vivification cases** in `FETCH_W` (writing into `null`, `false`, a string offset, an int): the table defers to the oracle; a reviewer may want the Error/Warning matrix enumerated.
4. **`RELEASE_CVS` on every escape**: the `try-finally` wrap must run on `return`, `break` out of the function (impossible), exceptions, and generator finalization (a suspended generator's CVs are released when the generator is destroyed: row 20 for the `Generator` object, which needs the generator cell to carry `:rc`; this is implied by "objects are counted" and should be stated as a row).
5. **The evidence record's race rule** (earlier datom wins) and whether a composition ledger is the right home versus the `claims` medium itself (the record cannot live on `claims`: it is not a claim and would be evicted with them).
6. **The narrowed ordering**: whether any corpus program the differential harness already relies on observes dynamic-import tick counts; the harness should flag such programs as departure fixtures automatically.
