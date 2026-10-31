# Review: the free-environment retention seam (fable, non-author)

Worktree `/Users/sto/workspace/datomworld-srvm-freeenv`, branch `srvm-free-env`,
commit daf6e184, diff vs master: two files, 30 insertions, 1 deletion
(`src/cljc/yin/vm/semantic_register.cljc`,
`test/yin/vm/semantic_register/vm_test.cljc`). Spec:
`docs/design/yin.vm.semantic-register-vm.continuation.md` §6.3. Everything
below was run in the foreground; the source tree was byte-identical to the
commit after every mutant (`git status --short -- src/ test/` empty).

## (1) The field is set once and never recomputed

Confirmed. `:free-env` occurs three times in the whole tree for the register
VM: the record field (`semantic_register.cljc:24`), the single
`map->RegisterVM` in `create-vm` (`:394`, from the `vm/check-bindings!`-checked
`env` bound at `:376`), and the read in `gc-roots` (`:489`). Checked every
record-update site:

- `put-registers` (`:50-55`) writes `:control :window :env :k :halted?` only;
  `register-restore`'s `:rd`/`:return` branches go through it, the request-sent
  branch assocs scheduling fields. None touch `free-env`.
- `reset` (`:338`) writes `:control :window :k :value :halted? :blocked?`.
- `eval` (`:335`) runs and then `engine/restore-initial-env (:env machine)`,
  the pre-existing transient capture of the *current* env; it rewrites `:env`,
  not `free-env`. Consistent with §6.3's description of eval.
- `attach-image`/`load-vector`/`load-ast` (`:308`, `:318`, `:328`) write
  `:code`/`:code-aliases`/`:program`/`:control`.
- `spawn-module` (`:411`) builds the child through `create-vm`, so the child's
  `free-env` is the child's own checked `:env` (`{}` here, since no `:env` is
  passed), never the parent's current E.
- No other file constructs or rebuilds a `RegisterVM`: `map->RegisterVM` /
  `->RegisterVM` appear only in `create-vm`. Telemetry snapshots do not
  rebuild the record.
- UCF handoff: `engine-of` (`handoff.cljc:838`) maps only
  `:yin.semantic/code`, `:yin.ast/code`, `:yin.debruijn.code`,
  `:yin.debruijn.register`; `:yin.semantic-register/code` falls to
  `non-portable! :unsupported-kernel`. So the handoff's `(:free-env vm)` reads
  (`:1269`, `:1571`) and its lower-side `(assoc :free-env …)` (`:3682`, gated
  by `pc-profile?` = `#{:stack :register}`) never see a `RegisterVM`. No
  write path there.

## (2) `gc-roots` correctness

Listing `free-env` under `:values` is right and it is needed.

- Mode: `engine/trace` walks a `:data` root map whole (keys and vals), so a
  cell ref inside an initial binding is found; `:kernel` mode would hand it to
  `gc-children`, which returns nil for this kernel and would walk it as data
  anyway. `:values` is the honest classification: an environment is data.
- Needed: `vm/check-bindings!` (`vm.cljc:161`) rejects only reserved names, so
  an initial binding may hold a heap cell ref. After the program rebinds that
  name (the test's own scenario, `{'x 1}` → `{'x 2}`), the cell is reachable
  only through `free-env`; without the root `engine/collect` would sweep it,
  and the lift that §6.3 says re-emits `free-env` would then emit a dangling
  ref. The root keeps the seam's contract true under GC.
- Not stale garbage: it pins exactly the initial bindings for the instance's
  life, which is the spec's "fixed for this instance". Nothing else is
  retained (the field is never updated, so it cannot accumulate).
- Observation, outside this diff: the de Bruijn kernels do *not* list their
  `free-env` in `gc-roots` (`stack.cljc:1018`, `register.cljc:955`) although
  their lift encodes it (`handoff.cljc:1571`). Same latent gap, same fix
  shape; not a blocker for this slice.

## (3) Mutation testing

Each mutant: `cp` backup, one-line edit, `clojure -M:test -e :slow -n
yin.vm.semantic-register.vm-test`, restore, `diff` against the backup
(identical every time). Baseline before and after: 18 tests, 165 assertions,
0 failures.

| Mutant | Edit | Result | Caught by |
|---|---|---|---|
| M1 transition writes it | `put-registers` adds `:free-env E` | 8 failures | `vm_test.cljc:271,274-279,286` |
| M2 dropped from roots | `gc-roots` `:values` without `(:free-env machine)` | 1 failure | `:275` |
| M3 recomputed after reset | `reset` adds `:free-env (:env machine)` | 1 failure | `:276` |
| M4 copy, not retention | `gc-roots` lists `(:env machine)` in its place (computed at gc time) | 1 failure | `:275` |
| M5 ignores checked `:env` | `create-vm` sets `:free-env {}` | 9 failures | `:269,271,274-279,286` |

All five are caught. M2/M3/M4 each hang on one assertion, which compares the
roots vector by value against the initial map; that is enough because the
test's program rebinds `x`, so current E ≠ initial E at every later check.
A stronger root test would put a cell ref in the initial env, rebind, call
`engine/collect`, and assert the cell survives; the current value-equality
check does not exercise `trace`. Nice-to-have, not required for this seam.

## Verification run here (JVM, foreground)

- `clj -M:kondo --lint` on the two changed files: 0 errors, 0 warnings.
- `yin.vm.semantic-register.vm-test`: 18 tests, 165 assertions, 0 failures.
- External namespaces that build register VMs and read the field via the
  linker: `yin.vm.linker-test`, `yin.vm.linker-require-test`,
  `yin.vm.continuation-invoke-test`, `yin.vm.ffi-test`, `yin.vm.rule-r-test`:
  135 tests, 1642 assertions, 0 failures.
- Node and Dart not run (brief scope); nothing in the diff is host-specific.

## Findings

| # | Severity | Where | Finding | Action |
|---|---|---|---|---|
| F1 | minor, non-blocking | `engine.cljc:1217` (`discharge-defect`) | The linker receiver reads `(:free-env state)`. For a `RegisterVM` this was `nil` → `{}` before and is now the initial env, so an obligation named by an initial binding is now discharged on the register VM exactly as on the de Bruijn kernels. This is the right behaviour and the linker suites pass, but the engineer report does not mention it. | Add one line to the commit message / report. |
| F2 | nit, non-blocking | `vm_test.cljc:275` | The GC-root assertion checks value membership in `gc-roots`, not survival through `engine/collect`. M2/M4 are caught only because the program rebinds `x`. | Optional follow-up: cell-ref-in-initial-env survives `collect`. |
| F3 | observation, out of scope | `stack.cljc:1018`, `register.cljc:955` | De Bruijn kernels do not root their `free-env` though their lift emits it. | Note for the de Bruijn track. |

Nothing blocks. The seam matches §6.3 ("set once in `create-vm` from the
checked `:env`, never written by any transition, listed in `gc-roots`"), the
test is real (five mutants killed), and the existing suites are green.

VERDICT: READY_TO_MERGE
