Created-GMT: 2026-11-27 07:04:50 GMT
Created-Local: 2026-11-27 14:04:50 +0700 (Asia/Ho_Chi_Minh)
Coding-Agent: agy
Session-ID: b7727f50-cada-4c38-8df3-a364422e79f5
# Findings: root-cause and fix for the `unit` gate hang on jolt fork main (row 2446)

Role: VM Runtime Engineer (diagnosis and fix)
Implementers:
- Model: gemini-4-argon-high | Assigned: 2026-11-27 14:04:50 +0700 | Status: completed

--------------------------------------------------------------------------------
## 1. Root Cause (Hypothesis H3 Confirmed, with a Secondary H1 Hazard Identified)
--------------------------------------------------------------------------------

The hang is **Hypothesis H3**: an unbounded mutual recursion between the fork's `clojure.core/sorted?` (`jolt-core/clojure/core/25-sorted.clj:222`) and upstream's `value-host-tags` class-registration dispatch path (`host/chez/java/host-static-classes.ss:3547,3589`), closed after **row 2440** (`test/chez/unit.edn:4255`) installs a `__register-class!` predicate that calls `(not (sorted? x))`.

### Why it did not happen before the sync

1. **Commit `8faa785f`** ("Stop resolving types and methods by name on every call") introduced the `dispatch-caches` suite in `test/chez/unit.edn`, including both row 2440 and row 2446 (`uis4-chk`). At `8faa785f` (and on old fork `main` `a6852ef8`), row 2440 registered:
   ```clojure
   (clojure.core/__register-class!
     (fn [x] (and (map? x) (= :uclass-probe (:uclass-kind x)) false))
     (fn [_] "my.Map")
     (fn [_] ["my.Map"]))
   ```
   Notably, commit `8faa785f` fixed the exact same class of recursion for `map?`, `vector?`, `set?`, `associative?`, and `coll?` by introducing `jrec-type-isa?` (`host/chez/records-coll.ss:368-387`) and `jrec-iface-pred?` (`host/chez/post-prelude.ss:336-352`), noting in `host/chez/records-coll.ss:373-377`:
   > `;; It must NOT be instance?: instance? consults the library arms, and a library's`
   > `;; value-tags arm (__register-class!) is a predicate over arbitrary values that`
   > `;; may well call map? — which asked instance? of the same deftype, which asked the`
   > `;; arm again: an unbounded recursion that hung any (satisfies? P x) or`
   > `;; (instance? P x) on such a value once a library had registered one.`

2. **Upstream commit `2573c1807cd3909c4cc0ebc0c77de85fa49e2d2a`** ("Register the JVM's built-in print-methods and resolve class keys by isa?", 2026-11-19, between `a6852ef8` and `17265497`) added `print-method` unit tests at the end of `test/chez/unit.edn` that print `(sorted-map 1 2)`. Because row 2440's registered predicate stayed live across later rows and called `(:uclass-kind x)` on every `map?`, evaluating `(:uclass-kind (sorted-map 1 2))` invoked the sorted map's comparator between `:uclass-kind` and `1` and threw `ClassCastException`. Upstream therefore changed row 2440 (`test/chez/unit.edn:4255`) to guard with `(not (sorted? x))`:
   ```clojure
   {:suite "dispatch-caches" :expr "(do (clojure.core/__register-class! (fn [x] (and (map? x) (not (sorted? x)) (= :uclass-probe (:uclass-kind x)) false)) (fn [_] \"my.Map\") (fn [_] [\"my.Map\"])) (class 1))" :expected "java.lang.Long"}
   ```
   In upstream, `sorted?` is `(or (sorted-map? x) (sorted-set? x))`, which checks `jolt.host/sorted-kind` directly and never calls `instance-check`.

3. **In the fork (`46d4090b`)**, `sorted?` in `jolt-core/clojure/core/25-sorted.clj:222` was changed to:
   ```clojure
   (defn sorted? [x] (or (tree-sorted? x) (instance-check clojure.lang.Sorted x)))
   ```

### Step-by-step mechanism of the hang

