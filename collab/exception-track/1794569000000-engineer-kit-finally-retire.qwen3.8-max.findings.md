Created-GMT: 2026-11-13 02:20:00 GMT
Created-Local: 2026-11-13 09:20:00 +07 (+0700)
Completed-GMT: 2026-11-13 10:42:50 GMT
Completed-Local: 2026-11-13 17:42:50 +07 (+0700)
Coding-Agent: qwen
Session-ID: 7f449035-53bf-4109-933a-25b49bce2fa8
Role: Engineer — KIT ROUND: retire the clojure finally-refusal per the owner's ruling
Branch: `exception-kit-finally`, base `96633aaf` (cut from `origin/master`)

# BLOCKED: no shell in this session — the kit edits are complete and statically verified, but nothing was executed, committed, or pushed

---

## 0. Required gate → status

Every gate the dispatch names requires process execution. This session has none.

| Required gate | Status |
|---|---|
| `clj -M:test -n yang.call.exception-source-test` | **NOT RUN — impossible** |
| Single new pair leg (`-v` targeting) | **NOT RUN — impossible**, and not addressable even with a shell (F5) |
| `clj-kondo` on changed files, 0/0 | **NOT RUN — impossible** |
| `cljstyle` (repo has `.cljstyle`) | **NOT RUN — impossible** |
| `git commit` + `git push exception-kit-finally` | **NOT RUN — impossible** |
| `git status` / `git diff` / `git rev-parse` | **NOT RUN — impossible**; branch/SHA below read from `.git` files |

Capability probe, verbatim: `tool_search "select:run_shell_command,monitor"` → `Not found:
run_shell_command, monitor`. A keyword search for shell/exec/bash returned only `enter_worktree`,
`task_stop`, `web_fetch`, `computer_use__list_apps`, `exit_worktree`, `send_message`.
`computer_use__list_apps` → `Computer Use install declined by user.` A `general-purpose` subagent
was dispatched and independently reported the same absence, so the gap is session-wide, not
parent-only. `web_fetch` works (used for the `Completed-*` clock).

Substituted for running: exhaustive reading. Every consumer of the changed surface was enumerated
by grep, and the two structural invariants that a compiler would have caught (paren balance, and
the derived-obligation pins) were checked by anchored regex and by hand. Those checks are listed in
§3 and are **not** a substitute for the gate.

---

## 1. Headline finding — the port is not in this branch (P1, blocks the shiva run)

The dispatch's item 3 states the finally capability "already landed with the port." **It has not
landed in this worktree.** `exception-kit-finally` is cut from `origin/master` @ `96633aaf`, and the
clojure convergence port (`exception-r3-impl` @ `854b74c3` / `d4e31b74`) is still unmerged — the
ledger's own last line says `port READY; merge gated on P1-1`, i.e. gated on the very ruling this
round implements.

Three independent verifications:

1. `src/cljc/yang/clojure.cljc:1126-1137` still carries the pre-port `try` lowering. The guard is
   `(= 1 (count clauses))`, `(= 'catch (first clause))`, class ∈ `#{Exception java.lang.Exception
   clojure.lang.ExceptionInfo}`, `(symbol? binding)`, and the rejection message is verbatim
   `"Portable try requires one Exception/ExceptionInfo catch and no finally"`.
2. `src/cljc/yang/clojure/prelude.cljc` exists and has **zero** matches for `%capture`,
   `clj.rt/ctx`, `clj/try`, `clj/raise`, or `provenance` — the entire carrier scheme the port
   introduced is absent.
3. `cell/dsg__new-provenance` / `cell/dsg__provenance` appear only in
   `yang/python/antlr/prelude.cljc` (:205, :918, :1054) and `yang/php/antlr/prelude.cljc` (:98,
   :4738, :4756, :4773, :4783). Nothing clojure-side.

**Consequence.** Run against this branch alone and the new legs do not fail at runtime — they fail
at **compile** time, inside `lower`, because the frontend still rejects `finally`. That takes out
more than the three new rows:

- `clojure-finally-admitted-test` (new) — red, `(lower ...)` throws.
- `all-directed-pair-sources-compile-test` — red for all three new `[:donor :clojure
  :finally-replacement]` rows, and red for the three clojure-caller **batch** legs, since
  `batch-caller-source` concatenates every scenario's source into one unit.
- `clojure-finally-replacement-leg-test` (new) and `python-to-clojure-test` — red via
  `kit/error-results`, i.e. reported as `:error`, not as a divergent value.

The dispatch pre-authorises a red as a FINDING and guesses at "§8.10.8 cleanup-before-transfer
interacting with foreign unwinding." **That diagnosis does not apply here.** A §8.10.8 finding is a
runtime-ordering divergence; this is a compile-time rejection with the message quoted above. Please
do not let a shiva run on this branch alone be read as evidence about cleanup ordering.

**Required before shiva:** measure the kit round and the port together — either merge
`exception-r3-impl` @ `854b74c3` into this branch, or rebase this branch onto it. The ruling's
consequence 4 orders it as "kit round landing + shiva, then the PORT merge notice"; if shiva runs
before the port merges, it must run a composed tree, not this branch.

I did **not** merge or rebase. That touches `src/`, which item 3 forbids, and it is an
orchestrator-level composition decision, not an engineer's.

---

## 2. What changed

Three kit files, plus the two governance records the ruling's consequences 2 and 4 require. No
`src/` file and no frontend was touched.

### `test/yang/call/exception_contract.clj`

- `pair-matrix` (:158): the `:when (not (and (= scenario :finally-replacement) (= caller
  :clojure)))` predicate is **removed**. Three rows become live — see F4 for their correct names.
- `pending-obligations` (:171): the `:clojure-finally-refusal` row is **removed, not flipped** to
  `:execution :supports`. Justification in §4.

### `test/yang/call/exception_legs.clj` — not in the dispatch's file list; see F2

- The generate-time guard at :106-111, `(when (= frontend :clojure) (throw (ex-info "Clojure
  finally remains an explicit source refusal" ...)))`, is **removed**, and the enclosing `(do ...)`
  wrapper dropped with it.
- `:finally-replacement` gains its three `:clojure` arms: `(def replacement (cell/new
  :replacement))`, `replacement_payload` → `replacement`, and the probe `(try (donor_raise)
  (finally (throw replacement)))`.
