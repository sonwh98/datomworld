Created-GMT: 2026-10-05 22:10:00 GMT
Coding-Agent: claude (opus-5-5)
# Findings: why every Python test costs seconds, and what removes it

Scope: the per-run floor of the Python tests (prelude + program on four
VMs), measured on JVM, Node and Dart; one test-side prototype, left
UNCOMMITTED in the working tree. No product file changed. No git writes.
Everything ran in the foreground, per namespace or per var. The full suite
was never run.
Base: this worktree at baa3796a (merge-base with master; master has since
landed S0 and the Python tagging docs, not measured here).

Machine load was high (load average up to 29 from other sessions): wall
times wander by 20-50% between runs. Where it matters I give thread-CPU
time and deterministic call counts as well as wall time.

## 0. Headline

1. The prelude is NOT re-lowered per test. `prelude/uast` is a top-level
   def (built once at namespace load). Per test, `mark-tails` over the
   whole tree takes ~1 ms and `ast->datoms` 1-3 ms (JVM). About 99% of the
   floor is compiling and admitting the whole (prelude + program) image on
   each VM. That work is not separable on the test side: every VM compiles
   one whole-program image, so a compiled prelude cannot be cached across
   tests without the product's linked prelude.
2. On every host the register VM is the biggest single cost: 60% of the
   JVM per-run floor and 83% of Dart's.
3. Dart is ~7.5x the JVM for one reason that applies to every run: two
   product functions, one per point below. Together they are about 7-8 s of
   Dart's ~10.8 s per-run floor:
   - `dao.jing/sha256` on :cljd is a hand-rolled SHA-256 over lazy
     `(partition 64 ...)` seqs. It hashes the 2.2 MB register-image
     encoding in 3.7-5.0 s. `package:crypto` (already a direct pubspec
     dependency) gives the same digest in 27-29 ms (checked equal).
   - `body-liveness` allocates a vector as long as the WHOLE image
     (11,304 slots) for each of 835 bodies. That is quadratic in prelude
     size. It runs twice per register run (`lower-register`, then the
     `live-exact-rule` admission check), at ~1.4 s of allocation per pass
     on Dart.
   None of the Dart "outliers" has a test-specific pathology. Each is
   N runs times the Dart floor, with no deep recursion, exception
   unwinding or BigInt hot spot.
4. The test-side prototype has one shared resolve per run, plus a memoized
   `derived-ast` in safepoint-test. It saves about 5-8% of the fast form
   and 6-18% of multi-run slow tests, with every assertion unchanged.
   Worth landing, but small. The big wins are three small product fixes
   outside `engine`/`ucf`, and later the linked prelude.

## 1. One trivial program end to end (JVM): e2e `print(1)`

Harness: target/prof/phase.clj. It runs the same stages as
`e2e-test/run-python`, called directly so each phase can be timed. The
walker is timed as create + load + run rather than through the observer
session. 8 runs: the cold first run, then the mean of the last 5 warm runs, ms.
Prelude: 10,544 AST nodes, 26,636 datoms; print(1)'s own body is 19 nodes.

| phase | cold | warm mean | kind |
|---|---|---|---|
| parse | 48.2 | 3.2 | program |
| lower body only | 4.0 | 0.3 | program |
| lower-packet (then prelude, mark-tails whole tree) | 2.0 | 0.9 | whole tree, trivial |
| naive ast->datoms | 2.4 | 1.8 | whole tree, trivial |
| naive walker create+load+run | 172.5 | 52.8 | load: whole tree; run: prelude defs + program |
| naive semantic create+load(linearize)+run | 325.4 | 166.4 | load: whole tree |
| naive stack resolve / lower-stack / create-vm / run | 157 / 93 / 128 / 31 | 83 / 74 / 65 / 4 | compile+admit: whole tree |
| naive register resolve / lower-register / create-vm / run | 109 / 349 / 472 / 69 | 83 / 251 / 427 / 14 | compile+admit: whole tree |
| hooks encoder/project | 231.1 | 82.4 | whole tree |
| hooks derive-envelope | 1.5 | 0.3 | (lazy; cost lands in the next row) |
| hooks semantic-bytecode->ast | 263.6 | 119.3 | whole tree |
| hooks: the same four VMs again | ~1,100 | ~1,060 | whole tree |
| TOTAL | 3,902 | 2,657 | |

