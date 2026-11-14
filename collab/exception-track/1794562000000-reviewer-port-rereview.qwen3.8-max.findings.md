Completed-GMT: 2026-11-12 23:47:00 GMT
Completed-Local: 2026-11-13 06:47:00 Asia/Ho_Chi_Minh
Role: Reviewer (re-review — the provenance carrier `f2e82ffc` + donor-link fix `d4e31b74` + P2 round `f15423c2`)
Coding-Agent: qwen (qwen3.8-max)
Subject: branch `exception-r3-impl` @ `d4e31b742e26471ca701548ab3b043e5828ea85e`
Resume of: `1794554000000-reviewer-port-implementation.qwen3.8-max.findings.md` (REQUEST CHANGES)
Clock: read from `timeapi.io` (UTC `2026-11-12T23:42:50`, +07 `2026-11-13T06:42:54`) — no shell in this session.

# Re-review — carrier + donor-link fix + the P2 round

## 0. Method and what I could not run

**Still no shell.** `tool_search` for `run_shell_command`/`monitor`/`bash`/`exec` again returned only
`computer_use`/worktree tools. So the brief's item-6 command
`git diff origin/master...d4e31b74 --stat` was **NOT RUN**, and I ran **no tests** — not the focused
`yang.clojure-test`, not the kit. Every verdict below is from reading.

Substitute method (same as the previous round, and it reconciles exactly):

- **State.** `.git/worktrees/datomworld-r3-orch/HEAD` → `ref: refs/heads/exception-r3-impl`;
  `.git/refs/heads/exception-r3-impl` → `d4e31b742e26471ca701548ab3b043e5828ea85e`. **Matches the
  brief.** Branch reflog adds three commits since my last review: `f15423c2` "test(yang.clojure):
  pin finally unwinding and call refusal" → `f2e82ffc` "feat(yang.clojure): retain protocol
  conditions with provenance" → `d4e31b74` "fix(yang.clojure): use runtime context in linked units".
- **Master moved again mid-review**: `.git/refs/heads/master` read `f554e65e` (was `2506a81e` during
  the previous round; `origin/master` read `868e334a` then). `f554e65e` is `docs: rename yang.cic/
  yin.cic to dao.li` and touches none of the paths below — every master line count I re-measured is
  identical to the previous round (3267 / 174 / 2712 / 4139 / 2360 / 45929 / `yin.repl.md` 267).
