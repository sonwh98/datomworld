Completed-GMT: 2026-10-15 18:26:00 GMT
Completed-Local: 2026-10-16 01:26:00 +07
Coding-Agent: claude (claude-fable-5-1)
Session-ID: pending (claude -p, no id exposed)
# Report: Node error in `float64-literal-beyond-2-53-loads-on-every-host` — TEST defect, not a src bug
Role: Architect
Brief: ./collab/1793360000000-architect-node-pin.prompt.md
Worktree: datomworld-srvm-p8nodefix @ 0ca2c0f9 (nothing edited but this report)

## Verdict

**Test defect. No src change. The merge to master is unaffected.**

The exception is not raised by the first testing block (the float64
pin). It is raised by the **second, cljs-only block**, whose `let`
evaluates `(linearize/project (corpus/tail (corpus/lit 1e20)))`
*outside* the `thrown-with-msg?` assertion. The block's own docstring
claims "projection accepts it as plain data, the load refuses it"; that
claim is false for the row lane, and the test was written against it.

## Root cause (reasoned from code; Node not run here)

1. `linearize/project` = `project-rows ∘ vm/ast->semantic-bytecode`
   (src/cljc/yin/vm/semantic/linearize.cljc:217-221).
2. `vm/ast->semantic-bytecode` mints every row's id with
   `(jing/segment-key body)` (src/cljc/yin/vm.cljc:1155), and the
   literal row's body contains the value. `segment-key` CBOR-encodes.
3. On JS the encoder's `number-wire` refuses a bare integral Number that
   is not a safe integer: `1e20` satisfies `host-integer?` (it is
   `number?`, `Number.isInteger`, not negative zero), so
   `cbor/float64?` is false and it hits the `:unsupported-value` refusal
   with detail "unsafe integral JavaScript number; pass a BigInt or
   float64" (src/cljc/dao/jing/cbor.cljc:1017-1021). That is exactly
   the message in collab/node-fix-node.log:63.
4. So the row lane refuses at **projection time**, before `load-image`
   is ever called. The throw escapes the `let`, hence "Uncaught
   exception, not in assertion".

The first block is sound on every host:

- `(cbor/float64 1.0E20)` is a `Float64` carrier on JS (cbor.cljc:512).
  The reader literal `1.0E20` is an integral Number on JS, but `float64`
  wraps it; it is not applied to an already-wrapped value twice in a
  lossy way (the carrier branch unwraps and rewraps).
- Row lane: `slot-value` for a `:data` slot is `strip-reader-positions`
  (vm.cljc:1146). `Float64` is a deftype, not a record/map/set/vector/
  sequential, so it returns through `:else x` untouched
  (vm.cljc:1045-1060). The row then holds the carrier; `segment-key`
  encodes it via `float-wire` (tag 27). Fine.
- Datom lane: `ast->datoms` emits `[e :yin/value v t m]` with `v` the
  carrier as-is (vm.cljc:748-749). `index-datoms`' `get-attr` returns
  `(nth d 2)` unchanged (vm.cljc:1817-1825). `datom-reader`'s `:check`
  runs `reject-host-value!` → `plain-data?`, which accepts
  `cbor/float64?` values (vm.cljc:909). `flatten-tree` emits
  `[:const d v]` with the carrier (linearize.cljc:101). `load-vector` →
  `jing/segment-key` → `float-wire`. Fine, and the address is the same
  BLAKE3 over the same tag-27 bytes as on the JVM, so the pinned
  `:segment/blake3-d034b2…` holds on JS too.
- `(= rows datoms)`: both vectors hold `Float64` carriers; the carrier
  implements `IEquiv` (cbor.cljc:360-366). Fine.

Candidates (a), (b), (c) from the brief are all ruled out: nothing in
the datoms lane unwraps the carrier, the corpus helpers are plain map
constructors (test/yin/vm/semantic/corpus.cljc:9-11, 29-31), and
`load-image` is just `code/load-vector` with the contract stamp. The
production datom ingestion path for Python float literals is not at
risk: the lowerer wraps floats as float64, and this report shows a
wrapped float64 flows through both lanes to one address on JS.

One observation worth keeping (not a defect): the two lanes refuse a
bare unsafe Number at different points. The row lane refuses at
projection (per-row `segment-key`); the datom lane refuses at load
(`plain-data?` accepts any `number?`, `flatten-tree` does not encode,
`load-vector` does). Both refuse before any address mints, which is
the invariant that matters. The corrected test wraps projection and
load together, so it pins the invariant without depending on which
step throws.

## Exact fix (test only)

File: test/yin/vm/semantic/load_paths_test.cljc, lines 28-34. Replace
the cljs block so that the projection call is inside the assertion,
and pin both lanes:

```clojure
  #?(:cljs
     (testing "a bare integral JavaScript Number past 2^53 is not content:
               neither lane mints an address for it"
       (let [ast (corpus/tail (corpus/lit 1e20))]
         (is (thrown-with-msg? js/Error #"unsafe integral JavaScript number"
               (load-image (:vector (linearize/project ast)))))
         (is (thrown-with-msg? js/Error #"unsafe integral JavaScript number"
               (load-image (:vector (linearize/project-datoms
                                      (vm/ast->datoms ast))))))))
     :default nil))
```

Why this is correct: `cljs.core/ExceptionInfo` extends `js/Error`, so
`thrown-with-msg? js/Error` catches `ex-info` refusals; the regex
matches both refusal details ("unsafe integral JavaScript number: its
value is already rounded" from `numeric-content-key`/`exact`, and
"…; pass a BigInt or float64" from `number-wire`), so the pin survives
whichever encoder entry point refuses first. The first testing block
(lines 19-27) is unchanged.

Also amend the commit message if it is still open: the row lane
refuses a bare unsafe Number at projection, not at load.

## Steps for codex

1. Apply the replacement above to
   test/yin/vm/semantic/load_paths_test.cljc:28-34. No other file.
2. JVM quick check (the cljs block is excluded on the JVM, so this
   guards the unchanged first block only):
   `clojure -M:test -n yin.vm.semantic.load-paths-test` (see
   docs build-n-test.md for the exact lane command).
3. Node: rerun the same slow invocation as before on jupiter,
   `DATOM_SLOW_TESTS=1` shadow compile test with ns-regexp
   `prelude-parity-test|semantic.load-paths-test`. Expected: 55 tests,
   0 failures, 0 errors; assertion count rises by 1 (two
   `thrown-with-msg?` where there was one).
4. Dart: no change expected (block is cljs-only); run the Dart lane
   once at landing per the test-tier cadence.
5. Commit under the existing branch as
   `test(yin.vm.semantic): the bare-Number refusal pin keeps projection inside the assertion, the row lane refuses at projection on JS`
   and report back for sign-off.

## Verification status

- Node: not run here (per brief). Diagnosis is from code, every step
  cited above by file:line; the log message is matched byte-for-byte
  to the `number-wire` refusal.
- JVM: not rerun; no JVM-visible change.

RULING-READY
