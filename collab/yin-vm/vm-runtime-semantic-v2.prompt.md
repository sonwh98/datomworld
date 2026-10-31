Created-GMT: 2026-09-08 10:32:32 GMT
Created-Local: 2026-09-08 17:32:32 +07 (Asia/Bangkok)
Coding-Agent: glm
Session-ID: 50d48a71-9ff9-44b7-8dc0-b334e5f42aac
# Task: port yin.vm.semantic to yin.vm.semantic on dao.stream
Role: VM Runtime Engineer
Implementers:
- Model: glm-5.3 | Assigned: 2026-09-08 17:32:32 +07 | Status: active | Rationale: VM Runtime primary per team.md; it implemented P1 and P2 of the dao.jing plan and knows the v2 stream contract

**Implementation task with write authority**, bounded to the files below.
Repository `/Users/sto/workspace/datomworld`, branch `dao.stream-redesign-v2`,
clean at `78b5262` apart from one uncommitted paragraph in
`docs/design/dao.stream.md`.

## The goal this serves

The project's primary goal is moving every consumer off v1 `dao.stream` onto
`dao.stream`, after which `dao.stream` is renamed to `dao.stream`
(recorded in `docs/design/dao.stream.md`, "The v2 namespace is transient").
`yin.vm.semantic` is one of the remaining v1 consumers.

## What to build

**`src/cljc/yin/vm/semantic.cljc`** — `yin.vm.semantic` ported to
`dao.stream`, applying exactly the v1→v2 transformation already performed
for the ast-walker. **Read `src/cljc/yin/vm/ast_walker.cljc` and
`src/cljc/yin/vm/ast_walker.cljc` side by side first: that diff is your
specification.** It is 836 lines and every change you need is visible in it.

The transformation, enumerated so nothing is missed:

1. **Requires.** `[dao.stream :as ds]` and `[dao.stream.apply]` become
   `[dao.stream.apply :as apply2]`. `yin.vm` → `yin.vm`, `yin.vm.engine`
   → `yin.vm.engine`, `yin.vm.ffi` → `yin.vm.ffi`, `yin.module` →
   `yin.vm.module`, `yin.vm.telemetry` → `yin.vm.telemetry`. All
   fourteen engine functions semantic uses exist in `yin.vm.engine`;
   verified.
2. **Record.** Drop `in-stream` and `in-cursor`. Add `modules`,
   `make-stream`, `call-capacity`, as the v2 ast-walker record has.
3. **`park-and-call`.** This is the heart of it. v1 registers a reader-waiter
   (`ds/register-reader-waiter!`) and appends with `ds/append!`. v2 has **no
   waiters** — `dao.stream.md` lists waiter registration under *Explicitly
   Absent*. Use `ffi/require-call-pair!`, `apply2/put-request!`, and dispatch
   on the outcome: `:dao.stream/ok` parks a `call-response-wait-entry` in the
   polling wait set; `:dao.stream/full` retains the identical encoded request
   in the wait set to be retried; anything else throws naming its outcome.
   Copy the v2 ast-walker's shape exactly.
4. **`call-result`.** v1 reads `(:dao.stream.apply/value val)` off a response
   that could only succeed. v2 checks correlation and unwraps ok/error. Port
   the v2 ast-walker's `call-result` and use it in the call-return frame.
5. **Keywords.** `:dao.stream.apply/*` → `:dao.stream.apply/*` throughout,
   and add the `request-sent` frame the v2 ast-walker has for a retained
   request that has now been appended.
6. **`resolve-var`** takes a `modules` argument in v2.
7. **No stream driving in the VM.** Delete `semantic-vm-run-on-stream` and the
   `step-on-stream` call. `step` becomes the ready-for-ingress check, `run`
   uses the scheduler only. Program input is observed above the VM by
   `yin.vm.stream-observer`; that is what V7 changed.
8. **`create-vm`** rejects `:in-stream` with an explicit error, selects
   `:modules :make-stream :call-in :call-out :call-capacity`, and calls
   `ffi/attach`.

**Keep everything else.** The datom-graph traversal, the object-array node
index, the hot loop, TCO, `create-ast-db`, the DaoDB transaction path, the
query utilities — all of it is semantic's kernel and is untouched by the
stream change, exactly as the ast-walker's CESK core was.

**`test/yin/vm/semantic_test.cljc`** — port `test/yin/vm/semantic_test.cljc`
(400 lines) to the new namespace, following the conventions in
`test/yin/vm/` (see `ast_walker_test.cljc` and `test_utils.cljc` there).

## Two decisions already taken — do not revisit

- **Macros stay.** `yin.vm.macro` is stream-free, so require it unchanged and
  keep `:yin/macro-expand`, `semantic-expand-macro-call`,
  `invoke-macro-lambda` and `macro-registry`. A faithful port keeps the VM's
  kernel and changes only the stream layer. Note in your report that this
  makes `yin.vm.divergence-register.md`'s claim "the v2 corpus is
  macro-free by construction" no longer true — the orchestrator owns that doc
  change; do not edit it.
- **`dao.space.query` stays as-is.** semantic requires it and it is still on
  v1 `dao.stream`, so the new namespace keeps a transitive v1 dependency until
  `dao.space` migrates under its own plan. That is expected; do not try to fix
  it, and do not touch `dao.space`.

## Bounds

Create only those two files. **Do not modify `src/cljc/yin/vm/semantic.cljc`
or its test** — v1 stays until its consumers move, and `demo.html` currently
depends on it through `compilation_pipeline.cljs`. Do not touch any other
namespace, any `dao.space` file, the divergence register, or
`datomworld.demo`. Do not stage or commit. If something outside this scope
must change for the port to work, stop and report rather than widening.

## Proof

`bb test:clj`, `bb test:cljs`, `bb test:cljd` all green, and
`clj -M:kondo --lint` clean on both new files. The cljd lane regenerates
`test/cljd-out/` and only one process may own it — do not run it concurrently
with anything. Confirm `Testing yin.vm.semantic-test` appears in the Node
output rather than assuming discovery. Report exact test and assertion counts
per host; the baseline before your change is clj 1425 tests / 165264
assertions.

Begin the final response exactly with:
Completed-GMT: <YYYY-MM-DD HH:MM:SS GMT>
Completed-Local: <YYYY-MM-DD HH:MM:SS local-timezone-name>
Coding-Agent: glm
Session-ID: 50d48a71-9ff9-44b7-8dc0-b334e5f42aac

Then report: every file created, the three suites' counts, kondo results, each
point of the transformation above and how you applied it, anything in v1
semantic that had no clean v2 equivalent and what you did about it, and
anything you could not do with the reason.