- **Delta since `35d09aaa`** (previous review's subject), by exact per-file line count:

| File | @35d09aaa | @d4e31b74 | Δ |
|---|---:|---:|---:|
| `src/cljc/yang/clojure.cljc` | 3098 | **3122** | +24 |
| `src/cljc/yang/clojure/prelude.cljc` | 272 | **248** | −24 |
| `test/yang/clojure_test.clj` | 3217 | **3281** | +64 |
| `test/yang/core/call_test.cljc` (dir `test/yang/core`) | 375 | **388** | +13 |
| `test/yang/latte_oracle_test.clj` | 4165 | 4165 | 0 |
| `test/yang/latte_oracle/depth_post_port.edn` | 4 lines, `:revision "8e1dab6d"` | **2 lines, `:revision "working-tree"`** | regenerated |
| `test/yang/latte_oracle/depth_baseline_fbd8da0f.edn` | 1 comment line | gained `:overlap-count 155` | regenerated |
| `test/yang/latte_oracle/depth_comparison.md:5` | "recorded at `8e1dab6d`" | "**provenance carrier working tree based on `f15423c2`**" | re-measured |
| `test/yang/call/**` (the kit) | 2360 | **2360** | 0 |
| `src/cljc/yin/vm/**` | 46266 | **46266** | 0 |

  The prelude's −24 is exactly `clj/unwind-to` (12) + `clj/call-ec` (12). The `clojure.cljc` +24 is
  the carrier. `test/yang/core` +13 is the re-pinned refusal.
- **Worktree dirt is still undetectable** without `git status`. File *sets* match master everywhere I
  checked (`src/cljc/yang` 47 = 47, `src/cljc/yin/vm` 79 = 79, `test/yin/vm` 42 = 42,
  `test/yang` +3 branch-new depth artifacts). If another seat has uncommitted edits in
  `datomworld-r3-orch`, my citations describe them, not `d4e31b74`.

Citations: `B:` = `/Users/sto/workspace/datomworld-r3-orch` (branch @ `d4e31b74`),
`M:` = `/Users/sto/workspace/datomworld` (master). All line numbers are ones I read.

---

## 1. Carrier vs sol's spec — **PASS**, point for point

Spec: `1794559000000-consult-carrier-authentication.gpt-6.1-sol.findings.md` §3.

**(a) Mint sites complete — reception + compiler refusals.** I enumerated every occurrence of the
doors across `B:src/` (17 hits total). Clojure has exactly **three mints and three readers**:

| Site | Line | Role |
|---|---|---|
| `compile-guest-call`'s raise exit | `B:clojure.cljc:1403` | **reception** — `(cell/dsg__new-provenance condition condition)` on the non-`:yang.call/thrown` branch |
| `refused` (admission refusal) | `B:clojure.cljc:1414` | compiler-generated `:yang.call/not-callable`, minted via a `u/let1` so the condition is named once |
| `dsg__intra_call` dispatcher refusal | `B:clojure.cljc:2400` | compiler-generated `:yang.call/not-callable` on a non-map callee |
| `call-entry`'s handler | `B:clojure.cljc:1206` | **egress** reader |
| `top-boundary`'s handler | `B:clojure.cljc:2214` | harness-top reader |
| `dsg__condition_view` | `B:clojure.cljc:2290` | accessor-projection reader |

That is precisely the three mint sites sol named (reception, admission refusal `:1409`→now `:1414`,
private dispatcher refusal `:2378`→now `:2400`). **No source path mints:** `rewrite-throw-try`
(`B:1013-1033`) lowers `(throw e)` → `(clj/throw e)` with no provenance construction, so
sol's "A source `(throw condition-shaped-map)` must never mint provenance" holds by construction.
Correctly *not* minted: `arity-error-form` (`B:1090-1101`), the named-argument `binding-error` and
`forged-refusal` in `call-entry` — these are `:yin.vm.data/reason :arity` / portable ex-info maps,
not the three qualified protocol reasons, and §8.10.7 wants binding failures delivered *under*
`:yang.call/thrown`. ✓

**(b) Carry unchanged.** `clj/throw` = `clj/raise` (`B:926`); `clj/raise` walks frames handing `e`
to the `:finally` thunk and then to the handler continuation **without decoding it** (`B:918-925`);
`clj/try`'s handler receives `r` verbatim (`B:927-937`). Nothing in the family calls `cell/get` on
the thrown value. ✓

**(c) Egress authenticate-once-and-forward.** `B:clojure.cljc:1204-1212` is sol's snippet literally:

```
(u/lam ['dsg__e]
  (u/let1 'dsg__provenance (u/app (u/v 'cell/dsg__provenance) (u/v 'dsg__e))
    (assoc (u/app raise (u/if-node (u/app (u/v 'dsg__equal) (u/v 'dsg__provenance) (u/lit nil))
                                   (u/app (u/v 'yang.call/thrown) (u/v 'dsg__e))
                                   (u/v 'dsg__provenance)))
           :tail? true)))
```

- **queried once** (a single `let1`, both branches read the binding) ✓
- **forwarded unchanged** (the recovered `dsg__provenance` is passed straight to the raise exit — no
  reconstruction, no re-`assoc`) ✓
- **`dsg__e` is never applied** ✓
- **no shape routing** — the discriminator is `cell/dsg__provenance`, the heap-authenticated reader,
  not a key test ✓
- **no frontend tag inspected** ✓
- **tail-marked**, as sol asked ✓; and `compile-guest-call`'s two `clj/raise` calls are tail-marked
  too (`B:1404`, `B:1416`)
- **protected primitives**: the comparison is `dsg__equal`, bound outermost in `compile-program`'s
  `cond->>` thread (`B:2425-2429` is the last step, therefore the outermost `let`), so a guest
  `defn` of `=` cannot capture it; the door itself is namespace-qualified `cell/…` ✓
- **private tail lane preserved**: `call-entry`'s packet branch is still
  `(u/app (u/v body) (u/app (u/v 'data/nth) (u/v invocation) (u/lit 1)))` with **no `clj/try`**
  (`B:1226`) ✓ — sol's "add no handler there".

**(d) Construction authority private.** No `defn`, export, projection or alias anywhere in `B:src/`
wraps the doors — all three mints are inline generated UAST. The `clj` module's exports are
`(core/exports "clj" definitions)`, i.e. every `clj/` key and nothing else: `init!`, `define-global!`,
`global-get`, `ctx`, `rt-ctx`, `restore!`, `handlers`, `set-handlers!`, `frame-depth`, `frame`,
`pop-frame`, `raise`, `throw`, `try`, `try-finally`, `unwrap-outcome`, `run-main` — **no `dsg__*`,
no `cell/*`, no mint helper**. Contrast python, which does have a factored constructor
(`B:src/cljc/yang/python/antlr/prelude.cljc:1054`) and therefore needs its private-names audit at
`:205`; clojure has no such surface. The shape mirrors python's reference split exactly (mint at
reception `py:1079`/`clj:1403`, recover at egress `py:918`/`clj:1206`).

