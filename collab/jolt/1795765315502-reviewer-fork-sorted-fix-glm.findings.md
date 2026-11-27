Completed-GMT: 2026-11-27 08:30:35 GMT
Completed-Local: 2026-11-27 15:30:35 +0700 (Asia/Ho_Chi_Minh)
Coding-Agent: glm
Session-ID: zcode-subagent-review-fork-sorted-fix

# Review: `sorted?` re-entry guard (branch `fix/sync-restore-tree-sorted-dispatch`)

**The gate is fixed, but a hang of the same kind remains for one exotic type shape.** I reproduced it seven ways; it is narrow, and I judge it non-blocking.

**Something you should know first.** The change was committed and pushed while I was reviewing: `00cb20b9` at 08:26:25 UTC, now on `origin/fix/sync-restore-tree-sorted-dispatch`. `origin/main` is still `e457adc9`. I did not do this. The sha256 of all six files is identical before and after my runs, so this review applies to that commit byte for byte.

## Findings

### P1 (residual, not a regression): `sorted?` still hangs for a type that has a `seqFrom` method but does not declare `clojure.lang.Sorted`

Once a library predicate calls `sorted?` on such a value, the new guard lets it through and the same mutual recursion runs. About 1 GB of RSS per second while it runs.

The guard at `jolt-core/clojure/core/25-sorted.clj:227` only asks whether a `seqFrom` method exists. For a type that does not declare `Sorted`:

- `own-type-declares?` answers false (`host/chez/java/records-interop.ss:116-130`).
- The arm walk then reaches the interface arm (`host/chez/java/host-static-classes.ss:3583`, `value-host-tags` at `:3589`).
- Its wrapper (`:3546-3552`) runs every `__register-class!` predicate.
- The predicate calls `sorted?` again. The memo entry is written only after the verdict returns (`records-interop.ss:255-260`), so each re-entry recomputes.

A second route needs no class arm at all: the `__register-instance-check!` arm (`host-static-classes.ss:3471-3478`) is asked live, never memoized (`records-interop.ss:267`).

Each probe below was a fresh process with a working watchdog:

| Value | Library predicate | Result |
|---|---|---|
| defrecord, `seqFrom` from a user protocol | upstream-style, map-guarded, calls `sorted?` | HANG |
| plain defrecord, `seqFrom` added by `extend-type` | same upstream-style predicate | HANG |
| deftype with `seqFrom` | calls `sorted?` on any value | HANG |
| reify with `seqFrom` | calls `sorted?` on any value | HANG |
| defrecord with `seqFrom` | `__register-instance-check!` calling `sorted?` | HANG |
| deftype with `seqFrom` | class arm calling `sorted?`, asked through `(reversible? t)` | HANG |

- **Not hanging (safe):**
  - Types that declare `Sorted` with `seqFrom`, under every predicate I tried.
  - Proxies implementing `Sorted`, even with a predicate that calls `sorted?` on any value. They are decided first by the proxy arm (`host/chez/java/proxy.ss:170-178`), which loads after the interface arm (`host/chez/rt.ss:3889` vs `:3753`).
  - Values with no `seqFrom` method.
- **Callers that reach it:** `sorted?`, `reversible?` (`20-coll.clj:549`), `distinct` (`40-lazy.clj:43`), `subseq`/`rsubseq` (`25-sorted.clj:260`). Only the first two were run.
- **Reachability:** I found no instance. No non-`Sorted` `seqFrom` exists in the repo's own sources, and among local library sources only data.priority-map defines `seqFrom`, under `Sorted`.
- **Severity:** it needs two unusual conditions at once and is strictly narrower than the bug being fixed. Fork main hangs on all of these inputs and on every plain map. It should be closed by a follow-up, not by holding this commit.

### P2 (process, not a defect in the change): the brief's probe script has no working watchdog

- `/tmp/orch-probe/jolt-probe-row.scm` calls `(sleep 25)`, which raises `Exception in sleep: 25 is not a time record of type time-duration` on this Chez. The watchdog thread dies at startup.
- `(exit 3)` from a non-primordial thread is also ignored while the main thread is busy. I measured this on `(loop [] (recur))`: "HANG" printed, then the process ran until I killed it.
- The brief's "no hang" results stand, because those processes returned. Any hang observation relied on an external kill.
- I used my own copy with `(sleep (make-time 'time-duration 0 n))` and `_exit(3)`, plus an RSS guard: `/tmp/glm-review/glm-probe.scm` and `run-probe.sh`.
- The gate's own watchdog (`host/chez/run-unit.ss:71-84`) is correct.