- The `caller-source` comment at :58-60 loses its now-false third sentence ("Clojure uses its
  admitted catch surface, and does not acquire finally syntax") and records that `:round-trip`'s
  clojure arm still cleans up through `catch` — see F7.

### `test/yang/call/exception_source_test.clj`

`clojure-restricted-catch-surface-preserved-test` (:37) is reduced to the one refusal that is
**still live**, `(try 1 (catch Throwable e 2))` — see F8. Two deftests are added:

- `clojure-finally-admitted-test` (:44) — `(is (map? (lower :clojure "(try 1 (finally 2))" [])))`.
- `^:slow clojure-finally-replacement-leg-test` (:53) — the single-leg local instrument, a verbatim
  structural copy of `python_exception_test.clj:27-32`'s `check-leg`, pinned to `[:python :clojure
  :finally-replacement]` and asserting `legs/expected` on each of the three vector VMs.

### Governance records (append-only)

- `1794960000000-owner-ruling-exception-uniformity-v2.md` — a `## SUPERSESSION (append-only)`
  section at EOF. It supersedes **two** clauses, not one: acceptance-gate §3 (the "PRESERVE
  clojure's rejection tests" duty) and the `no finally` half of **R4**. R4's single-catch /
  catch-class half is explicitly *not* superseded. Nothing in the original body was edited.
- `acceptance-ledger.md` — a `## Round deltas — 2026-11-13 (the kit round ...)` section at EOF,
  recording the retirement against the `1794556000000` packet (ruling consequence 2), claiming **no
  rows**, and carrying the F1 blocker. The pre-existing `MERGE STATE` line is left as filed rather
  than rewritten, since resolving it is the steward's.

Note: neither `1794556000000-owner-decision-finally-refusal-row.md` nor the dispatch prompt itself
is present in this worktree's `collab/exception-track/` — both postdate `96633aaf`. The
cross-references resolve only after a merge.

---

## 3. Static verification actually performed

- **Paren balance**, by anchored regex rather than by eye (one alternative per closing line, exact
  count, `$`-anchored). `exception_source_test.clj` — all four matched: `e 2))" []))))`  (:41),
  `(finally 2))" []))))`  (:49), `(is (some? leg))` (:59), `(str backend)))))`  (:61).
  `exception_legs.clj` — `(throw replacement)))")))`  matched at :123 with exactly three code-level
  closes (`case`, `probe`, `str`), and `:retained-lifetime` following at the same indent proves the
  outer `(case scenario` is still open.
- **`conformance_test.clj/mechanically-derived-obligations-test` survives.** All four pins checked
  by hand against the edited data: the 12-direction count is untouched (the removed predicate
  filtered on *scenario*, not direction); the 6 `:jvm-linked` directions are untouched; `every?`
  over `:gates`/`:frontends` holds with one fewer row; and `(get (ledger-seed) :clojure)` still
  yields `#{r1 r2 r3 r4 r5}` because `::r4` also comes from
  `:clojure-private-public-equivalence`. **This was the one pin that removing the row could have
  broken, and it does not.**
- **No other consumer of the removed vocabulary.** Repo-wide, `clojure-finally-refusal` and
  `preserve-source-refusal` had exactly one hit each, both in `exception_contract.clj`; the only
  remaining hit is my explanatory comment. `src/` had zero hits for either, and zero for
  `restricted-catch-surface` — the refusal was never a production-code concern.
- **No pinned row count anywhere.** Every `pair-matrix` consumer was read: `exception_legs.clj:248`
  (`run-pair` filter), `exception_source_test.clj:26,31`, `python_exception_test.clj:28`,
  `php_exception_test.clj:29`, `conformance_test.clj:63,66,68`. None asserts a total row count, so
  three new rows break nothing structurally.
- **Name-collision check on the new clojure source.** `batch-caller-source` concatenates all eight
  scenarios into one unit, so a duplicate top-level name would be a new failure mode. The clojure
  caller batch now defines `caught`, `saved`, `cleaned`, `done`, `replacement`, `replacement_payload`
  — no duplicates, and none collides with the donor exports projected in (`donor_raise`, `payload`,
  `success`, `needs_argument`). The `\bprobe\b` rename in `batch-caller-source` touches only `probe`.
- **`exception_r0_results.edn` deliberately untouched** — it is a frozen R0 snapshot; new legs do not
  rewrite history.
- **What I could not check:** kondo, cljstyle, and whether the port's `finally` lowering accepts a
  `finally` with no `catch` at all (the form both the source test and the new probe use). Real
  Clojure accepts it; the port is assumed to. That assumption is untested here and is the first
  thing a green run would confirm.

---

## 4. The choice the dispatch left open, and why

> `:execution :supports` shape or remove-and-let-the-new-pair-legs-carry-it; your choice, justify.

**Removed.** Three reasons, in order of weight:

1. **The DECIDED section says remove.** Consequence 1 reads "remove `:clojure-finally-refusal` and
   the pair-matrix exclusion". `:execution :supports` appears only in the RECOMMENDATION preamble,
   which the owner then answered with the single word "retire".
2. **A `:supports` row would be permanently open.** `open-gates` closes a row only when
   `(get statuses (:id %))` is `:green`. Nothing reports a status for a `pending-obligations` id —
   the reported ids are fixture and pair-leg ids — so flipping the row would leave `::r4` open for
   clojure forever, silently widening the acceptance gate that `ledger-seed` derives.
3. **`:supports` is not vocabulary this file has.** The four `:execution` values in use are
   `:jvm-linked`, `:pending-frontend-rounds`, `:pending-round-4`, `:preserve-source-refusal`; all
   describe *how or whether* a row is executed, and none is consumed as a dispatch key. Introducing
   a fifth value with no reader, in a vector whose docstring says "Round 0 does not claim the wider
   acceptance gate", would misfile a discharged obligation as a pending one.

The obligation is genuinely carried now: `[:python :clojure :finally-replacement]` is a
`:jvm-linked` pair-matrix row with gates `#{::r1 ::r2}`, which `ledger-seed` and `open-gates`
already fold into clojure's set. Removing the pending row loses no gate coverage. The retirement is
preserved as a comment at the removal site so a future seat does not "restore" it, and as the
append-only v2 supersession.

---

## 5. Findings

**F1 (P1, blocker) — the port is not in this branch.** §1. Kit and port must be measured together.

**F2 (P1) — the dispatch's two-file list is incomplete; `exception_legs.clj` had to change.**
Item 1 names only `exception_contract.clj`, and item 3 forbids only `src/` and the clojure frontend.
But `exception_legs.clj:106-111` held an *active generate-time* `throw` for clojure +
`:finally-replacement`. Removing the matrix exclusion without removing that `throw` does not produce
three red legs — it produces a **pair-wide** failure: `batch-caller-source` is called inside
`run-pair`'s `try`, so the `throw` is caught and mapped by `kit/error-results` across *every* leg of
*every* clojure-caller pair (all 8 scenarios × 3 directions), and
`all-directed-pair-sources-compile-test` calls `caller-source` with no `try` at all and would error
outright. The owner's ruling is consistent with this: its RECOMMENDATION names "the three kit
locations above", explicitly including `exception_legs.clj:60,106-109`. I treated the ruling as the
authority and the two-file list as an omission. If sol disagrees, reverting `exception_legs.clj`
requires reinstating the matrix exclusion too — the two are not independently revertible.

**F3 (P2) — the dispatch's "result 2" is not Clojure semantics, and I did not pin it.** Item 2 asks
that `(try 1 (finally 2))` "COMPILE AND RUN, result 2, cleanup executed". In Clojure, `finally` is
evaluated for effect and its value discarded: `(try 1 (finally 2))` ⇒ **1**. Pinning 2 would encode
exactly the dialect divergence the owner's principle forbids ("a dialect's surface derives from its
source language"), and `exception_contract.clj`'s own docstring is explicit that "the ruling, rather
than today's runtime output, determines `:expected`". The DECIDED section asks only that it "COMPILE
and RUN (finally executes on normal exit AND unwind)" and names no value, so I followed that. I did
not pin `1` either, because this ns is a generator check and I had no way to confirm what
`kit/call`'s clojure render (`yang.clojure/output` = `pr-str`) yields — see F9.

**F4 (P2) — the dispatch has the new legs' direction inverted.** Item 1 calls them "clojure→python,
clojure→php, clojure→typescript". The removed predicate filters on `caller = :clojure`, and this
kit's arrow is **donor→caller** (`[:clojure :python ...]` is rendered `clojure-to-python-test`, and
the ledger's green `clojure→python finally-replacement` row at :180-182 is clojure-as-*donor*, which
neither guard ever touched). The three rows that actually become live are:

- `[:python :clojure :finally-replacement]` — `python→clojure`
- `[:php :clojure :finally-replacement]` — `php→clojure`
- `[:typescript :clojure :finally-replacement]` — `typescript→clojure`

Worth correcting before shiva, or the orchestrator will look for rows under the wrong names and may
conclude nothing changed. "×3 backends" (`:register`, `:semantic`, `:stack`) is correct.

**F5 (P2) — `-v` targeting of a single pair leg is not possible; the dispatch's fallback command
does not exist.** `yang.call.exception-contract` is a pure data namespace with **zero** `deftest`
forms, so `clojure -M:test -n yang.call.exception-contract-test -v .../...pair...` has no target —
and `yang.call.exception-contract-test` is a *different* ns whose 9 deftests are provenance and
forgery pins that never touch `pair-matrix`. The pair legs are `testing` blocks inside pair-scale
deftests in `yang.python.antlr.exception-call-test`, so they are filterable in report output but not
addressable by `-v`. The dispatch said to "check available deftest names first" — checked, and the
answer is that the mechanism isn't there. Hence the new `clojure-finally-replacement-leg-test`,
which gives a genuine single-leg local instrument through `legs/run-leg`.

**F6 (P2) — two of the three new rows have no executing deftest anywhere.** Only
`python-to-clojure-test` (`check-pair :python :clojure`) runs a clojure caller. There is no
`php-to-clojure-test` or `typescript-to-clojure-test` in the repo. So `[:php :clojure
:finally-replacement]` and `[:typescript :clojure :finally-replacement]` are now matrix rows and are
compiled by `all-directed-pair-sources-compile-test`, but **nothing executes them** — they will not
produce ledger rows on shiva. Both are `:execution :pending-frontend-rounds`, so this is consistent
with their stated status, but the dispatch's expectation that all three directions get measured is
not satisfiable with the current test inventory. Adding those two pair deftests is a separate
decision and I did not make it.

