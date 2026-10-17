Created-GMT: 2026-10-15 09:50:28 GMT
Created-Local: 2026-10-15 16:50:28 +07
Completed-GMT: 2026-10-15 09:55:00 GMT
Completed-Local: 2026-10-15 16:55:00 +07
Coding-Agent: claude (claude-fable-5-1)
Session-ID: pending
Brief: ./collab/1793100000000-architect-8f3-nan-ruling.prompt.md
Branch: srvm-phase8f3 @ 7b8d2a93 (no source edits; probe test ns created and deleted; tree clean apart from collab/)

# Architect ruling: 8f-3 Node+Dart `noncanonical-registers` on NaN programs

## 1. Verdict

Root cause confirmed by reading the code and by a JVM probe. It is the
hypothesised bug class: §3.4 item 13 (`canonical-projection` in
`src/cljc/yin/vm/semantic_register/code.cljc`, lines 815 and 820) compares a
re-projected tuple against the loaded tuple with host `=`, whole tuple,
literal operands included. The re-projection copies every literal operand by
reference (`with-registers`, line 703, `:else (nth t (inc i))`), so the two
tuples hold the *same* NaN. The JVM's `=` short-circuits on reference identity
(`Util.equiv`: `k1 == k2`) and passes. On JS a NaN is a primitive: `identical?`
is `===`, false for NaN, and the number `-equiv` is also false. On Dart `=` on
doubles is IEEE `==`, false for NaN. So the check reports the first tuple that
carries a NaN literal as `:noncanonical-registers` at its pc. The two failing
tests put `##NaN` straight into the test form (`(py/float (data/float-value
##NaN))`, int_ops_test.cljc 402-412 and 533-536), which lowers to a
`[:const rd ##NaN]` tuple; pc 1600/1601 is the first such tuple after the
prelude. ±inf, -0.0 and large floats are NOT part of this defect: they are
reflexive under `=` on all three hosts.

This passed in 8e only because the `:semantic` runner then ran the old kernel,
whose loader had no item 13. Nothing about the Python prelude changed.

## 2. Evidence (JVM probe, throwaway ns `yin.vm.nan-probe-test`, deleted)

```
A  well-formed? [[:const 0 ##NaN] [:halt 0]]                 -> nil   (shared box; JVM identity)
A2 well-formed? NaN in a body and inside a data literal      -> nil   (same reason)
B  (= [:const 0 nanA] [:const 0 nanB]), distinct boxes        -> false (JVM = is not NaN-reflexive either; only identity saves it)
C  (cbor/content= [:const 0 nanA] [:const 0 nanB])            -> true
D  (cbor/content= t (copy t)) for every mnemonic in §2.3,
   incl. symbols, keyword ops, [regs], bool, nil buffer, str,
   bigint literal                                             -> true for all 22 shapes (no refusal, no throw)
E  nested distinct NaNs in {:k} and [...] with ±inf, -0.0,
   #{} , "s", 1/2, 1.5M: content= true, host = false
F  content= keeps -0.0 apart from 0.0 (host = merges them);  int vs float stays apart
G  semantic/load-vector + vm/run of [[:const 0 ##NaN] [:halt 0]] -> value ##NaN   (kernel executes NaN fine)
H  reload of the identical vector into the same machine      -> :ok
I  reload of a content-equal vector with a FRESH NaN box      -> throws "Cannot load segment: the id already holds different code"
```

Probe I exposes a second site of the same class (see §3, F2).

## 3. The fix (minimal, two sites, one new helper)

F1 — `src/cljc/yin/vm/semantic_register/code.cljc`

Add `[dao.jing.cbor :as cbor]` to the ns requires and one private helper next
to `with-registers`:

```clojure
(defn- tuple=
  "Item 13's tuple equality. Host `=` is not NaN-reflexive on JS and Dart
   (only the JVM short-circuits on the shared box), so a NaN literal would
   refuse its own re-projection. Slot by slot: the re-projection copies
   literal operands by reference, so `identical?` settles them without a
   traversal; everything else goes through the encoder's own kind-strict
   equality, under which canonical NaNs are equal and 0.0 and -0.0 are not."
  [a b]
  (and (= (count a) (count b))
       (every? true? (map cbor/content= a b))))
```