### P3: the guard opens a JVM-faithfulness gap that is wider than the stated KNOWN GAP

On the JVM `sorted?` is `instance?`, so it is true for any type that declares `Sorted`, however incomplete. The new definition is false unless `seqFrom` is implemented. Verified on both sides (Clojure 1.12.5, JDK 17):

| Declares `Sorted`, no `seqFrom` | JVM `sorted?` | jolt `sorted?` | jolt `(instance? Sorted)` |
|---|---|---|---|
| defrecord | true | false | true |
| deftype | true | false | true |
| reify | true | false | true |
| proxy | not run | false | true |
| deftype with `comparator` and `seq` only | not run | false | true |

- On the JVM such a type throws `AbstractMethodError` on its first `Sorted` call. In jolt, `subseq` now throws `ClassCastException` from `assert-sorted` (`25-sorted.clj:259-261`).
- The comment at `25-sorted.clj:219-224` still says "Any deftype/reify declaring Sorted qualifies", which is no longer accurate.
- No real instance found: data.priority-map 1.1.0 and 1.2.0 implement `seqFrom`; 0.0.7 has its `Sorted` block commented out, so it is not `Sorted` on the JVM either.

### P3 (pre-existing, not from this diff): `reversible?`

- `reversible?` is `(or (vector? x) (sorted? x))` (`20-coll.clj:549`), which came in with the fork's `46d4090b`. On the JVM it is `instance? Reversible`.
- For a user `Sorted` type that is not `Reversible`, the JVM answers `false` (run) and jolt answers `true`.
- `(rseq x)` in jolt throws `ClassCastException` "cannot be cast to clojure.lang.Reversible". I did not run `rseq` on the JVM.

## Answers

**Q1 (reading, plus the hang probes).** The stated recursion is real, but it is not the only way back into library code. I enumerated all 23 registered arms plus the user arm; three routes exist:

1. Class-arm predicates through `value-host-tags` in the interface arm (`host-static-classes.ss:3583`). This is the stated one, and it fired for every non-tree value.
2. The `__register-instance-check!` arm, asked live (`:3471-3478`, `records-interop.ss:262-268`).
3. Class-arm predicates through `jolt-class-name` in the exact-own-class arm (`:4580-4587`), for values outside `class-fast-name` (`host/chez/java/host-class.ss:70-78`). Not reachable from `sorted?`.

Under the new guard, `sorted?` enters `instance-check` only for a deftype, defrecord or reify with a `seqFrom` method:

- One that declares `Sorted` is answered at `records-interop.ss:362` before any arm runs.
- A proxy is decided by its own arm.
- Only the P1 shape falls through to routes 1 and 2.

Why fork main hung at the `uis4-chk` row and not the registering row: registration probes the predicate against `class-fast-probes` (`host-class.ss:34-37`, `93-95`) before its `value-host-tags` arm is installed (`host-static-classes.ss:3559` precedes `3560`).

**Q2 (running).** Yes, a hang remains; see P1. Legit `Sorted` types, proxies and values without `seqFrom` do not hang under any predicate I tried.

**Q3 (running on both sides, plus reading).**

- **(a) JVM true, new false:** only the P3 class, a declaration of `Sorted` without `seqFrom`. Ordinary code can write it, but it is a broken type on the JVM. Low seriousness.
- **(b) JVM false, new true:** none reachable from ordinary code.
  - `extend-type` and `extend-protocol` of `clojure.lang.Sorted` throw `IllegalArgumentException` "interface clojure.lang.Sorted is not a protocol" on the JVM.
  - jolt accepts both silently, but `sorted?` stays false because `instance?` ignores extended impls (`host/chez/protocols.ss:354-378`). The silent acceptance is an older, unrelated divergence.
  - A protocol cannot extend a Java interface on either side: on the JVM `:extends` is ignored and a deftype adding `comparator` fails to compile; in jolt the deftype fails with a ClassCastException.
