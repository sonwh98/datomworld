Created-Local: 2026-10-14 +07
Coding-Agent: codex
Branch: srvm-phase7m
Base: e6337294

# Phase-7 merged lint and scanner repair

No commit made. Only our rows implementation, its regression tests, and the :c4/:py scanner fixture changed; no C4/admission production file changed. A standalone mechanical golden reproduction script is included beside this report.

## Address policy and each fixed site

The architectural sweep's `flags-equality-validation?` walks forms and rejects an equality or inequality whose operand directly calls a default Jing minting var. The baseline JVM run found **two flagged forms**, `decode*` and `check-install!`, with **four comparisons**; there was no third flagged production form in this checkout (15 tests / 5,098 assertions, 2 failures).

1. `decode*`, carrier row hash: use `jing/segment-matches? id (vec body)`. Carrier row IDs remain BLAKE3 by construction. A parsed registered address naming a different algorithm is explicitly refused as `:yin.k/profile-mismatch`, kind `:row-algorithm`, before hash verification and canonical numbering. Malformed addresses and BLAKE3 digest mismatches retain the existing `:yin.k/hash-mismatch` / `:row` refusal. Minting, ordering and canonical encoding are unchanged.
2. `decode*`, task slot 11 carried code: use `jing/segment-matches? address v`. This is a STORE content address, so a SHA-256 address must verify under SHA-256; default BLAKE3 minting is not a validity oracle. The existing `:code` hash refusal and subsequent code well-formedness validation remain.
3. `check-install!`, manifest `[:yin.module/derivations :yin.semantic/code]`: verify the supplied derivation record under the algorithm named by its manifest address.
4. `check-install!`, derivation `:yin.ledger/output`: verify the delivered image value under that output address's algorithm. Tree/input equality and all other install checks remain unchanged.

Regression tests exercise an honestly SHA-256-minted carrier root (digest matches, but profile explicitly refuses it), honest SHA-256 carried code plus corrupted content, and installs with independently SHA-256/BLAKE3-minted image and record addresses (three combinations). Honest installs lower and attach; bad records refuse before attachment. Existing root pins are retained and checked by the rows suite.

## Scanner diagnosis and reproduction

`git log --oneline -- test/yin/vm/semantic_register/scanner_goldens.cljc` identifies `58dc0964` as the fixture introduction, with the fixture header identifying historical master `993d5411`. `git log -p 58dc0964..HEAD -- src/cljc/yang/python/antlr/prelude.cljc` shows:

- `c63ba6d6` (C4 I2): attribute lookup helpers, package/submodule imports, from-imports, star imports, and import failures.
- `544e99e1` (C4 I3): indexed `__all__` access (`star-item`) and persistent REPL `run-session`.

The old and new scanner answers agree exactly on the current module AST. These source changes explain the fixture drift; the scanner, rows and derive production logic were not changed to obtain agreement. Admission's pure namespace relocation is not itself the source of these prelude additions.

Only `[:c4 :py]` was regenerated, using the same `old-answer` computation as `scan-test/print-goldens`. Reproduce mechanically:

```sh
clojure -Sdeps '{:aliases {:p7m {:extra-paths ["test"]}}}' -M:p7m collab/1792570000000-engineer-p7m-scan-golden.clj
```

The script asserts old/new agreement, prints `clojure.data/diff`, and replaces only the textual :py map. Running it again produced an identical fixture (`diff -u` exit 0). The exact fixture diff is appended below; the full structural diff and generated answer are retained in `target/p7m-lint-scan/scan-diff.edn` and `py-golden.edn`.

The recomputed before/after summary (and an assertion that every other group is equal) is:

```clojure
{:definitions [432 444],
 :applications [3682 3831],
 :join-obligations [1771 1842],
 :definitions-added
 #{[import-sub false]
   [run-session false]
   [import-from false]
   [no-module false]
   [star-copy-public false]
   [dict-ref false]
   [star-item false]
   [star-name false]
   [import-star false]
   [import-fail false]
   [star-copy-all false]
   [lookup-attr false]},
 :other-groups-unchanged true,
 :join-status [:ok :ok],
 :occurrences [3347 3469],
 :free-names-removed #{},
 :store-keys-added
 #{import-star
   star-copy-all
   no-module
   import-fail
   run-session
   dict-ref
   lookup-attr
   star-name
   star-copy-public
   import-sub
   star-item
   import-from},
 :other-requirements-unchanged true,
 :free-names-added
 #{star-copy-all
   no-module
   dict-ref
   getattr
   lookup-attr
   star-name
   star-copy-public
   star-item}}
```

## Verification

All test/build processes were run in the foreground, on JVM only, without background jobs. Logs are in `target/p7m-lint-scan/`.

- Required focused command: `clojure -M:test -e :slow -n dao.jing.hash-registry-contract-test -n yin.vm.semantic-register.scan-test -n yin.vm.ucf.rows-test -n yin.vm.ucf.rows-custody-test -n yin.vm.ucf.rows-checkpoint-test -n yin.vm.semantic-register.layout-test -n yin.vm.semantic-register.safepoint-harness-test`: **121 tests, 10,056 assertions, 0 failures, 0 errors**.
- `clojure -M:test -n yin.vm.linker.dht-end-to-end-test`: **6 tests, 77 assertions, 0 failures, 0 errors**, including both slow tests. Thus the two tests reported red on the premerge Dart lane pass here on JVM. `08a3a722` is included in the merged master history. First load required missing generated Python parser classes; `bb gen:python-antlr` completed successfully after sandboxed network resolution failed.
- Every test namespace under `test/yin/vm/ucf/` (**44 namespaces**, no slow exclusion): **730 tests, 15,328 assertions, 0 failures, 3 initial errors**. All three errors were missing Node dependencies in the required stage-E process peers. After `npm ci`, rerunning only those three tests gave **3 tests, 912 assertions, 0 failures, 0 errors**. No UCF red remains. The test selection is recorded in `ucf-namespaces.txt`.
- Root `yin.vm.ucf-test` additionally: **23 tests, 124 assertions, 0 failures, 0 errors**. Thus 753 distinct UCF tests were exercised, including slow tests. No direct Node or Dart test lane was run; JVM stage-E tests themselves compile and launch their required Node peers.
- Final `clj -M:kondo --lint` on all four changed Clojure source/test/fixture files plus the reproduction script: **0 errors, 0 warnings**.
- Whole Jing family (**14 nested test namespaces plus `dao.jing-test`, 15 total**, no slow exclusion): **233 tests, 10,382 assertions, 0 failures, 1 initial error**. The only error was the real-loopback socket test: the sandboxed bind produced no local port. Rerunning `clojure -M:test -n dao.jing.dht.socket-test` with permission to bind loopback outside the sandbox gave **1 test, 2 assertions, 0 failures, 0 errors**. No Jing red remains. The full namespace list is recorded in `jing-namespaces.txt`.
- `cbor-conformance-test/no-source-file-that-names-the-resource-writes-files` **passes here**. The conditional master-failure exception is therefore unused; no master baseline rerun was necessary.
- Final regeneration script (including an assertion that the regenerated value equals the original fixture with only `[:c4 :py]` replaced): succeeds. `diff -u` and `git diff --quiet --no-index` against a `cp` backup both exit 0. The separate whole-fixture equality check also passes for every other group.

## Mutation evidence

Run: `python3 target/p7m-lint-scan/mutants.py`. Each case starts from a `cp` backup of the fixed rows source, writes a saved unified mutant diff, runs the relevant JVM test var in the foreground, expects exit 1, and restores with `cp` in `finally`. After **each** case, `diff -u` and `git diff --quiet --no-index` compare source to the fixed backup, and a byte-for-byte comparison of the complete `git diff` to its pre-mutation value proves that all intended tracked changes are intact. All six mutants were killed and exactly restored.