**(e) The projection does not replace the throw/save reference.** `dsg__condition_view`
(`B:2287-2292`) is applied at exactly two observation sites: the keyword-call operand
(`(:k m)` → `B:1482-1484`) and the native `get` first operand (`B:1544-1549`, guarded by
`(= operator 'get)` and `(zero? i)`). Neither `rewrite-throw-try` nor any `cell/set!` path passes
through it, so `(throw e)` and `(cell/set! saved e)` still carry the sealed reference. ✓ Exactly
sol's constraint.

**(f) sol's stale test assumption replaced.** `B:test/yang/clojure_test.clj:1862-1873` now reads
`"a caught protocol condition is an authenticated carrier"` and asserts
`[(data/map? e) (cell/authentic? e)]` = `[true true]`. Acceptance expectations in `test/yang/call/`
untouched. ✓

## 2. The saved case — **PASS**, pinned twice and it mirrors the kit's shape

The kit's clojure source (`B:test/yang/call/exception_contract.clj:72-80`, unchanged) is
`(def saved (cell/new nil))` / `(defn retain [f] (try (f) (catch Exception e (cell/set! saved e) e)))`
/ `(defn later [] (throw (cell/get saved)))`. Two branch pins reproduce that shape:

- `B:test/yang/clojure_test.clj:1896-1906` (`test-provenance-carrier-identity`) — `retain` stores the
  carrier with `cell/set!` and **returns `nil`**, so the activation genuinely completes; a *later,
  separate* activation `(let [f later] (f))` rethrows `(cell/get saved)`; asserts
  `[:yin.k/error condition]` byte-identical, on **both lanes**.
- `B:test/yang/clojure_test.clj:1849-1862` (`test-exception-r3-astra-probes`) — the kit's exact
  `retain`/`later` pair including `(let [retained (retain marker-operand)] (later))`, message
  "saved retain then rethrow forwards byte-identical", both lanes.
- `test-exception-r3-condition-field-preserved` (`B:1754-1786`) additionally round-trips an ordinary
  `:dsg__condition` evidence field byte-identically through **both** immediate and saved rethrow —
  i.e. provenance lives outside the visible map, so no evidence field is lost or confused with it.

