Completed-GMT: 2026-09-29 08:39:49 GMT
Completed-Local: 2026-09-29 15:39:49 +07 (+0700)
Coding-Agent: claude
Session-ID: c2557c35-ba40-4377-b51a-aad2152a95cb

# Report: yin.repl "q on require" slice

Worktree `/Users/sto/workspace/datomworld-q-require` (branch `repl-q-require`, base 3cf3c6de). Nothing is staged or committed.

## Changed files

- `src/cljc/yin/repl.cljc` (modified): adds the limit constants, the session query call pair, registry wiring, the drive loop serving queries, and carries the query interpreter's cursor across a rollback.
- `src/cljc/yin/repl/query.cljc` (new): the query bridge. It holds the host module, the require and call effect handlers, the call pair, the request answerer, and the interpreter's `serve`.
- `test/yin/repl/query_test.cljc` (new): 11 tests, 137 assertions.

No changes to `yin.vm/*`, `dao.space.query`, `dao.space.index` or `dao.stream.*`.

## Mechanism

1. **Activation (session-scoped host module).** `yin.repl.query/register` adds two effect handlers to the registry that `make-vm` builds. Registering effect handlers is the registry's public extension point.
   - `:module/require`: for `dao.space.query`, when the module is absent, it installs the host module with `module/register-host-module` into the requiring VM's own `:modules` value and answers at once. Every other name, and a repeated require, goes to `module/require-handler` unchanged.
   - The module is not in the initial registry, so before the require `engine/resolve-var` fails for `dao.space.query/q`. `(reset)` and `(vm …)` rebuild the VM with a fresh registry, which removes it.
   - The bare name `q` is never bound, because no import rule was added.
2. **The call.**
   - The export `q` is a pure data constructor. It returns `{:effect :yin.repl.query/call :args [...]}` with profile `:effectful #{:yin.repl.query/call}` and host-state `:none`.
   - The `::call` handler parks the continuation as an ordinary FFI call on the VM's `dao.stream.apply` pair. It gets the continuation registers from the VM's `:module/require` park builder, which all four kernels supply for an effectful primitive.
   - It uses the VM's own id scheme (`ffi/call-id` over `engine/park-id`), then `engine/park-continuation`, then `apply2/put-request!`.
   - It waits on the standard response readers: `ffi/call-response-wait-entry` for the walker and `ffi/response-wait-entry` for semantic, stack and register.
   - The engine's FFI response router wakes the call, and each VM's restore runs `ffi/call-result`. Errors therefore surface through the FFI error envelope, printed as `Error: FFI call failed: …`.
   - The request carries only data: the query, the `:in` inputs, and an optional final options map.
3. **The pair (slice 3d consistent).**
   - `make-session` creates the pair (`query/make-pair`) and supplies it as `:call-in`/`:call-out`, together with its `:call-out-cursor` (minted `:oldest` on the fresh medium).
   - No `:ffi-caller-id` is set: the pair is local and has one caller.
   - The interpreter's half (`:call-in`, `:call-out`, its own call-in `:cursor`) is kept in the shell under `:query-pair`.
4. **Interpreter.**
   - `drive-links` now also drives a VM that is waiting on a query call (`query/waiting?`). `yin.repl.query/serve` answers from the `:indexer` in the current shell state at serve time, and then the VM runs on.
   - Only rounds spent waiting on a link count against `link-round-budget`.
   - `run-evaluation` enters that drive for a VM that is waiting on either a link or a query.
5. **Answering a request.**
   - The snapshot is `index/read-datoms` of the indexer's latest read-back `:manifest-address`, wrapped as `query/relation`. The view is `query/current` by default; `{:view :history}` selects `query/history`.
   - The call is refused with `index-unavailable` when the indexer is `:lost?`, has a `:failure`, is not `:published?`, or its manifest cannot be read.
   - An indexer with zero transactions is a valid empty database.
   - The answer is `query/collect`'s materialized shape.

## Limits (named `yin.repl` constants — owner to review before commit)

