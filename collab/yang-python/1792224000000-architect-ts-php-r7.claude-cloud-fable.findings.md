Completed-GMT: 2026-10-17 08:22:29 GMT
Completed-Local: 2026-10-17 15:22:29 +0700

# Revised designs, round 7: three claimed-row defects, one classification, and the blocking meta-rule

Role: Lead System Architect. Findings only; nothing edited. This round answers the round-6 codex review (`collab/1792223000000-reviewer-ts-php-r6.gpt-6.1-sol.findings.md`): three P1 defects inside claimed rows, one P2 classification gap, and the P2 meta-rule, all accepted. Each fix is derived from the Zend handler the reviewer cites and lands as a corrected or added row with its fixture; the convergence subsection gains the blocking rule. Nothing outside these rows changes.

## 1. Argument ownership is acquired at evaluation (P1-1): `INIT_*CALL`, `SEND_*`, `zend_vm_stack_free_args`

Zend pushes the call frame at `INIT_FCALL`/`INIT_METHOD_CALL`/`NEW` **before** any argument is evaluated, resolving the callee then; each `SEND_VAR` copies its operand into the frame's argument slot with `ADDREF` at the moment that argument is evaluated, before the next argument's expression runs; `SEND_VAL` moves an owned temporary into the slot the same way; an exception during a later argument frees the pushed arguments and the frame (`zend_vm_stack_free_args`, `zend_vm_stack_free_call_frame`). Round 6's row 39 deferred acquisition to call entry, which is wrong, as the reviewer's program shows.

Substrate: a **pending call** is an owned record on the activation's temp list, created when the call expression begins and consumed when the callee is entered.

| # | Construct (handler) | Rule |
|---|---|---|
| 53 | call expression begins (`INIT_FCALL`, `INIT_FCALL_BY_NAME`, `INIT_METHOD_CALL`, `INIT_STATIC_METHOD_CALL`, `NEW`) | resolve the callee **first**: an undefined function, undefined method (after `__call`/`__callStatic` lookup), non-callable value, abstract class or missing constructor raises its `Error` before any argument expression runs; for a method call the receiver expression is evaluated now and `ADDREF`ed into the pending call's `$this` slot; for `new`, the object is created now (constructor not yet run) and held in the pending call; the pending call `{:callee :this :args []}` is pushed on the activation's temp list |
| 33 (revised) | argument from a borrowed operand (`SEND_VAR`), evaluated | **immediately** `ADDREF` and append to the pending call's `:args`, before the next argument expression is evaluated |
| 34 (revised) | argument from an owned temporary (`SEND_VAL`), evaluated | **immediately** move (unregister from the temp list, append to `:args`) |
| 35 (revised) | by-reference argument (`SEND_REF`), evaluated | **immediately** rows 4/5 (write fetch, `MAKE_REF`, `ADDREF`) into `:args`; a non-variable operand to a by-ref parameter is the notice plus a by-value send, decided now |
| 56 | spread argument `f(...$xs)` (`SEND_UNPACK`) | the iterable is evaluated and each element appended as a borrowed send (`ADDREF` each), by-ref parameters bind to the array's elements through row 5 when the operand is a variable array |
| 57 | named arguments (`SEND_*_EX` with names) | acquired in **source order** at evaluation, placed by name at entry; an unknown name or duplicate is an `Error` at entry, after all arguments were evaluated and acquired (Zend checks at `SEND` time for already-known callees: the oracle pins the position for the two cases, internal versus user function) |
| 58 | call entry (`DO_FCALL`) | the pending call is unregistered from the temp list; `:args` are **moved** into the callee's parameter slots (defaults filled by evaluating default expressions in the callee, variadics gathered into a fresh array by move); `:this` moved into the callee's `$this` slot; the callee's own temp list and CV set are then its activation's |
| 39 (revised) | exception or escape while a pending call exists | the activation's finally releases the temp list; a pending call releases `:this` and every `:args` entry (`DELREF`), in order, then is dropped; nested pending calls (an argument that is itself a call) are released innermost first because they were pushed later |
| 36 (revised) | return into the caller | the return value becomes the caller's owned temporary as before; the callee's parameter slots are released by row 16 (they were moved into the callee, so the count balances) |

