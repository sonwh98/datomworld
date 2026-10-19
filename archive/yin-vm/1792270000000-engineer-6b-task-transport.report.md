# Slice 6b report: full task transport and the safepoint harness on the reference lane

Engineer: claude-opus-5-5, 2026-10-12. Worktree `/Users/sto/workspace/datomworld-srvm-p6b`, branch
`srvm-phase6b` (base `73af17ac`). **Nothing committed.** Every lane ran in the foreground on the JVM. Node
and Dart were not run (per the brief).

## Step 0: prerequisites (all present, nothing built)

- `semantic_register.cljc`: `link-format` (:409), `spawn-module` (:411), `lift-closure` (:434),
  `lower-closure` (:454); the retained `free-env` field (:24, set in `create-vm` :394, a gc root :489).
- `yin.vm.semantic-register.scan` over v4 vectors: `free-occurrences`, `free-names`, `requirements`
  (5c merged, `e5df547a`).
- 4b stage 2 behaviours: FFI retained-to-sent restoration (`register-restore` :101-126, the `:request-sent`
  branch; `effects_test` `ffi-retained`, `retained-response-route`); link request/response/install entries
  keep the payload (`link_test` `require-installs-and-preserves-delivery`, `tail-require`); install children
  under `:installs` (engine `start-install` / `advance-install`).

## Files changed

| File | Change | Lines |
|---|---|---|
| `src/cljc/yin/vm/ucf/rows.cljc` | `lift-task`, `lower-task` and their helpers (section "Task transport"); ns doc and requires | +612 / -5 (1727 total) |
| `test/yin/vm/semantic_register/safepoint_harness_test.cljc` | **new**, the `:semantic-register` lane | 963 |
| `test/yin/vm/store_write_audit_test.clj` | allowlist: `restore-task`'s `engine/store-put` and one `assoc` of `:store` (the handoff lower's pattern) | +7 / -1 |

Not edited (checked with `git diff --quiet`): `semantic_register.cljc`, the old harness
`test/yin/vm/ucf/safepoint_harness_test.cljc`, `debruijn/stack.cljc`, `handoff.cljc`.

### API

- `(rows/lift-task m serve!)` / `(rows/lift-task m serve! opts)` → a version-3 carrier. `opts`: `:free-env`
  (overrides; default is the kernel's retained `:free-env`; `:yin.k/non-portable :missing-free-env` only when
  neither exists), `:source-index` `{A #{tree-address}}`, `:budget`. A refusal throws `ex-info` whose
  ex-data is `{:yin.k/status … :yin.k/kind …}`, like the codec's own refusals.
- `(rows/lower-task recv wire attach!)` / `(… opts)` → the restored machine, or a thrown refusal.
  Order: `decode` (gate, structure, graph, hashes, canonical form, numbering, frames) → for every task of the
  tree: role, custody, pendings (`:install` names an install row), kind consistency, `requires`/`code`
  agreement, `:yin.k/trees` completeness, closure segments, `:prim` names in the receiver → **only then**
  `attach!` (one per stream identity) → restore (code by A via `attach-module`, fresh resource keys, values
  re-sealed, waits in `lower-frame!` shapes, parked records, store through `engine/store-put`, module
  stores, install children spawned and restored, free env as `:env` and `:free-env`). Nothing runs.

## Tests

- Harness: 14 deftests. Quick tier (`-e :slow`): 13 tests, 357 assertions, 0 failures. Slow tier
  (`-i :slow`): 1 test (`every-row-every-segment-matches-the-reference`), 609 assertions, 0 failures.
- **89 runs**: `(is (= 89 (count cases)))` and `(is (= 89 @runs))`, so 22 wrapped rows (1-19, 21, 22, 24)
  × 4 segments + row 20 once. Each run checks laws 1, 2, 3 and 6. Law 2 compares
  `rows/canonical-under` root ids. The stream correspondence comes from the fixture: the source table's
  identities map to themselves; the relift table's identities map through a recording `attach!`
  (reflection handle → source identity).
- `yin.vm.semantic-register.safepoint-harness-test` + `yin.vm.ucf.rows-test` +
  `yin.vm.store-write-audit-test`: 45 tests, 939 assertions, 0 failures.
- Every `yin.vm.ucf.*` and `yin.vm.semantic-register.*` namespace (`clojure -M:test -e :slow -r
  'yin\.vm\.(ucf|semantic-register)\..*'`): 54 namespaces, 830 tests, 18624 assertions, 0 failures. This
  sweep ran before I added the last assertions to the harness (the lowered-tail no-window check and the
  tree-association refusal). The harness was rerun green after that, and `rows.cljc` has not changed since
  the sweep.
