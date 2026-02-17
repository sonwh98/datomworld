# VM Inefficiencies & Bugs

Cross-VM analysis of `src/cljc/yin/vm/` and `src/cljc/yin/vm.cljc`.

## Cross-VM Summary

| Issue | Register | Stack | Semantic | Walker |
|---|---|---|---|---|
| Falsy binding bug | `or` chain | no fallback at all | no fallback at all | `or` chain |
| `blocked?` every step | yes | yes | yes | yes |
| `primitives` not destructured | yes | n/a | n/a | yes |
| Opcode dispatch | `case` (good) | `condp =` (linear) | n/a | n/a |
| O(n) data access on hot path | `set-reg` growth | `take-last`/`drop-last` | full datom scan per node | - |
| Intermediate map churn | multi-`assoc` chains | - | - | - |

---

## Bugs (correctness)

### Falsy binding resolution (all VMs)

Variables bound to `false` or `nil` are mishandled in all four VMs.

**Register VM** (`register.cljc:339-343`) and **AST Walker** (`ast_walker.cljc:151-154`):
The `or` chain falls through to store/primitives/module when env maps the name
to a falsy value.

```clojure
;; broken
(or (get env name) (get store name) ...)

;; fix: use find
(if-let [pair (find env name)]
  (val pair)
  (if-let [pair (find store name)]
    (val pair)
    (or (get primitives name) (module/resolve-symbol name))))
```

**Stack VM** (`stack.cljc:235`) and **Semantic VM** (`semantic.cljc:208`):
Only check `env`, with no fallback to store/primitives/modules at all.
A variable not in env silently resolves to `nil`.

```clojure
;; stack: only checks env
val (get env sym)

;; semantic: only checks env
:variable (assoc vm :control {:type :value, :val (get env (:yin/name node-map))})
```

These need the full resolution chain (env -> store -> primitives -> module),
using `find`/`contains?` to handle falsy values correctly.

---

## Shared kernel (vm.cljc)

### Lazy seqs with side-effecting atom in `ast->datoms` (line 163)

`lazy-seq`/`lazy-cat` wraps calls to `gen-id` which mutates an atom. If a lazy
seq is realized more than once (held and re-traversed), entity IDs shift.
Either eagerly produce datoms or thread IDs functionally.

---

## Register VM (register.cljc)

See also `docs/register-vm.md` for detailed analysis.

### Multiple `assoc` calls create throwaway intermediate maps

Every opcode handler chains 2-6 `assoc` calls via `->`. Use single multi-kv `assoc`.

### `set-reg` bounds-checks on every register write (line 298)

Growth check runs every write. When growth is needed, `(into regs (repeat ...))`
creates a lazy seq intermediary. Fix: pre-allocate registers at frame entry using
a compiler-emitted register count.

### `primitives` not destructured in `reg-vm-step` (line 325)

Every `:loadv` does `(:primitives state)` as an extra map lookup.

---

## Stack VM (stack.cljc)

### `condp =` instead of `case` for opcode dispatch (line 227)

`condp =` does sequential equality tests (linear scan). `case` compiles to a
jump table (O(1)). This is the hot path, called every single step. The register
VM gets this right with the `opcase` macro.

### `take-last`/`drop-last` in OP_APPLY (lines 253-254)

Both are O(n) on the full stack, each creates a lazy seq materialized to a vector.

```clojure
;; broken: O(n) twice + 2 lazy seq allocations
args (vec (take-last argc stack))
stack-minus-args (vec (drop-last argc stack))

;; fix: O(1) subvec
(let [n (count stack)
      args (subvec stack (- n argc) n)
      fn-val (nth stack (- n argc 1))
      stack-rest (subvec stack 0 (- n argc 1))]
  ...)
```

---

## Semantic VM (semantic.cljc)

### Full datom scan per node lookup (lines 58-72) — worst issue

`datom-node-attrs` does `(reduce ... datoms)` scanning ALL datoms to find
attributes for one node. O(n) per lookup where n = total datom count. Called
for every node evaluated.

Fix: build an entity index once at load time:
```clojure
(group-by first datoms)
```
Then each lookup is O(1) hash + O(k) where k = datoms per entity (typically 2-4).

### `(vec (rest ...))` materializations in hot path (lines 154, 185)

Creates lazy seq then forces to vector for operand/pending lists.
Fix: use `(subvec v 1)` on original vector.

### No fallback to store/primitives for variable resolution (line 208)

Only checks env. See falsy binding bug above.

---

## AST Walker (ast_walker.cljc)

### `primitives` not destructured in `cesk-transition` (line 47)

Destructures `{:keys [control environment continuation store]}` but omits
`primitives`. Every `:variable` lookup does `(:primitives state)` as extra
map access.

### Unnecessary nil guard (line 341)

```clojure
(or (:parked state) {})
```

`parked` is always initialized to `{}` in `vm/empty-state`. The `or` never fires.

---

## All VMs: `blocked?` checked every step

Every eval loop checks `(or (halted? v) (blocked? v))` per iteration.
`blocked?` does `(= :yin/blocked (:value vm))` — almost never true during
normal execution.

Fix: only check blocked after operations that can block (stream-take), or fold
blocked into a truthy `:halted` sentinel.