| Mutant | Witness | Failures | Errors | Exit |
|---|---|---:|---:|---:|
| Row hash reverted to equality/default minting | Architectural source sweep | 1 | 0 | 1 |
| Carried code reverted to equality/default minting | Architectural source sweep | 1 | 0 | 1 |
| Manifest record reverted to equality/default minting | Architectural source sweep | 1 | 0 | 1 |
| Install image reverted to equality/default minting | Architectural source sweep | 1 | 0 | 1 |
| Non-BLAKE3 carrier accepted | Carrier algorithm regression | 2 | 0 | 1 |
| SHA-256 install image refused | Install algorithm regression | 4 | 2 | 1 |

The accepted-carrier mutant removes the algorithm refusal **and** bypasses canonical root renumbering, so the honestly SHA-256-minted root actually reaches successful decode rather than merely receiving a later noncanonical refusal. The SHA-install mutant forces BLAKE3 on the image address while retaining algorithm-aware matching, directly isolating the forbidden rejection. Its two additional errors are the positive test's `vm/blocked?` call on the induced refusal map; four assertions independently fail as well. These errors occur only under the intentionally defective mutant, not the restored code.

Saved per-case diffs, logs, test counters and the restoration summary are under `target/p7m-lint-scan/mutant-*` and `mutants-summary.log`.

## Limits and readiness

The requested JVM verification is complete; no production/test red remains. Node and Dart lanes were not run, as requested. Fable's external independent review is the sole unfinished step and requires the explicit disclosure authorization described below. No commit was created.


## Independent review

**Not reviewed by Fable yet.** Automatic approval review rejected invoking the external Claude/Fable service because it would export private repository code and project content without explicit user authorization for the payload and destination. The invocation did not run. Local verification continues independently; explicit approval is required only for that external review. No Codex self-review is represented as independent sign-off.

## Exact scanner fixture diff

