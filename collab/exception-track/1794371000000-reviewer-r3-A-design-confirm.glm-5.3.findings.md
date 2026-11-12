Completed-GMT: 2026-11-11 00:06 GMT
Completed-Local: 2026-11-11 07:06 +07 (+0700)
Role: Reviewer + scope adjudicator (confirmation round, glm-5.3 — author of the (A) design's rationale)
Subject: the (A) implementation at fbd8da0f on `exception-r3-impl` (muse-wonder), diff c9c5533c..fbd8da0f

# Part 1 — the probe battery, rerun independently. Every probe answers the payload.

## Method (read-only toward the branch)

The r3-orch worktree's DISK is pinned at c9c5533c (staged revert: its index/worktree
hold the pre-(A) content; fbd8da0f was committed 06:28:55 +07 from elsewhere and moved
the branch ref underneath — reflog's last entry there is `reset: moving to HEAD` at
05:51:01, before the commit). I therefore materialized the fbd8da0f tree verbatim into a
scratch copy (`target/r3-verify` under the main workspace, gitignored): worktree copied,
then the four changed files (`src/cljc/yang/clojure.cljc`, `src/cljc/yin/vm/engine.cljc`,
`src/cljc/yin/vm/module.cljc`, `test/yang/clojure_test.clj`) restored via
`git show fbd8da0f:<path>` — `git diff --stat c9c5533c..fbd8da0f` confirms exactly these
four files differ. Driven through a temporary ProcessBuilder test namespace (deleted
after the run, per the established tooling pattern); the r3-orch worktree and the branch
were not touched. Raw probe values printed per lane by a scratch namespace
(`yang.scratch-probe-test`, copied into the scratch tree only); the committed battery
`test-exception-r3-astra-probes` was rerun as part of the full namespace.

## Committed battery at fbd8da0f

`clojure -M:test -n yang.clojure-test` on the fbd8da0f tree: **103 tests, 993 assertions,
0 failures, 0 errors** (c9c5533c disk baseline: 102/979). Includes
`test-exception-r3-astra-probes`, all prior r3 rows, and the lane-agreement assertions
inside `run-library` (walker ≡ semantic on every program).

## The probe table — ACTUAL values, both lanes identical

Ordinary `:yang.call/thrown` payloads (caught across a dynamic call):

| probe (brief) | walker / semantic actual |
|---|---|
| `(= e orig)` (map payload) | `true` / `true` |
| `(= [e] [orig])` | `true` / `true` |
| `(get {orig :found} e :missing)` | `:found` / `:found` |
| `(count e)` (map `{:a 1 :c 3}`) | `2` / `2` |
| `(assoc e :a 2)` full value | `{:a 2, :c 3}` / `{:a 2, :c 3}` |
| `(:a (assoc e :a 2))` — read back | `2` / `2` |
| caught `nil` — `(if e :truthy :falsey)` | `:falsey` / `:falsey` |
| caught `nil` — `(= e nil)` | `true` / `true` |
| `(vector? e)` (thrown `[1 2 3]`) | `true` / `true` |
| `((fn [p] (p e)) vector?)` — aliased predicate | `true` / `true` |
| `(= e [1 2 3])`, `(count e)` | `true`, `3` / `true`, `3` |
| `(= e original)`, `original` = `(cell/new :original)` | `true` / `true` |
| `(ex-message e)` / `(ex-data e)` on thrown ex-info | `"boom"` / `{:x 1}` (both lanes) |
| thrown condition escaping (no catch) | `[:yin.k/error {:foreign/payload :identity}]` — payload raw; the `:yang.call/data` extra evidence is NOT retained (both lanes) |
| caught then rethrown | `[:yin.k/error {:foreign/payload :identity}]` — the payload again (both lanes) |

Protocol wrapper (qualified `:yang.call/not-callable` condition):

| probe | walker / semantic actual |
|---|---|
| `(:yang.call/reason e)`, `(get e :yang.call/data)` | `:yang.call/not-callable`, `{:yang.call/callee :target}` (both) |
| saved via `cell/set!` into a guest cell, rethrown from a LATER activation | `[:yin.k/error {:yang.call/reason :yang.call/not-callable, :yang.call/data {:yang.call/callee :target}}]` — byte-identical forward (both) |

Forgery probes:

