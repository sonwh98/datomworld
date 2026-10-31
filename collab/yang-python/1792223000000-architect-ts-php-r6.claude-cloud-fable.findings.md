Completed-GMT: 2026-10-17 08:13:22 GMT
Completed-Local: 2026-10-17 15:13:22 +0700

# Revised designs, round 6: the next Zend stratum, and the convergence mechanism

Role: Lead System Architect. Findings only; nothing edited. This round answers the round-5 codex review (`collab/1792220000000-reviewer-ts-php-r5.gpt-6.1-sol.findings.md`): five P1s at the next handler stratum, all accepted, plus the terminology correction. Part A extends the opcode-derived table with the five handler families (iteration, unset fetch mode, temporary ownership, typed-reference sources, object finalization and shutdown) and one more the same reading exposed (`clone`). Part B adds the convergence mechanism the brief asks for: a **Known engine-fidelity corners** subsection enumerating every Zend behaviour still outside the table, each with its implied deviation and closing slice, and the matching support-profile statement that only the specified fragment is claimed. Everything the reviewer confirmed (the round-4 counterexamples, saved cursors, first-claim acquisition, narrowed import ordering) is unchanged.

Terminology correction accepted: the evidence record's key is `:yang.claims/stream`, the **logical-stream identity**, not an attachment identity, matching the cursor contract.

Where my reading of a handler and the reviewer's could differ (noted at row 24), the rule of this design is unchanged: the row states the handler's behaviour as read, and the **oracle fixture is the arbiter**; a row whose fixture disagrees with the pinned PHP CLI is a row defect, not a departure.

---

## Part A. The table, extended

### A.1 Iteration by reference (P1-1): `FE_RESET_RW`, `FE_FETCH_RW`, `FE_FREE`, hash iterators

Zend separates **once**, at `FE_RESET_RW`; `FE_FETCH_RW` then reads buckets through a registered hash iterator on the table the loop's reference holds, and does not separate again. If the body copies the array (`$b = $a`, table rc 2), later fetches still make elements references **in the shared table**, so the copy sees them. Hash iterators are registered on the table and updated when buckets are deleted or the table is rebuilt (`zend_hash_iterators_update`), so appended elements are visited and a deleted current element is skipped.

Substrate: an instance array gains `:iterators #{iter-cell}`; an iterator is a cell `{:php/type :iter :arr h :pos i}` where `:pos` indexes the instance's order vector.

| # | Construct (handler) | Rule |
|---|---|---|
| 24 | `foreach ($x as &$v)` start (`FE_RESET_RW`) | `$x`'s location: `MAKE_REF` (rc 2: variable and the iterator temp hold it); the array inside the ref: upgrade to instance if plain, then `SEPARATE` if rc>1 (exactly once); register an iterator cell on that instance at pos 0; the iterator temp is an owned temporary of the activation (A.3) |
| 25 | element fetch (`FE_FETCH_RW`) | **no separation**: read the instance currently in the ref's value (it may be shared with copies made since); bucket at `:pos`: if the slot is not a marker, `MAKE_REF` in place (visible to every holder of that table); then `ASSIGN_REF` into `$v` (acquire before releasing `$v`'s old marker); advance `:pos` to the next existing key |
| 26 | mutation of an instance with registered iterators (`zend_hash_iterators_update`) | `unset` of a key at index `< pos` → every iterator's `pos` −1; of the key at `pos` → `pos` stays (now points at the next element); append → visited; a table **rebuild** (`sort`, `array_splice`, `$x = [...]` through the ref) → iterator repositioned by Zend's rule for that routine: sort resets to the same index position (keys renumbered), assignment of a new table resets the iterator to position 0 of the new table (the oracle pins each) |
| 27 | loop end or `break` (`FE_FREE`) | unregister the iterator; `DELREF` the array ref (the variable keeps its marker; `$x` stays a reference, so a later `$y =& $x` reuses it); `$v` keeps the last marker |
| 28 | `foreach ($x as $v)` (`FE_RESET_R`) | iterate an **owned temporary** holding the array (plain share, or instance `ADDREF`); body writes to `$x` separate `$x`'s copy, so the loop sees the original; `FE_FREE` releases the temporary; no iterator registration (the snapshot is immutable) |
| 29 | `foreach` over an object by value / by reference (`FE_RESET_R/RW` on `IS_OBJECT`) | property iteration in declaration order filtered by visibility from the calling scope; by reference makes property slots references (row 19 locations); `Iterator`/`IteratorAggregate` dispatch as in B1 |

