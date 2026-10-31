Completed-GMT: 2026-10-17 08:49:12 GMT
Completed-Local: 2026-10-17 15:49:12 +0700

# Revised designs, round 9: three generalizations corrected, narrowly

Role: Lead System Architect. Findings only; nothing edited. This round answers the round-8 codex review (`collab/1792227000000-reviewer-ts-php-r8.gpt-6.1-sol.findings.md`): three P1 defects in the rows I generalized from the oracle results F1 to F3, all accepted. The oracle results stand; the corrections below change only the generalizations, each tied to the Zend routine the reviewer cites. Every other row, the corner table and the blocking rule are unchanged.

## 1. Iterator copy lifecycle (P1-1): `zend_array_dup` iterator duplication, `zend_hash_iterator_pos_ex`, `zend_hash_iterator_find_copy_pos`

What Zend does, read narrowly: when a table with registered iterators is duplicated, the iterators' positions are **duplicated onto the target table and registered there**, so they follow that table's own mutations exactly as positions on the source follow the source's. When a fetch finds the loop variable holding a different table, it first looks for a copied position for that table (`find_copy_pos`); a hit **adopts that table and that position without separating it, even when the table is shared**; only a miss falls to the unrelated-table path (separate when shared, position from the internal pointer). Copied positions live until the iterator migrates or is freed; an ordinary same-table fetch does not expire them. The stored position is the **next bucket to fetch**.

Round 8's rows 25, 60 and 61 are corrected to that lifecycle; nothing else in the iteration rows changes.

| # | Construct (handler) | Rule |
|---|---|---|
| 60 (revised) | `DUP(h)` with iterators registered on h | for every iterator `it` registered on h: register `it` on h′ with `pos(it, h′) := pos(it, h)` (the next-bucket position at duplication). Records are per table: `it.tables = {h → pos, h′ → pos, …}`, `it.current = h`. h′'s own mutations (row 26) adjust `pos(it, h′)` independently of h's. A duplication of h′ carries the records again, so chains propagate |
| 25 (revised) | `FE_FETCH_RW` | let `h_now` be the instance the loop reference holds. **Same table** (`h_now = it.current`): fetch the bucket at `pos(it, h_now)`, then `pos := next existing key`. **Copy hit** (`h_now ∈ it.tables`, `find_copy_pos`): adopt **without separation**, `it.current := h_now`; the records on every other table are deleted (their tables' iterator counts drop); then fetch as in the same-table case. **Unrelated** (`h_now ∉ it.tables`): round 7's rule unchanged: `SEPARATE` the loop reference's value when `rc(h_now) > 1`, register at `ptr` of the resulting table, delete the old records, fetch |
| 61 (replaced) | record lifetime | records are deleted only on migration (the non-adopted ones) and on `FE_FREE` or destruction of their table; a same-table fetch never expires them |
| 54 | unchanged | a separation performed by another holder creates a record on that holder's copy, which stays inert unless the loop reference later adopts that table |

Traces, both with the next-bucket convention:

- **F1** `$a=[10,20,30]`: fetch h[0] (`10`), `pos(it,h) := 1`; `$b = $a` (rc 2); `$a[2] = 99` → `SEPARATE` → `DUP(h)` → h′ with `pos(it,h′) := 1`; write h′[2]; next fetch: loop reference holds h′, copy hit, adopt, fetch h′[1] (`20`), then h′[2] (`99`). `$b` keeps h. Output `10,20,99,` ✓.
- **Reviewer's program** `$a=[1,2]`: fetch h[0], `pos := 1`; `$b = $a` (rc 2); `$b[1] = 5` → `$b` separates: `DUP(h)` → h_b with `pos(it,h_b) := 1`, write 5; `$a = $b` through the loop reference: it now holds h_b (rc 2: `$b` and the reference); next fetch: copy hit on h_b → **no separation**; fetch h_b[1]: `MAKE_REF` on the shared slot, `$v` bound; `$v = 9` writes through; `break`; `$b[1]` reads h_b → **9** ✓.
- Round 6's unrelated-table program is unaffected (no record on `$b`'s table): still **2**. Round 7's "copy before the loop, assign back" program: `FE_RESET_RW` registers the iterator after separating, so the pre-loop copy's table has no record → unrelated → separate → still restarts at `ptr`.

Fixtures (P3, `iteration-v1`): F1 and the reviewer's program verbatim; a chain copy adopted two iterations later (records survive same-table fetches: position carried, no separation); a copy whose table was mutated by `unset` before adoption (its record adjusted on that table); a copy made, then the source mutated, then the copy adopted (the record on the copy is the position at duplication, adjusted only by the copy's mutations); adoption of a shared table followed by a write through `$v` visible to the other holder.

## 2. Named-argument validation at the send, after the argument's own evaluation (P1-2): `CHECK_FUNC_ARG`, `SEND_VAL_EX`/`SEND_VAR_EX`, `zend_handle_named_arg`

Zend compiles each argument as `CHECK_FUNC_ARG` (which looks the name up only to decide by-reference sending and **defers** an unknown name), then the argument expression's own opcodes, then the `SEND_*_EX` for it, where `zend_handle_named_arg` validates the name and raises `Unknown named parameter` or `Named parameter … overwrites previous argument`. So the offending argument's expression **runs**; later arguments do not. F2's first argument was a constant, so it could not show this; round 8's row 57 ("before evaluating `expr`") is corrected.

