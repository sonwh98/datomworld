Created-GMT: 2026-11-09 16:05 GMT
Created-Local: 2026-11-09 23:05 +0700
Coding-Agent: agy
Session-ID: pending (provider-generated)

# Task: attribute and fix the `Module link refused: absent` regression on master

Role: Yin.VM Runtime Engineer

Implementers:
- Model: gemini-3.8-flash-high | Assigned: 2026-11-09 23:05 +0700 | Status: active | Rationale: owner-designated implementation lane (routing-status 2026-11-09); linker/VM-interpreter work

## Worktree (own this tree, touch nothing else)

Work exclusively in `/Users/sto/workspace/datomworld-xlink` — a dedicated
worktree checked out at `origin/master` `a5a3ac0e`. The main checkout
(`/Users/sto/workspace/datomworld`) is on another branch with another seat's
in-flight work: never edit it, never run tests in it. ANTLR classes are
already generated in the worktree (`bb gen:antlr` cache hit); `mise trust`
and `eval "$(mise env -s bash)"` are needed in any fresh shell.

## The defect (reproduced, two hosts, three runs at b1b9abae; re-verify at a5a3ac0e)

The exception track's acceptance command
`clj -M:test -n yang.call.exception-contract-test -n yang.python.antlr.exception-call-test`
on master produces 24 rows that **cannot run at all** — every one errors with:

```
ERROR in (clojure-to-python-test) (engine.cljc:53)
[:clojure :python :binding] :register operational failure
expected: {:returned true, :value true}
  actual: clojure.lang.ExceptionInfo: Module link refused: absent
{:status :refused, :reason :absent, :name xlang.donor.clojure,
 :yin.link/id [:t0 81], :link-module xlang.donor.clojure}
```

The ledger of record (`test/yang/call/exception_r0_results.edn`, recorded at
`0fa3ff7a`) says these rows must be RED (assertion mismatch), not ERROR
(operational refusal). 159 failures / 39 errors is the recorded baseline;
master yields 150 F / 63 E. The affected rows all name the Clojure donor
`xlang.donor.clojure`: the 8 Clojure→Python pair rows, 6 PHP provenance rows,
6 Python→PHP pair rows.

## G0 reproduction (the ONLY test command you may run)

```sh
cd /Users/sto/workspace/datomworld-xlink
clj -M:test -n yang.python.antlr.exception-call-test \
    -v yang.python.antlr.exception-call-test/clojure-to-python-test
```

One deftest, ~10–20 min wall (its legs are e2e). This is the owner's
failure-reproduction rule: reproduce and iterate with ONLY the failing test,
never a suite, never a namespace, never `bb test`. Do not run the full
acceptance command — the orchestrator owns that verification later.

## Diagnostic state (orchestrator's findings so far — verify, don't trust)

1. The refusal shape `{:status :refused, :reason :absent, :name
   xlang.donor.clojure}` (with NO `:address` key) matches exactly two
   production sites:
   - `src/cljc/yin/vm/linker/interpreter.cljc:628` — the serving
     composition's name-env lookup missed the requested name
     (`(get (:name-env source) name)` → nil).
   - `src/cljc/yin/vm/linker.cljc:1556` (`root-address`) — a by-name request
     reached `link-manifest` and no name environment was observed.
2. The kit path: `test/yang/call/conformance.clj` `publish-donor!` (line 190)
   publishes the donor spec via `h/publish-guest-module!` into the shared
   TypeScript-harness content store; `link-source` (line 266) builds the
   serving composition with `{module address}` in `:name-env`;
   `run-exception-program` (line 442) serves that composition. If the
   name-env entry is present but the link still refuses, the miss is in
   what the caller actually requires by name vs what `link-source` maps.
3. The problem statement's leading hypothesis (NOT confirmed): the Python
   manifest/publication pin moved after the ledger was recorded —
   `21350ec5` changed the pinned manifest root, `cb2cbe11`/`4f772d61`
   changed REPL index/DHT publication. Alternative hypothesis: a registry
   admission regression in `clj-prelude/admit` interacting with the cc1
   `yin.vm.macro` changes (`f440a2fb`, `ba2deb73`) that landed in the same
   window.
4. Bisect window: `0fa3ff7a` (ledger green-of-record) → `b1b9abae`. If you
   bisect, use `git worktree add` per step in YOUR OWN scratch directories
   under /tmp, run the single -v command above at each step, and stop at the
   first causal commit. Prefer reading the link path first; bisect only if
   reading does not converge.

## Acceptance criteria

- **Attribution**: name the causal commit (or prove it is not a commit-level
  regression but a latent defect exposed by one) with evidence: the exact
  mechanism by which `xlang.donor.clojure` fails to resolve, traced through
  `publish-donor!` → store → `link-source` → serve → `root-address`/name-env.
- **Fix**: a minimal fix in the owning subsystem. Do NOT touch
  `test/yang/call/exception_r0_results.edn` or any ledger; the baseline
  belongs to the exception track. Do NOT invert or weaken any assertion.
- **Verification**: the single -v command above must show the
  Clojure→Python pair rows back at their ledger state (red-as-recorded, no
  `Module link refused` errors) — paste the exact output. If your fix is
  correct, the same mechanism is fixed for the PHP provenance and
  Python→PHP rows; you may ALSO run
  `clj -M:test -n yang.python.antlr.exception-call-test -v yang.python.antlr.exception-call-test/python-to-php-test`
  as a second confirmation point, but nothing broader.
- **Report**: write your findings to
  `/Users/sto/workspace/datomworld/collab/exception-track/1794241000000-engineer-donor-link-absent.gemini-3.8-flash-high.findings.md`
  (absolute path — the worktree has no collab/). Include: causal commit +
  mechanism, changed files with rationale, exact test output tails, and any
  mechanism you ruled out with evidence.

## Constraints

- File ownership: `src/cljc/yin/vm/linker/**`, `src/cljc/yin/vm/engine.cljc`,
  `src/cljc/yang/**`, and kit files under `test/yang/call/` and
  `test/yang/python/antlr/` ONLY if the defect is proven to be in the kit's
  own publish/serve contract — say so explicitly in the findings before
  editing kit files.
- No commit: leave the fix uncommitted in the worktree; the orchestrator
  stages, reviews, and commits.
- If the correct fix is a design choice between competing approaches (e.g.
  re-pinning a manifest vs changing the linker's resolution), STOP, write
  the findings with both options and your recommendation, and end the turn —
  the routing decision belongs to an architect, not this round.
- Preserve CESK and execution-parity invariants. The three VM backends must
  agree.

Begin the final response exactly with:
Completed-GMT: <YYYY-MM-DD HH:MM:SS GMT>
Completed-Local: <YYYY-MM-DD HH:MM:SS local-timezone-name>

Report changed files, exact test/check outcomes, unresolved concerns, and any
incomplete work. Do not claim edits or tests that did not occur.
