Created-GMT: 2026-11-27 07:41:55 GMT
Created-Local: 2026-11-27 14:41:55 +0700 (Asia/Ho_Chi_Minh)
Coding-Agent: glm
Session-ID: zcode-subagent-review-fork-sorted-fix
# Task: review the fix for the `unit` gate hang on jolt fork main

Role: Review (adversarial; the author of the fix is agy gemini-4-argon-high, a different family)
Implementers:
- Model: glm-5.3-flash | Assigned: 2026-11-27 14:41:55 +0700 | Status: active | Rationale: cross-family review before the change is pushed to the owner's fork main

You are reviewing a small, uncommitted change in the jolt fork worktree
`/Users/sto/workspace/jolt-syncfix` (branch
`fix/sync-restore-tree-sorted-dispatch`, based on fork `main` `e457adc9`).
READ-ONLY on sources. You may run the Chez gate and probes described below.
Do not edit, commit or push anything. Do not touch any other worktree.

## Background

The fork's own commit `46d4090b` makes `sorted?` honor user types:
`(defn sorted? [x] (or (tree-sorted? x) (instance-check clojure.lang.Sorted x)))`.
Upstream `jolt-lang/jolt` commit `2573c180` (inside the range the fork synced
on 2026-11-26) changed one row of `test/chez/unit.edn` so that a registered
library class-arm predicate is
`(fn [x] (and (map? x) (not (sorted? x)) (= :uclass-probe (:uclass-kind x)) false))`.
After that row runs, the first `instance?` on a map (the `uis4-chk` row) never
returns. The author's diagnosis: an unbounded mutual recursion between the
fork's `sorted?` and the arm walk in `instance-check`
(`host/chez/java/records-interop.ss` `instance-kind-verdict`,
`host/chez/java/host-static-classes.ss` `value-host-tags`). Upstream's
`sorted?` never calls `instance-check`, so upstream has no cycle. The
orchestrator confirmed a two-row reproduction in a fresh process: the arm row
then the `uis4-chk` row hangs; the arm row then each of the three rows between
them returns normally.

The owner's constraint, verbatim: "you have permission to change my fork as
long as the unit tests in my fork are invariant". So no test may change.

## The change under review (`git -C /Users/sto/workspace/jolt-syncfix diff`)

1. `jolt-core/clojure/core/25-sorted.clj`:
       (defn sorted? [x]
         (or (tree-sorted? x)
             (and (jolt.host/jrec-method? x "seqFrom")
                  (instance-check clojure.lang.Sorted x))))
   plus a comment explaining the guard.
2. `jolt-core/clojure/core/22-coll.clj`: two lines restored from the fork's
   own commit, which the sync's conflict resolution had dropped: `(sorted? m)`
   back to `(tree-sorted? m)` in `find-other`, and `(sorted? coll)` back to
   `(tree-sorted? coll)` in `empty`.
3. Re-minted seeds: `host/chez/seed/{prelude,image}.ss`,
   `host/gambit/seed/{prelude,image}.ss`.

## Evidence already in hand (orchestrator's own runs on this tree)

- `make unit`: `unit gate: 2828/2828 passed`, 175 s. Pure upstream at the
  synced commit: 2828/2828 in 192 s. Unfixed fork `main`: hangs at the
  `uis4-chk` row.
- Probes, each in a fresh process:
  - `[(sorted? {}) (sorted? []) (sorted? nil) (sorted? (sorted-map 1 2)) (sorted? (sorted-set 1)) (sorted? 5) (sorted? "s")]`
    is `[false false false true true false false]`.
  - a deftype implementing `clojure.lang.Sorted`: `[(sorted? t) (instance? clojure.lang.Sorted t)]`
    is `[true true]`.
  - a reify implementing `Sorted`: `(sorted? r)` is true.
  - a deftype with a `seqFrom` protocol method but not `Sorted`: false.
  - a plain defrecord: `[(sorted? r) (map? r)]` is `[false true]`.
  - KNOWN GAP: a defrecord that declares `clojure.lang.Sorted` and
    implements none of its methods gives
    `[(sorted? r) (instance? clojure.lang.Sorted r) (map? r)]` =
    `[false true true]`. On the JVM `sorted?` is
    `(instance? clojure.lang.Sorted x)`, so the JVM answers true here.
  - the two-row reproduction in one expression returns `true`, no hang.
  - an arm whose predicate calls `sorted?`, then `instance?` on a defrecord
    (a map-like value): `[true false]`, no hang.