And the rest of sol's §4 list is pinned in `test-provenance-carrier-identity` (`B:1881-1931`), all on
both lanes: **alias** through a closure, **overwritten visible value** (`(cell/set! e {:forged true})`
then rethrow → still the *original* condition, i.e. `set!` cannot replace the retained condition),
**finally** unwinding carries the carrier, **content clone loses authority**
(`(into {} (cell/get e))` → `[(cell/authentic? copy) copy]` = `[false condition]`), and
**egress never applies a thrown closure** (a thrown `fn` that sets `ran` leaves it `false`).
These match the VM-level pins in `B:test/yin/vm/provenance_test.cljc` (348 = 348 vs master, 17
deftests including `set!-preserves-provenance-test`, `content-copy-loses-provenance-test`,
`ref-copy-retains-rethrow-authority-test`, `provenance-is-hidden-from-get-test`,
`provenance-query-is-never-fatal-test`, `mint-refuses-nil-provenance-test`), which the port did not
touch.

**One limit, stated plainly:** all of these decode at `top-boundary` via `run-cell-program`
(the intra-unit lane), not at `call-entry`. `top-boundary` (`B:2211-2218`) uses the *same* recovery
expression as `call-entry`, and I read `call-entry`'s line by line, so the residual risk is small —
but the public-entry egress that the 18 ledger rows actually measure is still only provable on
shiva. That is the known two-decode-site split, not a new defect.

## 3. Donor-link fix (`d4e31b74`) — **PASS**; admission is *stricter*, not looser

The change is one predicate and one comment (`B:clojure.cljc:2420-2425`):

```clojure
;; A linked unit shares the required clj module's handler stack.
;; Its inline counterpart names the private clj.rt/ctx store key,
;; which cannot be read from a different module.
(and (outcomes? env) (not (:linked-runtime opts))) (u/then (discard-result continuation-uast))
```

with `:linked-runtime true` added to `unit-opts` (`B:2971-2981`).

Why this is correct: `compile-program` used to emit the whole `clj/` family **and** allocate its own
`clj.rt/ctx` cell inline in every program. In a *linked* unit that means a second, disjoint handler
stack beside the one `clj/init!` allocates in the required `clj` module — so a raise crossing between
the unit and `clj/run-main` used a different context cell. Suppressing the inline copy makes the
linked unit read `clj/try`, `clj/raise`, `clj/throw`, `clj/try-finally` from the module it already
requires, sharing that module's `clj.rt/ctx`.

Why it does **not** weaken the undeclared-free-name rule:

- The names become **free**, which makes admission *harder*, not easier: they must now be discharged
  by a real requirement. The donor publication already supplies one —
  `B:test/yang/call/conformance.clj:216-228` builds the donor tree as
  `[(core-module/require-node runtime) (u/def! 'body (unit …))]` with
  `:runtime-name 'clj`, `:runtime-address (address)`, `:deps {'yang.call …}`. So `clj/*` resolves
  against a declared, address-pinned requirement of the `clj` module.
- `cell/dsg__new-provenance` / `cell/dsg__provenance` resolve against the host `cell` module, which
  already exports them (`B:src/cljc/yin/vm/module.cljc:438-439`, profiles at `:450,:453`) and which
  the unit already read pre-port (`cell/new`, `cell/get`, `cell/set!`, `cell/authentic?` — the kit's
  own clojure fixture uses `cell/new`).
- **The admission machinery itself is untouched**: `src/cljc/yang/core` **1135 = 1135** and
  `src/cljc/yin/vm/linker` **4771 = 4771**. `yang.core.prelude/module-spec`'s free-name refusal and
  the `clj` module's own `:free-names module-free-names` / "the clj module reads an undeclared name"
  guard (`B:prelude.cljc:196-217`) are byte-count identical to master. Nothing was made permissive.
- `continuation-family-names` (`B:963-968`), the allow-list that admits `clj.rt/ctx` + the family
  keys, is referenced in exactly one place repo-wide: `B:test/yang/latte_oracle_test.clj:211`,
  inside `unbound-ast-symbols` — a **test-harness** scan for the portable oracle context, not the
  linker. That harness compiles with its own opts (`latte_oracle_test.clj:184`, no `:linked-runtime`),
  so the family genuinely *is* inline there and the allow-list is accurate. See **P3-7** for the
  latent coupling.