- **Proxies:** a full `Sorted` proxy answers `true`, as on the JVM.
- **Built-ins and host shims:** `{}`, `nil`, `[]`, scalars, `TreeMap`, `TreeSet`, `ConcurrentSkipListMap` all answer as on the JVM.

**Q4 (reading, plus a probe on both sides).** The claim is accurate.

- `empty` uses `jolt.host/jrec-method?` at `22-coll.clj:185`, `ifn?` at `22-coll.clj:245`, and the predicate is defined at `host/chez/protocols.ss:405-413`.
- `ifn?` carries the same tradeoff. For a type declaring `IFn` with no `invoke`, the JVM answers `ifn?` true for record, deftype and reify. jolt answers `[true false false]`.
  - The record's `true` comes from `(map? x)` at `:243`, not from the guard.
- The roles differ. In `empty` and `ifn?` the guard is the affirmative test. In `sorted?` it is a pre-filter that assumes "Sorted implies `seqFrom`", and that assumption is what fails.

**Q5 (running, plus a JVM reference).** The author is right, and both lines must be `tree-sorted?`.

- **If the lines said `sorted?`:** `(jolt.host/ref-get r :ops)` answers nil on a user type, so:
  - `find` on a `Sorted` record throws `NullPointerException` "Cannot invoke clojure.lang.IFn.invoke(Object, Object)"; the JVM gives `[:a 1]`.
  - `empty` on a `Sorted`-only deftype throws the same `NullPointerException`; the JVM gives `nil`.
- **With `tree-sorted?`**, jolt matches the JVM through the other arms:
  - `Sorted` record: `(find r :a)` is `[:a 1]`, `(find r :zz)` is `nil`, `(empty r)` throws `UnsupportedOperationException` "Can't create empty: user.GLMSR".
  - `Sorted`-only deftype: `find` throws `IllegalArgumentException` "find not supported on type: …", `empty` is `nil`.

**Q6 (reading and analysis only; I did not run remint).** The seed diff is exactly the two source edits plus their mechanical consequences.

- **Real changes:** four forms per prelude and none per image, identical in the Chez and Gambit halves:
  - `find-other` and `empty` now reference `tree-sorted?`.
  - `sorted?` has the new form.
  - `mk-bound-fn` changes only in the position-hashed class names of its two inner fns and its `:line`.
- **Everything else** is identical once digits outside strings are masked. Every numeric delta is the constant-counter shift (+4 Chez, +1 Gambit), apart from six `:line` metadata numbers per prelude that move by exactly 6.
- **Gambit host:** it has `jrec-method?` (`host/gambit/records-gambit.ss:2075`) and its seed carries the guard.
- **Idempotency:** `host/chez/remint.sh` copies only when `diff -q` says the new output equals the current seed, and the log says "converged after 1 pass(es)", so the seed is a byte-fixpoint.
- **Caveat:** the Gambit half is re-emitted each run and is not diffed by the script, so its idempotency rests on `gen-seed` being deterministic.

## Gates

- `make unit` ran twice: `unit gate: 2828/2828 passed`, exit 0, 152 s and 161 s. 329 suites, none with a pass/total mismatch; `dispatch-caches` 19/19. Full log: `/tmp/glm-review/unit.log`.
- I did not run `sorted-test.ss`.
- The worktree was left untouched.

## Notes for the follow-up

1. Close the P1 by making `sorted?` never enter the arm walk: a host predicate equivalent to `own-type-declares?` (`records-interop.ss:116-130`) plus the proxy's declared supers, or at minimum a re-entrancy flag. A declaration-based check would also close the P3 gap.
2. Correct the comment at `25-sorted.clj:219-224`, or record the gap in `test/conformance/known-divergences.edn` once tests may change.
3. Fix the probe script's watchdog.
4. Look at `reversible?` separately.

RECOMMENDATION: ACCEPT-WITH-NOTES — (1) residual P1 hang for a type with `seqFrom` but no declared `Sorted`, to be closed by a follow-up; (2) `Sorted`-without-`seqFrom` now answers `false` where the JVM answers `true`, for defrecord, deftype, reify, proxy and partial implementations; the comment at `25-sorted.clj:219-224` should say so; (3) the brief's probe watchdog does not work; (4) the commit reached the origin feature branch before this review finished, and `origin/main` is still `e457adc9`.