Fixtures: php-src `tests/lang/foreachLoop.*.phpt` (the by-reference and copy-during-iteration series), `Zend/tests/foreach_*.phpt`, `Zend/tests/bug*foreach*.phpt`, filtered to the supported surface; plus one program per row, including the reviewer's (`$b[1]` is 12 under this reading) and: append during by-ref loop (visited), unset of current (skipped), unset of a previous element (no element skipped), `$x = [9,9]` through the loop variable mid-loop, `sort($x)` mid-loop, `break` then `$y =& $x`.

### A.2 Unset fetch mode (P1-2): `FETCH_DIM_UNSET`, `FETCH_OBJ_UNSET`, `UNSET_DIM`, `UNSET_OBJ`

Zend's `BP_VAR_UNSET` fetch separates array containers it traverses but **never vivifies**: a missing key yields the uninitialized zval, and an `unset` applied to `null` is a no-op. Non-array containers follow fixed rules.

```text
FETCH_UNSET(path): at each step:
  marker            -> continue inside value(c)
  handle            -> SEPARATE here; continue
  plain array       -> continue (no change)
  missing key       -> stop: the remaining path is a no-op (nothing created)
  null              -> stop: no-op
  string            -> Error "Cannot unset string offsets"
  int/float/bool    -> Error "Cannot unset offset in a non-array variable"
  object            -> ArrayAccess::offsetUnset, else Error "Cannot use object of type X as array"
UNSET_DIM(container, k): delete the bucket if present; DELREF its content; deps -= k; iterators updated (row 26)
UNSET_OBJ(o, p):          the property slot: DELREF content; a typed property becomes uninitialized (A.4)
```

| # | Construct | Rule |
|---|---|---|
| 30 | `unset($x[k…])` | `FETCH_UNSET` to the container, then `UNSET_DIM`; `unset($a['missing']['child'])` on `[]` leaves `[]` unchanged |
| 31 | `unset($o->p[k])`, `unset($o->p)` | property slot as container through `FETCH_OBJ_UNSET` (no separation, objects are handles); `__unset` when the property is inaccessible or absent |
| 9 (revised) | round 5's row 9 | now defined as row 30; it no longer shares `FETCH_W` |

Fixtures: php-src `Zend/tests/unset_*.phpt`, `Zend/tests/bug*unset*.phpt`, `Zend/tests/isset_*` where paired; per-row programs for every non-array container kind and nested missing paths.

### A.3 Temporary ownership (P1-3): `SEND_VAR`/`SEND_VAL`/`SEND_REF`, `RECV`, `RETURN`, `FREE`, live-temporary cleanup

Zend distinguishes **borrowed** operands (a CV or a fetched slot: copying them `ADDREF`s) from **owned temporaries** (`TMP_VAR`: the result of a call, `new`, `clone`, an array literal, a cast or operator producing a counted value; transferred by move, released by `FREE` when discarded, and released by `cleanup_live_vars` and `zend_vm_stack_free_args` when an exception abandons a frame or a half-built argument list).

Substrate: every activation (function body, and the global script) owns a **temp list** cell `php.rt/tmps` (a vector of counted values). The lowering classifies each expression syntactically:

- **borrowed**: variable read, property read, dimension read, constant, literal scalar, static property read;
- **owned**: call result, `new`, `clone`, array literal containing any non-literal element, `(array)`/`(object)` cast, `yield` result, closure creation, the result of an assignment expression used as a value (Zend copies it: borrowed-then-`ADDREF`), string interpolation (plain, uncounted), `match`/ternary results (owned iff the chosen arm is owned: the lowering registers at the arm).