| Constant | Value | Rationale |
|---|---|---|
| `query-row-limit` | 1000 | The REPL prints an answer whole, so more than 1000 rows is a query to narrow. A `def` of ordinary size projects to tens to hundreds of facts, so all facts of several definitions still fit. |
| `query-byte-limit` | 262144 (256 KiB) of canonical CBOR | Rows bound the count, not the size: one row can be a long string literal. CBOR (`dao.jing.cbor/encode`) is the same encoding on every host, so the check is deterministic across hosts, and it doubles as the portability check. |
| `query-pair-capacity` | 64 elements | A VM has one call outstanding at a time. The bound on the medium's retention is 64 × 256 KiB = 16 MiB. |
| `query-serve-budget` | 64 | Requests answered per serve round. |

- Rows are checked first. They count members for a relation or collection, and count 1 for a scalar or tuple. Bytes are checked after rows.
- Both checks run before the response is appended.
- A refusal carries `:yin.repl.query/limit {:rows n}` or `{:bytes n}` in the error map, and the message names the limit.

## Error codes

- `:yin.repl.query/index-unavailable`
- `:yin.repl.query/invalid-input`: an argument that is not portable (the CBOR encoder refuses it, e.g. a host function), a query that is not a vector or map, or bad options.
- `:yin.repl.query/result-limit`
- `:yin.repl.query/query-failed`: **a fourth code I added.** It is used when `dao.space.query` itself rejects the query (malformed query, `:in` arity mismatch, an unknown builtin). The design said "such as", and folding these into `invalid-input` would have blurred bad Yin data with a bad Datalog query. Please confirm.

Every message ends with its code in parentheses, so the REPL text shows the stable keyword. For example:

`Error: FFI call failed: the query result has 1106 rows, over the limit of 1000 (:yin.repl.query/result-limit)`

## Acceptance → tests (`yin.repl.query-test`; every shell-level case loops over all four VMs)

1. `q-is-unresolved-until-required-and-bare-q-is-never-bound`
2. `a-def-is-queryable-by-its-name-and-facts`: name via `:yin/value`, body names, `:literal` type, current view; a query with no match gives `#{}`.
3. `the-history-view-reaches-session-and-round-through-m`: `[token r]` for all three rounds under `{:view :history}`; the default current view gives `#{}` for the 5-slot pattern.
4. `an-unavailable-index-refuses-the-call`: covers a lost indexer (the gap technique from index_test), a failed publication (broken store), committed-but-unpublished, and a manifest that can no longer be read. `an-empty-session-is-an-empty-database` checks that an indexer with zero transactions gives `#{}`.
5. `reset-removes-the-binding-and-a-new-require-restores-it`, plus `vm-selection-removes-the-binding`.
6. Limits and input:
   - `results-over-a-limit-refuse-naming-it`: the real row limit on all VMs via a 1100-literal program, plus unit checks of the row and byte limits with small limits and the `::limit` data.
   - `unsupported-input-is-refused`: a host fn `+` as input, a bad `:view`, and a non-query.
7. Additional tests:
   - `a-failed-round-keeps-its-answers-answered`
   - `the-interpreter-answers-each-request-once`: serve is once-only, and an unknown op gives an `unknown-operation` error response.

**Mutation proofs.** Each mutation was applied, the test ns run, then the file restored and confirmed byte-identical to a pristine copy with `cmp`:

| Mutation | Failures |
|---|---|
| query-pair not carried on the raise | 4 (failed-round test, every VM) |
| module preinstalled in the initial registry | 9 (unresolved/reset/vm-selection tests) |
| `lost?` ignored | 8 |
| row-limit check disabled | 10 |
| `:history` mapped to `current` | 4 |
| portability check removed | 12 |
| byte-limit check disabled | 3 |

All seven were caught, and all were reverted.

## Verification (all in the worktree)

