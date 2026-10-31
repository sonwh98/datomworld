# Engineer report — linker over dao.jing.dht, slice L5 (end to end)

Model: claude-opus-5-5. Worktree `/Users/sto/workspace/datomworld-linker-l2`, branch `linker-l5` (from `e5b856b9`). Nothing staged or committed.

## Files touched (only the L5 list of §12)

| File | Change |
|---|---|
| `test/yin/repl/dht_process_test.clj` | `start-anchor!` takes core-state opts, so a storing in-JVM peer is possible. New deftest `a-module-published-by-name-is-required-by-name-across-processes`, plus its helpers (bounded `await-count`, `await-head-published`, `step-until`, `require-on-each-vm`, `plain-clojure-leg`). The ns docstring now covers L5. |
| `test/yin/vm/linker/dht_end_to_end_test.cljc` (new) | The same scenario in process over the mesh seam, on JVM, Node and Dart. |
| `docs/agents/build-n-test.md` | Describes the L5 gate, with its two tests and how to run them, and why the Dart leg runs in process. |

No source file changed. The mutations below were temporary edits and were reverted; `git status` shows only the three files above.

## Acceptance → evidence

**1. Process A defines a function and publishes it as a module; the test reads A's index manifest address and principal from A's output.**
- Process A runs as `yin.repl.main` on the JVM. Its flags are `dht:<dir>`, two storing peers (`--dht-peer` ×2), `--dht-publish` and `--dht-key <file>`. The key file is written by `main/keygen!`, the same code `--dht-keygen` runs.
- The principal is parsed from A's banner line `names published here are signed by principal ed25519:<hex>`.
- A types `(def f (fn [x] (+ x 4200)))`, `(def base 7)` and `(def g (fn [] (+ base 1)))`. It then runs `(yin.link/publish 'my.lib '[f])` and `(yin.link/publish 'my.store '[g])`.
- The test asserts that both `{:module 'my.lib …}` and `{:module 'my.store …}` are printed.
- The manifest is the last `dht: published :segment/… — acknowledged` line in A's output (`await-head-published`). That line is cross-checked against A's `HEAD`.

**2. Process B (`--dht-manifest`, `--dht-principal`) requires the module by name, waits through pending, and evaluates the export on each of the four VMs; B runs on the JVM and on Node.**
- Readers `reader-b-jvm` (`java … yin.repl.main`) and `reader-b-node` (`node target/yin-repl.js`) each get `--dht-manifest :<M> --dht-principal <hex>`.
- `require-on-each-vm` takes each VM in turn: `(vm X)`, then `(require 'my.lib)` (waits for `'my.lib`), then `(my.lib/f 1)` (waits for `4201`). Each step waits for its answer before the next line is typed. This is needed because `(vm …)` is answered at once even while a require is pending, so lines typed ahead would land on the wrong VM.
- The test asserts `;; require pending: my.lib` appeared, which shows the reader waited through pending.

**3. Plain Clojure does the same through `join → load-index → names → load-module → link`, runs the image, and asserts each failure result of §9 as data.** This is `plain-clojure-leg`: a plain `dao.space.dht/join` in the test JVM over real UDP. Its peers are a storing anchor and A.
- Success path:
  - `load-index M` reaches `:loaded`.
  - `ld/snapshots` is `[M]`.
  - `ld/names` resolves `my.lib` with provenance `[principal]`, and `resolve-name` agrees.
  - `ld/load-module` fetches the closure from peers (`:fetched` > 0).
  - `ld/link` is ok on all four formats, and every image runs on its own VM backend and defines `f`.
  - The export evaluates: `(f 1)` = 4201 on the walker.
  - The store corpus links on semantic, stack and register.
- Failures, as the plain functions answer them:
  - `:absent` (name): `{:status :refused :reason :absent :name 'my.lib}` with `[[:undeclared-principal P]]`.
  - `:ambiguous-name`: a second key's index asserts `my.lib` at another address. The answer carries `:addresses` = both and `:asserters` = both.
  - `:absent` (content): a load of an address no node holds fails `{::dht/failure :miss :cause ::jing.dht/exhausted}` over the real network.
  - `:descriptor-defect`: a schema-2 manifest fails `:invalid` with `:code :manifest-defect`.
  - `:yin.link.dht/dependency-binding`: an `app` pinned to `my.lib` binds `:absent` without the principal (`:undeclared-principal`) and `:ok` with it.
  - Linker's own refusal: `my.store` on `:yin.ast/code` is `:undeclared-free :name base`.
  - `:yin.link.dht/closure-incomplete`: a blob is lost after `:loaded`.
  - `:yin.link.dht/unaskable`: the outcome is `:request-undeliverable`.
  - `:yin.link.dht/not-loaded`: a link before the load.