## 4. R5 — **PASS**; a forged mint is unreachable

- **Guest-thrown shapes stay data.** `rewrite-throw-try` never mints, so `(throw {:yang.call/reason
  :yang.call/not-callable …})` reaches egress with no provenance → `(yang.call/thrown e)` → a **fresh**
  condition whose value is that map. Pinned by `test-exception-r3-thrown-shape-stays-data`
  (`B:1524-1562`), including the forged variant carrying `:yang.call/admitted? true` **and**
  `:clj/admission true`. `test-exception-r3-modified-condition-refuses` (`B:1733-1752`) pins the
  adjacent case: `(assoc (cell/get e) :yang.call/reason :yang.call/unsupported-binding …)` produces a
  plain map with no provenance, so the *modified* map is forwarded as data and the forged qualified
  reason is never authenticated. Also re-pinned host-side:
  `B:test/yang/core/call_test.cljc:171-181` "throwing wraps condition-shaped data; normal return
  remains data", across every VM.
- **The doors cannot be spelled or constructed by a guest.** `yin.vm/reserved-prefix` = `"dsg__"` and
  `reserved-prefix-name?` matches **whatever the namespace** (`B:src/cljc/yin/vm.cljc:124-142`), with
  the docstring naming `cell/dsg__new-provenance` and `cell/dsg__provenance` explicitly: "Source
  admission and macro admission refuse it in any executable position — a variable, a binder, or a
  definition target. A quoted symbol carrying it stays inert data." `yang.clojure` enforces the same
  prefix (`B:clojure.cljc:411-443`, `check-program-symbols!` at `:2168-2186`).
- **Macro path pinned at both levels** (the brief asked me to check this specifically):
  frontend — `test-program-pre-pass` "a reserved-prefix symbol is refused in any position"
  (`B:clojure_test.clj:2149-2159`, six positions: `def` value, call operand, `let` binder, `def`
  target, vector element, map value) plus `(defmacro dsg__0 …)` refused
  (`B:clojure_test.clj:590-598`) and quoted data exempt (`:2167`);
  VM — `macro-door-is-refused-at-expansion-test` (`provenance_test.cljc:292`) and
  `macro-cannot-construct-the-mint-door-test` (`:315`), both asserting the door name appears in the
  refusal. Neither file was modified by the port.
- **Exports/projections/aliases swept**: the full `B:src/` enumeration in §1(a) shows no exported
  helper, no alias, no projection carrying a door. `module-exports` for `clj` is `clj/`-only.

## 5. The P2 round — **all three closed**, one closed better than I asked

- **P2-1 (`finally` coverage) CLOSED.** New `finally-unwinds-and-orders-cleanup`
  (`B:clojure_test.clj:2581-2600`), both lanes, three cases: inner `finally` runs before an outer
  `catch` (`[:finally]`); `catch` + `finally` in one `try` plus an outer `finally`
  (`[:catch :finally :outer]` — the ordering pin); and a `finally` that throws **replaces** the
  in-flight exception (`:cleanup`). That is throw-path, ordering, nesting and throwing-finally — the
  four gaps I named. The previously-untested `unwind-to` finally path is now moot (see next).
- **P2-2 (`missing-raise-exit` unpinned) CLOSED and strengthened.** New
  `conditions-and-transfer-test` "a missing raise exit is refused by both invocation variants"
  (`B:test/yang/core/call_test.cljc:159-170`) pins the exact
  `{:yang.call/reason :yang.call/invalid-invocation :yang.call/data {:yang.call/reason
  :missing-raise-exit}}` shape for **both** `yang.call/invoke` and `yang.call/invoke-with`, via
  `run-everywhere` (all VMs, not two lanes), by substituting a rejecting `data/callable?`. That is
  host-side, frontend-independent and broader than the deleted clojure-specific pin — exactly the
  relocation I recommended, and `test/yang/core/` is not a kit directory, so item 7 is unaffected.
