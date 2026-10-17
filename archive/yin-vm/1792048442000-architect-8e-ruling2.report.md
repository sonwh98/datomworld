Completed-GMT: 2026-10-15 07:14:02 GMT
Completed-Local: 2026-10-15 14:14:02 +07
Coding-Agent: claude (claude-fable-5-1)
Session-ID: pending (not exposed inside this -p session; orchestrator fills from the CLI run)

# 8e Architect ruling, round 2: revised, verified NaN leaf rule; T3 retired and replaced

Worktree /Users/sto/workspace/datomworld-srvm-p8e, branch srvm-phase8e, codex's uncommitted fix-1 diff. Edited: this report only. One throwaway probe namespace, `test/yin/vm/probe_8e_ruling2_test.cljc`, was run on the JVM and deleted (git status clean for that path).

Codex was right on both counts. Round 1 rested on two false premises, both mine:

1. **`(not= a a)` is not a NaN test on the JVM.** Clojure `=` on two `Object` arguments short-circuits on identity before any numeric comparison. A NaN that reaches `same-content?` is a boxed `Double`, and `(not= a a)` on one boxed `Double` is `false`. The precedent I cited, `debruijn-code/double-le-hex`, works only because its `v` is a primitive-typed local; that does not transfer to a generic predicate.
2. **The guard's "known limitation" is stale.** `dao.jing.cbor` encodes symbol metadata (tag 27 `clojure/with-meta`, src/cljc/dao/jing/cbor.cljc:24-27, :1124; docs/design/dao.jing.md:200 says symbol metadata is address-significant). `^{:meaning 1} x` and `^{:meaning 2} x` mint different ids, so the metadata-collision fixture never reaches the guard. The `ast->semantic-bytecode` docstring paragraph that claims otherwise is wrong and must go.

---

## What I probed (JVM, `clojure -M:test -n yin.vm.probe-8e-ruling2-test`, 0 failures)

`a = (* 1.0 ##NaN)`, `b = (* -1.0 ##NaN)`, both passed through a generic `(fn [a b] ...)` so they arrive boxed, exactly as in `same-content?`.

| Expression (boxed a, b) | Result |
|---|---|
| `(= a b)` | false |
| `(not= a a)`, `(not= b b)` | false, false (round-1 rule is dead on JVM) |
| `(== a a)`, `(== b b)` | false, false (`==` goes through `Numbers.equiv`, no identity shortcut) |
| `(cbor/content= a b)` | **true** |
| `(and (number? a) (number? b) (not (== a a)) (not (== b b)))` | true |
| `(== "a" "a")` | throws ClassCastException (the `==` candidate needs its `number?` gate) |
| `(jing/segment-key [:literal a])` = `(jing/segment-key [:literal b])` | true, both `:segment/blake3-88491ea0d131d67ecf50c6a1714868257c996f9518a139262e049ebd428e55b7` (T1 pin stands) |

A copy of `same-content?` with the leaf rule replaced by `(cbor/content= a b)` returns **true** for the flat rows `[id :literal a]` / `[id :literal b]` and for nested rows `[id :literal [a 1 {:k a} #{"s"}]]` / `[id :literal [b 1 {:k b} #{"s"}]]`, and **false** for `[id :literal ^{:meaning 1} x]` vs `[id :literal ^{:meaning 2} x]` (metadata rule still bites).

`cbor/content=` on other leaves: `1`/`1` true, `1`/`1.0` false, `0.0`/`-0.0` false, `1`/`1N` true, `1.0`/`(float 1.0)` true, strings, keywords, symbols, nil, booleans as `=`, `NaN`/`1.0` false, `'x`/`^{:meaning 1} x` true (metadata ignored at the leaf, which is why the metadata rule stays above it).

Slot assertion candidates on the stored value `v`: `(not= v v)` **false**; `(not (== v v))` true; `(cbor/content= ##NaN v)` true.

T3 ids: meaning 1 `…813c887f…`, meaning 2 `…72250312…`, bare `'x` `…5fd45b13…`: three distinct rows, no collision possible.

Cross-host proof, not run by me but already in the tree: `test/dao/jing/cbor_content_equality_test.cljc:60-66` (`canonical-nans-are-one-content`) asserts `(cbor/content= nan-a nan-b)` and runs on all three lanes as a `.cljc` `*-test` namespace. I could not read the ClojureScript or ClojureDart `==` sources from this sandbox (outside the worktree), so `==` is verified on the JVM only and is **not** the prescribed rule.

---

## Revised spec (codex implements; files: src/cljc/yin/vm.cljc and test/yin/vm_test.cljc only)

### 1. Leaf rule: reuse the encoder's own content equality

In `same-content?` replace the `:else` branch with

```clojure
:else (cbor/content= a b)
```