Trace of the reviewer's program: row 53 pushes the pending call for `f`; argument 1 `$a` is borrowed → row 33 `ADDREF(h)`, rc 2; argument 2 `$a[0] = 2` → `FETCH_W`: rc 2 → `SEPARATE` ($a now holds h', whose slot was unwrapped from the rc-1 reference c), write 2 into h'; row 58 moves h into `$x`; `$x[0]` reads h's slot through c → **1**. ✓

Fixtures: php-src `Zend/tests/temporary_cleaning_0*.phpt` (already selected), `Zend/tests/call_*`/`Zend/tests/named_params/*.phpt` (filtered), `tests/lang/passByReference_0*.phpt`; per row: the reviewer's program; a method call whose receiver is reassigned in an argument (`$o->m($o = null)` keeps the original receiver); `new C(throwing())` destroying the half-constructed object (constructor never ran, destructor **not** called, as Zend marks ctor-failed objects); an undefined function with a side-effecting argument (no side effect); a throwing third argument after two object arguments (both destroyed during unwinding, in order).

## 2. Uninitialized and readonly typed properties under `=&` (P1-2): `zend_fetch_property_address` with `BP_VAR_W`

Zend, fetching a typed property for write on an **uninitialized** slot: if the declared type is nullable, the property is initialized to `null` and the fetch proceeds; otherwise `Error: Cannot access uninitialized non-nullable property C::$x by reference`. A readonly property refuses the reference fetch in every state. Round 6's row 41 invented a sentinel-holding reference; withdrawn.