**F7 (P3) — `:round-trip`'s clojure arm still emulates `finally` with `catch` + rethrow.** It reads
`(try (donor_raise) (catch Exception e (cell/set! cleaned (+ (cell/get cleaned) 1)) (throw e)))`
where python/ts/php all use a real `finally`. That emulation exists *only* because `finally` was
refused. I deliberately did **not** convert it: `:round-trip` is an existing leg with ledger rows,
its `expected` would not change, and rewriting a possibly-green leg's source is beyond this dispatch
and risks exactly the "locally green, zero rows moved" trap this track keeps hitting. It belongs to
the queued dialect-definition round (ruling consequence 3), and the `caller-source` comment now says
so in place.

**F8 (P3) — `(catch Throwable e 2)` is still refused, and I left that assertion alone.** The
frontend's allowed class set is `#{Exception java.lang.Exception clojure.lang.ExceptionInfo}`. The
ruling retires the *finally* refusal only; v2 R4's single-catch/catch-class restriction is not
superseded, `pair-matrix`'s `:catch-surface :single-Exception` for a clojure caller is unchanged,
and `exception_legs.clj:87-88`'s "Clojure admits exactly this restricted catch" is still true. So
`clojure-restricted-catch-surface-preserved-test` keeps that one assertion and its name stays
accurate. Worth an explicit owner word at the dialect-definition round, since "surface derives from
the source language" would eventually admit `Throwable`.

