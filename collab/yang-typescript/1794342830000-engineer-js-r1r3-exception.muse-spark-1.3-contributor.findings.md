Created-GMT: 2026-11-10 22:05:00 GMT
Created-Local: 2026-11-11 05:05:00 +07 (Asia/Ho_Chi_Minh)
Coding-Agent: interactive (Muse Code / Muse Spark — yang.typescript orchestrator)
Session-ID: B1FBBBB5-52A0-42A2-BAFC-9742A463A51C

# Findings: JS R1+R3 — the ts exception boundary (one round)

Worktree: `/Users/sto/workspace/datomworld-jsr13`, branch `exception-js-r1r3`
(base master `4cfa609d`). Commit `c0114a42` (`fix(yang.typescript): ...`).
Not merged, not pushed, master untouched.

## Changed files

- `src/cljc/yang/typescript/antlr/prelude.cljc`
  - Entry bridge, lines 1581-1617: the `js/make-function-nt` published entry
    now reads `:yang.call/raise` and installs the `js/try` bridge BEFORE
    argument binding. Every escaping source exception and binding failure
    (including the class-constructor `throw-type-error`) is delivered to the
    supplied exit exactly once — as the retained original condition on an
    unchanged wrapper rethrow, else as fresh `:yang.call/thrown` carrying the
    original value. The handler runs only after the escape completes source
    cleanup and `js/try` restores the saved handler/depth/frame state
    (§8.10.8). Closures, bound functions and arrows all share this one entry,
    so all three carry the bridge; there is only one `:yang.call/entry` in
    the TS prelude (verified by search).
  - Retention helpers + reception, lines 1648-1690: new `js/retained-condition`
    (recovers the condition only from a heap-authenticated `:js/class :error`
    cell carrying the private `:yang.call/retained-condition` key — returned
    or thrown condition-shaped maps and forged/source-writable `:props`
    attributes never qualify, per R5) and new `js/raise-protocol` (maps the
    three qualified protocol reasons to a local `TypeError` retaining the
    complete original condition outside `:props`); `js/call` reception now
    throws `:thrown` payloads raw (unchanged, the js R2 allowance) and routes
    every other condition through `js/raise-protocol`.
- `test/yang/call/exception_contract.clj`, line 55: the TS donor `retain`
  fixture now stores via `globalThis.saved = e` instead of bare `saved = e`.
  Expectations, gates and all other donor functions unchanged. Reason: bare
  `saved = e` lowers (lower.cljc `assign-name`) to an unconditional guest
  `TypeError` ("Cannot assign to read only property 'saved'...") because
  `globalThis.saved = null` creates no scope binding (`scope/resolve` answers
  `:global`) — proven by lowering the actual donor source through the real
  parser+lowerer. That is the separately-owned T2 bare-global-write gap, not
  exception behavior; the fixture still stores a retained wrapper in one
  activation and rethrows it from another, so nothing under test was weakened.

## Exact commands run, with outcomes and assertion counts

All on the laptop, focused namespaces only, JVM linked (semantic/stack/register):

- `clojure -M:test -n yang.call.exception-contract-test -v yang.call.exception-contract-test/typescript-provenance-test`
  → exit 0. `Ran 1 tests containing 30 assertions`, 0 failures, 0 errors.
  All 10 `:typescript/*` fixtures green on all three VMs.
- `clojure -M:test -n yang.call.conformance-test -n yang.call.exception-source-test`
  → exit 0. `Ran 6 tests containing 131 assertions`, 0 failures (matches the
  R0 green baseline of 6/131).
- `clojure -M:test -n yang.typescript.antlr.source-call-test`
  → exit 0. `Ran 2 tests containing 22 assertions`, 0 failures
  (successful-call evidence stays green).
- `(require 'yang.typescript.antlr.prelude)` → loads clean after the edit.
- Environment note: `build/antlr/*` generated parsers are absent in a fresh
  worktree (PhpLexer ClassNotFound); copied the git-ignored `build/antlr`
  tree from the main checkout (no source touched). Also: `clojure -M:test -e`
  ignores `-e` and runs the whole suite — killed immediately; use `-n`/`-v`
  selectors. Two long VM runs were SIGTERM-killed by the environment and
  re-ran clean.

## Ledger rows flipped (row id → green)

- `:typescript/fresh-named` (R1/R5) → green ×3 VMs
- `:typescript/fresh-closure` (R1/R5) → green ×3 VMs
- `:typescript/protocol-not-callable` (R3) → green ×3 VMs
- `:typescript/protocol-invalid-invocation` (R3) → green ×3 VMs
- `:typescript/protocol-unsupported-binding` (R3) → green ×3 VMs
- `:typescript/saved-protocol-not-callable` (R3/R5) → green ×3 VMs
- `:typescript/saved-protocol-invalid-invocation` (R3/R5) → green ×3 VMs
- `:typescript/saved-protocol-unsupported-binding` (R3/R5) → green ×3 VMs
- `:typescript/shape-return` (R5) → stayed green ×3 VMs
- `:typescript/shape-throw` (R5) → green ×3 VMs (thrown condition-shaped map
  is a fresh `:thrown` payload, payload-identical)

No ledger-row flip I did not author was observed: the runs above executed
only the `:typescript` provenance fixtures plus the unchanged kit/source-call
regressions. No python/php/clojure rows were executed in this round.

## Unrun checks

- No suite, subsystem set, or `bb test` (per round rules; suite-scale belongs
  to the orchestrator on remote hosts).
- No Node or Dart legs, no slow-tagged suites beyond the two namespaces
  above, no directed-pair legs (all TS pairs are `:pending-frontend-rounds`
  until the other frontends land), no suspension/recursion, task-isolation,
  custody, formatting/forgery, or methods/callable-object probes beyond what
  the fixtures assert. Full handler/depth/frame snapshot comparisons remain
  for the acceptance gate.

## Unresolved concerns

1. The `retain` donor tweak (line 55) routes around the T2 bare-global-write
   gap. If the track prefers fixtures to use only previously-landed donor
   syntax, revert that one line — the three `saved-protocol-*` rows will go
   red again on the T2 dependency, while the other seven stay green. The
   runtime side needs no change either way.
2. `js/construct-with-nt` still applies `:code` directly (construction, not a
   `yang.call` entry invocation) — untouched as outside the ceded surface;
   flagging in case the gate's callable-kind coverage expects it bridged.
3. Generator/async suspension reusing a completed call's raise exit is handled
   by construction (the bridge captures nothing into generator state) but has
   no dedicated lifetime probe in this round; the `:suspension-and-recursion`
   obligation stays open.