- **P2-3 (dead exported surface) CLOSED.** `clj/unwind-to` and `clj/call-ec` are gone from
  `B:src/cljc/yang/clojure/prelude.cljc` (272 → 248 = exactly their 24 lines); the ns docstring's
  family list was updated to `clj/try, clj/try-finally, clj/throw, clj/raise` (`:28-31`) and
  `continuation-family`'s "minus `call-ec`/`unwind-to`" note (`B:clojure.cljc:894-896`) is now true
  of both copies. `B:prelude.cljc:120`'s `clj/raise` comment still says "throw and escapes run it as
  they unwind" in `clj/try-finally`'s docstring (`:157`) — harmless, but see P3-8.
  Residual observation, **not filed as a finding**: `clj/rt-ctx` remains exported with no clojure
  caller, but `js/rt-ctx` (`typescript/antlr/prelude.cljc:1773`) is in the same position while
  `py/rt-ctx` has real callers (`python/antlr/safepoint.cljc`), so it reads as a deliberate
  cross-runtime convention rather than port leftovers.

## 6. Kit and VM unchanged across the whole span — **PASS** (`git diff` not run)

- **Kit**: `test/yang/call/` aggregate **2360 = 2360** vs master, and per-file
  `exception_contract.clj` **211 = 211**, `exception_contract_test.clj` **136 = 136**,
  `exception_source_test.clj` **41 = 41**. `conformance.clj`'s donor publication is therefore the
  master version, which is what makes §3's "already declares the runtime" argument valid.
- **VM**: `src/cljc/yin/vm/` aggregate **46266**, identical to what I measured at `35d09aaa` and to
  the previous round's finding; `engine.cljc` **2934 = 2934**, `module.cljc` **701 = 701**; the sole
  divergence remains master's own `docs/yin.repl.md` (604 vs 267 = the whole 337 delta). Non-docs VM
  total **40943 = 40943**. The carrier consumes cc3's already-landed provenance primitive; it adds
  **zero** kernel lines, so the owner's NO and the FINAL DIRECTIVE's "No VM change" still hold.
- **VM tests**: `test/yin/vm/` 42 = 42 files; `provenance_test.cljc` **348 = 348**;
  `scanner_goldens.cljc` **8185 = 8185** — so removing two `clj` exports required no golden
  regeneration, confirming the goldens do not enumerate the `clj` module's surface.

## 7. Coverage accounting since `35d09aaa` — no deletions

`test/yang/clojure_test.clj` deftest count **103 → 105**; all 103 previous names still present. The
two additions are `test-provenance-carrier-identity` (`:1881`) and
`finally-unwinds-and-orders-cleanup` (`:2581`). `(is ` source occurrences **605 → 610**.
`test/yang/latte_oracle_test.clj` unchanged at 4165 lines / 36 deftests.
**Note for the orchestrator:** the focused suite is now **105 deftests**, not 103, and the runtime
assertion count will be **higher than 993** (the new pins `doseq` over two lanes and three labels).
A changed count on the next run is expected, not a regression. I could not run it to give the number.

---

## Findings

No P0. No P1 attributable to this round. The two P1s from my previous report are unchanged in
status and outside my remit here: **P1-1** (the `finally` lift vs the unchanged kit's
`clojure-restricted-catch-surface-preserved-test`) is with the owner, and I do not re-litigate it —
I record only the fact that the kit is still byte-identical (`41 = 41`) and therefore still asserts
the refusal. **P1-2 is CLOSED** by the carrier: the unconditional reclassification I flagged at
`35d09aaa` is gone, replaced by authenticate-once-and-forward at `B:clojure.cljc:1204-1212`.