- kondo: `clj -M:kondo --lint` on all three changed files: 0 errors, 0 warnings.
- Timing: the whole slow run, JVM start included, took 10 s wall on the laptop. The matrix is tagged
  `^:slow` + `slow/guard` per the brief. `bare-rows-match-the-reference` (quick tier) runs every row once,
  bare, so the quick tier still covers all 23 rows.

### Acceptance items

1. `every-row-every-segment-matches-the-reference` (slow, 89 runs); quick slice
   `bare-rows-match-the-reference`.
2. `tail-states-carry-no-window` (rows 6, 9, 12, 20: `:tail` state with 3 slots, wire row `[id :tail A
   site k]`) and `tail-states-lower-to-through-k-delivery` (lowered entry `{:deliver :return}`, no
   `:window`/`:env`/`:pc`; row 20 has an empty K and halts with `"B"`).
3. `install-rows-carry-the-child` (rows 10-12): the child task's kind and phase, role `:child`; on lower
   the child has nil `:control`, is still blocked on its one `:next`, and its code is attached by A. See
   design bug B1.
4. `captures`: rows 14-16 round-trip. Row 15: the capture travels in E with no value in its `rd`, and the
   lowered run yields `:done`.
5. `free-env-shadowing`: row 21 yields 99 after lower. The explicit `:free-env` overrides a kernel field
   that disagrees (`{x 5}` is refused `:inconsistent-state`; with `{:free-env {x 99}}` the root carries x 99
   and the receiver gets it). With neither, the lift refuses `:missing-free-env`.
6. `synthetic-tail-bookkeeping` (row 23): `nil` under a live call id; `:parked-site` under a key outside
   `:ffi-call-ids`; `:site` once the site is moved to a non-tail call.
7. `refusals`: every membership tamper of §5.2 is refused `:yin.k/undecodable` with its kind and **0
   attaches**: `:window-extra`, `:window-missing`, `:window-self`, `:destination`, `:return-site` (pc moved
   after a real tail call), `:reason-mismatch`, row 22's dropped `[:lit nil]` → `:window-missing`,
   `:captured-site`, `:parked-site`, E2 (`y` → 8 and lexical `x` removed → `:free-env-mismatch`; lexical
   `x` → 98 lowers), X1 `:reserved-name`. Also tested: the `:tail` fourth slot (see B3), the
   tree-association refusal on lower (0 attaches) and on lift, and the lift refusals
   `:yin.k/not-at-safepoint` (stepped once past a `:const`) and the `:observe` hold
   (`:non-portable :reason-mismatch`), each with 0 serves. `first-defect-order`: a row-18 body with the two
   cursor cells swapped **and** a tampered window reports `:noncanonical-cursor-cells` (the window tamper
   alone reports `:window-self`).
8. `a-response-cursor-shared-by-two-waiters-crosses-as-one-cursor-cell` and
   `a-nonempty-ready-queue-is-not-quiescent`, ported to the row carrier. Also `act-ffi-bookkeeping`
   (row 24's parked-table record under its call id) and `module-store-snapshot` (row 18: two cursor cells
   stay two after lower).
9. kondo clean; JVM green. **`bb test:sub yin.vm` on three hosts was not run** (the brief excluded Node and
   Dart).

## Mutation results (each restored with `cp`; `diff` against the backup and `git diff` of the file against the saved pre-mutation diff were both identical)

| Mutant | Caught? | By |
|---|---|---|
| M1 lower a tail state with a window (`:window {}` on the tail payload) | yes | `tail-states-lower-to-through-k-delivery` (3 fails: rows 6, 9, 12) |
| M2 skip the first-defect ordering (frames judged before the numbering check) | yes | `first-defect-order` |
| M3 drop one pending-row restoration (`:ffi-request` loses `:request-sent`) | yes | `bare-rows-match-the-reference` errors: the relift sees `:put` on an `:ffi-call` site, P2 → `:inconsistent-state` |
| M4 deliver twice into the captured rd (lower pre-fills the reified window's rd) | yes | `captures`, `bare-rows-match-the-reference` (the relift's validator refuses) |
| M5 a refusal row attaches before refusing (`attach-streams!` before steps 8-10) | yes | `refusals` / tree-association case (attaches = 1) |
| M6 ignore the explicit `:free-env` override | yes | `free-env-shadowing` |

M1 and M5 were caught only after I added the two assertions named under Tests. As first written, the tail
test checked only the source entry, and every tamper refusal fired inside `decode`, so neither mutant had a
test that could see it.

## Deviations from the design (with reasons)

1. **Tree association is a lift input.** The kernel holds no source index, so the lift takes `:source-index
   {A #{tree-address}}` from the composition and refuses `:yin.k/unsatisfied` (discovery `:incomplete`,
   `:yin.k/missing {:yin.k/trees …}`) for any carried segment with no entry or an empty set. The harness
   records `A → segment-key [:yin/tree ast]` whenever it projects a vector.