(`cbor/content=` already begins with `identical?`, so the slotwise form gives
the reference shortcut for free.) Then replace the two comparisons in
`canonical-projection`:

- line 815: `(when-not (= t (nth v pc))` → `(when-not (tuple= t (nth v pc))`
- line 820: `(when-not (= (get canon pc) (nth v pc))` → `(when-not (tuple= (get canon pc) (nth v pc))`

Why slotwise rather than `(cbor/content= t (nth v pc))` on the whole tuple:
the whole-tuple form builds a `content-key` over every data literal four
times per load (two sides, register pass and layout pass); the slotwise form
hits `identical?` on the literal slot and never walks it. Either form is
correct; the slotwise one is the one to ship. Do NOT compare only register
slots in the layout pass: there the canon tuple at pc may come from another
pc of `v`, so literals must be compared, and content= does that.

Behaviour notes for the rule: item 13 keeps refusing real register and layout
defects in NaN-carrying vectors (content= is a strict equality, F below pins
it). A `-0.0` literal versus a `0.0` literal is now a difference (host `=`
merged them), which is the encoder's own notion of canonical content and is
the right answer for a content-addressed vector. On JS, `content-key` refuses
an unsafe raw integral Number; such a literal could not reach `segment-key`
either (same refusal), so no vector that loads today stops loading.

F2 — `src/cljc/yin/vm/semantic_register.cljc` line 281 (`store-image`)

`(= (val existing) image)` compares two whole images (vector, bodies,
analysis) with host `=`. With a NaN literal this throws "the id already holds
different code" for a content-equal reload: on the JVM already when the box
differs (probe I), on JS and Dart even for the identical vector (probe H would
fail there). Both images under one segment id are deterministic functions of
their vector, and `:address` is `jing/segment-key` (a keyword
`:segment/blake3-<digest>` over the canonical encoding), so address equality
is content equality. Replace with:

```clojure
(if (= (:address (val existing)) (:address image)) images ...)
```

No helper, no traversal, host-neutral. (Alternative, if the Architect of the
carrier prefers vector comparison: `(cbor/content= (:vector (val existing))
(:vector image))`. Address comparison is the one to ship.)