| # | Sev | Where | Finding |
|---|---|---|---|
| P2-4 | **P2** | `B:test/yang/latte_oracle/depth_post_port.edn:2`; `depth_comparison.md:5` | The depth evidence is **not attributable to the merge SHA**. The EDN was regenerated with `:revision "working-tree"`, and the comparison header says "provenance carrier working tree based on `f15423c2`" — i.e. it covers the carrier but **not `f2e82ffc`'s commit state and not `d4e31b74`**. FINAL DIRECTIVE criterion 4 makes the depth thresholds a pre-merge escalation gate, so the artifact of record should name a commit. Mitigating: `d4e31b74` only suppresses the inline family when `:linked-runtime` is set, and the oracle harness does not set it, so the measured numbers are very likely unchanged — but that is my inference, not a measurement. Values themselves are fine (155/155 overlap, none above 10×, max ratio 1.20×, max depth 28). **Ask: re-run `write-kernel-gate-depths!` at `d4e31b74` and record the SHA**, or state the scoping explicitly in the merge request. |
| P3-1 | P3 (carried, partly addressed) | `B:test/yang/latte_oracle_test.clj:2552-2591` | Still **no executable depth gate**. The harness measures and writes; no `deftest` asserts the overlap, the ratio bound or the frame bound, so a future regression is invisible until someone re-reads a markdown table. Re-measurement did happen this round, which is why this stays P3 rather than rising. |
| P3-5 | P3 | `B:clojure.cljc:1482-1484`, `:1544-1549`, `:2287-2292` | **The carrier changes the guest-visible representation of a caught protocol condition** from a plain map to an authenticated cell. The projection covers exactly two forms — keyword invocation `(:k e)` and `(get e k)`. `assoc`, `keys`, `dissoc`, `count`, `=` and `into` applied to a caught carrier see the cell, not the map (the pins themselves use explicit `(cell/get e)`). `data/map?` and `cell/authentic?` are both true, which is pinned. Acceptance-neutral — the kit's `receive`/`echo`/`raise_value` only rethrow — but it is a real semantic change vs `35d09aaa` and belongs in the merge request, ideally with a pin naming the supported projection set. |
| P3-6 | P3 | `B:test/yang/clojure_test.clj` (absent) | No clojure-side **export-surface audit** mirroring python's `python-provenance-export-surface-test` (`B:test/yang/call/python_exception_test.clj:33-49`, over the `py` prelude's private-names set). Low risk — clojure never binds the doors to a name, so there is no helper to leak — but it is the cheap pin that would catch a future refactor that factors the mint into a named helper. |
| P3-7 | P3 | `B:test/yang/latte_oracle_test.clj:205-215` | `yang/continuation-family-names` is admitted **unconditionally** into the oracle harness's `unbound-ast-symbols` allow-list, with the comment "emitted inline by compile-program under `:outcomes true`". That is now true only when `:linked-runtime` is absent. If the oracle corpus is ever compiled with `unit-opts`, the allow-list would mask genuinely undeclared `clj/*` reads. Gate the allow-list on the same predicate, or note the coupling. |
| P3-2 | P3 (carried, still open) | `B:clojure.cljc:2008-2010`, `:2461-2463`, `:2971-2976`, `:3093-3097`; 12 `test-outcome-*` deftests | Stale outcome-model prose. `unit-opts` was **partly** fixed (it now adds "The required clj module supplies the continuation family") but still opens "the argument-vector convention **with the outcome model** over the guest library's adapters". `lower-unit`'s docstring still says "the convention preamble `compile-program` emits defines those names locally" — true for `yang.call`, no longer true for `clj/*`, which is exactly what `d4e31b74` changed. `:2008-2010` is still a comment describing a `yin.k/outcome-of` arm that does not exist, sitting above the `dsg__let` arm. `:2461-2463` still says the guest library is "written for … the outcome model … unwrapped by the compiler's own early-return pattern". Twelve deftests named `test-outcome-*` exercise the continuation lane. |
| P3-3 | P3 (carried) | `B:clojure.cljc:2205-2218`; `B:prelude.cljc:180-197` | `clj/run-main` still returns `[:yin.k/error <recovered>]` as an ordinary **value** for a unit's top-level escaping throw, because `clj/unwrap-outcome` strips only `:yin.k/ok`. Outside R1's scope (which names function/closure/higher-order entries, not the program entry) and it is the pre-existing harness contract — but it is the last outcome-tag path in production lowering, and the carrier recovery now flows through it. Record as a known gap or convert. |
| P3-4 | P3 (carried) | `B:clojure.cljc:1183,1204-1212` | The entry resolves `:yang.call/raise` at raise time, so a record without one surfaces as a not-callable refusal thrown *from inside the handler* rather than the convention's `:missing-raise-exit`. Guarded upstream by `yang.call/invoke`'s own `data/callable?` check — which is now itself pinned (P2-2). |
| P3-8 | P3 (new, trivial) | `B:prelude.cljc:156-158` | `clj/try-finally`'s comment still reads "normal exit runs `fin` here; throw and **escapes** run it as they unwind through the `:finally` frame" — the escape path went out with `clj/call-ec`/`clj/unwind-to` in `f15423c2`. Same for `clj/raise`'s "with no handler above it the value escapes the whole stack". Cosmetic. |