2. **Refusals are thrown** as `ex-info`, not returned as `{:status …}` maps, to match `rows/decode`/`emit`.
3. **Custody**: the root always carries `custody nil` (fork). The lower refuses a non-nil custody with
   `:yin.k/unsupported :custody`. The exclusive and holder path is phase 7.
4. **Linked registry entries do not travel.** The row table has no slot for them, only module stores.
   Row 17's receiver composition is given the source's registry value, so the relift can discharge
   `host.mod/f`. Without that the relift refuses `:yin.k/unsatisfied {:obligations #{host.mod/f}}`.
5. **Free-name discharge runs only at lift** (§7.1), over the free env, every carried E's names, the store,
   the module stores, installing exports, profiled primitives (host-state refused) and the module registry.
   The lower checks segments, trees, closure segments and `:prim` names, but does not re-discharge free names
   against the receiver.
6. **Store slice = the whole store.** Every parked record travels, and the validator judges each one (P4).
7. **The receiver's module registry is kept as given.** There is no `isolated-receiver` pruning of linked
   entries.
8. **The lift validates its frames before emitting** (`validate-frames`, failure → `:inconsistent-state`).
   It does not decode its own output a second time.
9. **Lower-side kinds of my own choosing** for steps 8-10: `:role`, `:custody`, `:incomplete-install`,
   `:kind`, `:parked-id`, `:requires`, `:segment`. The design names none of them.
10. **Fixtures**: row 18 keys its hand-built module store by the symbol `host.mod`, as `frame_test` does,
    while the kernel keys module stores by module address. Row 13 resumes with a beside-load of
    `{:type :vm/resume …}`. Row 20's setup batch defines `s` and `c` in the store.

## Design bugs found (the design text is not changed)

- **B1 (rows 10 and 11).** Row 10's state "child `:blocked`, phase `:running`" is not reachable at a
  quiescent safepoint: after any `vm/run` or `check-wait-set`, a blocked child is at phase `:parked`
  (probed). Row 11's "child executing `:park`, kind `:parked`" is not reachable at all. A parked child is
  `:halted?`, so `advance-install` goes to `link-install`: the install completes if the exports are already
  defined, and otherwise refuses with `:export-missing`. The harness uses row 10 = the engine's actual state
  (child `:blocked`, phase `:parked`) and row 11 = the same machine with phase set to `:running` by hand (a
  state the engine passes through inside one sweep), which still exercises the `:running` wire phase. An
  Architect should rewrite both rows.
- **B2 (row 21 under `:depth-3`).** `nest`'s own lambda parameter is `x`, so `(fn [] x)` captures the
  wrapper's `x = 1`. The reference itself answers `[3 [2 [1 1]]]`, not `[3 [2 [1 99]]]`. The mechanical
  wrapper rule of §5.2 does not hold for this row. The harness pins 1 for that one case (the lowered run
  matches the reference).
- **B3 (refusal "a `:tail` row given a fourth slot → `:frame-shape`").** The codec's structure step (2)
  refuses first with `:yin.k/undecodable :row-shape`, because the row table fixes `:tail` at 3 slots. S1
  cannot see it from the wire. The test asserts `:row-shape`. Relatedly, `emit`/`emit-numbered` silently
  drop extra slots from an abstract node (`map` over the shorter descriptor list), so the tamper has to be a
  raw row edit with a rehash.
- **B4 (§7.1 order).** The listed order puts the dependency closure before numbering and the walk, but the
  segments are known only after the walk. The closure therefore runs after the walk, before frame
  validation and emit.

## Other findings and uncertainties

- `handoff.cljc` `export-task` builds installing names as `(symbol (str m "/" x))`, which is a symbol with a
  nil namespace and never equals `m/x`. This is likely a latent bug there; I did not edit it. Mine uses
  `(symbol (name m) (name x))`.
- Portability was reviewed by hand only: no `for`, no `#'`, no multi-key `assoc` on nil (every target is an
  existing map or record), catches use `#?(:cljd Object :clj Throwable :cljs :default)`, no unary minus, no
  new protocol methods. The `.cljc` harness has not run on Node or Dart. `fn?` and `cbor/numeric?` on Dart
  follow `handoff.cljc`'s existing use.
- Law 2 passed on every run, so no receiver's `id-counter` exceeded the carried one. A receiver whose
  construction mints more ids would relift with a different counter.
- No git command was blocked. I committed nothing and ran no `git merge`. Logs are under `target/`
  (`p6b-*.log`, `m1..m6.log`, `final-*.log`).