- One note on shapes: the content-miss, defect and unaskable cases are asserted in `dao.space.dht`'s raw load-status shape. `yin.repl.link/load-refusal` turns that shape into the §9 response row. Those REPL-shaped rows are already asserted in L3's `yin.repl.dht-test`.
- `:yin.repl/abandoned` and `/link-policy` are REPL-only and have no plain-function form.

**4. Without `--dht-principal`, B's require is `:absent` with an `:undeclared-principal` diagnostic.**
- In process: reader `reader-c-undeclared` is identical except it has no principal. It prints `Module link refused: absent`. Its `(yin.link/names)` output carries `:undeclared-principal` and A's `ed25519:<hex>`.
- In the cljc test: `without-the-principal-the-require-is-absent-undeclared` asserts the exact response, `{:status :refused :reason :absent :name 'my.lib}` with diagnostics `[[:undeclared-principal P]]`.

**5. The in-process form passes on Dart over the mesh seam, signatures verified, on all four VMs.** This is `yin.vm.linker.dht-end-to-end-test`:
- `a-reader-requires-a-published-module-by-name-on-all-four-vms`: the publisher shell holds a key and publishes at the prompt. The reader shell is composed with `:manifest` (hydrates) and `:principals`. The names are signed by P. On each VM, the require pends and then completes with no typed line, and `(my.lib/f 1)` gives `"4201"`.
- `a-proof-over-another-envelope-does-not-resolve` checks that signatures are verified, not merely present. A forged index carries the real envelopes, each with a proof made by the same key over a different envelope. A reader that declares P is refused `:absent` with `[[:unauthenticated :bad-proof]]`.
- I proved it ran on Dart because the compact Dart reporter never names `yin/vm/linker/*` files. I temporarily changed one expected value to `"4201-PROBE"`. `bb test:cljd` then failed with `Expected: (= "4201-PROBE" answer)  Actual: … "4201"`. I reverted the probe and re-ran the lane: green (see Lanes).

**6. The store corpus (an export reading a module-level definition) evaluates on the semantic, stack and register VMs and refuses on the walker with the linker's reason.**
- In process, JVM and Node readers: `(my.store/g)` gives `8` on semantic, stack and register. The walker prints exactly one `Module link refused: undeclared-free`.
- cljc test: the walker's response is `{:status :refused :reason :undeclared-free :name base}`, and the other three VMs are `:ok` with `"8"`.
- A's publish result shows the same split: `:links {:yin.ast/code :undeclared-free, …semantic/stack/register :ok}`.

**Brief extras.**
- Ephemeral ports are reported back: two storing anchors bind port 0 and report `:bound`, and every REPL reports `listening on host:port`. Nothing is reserved ahead, so there is no reservation race.
- Every wait is bounded and fails with a cause: `await-count`/`await-line` include the transcript, `step-until`/`run-world` include a cause string, and `require-on-each-vm` names the VM and the line that never answered.
- The Node reader uses `bb build:yin-repl-node`. A missing build fails the test with that instruction.
- There is no Dart reader process. `build/yin-repl-peer` is the R5 slice peer (`yin.repl.slice-peer`), not `yin.repl.main`, so the peer build does not allow one. Dart is covered by bullet 5, as the contract asks.

## Tests-first / mutations (each run, seen failing, reverted)

L5 adds tests only, so each core property is shown by a mutation the new tests catch:

| # | Mutation (temporary) | Property | Result |
|---|---|---|---|
| M1 | `yin.vm.linker.dht/authority` `:verify (constantly true)` | signatures verified | cljc e2e: 2 failures (`a-proof-over-another-envelope…` resolved `:ok`, no `:bad-proof`) |
| M2 | `ld/fold` declares every asserter seen ("trust on sight") | undeclared principal refused | cljc e2e: 3 failures. **Process test**: 5 failures (reader C, plain `:absent`, plain dependency-binding) |
| M3a | `yin.repl.link/dht-attempt` `:loading` → refused | waits through pending | **survived**: a `:loading` attempt never happens on this path, because the re-check runs only on the terminal event. Retargeted ↓ |
| M3b | `dht-attempt` no-record → refused instead of `::pending` | waits through pending | cljc e2e: 5 failures (no `pending`, `last-value` ≠ `my.lib`, `my.lib/f` unresolved) |
| M4 | `yin.repl.link/dht-link` flattens linker refusals to `:absent` | store corpus refuses with the linker's reason | cljc e2e: 2 failures |
| P | expected `"4201-PROBE"` in the cljc test | test actually runs on Dart | `bb test:cljd`: `+2556 -1`, actual `"4201"` |

First runs also produced real failures that shaped the tests:
- `{:module 'my.lib` prints with the quote.
- `(vm …)` is answered during a pending run, so per-VM lines must be sequenced.
- The semantic VM cannot `eval` a raw AST, so the export is evaluated on the walker.
- Dart failed to compile `_ (is …)` let bindings (`is` is void on Dart). I restructured them.

## Lanes (all run to their verdict; final code unless noted)

| Lane | Verdict |
|---|---|
| kondo (`clj -M:kondo --lint` on both test files) | errors 0, warnings 0 |
| `bb build:yin-repl-peer` | built `build/yin-repl-peer` |
| `bb test:clj` (incl. `build:yin-repl-node`) | **Ran 2689 tests, 224243 assertions, 0 failures, 0 errors**; `yin.repl.dht-process-test` and `yin.vm.linker.dht-end-to-end-test` both ran |
| `bb test:cljs` | **Ran 2602 tests, 90308 assertions, 0 failures, 0 errors**; `Testing yin.vm.linker.dht-end-to-end-test` present (re-run on the final file) |
| `bb test:cljd` (after `rm -rf test/cljd-out`) | **+2557: All tests passed!** |
| process test alone, final | `Ran 1 tests containing 57 assertions. 0 failures, 0 errors.` |

The full `bb test:clj` ran before the Dart-portability restructure of `dht_end_to_end_test.cljc`. That change moves the `is` calls out of let bindings and asserts nothing new. After it, I re-ran that namespace alone on the JVM (`Ran 3 tests containing 46 assertions. 0 failures, 0 errors.`), and the Node and Dart lanes ran on the final file. The full JVM lane was not repeated: it takes longer than the tool's foreground cap.

## Owner invariants

- One path: the plain leg calls the functions `yin.repl.link` and the host modules call (`names`/`resolve-name`, `load-module`, `module-status`, `dependency-bindings`, `link`).
- P2P with no privileged node: every process binds an ephemeral port, the anchors are ordinary core nodes, and there is no server or client role.
- `apply` is untouched, and no rpc concepts were introduced.
- Nothing is persisted that a query could derive: the tests read names from the fold.
- `dao.jing` stays passive: the tests only add composition.

## Open items for the reviewer

- §9 rows are asserted in the plain API's raw shapes (bullet 3 note). If the Architect wants the REPL row shapes produced from plain Clojure, that needs a public `load-refusal` in `yin.repl.link` or `yin.vm.linker.dht`, which is outside L5's files.
- The process test takes several minutes, so `build-n-test.md` says how to run it alone.

## Fix round 1 (gpt-6-sol review, `1790840000000-architect-linker-epic-review.gpt-6-sol.findings.md`)

Scope: the HIGH finding only. The MEDIUM finding (dangling retraction diagnostics) waits on an owner decision and was **not touched**. The orchestrator authorized the extra files: `src/cljc/yin/vm/linker/dht.cljc`, `src/cljc/yin/repl/link.cljc`, `test/yin/vm/linker/dht_test.cljc`, and §10 of the design doc.

### HIGH: §9 rows for a failed load are now a public plain function

- **New `yin.vm.linker.dht/load-refusal`** (`src/cljc/yin/vm/linker/dht.cljc`): `(load-refusal status)` takes the load status that `module-status` answers. For a `:failed` load it answers the §9 row:
  - `:miss` gives `{:status :refused :reason :absent :address a :cause c}`.
  - `:invalid` gives `{… :reason :descriptor-defect :address a :code k}`, plus `:detail` and `:text` when present.
  - `:unaskable` gives `{… :reason :yin.link.dht/unaskable :address a :outcome o}`.
  - It answers nil for a load that has not failed.
