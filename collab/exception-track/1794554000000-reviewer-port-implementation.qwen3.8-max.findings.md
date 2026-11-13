Completed-GMT: 2026-11-12 22:25:00 GMT
Completed-Local: 2026-11-13 05:25:00 Asia/Ho_Chi_Minh
Role: Reviewer (port implementation, non-codex family per routing)
Coding-Agent: qwen (qwen3.8-max)
Subject: branch `exception-r3-impl` @ `35d09aaa84f84ad889e6b6304fc84d36ac0d0ff8`

# Findings — the continuation port, implementation level

## 0. Method, and what I could NOT run (read first)

**No shell exists in this session.** `tool_search` for `shell`/`exec`/`run`/`bash`/`monitor` returned
only `computer_use`/worktree tools. Therefore I did **not** run, and cannot quote:

- `git diff origin/master...35d09aaa` (the brief's diff of record) — **NOT RUN**
- `git diff … -- src/cljc/yin/vm/` — **NOT RUN**
- `git status` / `git log` / `git merge-base` — **NOT RUN**
- `clojure -M:test -n yang.clojure-test` — **NOT RUN**. The implementer's **103/993 0F/0E is
  UNVERIFIED by me.** I corroborated it statically (§8) but a static count is not a run.
- Any shiva/jupiter lane — not attempted (no shell, and the kit is shiva-only regardless).

What I did instead, and its limits:

1. **Git state read from `.git` directly.** `/.git/worktrees/datomworld-r3-orch/HEAD` =
   `ref: refs/heads/exception-r3-impl`; `.git/refs/heads/exception-r3-impl` =
   `35d09aaa84f84ad889e6b6304fc84d36ac0d0ff8` — **matches the brief exactly**. The branch reflog
   gives the full commit chain (rebase onto `fb493d52`, then onto `b134207f`; port commits
   `79d2c1b3` phase 1, `8e1dab6d` phase 2, `8456c2e9` phase 3r, `6adb9b94` baselines, `35d09aaa`
   phase 4). Base of record: **`b134207f`**.
2. **Master moved during the review.** First read of `.git/refs/heads/master` = `868e334a`; a later
   read = `2506a81e` (`.git/refs/remotes/origin/master` read `868e334a`). Both are one `docs(log)`
   commit apart, so nothing below is affected — but the seat is not the only writer, as usual.
3. **Tree-vs-tree comparison** between the branch worktree (`/Users/sto/workspace/datomworld-r3-orch`,
   `35d09aaa`) and the main checkout (`/Users/sto/workspace/datomworld`, master). Every master
   commit after the base `b134207f` is `docs(…)` (master reflog), and the only file under `src/`
   or `test/` they touch is `src/cljc/yin/vm/docs/yin.repl.md` (`ebd64c1e`). So for code, the
   tree delta **is** the diff of record. Line counts are exact per-file `grep -c ''` equivalents;
   directory aggregates above ~50k matches are truncated by the search tool and I discarded those
   (they gave a false −3412 for `test/yang` before I re-derived it per subdirectory).
4. **Worktree dirt in `datomworld-r3-orch` is undetectable** without `git status`. I checked file
   *sets* instead: `src/cljc/yang` 47 = 47, `src/cljc/yin/vm` 79 = 79, `test/yang` 152 vs 149
   (+3 branch-new). No stray or missing files. If another seat has uncommitted edits in that
   worktree, my citations could be describing them rather than `35d09aaa` — please re-verify
   `git status` before acting.

**Citation convention:** `B:` = branch worktree `/Users/sto/workspace/datomworld-r3-orch`,
`M:` = main checkout `/Users/sto/workspace/datomworld` (master). All line numbers are ones I read.

**The port's file set (derived, not `git diff` output):**

| File | master | branch | Δ |
|---|---:|---:|---:|
| `src/cljc/yang/clojure.cljc` | 3267 | 3098 | −169 |
| `src/cljc/yang/clojure/prelude.cljc` | 174 | 272 | +98 |
| `test/yang/clojure_test.clj` | 2712 | 3217 | +505 |
| `test/yang/latte_oracle_test.clj` | 4139 | 4165 | +26 |
| `test/yang/latte_oracle/depth_post_port.edn` | — | new | +4 |
| `test/yang/latte_oracle/depth_baseline_fbd8da0f.edn` | — | new | — |
| `test/yang/latte_oracle/depth_comparison.md` | — | new | +161 |

`src/cljc/yang/core` 1135 = 1135, `src/cljc/yang/python` 8361 = 8361, `test/yang/call` 2360 = 2360,
`test/yang/{python,php,typescript,antlr,core,clojure}` all identical, `docs/design/yang.antlr.md`
4825 = 4825. The `src/cljc/yang` aggregate delta is exactly −169 + 98 = **−71**, which closes the
accounting: nothing else in `src/cljc/yang` changed.

---

## 1. VM purity — **PASS** (no `git diff`, but the evidence is tight)

`git diff origin/master...35d09aaa -- src/cljc/yin/vm/` was **not run**. Substitute evidence:

- Identical file sets: 79 files in both trees, same names, no additions or deletions.
- Per-file line counts for the only two VM files this branch ever touched (reflog: `fbd8da0f`
  added the retained seam, `e735ebb2` removed it): `engine.cljc` **2934 = 2934**, `module.cljc`
  **701 = 701**.
- Per-subtree line counts: `linker` 4771 = 4771, `semantic` 3138 = 3138, `ucf` 12699 = 12699.
- Whole-tree counts differ by **+337 on the branch** (46266 vs 45929) — and
  `src/cljc/yin/vm/docs/yin.repl.md` alone differs by **exactly 337** (B: 604 lines, title
  "# yin.repl: the Interface to datom.world"; M: 267 lines, title "# yin.repl: Quick Start &
  User Guide"). That is master's `ebd64c1e` "restructure quick start guide for … conciseness",
  i.e. a **master-side** doc change after the branch base, not a branch change. Subtracting
  `docs/`, the non-doc VM total is **40943 = 40943** exactly.
- Content probe: `grep -c 'retained|dsg__new-provenance|handler-stack|%capture'` over VM `*.cljc`
  → **166 in both trees**; `grep -c '%capture|handler-stack|clj/try|clj/throw|clj/call-ec|dsg__new-retained|cell/retained|continuation'` → **429 in both trees**. No `dsg__new-retained`,
  no `cell/retained?`, no `%capture`/`handler-stack` anywhere in the VM.
- `src/cljc/yang/core` (the shared `yang.call` runtime) is unchanged: 1135 = 1135.

**Verdict: no VM change. The declined seam's territory is untouched.** The one file that differs
under `src/cljc/yin/vm/` is master's own docs restructure, which the merge will take from master.

## 2. Encoding shape — **PASS**, with named residue

`try`/`throw`/`finally` lower to the `%capture` family, mirroring py/ts/php:

- The family is authored twice on purpose: `B:src/cljc/yang/clojure/prelude.cljc:96-176` (the
  linked `clj` runtime module, `clj.rt/ctx` added to `runtime-keys`) and
  `B:src/cljc/yang/clojure.cljc:889-941` `continuation-family`, emitted inline by
  `compile-program` via `continuation-uast` (`:955-966`) and admitted by
  `continuation-family-names` (`:968-974`). Same shape, same frame layout `[tag payload rest n]`,
  same `:handler`/`:finally` tags, same flag-cell re-entry discrimination. The inline copy routes
  its capture-sensitive primitives through `dsg__get`/`dsg__assoc`/`dsg__equal` so a guest
  `defn` of `get`/`assoc`/`=` cannot capture a frame access; `conj`/`vec` stay bare and are
  protected instead by the binder/definition checks at `clojure.cljc:403` and `:2137`. That is a
  real hazard closed, not decoration.
- Source-to-source pass: `rewrite-throw-try` (`:1013-1033`) `(throw e)` → `(clj/throw e)`,
  `(try …)` → `rewrite-try` (`:1036-1073`) → `clj/try` / `clj/try-finally` over `dsg__raw-fn`
  thunks. `lower-function-body` (`:1076-1082`) is now purely a source rewrite — no operand
  classification, no early-return rewriting.
- **Outcome-channel machinery that is genuinely gone** (present at `M:`, absent at `B:`):
  the `yin.k/outcome-of` source form (`M:997-1000`, `M:2165`), the `:clj/outcomes?` descriptor
  marker (`M:1385`, `M:1565`), `:clj/escape-token` / `:clj/outcome-token` (`M:1531`, `M:1570`,
  `M:1595`), and the whole per-call-site outcome router (`M:1524-1606`, ~80 lines of
  `outcome-node` ok/error splicing). `condition-wrapper-form`, `condition-retained-form`,
  `dsg__new-retained`, `cell/retained?`: **zero hits in either tree** — no wrapper forms, no
  retention objects, no routers survive.
- **Residue that survives, all of it named and narrow:** `ok-tag`/`error-tag` (`B:854-857`),
  `outcome-node` (`B:1173`), the new `top-boundary` (`B:2195-2205`), `outcome-adapter`
  (`B:1106-1124`, the host-primitive refusal adapter used by `admit`), and `clj/unwrap-outcome`
  in the prelude. This is the `[:yin.k/ok v]` / `[:yin.k/error raised]` **harness contract at the
  `compile-program` boundary only**, plus the host-adapter refusal mapping — not a semantic
  outcome channel between guest calls. `::outcomes` / `outcomes?` / `:outcomes true` were kept as
  the *name* of the lane selector and now mean "continuation lane" (`B:842-843` says so).
  See **P3-2** and **P3-3** for the two places this residue still bites.

## 3. §8.10.7 boundary — **PASS**

`call-entry` (`B:src/cljc/yang/clojure.cljc:1176-1234`):

```
entry-body = (clj/try (fn [] (if (dsg__empty named) (body positional) binding-error))
                      (fn [dsg__e] (raise (yang.call/thrown dsg__e))))
```

- **Bridge BEFORE argument binding.** The named-argument check, the positional application and the
  arity dispatch (`av-dispatch` → `arity-error-form`, `B:1090-1101`) are all *inside* the thunk, so
  every binding failure is caught by the same `clj/try`. ✓ R1.
- **Escaping throw and binding failures → the raise exit, exactly once.** Both paths converge on
  the single handler `(raise (yang.call/thrown dsg__e))`; `binding-error` and `arity-error-form`
  are `clj/throw`s, and §8.10.7's "callee argument-binding failures retain the actual callee
  exception under `:yang.call/thrown`" is exactly what they get.
- **§8.10.8 cleanup first, restore before the transfer.** `clj/try`'s re-entry branch is
  `(do (clj/restore! saved) (handler r))` (`B:prelude.cljc:120-127`, `B:clojure.cljc:934-936`):
  the saved handler/ctx snapshot is restored **before** the caller's raise exit is invoked. Guest
  `catch`es run first (they are inner frames); `:finally` frames between the throw point and the
  handler are popped-and-run by `clj/raise` itself (`(do (clj/pop-frame hs) ((get hs 1) e)
  (clj/raise e))`) before the `:handler` frame is reached. ✓
- **After-call unreachable.** The caller-side raise exit built in `compile-guest-call`
  (`B:1394-1404`) is `(fn [condition] (clj/raise …))` with `:tail? true`, and `clj/raise`'s last
  act is invoking a `%capture`d continuation — a transfer, not a return. Neither the callee's
  handler nor the caller's raise exit returns normally.
- **A→B→A / no older-handler jump.** Each activation owns its `saved` snapshot and restores it on
  both exits; there is no ambient dispatcher. The single `clj.rt/ctx` cell is the same shape the
  ruling's own baseline sanctions for js (`js.rt/ctx`).
- Minor: the handler evaluates `(dsg__get invocation :yang.call/raise)` at raise time, so an
  invocation record lacking a raise exit surfaces as a not-callable refusal *from inside the
  handler* rather than the convention's `:missing-raise-exit`. Unreachable in practice —
  `yang.call/invoke` checks `data/callable?` on the raise exit first
  (`B:src/cljc/yang/core/call.cljc:126`, `:150`). **P3-4.**

## 4. R5 — **PASS**

- The public entry **always** builds a fresh condition: `(yang.call/thrown dsg__e)`, and
  `yang.call/thrown` is unconditionally `(assoc (assoc {} :yang.call/reason :yang.call/thrown)
  :yang.call/value value)` (`B:src/cljc/yang/core/call.cljc:97-100`). A guest-thrown
  condition-shaped map therefore becomes the *payload of a new condition*, never an authenticated
  condition. ✓
- The caller's raise exit routes on `:yang.call/reason` of a condition **the convention itself
  delivered** (`B:1394-1404`): `:yang.call/thrown` → the payload raw; anything else → whole. That
  is §8.10.7 reception, not shape-routing on guest data.
- Admission still routes on the **heap**, not on shape: `cell-ref-shaped-node` (`B:1352-1382`) is
  documented and used strictly as the pre-filter for *when to ask*, and `cell/authentic?` decides.
  The r4 heap-id-language discriminator is retired, as the comment states.
- Pinned: `test-exception-r3-thrown-shape-stays-data` (`B:test/yang/clojure_test.clj:1525-1562`)
  throws `{:yang.call/reason :yang.call/not-callable …}` **and** a forged variant carrying
  `:yang.call/admitted? true` + `:clj/admission true`, and asserts both come back as the original
  value. Good test.
- Caveat worth stating: both R5 blocks decode at `top-boundary` via `run-cell-program`
  (intra-unit private lane), **not** at a public entry. The public-entry half of R5 is only
  observable in the shiva kit. Not a defect — a limit on what this suite proves.

## 5. `finally` semantics — **PASS on mechanism, FAIL on coverage** (P2-1)

Mechanism, by inspection: `rewrite-try` accepts one `finally`, wraps the (optionally caught) body
as `(clj/try-finally (dsg__raw-fn [] guarded) (dsg__raw-fn [_] …))` (`B:1064-1071`).
`clj/try-finally` pushes a `:finally` frame, and on normal exit does
`(do (clj/restore! saved) (fin nil) v)` — restore, then cleanup, then the value. On a throw,
`clj/raise` pops the `:finally` frame and runs `(fin e)` before continuing to unwind; on an
escape-continuation exit `clj/unwind-to` pops and runs `(fin nil)`. Ordering vs `catch` is
correct for both nestings (inner `finally` runs before an outer `catch`; an outer `finally` runs
after an inner `catch` returns). A `finally` that throws replaces the in-flight value, as in real
Clojure. **I could not find a semantic defect here.**

Coverage: the **only** `finally` assertion in the entire repo's guest-source tests is
`B:test/yang/clojure_test.clj:2951-2953` — `(try 1 (finally 2))` → `[:yin.k/ok 1]`, the
**normal-exit** path. Nothing anywhere asserts:

- `finally` runs when the body throws (the `clj/raise` `:finally` branch — untested);
- `finally` + `catch` together, or their ordering;
- nested `finally`, or a `finally` that itself throws;
- `clj/unwind-to`'s finally-with-`nil` path — see P2-3, it has no caller at all.

Given the FINAL DIRECTIVE's own argument rests on "`finally` runs from arbitrary depth", shipping
the lift with one normal-exit assertion is thin. **P2-1.**

## 6. Tail lane — **PASS**

- `intra-unit-fn?` (`B:1335-1343`) still selects the private lane, and it emits
  `dsg__intra_call(operator, [operands…])` with `:tail?` propagated (`B:1521-1531`).
  `dsg__intra_call` (`B:2364-2381`) looks up `:yang.call/entry` and applies it to the two-element
  token packet `[dsg__call_token args]`.
- `call-entry`'s packet branch (`B:1216-1229`) matches `data/vector?` + count 2 +
  `dsg__equal` on the token, then applies `body` **directly — no `clj/try`, no record
  construction**. A forged packet gets `forged-refusal`; maps take the ordinary record path. So
  bridge frames appear only at published/cross-unit entries, exactly as the FINAL DIRECTIVE's
  binding criterion 2 requires.
- `mark-tails`/`remark-tails` interaction is correct. `compile-guest-call` marks the inner
  application via `u/mark-tails` over the `let1` pair and the outer node via `cond-> … tail?`
  (`B:1441-1444`); `admit-call-descriptor` marks the published tree (`B:1241`); and
  **`compile-program` finishes with `tails/remark-tails`** (`B:2451`) after the preamble wrap —
  which is precisely what `yang.tails`' ns docstring demands ("a stage that wraps a body must
  strip every mark and recompute them over the whole derived tree, or a wrapped body's old tail
  calls would skip the wrapper"). Recomputation reaches `entry`'s `if` branches
  (`mark-tails` propagates `tail?` through `:if` consequent/alternate), so the private lane's
  `(body (data/nth invocation 1))` is structurally re-derived as a tail call rather than relying
  on a surviving mark. This is the part most likely to have been gotten wrong; it was not.
- Empirical: `test/yang/latte_oracle/depth_comparison.md` — 155/155 workload overlap, **max ratio
  1.20×**, max post-port depth **28** frames (`check-assume-have-discharge`, *down* from 32),
  nothing above 10×, nothing near 10,000 frames. Binding criterion 4's escalation thresholds are
  not approached. Harness `run-guest-machine-depth` (`B:test/yang/clojure_test.clj:2478-2510`) is
  real: it steps the machine and takes the running `max` of the semantic K-vector length /
  walker `:next` chain length. See **P3-1** for why this evidence is not yet a gate.

## 7. Kit integrity — **PASS** (and that is the problem: see P1-1)

`test/yang/call/` aggregate **2360 = 2360** lines; per-file
`exception_contract.clj` **211 = 211**, `exception_contract_test.clj` **136 = 136**,
`exception_source_test.clj` **41 = 41**. `M:test/yang/call/exception_source_test.clj:37-41`
contains the same `clojure-restricted-catch-surface-preserved-test` as `B:`. The acceptance
instrument was **not** edited by the port. Correct per the brief — and it means the kit now
contains an assertion this branch falsifies. **P1-1.**

## 8. Test integrity — **PASS**; every removed assertion located and explained

I hunted the deleted-coverage pattern rather than trusting the 985→993 report.

- **`test/yang/clojure_test.clj`: deftest name set is a strict superset.** master 95, branch 103;
  all 95 master names are present on the branch. The 8 additions are the R3 battery
  (`test-exception-r3-{protocol-condition-forwarded-unchanged, thrown-shape-stays-data,
  ordinary-call-catch-keeps-payload-behavior, caught-payload-identity, token-transplant-refuses,
  modified-condition-refuses, condition-field-preserved, astra-probes}`, `B:1475-1891`).
  `(is ` count **569 → 605 (+36)**. **103 deftests matches the reported "103 tests".**
- **`test/yang/latte_oracle_test.clj`: identical 36-name deftest set**, no additions, no deletions
  (the depth harness added `defn`s, not a `deftest`). `(is ` count **318 → 316 (−2)** — the only
  assertion loss in the whole port span, and I located both:
  `M:2900-2907` asserted that a descriptor with `:clj/outcomes? false` and one with the key
  `dissoc`'d both fail `kernel-host-value-deviation`. The branch drops those two and drops
  `:clj/outcomes? true` from the `descriptor` literal (`B:2876-2878`). That is exactly −9 lines,
  matching the −9 span delta for `test-kernel-acceptance-counting`. **Legitimate:** the
  `:clj/outcomes?` marker no longer exists, so a forgery pin over it is dead. The remaining four
  descriptor-forgery assertions (missing admission witness, non-closure entry, wrong `:clj/type`,
  bare `{:type :closure}`) plus the declared-call pin, the cross-call copy pin, the per-lane
  `:blocked` pins, the payload/shape-mutation pins and the `with-redefs` declaration pin are all
  intact and byte-identical.
- The other two shrunk spans are explained and are **not** coverage loss:
  - `test-common-call-invalid-continuation-refuses` (M:1406-1428 → B:1408-1427, −3 lines,
    **−2 assertions**): master tampered `u/capture` into `:invalid-continuation` and asserted
    `{:yang.call/reason :yang.call/invalid-invocation :yang.call/data {:yang.call/reason
    :missing-raise-exit}}`. The port emits no bridge `u/capture`, so there is nothing to tamper.
    Both *positive* assertions (dynamic call and static intra-unit call each `[:yin.k/ok 2]`) are
    kept, and the comment states why. **But see P2-2 — that refusal is now unpinned repo-wide.**
  - `test-outcome-refusals` (M:1621-1661 → B:2066-2104, −2 lines, **same 7 assertions**): two
    static refusals were **inverted to acceptances** — "a macro call with an operand that may fail
    is refused" and "a non-final top-level form that may fail is refused" are now
    `(is (nil? (refused …)))` with rationale comments. Legitimate: the outcome model's `failing?`
    classification pass is retired, and the guarantee it protected (a failure is never silently
    discarded) now holds dynamically because a raise propagates to `top-boundary`. It is
    nonetheless a **real semantic relaxation** and belongs in the merge request text.
- Grew, i.e. net strengthening: `test-common-call-rejects-named-arguments` 24 → 47 lines,
  `test-outcome-control-forms` 195 → 229, `test-value-invocation-refusals-are-terminating`
  56 → 66, `test-intra-unit-call-lowering-guards-and-budget` 111 → 122.
- The Phase-4 restorations in `35d09aaa` are visible in the seat log and are genuine additions
  (ordinary-cell authenticity/mutability after retention retirement; the `data/nth` host refusal
  intact under the retired adapters; the `def early`/`defn first` shadowing case strengthened from
  one `[:yin.k/ok 99]` into a `:yin.k/error` + `:yang.call/not-callable` + `identical?` triple).
- **993 assertions:** not run by me. It does match the pre-port baseline of record
  (`1794372000000-owner-decision-r3-vm-retained-seam.md` cites muse-wonder's
  "103 tests/993 assertions" at `fbd8da0f`), so 985→993 is a restoration to the exact pre-port
  count. Treat as corroborated, not verified.

**Verdict: no surviving deleted or weakened expectation beyond the four assertions above, all
four of which retire a surface the port itself removed.** The two prior deleted-coverage incidents
do not repeat here.

## 9. Source surface — **PARTIAL / FAIL** (P1-1, P3-2)

First, a correction to the brief's premise: **the one-catch restriction was NOT lifted.**
`rewrite-try` still refuses `(when (< 1 (count catches)) (reject! "Portable try supports one
catch" …))` (`B:1049-1050`), still refuses a trailing form after the clauses, still refuses a
`catch` class other than `Exception`/`java.lang.Exception`/`clojure.lang.ExceptionInfo`, and now
also refuses more than one `finally`. Only **`finally`** became expressible.

Where the widened surface *is* documented — code comments only:
`B:src/cljc/yang/clojure.cljc:839-852` (the lane's section header), `:1036-1041` (`rewrite-try`'s
docstring: "`finally` is now expressible; … one catch is the supported surface"),
`B:prelude.cljc:28-33`, and `B:test/yang/clojure_test.clj:2949-2950`.

Where it is **not** documented, and where the opposite is still asserted:
- There is no `docs/design/yang.clojure*.md` at all, and `docs/design/yang.antlr.md` is unchanged
  (4825 = 4825) and never spelled out the clojure catch surface. So the normative statement of
  the restriction lives in **ruling v2** and in **the kit** — and both still say "refuses":
  ruling v2 acceptance gate §3 "clojure's admitted surface rejects `finally`; … **PRESERVE
  clojure's rejection tests**"; `exception_contract.clj:178`
  `{:id :clojure-finally-refusal … :execution :preserve-source-refusal}`;
  `exception_legs.clj:60` "does not acquire finally syntax"; `exception_legs.clj:106-109`
  `throw "Clojure finally remains an explicit source refusal"`.
- The FINAL DIRECTIVE supersedes toward real-Clojure semantics and names "no `finally`" as part of
  the restricted dialect the outcome model forced — so lifting it is defensible. But **no ruling
  retires the kit's finally-refusal row**, and the port did not (and per the brief must not)
  touch the kit. That is the collision in P1-1.

---

## Findings

| # | Sev | Where | Finding |
|---|---|---|---|
| P1-1 | **P1 blocking** | `B:test/yang/clojure_test.clj:2951-2953` vs `B:test/yang/call/exception_source_test.clj:37-41`; also `exception_contract.clj:162,178`, `exception_legs.clj:60,106-109` | The branch makes `(try 1 (finally 2))` compile and run; the unchanged kit asserts it throws `clojure.lang.ExceptionInfo`. `clojure-restricted-catch-surface-preserved-test` **must fail on this branch**, and the ledger obligation `:clojure-finally-refusal` (`:execution :preserve-source-refusal`) plus the `:finally-replacement` clojure exclusion are now false records. Ruling v2 §3 explicitly says "PRESERVE clojure's rejection tests"; the FINAL DIRECTIVE implies the opposite; nothing reconciles them. The planned next step is "shiva (kit …)", so this goes red for a governance reason, not a code reason. **Needs an owner/steward ruling retiring (or reinstating) the finally-refusal row, and a separate kit round, before shiva.** |
| P1-2 | **P1 blocking for the acceptance claim** | `B:src/cljc/yang/clojure.cljc:1207-1211`; `B:src/cljc/yang/core/call.cljc:97-100`; `acceptance-ledger.md:102-119` | **R3's outbound half is not closed.** The public entry's handler is an unconditional `(raise (yang.call/thrown dsg__e))` and `yang.call/thrown` always builds a *fresh* `:yang.call/thrown`. A guest that catches a boundary-delivered protocol condition and rethrows it unchanged is therefore reclassified at the public escape → `:reason :yang.call/thrown`, `:condition-identical false`. R3 scopes "reception **AND** outbound rethrow". Reception *is* fixed (`compile-guest-call`'s raise exit delivers non-`thrown` conditions whole, pinned by `test-exception-r3-protocol-condition-forwarded-unchanged`). Outbound is not, so the **18 red `:clojure/protocol-*` / `:clojure/saved-protocol-*` rows are predicted unchanged** — the same signature as the 2026-11-11 acceptance report. The focused suite cannot see this: that test decodes at `top-boundary` via `run-cell-program`, the intra-unit lane, and its own second block concedes "the wire condition is reconstructible from the payload as a fresh thrown condition at a public entry". Not a regression introduced by the port; a requirement the port was chartered to close. Remedy already authorized: the owner's pre-approved **(B) fallback** — "the protocol carrier is expected to be a closure-based immutable form (no VM change needed either)". Shape-routing is forbidden by R5 and the VM seam is declined, so a prelude-owned closure carrier is the only remaining route. Alternative: the owner explicitly defers R3-outbound for clojure and records the 18 rows as an accepted known gap in the ledger. |
| P2-1 | P2 | `B:test/yang/clojure_test.clj:2951-2953` | `finally` is now admitted but only its **normal-exit** path is asserted, once. Untested: `finally` runs on a throw (the `clj/raise` `:finally` branch), `finally` + `catch` ordering, nested `finally`, a `finally` that throws. The FINAL DIRECTIVE's own rationale is "`finally` runs from arbitrary depth"; ship at least a throw-through and a catch+finally ordering pin, on both lanes. |
| P2-2 | P2 | `B:src/cljc/yang/core/call.cljc:126,150`; `B:test/yang/clojure_test.clj:1408-1427` | Deleting the `u/capture` tamper case removed the **only** pin in the repo on `:yang.call/invalid-invocation {:yang.call/reason :missing-raise-exit}`. Repo-wide grep for `missing-raise-exit` now returns the two `core/call.cljc` production sites and one explanatory comment — no test. This is a *shared* convention refusal (all four frontends reach it through `yang.call/invoke`), so it should be re-pinned host-side in `test/yang/core/call_test.cljc` (not a kit file) rather than through clojure's lowering. |
| P2-3 | P2 | `B:src/cljc/yang/clojure/prelude.cljc:135-146,165-176` | `clj/unwind-to` and `clj/call-ec` are published exports of the `clj` module (`module-exports` = every `clj/` key) with **zero callers and zero tests anywhere in `src/` or `test/`** — the inline `continuation-family` deliberately omits them. `unwind-to` is the *only* implementation of finally-on-non-local-exit, so the dead surface is also unverified surface. Either drop them from the prelude until a consumer exists, or pin them. |
| P3-1 | P3 | `B:test/yang/latte_oracle_test.clj:2552-2591`; `depth_comparison.md`, `depth_post_port.edn`, `depth_baseline_fbd8da0f.edn` | The depth evidence is **not a gate**. There is no `deftest` asserting the 155/155 overlap, the ≤10× ratio or the ≤10,000-frame bound; the harness only *writes* EDN, and the comparison is a hand-assembled `.md`. A future depth regression is invisible. Also, both `.edn` headers were hand-annotated and are not reproducible from `write-kernel-gate-depths!`, which emits a single comment line (`:2587`) while the committed files carry two and three. Criterion 1's "harness built FIRST" is satisfied in substance (the baseline is genuinely `fbd8da0f`, the pre-port outcome-model tip) but chronologically the harness landed in Phase 3r and the baseline in the commit after it. |
| P3-2 | P3 | `B:src/cljc/yang/clojure.cljc:2008-2010`, `:2461-2463`, `:2942`, `:2951-2954`; 12 `test-outcome-*` deftests | Stale outcome-model documentation contradicting the port's own new text at `:842-843`. `:2008-2010` is a comment describing a `yin.k/outcome-of` case arm that no longer exists, now sitting above the `dsg__let` arm. `:2461-2463` still says the guest library "is written for … the outcome model, so a closure it calls is unwrapped by the compiler's own early-return pattern" — the library no longer contains a single `yin.k/outcome-of`. `:2942`/`:2951-2954` still describe `unit-opts` as "the outcome model". And twelve deftests named `test-outcome-*` now exercise the continuation lane. |
| P3-3 | P3 | `B:src/cljc/yang/clojure.cljc:2195-2205`; `B:prelude.cljc:180-197` | `clj/run-main` returns `[:yin.k/error raised]` as an ordinary **value** for a top-level escaping throw, because `clj/unwrap-outcome` strips only `:yin.k/ok`. Outside R1's scope (R1 names function/closure/higher-order entries, not the program entry) and it is the pre-existing harness contract, but it is the last outcome-tag path in production lowering and an escaping throw from a linked unit's top level never reaches the composition as a condition. Record it as an explicit known gap or convert it. |
| P3-4 | P3 | `B:src/cljc/yang/clojure.cljc:1181,1207-1211` | The entry's handler resolves `:yang.call/raise` at raise time; a record without one surfaces as a not-callable refusal from inside the handler instead of `:missing-raise-exit`. Guarded upstream by `yang.call/invoke`; noted for robustness. |

### No P0

`src/cljc/yin/vm/` is untouched (§1). The declined seam's territory was not entered.

---

## What is good, and should not be churned

The `%capture` family is a faithful mirror of the py/ts/php preludes rather than a clojure-shaped
approximation; the flag-cell re-entry discrimination, the integer frame count making
"unwind-to" a comparison, and `(clj/restore! saved)` **before** `(handler r)` are each exactly
right, and that last one is the §8.10.8 detail most implementations get backwards. Routing the
inline family's frame accesses through `dsg__get`/`dsg__assoc`/`dsg__equal` closes a real
shadowing hazard. Deleting the outcome router, the `:clj/outcomes?` marker and the escape/outcome
tokens rather than leaving them dormant is the right call, and the private token lane surviving
intact with `remark-tails` applied *after* the preamble wrap is the load-bearing part of the whole
port — it is correct. The 155-workload depth table with a 1.20× ceiling and an absolute max that
went *down* (32 → 28) is the evidence the FINAL DIRECTIVE asked for. Coverage discipline held: no
deftest deleted, and all four lost assertions are dead pins over surfaces the port removed.

## Recommended disposition

P1-1 and P1-2 are not code-quality nits: P1-1 guarantees a red shiva run, and P1-2 means the merge
request would carry an acceptance claim the ledger will not support. Neither is fixable inside the
port's own authority — P1-1 needs a ruling plus a kit round, P1-2 needs either the (B) closure
carrier or an explicit owner deferral recorded against the 18 rows. Copy-pasteable verification for
a seat that has a shell:

```sh
cd /Users/sto/workspace/datomworld-r3-orch
git status --porcelain                      # confirm the worktree is clean at 35d09aaa
git diff origin/master...35d09aaa --stat    # confirm the 7-file set in §0
git diff origin/master...35d09aaa -- src/cljc/yin/vm/   # expect: only docs/yin.repl.md, master-side
clojure -M:test -n yang.clojure-test                    # expect 103 tests / 993 assertions / 0F 0E
clojure -M:test -n yang.call.exception-source-test      # P1-1: expect clojure-restricted-catch-surface-preserved-test to FAIL
clojure -M:test -n yang.latte-oracle-test
```

**REQUEST CHANGES** — blocking: **P1-1** (the finally lift falsifies the unchanged acceptance kit;
needs an owner/steward ruling and a kit round before shiva) and **P1-2** (R3's outbound-rethrow
half is unclosed, so the 18 red `:clojure/protocol-*` / `:clojure/saved-protocol-*` ledger rows are
predicted unchanged; remedy is the owner's pre-authorized (B) closure carrier or an explicit
deferral). Non-blocking: P2-1, P2-2, P2-3, P3-1, P3-2, P3-3, P3-4. Items 1, 2, 3, 4, 6, 7, 8 pass;
item 5 passes on mechanism and fails on coverage; item 9 is partial. The port itself is sound and
VM-pure — the blockers are at its acceptance boundary, not in its lowering.

— qwen3.8-max, independent of both implementer families (deepseek-v4-pro Phases 1-3r,
codex-terra 3r-b/4). I did not read any sibling reviewer's output for this round; none existed on
disk when I started.