Rules (one per handler):

| # | Construct (handler) | Rule |
|---|---|---|
| 32 | owned temporary produced | `php/tmp! v`: if `v` is counted, push it on the activation's temp list and return it; uncounted values are not registered (the count is already held by the producing operation: a call result arrives with the callee's transferred count) |
| 33 | `f(expr)` argument from a borrowed operand (`SEND_VAR`) | `ADDREF`, store into the callee's parameter slot |
| 34 | argument from an owned temporary (`SEND_VAL`) | **move**: unregister from the caller's temp list, store into the parameter slot without `ADDREF` |
| 35 | by-reference argument (`SEND_REF`) | row 5 or 4 into the parameter slot; a non-variable argument is `Notice: Only variables should be passed by reference` and a by-value send (the oracle pins which builtins are prefer-ref) |
| 36 | `return expr` (`RETURN`) | borrowed → `ADDREF`; owned → unregister (move); the value becomes the **caller's** owned temporary (`php/tmp!` at the call site); then row 16 releases the callee's CVs and **every temporary still on the callee's list** |
| 37 | expression statement, discarded call result (`FREE`) | at statement end `php/release-tmps mark`: release every temporary registered since the statement's mark, in registration order; a discarded `new Foo()` or a discarded call returning a fresh object is therefore destroyed, and its destructor runs, **before the next statement** |
| 38 | temporary consumed by `STORE`/`ASSIGN` | owned → move (unregister, no `ADDREF`); borrowed → `ADDREF` |
| 39 | exception or escape leaving an activation (`cleanup_live_vars`, `zend_vm_stack_free_args`) | the activation's `php/try-finally` runs `RELEASE_CVS` then releases the whole temp list; arguments already sent to a callee frame that never started are on the caller's temp list (sent-but-not-moved values stay registered until the call begins, at which point rows 33 and 34 apply), so they are released by the same finally |
| 40 | `php/release-tmps` within a statement that spans a call | the mark is per statement; a temporary produced inside a nested expression and consumed by row 34 or 38 is gone before the mark is released; one that is neither consumed nor discarded (an owned operand of a comparison, a `??` left side) is released at the statement end |

Cost: one vector push per counted owned temporary and one release per statement that produced any; scalars, strings and plain arrays never touch the list.

Fixtures: php-src `Zend/tests/temporary_cleaning_0*.phpt` (the whole series, filtered to the supported surface), `tests/classes/destructor_*.phpt` where the destructor's timing is the assertion, and per row: discarded `new` with a destructor echoing; a call that throws after two arguments were evaluated, both objects with destructors (destroyed during unwinding, in order); `return $cv` versus `return new X`; a by-value `foreach` whose body reassigns the iterated variable.

### A.4 Typed property references (P1-4): reference type sources

A `zend_reference` bound to a typed property carries **type sources** (`zend_property_info` entries). Every assignment through the reference is verified against all sources (`zend_verify_ref_assignable_zval`), coercing in non-strict mode so that the result satisfies every source or raising `TypeError` ("Cannot assign string to reference held by property C::$x of type int"); sources are added when the reference is acquired on a typed property and removed when the property is unset, reassigned by `=&` to another reference, or the object is freed.

Substrate: the reference cell gains `:sources #{[o prop-name]}` (object handle and property name; the declared type is read from the class descriptor).

