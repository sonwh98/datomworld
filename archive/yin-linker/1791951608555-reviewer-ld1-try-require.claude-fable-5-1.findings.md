Completed-GMT: 2026-10-14 04:23:27 GMT
Completed-Local: 2026-10-14 11:23:27 +07
Coding-Agent: claude
Session-ID: 547851ca-eeb1-4805-833e-a978eb20b395

# L-d-1 review: `try-require` primitive and refusal-as-data

Verdict: **APPROVE-WITH-NITS**. The slice does what the brief and the scoping design (§1.1 to §1.4) ask, in exactly the seams the design named. One P2 sits outside the slice's acceptance lines but is newly introduced behavior, so it should be closed before commit. It is a one-word fix.

## Checklist results

1. **Primitive and profile** (`src/cljc/yin/vm.cljc:416`). Confirmed. Mints `:module/try-require {:module ns-sym}`, profile `:effectful [1] #{:module/try-require}`, same vector-spec unwrapping as `require`. The profile test pins class, arity, effects and `:none` host state.
2. **Handler** (`src/cljc/yin/vm/module.cljc:523`). Confirmed. Shared `handle-require` with a `try?` flag. Cycle under `try?` returns `{:status :refused :reason :require-cycle :module :chain}` without throwing. Registry hit returns the ok map. The `registers` thunk adds `:try? true` to every parked entry, so `:install` and `:link-request` both carry it. Park fallback is `(or try-require require)`, and no kernel supplies a `:module/try-require` builder, so all five backends go through the fallback. Verified: the "no link pair" assembly defect still throws under `try?`, as the design intends.
3. **Engine delivery** (`src/cljc/yin/vm/engine.cljc`). Confirmed. `refused-entry` is the single refusal seam and every refusal path funnels through it: settle (response, name mismatch, discharge defect, loader defect), `link-read-step` (stream gap and terminal outcomes), `refuse-install` via `wake-installed`, `abandon-link`, `abandon-installs`. `linked-entry` covers the two success wakes. Mixed co-waiters are handled per entry inside `wake-installed`, and the test drives it through the real handler. `:try?` survives the `:link-request` to `:link-response` transition (assoc only) and settle's install-waiter build (dissoc of link fields only).
4. **Portability**. Confirmed. No `with-redefs`, no interop, no `for` over long seqs, maps start from `{}`, existing `refusal-of` reader conditional is cljd-first.
5. **Invariants**. Confirmed. No language words in VM, engine or module. Under `try?` the handler takes the same branches, settle runs the same discharge and name checks, and `link-install` runs the same export, pin and origin checks. Only the resumed value changes.

Engineer evidence checked: targeted lane 40 tests, 1036 assertions, green. Subsystem lane 82 namespaces green. Kondo delta empty. Changed-file cljstyle clean.

## Findings

**P2 | `src/cljc/yin/vm/macro.cljc:666` | evidence:** the macro prelude is documented as "the pure standard primitives (no `require`, no stream or FFI constructor)" and builds itself by `(dissoc vm/primitives 'require 'bytes->str)`. Adding `try-require` to `vm/primitives` silently puts a linking primitive into every macro body environment. This is newly introduced behavior outside the slice's claimed acceptance, which the Q6 rule makes blocking. **Fix:** `(dissoc vm/primitives 'require 'try-require 'bytes->str)`. No new entry point, no catalog change.

**P3 | `src/cljc/yin/vm/module.cljc:572` | evidence:** a `try-require` that joins an install already in flight is given `:link-id` equal to the install's `:parent`, which is another waiter's link id. On `abandon-installs` the joiner receives `{:status :lost :reason r :link-id <someone else's id>}`. The joiner never minted or saw that id, so a consumer correlating by id is misled. **Fix:** omit `:link-id` for joiners, or document that `:link-id` names the install's originating link. Ledger candidate if not fixed now.

**P3 | `src/cljc/yin/vm/linker/publish.cljc:253` | evidence:** the publication scanner records a manifest requirement only for operator `require`. A module-level `(try-require 'm)` produces no pin, so the install-time pin check does not cover it. The scoping design assigns this to L-d-3 ("`publish/requirements` public; walker `:eager?`"), so it is not this slice's defect, but it should go on the known-gaps ledger so the cutover is not forgotten.

**P3 | `test/yin/vm/linker_require_test.cljc:1709` | evidence:** `['f']` reads as the symbol `f'`, not `f`. The test abandons before validation so it passes anyway, but if someone later lets the install complete it would refuse with `:export-missing` for the wrong reason. **Fix:** `['f]`.

**Note, not a finding.** The mob ruling asked for `docs/design/yang.antlr.md` §8.5.6 to say `module/try-require` is an effect kind, not a host module. The brief scoped docs to the two linker files, so this belongs to the yang.python track, not a fix round here.

## Deviation check

The engineer's report says the scoping log and mob document were absent from the worktree. They are present in the main checkout's `collab/`, and the implementation matches them, so nothing was lost.

Next step for the orchestrator: one fix round for the P2 (one word in `macro.cljc`) and the `['f]` typo, then quick tier and commit.
