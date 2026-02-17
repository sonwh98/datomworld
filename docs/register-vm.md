# Register VM Inefficiencies

Analysis of `src/cljc/yin/vm/register.cljc` hot path.

## 1. Multiple `assoc` calls create throwaway intermediate maps

Every opcode handler chains 2-6 `assoc` calls via `->`, each creating a
discarded intermediate map. Affects `:call` (6 assocs), `:return` (5),
`:loadk`/`:loadv`/`:move`/`:closure` (2-3 each).

Fix: single multi-kv `assoc`:
```clojure
;; before (5 intermediate maps)
(-> state (assoc :regs []) (assoc :k new-frame) (assoc :env new-env) ...)

;; after (1 map)
(assoc state :regs [] :k new-frame :env new-env :bytecode bytecode :pool pool :ip body-addr)
```

## 2. `set-reg` bounds-checks on every register write

`set-reg` (line 298) checks `(> r (dec (count regs)))` on every single write.
When growth is needed, `(into regs (repeat ...))` creates a lazy seq intermediary.
After the first few instructions registers rarely grow, but the branch is always taken.

Fix: pre-allocate registers at frame entry. The compiler could emit a register
count per function body, then `:call` and `create-vm` allocate
`(vec (repeat n nil))` upfront. Eliminates the growth check entirely.

## 3. Bug: `:loadv` `or` chain fails on falsy bindings

Lines 339-343:
```clojure
(or (get env name)
    (get store name)
    (get (:primitives state) name)
    (module/resolve-symbol name))
```

If `env` maps `name` to `false` or `nil`, the `or` falls through to
store/primitives/module resolution. This is a correctness bug.

Fix: use `find` or `contains?`:
```clojure
(if-let [pair (find env name)]
  (val pair)
  (if-let [pair (find store name)]
    (val pair)
    (or (get (:primitives state) name)
        (module/resolve-symbol name))))
```

## 4. `:primitives` not destructured in `reg-vm-step`

Line 325 destructures `{:keys [bytecode pool ip regs env store k]}` but not
`:primitives`. Every `:loadv` does `(:primitives state)` as an extra map lookup.

Fix: add `primitives` to the destructuring.

## 5. `blocked?` checked every step

`reg-vm-eval` loop (line 498) checks both `halted?` and `blocked?` every
iteration. `blocked?` does `(= :yin/blocked (:value vm))`. The VM is almost
never blocked.

Fix: only check blocked after operations that can actually block (`:stream-take`),
or fold blocked into a truthy `:halted` sentinel.