`dao.jing.cbor` is already required in vm.cljc as `cbor` (line 28). No `number?` gate, no `==`, no host conditional, no unary minus. Rationale: `content=` is documented as "two values are content= exactly when content addressing would give them the same identity … canonical NaNs are equal … metadata ignored" (cbor.cljc:906-915). Same id means same canonical bytes, so this is the one predicate that agrees with the encoder by definition; it is kind-strict (`1` vs `1.0`, `0.0` vs `-0.0` differ) exactly where the ids differ, and metadata-blind exactly where the metadata rule above it already decides. Keep everything else in `same-content?` as codex has it: metadata `=` at every depth with recursion into metadata, map/set/sequential count checks, `contains?` before `find`.

Do not use `(and (number? a) (number? b) (not (== a a)) (not (== b b)))`: it works on the JVM (probed) but throws on non-numbers without the gate, and its Node/Dart behaviour has no existing test to lean on.

### 2. Docstrings

* `same-content?`: replace "treating NaN leaves as equal" with "comparing leaves by `dao.jing.cbor/content=`, the encoder's own kind-strict equality, so canonical NaNs are equal and `0.0`/`-0.0` are not". Keep the NaN-key/set-element scope sentence and the comparator-set boundary.
* `ast->semantic-bytecode`: delete the whole "Known limitation, inherited from `dao.jing` …" paragraph (it is false: the encoder hashes symbol and collection metadata, docs/design/dao.jing.md:200). Replace with:

  > Two nodes that mint one id must have one content: the row is checked against the stored row with `same-content?` (metadata at every depth, leaves by `dao.jing.cbor/content=`), and a mismatch throws `"Semantic bytecode address collision"` rather than letting one row silently replace the other. No known input reaches that throw: the encoder hashes symbol and collection metadata, and makes every NaN one quiet NaN, so two NaN scalars are one row value. The guard is a defence against the encoder and this check disagreeing.

### 3. Tests (test/yin/vm_test.cljc)

Add `[dao.jing.cbor :as cbor]` to the test ns requires (after `dao.jing`).

* **T1 `semantic-bytecode-nan-structural-sharing`**: keep as written, but the slot assertion becomes
  ```clojure
  (is (cbor/content= ##NaN value))
  ```
  in place of `(is (not= value value))`. Pinned id unchanged.
* **T2 `semantic-bytecode-nested-nan-structural-sharing`**: keep as written.
* **T3 retired, replaced.** Delete `semantic-bytecode-scalar-metadata-collision`. Reason: the fixture cannot reach the guard because symbol metadata is hashed; no known input reaches the guard, so there is no honest collision fixture, and a `with-redefs` of `jing/segment-key` is not portable to ClojureDart. Replace with the fact that makes the guard unreachable, so the test pins the premise instead of a throw:
  ```clojure
  (deftest semantic-bytecode-symbol-metadata-is-address-significant
    (let [bc (vm/ast->semantic-bytecode
               (app (local 'vector)
                    [(lit (with-meta 'x {:meaning 1}))
                     (lit (with-meta 'x {:meaning 2}))]
                    false))
          literals (filter #(= :literal (second %)) (vals (:rows bc)))]
      (is (= 2 (count literals)))
      (is (= #{{:meaning 1} {:meaning 2}} (set (map #(meta (nth % 2)) literals))))))
  ```
  The guard's throw path stays proven by the **mutant gate** below, not by a fixture.
* **T4, T5** unchanged from round 1 (float-address-test cited; int-ops-test incl. `nan-comparisons-test` and `float-division-edge-test` on stack and register must go green).

### 4. Gates before sign-off (foreground, sequential)

1. kondo on vm.cljc, vm_test.cljc and the changed set: 0 errors, 0 warnings.
2. **Mutant gate**: temporarily replace `(not (same-content? prior row))` with `(not (= prior row))`, run `clojure -M:test -n yin.vm-test -v yin.vm-test/semantic-bytecode-nan-structural-sharing`, expect the collision error at the pinned id; restore bytes and assert byte equality (codex's procedure from fix-1 is exactly right, repeat it).
3. `clojure -M:test -n yin.vm-test` and T5 green.
4. The 35-namespace changed command and the whole `yin.vm.*` quick tier (head-board loopback error remains a sandbox denial, orchestrator reruns where allowed).
5. Node and Dart lanes on `yin.vm-test`: orchestrator. The cross-host claim for the leaf rule is already carried by `cbor_content_equality_test`; the lanes confirm the T1 pin and the new T3.

### Invariants, unchanged from round 1

A is the only code identity (the check now defers to the encoder's own equality instead of host `=`); tree always available; derive, don't persist; minimal diff (one leaf expression, two docstrings, one test swapped, one require).

RULING-READY