- **One conversion.** `yin.repl.link`'s private `load-refusal` is deleted. `dht-attempt` now answers `(linker.dht/load-refusal status)`. The REPL rows are unchanged: the L3 tests in `yin.repl.dht-test` pass as before.
- **Design doc.** §10 gains a `load-refusal` row. The interface-gate bullet now lists `load-refusal` among the functions `yin.repl.link` calls.
- **L5 plain leg** (`plain-clojure-leg` in `test/yin/repl/dht_process_test.clj`): the three raw-status assertions are replaced by exact §9 rows from `ld/load-refusal (ld/module-status …)`, over real UDP:
  - `{:status :refused :reason :absent :address nowhere :cause ::jing.dht/exhausted}`
  - `{:status :refused :reason :descriptor-defect :address bad :code :manifest-defect}`. `:detail`/`:text` are dissoc'd, as L3 does.
  - `{:status :refused :reason :yin.link.dht/unaskable :address elsewhere :outcome :request-undeliverable}`
  - This also closes the open item above.
- **Unit test on all three hosts.** `a-failed-load-is-its-section-9-row` in `test/yin/vm/linker/dht_test.cljc` checks:
  - each of the three rows, with the defect row both with and without `:detail`/`:text`, and extra defect keys dropped;
  - nil for `nil`, `:loading` and `:loaded`;
  - a real solo-node failed load, which converts to `:absent` with `:cause ::jing.dht/solo`.
- **Tests first.** The unit test was red before the function existed (`No such var: ld/load-refusal`). Two of my own expectations were wrong and were corrected against observed behaviour: a solo load needs a second step before it fails, and the cause keyword is `:dao.jing.dht/solo`.

### Mutations (each temporary, seen failing, reverted)

| # | Mutation of `load-refusal` | Failing assertions |
|---|---|---|
| F1 | `:miss` row drops `:cause` | unit: `:miss` row, real solo load (2 failures) |
| F2a | `:invalid` row drops `:text` | unit: the defect row with detail and text (1) |
| F2b | `:invalid` row has the wrong `:code` | unit: both defect rows (2) |
| F3 | `:unaskable` row drops `:outcome` | unit: the unaskable row (1) |
| F4 | no `:failed` guard (a row for any status) | unit: nil, `:loading`, `:loaded` (3) |
| F5 | F1 + F2b + F3 together | **process test plain leg**: `:absent` content, `:descriptor-defect`, `:yin.link.dht/unaskable` rows (3 failures of 57) |
| F5′ | same as F5 | REPL `yin.repl.dht-test`: 5 failures (solo, defect, unaskable, partial-round, four-VM refusal). This shows the REPL goes through the same function. |

After reverting, `git diff` of the two source files shows only the intended change.

### Lanes (final code, all foreground, all to their verdict)

| Lane | Verdict |
|---|---|
| kondo on all 5 changed `.clj`/`.cljc` files | errors 0, warnings 0 |
| `bb test:cljs` | **Ran 2603 tests, 90317 assertions, 0 failures, 0 errors** (both `yin.vm.linker.dht-test` and `…dht-end-to-end-test` ran) |
| `bb build:yin-repl-peer` | built |
| `bb test:cljd` (after `rm -rf test/cljd-out`) | **+2558: All tests passed!** (+1 vs before: the new unit test) |
| `bb build:yin-repl-node` | built |
| JVM lane, `clojure -M:test` in three regex chunks (`dao\..*`, `yin\..*`, the rest) | 1347 tests/211257 assertions, 1221/12062, 122/938: **2690 tests, 0 failures, 0 errors**. The chunks cover 168 namespaces, the same 168 the full lane runs. |
| process test alone | `Ran 1 tests containing 57 assertions. 0 failures, 0 errors.` |

Two notes on the JVM lane:
- **Why chunks.** `bb test:clj` runs longer than the 10-minute foreground cap, and this environment needs approval for any shell wait loop. So the JVM lane was run as `bb build:yin-repl-node` plus `clojure -M:test` split into three chunks, each in the foreground.
- **A failure I saw and how it went away.** My first, backgrounded `bb test:clj` run hit one failure: `dao.jing.dht` `three-real-loopback-sockets-exchange-chunked-store` (`socket_test.clj:71`). It is a real-socket timing test. It failed while I was also running the process test in parallel. No code under it changed. I stopped that run; in the clean chunked run, with nothing concurrent, it passed.