## Review questions. Answer each explicitly, with file and line evidence.

Q1 Root cause. Read `instance-check`, `instance-kind-entry`,
   `instance-kind-verdict`, `instance-arms-until` in `records-interop.ss`
   and `value-host-tags` and the arm registration path in
   `host-static-classes.ss` and `host-class.ss`. Is the stated recursion
   real and is it the only cycle between `sorted?` and the arm walk?
Q2 Recursion freedom. Try to construct any OTHER reachable input for which
   the NEW `sorted?` still re-enters the arm walk in a way that can recurse:
   a value with a `seqFrom` method whose own type does not declare
   `clojure.lang.Sorted`, combined with an arm predicate that calls `sorted?`
   on it. Test it. If you find a hang, that is a P1.
Q3 Semantic faithfulness. On the JVM `sorted?` is exactly
   `(instance? clojure.lang.Sorted x)`. Enumerate the classes of value where
   the new definition and the JVM differ: (a) JVM true, new false; (b) JVM
   false, new true. For each, say whether it is reachable from ordinary
   Clojure code and how serious it is. The known vacuous-declaration gap is
   one of them; check for others, including proxies and types whose `Sorted`
   comes from `extend-type` / `extend-protocol` (a protocol cannot extend a
   Java interface, so say what jolt does).
Q4 The guard's precedent. The author says the guard follows the pattern
   `empty` and `ifn?` use with `jolt.host/jrec-method?`
   (`22-coll.clj`, `host/chez/protocols.ss`). Verify that claim by reading
   them, and say whether jolt's `ifn?` carries the same vacuous-declaration
   tradeoff (a type declaring `IFn` without `invoke`).
Q5 The two restored lines in `22-coll.clj`. The author says they must be
   `tree-sorted?` because both branches dereference `(jolt.host/ref-get m :ops)`,
   which only jolt's native sorted collections have. Verify: with the fork's
   `sorted?` semantics, what would `find` and `empty` do on a user type that
   implements `Sorted` if those lines used `sorted?`? And with `tree-sorted?`,
   do `find` and `empty` on such a user type still give the JVM's answer
   through the other arms? Test with a deftype implementing `Sorted`.
Q6 The seeds. Confirm the seed diff is consistent with the two source edits
   and nothing else: after the source edits, a second
   `sh host/chez/remint.sh` (with `JOLT_CHEZ` set) must change nothing.

## How to run things

    export JOLT_CHEZ=/Users/sto/.local/share/mise/installs/chezscheme/10.4.1/bin/scheme
    cd /Users/sto/workspace/jolt-syncfix
    make CHEZ="$JOLT_CHEZ" unit          # about 3 minutes on this tree

For a single expression in a fresh process (25 s watchdog), the probe script
is `/tmp/orch-probe/jolt-probe-row.scm`; if it is missing, recreate it with
this content and run it from the worktree root as
`"$JOLT_CHEZ" --script <file> '<expr>'`:

    (import (chezscheme))
    (load "host/chez/run-gate-harness.ss")
    (load "host/chez/loader.ss")
    (load "host/chez/java/ffi.ss")
    (set-source-roots! ldr-install-roots)
    (load-namespace "jolt.time.base")
    (load "host/chez/gate-private-tmpdir.ss")
    (define src (list-ref (command-line) 1))
    (fork-thread (lambda () (sleep 25) (printf "HANG (>25s): ~a\n" src) (flush-output-port) (exit 3)))
    (printf "OK: ~a => ~a\n" src (jolt-repl-str (jolt-compile-eval (string-append "(do " src ")") "user")))
    (flush-output-port)
    (exit 0)

Run every command in the foreground with an explicit timeout. Do not run
`make unit` more than twice. Do NOT run `make remint` or any command that
writes to the worktree; for Q6 reason from the diff and the existing log
`/tmp/orch-probe/remint2.log` instead.

## Deliverable

Your final response, beginning with `Completed-GMT`, `Completed-Local`,
`Coding-Agent`, `Session-ID`. Findings P0 to P3, each with file and line
evidence and the probe or code reading that supports it. Then your answers to
Q1 to Q6. End with one line: `RECOMMENDATION: ACCEPT` or
`RECOMMENDATION: ACCEPT-WITH-NOTES` (list the notes) or `RECOMMENDATION:
REJECT` (the blocking findings). State explicitly which questions you answered
by running something and which by reading only.