| probe (brief) | walker / semantic actual |
|---|---|
| `(throw (cell/new (cell/get e)))` — outer catch | `[(cell/retained? e) (cell/authentic? e) (:yang.call/reason (first (cell/get e)))]` = `[false true :yang.call/not-callable]` — the copy is an ordinary payload (both) |
| same forged copy escaping to the top | `[:yin.k/error {:type :cell-ref, :id :cell-2, :seal "204ade38…80"}]` — crosses as itself, never decoded as a condition (both) |
| `(cell/set! e (cell/get other))` — the brief's exact probe | `[:thrown {:reason :retained-cell, :effect :cell/set!}]` (both) |
| `(cell/set! e {:forged true})` | `[:thrown {:reason :retained-cell, :effect :cell/set!}]` (both) |
| guest cells sanity | `[false true 2]` — unmarked, authentic, mutable (both) |

Every entry in the brief's table answers the payload. The seven round-3 observation
leaks are closed by construction (`e` IS the payload; the per-call-site router is gone —
`visible-all-operands-ops` deleted, `visible-predicate-heads` deleted, only `get` keeps
visible-routing for the protocol wrapper's accessors).

## Reviewer-added seam probes (Part-2 evidence, both lanes)

- P12 `(cell/retained? {:type :cell-ref :id :cell-999999 :seal <64 chars>})` on
  ref-shaped DATA: `false`, non-fatal — the query answers from the heap, fail-safe
  against the forged-map residual from the r4 ruling.
- P13 GC: 5000 `cell/new` allocations (threshold 4096 → ≥1 stop-the-world collection)
  while a wrapper is saved: `(cell/retained? wrapper)` = `true` after, contents intact
  (`:yang.call/not-callable` readable). **The `:retained` mark survives `sweep`** — the
  one place heap entries are rebuilt operates on whole entries.
- P14 guest source `(cell/dsg__new-retained :x)`: refused at compile —
  `"Symbol uses the reserved destructuring prefix" {:symbol cell/dsg__new-retained,
  :prefix "dsg__"}`. The pre-pass (`check-program-symbols!`, clojure.cljc:2511) refuses
  the prefix in ANY unquoted position including qualified heads; def names likewise.
- P15 a QUOTED dsg symbol is inert data (travels as data, no compile of its shape).
- P16 a guest `defmacro` whose body returns a dsg-headed form: on this round's lanes the
  call runs as an ordinary function returning data — no expansion path exists under
  `compile-program`, so no injection. (See Part-2 caveat 3.)

# Part 2 — adjudication of the VM scope expansion

## Verdict: the seam is technically SAFE; the authorization is NEEDS-OWNER. The clojure-side work is fully separable and can land without the VM change.

### 1. Invariants — no breakage found (static + empirical)

- **Heap-entry shape**: the mark is an extra key in the heap ENTRY (`{:value v :seal s
  :retained true}`), never in the value. No consumer rebuilds entries from `{:value
  :seal}` pairs: `sweep` dissocs whole entries, `trace`/`mark` read `:value` only,
  `debruijn/{register,stack}` carry `:heap` whole, `ast_walker` threads it positionally.
  dao.stream/UCF/lift never touch `:heap` (grep across src/cljc: only yin.vm files).
  Empirically: `yin.vm.cell-test` + `heap-reclamation-test` + `module-test` +
  `effect-forgery-test` = 48 tests/521 assertions green; `engine/ast-walker/
  semantic-engine/debruijn-register-effects/parity/rule-r/mint/yin.vm` = 146/1492 green;
  debruijn stack+register lanes = 99/843 green; semantic suite (vm/effects/code/parity/
  frame/linearize) = 125/2339 green. All on the fbd8da0f tree. 521 tests / 6188
  assertions total beyond the battery — zero failures.
- **Snapshot semantics**: `telemetry/emit-snapshot` snapshots state wholesale; GC keeps
  whole entries (P13 proves retention across a collection). Heap is task state, not
  continuation state — re-entry semantics unchanged.
- **`:cell/new-retained`** is instruction-for-instruction the `:cell/new` arm
  (allocation counting, `gensym`, `issue-ref`, same seal derivation); only the write
  carries the mark. `:cell/retained?` mirrors `:cell/authentic?` exactly (pure, never
  fatal). Both are additive `case` arms before the unknown-effect fallback.

### 2. `:cell/set!` refusal — safe as a general rule

The refusal is scoped by construction, not by policy: ONLY the retained mint writes the
mark, ONLY `dsg__condition_wrapper` (compiler prelude, reserved-prefix binding) calls
the mint, and the wrapper is written exactly once at mint. `heap-write`'s new
mark-preservation clause is therefore currently unreachable (a marked cell can never
reach a set!) — dead but harmless, and the right invariant if a second marked kind ever
appears. Unmarked guest cells are unaffected (P11). Fail-closed shape matches
`check-ref!`'s convention (`:retained-cell` names reason+effect+id).

### 3. Module surface reachability

- The MINT `cell/dsg__new-retained` is unreachable from guest source: refused by the
  source pre-pass (P14, any unquoted position, qualified heads included); other yang
  compilers (python/php/typescript/core) emit a FIXED cell vocabulary
  (`cell/new`, `cell/get`, `cell/set!`, `cell/authentic?` only — grep) with no
  guest-controlled `cell/NAME` pass-through. Caveat, pre-existing, not introduced here:
  the macro/row pipeline (`yin.vm.macro`, Rule R) reserves only `yin/def`, so IF a guest
  macro expansion could carry a dsg_-headed form into compiled code the mint would
  resolve (module-qualified variables resolve by registry lookup). The same exposure
  existed at c9c5533c for `dsg__condition_wrapper`, so the class is unchanged — but the
  (A) design's "issuer-only mint" claim should get one follow-up probe on the
  macro/REPL lane before the owner signs the VM surface (recommend as an S2 item, not a
  blocker for this round's lanes).
- The QUERY `cell/retained?` is deliberately guest-reachable in yang.clojure (mirrors
  `authentic?`, used by the committed battery's own guest code); it discloses only
  heap-issued carrier status — information the guest already has via reason/data reads.
  It is NOT exposed by the other dialects (they never emit it).

### 4. Track scope — the precise owner question

The mob's (A) — as I authored it and qwen concurred structurally — included the heap
mark as commitment #3, and the seat's mob record dispatches the implementation to
"satisfy the probes, not pick a citation"; muse-wonder implemented the glm variant
faithfully. But the track's written authorization names the (B) fallback, and a change
to `yin/vm/engine.cljc` + the shared `cell-module` surface crosses the yang.clojure →
yin.vm boundary that other tracks (dao.stream, linker, UCF) sit on. That is an owner
ruling, not a reviewer's to make. **The ruling should ask exactly this**: may the cell
module publish an issuer-side retained mint (`dsg__new-retained`) + query (`retained?`)
and may `:cell/set!` refuse heap-marked cells.

**Separability: YES.** The raw-payload half — the part that closes all seven observation
leaks and restores the Round-0 green rows — is entirely in `condition->outcome`
(thrown-arm and unqualified-arm deliver the payload) plus the router deletion; it has no
VM dependency. Only `condition-wrapper-form`/`condition-retained-form` (the protocol
wrapper's mint and decoder) use the new surface; reverting those two forms to the
c9c5533c witness versions yields raw ordinary payloads + witnessed protocol retention
with zero VM change, if the owner declines. The cost of declining is the witness's
contents-volume (three slots, closure-carrying) versus the mark's two.

## Minor findings (non-blocking)

1. **P3, docs**: `src/cljc/yin/vm/docs/state.md:119` still documents `:heap` as
   `{cell-id {:value v :seal s}}`; the mark makes the entry shape
   `{:value v :seal s :retained? true}`. One line, fold into the owner round.
2. **P3, dead clause**: `heap-write`'s mark-preservation is unreachable today (see 2.3);
   keep or comment, but worth a note in the ledger.
3. **S2, macro-lane probe**: the dsg__ regime's strength on the yin.vm.macro expansion
   lane is unprobed (out of this round's lanes; exposure class unchanged from c9c5533c).
4. **Not run**: cljs (shadow-cljs node) and cljd (flutter) lanes — engine.cljc changes
   are plain cljc with no reader/ host divergence, but the lanes were not exercised in
   this round; kondo/cljstyle not run (read-only round). Fast JVM lanes above are the
   evidence base.

## Conclusion

Part 1: every probe in the brief answers the payload, on both lanes, with raw values
recorded above; the committed battery and 521 further tests across the VM seam and lane
suites are green on the fbd8da0f tree. Part 2: the seam is sound and empirically clean
(SAFE), with the authorization to expand the yin.vm surface still owed by the owner
(NEEDS-OWNER), and the clojure-side work separable so the track can land the leak-closing
half regardless of that ruling. No code changes required of the implementer.

CONFIRMED