## What is good

The carrier is a faithful, minimal implementation of the consult rather than an approximation of it:
three mints at exactly the three named condition-producing sites, one reader per observation point,
the egress expression reproduced literally including the single query and the tail mark, the private
lane left handler-free, and the projection confined to read sites so the sealed reference survives
throw and save. Using cc3's already-landed provenance primitive instead of a new seam is the right
reading of the owner's NO, and it shows: **zero kernel lines across the entire port span.** The pin
set added for it is the strongest part of the round — alias, overwrite, finally, saved-across-
activations, content-clone, and never-applies-a-thrown-closure, all on both lanes — and the
`missing-raise-exit` re-pin landed host-side over both invocation variants and every VM, which is
broader than the frontend-specific pin it replaced. `d4e31b74` is a two-line predicate that makes
admission stricter rather than laxer, and the linker machinery that enforces it is untouched.
The P2 round closed all three findings, and closed P2-3 by deleting the dead surface rather than
testing it.

## Recommended disposition

Nothing here blocks the code. **P2-4 should be settled before the merge request** — it is the one
item the FINAL DIRECTIVE makes a pre-merge obligation, and it costs one regeneration pass. The P3s
are merge-request text and follow-up pins.

Copy-pasteable verification for a seat that has a shell:

```sh
cd /Users/sto/workspace/datomworld-r3-orch
git status --porcelain                                  # confirm clean at d4e31b74
git diff origin/master...d4e31b74 --stat                # expect the §0 file set, nothing else
git diff origin/master...d4e31b74 -- src/cljc/yin/vm/   # expect only docs/yin.repl.md (master-side)
git diff origin/master...d4e31b74 -- test/yang/call/    # expect EMPTY
clojure -M:test -n yang.clojure-test                    # expect 105 tests, 0F 0E
clojure -M:test -n yang.core.call-test                  # the re-pinned missing-raise-exit
clojure -M:test -n yang.latte-oracle-test
# P2-4: regenerate the depth evidence at this SHA and re-write the comparison table
clojure -M:test -n yang.call.exception-source-test      # P1-1 (owner's): still expected RED until ruled
```

**APPROVE WITH FINDINGS** — P2-4 (depth evidence not attributable to `d4e31b74`; re-record before
merge); P3-1, P3-5, P3-6, P3-7, P3-2, P3-3, P3-4, P3-8 as listed. Items 1-6 of the brief all
**PASS**: the carrier matches sol's spec on every point, the saved case is pinned twice against the
kit's own `retain`/`later` shape, the donor-link fix declares the runtime correctly and leaves the
free-name admission rule untouched (and stricter in effect), R5 holds with the mint unreachable from
source or macros, all three P2s are closed, and neither the kit nor `src/cljc/yin/vm/` changed
anywhere in the port span. The port is ready for shiva once P2-4 is re-recorded; the kit's
`finally`-refusal row remains the owner's to rule on and is the one thing that will still go red.

— qwen3.8-max, independent of both implementer families. I read no sibling reviewer's output for
this round; the only peer artifact I opened was the sol consult named in the brief, which is the
spec I was asked to check against.