## Fix round 2 (owner decision 6: dangling retraction is a global diagnostic)

Owner, verbatim: "Global diagnostic". A retraction whose referenced assertion is outside the loaded snapshot set is reported **once**, as a global diagnostic that is not attached to a name. It carries the retraction's principal and the assertion id it names. The envelope is unchanged. The files are the same authorized set; this round edited `src/cljc/yin/vm/linker/dht.cljc`, the design doc, and the two cljc test files.

### Implementation (`yin.vm.linker.dht`)

- **`fold`.** `authority/name-environment` is unchanged. After it runs, `fold` splits its `:diagnostics`:
  - A `:dangling-retraction` whose `:of` is not among the envelopes of the snapshot set moves to a new key, `:global-diagnostics`.
  - That entry keeps the authority's diagnostic fields, including `:principal`, the retraction's principal, and `:of`, the assertion id it names.
  - It appears there once: events are already deduplicated by content id, and the split only partitions them.
- **`names`.** Answers both keys; the docstring states this.
- **`resolve-name` and `dependency-bindings`.** Their per-name diagnostics come from `diagnostics-for` over the remaining `:diagnostics`, so a global entry can never be attached to a name.
- **Retraction inside the set.** When the referenced assertion is in the set, for example principal B retracting principal A's assertion, which B cannot do, the diagnostic stays per-name. The reader knows the name from the referenced envelope's own content hash.
- **REPL.** `(yin.link/names)` is answered by `ld/names` (`yin.repl.query`, unchanged), so `:global-diagnostics` reaches the prompt through the same plain path.

### Design doc

- **§7.3.** The fold's answer shape gains `:global-diagnostics`. A new bullet states the rule:
  - which retraction is global, and why (the envelope carries only the assertion id);
  - that it is reported once, with `:principal` and `:of`;
  - that it is in no per-name diagnostic;
  - the in-set case.
- **§9.** The `:absent` (name) row lists `:dangling-retraction` only for an assertion inside the snapshot set. It points to `:global-diagnostics` for the rest.

### Not done: "print it once per change"

Stated plainly:
- **The premise doesn't hold.** The brief says to print it "once per change, as the fold diagnostics already are". No such printing exists today: the REPL never prints fold diagnostics. They reach the user only as the data `(yin.link/names)` returns.
- **What it would take.** Printing on change would be a new mechanism: shell state remembering what it last printed, plus a hook in the step or ticker. That lives in `yin/repl.cljc`, `yin/repl/main.cljc` or `yin/repl/dht.cljc`, none of which is authorized.
- **What I did.** Per the brief's "if you need another file, stop and report", I did not build it. The global diagnostic is surfaced once, as data, by `(yin.link/names)`, and the REPL test asserts exactly that.
- **For the orchestrator.** If a printed line is wanted, authorize one of those files and say which event triggers the print: an index load, a HEAD change, or both.

### Tests (all three hosts)

- **Unit** (`test/yin/vm/linker/dht_test.cljc`): `a-retraction-of-an-assertion-outside-the-snapshot-set-is-global`. The retraction sits in two loaded snapshots, and the assertion it names was never published.
  - `:global-diagnostics` is exactly `[[:dangling-retraction P1 <id>]]`, once.
  - `:diagnostics` holds no `:dangling-retraction`.
  - `resolve-name 'base` is `{:status :refused :reason :absent :name base :diagnostics []}`.
  - The other name, `app`, still resolves.
  - In-set case: P2 retracts P1's published assertion. `:global-diagnostics` is `[]`, the per-name `:diagnostics` is `[[:dangling-retraction P2]]`, and `base` still resolves.
- **REPL** (`test/yin/vm/linker/dht_end_to_end_test.cljc`): `a-dangling-retraction-is-one-global-diagnostic-at-the-prompt`.
  - The publisher signs a retraction of an assertion it never published and announces that index.
  - A reader hydrates it, declaring the principal. `(yin.link/names)` at the prompt carries `[[:dangling-retraction P <id>]]` under `:global-diagnostics`, and none under `:diagnostics`.
  - `(require 'my.lib)` is refused `{:status :refused :reason :absent :name my.lib :diagnostics []}`.
- **Red first.** The unit test's global assertions failed before the fix: 3 failures, with no `:global-diagnostics` key and the dangling entry still in `:diagnostics`. The two per-name-absence assertions already held before the fix, because a missing envelope gives no name. Mutation G3 below shows they still catch a leak.