```diff
diff --git a/test/yin/vm/semantic_register/scanner_goldens.cljc b/test/yin/vm/semantic_register/scanner_goldens.cljc
index d342404e..b6cab3e7 100644
--- a/test/yin/vm/semantic_register/scanner_goldens.cljc
+++ b/test/yin/vm/semantic_register/scanner_goldens.cljc
@@ -1,5 +1,7 @@
 (ns yin.vm.semantic-register.scanner-goldens
-  "Historical v3 scanner answers generated on JVM from master 993d5411.")
+  "Historical v3 scanner answers generated on JVM from master 993d5411.
+   Only :c4/:py was regenerated after C4 I2/I3 on e6337294; reproduce with
+   collab/1792570000000-engineer-p7m-scan-golden.clj.")
 
 
 (def scanner-goldens
@@ -614,6 +616,7 @@
         py.rt/sys
         py.b/float
         py.b/dict-keys
+        star-copy-all
         py.b/print
         py.b/KeyboardInterrupt
         py.b/dict-items
@@ -680,6 +683,7 @@
         gen-fail
         integer/pow
         gen-exit
+        no-module
         seq-contains?
         py.b/isinstance
         mod-p
@@ -747,6 +751,7 @@
         strip-space
         not
         attr-error
+        dict-ref
         crossing-base
         any-of
         range-elem
@@ -762,6 +767,7 @@
         py.b/next
         make-instance
         print
+        getattr
         globals-fn
         data/str-length
         /
@@ -774,6 +780,7 @@
         float-parts
         py.b/UnboundLocalError
         data/str->code-points
+        lookup-attr
         py.b/tuple
         py.b/KeyError
         text-repeat
@@ -799,12 +806,14 @@
         round-conv
         str-conv
         eq-items
+        star-name
         str?
         mul
         py.b/Exception
         integer/bit-not
         py.b/gen-send
         snapshot
+        star-copy-public
         py.b/object
         float-parts-down
         py.b/MemoryError
@@ -833,6 +842,7 @@
         class-lookup
         py.b/hex
         fpow
+        star-item
         all-in?
         round-integer
         slice-of
@@ -1205,7 +1215,7 @@
        [str true]
        [cell? true]
        [cell/get true]
-       [attr-error true]
+       [lookup-attr true]
        [cell? true]
        [cell/get true]
        [attr-error true]
@@ -2006,7 +2016,30 @@
        [dict-del-quiet true]
        [py.rt/modules true]
        [module-new true]
+       [raise-new true]
+       [py.b/ImportError true]
+       [str true]
+       [data/str-concat true]
+       [data/str-concat true]
+       [get true]
        [modules-ref true]
+       [modules-ref true]
+       [cell/get true]
+       [lookup-attr true]
+       [get true]
+       [str? true]
+       [getattr true]
+       [str? true]
+       [data/str->code-points true]
+       [get true]
+       [kind true]
+       [star-item true]
+       [get true]
+       [module-dict true]
+       [raise-new true]
+       [py.b/ImportError true]
+       [cell/set! true]
+       [py.rt/out true]
        [cell/set! true]
        [py.rt/out true]
        [cell/set! true]
@@ -2189,6 +2222,8 @@
        [get true]
        [get true]
        [get true]
+       [= true]
+       [attr-error true]
        [get true]
        [as-float true]
        [num true]
@@ -2708,14 +2743,59 @@
        [not true]
        [= true]
        [= true]
-       [raise-new true]
-       [py.b/ImportError true]
+       [no-module true]
+       [module-run true]
+       [get true]
+       [str true]
+       [not true]
+       [= true]
+       [= true]
+       [no-module true]
+       [module-run true]
+       [get true]
        [str true]
+       [get true]
+       [cell/get true]
+       [get true]
+       [get true]
+       [key true]
+       [not true]
+       [= true]
+       [lookup-attr true]
+       [type-error true]
+       [str? true]
+       [type-text true]
        [data/str-concat true]
+       [= true]
        [data/str-concat true]
        [get true]
-       [module-run true]
+       [data/str-concat true]
+       [type-text true]
+       [< true]
+       [data/count true]
+       [str true]
+       [data/code-points->str true]
+       [vconj true]
+       [get true]
+       [= true]
+       [get true]
+       [get true]
+       [cell/get true]
+       [= true]
        [get true]
+       [get true]
+       [= true]
+       [range-at true]
+       [= true]
+       [type-error true]
+       [type-error true]
+       [type-text true]
+       [= true]
+       [star-name true]
+       [= true]
+       [star-name true]
+       [dict-ref true]
+       [str true]
        [dict-new true]
        [cell/set! true]
        [py.rt/ctx true]
@@ -2724,6 +2804,11 @@
        [cell/set! true]
        [py.rt/ctx true]
        [ctx true]
+       [modules-ref true]
+       [str true]
+       [cell/set! true]
+       [py.rt/ctx true]
+       [ctx true]
        [modules-put! true]
        [str true]
        [py.rt/builtins-mod true]
@@ -3156,17 +3241,14 @@
        [= true]
        [method true]
        [py.b/list-append true]
-       [attr-error true]
        [= true]
        [= true]
        [str true]
        [get true]
-       [attr-error true]
        [= true]
        [= true]
        [method true]
        [py.b/set-add true]
-       [attr-error true]
        [= true]
        [= true]
        [method true]
@@ -3180,7 +3262,6 @@
        [= true]
        [method true]
        [py.b/dict-get true]
-       [attr-error true]
        [= true]
        [gen-attr true]
        [= true]
@@ -3190,8 +3271,6 @@
        [= true]
        [method true]
        [py.b/gen-iter true]
-       [attr-error true]
-       [attr-error true]
        [= true]
        [= true]
        [cell/set! true]
@@ -3566,6 +3645,28 @@
        [get true]
        [get true]
        [modules-put! true]
+       [setattr true]
+       [get true]
+       [= true]
+       [get true]
+       [get true]
+       [= true]
+       [str? true]
+       [star-copy-all true]
+       [+ true]
+       [dict-set true]
+       [getattr true]
+       [get true]
+       [star-copy-public true]
+       [+ true]
+       [= true]
+       [data/str-index-of true]
+       [get true]
+       [dict-set true]
+       [get true]
+       [= true]
+       [cell/get true]
+       [star-copy-all true]
        [globals-fn true]
        [assoc true]
        [assoc true]
@@ -3575,6 +3676,9 @@
        [snapshot-exc true]
        [module-run true]
        [str true]
+       [= true]
+       [module-new true]
+       [str true]
        [arg true]
        [next true]
        [arg true]
@@ -3736,7 +3840,6 @@
        [= true]
        [str true]
        [get true]
-       [attr-error true]
        [str? true]
        [data/str-length true]
        [data/count true]
@@ -3864,7 +3967,19 @@
        [integer/bit-and true]
        [data/float-value true]
        [try true]
+       [modules-ref true]
+       [str true]
+       [data/str-concat true]
+       [get true]
+       [data/str-concat true]
+       [get true]
+       [star-copy-public true]
+       [get true]
+       [get true]
        [try true]
+       [module-dict true]
+       [modules-put! true]
+       [str true]
        [restore! true]
        [set-handlers! true]
        [frame true]
@@ -3942,10 +4057,8 @@
        [get true]
        [dict-new true]
        [= true]
-       [attr-error true]
        [bind true]
        [= true]
-       [attr-error true]
        [get true]
        [get true]
        [= true]
@@ -4023,12 +4136,24 @@
        [< true]
        [globals-fn true]
        [modules-del! true]
+       [not true]
+       [= true]
+       [raise-new true]
+       [py.b/ImportError true]
+       [str true]
+       [data/str-concat true]
+       [data/str-concat true]
+       [get true]
+       [data/str-concat true]
+       [data/str-concat true]
+       [get true]
        [assoc true]
        [assoc true]
        [cell/get true]
        [py.rt/out true]
        [= true]
        [snapshot-exc true]
+       [try true]
        [unwind-to true]
        [frame-depth true]
        [get true]
@@ -4102,6 +4227,13 @@
        [float-decimal true]
        [= true]
        [raise true]
+       [assoc true]
+       [assoc true]
+       [cell/get true]
+       [py.rt/out true]
+       [= true]
+       [snapshot-exc true]
+       [globals-fn true]
        [restore! true]
        [fill-kwdefaults true]
        [get true]
@@ -4391,6 +4523,7 @@
        [method false]
        [bind false]
        [attr-error false]
+       [lookup-attr false]
        [getattr false]
        [setattr false]
        [as-float false]
@@ -4586,9 +4719,20 @@
        [modules-put! false]
        [modules-del! false]
        [module-run false]
+       [no-module false]
        [import false]
+       [import-sub false]
+       [dict-ref false]
+       [import-from false]
+       [star-name false]
+       [star-item false]
+       [star-copy-all false]
+       [star-copy-public false]
+       [import-star false]
+       [import-fail false]
        [run-module false]
        [run-main false]
+       [run-session false]
        [py.rt/state false]
        [init! false]
        [py.rt/ctx true]
@@ -4657,7 +4801,7 @@
        [py.b/object true]
        [py.rt/out true]
        [py.rt/limit true]],
-      :application-count 3682,
+      :application-count 3831,
       :requirements
       {:store-keys
        #{rt-limit
@@ -4717,6 +4861,7 @@
          iadd
          decimal-exponent
          int-lit
+         import-star
          dict-items
          numeric?
          range-len
@@ -4761,6 +4906,7 @@
          py.rt/sys
          py.b/float
          py.b/dict-keys
+         star-copy-all
          yield
          py.b/print
          py.b/KeyboardInterrupt
@@ -4823,8 +4969,10 @@
          extend
          sub
          gen-exit
+         no-module
          seq-contains?
          py.b/isinstance
+         import-fail
          mod-p
          float-text-error
          make-function
@@ -4844,6 +4992,7 @@
          run-module
          local-get
          pick
+         run-session
          sequence?
          frame-depth
          call-ec
@@ -4895,6 +5044,7 @@
          slice-positions
          strip-space
          attr-error
+         dict-ref
          crossing-base
          any-of
          lshift
@@ -4922,6 +5072,7 @@
          float-parts
          py.b/UnboundLocalError
          global-or
+         lookup-attr
          py.b/tuple
          imul
          py.b/KeyError
@@ -4948,11 +5099,13 @@
          str-conv
          set-from
          eq-items
+         star-name
          str?
          mul
          py.b/Exception
          py.b/gen-send
          snapshot
+         star-copy-public
          py.b/object
          float-parts-down
          py.b/MemoryError
@@ -4970,6 +5123,7 @@
          collect
          run-main
          pad
+         import-sub
          py.b/dict-get
          py.b/NotImplementedError
          global-set
@@ -4984,6 +5138,7 @@
          class-lookup
          py.b/hex
          fpow
+         star-item
          all-in?
          round-integer
          slice-of
@@ -5010,6 +5165,7 @@
          set-fill-at
          hash-float
          snapshot-all
+         import-from
          global-get
          float-divmod
          lookup-pair
@@ -5591,6 +5747,15 @@
         [get true]
         [cell/get true]
         [cell/get true]
+        [data/str-concat true]
+        [data/str-concat true]
+        [get true]
+        [cell/get true]
+        [get true]
+        [data/str->code-points true]
+        [get true]
+        [get true]
+        [cell/set! true]
         [cell/set! true]
         [cell/set! true]
         [= true]
@@ -5699,6 +5864,7 @@
         [get true]
         [get true]
         [get true]
+        [= true]
         [get true]
         [= true]
         [cell/set! true]
@@ -6058,10 +6224,38 @@
         [not true]
         [= true]
         [= true]
+        [get true]
+        [not true]
+        [= true]
+        [= true]
+        [get true]
+        [get true]
+        [cell/get true]
+        [get true]
+        [get true]
+        [not true]
+        [= true]
+        [data/str-concat true]
+        [= true]
         [data/str-concat true]
+        [get true]
         [data/str-concat true]
+        [< true]
+        [data/count true]
+        [data/code-points->str true]
+        [get true]
+        [= true]
+        [get true]
+        [get true]
+        [cell/get true]
+        [= true]
         [get true]
         [get true]
+        [= true]
+        [= true]
+        [= true]
+        [= true]
+        [cell/set! true]
         [cell/set! true]
         [cell/set! true]
         [data/float-value true]
@@ -6435,10 +6629,25 @@
         [= true]
         [get true]
         [get true]
+        [get true]
+        [= true]
+        [get true]
+        [get true]
+        [= true]
+        [+ true]
+        [get true]
+        [+ true]
+        [= true]
+        [data/str-index-of true]
+        [get true]
+        [get true]
+        [= true]
+        [cell/get true]
         [assoc true]
         [assoc true]
         [cell/get true]
         [= true]
+        [= true]
         [get true]
         [cell/get true]
         [get true]
@@ -6567,6 +6776,12 @@
         [= true]
         [integer/bit-and true]
         [data/float-value true]
+        [data/str-concat true]
+        [get true]
+        [data/str-concat true]
+        [get true]
+        [get true]
+        [get true]
         [get true]
         [cell/set! true]
         [get true]
@@ -6673,6 +6888,14 @@
         [integer/bit-and true]
         [integer/add true]
         [< true]
+        [not true]
+        [= true]
+        [data/str-concat true]
+        [data/str-concat true]
+        [get true]
+        [data/str-concat true]
+        [data/str-concat true]
+        [get true]
         [assoc true]
         [assoc true]
         [cell/get true]
@@ -6717,6 +6940,10 @@
         [data/float-value true]
         [data/float-value true]
         [= true]
+        [assoc true]
+        [assoc true]
+        [cell/get true]
+        [= true]
         [get true]
         [get true]
         [< true]
```