| # | Construct | Rule |
|---|---|---|
| 41 | `$r =& $o->p` where `p` is typed (`ASSIGN_REF` on `FETCH_OBJ_W`) | `MAKE_REF` on the property slot; `sources += [o p]`; if the property is `readonly`: `Error: Cannot modify readonly property C::$p` unless acquired inside the declaring class scope on an uninitialized property (the oracle pins the 8.3 matrix); an uninitialized typed property vivifies to a reference holding the uninitialized sentinel, and a read through it before assignment is the "must not be accessed before initialization" `Error` |
| 42 | `ASSIGN` through a reference with non-empty `:sources` | before mutation: for each source, verify `v` against the declared type under the **assigning unit's** `strict_types`; coercion (int→float, numeric-string→int/float, Stringable→string, int→string in coercive mode) is applied once and the coerced value must satisfy every source, else `TypeError` naming the first failing source; `null` requires a nullable type; then row 2 proceeds with the coerced value |
| 43 | `$r++`, `$r .= …`, `$r += …` through a sourced reference (`PRE_INC`/`ASSIGN_OP` on `IS_REFERENCE`) | the result is verified as in row 42; an `int` property incremented past `PHP_INT_MAX` is `TypeError: Cannot increment property C::$p of type int past its maximal value` |
| 44 | source removal | `unset($o->p)` → `sources −= [o p]` and the property becomes uninitialized (magic `__get` re-enabled for it); `$o->p =& other` → the old reference loses the source, the new gains it; object free (A.5 free step) → every property's reference loses `[o p]` |
| 45 | two sources of different types on one reference (`$r =& $a->i; $b->s =& $r` with `int` and `string`) | the acquisition itself is checked: the current value must satisfy the new source, else `TypeError` at `=&`; thereafter row 42 requires both; a coercion that would satisfy one and not the other fails |
| 46 | typed **static** properties | the same sources with `[class prop]` as the source key |
| 47 | `list()`/`[]` destructuring by reference into typed properties (`[&$o->p] = $arr`) | row 41 per element, then row 42 at the assignment |

Fixtures: php-src `Zend/tests/type_declarations/typed_properties_*.phpt` restricted to those mentioning `&` or `reference` in `--FILE--` or `--TEST--`, and `typed_properties_0*` covering readonly and uninitialized states; plus the reviewer's program and one per row.

### A.5 Object finalization and shutdown (P1-5): `zend_objects_store_del`, `zend_objects_destroy_object`, `zend_call_destructors`, `shutdown_executor`

Finalization is not freeing. Zend: on reaching zero, if the destructor has not run, set `DESTRUCTOR_CALLED`, pin the object (`ADDREF`), run `__destruct` with the exception-chaining rule, unpin; if the count is still above zero the object was **resurrected** and storage is kept; otherwise the properties are destroyed and storage freed. A second drop to zero after resurrection frees without calling the destructor again. At request shutdown: shutdown functions, then destructors (globals that are objects with count 1, in reverse symbol-table order, repeated until stable; then every remaining object in the store in creation order), then output flush, then the symbol table is destroyed in reverse order, then remaining objects are freed with the destructed flag set.

Substrate: the object cell gains `:destructed? bool` and a creation ordinal `:ord`; the prelude keeps `php.rt/finalizable`, an **uncounted** ordered registry of objects whose class defines `__destruct` and which have not yet been destructed (uncounted: holding the cell ref there adds nothing to `:rc`; the entry is removed when the destructor runs). The exception-chaining rule is Zend's: if an exception is in flight when a destructor runs, it is set aside; a destructor exception then takes its place with the set-aside one as `previous`; otherwise the set-aside exception is restored.

```text
DESTROY(obj o)   [rc(o) reached 0]:
  if not destructed?(o) and class defines __destruct:
      destructed?(o) := true; rc(o) += 1 (pin); remove o from finalizable
      run __destruct under the chaining rule; rc(o) -= 1 (unpin, exception-safe: the unpin is in a finally)
      if rc(o) > 0 -> return                         (resurrected: storage kept, properties alive)
  FREE(o): for each property slot: if a marker, drop [o p] from its sources; DELREF content; o is garbage
```