### Mutations (each temporary, seen failing, reverted; `git diff` shows only the intended code)

| # | Mutation in `fold` / `diagnostics-for` | Assertions that failed |
|---|---|---|
| G1 | no split (nothing global) | unit: global once (662), not in `:diagnostics` (666). REPL: global (326), not in `:diagnostics` (330) |
| G2 | global entries also left in `:diagnostics` | unit 666, REPL 330 |
| G3 | `diagnostics-for` appends the global diagnostics to every name | unit: `resolve-name` has empty diagnostics (668). REPL: the require's response (334) |
| G4 | every dangling retraction global, ignoring envelope presence | unit: in-set global empty (677), stays per-name (678) |
| G5 | the global entry listed twice | unit 662, REPL 326 ("once") |
| G6 | global diagnostics empty the name map | unit: the other name still resolves (670) |
| G7 | a dangling in-set retraction removes the name it targets | unit: P2 cannot retract P1's assertion (680) |

Every new assertion failed under at least one mutation.

### Lanes (final code, all foreground, all to their verdict)

| Lane | Verdict |
|---|---|
| kondo on the 5 changed `.clj`/`.cljc` files | errors 0, warnings 0 |
| `bb test:cljs` | **Ran 2605 tests, 90335 assertions, 0 failures, 0 errors** (`yin.vm.linker.dht-test` and `…dht-end-to-end-test` both ran) |
| `bb build:yin-repl-peer` | built |
| `bb test:cljd` (after `rm -rf test/cljd-out`) | **+2560: All tests passed!** (+2: the two new tests) |
| `bb build:yin-repl-node` | built |
| JVM `clojure -M:test` in three regex chunks | 1347 tests/211257 assertions, 1223/12080, 122/938: **2692 tests, 0 failures, 0 errors**. The chunks cover the full lane's 168 namespaces, including `yin.repl.dht-process-test`. |

The JVM lane ran in chunks for the same reason as Fix round 1: the 10-minute foreground cap.

## Fix round 3 (gpt-6.1-sol r2: three MEDIUM defects, `1790843000000-architect-linker-epic-review-r2.gpt-6.1-sol.findings.md`)

All three were reproduced by a failing test before the fix. All three are fixed in this worktree (branch `linker-l5`, uncommitted, no staging). The review also confirmed that returning the global diagnostic through `(yin.link/names)` meets owner decision 6. So the "print once per change" item from Fix round 2 is closed with no REPL print added.

### 1. Global-diagnostic classification is by assertion target (`src/cljc/yin/vm/linker/dht.cljc`, `fold`)

- **Fix.** A `:dangling-retraction` is global unless its `:of` names an envelope in the snapshot set whose `:yin.module/op` is `:assert`. Before, it was global only when `:of` named no envelope at all. Only an assertion carries a name, so a retraction targeting another retraction is now global too.
- **Docs.** The docstrings of `fold` and `names`, and the §7.3 scope bullet, state "not an assertion in the set (none, or another retraction)".
- **Plain test** (`test/yin/vm/linker/dht_test.cljc`, a new `testing` in `a-retraction-of-an-assertion-outside-the-snapshot-set-is-global`): R1 retracts an unpublished assertion, and R2 retracts R1. Both appear in `:global-diagnostics`, keyed by their `:of`, each once (count 2), and neither appears in `:diagnostics`. Before the fix: 3 failures. R2 stayed in `:diagnostics`.
- **REPL test** (`test/yin/vm/linker/dht_end_to_end_test.cljc`, `a-retraction-of-a-retraction-is-global-at-the-prompt`): the publisher announces an index holding R1 and R2, and a reader hydrates it. `(yin.link/names)` carries both, globally, each once, with the principal and `:of`. Neither is per-name.
- **Mutation F1:** back to the "any envelope" rule. 6 failures: 3 plain, 3 REPL.

### 2. Loads sharing a missing blob each keep the miss cause (`src/cljc/dao/space/dht.cljc`, L1)

- **Bug.** `advance-load` removed `:misses a` when the first waiting load failed, so a second load waiting on the same address got `::dht/gap`.
- **Fix.**
  - `advance-load` no longer removes it, on the miss path or the found path.
  - After every loading record has advanced in the step, `advance-loads` keeps only the causes of addresses some load is still fetching: `(update node :misses select-keys (fetching node))`. Every waiting load reads the cause, and none is retained once nothing waits.