Per-test constant (a function of the prelude, ~99%): every compile, load
and admission row, plus hooks project/derive/bytecode->ast. The prelude
dominates the tree, so these are flat for any small program.
Program-proportional: parse, lowering the body, and the `run` rows. The
run rows also include executing the ~800 prelude definitions (walker
run 37 ms).
Isolated, warm: `mark-tails prelude` 1 ms, `sexp->uast` of every prelude
function 1-2 ms, `ast->datoms prelude` 1 ms.

The same program shape on all three hosts (portable profiler, now at
target/prof/zz_phase_prof_test.cljc; it lived briefly under test/ to be
compiled for Node and Dart, then was removed): prelude-parity's
`run-with-prelude` shape, `print(1)` via `py/run-module` after
`prelude/uast`, 4 VMs, resolve once per compiled VM. Cold run / mean of 5
warm runs, ms:

| phase | JVM | Node | Dart |
|---|---|---|---|
| mark-tails(then prelude form) | 1.8 / 1.0 | 10 / 5.4 | 12.9 / 2.2 |
| ast->datoms | 3.1 / 1.6 | 19.7 / 12.6 | 21.0 / 7.2 |
| walker eval | 145 / 74 | 208 / 105 | 602 / 90 |
| semantic load(linearize) | 275 / 221 | 711 / 520 | 670 / 398 |
| semantic run | 13 / 7 | 16 / 9 | 209 / 45 |
| stack resolve | 104 / 98 | 281 / 265 | 296 / 208 |
| stack lower-stack | 110 / 87 | 193 / 205 | 203 / 150 |
| stack create-vm (validate + hash) | 85 / 78 | 201 / 197 | 1,213 / 860 |
| stack run | 55 / 8 | 29 / 16 | 124 / 27 |
| register resolve | 90 / 103 | 245 / 243 | 288 / 208 |
| register lower-register | 263 / 281 | 513 / 491 | 2,327 / 1,895 |
| register create-vm (validate + hash) | 500 / 451 | 930 / 881 | 6,714 / 6,843 |
| register run | 70 / 28 | 61 / 43 | 131 / 70 |
| TOTAL | 1,714 / 1,437 | 3,417 / 2,990 | 12,811 / 10,802 |

Sub-phases, timed separately (warm mean, ms):

| sub-phase | JVM | Node | Dart |
|---|---|---|---|
| body-liveness, all 835 bodies (runs inside lower-register AND create-vm) | 186 | 275 | 1,755-2,064 |
| register-image-defect (incl. a second body-liveness) | 227 | - | 2,053-2,359 |
| encode-register-image (2.2 M chars) | 214 | - | 981-1,039 |
| jing/sha256 of that encoding | 1.9 | - | 4,994-5,042 |
| stack encode-image (358 K chars) | 36 | - | 141-196 |
| jing/sha256 of the stack encoding | 0.5 | - | 711-792 |

## 2. The parity floor and the worst Dart outlier

Parity (above): the floor is 1.44 s JVM, 2.99 s Node and 10.8 s Dart per
4-VM run. The previous report had about 1.4 / 3 / 11 s.

set-recursion-limit-test (first case reproduced: `programs/probe` derived
under `hooks/profile`, limit 50, hook prelude + base prelude, `with-signals`):

| phase (warm mean, ms) | JVM | Node | Dart |
|---|---|---|---|
| encoder/project | 94 | 252 | 281 |
| derive-envelope | 206 | 487 | 677 |
| semantic-bytecode->ast | 136 | 266 | 407 |
| walker eval | 638 | 346 | 353 |
| semantic load + run | 273 | 591 | 746 |
| stack resolve + lower + create + run | 317 | 771 | 1,785 |
| register resolve + lower + create + run | 1,077 | 2,555 | 10,445 |
| TOTAL per run | 2,751 | 5,297 | 14,764 |

The test makes 10 such hooked 4-VM runs (1 + 1 + 4 + 1 + 3 cases). In the
baseline helpers each run also re-derives `A'` (10 derive chains, 20
resolves; call counts in section 4). So the test costs 10 x the per-run
floor: JVM ~17 s, Dart ~130-150 s. The other two Dart outliers match:
int-defects-stay-host-failures is 4 parity runs (4 x 10.5 = 42 s Dart,
4 x 1.4 = 5.6 s JVM), and admission-in-every-mode is 3 runs + 2 derives.