| # | Construct | Rule |
|---|---|---|
| 48 | last holder released | `DESTROY` above |
| 49 | destructor resurrects `$this` (stores it in `$GLOBALS`, a static, or another live object) | `rc > 0` after unpin → kept; properties untouched; the destructor never runs again; the later final release goes straight to `FREE` |
| 50 | destructor throws | chaining rule; the releasing operation (statement-end release, `unset`, scope exit) propagates the exception after completing its remaining releases (Zend releases the rest of the frame before the exception surfaces) |
| 51 | `exit()` inside a destructor, destructor of the pending exception object | `exit` is the ordinary exit escape and proceeds to shutdown; destructing the exception currently in flight is `Fatal error: Attempt to destruct pending exception` (an `Error`-class halt in this VM, output text pinned) |
| 22 (revised) | script end / request end | (1) run `register_shutdown_function` callbacks in registration order; (2) **destructor phase**: repeat { walk `$GLOBALS` in reverse insertion order; for each entry holding an object handle with `rc 1`, remove the entry and `DELREF` (destroying it) } until the entry count is stable; then call `__destruct` for every object remaining in `finalizable` in `:ord` order (pin/unpin as above, no freeing yet); scalars, strings and arrays in `$GLOBALS` are untouched here, so destructors can read them; (3) flush output buffers to the response stream; (4) release `$GLOBALS` in reverse insertion order, then static tables, then `FREE` every remaining object without calling destructors (flag set); an uncaught `Throwable` runs this sequence after the error output; the VM's operational halts (budget exhaustion, cancellation) skip the destructor phase, as Zend skips it on fatal errors |
| 52 | `clone $o` (`zend_objects_clone_members`) | a fresh object cell (`rc 1`, `:ord` new, `destructed? false`); each property zval copied with `zval_add_ref` semantics: a marker whose reference has `rc 1` is **unwrapped** into the clone (value copied, `ADDREF`), any other counted content `ADDREF`ed (shared references stay shared, typed sources gain `[clone p]`); then `__clone` on the clone |

Fixtures: php-src `tests/classes/destructor_and_*.phpt`, `tests/classes/destructor_visibility_*`, `Zend/tests/objects_0*.phpt` (destructor and resurrection cases, filtered), `Zend/tests/bug*destruct*.phpt`, `Zend/tests/gc_*` only where no cycle collection is required (the others are corner C1 fixtures, expected to differ); per row: resurrection then final free (destructor once), destructor exception chaining with and without an in-flight exception, shutdown order with a destructor reading a global string created earlier and an object global with rc 1 versus rc 2, `exit` in a destructor, `clone` of an object with a shared and an unshared reference property.

### A.6 Row renumbering and the earlier rows touched

Row 9 is replaced by row 30; row 12 by rows 24 to 27; rows 1, 13 and 15 are refined by rows 32 to 40 (row 1 keeps "copy" for a borrowed source and "move" for an owned temporary); row 19 gains the typed-source hooks of A.4 and `clone` as row 52; row 20 is replaced by `DESTROY` of A.5; row 22 is revised above. The primitive procedures of round 5 stand with `DESTROY(obj)` replaced and `FETCH_UNSET` added.

---

## Part B. Convergence: the specified fragment and the known engine-fidelity corners

### B.1 The support-profile statement

The `yang.php` runtime profile claims **exactly the rows of the transition table (rows 1 to 52 as revised) with their fixture sets**, verified against the pinned PHP 8.3 CLI on the three fixture sources (php-src `.phpt` selection, per-row programs, the permutation corpus). Every Zend behaviour outside those rows is listed below as a named corner with status `:restricted` (behaves differently, pinned by a departure fixture) or `:unsupported` (refused with a diagnostic), the deviation it implies, and the slice that would close it. The profile claims nothing about a corner beyond what its fixture pins. This is the project's standing pattern (C3's recorded departures, C2's finalization restriction, 8.5.6's refusal postures, the known-gaps ledger): an honest fragment with tracked gaps, not a claim of Zend fidelity. **None of the corners below is required by the Phase 3 exit criteria** (mixed text and output order; array ordering and alias/copy semantics; superglobals from explicit request input; type declarations not silently ignored, including through references per A.4; cross-request isolation), and none is required by the C4 import machinery; where a corner touched one of those, it was moved into the table in this round (typed-reference sources, destructor timing of discarded temporaries, unset without vivification).

### B.2 Known engine-fidelity corners