**F9 (P3) — `finally` on NORMAL exit is not runtime-covered anywhere.** The ruling asks for "finally
executes on normal exit AND unwind". The unwind half is now covered by the new
`:finally-replacement` leg (a `finally` that raises, replacing the in-flight condition). The
normal-exit half is covered only by the compile assertion. I considered adding it via `kit/call`
but declined: `kit/call` has **zero** uses anywhere in `test/`, and its clojure render is
`yang.clojure/output` = `(pr-str value)`, so pinning it would mean asserting against a `pr-str` of
an opaque VM value in a ns whose docstring says "refusals here are fixture defects, never runtime
reds". Authoring an unverifiable harness I cannot execute, in a generator-check ns, would create a
second instrument measuring the same thing through an unproven path. Recommend the normal-exit
finally fixture land in the queued **corpus round** (ruling consequence 2 — "LaTTe oracle/gate gains
try/finally fixtures"), which is where the ruling already puts it.

**F10 (P3) — no shell, so no commit, no push, no kondo, no SHA.** §0. The edits are on disk in the
worktree, uncommitted. Branch and base were read from `.git` directly:
`.git/worktrees/datomworld-kitfinally/HEAD` → `ref: refs/heads/exception-kit-finally`, and
`.git/refs/heads/exception-kit-finally` → `96633aaf3682ba949334e050336a9c1b6c25f722`. Worktree
dirt is otherwise undetectable without a shell (the index is binary), so I cannot rule out that
another seat wrote here concurrently.

---

## 6. Exact commands for the next seat

Run from `/Users/sto/workspace/datomworld-kitfinally`.

```bash
# 0. Confirm the composition question FIRST (F1). Against 96633aaf these will all be red
#    at compile time, which proves nothing about finally semantics.
git log --oneline -1
git status --porcelain
grep -n "no finally" src/cljc/yang/clojure.cljc     # present => the port is NOT in this tree

# 1. Compose with the port before measuring anything.
git merge 854b74c3        # or: git rebase 854b74c3
grep -n "no finally" src/cljc/yang/clojure.cljc     # must now be ABSENT

# 2. The dispatch's local gate.
clj -M:test -n yang.call.exception-source-test

# 3. The single new leg (the -v form the dispatch suggested does not exist; F5).
clj -M:test -n yang.call.exception-source-test -v \
  yang.call.exception-source-test/clojure-finally-replacement-leg-test

# 4. The pair-scale deftest that carries python->clojure, and the derived-obligation pins.
clj -M:test -n yang.python.antlr.exception-call-test -v \
  yang.python.antlr.exception-call-test/python-to-clojure-test
clj -M:test -n yang.call.conformance-test -v \
  yang.call.conformance-test/mechanically-derived-obligations-test

# 5. Lint + format.
clj-kondo --lint test/yang/call/exception_contract.clj \
                 test/yang/call/exception_legs.clj \
                 test/yang/call/exception_source_test.clj    # must be 0/0
cljstyle check test/yang/call/

# 6. Commit. Message below. Then push.
git add test/yang/call/exception_contract.clj \
        test/yang/call/exception_legs.clj \
        test/yang/call/exception_source_test.clj \
        collab/exception-track/1794960000000-owner-ruling-exception-uniformity-v2.md \
        collab/exception-track/acceptance-ledger.md \
        collab/exception-track/1794569000000-engineer-kit-finally-retire.qwen3.8-max.findings.md
git commit   # heredoc the message from §7
git push -u origin exception-kit-finally
git log --oneline -1 && git status
```

Expected on a **composed** tree: step 2's two compile deftests green, step 3 green on all three of
`:register`/`:semantic`/`:stack`. If step 3 is red on a composed tree, *that* is the real finding —
report the `:reason`/`:payload-identical` delta per backend and do not weaken `legs/expected`.
Expected on `96633aaf` alone: steps 2-4 red with `"Portable try requires one Exception/ExceptionInfo
catch and no finally"` — F1, not a semantics defect.

---

## 7. Commit message for the next seat

```
test(exception-kit): retire the clojure finally refusal per owner ruling RETIRE

owner ruling RETIRE (1794556000000): dialect surface derives from the source
language; the LaTTe corpus verifies admissions and no longer authors them.
v2 §3's finally-refusal clause is superseded.

The clojure-caller :finally-replacement legs become live for the first time.
exception_legs.clj had to change with the matrix exclusion: its generate-time
refusal throw sits inside run-pair's try, so leaving it would error every leg
of every clojure-caller pair rather than the three new ones.

:clojure-finally-refusal is removed rather than flipped to :execution
:supports — open-gates closes only on a reported :green, so a :supports row
would hold ::r4 open for clojure forever. ::r4 survives via
:clojure-private-public-equivalence, so ledger-seed is unchanged.

The (catch Throwable e 2) refusal stays: v2 R4's single-catch and catch-class
restriction is not superseded, only its "no finally" half.

No src/ and no frontend touched. Nothing claimed: the port (exception-r3-impl
@ 854b74c3) is not in this branch, so these legs cannot be measured here.
```

---

**BLOCKED: no shell in this session — the three kit files and both governance records are edited and statically verified on branch `exception-kit-finally` (base `96633aaf`, uncommitted), but `clj -M:test`, `clj-kondo`, `git commit` and `git push` could not be run, so there is no SHA and no test result | new-legs local result: NOT RUN — and would be red here regardless, because the clojure port is absent from this branch (F1)**