Nothing else on the load/derive/allocation path compares literal values:
every other `=`/`not=`/`set`/`distinct` in `code.cljc`, `derive.cljc`,
`linearize.cljc`, `layout.cljc`, `analysis.cljc`, `scan.cljc` is over
mnemonics, pcs, register ids, counts, symbols or kinds (surveyed by grep; the
list is in the orchestrator's transcript).

## 4. Tests to add (portable `.cljc`, all three lanes)

T1 — `test/yin/vm/semantic_register/code_test.cljc`, new deftest
`item-13-compares-literal-operands-by-content` (keep the `refusals` table
and `every-item-has-a-refusal-row` untouched). Accepted (`nil` from
`code/well-formed?`):

```clojure
'[[:const 0 ##NaN] [:halt 0]]
;; NaN in a body and in main: exercises the per-body register pass and the
;; whole-segment layout pass
'[[:closure 1 [x] 4] [:const 2 ##NaN] [:call 0 1 [2] false] [:halt 0]
  [:const 0 ##NaN] [:return 0]]
;; NaN and signed zero nested inside data operands
'[[:const 0 [##Inf ##-Inf -0.0 0.0 ##NaN {:k ##NaN}]] [:halt 0]]
'[[:store-put 0 {:k ##NaN} [##NaN]] [:halt 0]]
```

Refused, pinning that NaN does not mask a defect and that content= is strict:

```clojure
;; rd must be 0: the NaN literal is beside the point
[{:rule :noncanonical-registers :pc 0} '[[:const 1 ##NaN] [:halt 1]]]
;; (f (fn [x] NaN) (fn [y] Inf)) with its bodies swapped; the layout pass
;; now compares [:const 0 ##NaN] against [:const 0 ##Inf] and must differ
[{:rule :noncanonical-layout :pc 1}
 '[[:var 1 f] [:closure 2 [x] 7] [:closure 3 [y] 5] [:call 0 1 [2 3] false]
   [:halt 0] [:const 0 ##Inf] [:return 0] [:const 0 ##NaN] [:return 0]]]
```

(Codex: verify the pc of the second refusal by running it; the existing
swapped-bodies row reports pc 1, where the first `:closure` sits, because the
layout pass reports the least differing pc; the first differing tuple here
is also at the closure site. Pin whatever `well-formed?` returns on the JVM
after the fix, then confirm Node and Dart agree.)

These rows fail today on Node and Dart (every accepted row above returns
`{:rule :noncanonical-registers :pc 0}` there) and pass on the JVM, which is
exactly the gap the lanes left open.

T2 — a new deftest in `test/yin/vm/semantic_engine_test.cljc` (it already
drives the register kernel) `a-nan-literal-loads-runs-and-reloads`:

```clojure
(let [nan   (* 1.0 ##NaN)             ; a fresh box on the JVM, a plain NaN elsewhere
      v1    [[:const 0 ##NaN] [:halt 0]]
      v2    [[:const 0 nan] [:halt 0]]  ; content-equal, not identical on the JVM
      m     (semantic/load-vector (semantic/create-vm {}) v1 code/contract)
      value (vm/value (vm/run m))]
  (is (not= value value) "the kernel yields NaN")        ; portable NaN test
  (is (cbor/content= ##NaN value))
  (let [m2 (semantic/load-vector m v1 code/contract)     ; identical reload (fails today on JS/Dart)
        m3 (semantic/load-vector m v2 code/contract)]    ; content-equal reload (fails today on the JVM too)
    (is (= (:program m) (:program m2) (:program m3)) "one address, one segment id")))
```

T3 — no new Python test: `yang.python.antlr.int-ops-test/nan-comparisons-test`
and `float-division-edge-test` are the regression and go green on Node and
Dart with F1. Keep them.

## 5. Other kernels and paths

- `derive.cljc` (`stack-image`, `register-image`, the `:stack` and `:register`
  runners) calls `code/parse`, not `well-formed?`; item 13 never runs there,
  which is why those runners stayed green in 8e and 8f-3. No change.
- `debruijn_register_code.cljc` rules (`live-exact-rule` 641, 368, 518, 550)
  compare live sets, arities and owners: integers only. No change.
- `debruijn_code.cljc` 233 `(not= v v)` is a deliberate NaN detector. No change.
- `yin.vm/same-content?` (vm.cljc 1072) already uses `cbor/content=` after
  the 8e ruling. `yin.vm.data/data-content=` likewise.
- Known gap, OUT of this ruling's scope, for the ledger:
  `src/cljc/yin/vm/semantic_register/frame.cljc` 141 compares a captured
  frame's free-environment bindings with the task's free-env by host `=`. A
  NaN-valued free binding in a captured, parked or waiting frame would be
  refused as `:free-env-mismatch` on JS and Dart. Same class, different layer
  (carrier validation, not load). Fix shape when taken: slotwise content
  equality guarded for callables and refs the way `data-content=` does.

## 6. Ordered steps for codex

1. In `code.cljc`: add the `dao.jing.cbor` require, add `tuple=` after
   `with-registers`, switch lines 815 and 820 to `tuple=`. Update the
   `canonical-projection` docstring with one sentence: tuples compare by the
   encoder's content equality, not host `=`.
2. In `semantic_register.cljc` `store-image`: compare `:address`, not the
   image.
3. Add T1 to `code_test.cljc` and T2 to `semantic_engine_test.cljc`
   (`cbor` and `code` requires as needed).
4. JVM: `clojure -M:test -n yin.vm.semantic-register.code-test -n
   yin.vm.semantic-engine-test -n yang.python.antlr.int-ops-test`. Expect T2's
   content-equal reload to have FAILED before step 2 and pass after.
5. Node and Dart lanes for the same three namespaces (foreground, one lane at
   a time per docs/agents/build-n-test.md); confirm "Testing
   yin.vm.semantic-register.code-test" appears in the node output and that
   `nan-comparisons-test` / `float-division-edge-test` report the Python
   vectors, not `[:thrown ...]`.
6. Quick tier (G1) before handing back; the design doc
   `docs/design/yin.vm.semantic-register-vm.md` §3.4 item 13: add one clause
   that tuple equality is content equality (kind-strict, canonical NaN equal,
   signed zero distinct). No other doc changes.
7. Report path `./collab/<ms>-engineer-8f3d.report.md`, ending with the three
   lanes' counts for the three namespaces.

RULING-READY