- **Test** (`test/dao/space/dht_test.cljc`, `loads-sharing-a-missing-blob-each-keep-its-cause`), three cases:
  1. `/solo`: two loads ask for the same address in the same step. Both fail `{:failure :miss :address a :cause ::jing.dht/solo}`.
  2. Completions spanning several steps: over a mesh with a silent peer, load 1 asks at reading 0, load 2 joins at reading 20, and load 3 at reading 40. All three fail with the same `::jing.dht/exhausted`, and `:misses` is empty afterwards. I first expected `/deadline`; with 10 ms steps the candidates run out first. I corrected my expectation; the shared cause, not `/gap`, is the property.
  3. A load started after the first one failed asks again, and each keeps `/solo`.
  - Before the fix: 4 failures, with the later loads reporting `/gap`.
- **Mutations.**
  - F2a, the destructive `dissoc` restored: 3 failures, the `/gap` regression.
  - F2b, no prune after the step: 1 failure, the cause kept when nothing waits.

### 3. An empty export list is the canonical no-op module, as data (`src/cljc/yin/vm/linker/publish.cljc`)

- **Fix.**
  - New public `yin.vm.linker.publish/no-op-tree`, which is `{:type :literal :value nil}`: one `nil` literal that defines nothing.
  - `sequenced` answers it for zero programs instead of popping an empty vector.
  - So `module-from-index` with `:exports []` answers `{:name n :ast no-op-tree :exports #{} :requires {} :primitives {}}`, whatever the index holds. It publishes, and links ok on all four formats.
  - §5.3's "One tree" bullet states the rule.
- **Plain test** (`test/yin/vm/linker/publish_test.cljc`, `an-empty-export-list-is-the-canonical-no-op-module`, all three hosts):
  - the exact spec;
  - the same spec from a different index;
  - `publish-module!` gives a segment address with all four `:links` `:ok` and `:yin.module/exports #{}`.
  - Before the fix: `IllegalStateException: Can't pop empty vector`.
- **REPL test** (`dht_end_to_end_test.cljc`, `an-empty-export-list-publishes-the-no-op-module-at-the-prompt`): `(yin.link/publish (quote empty.lib) (quote []))` answers `{:module empty.lib … :links {all four :ok}}` as data. A reader hydrating the publisher's HEAD then requires `empty.lib` by name on all four VMs.
- **Mutation F3,** the empty branch removed: the plain test errors with `Can't pop empty vector`, and the REPL test fails 6 assertions. The REPL printed `Error: Can't pop empty vector`, and the reader's require was `:absent` on every VM.

Every mutation was reverted. `git status` shows only intended changes. Files this round: `src/cljc/{dao/space/dht,yin/vm/linker/dht,yin/vm/linker/publish}.cljc`, `test/{dao/space/dht_test,yin/vm/linker/dht_test,yin/vm/linker/publish_test,yin/vm/linker/dht_end_to_end_test}.cljc`, and `docs/design/yin.vm.linker.dht.md` (§5.3, §7.3).

### Lanes (final code, all foreground, all to their verdict)

| Lane | Verdict |
|---|---|
| kondo on all 9 changed `.clj`/`.cljc` files | errors 0, warnings 0 |
| `bb test:cljs` | **Ran 2609 tests, 90374 assertions, 0 failures, 0 errors** (`dao.space.dht-test`, `yin.vm.linker.dht-test`, `…dht-end-to-end-test`, `…publish-test` all ran) |
| `bb build:yin-repl-peer` | built |
| `bb test:cljd` (after `rm -rf test/cljd-out`) | **+2564: All tests passed!** (+4: the four new tests) |
| `bb build:yin-repl-node` | built |
| JVM `clojure -M:test` in three regex chunks | 1348/211266, 1226/12111, 122/938: **2696 tests, 0 failures, 0 errors**. The chunks cover all 168 namespaces, including `yin.repl.dht-process-test`. |

## Fix round 4 (gpt-6.1-sol r3: L5 GRANTED; epic withheld on one MEDIUM, `1790846000000-architect-linker-epic-review-r3.gpt-6.1-sol.findings.md`)

### The defect

