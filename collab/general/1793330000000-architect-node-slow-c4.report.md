Completed-GMT: 2026-10-16 07:50:00 GMT
Completed-Local: 2026-10-16 14:50:00 +07
Coding-Agent: claude (claude-fable-5-1)
Session-ID: pending
(Host clock read 2026-10-15 17:53 GMT at completion; the brief's clock is used above.)

# Architect report: Node slow-C4 failure in prelude-semantics-on-every-vm-test

Brief: ./collab/1793330000000-architect-node-slow-c4.prompt.md
Worktree: datomworld-srvm-p8final at origin/srvm-phase8 = 47249651. No tracked file edited; the throwaway probe `test/yin/vm/semantic/probe_node_unsafe_test.cljc` was run on the JVM and deleted.

## Ruling in one paragraph

The trigger is not the big integers. It is the three bare float literals `1.0E20`, `-1.0E20` and `1.0E300` in the `py/float-mod` rows of the fixture (`test/yang/python/antlr/prelude_parity_test.cljc:231-233`). The fixture deliberately exempts those rows from `ops/exact-literals` (lines 242-244), so on JS they reach the AST as bare JavaScript Numbers that are integral and above 2^53. `dao.jing.cbor` classifies such a Number as a host integer (cbor.cljc:461-468) and its encoder refuses it as "unsafe integral JavaScript number; pass a BigInt or float64" (cbor.cljc:1017-1022). Since 8c every VM except the AST walker loads through `code/load-vector`, which mints the vector's address with `jing/segment-key` (semantic/code.cljc:890), so the refusal fires at load on `:semantic`, `:stack` and `:register` and the AST walker, which never hashes, passes. This is classification (b): the cbor refusal is the ruled JS content model (a bare integral Number is an integer; a float keeps its kind only as the float64 carrier), exposed by the 8c change that made content addressing mandatory on every load path. It is a fixture defect, not a lowering bug: the production Python lowerer already wraps every float literal as float64 content (lower.cljc:333-341) and routes integer literals beyond 2^53 through `py/int-lit` hex strings (lower.cljc:318-324). No `src/` change. The fix is to the fixture (one of the ten C4 files, edited by this track under the 8f freeze rule) plus one portable non-slow pin in this seat's `test/yin/vm/semantic/load_paths_test.cljc` so the Node fast lane covers the shape.

## Evidence

1. Which literal. `prelude-semantics-on-every-vm` (lines 238-251) builds one `py/vconj` chain over `cases`. Every row goes through `ops/exact-literals`, which rewrites any integer literal of magnitude >= 2^53 to `(integer/parse "...")` (int_ops_test.cljc:107-117), so `9007199254740992` and friends arrive as strings and are safe. The only rows skipped are the `py/float-mod` ones, because on JS `1.0E20` reads as an integral Number and `exact-literals` would wrongly turn it into an exact integer. Those rows keep bare `1.0E20`, `-1.0E20`, `1.0E300`. `with-float64` (lines 105-114) only wraps `{:py/float x}` maps, so it does not touch them. The long vector in the log is the expected side; the actual on each VM is `[:thrown ...]`.

2. Where the refusal is raised. `semantic/load-ast` (semantic.cljc:322-326) -> `load-vector` -> `admit-image` -> `code/load-vector` (code.cljc:883-891) -> `(jing/segment-key v)` -> cbor encode -> `number-wire` (cbor.cljc:1014-1022): `host-integer?` is true for a Number with `js/Number.isInteger` (cbor.cljc:467-468), then `isSafeInteger` fails and `refuse :unsupported-value` throws with exactly the logged message. The `:stack` and `:register` runners (prelude_parity_test.cljc:61-69) reach the same place through `derive/stack-image` and `derive/register-image`, which take `(:vector (sr-linearize/project ast))`; the layout/segment path also segment-keys (layout.cljc:142). The literal row itself is emitted raw, `[:const d value]` (linearize.cljc:101), and the projection's `reject-host-value!` uses `vm/plain-data?`, which accepts any `number?` (vm.cljc:902-916), so projection succeeds and the refusal surfaces only at load, without a node id.

3. Why JVM and Dart pass. On the JVM `1.0E20` reads as a Double and on Dart as a double; both are `float64?` (cbor.cljc:471-479) and encode through `float-wire`. Only JS has one number type, which is why Jing's JS model says: a bare integral Number is an integer, a float is the `Float64` carrier (`cbor/float64`, cbor.cljc:505-512; `data/float64`, data.cljc:443-450).

4. Pre-phase-8 master b19dd97a, read from code. `linearize/ast-loader` lowered to a batch and called `vm-load-program` -> `load-image`, which minted or checked an address only `when claimed` by a `:yin.code/hash`, and "a batch carrying no hash, every `lower` output, records no alias". The `:stack` and `:register` runners used `dl/lower-stack` and `rc/lower-register` over the resolved AST, which never hashed literals through cbor (debruijn-code's scalar encoder handles them on its own, with its own cljs-only unsafe-integer rejection pinned at debruijn_code_test.cljc:250-253 for `:uint` operands, not `:const` values). So on pre-8 Node this test ran the bare literals and, per the AST walker still passing today, computed the right values. The change that exposed it is 8c (19528b7c, merged 31d403e7): "the Python harness loads through the semantic-register kernel" and `load-vector` always segment-keys. The 8c gates were quick tier only; the Node slow lane is the first run since.

5. The 8d fix 9b4b250a is the mirror image: it moved BigInt literals out of the AST path because `plain-data?` accepts float64 but not the JS/Dart BigInt carriers. Here the AST path accepts the value and the content layer refuses it; the remedy is the same family (give the literal its canonical carrier).

## Fix

### F1. Fixture rows (C4 file, this track under the 8f freeze; notify yang.python via collab)

`test/yang/python/antlr/prelude_parity_test.cljc`

Replace lines 231-233

```clojure
    [(py/float-mod 1.0E20 3.0) 1.0]
    [(py/float-mod -1.0E20 3.0) 2.0]
    [(py/float-mod 1.0E300 7.0) 1.0]
```

with

```clojure
    [(py/mod {:py/float 1.0E20} {:py/float 3.0}) {:py/float 1.0}]
    [(py/mod {:py/float -1.0E20} {:py/float 3.0}) {:py/float 2.0}]
    [(py/mod {:py/float 1.0E300} {:py/float 7.0}) {:py/float 1.0}]
```

and in `prelude-semantics-on-every-vm` (lines 240-246) drop the `py/float-mod` special case so every row is `(ops/exact-literals (with-float64 f))`:

```clojure
  (let [form (reduce (fn [acc [f _]]
                       (list 'py/vconj acc (ops/exact-literals (with-float64 f))))
                     []
                     cases)
```

Why this shape and not wrapping the `py/float-mod` arguments: `py/float-mod` (prelude.cljc:1264-1274) does host arithmetic on raw doubles, and the prelude's rule is that the float64 carrier never meets host arithmetic (prelude.cljc:1791). `py/mod` on two floats (prelude.cljc:1353-1359) unwraps through `py/division-operands` -> `py/as-float`, calls the same `py/float-mod`, and re-wraps with `py/float`. The large-quotient property the rows exercise ("no quotient is formed", prelude.cljc:1265-1266) is reached unchanged. This is also the shape the neighbouring rows already use (`py/mod {:py/float 5.9} {:py/float 1.1}`, lines 189-191), which pass on Node today with carrier payloads and carrier expectations.

JS reasoning for the fixed rows: `with-float64` turns each payload into a `Float64` carrier; `plain-data?` accepts it (vm.cljc:904-905, 909); `exact-literals` skips it (not `integer?`); cbor encodes it through `float-wire`; the address mints. At run time the result is `{:py/float <carrier 1.0>}`, the expectation is the same carrier after `with-float64`, `canon-ints` leaves carriers alone on JS (not `number?`), and `=` on carriers is `IEquiv` by value, which the `{:py/float #dao.jing/float64 -4}` row in this very test already relies on in the Node log.

JVM verification (probe, deleted): the three new rows evaluate to `[{:py/float "1"} {:py/float "2"} {:py/float "1"}]` after `canon-ints` on `:ast-walker`, `:semantic`, `:stack` and `:register`, equal to the expectation; the old rows still pass on the JVM, confirming the host-type explanation.

### F2. Portable non-slow pin in this seat (so the Node fast lane catches the shape)

`test/yin/vm/semantic/load_paths_test.cljc`, new deftest, no `^:slow`, no guard:

```clojure
(deftest float64-literal-beyond-2-53-loads-on-every-host
  (testing "a float literal past 2^53 is float64 content on every host:
            both projection lanes load it to one image at one address"
    (let [ast (corpus/tail (corpus/lit (cbor/float64 1.0E20)))
          rows (load-image (:vector (linearize/project ast)))
          datoms (load-image (:vector (linearize/project-datoms
                                        (vm/ast->datoms ast))))]
      (is (= rows datoms))
      (is (= :segment/blake3-d034b2a44fee49a5f13bf6d2a6d186cb265f567d932d144939d3587461ed46b6
             (:address rows)))))
  #?(:cljs
     (testing "a bare integral JavaScript Number past 2^53 is not content:
               projection accepts it as plain data, the load refuses it"
       (let [v (:vector (linearize/project (corpus/tail (corpus/lit 1e20))))]
         (is (thrown-with-msg? js/Error #"unsafe integral JavaScript number"
                               (load-image v)))))
     :default nil))
```

Notes for the implementer: the pinned address was minted on the JVM from `(u/mark-tails (u/sexp->uast (cbor/float64 1.0E20)))`; `corpus/tail` of `corpus/lit` should give the same vector (a root literal marked tail). Verify on the JVM first and, if the vector differs, re-pin from the JVM value. The address is a cross-host determinism golden of the same kind as `corpus/goldens`; a mismatch on Node or Dart is itself a finding, not a reason to loosen the assertion. The cljs clause uses the `#?(:cljs ... :default nil)` pattern of debruijn_code_test.cljc:250-253. Requires adding `[dao.jing.cbor :as cbor]` to the ns.

### Not changed, recorded

- `src/yang/**` untouched (production lowering already correct). `int_ops_test.cljc` untouched; its `exact-literals` docstring sentence "callers skip the float-only rows" becomes stale after F1. Optional one-line trim, C4 file, not required.
- Known-gaps ledger row (yin.vm seat, optional follow-up, not in this fix): `linearize/project`'s literal check (`reject-host-value!` via `plain-data?`) accepts a bare unsafe integral JS Number that only cbor refuses later at load, so the error names no node. A projection-time refusal on JS with the node id would be friendlier; it belongs to a later slice, not a hot fix.

## Steps for codex (gpt-6.1-sol, workspace-write), in order

1. Branch from origin/srvm-phase8 (47249651), e.g. `srvm-node-slow-c4-floats`.
2. Apply F1 to `test/yang/python/antlr/prelude_parity_test.cljc` exactly as above.
3. Apply F2 to `test/yin/vm/semantic/load_paths_test.cljc`; run `clojure -M:test -n yin.vm.semantic.load-paths-test` and confirm the address assertion holds on the JVM (re-pin from the JVM value only if `corpus/tail`+`corpus/lit` yields a different vector than the probe's AST; say so in the commit message if re-pinned).
4. JVM check of the fixture, foreground: `DATOM_SLOW_TESTS=1 clojure -M:test -n yang.python.antlr.prelude-parity-test` (the deftest is `^:slow`; the guard must be enabled). Expect green on all four VMs.
5. Node, foreground, this is the lane that found it: `bb test:sub:cljs yang.python --slow` (jupiter or a worktree after `npm ci`). Expect `prelude-semantics-on-every-vm-test` green on `:semantic`, `:stack`, `:register`; the 66 dht-import-test fixture failures remain the known red. Then the quick lanes for the pin: `bb test:sub:cljs yin.vm` and `bb test:sub:cljd yin.vm`.
6. Commit as `test(yang.python): the float-mod parity rows carry float64 payloads, so their addresses mint on JS (phase 8 Node slow fix)` and `test(yin.vm.semantic): a float64 literal past 2^53 loads at one address on every host; a bare unsafe JS Number is refused at load`. Two commits or one, implementer's choice. Do not touch `src/`.
7. Non-author review (Fable) on the diff; Architect sign-off before commit to the integration branch per orchestrator.md. Leave a note in collab for the yang.python orchestrator that a C4 file was edited under the 8f freeze rule (three fixture rows, one reducer branch).

## Unverified

- Node could not be run here (`npm ci` needs an approval this session cannot grant). The JS behaviour above is reasoned from the cited cbor, data, prelude and vm source lines and from the fact that the `:ast-walker` runner passes on Node in the same log, so the arithmetic on those values is already right there and only the hashing fails. Step 5 is the verification of record.

RULING-READY
