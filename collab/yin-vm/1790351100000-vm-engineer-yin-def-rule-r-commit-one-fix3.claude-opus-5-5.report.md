Completed-GMT: 2026-09-25 18:04:21 GMT
Completed-Local: 2026-09-26 01:04:21 +07
Coding-Agent: claude
Session-ID: resume-of-078d0a96-daf0-4cef-b994-076601d800d6

# Report: Rule R commit one, fix round 3

Role: VM Runtime Engineer. Worktree: /Users/sto/workspace/datomworld-ucf-rule-r
(branch ucf-rule-r). Nothing is staged or committed. The whole uncommitted
diff is now 63 tracked files (+1852/-543) plus two new test files.

## Lanes

Each lane ran solo under mise. Dart ran after `rm -rf test/cljd-out`.

| Lane | Last round | Now |
|---|---|---|
| JVM | 2,051 / 180,998 | 2,051 tests / 181,066 assertions, 0 failures, 0 errors |
| Node | 1,964 / 47,987 | 1,964 tests / 47,987 assertions, 0 failures, 0 errors |
| Dart | 1,926 | 1,926 passed |

- **Count changes.** The only change is 68 more JVM assertions: new
  fixtures in the JVM-only audit test (`.clj`). No tests were added, so the
  Node and Dart counts are unchanged.
- **kondo** on every changed file: 0 errors. All 7 warnings already exist
  at HEAD.
- **cljstyle:** clean.
- **ASCII and 80 columns:** every added line in the uncommitted diff is
  ASCII and at most 80 columns, except Markdown grid-table rows.

## P2: pipelines seeded with a store (fixed)

`threaded-sites` in `test/yin/vm/store_write_audit_test.clj` now seeds
detection from the pipeline's **initial value**. A pipeline is carrying a
store in two cases:

- **From the start,** when its initial value is a store:
  - a store symbol;
  - `(:store x)`, `(get x :store)` or `(get-in x [:store ...])`;
  - a scoped `let`-family alias.
- **From the first step that moves onto a store:** `:store`, `(:store)`,
  `(get :store)` or `(get-in [:store ...])`.

From then on, every mutation-head step is a site. This is deliberately
conservative: a later step that leaves the store (`(get 'k)`) does not stop
the tracking.

**Pipeline forms covered:**

- `->`, `->>`, `some->` and `some->>`.
- `cond->` and `cond->>`: only the forms are checked, the tests are
  skipped.
- `doto`.
- `as->` (new `as->-sites`): the bound name becomes a store alias from a
  store initial value, or after any step that is a store.
- A store passed as the first argument of a threaded call, such as
  `(->> v (assoc (:store vm) k))`. The ordinary walk already catches this;
  it is now pinned by a fixture.

**New negative fixtures, 37 in total, all detected:**

- `(-> (:store vm) (assoc 'yin/def v))`, the reported case.
- `->>` from `(:store vm)`.
- `some->` from `(get vm :store)`.
- `some->>` from `(get-in vm [:store])`.
- `->` from a `store` symbol.
- `->`, `->>`, `some->`, `some->>`, `cond->`, `cond->>`, `doto` and
  `as->`, each seeded with a let-bound alias.
- `cond->` and `cond->>` from an extracted store, plus `cond->` passing
  `:store`.
- `as->` from an extracted store, and `as->` via a `(:store m)` step.
  The second one was a residual fixture before; it moved here.
- `doto` from an extracted store.
- A store as the first argument of a threaded call, both directly and via
  an alias inside `as->`.
- `->>` passing `:store`.
- Pipelines that step onto the store with `(:store)` and with
  `(get-in [:store])`.

**Claim-by-claim check.** I re-read the test docstring and closed every gap
between a claim and a fixture. That added:

- fixtures for `vswap!`, `vreset!` and the `store0` spelling;
- fixtures for the `let*`, `loop*`, `when-some`, `if-some` and `binding`
  aliases.

It also removed one claim. `when-first` binds the first element of a
collection, so its name is never the store itself. It is no longer listed
as an alias form (I removed it from `binding-heads` and the docs). The
collection case is now pinned as residual.

**Residual fixtures, all asserted undetected:**

- a store passed across a function boundary;
- a store inside a map;
- a store inside an atom;
- a store inside a collection read with `when-first`;
- `apply`, `partial` and `comp`;
- a mutation head bound to another name.

Stated as outside the detector, with no fixture: transients, host interop,
and macros that expand to a write.

**Wording, re-read against the fixtures:**

- `yin.vm.engine.md` section 1.1 now lists exactly what is detected, with
  the same content as the test docstring. It says the fixtures cover every
  listed form and names the residual.
- The `engine/store-put` docstring and the `datom.world.md` line already
  claim only "detected" sites plus a stated residual, so I left them
  unchanged.

The `src` allowlist is unchanged. No store pipeline, as-> form or alias
write exists in `src` today.

## Deferred limitation, now stated

`yin.vm.macro.md` section 4.2 has a new **Limitation** paragraph:

- Harvest stamps its packets as current. That is sound only if every input
  batch is fresh source syntax.
- The expander's API cannot establish that provenance.
- Admitting persisted batches as code needs a stamped-input design, which
  is not specified.
- Until then, a composition must feed the expander fresh syntax only.

No design was built.

## Files changed this round

- `test/yin/vm/store_write_audit_test.clj`: seeded pipelines, conditional
  pipelines, `doto`, `as->`, `when-first` removed, new fixtures, and the
  docstring.
- `docs/design/yin.vm.engine.md`: section 1.1.
- `docs/design/yin.vm.macro.md`: section 4.2, the limitation.

## Unrun checks

None. The three lanes, kondo and cljstyle all ran. The parity suites run
inside the lanes.

Status: COMPLETE