`yin.vm.linker/manifest-defect` is the schema-1 validator that the closure walker calls (`closure.cljc` `walk-module`). It called `(vals (:yin.module/index manifest))` without checking the shape first. So a correctly hashed manifest whose optional index was `42`, `[1]` or `"bad"` threw inside the validator. Three things followed:
- the walk was not total;
- the staged load reported `:dao.space.dht/walk-threw` with `:address nil`, instead of `:manifest-defect` naming the manifest;
- `publish-module!` with such a manifest under `:requires` threw instead of refusing as data.

### Fix (`src/cljc/yin/vm/linker.cljc`, 5 lines)

The index stays optional. When present and not nil, it must be a map; otherwise the validator answers `{:rule :manifest-shape :key :yin.module/index}`. Only after that check does it look at the values, as before. Neither `closure.cljc` nor `publish.cljc` needed a change: both already turn the validator's defect into data. The walker gives `:invalid :manifest-defect`, and publication gives `/invalid-requirement`.

### Scan for other assumption-throws on optional or any manifest fields

I made the scan executable instead of reading the code by eye. `the-manifest-validator-is-total-over-every-key` (in `closure_test`) checks every manifest key plus the optional index. Each key gets 8 malformed values: `42 [1] "bad" #{1} '(1) :k {1 2} [[1 2]]`, which is 88 cases. For each, the validator must answer a defect map and not throw. The manifest is then materialized and walked, and the walk must answer `:invalid` with `:manifest-defect` naming that address.
- **Result before the fix:** only `:yin.module/index` threw, for 7 of its 8 values (`{1 2}` already gave a defect). Every other key already answered a defect for every value: each map-valued key is checked with `map?`/`set?`/`symbol?` before it is used, and the reserved-name scan runs only after the declaration keys pass.
- **Result after the fix:** all 88 cases are a defect, and every walk is total.

### Regressions (tests first; all cross-host .cljc)

| Test | Asserts | Before the fix |
|---|---|---|
| `closure_test/a-malformed-optional-index-is-a-manifest-defect-naming-the-manifest` (42, [1], "bad") | validator → `{:rule :manifest-shape :key :yin.module/index}`; **direct walk** → `{:outcome :invalid :address m :role :manifest :path [m] :defect {:code :manifest-defect :detail {...}}}` | 3 failures + 3 errors (`ClassCastException`, `Don't know how to create ISeq`) |
| `closure_test/the-manifest-validator-is-total-over-every-key` | the scan above | 7 + 7 failures, all `:yin.module/index` |
| `linker/dht_test/a-malformed-optional-index-fails-the-load-as-a-manifest-defect` | **staged load** → `load-refusal` = `{:status :refused :reason :descriptor-defect :address m :code :manifest-defect :detail {:rule :manifest-shape :key :yin.module/index}}`, exactly one `:load-failed` | 3 failures (was `walk-threw`, `:address nil`) |
| `publish_test/a-required-manifest-with-a-malformed-index-refuses-as-data` | **publication** requiring it → `{:status :refused :reason :yin.link.publish/invalid-requirement :name dep}`, nothing written | 3 failures (threw) |

### Mutations

- **Original code restored** (the map check removed): every regression above fails, 17 failures + 3 errors in `closure_test` alone. This includes the 7 walk-totality assertions.
- **Mutation G**, a non-map index silently accepted (no throw, no defect): 16 failures across the direct walk, the staged load and the scan. The publication test still passes under G, and that is correct: the requirement walk refuses `/invalid-requirement` later, at the missing index entry. The original throwing code does fail it, 3 failures in the red run.

Reverted. `git diff src/cljc/yin/vm/linker.cljc` is the 5-line fix only.

### Lanes (final code, all foreground, all to their verdict)

| Lane | Verdict |
|---|---|
| kondo on all 11 changed `.clj`/`.cljc` files of the branch | errors 0, warnings 0 |
| `bb test:cljs` | **Ran 2613 tests, 90633 assertions, 0 failures, 0 errors** (`closure-test`, `dht-test` and `publish-test` ran) |
| `bb build:yin-repl-peer` | built |
| `bb test:cljd` (after `rm -rf test/cljd-out`) | **+2568: All tests passed!** (+4: the four new tests) |
| `bb build:yin-repl-node` | built |
| JVM `clojure -M:test` in three regex chunks | 1348/211266, 1230/12370, 122/938: **2700 tests, 0 failures, 0 errors**. The chunks cover all 168 namespaces, including `yin.repl.dht-process-test`. |