What is specifically slower on Dart (Dart/JVM, parity):
- register create-vm: 15x (6.8 s vs 0.45 s). Of that, 5.0 s is
  `jing/sha256`, which is 2,600x the JVM's `MessageDigest`. The :cljd
  branch (dao/jing.cljc:184) pads to a seq, then runs `partition 64` and
  `reduce process-chunk` byte by byte. `package:crypto`'s `sha256` over
  `utf8.encode(s)` takes 27.5 / 26.9 / 29.1 ms on the same string vs
  4,077 / 3,828 / 3,679 ms, with an equal hex digest.
- stack create-vm: 11x. 0.7 s of it is the same sha256.
- body-liveness: 9.4x. `(vec (repeat total (sorted-set)))` per body is
  835 x 11,304 = 9.4 M slots per pass. Allocating those vectors alone, with
  no dataflow, takes 2.86-2.91 s for two passes on Dart. This is O(bodies x
  instructions), quadratic in the prelude's size, on every host.
- Everything else is 1.2-2.1x (resolve, linearize, lower-stack, runs).
  There is no deep-recursion, exception-unwinding or BigInt hot spot.
  The hooked runs' guest code (recursion to depth 50 with RecursionError)
  is a small part of every host's total.

The JVM has its own anomaly: the AST walker is 1.8x SLOWER on the JVM
than on Node/Dart in the hooked run (638 vs 346 ms). A stack sample
(target/prof/walker.clj) puts 60% self time in clojure.lang.RT:944 under
`->ASTWalkerVM`. `cesk-return` calls the 45-field record's positional
factory on every CESK step. Past 20 fields Clojure's factory takes the
rest as a varargs seq and reads each with `nth`, which walks the seq.
So that is ~66% of walker time on the JVM: ~250-400 ms per hooked run,
~40 ms per parity run.

## 3. Options ranked by test time saved per unit of risk