| # | Construct (handler) | Rule |
|---|---|---|
| 57 (revised) | named argument `name: expr` | (1) `CHECK_FUNC_ARG`: look `name` up on the callee only to choose read versus write-fetch for `expr`; an unknown name is **not** an error here and `expr` is fetched for read. (2) Evaluate `expr` (side effects happen; the value is an owned or borrowed operand as rows 32 to 35). (3) At the send, validate: **unknown** and not variadic → `Error: Unknown named parameter $name`; **already supplied** → `Error: Named parameter $name overwrites previous argument`; on either, the just-evaluated value is released (borrowed: nothing; owned: `DELREF`) and pending-call cleanup (row 39) releases the arguments sent so far; later arguments never run. (4) Otherwise acquire and place by name as before (unknown on a variadic callee: collected under the string key) |
| 63 to 65 | unchanged | entry-time defaults and `ArgumentCountError`; positional-after-named compile error; string-key spreads follow row 57's step 3 at the spread's send |

Traces: **F2** `f(bad: 1, x: side())`: `1` evaluated, send `bad` → `Error`; `side()` never runs ✓. **Reviewer's program** `f(bad: side())`: `side()` runs (`seen`), send `bad` → `Error` ✓. `f(1, x: side())` with one parameter: `side()` runs, send `x` → overwrite `Error`.

Fixtures (`oracle-2026-10-17/F2` plus `calls-v1`): the three programs above; `f(bad: new D)` where `D` has a destructor echoing (constructed, then destroyed during cleanup before the `Error` surfaces, order pinned); `g(...)` variadic with `g(bad: side())` (runs, collected); a by-reference named parameter with an unknown name (`CHECK_FUNC_ARG` chooses read-fetch: the operand's missing key is not vivified; pinned by oracle); php-src `Zend/tests/named_params/unknown_named_param.phpt`, `overwrite_*`, `named_params_variadic*` as direct oracles.

## 3. Readonly initialization clears the clone flag (P1-3): `zend_std_write_property` readonly branch

Zend's successful write to an uninitialized readonly property clears both the uninitialized state and the reinitializable flag (`Z_PROP_FLAG_P(slot) &= ~(IS_PROP_UNINIT | IS_PROP_REINITABLE)`). Round 8's row 66 left the flag set on initialization; corrected. F3 is unaffected: its `unset` already cleared the flag and the following write was an initialization.

| # | Construct (handler) | Rule |
|---|---|---|
| 66 (revised) | write to a readonly property | **uninitialized**: initialization from the declaring scope, allowed; **the flag is cleared** (whether or not it was set). From another scope: `Error: Cannot initialize readonly property C::$x from scope …`. **initialized, `reinitable`**: allowed, flag cleared. **initialized, not `reinitable`**: `Error: Cannot modify readonly property C::$x` |
| 67 | unchanged | `unset`: allowed when `reinitable` (clears it, property becomes uninitialized) or when uninitialized from the declaring scope; otherwise `Error: Cannot unset readonly property C::$x` |
| 52, 68 | unchanged | flags set at `__clone` entry, cleared at exit; references to readonly always refused |

Traces: **reviewer's program** (`x` never initialized, `__clone` writes twice): entry sets the flag; first write is an initialization → allowed, flag cleared; second write: initialized, no flag → `Error` ✓. **F3**: `unset` clears the flag and uninitializes; write initializes → `2` ✓. Two writes on an initialized property in `__clone`: first reinitializes (flag cleared), second `Error` ✓ (round 7).

Fixtures (`oracle-2026-10-17/F3` plus `objects-v1`): the reviewer's program; F3; `unset` then two writes in `__clone` (second `Error`); initialization outside `__clone` from the declaring scope on a never-initialized property followed by a second write (`Error`); php-src `Zend/tests/readonly_props/readonly_clone_*.phpt`, `initialization_scope_*.phpt`, `unset_*.phpt`.

## 4. Bookkeeping

Rows changed this round: 25, 57, 60, 61 (replaced), 66. Fixture groups touched: `iteration-v1`, `calls-v1`, `objects-v1`. Corner table, slices, owner decisions: unchanged. Under the round-7 blocking rule these are claimed-row corrections, each with an oracle fixture to run before P3/P4 land.

## What round 9's reviewer should probe

1. **Copy-hit adoption of a shared table** (row 25): the slot `MAKE_REF` on adoption is visible to every holder of that table; a fixture where the other holder is then copied again (`$c = $b` after adoption) should show the reference preserved in `$c` by `DUP`'s rc>1 rule.
2. **Record deletion on migration**: whether Zend deletes the non-adopted copies at migration or leaves them until `FE_FREE`; observable only if the loop later migrates *back* to a previously copied table (adopt again versus unrelated). One oracle program decides; row 25 says deleted (so a return is unrelated).
3. **`CHECK_FUNC_ARG` with an unknown name on a by-reference-only callee** (`function f(&$x)`, `f(bad: $u['k'])`): read-fetch, no vivification, then the `Error`; pinned by oracle before landing.
4. **Row 67's uninitialized-`unset` branch**, still oracle-pinned as in round 8.