| Corner | Zend behaviour beyond the table | Implied deviation (pinned by fixture) | Status | Closing slice |
|---|---|---|---|---|
| C1 cycle collector | `gc_collect_cycles` reclaims reference cycles and runs their destructors at collection time | cyclic garbage lives until shutdown; destructors run in the row-22 phase instead; `gc_collect_cycles()` returns 0; `gc_enable`/`gc_disable` are no-ops | restricted | a cycle-collection slice over the heap tracer (after the reclamation work ahead of lift) |
| C2 object id reuse | `spl_object_id`/`#n` ids are reused after free | ids are fresh ordinals, never reused | restricted | none planned (engine internal) |
| C3 weak references | `WeakReference`, `WeakMap` observe collection | refused | unsupported | needs collector weak support; after C1 |
| C4 `ArrayAccess` by reference, `&__get`, indirect modification | `offsetGet` returning by reference, "Indirect modification of overloaded element/property" notices | `offsetGet`/`__get` results are values; writes to them are notices with no effect, pinned | restricted | P4-b (object dimension containers in write fetches) |
| C5 string offset writes | `$s[i] = 'x'`, padding on write past the end, negative offsets, `$s[] = ` Error, multi-byte warnings | row 3's "per oracle" for strings is specified only for reads and single-byte in-range writes | restricted | P9 strings II |
| C6 internal by-reference parameters | `sort`, `array_push`, `preg_match`'s `&$m`, prefer-ref `array_multisort`, "Only variables should be passed by reference" | each supported builtin declares its by-ref positions in the prelude table (P2); the notice is emitted; prefer-ref builtins are refused until P9 | restricted | P2 (core), P9 (prefer-ref) |
| C7 return by reference | `function &f()`, `&__get`, `&offsetGet` | refused | unsupported | a later slice after P9 |
| C8 generator destruction | destroying a suspended generator runs its pending `finally` blocks and releases its frame | a suspended `Generator` object reaching `rc 0` releases its CVs (rows 16/39) but does **not** run pending `finally` | restricted | P4-c (generator finalization on the C2 core) |
| C9 pending-exception destruct fatal | `Attempt to destruct pending exception` | an `Error`-class halt with pinned text, not a process fatal | restricted | none planned |
| C10 property hooks, asymmetric visibility | PHP 8.4 | not parsed (8.3 pinned) | unsupported | a grammar and version bump |
| C11 dynamic scope references | `extract(EXTR_REFS)`, `compact` of references, `$$name =& …`, `get_defined_vars` | the dynamic-scope profile's symbol table holds markers like any location; `EXTR_REFS` and `$$x =&` are refused until P9 | unsupported until P9 | P9 |
| C12 nested destructuring by reference | `[[&$a]] = …`, keyed nested forms | one level supported (row 47); deeper refused | restricted | P9 |
| C13 `$GLOBALS` write semantics (8.1) | `$GLOBALS` is a read-only copy; element writes allowed; `$GLOBALS = …` compile error | element writes supported; whole-array operations on `$GLOBALS` refused | restricted | P9 |
| C14 `ArrayObject`/`ArrayIterator` | SPL array wrappers sharing storage with an array by reference | deferred | unsupported | SPL slice |
| C15 iterator repositioning on table rebuild | the exact `zend_hash_iterators_update` outcome for every rebuilding builtin | row 26 pins `unset`, append, assignment and `sort`; other rebuilding builtins (`array_splice`, `shuffle`, `array_unshift`) during by-ref iteration are pinned individually in P2 or listed here as restricted | restricted | P2 |
| C16 `debug_zval_refcount`, `debug_zval_dump` counts | engine refcount numbers | refused (internal) | unsupported | none |
| C17 destructor phase on engine fatals | fatal errors skip destructors; `E_ERROR` from the engine | this VM has no engine fatals; operational halts skip the phase (row 22) | restricted | none |
| C18 `serialize`/`unserialize` of references, `__sleep`/`__wakeup`/`__serialize` | reference preservation `R:`/`r:` in serialized form | deferred | unsupported | serialization slice |
| C19 `clone` of objects holding `Generator`/`Closure` state | engine-specific | pinned by oracle where supported; `Closure` clone supported, `Generator` clone is an `Error` as in the engine | restricted | P4-c |
| C20 references to static properties across inheritance | redeclared static props and reference identity across child classes | row 46 covers the declaring class; inheritance edge cases pinned in P4 | restricted | P4 |
| C21 memory and time limits | `memory_limit`, `max_execution_time`, `memory_get_usage` | the deterministic safepoint budget (B6) and no memory accounting; `memory_get_usage` returns a pinned constant | restricted | none (operational, §11) |
| C22 `__destruct` output ordering with output buffers at shutdown | destructor `echo` inside an unflushed `ob_start` at shutdown | row 22 flushes after destructors; nested unclosed buffers are flushed in stack order; pinned | specified, listed for visibility | — |
| C23 string interning and `===` | unobservable | none | — | — |