| # | Construct | Rule |
|---|---|---|
| 41 (revised) | `$r =& $o->p`, `foreach ($o->p as &$v)`, `f($o->p)` by ref, `[&$o->p] = …`, where `p` is typed | **initialized**: `MAKE_REF` on the slot, `sources += [o p]`. **uninitialized, nullable** (`?T`, `T\|null`, `mixed`): the slot is initialized to `null`, then as initialized. **uninitialized, non-nullable**: `Error: Cannot access uninitialized non-nullable property C::$p by reference`, nothing changes. **readonly**: `Error` in every state; the 8.3 texts are `Cannot modify readonly property C::$p` for an initialized property and `Cannot indirectly modify readonly property C::$p` for an uninitialized one (both oracle-confirmed before the slice lands; a text mismatch is a row defect). **unset typed property with `__get`**: `Notice: Indirect modification of overloaded property` and a reference to a temporary (corner C4's territory, pinned as restricted). |
| 59 | read-write fetch on uninitialized typed property (`$o->p++`, `$o->p .= …`, `$o->p[] = …`) (`BP_VAR_RW`) | `Error: Typed property C::$p must not be accessed before initialization`; `$o->p[] = …` on an uninitialized `array`/`?array`/`mixed` property **does** auto-vivify (Zend's `W` fetch for dim-write), so only `RW` operators error |
| 44 (revised) | source removal | unchanged, plus: initializing a nullable property to `null` under row 41 adds the source like any initialized property |

Fixtures: php-src `Zend/tests/type_declarations/typed_properties_0{44..99}*.phpt` and the `typed_properties_1*` series filtered for `&`, `reference`, `uninitialized`, `readonly`; `Zend/tests/readonly_props/*.phpt` filtered for `reference`; per branch of row 41 and row 59.

## 3. Iterator migration and the internal pointer (P1-3): `zend_hash_iterator_pos_ex`, `nInternalPointer`

`FE_FETCH_RW` calls `zend_hash_iterator_pos_ex(iter, array)`. If the iterator's table is the one it was registered on, it answers the iterator's position without separating (round 6's row 25, correct for the ordinary case). If the loop variable now holds a **different table** (assigned during the loop), the iterator **migrates**: the new table is `SEPARATE_ARRAY`d when shared, the iterator is re-registered on the resulting table, and its position becomes that table's **internal pointer** (`nInternalPointer`, moved by `next`/`prev`/`end`/`reset`/`each`-style builtins, initially the first element). Round 6's row 26 said "reset to position 0 of the new table" and omitted separation; both wrong.

Substrate: an instance carries `:ptr`, the internal pointer (an index into its order vector, default 0, adjusted on deletes like iterator positions); the iterator cell carries `:arr h`.

| # | Construct (handler) | Rule |
|---|---|---|
| 25 (revised) | `FE_FETCH_RW` | let `h_now` be the instance in the loop reference's value (a plain array there is upgraded first). **Same table** (`h_now = iter.arr`): fetch the bucket at `iter.pos` without separation, as round 6. **Migration** (`h_now ≠ iter.arr`): if `rc(h_now) > 1` → `SEPARATE` the loop reference's value (DUP, unwrapping rc-1 references in the copy) and let `h_now` be the copy; unregister the iterator from the old table, register on `h_now`, `iter.pos := ptr(h_now)`; then fetch at `iter.pos` as in the same-table case |
| 54 | iterator copy tracking (`zend_array_dup` does not carry iterators) | iterators stay registered on the table they were on; a `SEPARATE` performed by **another** variable (`$b = $a; $b[0] = 9` inside the loop) copies the table for `$b` and leaves the iterator on the original, which the loop reference still holds, so the loop is unaffected; only the loop reference's own table change triggers migration |
| 55 | internal-pointer builtins (`current`, `key`, `next`, `prev`, `reset`, `end`, `array_key_first` is pure) | operate on `:ptr` of the instance the argument holds; `next`/`prev`/`end`/`reset` take the array by reference (row 35) and separate when shared; on a plain array the pointer is kept in the variable's cell beside the value (a plain array has no instance; `:ptr` is stored as `{:php/arr-ptr i}` metadata in the holder, promoted into the instance on upgrade); deletes adjust `:ptr` as they adjust iterators (row 26) |
| 26 (revised) | mutation with registered iterators | `unset` and append as before; **rebuild by a builtin** (`sort`, `array_splice`, `shuffle`, `array_unshift`): the builtin receives the array by reference, separates when shared, and produces a new order; Zend's `zend_hash_iterators_update`/`zend_hash_iterators_lower_pos` keep each registered iterator at the same *ordinal position* in the rebuilt table (not the same key); `:ptr` is reset to 0 by `sort`-family builtins; **whole-array assignment** is no longer in this row: it is migration, row 25 |

Trace of the reviewer's program: `FE_RESET_RW` makes `$a` a reference holding instance h_a (rc 1), iterator on h_a at 0; `$v` bound to h_a[0]; `$a = $b` through the reference: `ASSIGN` row 2 replaces the value with h_b (rc 2: `$b` and the reference), h_a released; next `FE_FETCH_RW`: migration, rc(h_b) 2 → `SEPARATE`: the reference now holds h_b' whose slot 0 was unwrapped from the rc-1 reference c (value 2); iterator on h_b' at `ptr` 0; `$v` bound to h_b'[0]; `$v = 3` writes h_b'; `break`; `$b[0]` reads h_b's slot through c → **2**. ✓

Fixtures: `tests/lang/foreachLoop.*` already selected (the series includes assignment-during-iteration and internal-pointer cases), `Zend/tests/foreach_*` and `ext/standard/tests/array/current_variation*.phpt`, `next_*`, `reset_*`, `end_*` filtered to the surface; per row: the reviewer's program; `$a = $a` mid-loop (no migration); migration to a table whose `:ptr` was moved by `next()` before the loop; `sort($a)` mid-loop with the iterator's ordinal preserved; `$b = $a; $b[0] = 9` mid-loop leaving the loop unaffected.

## 4. Clone and readonly reinitialization (P2-4): `zend_objects_clone_members`, `IS_PROP_REINITABLE`

PHP 8.3 allows readonly properties of the **clone** to be reinitialized inside `__clone` once each: at `__clone` entry every readonly property of the clone is flagged reinitializable; an assignment or `unset` of such a property is permitted and clears its flag; a second modification is the readonly `Error`; the flags are cleared when `__clone` returns (including by exception). Acquiring a reference to a readonly property stays an `Error` (row 41).

| # | Construct | Rule |
|---|---|---|
| 52 (revised) | `clone $o` | the reference-copy rule stands (rc-1 references unwrapped into the clone, shared references `ADDREF`ed, typed sources gain `[clone p]`); then, if `__clone` is defined: set `:reinit #{readonly prop names}` on the clone, run `__clone` as a method call (row 53/58 ownership), clear `:reinit` in a finally. Inside `__clone`, `$this->ro = v` or `unset($this->ro)` with `ro ∈ :reinit` is allowed from the declaring scope (the oracle pins the scope rule for child classes) and removes `ro` from `:reinit`; a second modification, or any modification outside `__clone`, is `Error: Cannot modify readonly property C::$ro`; readonly reinitialization through a **reference** or by-ref `foreach` is still refused |

Classification: **in the table** (specified fragment), since `readonly` is part of "type declarations are not silently ignored". Fixtures: php-src `Zend/tests/readonly_props/readonly_clone_*.phpt` and `Zend/tests/readonly_props/*clone*`; per branch: single reinit, double reinit error, `unset` then assign, reinit after `__clone` returned (error), exception inside `__clone` clearing the flags.

## 5. The convergence rule, completed (P2-5)

Added to B.3 as a stated rule:

> **Claimed rows are normative, and a known-incorrect claimed row blocks.** A row of the transition table is a statement of Zend's behaviour, fixed from the handler source and **confirmed by the oracle before the slice that claims it lands**; after landing, a disagreement between a row and the pinned PHP CLI is a defect of the row, never of the fixture. A finding that a claimed row contradicts Zend blocks landing of any slice whose support profile includes that row until one of two things happens: the row is corrected (and its fixture regenerated from the oracle), or the Architect explicitly moves the behaviour to B.2 as a restricted corner with a departure fixture whose expected value is the recorded deviation, with the owner's acceptance when the corner touches a Phase 3 exit criterion. Regenerating a fixture to match an incorrect row is forbidden: oracle fixtures pin behaviour, they do not legitimize a contradictory normative rule. The landing gate is therefore: every claimed-row fixture equals the oracle; every corner's departure fixture equals its recorded deviation; no fixture failure is unclassified.

The three bins of B.3 become four, in blocking order: (1) a defect in a claimed row (blocks); (2) a missing row required by an exit criterion or the C4 profile (blocks); (3) a reclassification request, row to corner (an Architect decision recorded in the ledger; blocks until decided); (4) a new corner (tracked, non-blocking). Round 6's row 25 hedge ("the oracle decides and the row flips") is restated under this rule: the oracle decides how the row is **written before landing**; it never excuses a row afterwards.

## Slice and owner-decision deltas

| Slice | Change |
|---|---|
| P3 | rows 53 to 59 and the revised 25, 26, 33 to 36, 39, 41, 44, 52; `:ptr` on instances and plain-array holders; pending-call records; new php-src selections (`named_params`, `passByReference_0*`, `readonly_props`, `typed_properties` uninitialized/readonly, `current/next/reset/end` variations) |
| P4 | readonly reinitialization in `__clone`; ctor-failed objects skip `__destruct` |
| known-gaps ledger | the B.3 blocking rule becomes the PHP section's standing rule, beside Q6 |

Owner decision **B8.13 (new)**: adopt the blocking rule of §5 as binding for `yang.php` landings (recommended; it is the Q6 review rule applied to normative rows). No other decision changes.

## What round 7's reviewer should probe

1. **Row 57's named-argument error position**: Zend checks unknown names at `SEND` time when the callee is already known and at entry otherwise; I pinned "at entry after all arguments" for user functions and left the two cases to the oracle. The reviewer may want the exact handler branch (`zend_handle_named_arg`) stated rather than oracle-pinned.
2. **Ctor-failed objects** (`new C(throwing())`): Zend marks `ZEND_ACC_CTOR_FAILED`-style state so `__destruct` is skipped (`zend_object_store_ctor_failed`); the row says "destructor not called", which should be confirmed for the case where the exception is thrown by an *argument* before the constructor frame starts (then the object exists with its destructor flag cleared: Zend calls `zend_object_store_ctor_failed` in `NEW`'s cleanup).
3. **Row 25's migration position** uses the new table's internal pointer; Zend's `_zend_hash_get_current_pos` returns `nInternalPointer` unless it is past the end, in which case the iteration is at end. The reviewer may probe a migration to an array whose pointer was moved by `end()`.
4. **Row 55's plain-array pointer storage** (metadata on the holder promoted on upgrade): whether a plain array copied to another variable copies the pointer (Zend: the copy shares the table, so the pointer is shared until separation; after separation the copy's pointer equals the original's at dup time). My rule copies the pointer with the value; a `next($a); $b = $a; current($b)` fixture pins it.
5. **Readonly reinitialization scope** for child classes (8.3 allows the declaring class scope only; 8.4 widened it); pinned by oracle at 8.3.
6. **The blocking rule's reclassification bin**: whether the owner's acceptance should be required for every reclassification rather than only those touching exit criteria.