| rank | option | saving (measured or prototyped) | risk |
|---|---|---|---|
| 1 | (d1) :cljd `jing/sha256` and `sha256-bytes` via package:crypto (dao/jing.cljc, below the dao.stream boundary) | Dart: -5.4 s per 4-VM run (-50% of the floor); digests equal on the 2.2 MB case | very low: library swap, same bytes; the existing jing hash fixtures check it |
| 2 | (d2) body-liveness: per-body vectors instead of image-length vectors (debruijn_register_code.cljc) | JVM, prototyped via alter-var-root, outputs and VM hashes identical: lower-register 248 -> 149 ms, create-vm 430 -> 333 ms (-200 ms/run, -14%). Dart estimate -2.9 s/run (the allocation measured alone) | low: pure function, equality checked on all 835 bodies |
| 3 | (a) test-side: one resolve per run for stack + register, memoized `derived-ast` in safepoint-test | section 4: -5 to -8% fast form, -6 to -18% multi-run slow tests | very low: assertions unchanged; see section 4 |
| 4 | (d3) `to-hex` without per-digit `str` (debruijn_code.cljc) | JVM prototyped: register create-vm 333 -> 235 ms, stack create-vm 60 -> 48 ms (-110 ms/run); hashes identical | low; CLJS and Dart need their own int64 path checked |
| 5 | (d4) `cesk-return`: `assoc` the 3-4 changed fields (or call the record's constructor directly) instead of the 45-arg `->ASTWalkerVM` | JVM only: up to ~66% of walker time (~40 ms per parity run, ~250-400 ms per hooked run). Not prototyped | low-medium: the walker is hot code; record identity semantics unchanged |
| 6 | (c) fewer VMs in the fast lane | per-run floor share by VM (JVM / Dart): walker 5% / 1%, semantic 16% / 4%, stack 19% / 12%, register 60% / 83%. Walker + semantic only: -79% JVM, -95% Dart. Dropping only register: -60% / -83% | high coverage cost: the de Bruijn compilers, liveness and admission get no fast-lane Python signal; register- or stack-only regressions show only in test:slow |
| 7 | (b) product linked prelude (compiled once, content-addressed) | per run: only the program's own compile + run plus attach; roughly -90 to -95% of the floor on every host if admitted images are cached by identity | high: a multi-slice product project (below) |

(d) other observations, not ranked:
- `attach-image` (yin.vm.debruijn.register) re-validates the attached
  image and calls `register-hash` on the combined image. On Dart, until
  d1 lands, a linked prelude would still pay a 4+ s hash per test. The
  linked prelude needs an admitted-image cache keyed by identity to deliver
  its saving.
- `resolve`'s `validate-resolved` runs again inside `lower-stack` and
  `lower-register`. `index-datoms`'s `get-attr` (a linear `filter` over
  an entity's datoms) is 50-59% of resolve and lower-stack time. A product
  fix could save about 100-150 ms per run on the JVM (unprototyped).
- The hooks path (encoder/project + derive + bytecode->ast) is ~0.4 s
  JVM, ~1.0 s Node and ~1.4 s Dart per derivation. Its cost is
  content-hashing (blake3, CBOR `check-text`) and the
  `parse-segment-address` regex in `validate-rows`. All of it scales with
  the whole tree.
- No repeated registry construction of note: `host-registry` and
  `opts-under` are cheap or built once per namespace.

(b) in the design: docs/design/yang.antlr.md section 8.5.6 "Imports, the
linked prelude, and the frontend catalog (phase C4)", paragraph "The
linked prelude" (one source, two emitters; state via `py/init!`; entry
wrapper `(do (require 'py) (py/init!) (py/run-main ...))`), with
prerequisites L-a (host-export profiles in the published prelude manifest:
cell, data, integer, stream), L-b (the AST walker admitting the linked
prelude's sibling reads: "the walker cannot link the prelude today"), and
P2 (module emitter, publication, linked profile; needs L-a). Section 9.3
states the goal: "A prelude is compiled once and content-addressed".
Cost: every golden moves once, `pysp` load order inverts, and the
safepoint stream passes through `(pysp/attach! signals)`. This is the
real fix for the floor, but it is phase C4 product work, not a test change.

## 4. The test-side prototype (UNCOMMITTED, in this working tree)

`git diff --stat`: 5 files, +34/-11.

```
test/yin/vm/test_utils.cljc
+            [yin.vm.debruijn-resolve :as resolve]
+(def ^:private last-resolved (atom nil))
+
+(defn resolved-of
+  "`(resolve/resolve (vm/ast->datoms ast))`, kept for the last `ast` by
+   identity, so the stack and register runners of one run resolve its
+   program once (`adapt` is `lower-*` after exactly this `resolve`)."
+  [ast]
+  (let [[k v] @last-resolved]
+    (if (identical? k ast)
+      v
+      (let [v (resolve/resolve (vm/ast->datoms ast))]
+        (reset! last-resolved [ast v])
+        v))))

prelude_parity_test.cljc, float_address_test.cljc, safepoint_test.cljc
(runners), e2e_test.clj (run-python, which e2e-c1 and e2e-c2 reach through
every-vm=):
-  (:image (dl/adapt (vm/ast->datoms ast)))      ; or (dl/adapt datoms)
+  (:image (dl/lower-stack (tu/resolved-of ast)))
-  (:image (rc/adapt (vm/ast->datoms ast)))      ; or (rc/adapt datoms)
+  (:image (rc/lower-register (tu/resolved-of ast)))

safepoint_test.cljc:
-(defn- derived-ast
-  [pk]
-  (vm/semantic-bytecode->ast (tree-of (:derived (derive* pk hooks/profile)))))
+(def ^:private derived-ast
+  "`A'` for `pk` under `hooks/profile`, memoized: `pk` is a constant
+   packet and `A'` an immutable value (`insertion-is-deterministic-test`
+   still derives twice through `derive*`)."
+  (memoize
+    (fn [pk]
+      (vm/semantic-bytecode->ast (tree-of (:derived (derive* pk hooks/profile)))))))
```

Why it cannot change what a test asserts:
- `dl/adapt` is defined as `(lower-stack (resolve/resolve named-datoms))`
  and `rc/adapt` as `(lower-register (resolve/resolve ...))`.
  `resolve` returns immutable maps and vectors (its atoms are internal and
  dereferenced before return), so one result is safe to share. The cache
  holds one entry keyed by `identical?`. A throwing resolve caches
  nothing, so both runners still throw. Every VM is still created fresh
  per run; nothing about the VM, store or prelude is cached.
- `derived-ast`'s input is a constant packet and its output an
  immutable AST. Every caller derives a new tree from it
  (`with-run`/`with-limit-cell` use `update-in`).
- A first version also memoized `envelope` and `derive*`. That would have
  made `insertion-is-deterministic-test` compare one cached value with
  itself, a vacuous assertion. I stopped that option and memoized only
  `derived-ast`, which that test does not use.
- Results: every run below has the same pass counts before and after,
  0 failures, 0 errors. kondo on the 5 files: 0 errors, 0 warnings.
  cljstyle was NOT run. Four changed lines are over 80 columns (the
  parity, float and safepoint stack runner lines and the new
  `derived-ast` body), so run `cljstyle fix` on these files.

JVM, fast form (all non-^:slow vars of each namespace, one process per
mode, target/prof/nstime.clj; `--base` puts HEAD copies of the five files
first on the classpath):

| ns | resolve calls base -> proto | wall s base -> proto | thread-CPU s base -> proto | assertions |
|---|---|---|---|---|
| prelude-parity-test | 32 -> 16 | 22.9 -> 22.7 | 22.1 -> 21.8 | 72 pass both |
| safepoint-test | 8 -> 4 | 8.8 -> 8.5 | 8.5 -> 8.2 | 328 pass both |
| float-address-test | 10 -> 9 | 8.0 -> 5.2 | 6.4 -> 5.0 | 83 pass both |
| e2e-test | 32 -> 16 | 40.0 -> 22.2 | 28.5 -> 21.4 | 107 pass both |
| total | 82 -> 45 | 79.7 -> 58.5 | 65.5 -> 56.4 | |

A second pair of runs gave base 74.9 s wall / 64.2 s CPU and prototype
60.9 / 57.2. The wall and CPU deltas are inflated by load (the e2e base
run was clearly disturbed). The dependable estimate comes from the counts:
37 fewer resolves x ~95 ms = ~3.5 s, about 6% of these four namespaces.
The fast form never reuses a derivation; the derive, project and
bytecode->ast counts are equal in both modes.

JVM, a handful of slow vars (`:vars`, same harness):

| var | base -> proto calls (resolve; derive-envelope) | wall s | CPU s |
|---|---|---|---|
| safepoint set-recursion-limit-test | 20 -> 10; 10 -> 1 | 18.6 -> 15.3 | 17.5 -> 14.1 |
| safepoint admission-in-every-mode-test | 6 -> 3; 2 -> 1 | 5.5 -> 5.1 | 5.4 -> 5.0 |
| safepoint generator-depth-test | 2 -> 1; 1 -> 1 | 13.3 -> 14.2 | 13.2 -> 14.0 (noise) |
| parity int-defects-stay-host-failures | 8 -> 4 | 4.8 -> 4.8 | 4.8 -> 4.7 |
| parity range-fast-path | 2 -> 1 | 5.1 -> 5.4 | 5.0 -> 5.3 (noise) |
| float runs-on-every-vm-test | 4 -> 2 | 4.5 -> 4.3 | 4.4 -> 4.2 |
| e2e arithmetic-and-print-test | 4 -> 2 | 2.9 -> 2.7 | 2.8 -> 2.7 |
| e2e numeric-hash-test | 4 -> 2 | 6.4 -> 6.0 | 6.0 -> 5.9 |
| total | | 61.1 -> 57.8 | 59.1 -> 55.8 (-6%) |

Node: the helpers are .cljc and share the code path (test-utils is
.cljc and the runners are the same forms). prelude-parity-test fast form,
`clj -M:cljs -m shadow.cljs.devtools.cli compile test --config-merge
'{:ns-regexp "^yang\\.python\\.antlr\\.prelude-parity-test$"}'`, then
`node target/node-tests.js` 3 times each: base 14.5 / 14.4 / 14.2 s,
prototype 13.3 / 13.3 / 13.2 s (-8%). Both: 26 tests, 28 assertions,
0 failures, 19 SKIP. (For the base I copied the HEAD files over test/,
ran, and copied the prototype back from target/prof/proto/. The working
tree now holds the prototype, as `git diff` shows.)
Dart: safepoint-test compiled with the prototype and passed its fast form
(`flutter test`, 22 tests). Not timed: the saving is ~0.21 s per resolve
avoided plus ~1.4 s per derivation avoided, so about -2% of a parity run
and about -10% for set-recursion-limit-test (~9 x 1.4 + 10 x 0.21 = ~15 s
of ~130-150 s).

## 5. Recommendation and expected totals

Current Python-namespace totals (previous report, after tagging):
fast form JVM ~120 s, Node ~27 s, Dart ~96-103 s. The whole Python set,
slow included (its before-tagging totals): JVM ~520 s, Node ~430 s,
Dart ~970 s.

Land now (test-side): the prototype above, after cljstyle. Expected: fast
form JVM ~114 s (-5%), Node ~25 s (-8%), Dart ~98 s (-2%). Whole Python
set: JVM ~490 s, Node ~400 s, Dart ~930 s (the derived-ast memo helps the
multi-run safepoint tests most). This is an estimate from the measured
per-call costs and counts.

Schedule next (product, small, outside engine/ucf; each its own slice with
golden/hash checks):
1. d1, Dart sha256 via package:crypto: Dart per-run 10.8 -> ~5.4 s. This
   matches the standing rule to use a library for crypto, not hand-roll it.
2. d2, per-body liveness vectors: JVM -0.2 s/run; Dart a further ~-2.9 s/run.
3. d3, to-hex: JVM -0.1 s/run.
4. d4, the walker's 45-arg record factory: JVM walker -40 to -400 ms/run.
After 1-3 plus the test-side change, the per-run floor is estimated at
JVM ~1.0 s (from 1.44), Node ~2.3-2.5 s (from 3.0; d2 and d3 unmeasured
there) and Dart ~2-2.5 s (from 10.8). Expected fast form: JVM ~90 s, Node
~21 s, Dart ~30-35 s (the 5 Dart smoke tests drop from ~55 s to ~12 s).
Whole Python set: JVM ~390 s, Node ~330 s, Dart ~250 s. Most of the 15
guard-only tests (tagged only because Dart took over 5 s) could then come
back into the Node/Dart fast lane. Re-measure before untagging.
Then (b), the linked prelude (C4: L-a, L-b, P2, with an identity-keyed
admitted-image cache): the floor falls to the program's own compile and
run, roughly 0.1-0.2 s JVM per run. The fast form would then be dominated
by tests' own work, plausibly JVM ~30-40 s.
(c), fewer VMs in the fast lane: I do not recommend it now. After d1 and d2
the register VM is no longer Dart's 83%, and the coverage it gives up (the
de Bruijn compile and admission paths) is where cross-host bugs live.
Revisit only if the fast lane is still over target after d1-d3.

All post-change totals above are estimates from measured per-phase costs,
not measured lanes. Only the test-side prototype was measured end to end
(JVM per namespace and per var, Node one namespace).

## Artifacts (target/prof/, gitignored)

phase.clj (JVM e2e breakdown), zz_phase_prof_test.cljc (portable
profiler; to rerun on Node/Dart, copy it under test/yang/python/antlr/,
compile, run, then delete it), sample.clj and walker.clj (JVM stack
samplers), proto.clj (d2/d3 alter-var-root prototypes with equality and
hash checks), nstime.clj (per-ns/per-var wall, thread-CPU and call
counts), run.clj (`--base` puts target/prof/base first), mkbase.clj and
swap.clj (HEAD copies under base/, prototype copies under proto/),
nodetime.clj.

## Fixes implemented (2026-10-06, UNCOMMITTED in this worktree)

Owner decision: implement d1, d2 and d3 now (d3 only if trivial), keep
the test-side prototype, and touch nothing in engine or ucf. Before
editing I checked every sibling worktree (target/prof/who_touches.clj:
commits in master..HEAD plus uncommitted edits, read-only). No other
worktree touches dao/jing.cljc, debruijn_code.cljc or
debruijn_register_code.cljc, and none of the three is in the engine or
ucf directories.

`git diff --stat`: 10 files, +113/-159. Product: 3 files. Tests: 7 (the
5 prototype files, plus jing_test and debruijn_register_compile_test).

### d1: Dart SHA-256 via package:crypto (src/cljc/dao/jing.cljc)

- `sha256` :cljd is now
  `(.toString (.convert crypto/sha256 (convert/utf8.encode s)))`, and
  `sha256-bytes` :cljd is `(.toString (.convert crypto/sha256 bs))`.
  Same functions, same lowercase-hex String output. The :clj and :cljs
  branches are unchanged. The 112-line hand-written SHA-256 block (`:cljd`
  only, used by nothing else) is deleted. `package:crypto` 3.0.7 was
  already a direct pubspec dependency; the `ns` form gains one :cljd
  require.
- Test first (test/dao/jing_test.cljc, `sha256-known-answer`): two new
  rows, the NIST million-'a' vector (15,625 blocks) and a non-ASCII
  string, written with ASCII escapes, whose surrogate pair must digest as
  one 4-byte UTF-8 sequence. Expected digests come from Python's hashlib.
  Both rows passed on the OLD Dart implementation (39 tests) before the
  swap, and pass after it. The stale "hand-rolled" comment now names
  package:crypto.
- Byte identity: the jing known-answer, hash-registry contract, CBOR
  fixture and CBOR conformance tests (resource SHA-256 over whole fixture
  files) pass on Dart. The register image hash, stack image hash and
  live-set digest over the prelude are identical before and after, and
  identical to the JVM (table below).

### d2: body-liveness per-body vectors (src/cljc/yin/vm/debruijn_register_code.cljc)

- The two dataflow vectors are as long as the body (`pc - start`
  indexed), not the whole image. A successor pc outside the body reads the
  empty set, exactly as the never-written slot did before. A successor pc
  outside the image still throws from `nth`, as before.
- Test first (debruijn_register_compile_test.cljc,
  `body-liveness-reads-only-its-own-body-test`): a fixed four-body image
  pins the answer for every body (`[{3 [1]} {9 [0]} {12 []} {}]`). The
  expected values are the old implementation's output, captured before the
  change. The test also pins that a body falling into the next body does
  not see that body's reads, and that a successor past the image throws.
  The existing hand-derived live-set fixtures and
  `lowered-live-matches-freshly-recomputed-body-liveness-exactly` still pass.
- Hash equality: the register image hash over the prelude (its `:call`
  live operands come from body-liveness) and a SHA-256 over every body's
  live map are unchanged on the JVM and on Dart.

### d3: to-hex via a byte table (src/cljc/yin/vm/debruijn_code.cljc)

- Every caller passes width 2 or 8 with a non-negative integer, and the
  old result is the low `width` hex digits of n. The new `to-hex` reads a
  256-entry two-digit table: one lookup for width 2, four for width 8.
  Host behaviour is unchanged. Above 2^32, the old CLJS loop worked on
  n mod 2^32 through `unsigned-bit-shift-right`, and the new code does the
  same through `bit-and`/`unsigned-bit-shift-right` with shifts of 24 or
  less.
- Verified on the JVM against the old function for n in 0..69,999 plus
  the 2^24, 2^31, 2^32 and 0x123456789ab edges, both widths
  (target/prof/tohex.clj). Isolated: `register-hash` over the prelude
  215 -> 120 ms, stack `image-hash` 33 -> 20 ms, hashes identical. The
  copy of `to-hex` in yin/vm/debruijn.cljc (the projection dimension, not
  on this hot path) is left alone.

### Measured: one parity run (print(1) after the whole prelude, 4 VMs)

Profiler: a temporary test namespace, kept now as
target/prof/zz_parity_prof_test.cljc and deleted from test/. It shares
`resolve` between stack and register in every column, so the columns
differ only by the product fixes. Warm mean of 5 runs after a cold run.
CPU is whole-process CPU: the JVM's OperatingSystemMXBean, and on Dart
`ps -o time` of the test process. The machine was loaded, so CPU is the
fairer number.

| host | before: wall / CPU ms | after d1: wall / CPU | after d1+d2+d3: wall / CPU |
|---|---|---|---|
| JVM | 1,383 / 1,707 | (d1 is :cljd only) | 923 / 1,240 (-33% / -27%) |
| Dart | 15,913 / 15,440 | 6,325 / 6,398 | 2,312 / 2,526 (-85% / -84%) |

Dart per phase, before -> after all fixes (warm mean, ms): register
create-vm 10,750 -> 618, register lower-register 2,431 -> 364, stack
create-vm 1,211 -> 145, the rest unchanged within noise. JVM: register
create-vm 481 -> 248, lower-register 297 -> 158, stack create-vm 78 -> 56.
Dart is now ~2.5x the JVM, not ~11x. The JVM total is 923 ms against the
1.44 s floor in section 1, which did not yet share resolve.

Hashes printed by both hosts, before and after every step, all equal:
register image 54719952691cd9feb6fdc28d7bb343e2bfb774988a6f555422be00c4cf54503f,
stack image 8c42c966ddf44692962945090144b08fc7cea1714220ff4f82a1624e88843ad4,
live sets 5e2402d97dfcd00ea394aa6a8421e14d3da4594236c22a5f2f268726349f1957.

### Focused tests that passed

- `bb test:changed:{clj,cljs,cljd} --changed test/dao/jing_test.cljc
  test/dao/jing/hash_registry_contract_test.cljc
  test/dao/jing/cbor_fixtures_test.cljc
  test/yin/vm/debruijn_register_compile_test.cljc
  test/yin/vm/debruijn_code_test.cljc` selects 6 namespaces (dao.jing-test,
  cbor-conformance, cbor-fixtures, hash-registry-contract,
  debruijn-code-test, debruijn-register-compile-test). JVM 136 tests,
  0 failures, 17 s. Node 166 tests, 0 failures, 21 s. Dart 132 tests,
  0 failures, 55 s.
- JVM, `bb test:changed:clj --changed` on the register, stack,
  attach-image, debruijn, register-contract, vm-contract and stack-parity
  tests plus the four Python test files: 27 namespaces (the Python
  e2e/c1/c2/parity/safepoint/float set, debruijn register/stack/linearize
  suites, linker, mint, yin.repl require/dht), 533 tests, 0 failures, 156 s.
- The plain `bb test:changed:list` selects 140 namespaces per lane, because
  dao.jing and the debruijn code files are required almost everywhere. I
  did not run it: too wide for the 600 s cap. Before landing, the
  full-lane command is `bb test:changed` (or `bb test`), one lane set at a
  time.
- Not run: Dart and Node on the Python namespaces with these fixes
  (covered only by the hash equality above), and every slow lane.

### Lint, format, line length

- kondo (`clj -M:kondo --lint` on all 10 changed files): 0 errors,
  0 warnings. A first version of d2 drew one "unused value" warning, which
  the `cond` form removed.
- cljstyle was BLOCKED: `cljstyle fix` and `cljstyle check` both need a
  permission I do not have here. I matched the existing layout by hand.
  Please run `cljstyle check` (or `fix`) on the 10 files.
- No added line exceeds 80 columns and none is non-ASCII
  (target/prof/longlines.clj over `git diff -U0`). This needed wrapping
  the four over-long prototype lines: e2e now binds
  `resolved #(tu/resolved-of ast)`.