Each corner's fixture is part of `ref-lifecycle-v1` (or `objects-v1`, `iteration-v1` for the new families) with its **expected departure recorded as data**, so the differential harness distinguishes a known corner from a row defect automatically; the corner table is the known-gaps ledger's PHP section, row for row, owner seat `yang.php`.

### B.3 How the loop converges

The reviewer may now grant READY on the fragment: the table rows have per-row oracle fixtures, the three fixture sources are mechanical, and any Zend behaviour not in a row must be either in B.2 (tracked, with its deviation pinned) or a defect of the table (a fixture failure). A new finding therefore lands in one of three bins: a row defect (fix the row), a missing row that an exit criterion or the C4 profile needs (add the row, as this round did five times), or a corner (add to B.2 with its status and slice). Only the second bin blocks; the design states which bin by the criteria of B.1.

---

## Updated slices and owner decisions

| Slice | Change |
|---|---|
| P2 | by-ref position table for every supported builtin (C6); rebuild-during-iteration pins (C15) |
| P3 | rows 24 to 47 as listed; iterator cells on instances; temp lists; typed sources; the three new fixture families (`iteration-v1`, `objects-v1`, `temps-v1`) beside `ref-lifecycle-v1`; the php-src selection predicates extended to `foreachLoop.*`, `unset_*`, `temporary_cleaning_*`, `typed_properties_*` with references, `destructor_*`, `objects_0*` |
| P4 | `DESTROY`/`FREE` states, resurrection, chaining, `clone` (row 52), shutdown sequence (row 22); typed-reference `TypeError` texts |
| P4-b, P4-c | new follow-on slices named by the corners (object dimension containers; generator finalization) |

Owner decisions: **B8.11 (new)** adopt the support-profile statement of B.1 and the corner table of B.2 as the PHP section of the known-gaps ledger (recommended); **B8.12 (new)** C1 cycle collection scheduled after the reclamation work rather than inside Phase 3 (recommended); **B8.8** stands (exact engine model for the specified fragment).

## What round 6's reviewer should probe

1. **Row 25's non-separating fetch**: the oracle fixture decides; if the pinned CLI prints 2 for the reviewer's program, the row flips and nothing else changes.
2. **Row 26's repositioning rules** for `sort` and whole-array assignment through the loop reference, which I pinned by handler reading and the oracle must confirm.
3. **The borrowed/owned classification** (A.3): whether any expression form yields an owned value the lowering treats as borrowed (candidates: `yield` results, `match` arms, string-to-array casts of literals) and whether an assignment expression's value is correctly treated as borrowed-then-`ADDREF`.
4. **Row 42's coercion order with multiple sources** and the exact `TypeError` texts for sources of two classes.
5. **Shutdown step 2's termination**: the repeat-until-stable walk and whether destructors that create new globals are handled as Zend does (they are re-walked; the oracle pins).
6. **B.2's completeness**: whether any listed corner is in fact needed by the Phase 3 exit criteria or by the import machinery; the reviewer's candidate list should be checked against B.1's criterion, and anything that fails it moves into the table in the next round, which is the intended convergence path.