1. **Row 2440** (`test/chez/unit.edn:4255`) calls `clojure.core/__register-class!` (`host/chez/java/host-static-classes.ss:3556-3563`) with `pred = (fn [x] (and (map? x) (not (sorted? x)) (= :uclass-probe (:uclass-kind x)) false))`:
   - Line 3559 calls `(register-class-arm-checked! p ...)` (`host/chez/java/host-class.ss:34-37`), which runs `(class-arm-reject-fast-type! '__register-class! p)` against `(class-fast-probes)` **before** `p` is added to `jt-user-value-tags-arms` on line 3560.
   - During the probe on `{:a 1}`, `p` calls `(sorted? {:a 1})` -> `(instance-check clojure.lang.Sorted {:a 1})`, which succeeds because `p` is not yet in `jt-user-value-tags-arms`.
   - `register-class-arm!` (`host/chez/java/host-class.ss:24`) then increments `instance-arms-epoch` (invalidating the memo entry computed during the probe), and `host-static-classes.ss:3560` appends `(cons p ...)` to `jt-user-value-tags-arms`.
   - `jt-user-value-tags-arms` is process-wide and is not reset by `zj-reset!` (`host/chez/run-case-isolation.ss`).
2. **Rows 2441–2445** (`test/chez/unit.edn:4258-4260`) pass because they only call `instance?` on a bare `deftype` `(T.)`, `:a`, `"s"`, and `1`, for all of which `(map? x)` is `false` (short-circuiting `p` before `(not (sorted? x))`). Row 2444 (`test/chez/unit.edn:4259`) also calls `__register-instance-check!`, bumping `instance-arms-epoch` again.
3. **Row 2446** (`test/chez/unit.edn:4261`) is the first row after row 2440 to call `instance?` on a map (`{}`):
   - `(uis4-chk {})` evaluates `(instance? clojure.lang.IPersistentMap {})`, which lowers to `(jolt-instance-site site clojure.lang.IPersistentMap {})` (`host/chez/java/records-interop.ss:309`).
   - On the cold site, `instance-site-miss` (`records-interop.ss:316,328`) calls `(instance-kind-entry "clojure.lang.PersistentArrayMap" "clojure.lang.IPersistentMap" ts {})`.
   - Because `instance-arms-epoch` moved, `instance-kind-entry` (`records-interop.ss:249-254`) calls `(instance-kind-verdict ts {})` **before** storing the entry in `instance-kind-memo`.
   - `instance-kind-verdict` (`records-interop.ss:239-242`) runs `(instance-arms-until instance-check-registry instance-check-user-arm ts {})`.
   - The builtin interface arm at `host/chez/java/host-static-classes.ss:3583` (registered after `instance-check-user-arm` at line 3471, so placed ahead of it in the `cons`-ordered `instance-check-registry`) calls `(value-host-tags {})` on line 3589.
   - `value-host-tags` (`host-static-classes.ss:3547-3552`) walks `jt-user-value-tags-arms` **before** the base `value-host-tags`, invoking row 2440's predicate `p` on `{}`.
   - Inside `p`, `(map? {})` is `true`, so `p` calls `(sorted? {})`.
   - In the fork's `sorted?` (`jolt-core/clojure/core/25-sorted.clj:222`), `(tree-sorted? {})` is `false`, so it calls `(instance-check clojure.lang.Sorted {})` (via `sorted?`'s own `jolt-instance-site`).
   - Both `sorted?`'s inline site cache and the `instance-kind-memo` entry for `("clojure.lang.PersistentArrayMap", "clojure.lang.Sorted")` have a stale epoch (from the epoch bumps at the end of row 2440 and in row 2444), so `instance-kind-entry` calls `(instance-kind-verdict 'clojure.lang.Sorted {})` **before** writing to `instance-kind-memo`.
   - `(instance-kind-verdict 'clojure.lang.Sorted {})` again calls `(value-host-tags {})` -> `p` -> `(sorted? {})` -> `(instance-check clojure.lang.Sorted {})` -> `(instance-kind-verdict 'clojure.lang.Sorted {})` in an infinite non-tail recursion (Chez allocates stack segments on the heap, spinning on one core).

--------------------------------------------------------------------------------
## 2. Smallest Reproduction
--------------------------------------------------------------------------------

Two expressions in a single process reproduce the hang deterministically: registering the class predicate from row 2440, followed by `(instance? clojure.lang.IPersistentMap {})`.

### Command

```sh
cat > /tmp/jolt-probe-row.scm << 'EOF'
(import (chezscheme))
(load "host/chez/run-gate-harness.ss")
(load "host/chez/loader.ss")
(load "host/chez/java/ffi.ss")
(set-source-roots! ldr-install-roots)
(load-namespace "jolt.time.base")
(load "host/chez/gate-private-tmpdir.ss")
(define src (list-ref (command-line) 1))
(fork-thread (lambda () (sleep (make-time 'time-duration 0 5)) (printf "HANG (>5s): ~a\n" src) (flush-output-port) (jolt-exit-process 3)))
(printf "OK: ~a => ~a\n" src (jolt-repl-str (jolt-compile-eval (string-append "(do " src ")") "user")))
(flush-output-port)
(exit 0)
EOF
/Users/sto/.local/share/mise/installs/chezscheme/10.4.1/bin/scheme --script /tmp/jolt-probe-row.scm '(clojure.core/__register-class! (fn [x] (and (map? x) (not (sorted? x)) (= :uclass-probe (:uclass-kind x)) false)) (fn [_] "my.Map") (fn [_] ["my.Map"])) (instance? clojure.lang.IPersistentMap {})'
```

### Verbatim output before fix (exit code 3)

```text
HANG (>5s): (clojure.core/__register-class! (fn [x] (and (map? x) (not (sorted? x)) (= :uclass-probe (:uclass-kind x)) false)) (fn [_] "my.Map") (fn [_] ["my.Map"])) (instance? clojure.lang.IPersistentMap {})
```

### Verbatim output after fix (exit code 0)

```text
OK: (clojure.core/__register-class! (fn [x] (and (map? x) (not (sorted? x)) (= :uclass-probe (:uclass-kind x)) false)) (fn [_] "my.Map") (fn [_] ["my.Map"])) (instance? clojure.lang.IPersistentMap {}) => true
```

--------------------------------------------------------------------------------
## 3. What Was Changed and Why (Plus Rejected Alternatives)
--------------------------------------------------------------------------------

### Change made

In `jolt-core/clojure/core/25-sorted.clj` (lines 219–228), guarded `(instance-check clojure.lang.Sorted x)` in `sorted?` with `(jolt.host/jrec-method? x "seqFrom")`:

```clojure
;; Clojure: (instance? clojure.lang.Sorted coll). Any deftype/reify declaring
;; Sorted qualifies (data.priority-map, user B-trees). instance-check: the
;; instance? macro is tier 30. Guarded by jrec-method? (like empty and ifn? in
;; 22-coll.clj) so built-in values and records without seqFrom never enter
;; instance-check, whose value-host-tags walk re-enters any __register-class!
;; predicate that calls sorted?.
(defn sorted? [x]
  (or (tree-sorted? x)
      (and (jolt.host/jrec-method? x "seqFrom")
           (instance-check clojure.lang.Sorted x))))
```

Then re-minted both Chez and Gambit seeds via `sh host/chez/remint.sh`.

### Why this is the right place and mechanism

1. **Keeps upstream code 100% untouched**: The defect was introduced by the fork's `sorted?` definition in `jolt-core/clojure/core/25-sorted.clj`. Fixing it in `25-sorted.clj` keeps every upstream `host/chez/*.ss` and `host/gambit/*.ss` file identical to upstream `17265497`.
2. **Follows the exact overlay pattern of `22-coll.clj`**: `clojure.core/empty` (`22-coll.clj:185`, `(jolt.host/jrec-method? coll "empty")`) and `clojure.core/ifn?` (`22-coll.clj:245`, `(jolt.host/jrec-method? x "invoke")`) already use `jolt.host/jrec-method?` (`host/chez/protocols.ss:409`, `host/gambit/records-gambit.ss:2073`) to test whether a `deftype`, `defrecord`, or `reify` implements an interface method without entering `instance-check`.
3. **Breaks the recursion for both built-in maps AND `defrecord`s**:
   - For any non-`jrec`/non-`jreify` value (`{}`, `[]`, `#{}`, scalars, `htable`, `jhost`), `jrec-method?` returns `#f` in two record-tag checks.
   - For any `defrecord` or `deftype`/`reify` that does not implement `seqFrom` (including every plain `defrecord`, for which `(map? r)` is `true`), `jrec-method?` checks the type's protocol method table directly (`find-method-any-protocol` / `reify-method-ref`) and returns `#f` without touching `instance-check` or `value-host-tags`.
   - For a `deftype`, `defrecord`, or `reify` that implements `clojure.lang.Sorted` (`seqFrom` is unique to `clojure.lang.Sorted` across the entire JDK and Clojure runtime), `jrec-method?` returns `#t` and `(instance-check clojure.lang.Sorted x)` confirms the type actually declares `clojure.lang.Sorted` via `own-type-declares?` (which answers from descriptor slot 8 / `reify-class-memo` without walking `value-host-tags`).
4. **Eliminates `jolt-instance-site` cache thrashing on `sorted?`**: Callers such as `reversible?` (`20-coll.clj:549`) and `distinct` (`40-lazy.clj:43`) call `sorted?` on built-in vectors, sets, and maps; those built-in values no longer reach or thrash `sorted?`'s inline `instance-check` site cache.

### Alternatives rejected

1. **Guarding `instance-check` only with a generic `(or (jrec? x) (jreify? x))` predicate (the naive H1 fix)**:
   - **Rejected because it still hangs on `defrecord` instances (and on any `deftype`/`reify` implementing `IPersistentMap` without `Sorted`)**. Every `defrecord` satisfies `(jrec? r) => #t` AND `(map? r) => true`. When a row after row 2440 calls `(instance? SomeProto r)` or `(satisfies? SomeProto r)` on a `defrecord` `r` where `own-type-declares?` misses (e.g., `rdp1.ns` and `rdp5.ns` in `test/chez/unit.edn:4638,4641`), `value-host-tags` invokes row 2440's class predicate `p` on `r`. Since `(map? r)` is `true` and `(jrec? r)` is `#t`, a `jrec?`-only guard in `sorted?` would still call `(instance-check clojure.lang.Sorted r)`, which misses `own-type-declares?` and re-enters `(value-host-tags r)` -> `p` -> `(sorted? r)` in the exact same infinite recursion.
2. **Editing `host/chez/java/host-static-classes.ss` or `host/chez/post-prelude.ss`**:
   - **Rejected because it violates the constraint to keep upstream code as synced and put the fix where the fork's defect is** (`jolt-core/clojure/core/25-sorted.clj`).

--------------------------------------------------------------------------------
## 4. Verification Run
--------------------------------------------------------------------------------

All commands were run in the foreground from `/Users/sto/workspace/jolt-syncfix` with `JOLT_CHEZ=/Users/sto/.local/share/mise/installs/chezscheme/10.4.1/bin/scheme` and `GAMBIT_PREFIX=/Users/sto/.local/share/mise/installs/gambit/4.9.8`. None of the required gates were skipped.

1. **Seed re-mint (`sh host/chez/remint.sh`)**:
   ```text
   re-minted seed (converged after 2 pass(es))
   gen-seed: backend target -> :gambit
   gen-seed: prelude #3% occurrences: 0 (all inside string data, none as op uses)
   gen-seed: image #3% occurrences: 1 (all inside string data, none as op uses)
   gen-seed: prelude grep-verified (no #3% / (foreign-procedure op uses)
   gen-seed: image  grep-verified (no #3% / (foreign-procedure op uses)
   gen-seed: wrote host/gambit/seed/prelude.ss (2463386 bytes)
   gen-seed: wrote host/gambit/seed/image.ss (2764741 bytes)
   mint: 0 form(s) skipped
   gen-seed: gambit seed minted on Chez
   ```

2. **Sorted gate (`"$JOLT_CHEZ" --script test/chez/sorted-test.ss`)**:
   ```text
     (first (seq sm)): 2064 bytes at 10k, 2096 at 40k
     (doall (take 3 sm)): 2512 bytes at 10k, 2544 at 40k
     (first (rseq sm)): 2064 bytes at 10k, 2096 at 40k
     (first (keys sm)): 528 bytes at 10k, 560 at 40k
     (second (vals sm)): 592 bytes at 10k, 624 at 40k
     (first (subseq ss >= 5000)): 608 bytes at 10k, 640 at 40k
     (first (rsubseq ss < 5000)): 672 bytes at 10k, 704 at 40k
     a repeated (hash sm): 16 bytes at 10k, 16 at 40k
     (= ss ss2) with different counts: 48 bytes at 10k, 48 at 40k
     = of two equal 40k sorted sets: 8 bytes/entry
     full next-walk of a 40k sorted map: 182 bytes/entry
     into (sorted-set) of 5: vector 1344 bytes, list 1280
     apply sorted-set of 4096 shuffled: 102 bytes/entry
   156/156 passed
   ```

3. **Freshness and structural gates (`make CHEZ="$JOLT_CHEZ" GAMBIT_PREFIX=/Users/sto/.local/share/mise/installs/gambit/4.9.8 gambitseedcheck gambitgencheck emittedheads deadhost`)**:
   ```text
   gambitseedcheck: gambit seed is current with the sources
   gambitgencheck: records-gambit.ss is current with the records files
   jolt: source mode; run 'make devboot' to precompile
   OK: 297 emitted heads, none shadowable (16 allowed)
   dead host: 8131 top-level procedures scanned, 4 reachable only through a built name
   dead host: passed
   ```

4. **Full unit gate (`make CHEZ="$JOLT_CHEZ" unit`)**:
   - First run inside the sandbox passed `2827/2828` (including `19/19 dispatch-caches`, `120/120 jvm-misc`, and all `sorted` / `r7-rsubseq` / `r7-sorted-throw` suites; only `winsock`'s `loopback-pair` TCP connect was blocked by the sandbox).
   - Second run with sandbox network/loopback restriction lifted passed all 2828 rows:
   ```text
   unit gate: 2828/2828 passed
   ...
     19/19  dispatch-caches
   ...
     120/120  jvm-misc
   ...
     3/3  r7-rsubseq
     4/4  r7-sorted-throw
   ...
     2/2  sorted / assoc identity
     1/1  sorted / subseq laziness
     2/2  sorted / tree
   ```

--------------------------------------------------------------------------------
## 5. Residual Risks & Gambit Host Impact
--------------------------------------------------------------------------------

- **Gambit host (`host/gambit/`)**: `jolt.host/jrec-method?` is already defined in `host/gambit/records-gambit.ss:2073` (generated from `host/chez/protocols.ss:409`) and is already called by `clojure.core/empty` and `clojure.core/ifn?` in the Gambit seed prelude. Re-minting updated `host/gambit/seed/prelude.ss` and `host/gambit/seed/image.ss` cleanly, and `gambitseedcheck` / `gambitgencheck` both pass.
- **Residual edge case**: A vacuous `(deftype EmptySorted [] clojure.lang.Sorted)` or `(reify clojure.lang.Sorted)` that declares the marker interface `clojure.lang.Sorted` without implementing `seqFrom` would answer `false` to `(sorted? x)` (the same tradeoff `clojure.core/ifn?` makes at `22-coll.clj:245` by checking `(jolt.host/jrec-method? x "invoke")`). Any functional `clojure.lang.Sorted` collection (such as `UserSorted` in `test/chez/sorted-test.ss:71` or `clojure.data.priority-map`) implements `seqFrom` because `subseq` and `rsubseq` require it (`25-sorted.clj:246`).

--------------------------------------------------------------------------------
## 6. Should the Two Restored `tree-sorted?` Lines in `22-coll.clj` Stay?
--------------------------------------------------------------------------------

**Yes, both restored `tree-sorted?` lines in `jolt-core/clojure/core/22-coll.clj` (line 31 in `find-other` and line 189 in `empty`) MUST stay:**

1. **Correctness for user `Sorted` types**: Both branches immediately access `(get (jolt.host/ref-get m :ops) :entry-at)` (in `find-other`) and `(get (jolt.host/ref-get coll :ops) :empty)` (in `empty`), which only exist on Jolt's built-in B+tree sorted collections (`tree-sorted?`), not on a user `deftype` or `reify` implementing `clojure.lang.Sorted`. If `sorted?` were used there, calling `(find user-sorted-map k)` or `(empty user-sorted-coll)` (when it has no custom `.empty`) would attempt `(nil user-sorted-map k)` and crash instead of falling through to the associative `(when (contains? m k) ...)` / collection arms.
2. **Performance**: `find-other` and `empty` only want to test for Jolt's native B+tree sorted collections (`tree-sorted?`), avoiding the `jrec-method?` / `instance-check` path completely.