- The temporary profiling namespace is gone from test/ (moved to
  target/prof/), and its generated test/cljd-out files are deleted.

### Declined, and why

- d4 (the AST walker's 45-arg `->ASTWalkerVM` rebuild in `cesk-return`):
  skipped as instructed. It is an engine-side concern, JVM only, ~40 ms per
  parity run.
- The duplicate `to-hex` in yin/vm/debruijn.cljc: not on the measured hot
  path, so it is outside the minimal change.
- `index-datoms` `get-attr` / the repeated `validate-resolved` (~100-150 ms
  per JVM run): not requested, not prototyped, and they live in yin/vm.cljc
  and debruijn_resolve.cljc, which is broader than these fixes.
- (b) linked prelude and (c) fewer VMs: unchanged from section 5. With
  d1-d3 the register VM is no longer Dart's 83%, which strengthens the
  case against (c).

Revised expectations (estimates from the per-run numbers, not measured
lanes): a Python parity run now costs ~0.9 s JVM and ~2.3 s Dart. The
Python fast form should be roughly JVM ~85-90 s (from ~120) and Dart
~25-30 s (from ~100). Most of the 15 guard-only tests (tagged because Dart
took over 5 s) should now run in about 2.5-5 s on Dart. Re-measure before
untagging any of them.