- `clj -M:kondo --lint src/cljc/yin/repl.cljc src/cljc/yin/repl/query.cljc test/yin/repl/query_test.cljc`: 0 errors, 0 warnings.
- `cljstyle check`: **BLOCKED.** The session's permission gate refused the command (it is installed at mise cljstyle 0.17.642). Formatting was not checked; please run it.
- Focused JVM (`yin.repl.query-test`, `yin.repl-test`, `yin.repl.index-test`, `yin.repl.require-test`, `yin.vm.ffi-test`): 94 tests, 727 assertions, 0 failures, 0 errors.
- Full `clj -M:test`: 2353 tests, 184223 assertions, 0 failures, 0 errors. This run is on the final code. The known `yin.repl.main-test` flake did not appear.
- `bb test:cljs`: 2259 tests, 50721 assertions, 0 failures, 0 errors, including "Testing yin.repl.query-test".
  - The first CLJS run failed 4 assertions, all on printed set order in my own history test (the answer was right). I made that assertion order-independent and re-ran green.
- `bb test:cljd`: not run, as instructed. The new code follows the CLJD rules: there is no `#?(:clj …)`-only code, and the byte length uses `#?(:cljd (.-length ^Uint8List …) :default (alength …))` with `dart:typed_data` required under `:cljd`. It has not been compiled on CLJD.

## Design choices worth review

- **Require interception is a composition effect handler, not a linker special case.** The link interpreter never sees `dao.space.query`. Every other require still goes through `module/require-handler`. I read "activates it through the existing require handler" as "the same `:module/require` effect". If the Architect meant something stricter, this is the point to challenge.
- **The FFI park is done from a composition effect handler rather than a `dao.stream.apply/call` node.** It needs the VM-specific wait-entry shape. I pick it by `(:vm-model state)` (`:ast-walker` gets the eval-call frame; everything else gets `ffi/response-wait-entry` over its register payload). The helpers are public `yin.vm.ffi`/`yin.vm.engine` functions, but the handler does depend on each kernel's `:module/require` park builder returning its continuation registers. This is a coupling to how the kernels build park entries, not a `yin.vm` change.
- **A defect found and fixed during mutation work.**
  - What happened: when a round failed after a query was answered, the shell rolled its state back to before the drive, including the interpreter's call-in cursor, so the interpreter answered the same request a second time.
  - The fix: `link-raise` now also carries `::query-pair`, and both rollback sites (`run-evaluation`, `recheck-pending*`) restore it.
  - Why no id collision: `link-raise` already carries the VM's id counter forward, so call ids never repeat. I briefly tried a per-round `:ffi-caller-id` stamp to prevent collisions, found it unnecessary, and removed it.
- **Behavior change.** A raw user `(dao.stream.apply/call :x/y …)` at the prompt used to wedge with "Program stream did not form a complete, runnable Yin VM program". It now gets `Error: FFI call failed: No handler for operation (:dao.stream.apply/unknown-operation)` from the shell's interpreter, because the session VM now has a served call pair. No existing test depended on the old behavior.

## Concerns

1. **Stale responses after a rollback.** A VM rolled back to its round base keeps its old call-out cursor. On its next call the router re-reads the failed round's responses and skips them as unmatched (`:ffi-diagnostics`, capped at 64). This is harmless unless a single failed round made more than `query-pair-capacity` (64) calls. In that case the next call reads a gap and fails with `:dao.stream.apply/ended` (response-gap). This is documented on `query-pair-capacity`.
2. **Options map vs. inputs.** A trailing map is always taken as the options map, so a map cannot be an `:in` input. Scalars, vectors, lists and sets can. The design asked only for scalar inputs; the arity follows `dao.space.query/q`.
3. **Cost per call.** Each `q` re-reads the whole EAVT of the latest manifest with `index/read-datoms`. This is the linear per-call cost the design accepts ("q pays its own query cost"). `open-published!` with lazy B-trees would be the upgrade.
4. **The calling program is indexed too.** A query sees the program that is making the call, because the index stage runs before evaluation. That is why the history test expects 3 rounds.
5. **Not done yet:** `cljstyle check` is still to be run, and `bb test:cljd` was not run.
