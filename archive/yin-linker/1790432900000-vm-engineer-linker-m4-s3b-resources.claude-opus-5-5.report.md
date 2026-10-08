Completed-GMT: 2026-09-26 11:46:56 GMT
Completed-Local: 2026-09-26 18:46:56 +07
Created-GMT: 2026-09-26 12:00:00 GMT
Created-Local: 2026-09-26 19:00:00 +0700
Coding-Agent: claude
Session-ID: 59731830-4c7a-442c-ab9a-350c68c8a122 (resumed)

# Report: S3b, the private resources table and sealed references (linker r8 to r11)

Worktree: /Users/sto/workspace/datomworld-m4-s3 (branch m4-s3, uncommitted).
The S3a fixes are kept, and so is the orchestrator's cljd `runtimeType`
patch in `engine.cljc` (the `:host-object` hint in the encoder).

The clocks disagree: `date` reported 11:46:56 GMT when I finished, earlier
than the brief's Created-GMT of 12:00:00. I have recorded what `date` gave.

## Summary

The codex gate's P0 is closed. The runtime now matches linker r8 to r11:

- Stream handles, cursor cells, the FFI pair, and the link pair live only
  in the private `:resources` table.
- The engine seals every reference it issues under the task's capability
  secret, and checks the seal at effect dispatch.
- A lift authenticates a reference before encoding it; a lower installs
  the resource in the receiver's `:resources` and re-seals the reference
  under the receiver's secret.
- The missing `:foreign-image` test is added.

## How it works

### r8: the private resources table

- `empty-state` puts the FFI pair into `:resources`, and `:store` starts
  empty.
- The stream effect handlers read and write only `:resources`:
  `handle-make`, `handle-put`, `handle-cursor`, `handle-next`, and
  `handle-close`.
- So do the wait-set resolver and `make-woken-run-queue-entries`.
- The ready-entry key `:store-updates` is renamed to `:resource-updates`,
  and `resume-from-run-queue` merges those updates into `:resources`.
- No store instruction and no `resolve-var` step reads `:resources`.

### r10: sealed references

- The engine functions are `issue-ref`, `authentic-ref?`, `check-ref!`,
  and `attach-resource`.
- A reference is `{:type kind :id id :seal s}`, where the seal is
  `dao.jing/content-hash` over `[:yin.k/seal secret kind id]` with sha256.
- `put`, `cursor`, `next`, and `close` verify the seal, the kind, and
  that the resource is live and of the matching kind. Anything else
  fails closed with `{:reason :forged-resource-reference :effect e :kind
  k :id id}`.
- `attach-resource` is how a composition hands a task a host stream. The
  handle goes into `:resources` and the task receives a sealed reference.

### Where the secret comes from

The secret is injected; it is never minted by the engine:

- `:capability-secret` is `empty-state`'s option for the task's secret.
- `:secret-source` is `(fn [origin] secret)`, which the scheduler uses to
  mint an install child's secret.
- `:attach-stream` is `(fn [descriptor] outcome)`, which r9's lowering
  uses to attach a stream.

These are threaded through all four kernels and through `spawn-module`.
The ast-walker record gained the fields it needs.

### r11 and r9: lift and lower

- **Lift.** The encoder calls `authentic-ref?` against the emitter's own
  secret before encoding any reference, so an unsealed or forged
  reference refuses the lift as `:yin.k/non-portable` with kind
  `:forged-resource-reference`.
- **Stream markers.** An authentic stream reference becomes a
  `:yin.k/stream` marker carrying the descriptor and identity, never the
  handle. A handle with no descriptor surface refuses as
  `:in-memory-handle`.
- **Cursor cells.** An authentic cursor reference becomes a
  `:yin.k/cursor-ref` marker naming a logical cell. The cells travel as
  `:cells` in the lifted slice and in the registry entry (`link-module`
  now stores `:cells`); two references to one cursor share one cell.
- **Lower** (`lower-resources`, called by `receive-module`):
  - it attaches every stream once, through the composition's
    `:attach-stream`, under a fresh resource id;
  - it installs every referenced cell as a fresh cursor entry seeded with
    its carried position;
  - the decoder then issues references sealed under the receiver's secret;
  - no store key is created;
  - with no attacher, the lower refuses as `:yin.k/unsatisfied`, which
    refuses the install.

## Readings where the spec is ambiguous (I chose the fail-closed one)

1. **No secret.** A task composed without a secret issues no reference:
   `:stream/make` and `:stream/cursor` fail with `:no-capability-secret`,
   and fail before any stream is created. There is no default secret and
   no hidden RNG in the engine.
2. **Child secrets.** An install child's secret comes only from the
   composition's `:secret-source`. With none, the child has no secret, so
   a module that makes a stream fails its install.
3. **Compositions that mint their own secret.** The spec says the
   composition mints the secret "from its own random source". Where the
   composition is not a test, it mints `(str (random-uuid))`:
   - `yin.repl` `make-vm`;
   - `dao.await` `run`, unless the caller supplies a secret;
   - `yin.demo`, the three cljs demos, and the cljd register bench.
   Every test composition injects a deterministic secret instead
   (`tu/secret`, or its own).
4. **Resources in telemetry.** Telemetry now emits a `:vm/resources`
   summary component beside `:vm/store`, using the same summarizer: a
   handle becomes an identity node, never the object. Without it, an
   analyzer would lose stream and cursor identities once they left the
   store. Seals and the secret never appear in it.
5. **Resources in UCF discovery.** `yin.vm.completion` pulls stream
   handles and cursor cells into a new `:resource-slice`, output as
   `:yin.k/resources`, beside the store slice and never inside it (r8's
   "beside the program store").
6. **Stream seals checked before closure markers.** A closure marker
   whose origin is foreign is still refused by act 2 in `link-install`.

## Files changed in this pass

- **Engine and linker plumbing:**
  - `engine.cljc`: the resources and seals section, the handlers, the
    resolver, resume, reference-aware encoder and decoder,
    `lower-resources`, `receive-module`, and the child secret in
    `spawn-child`;
  - `module.cljc`: `:cells` in `link-module`;
  - `vm.cljc`: in `empty-state`, the pair moves to `:resources` and the
    new options are added;
  - `ffi.cljc`: reads the pair from `:resources`;
  - `completion.cljc`: the resource slice;
  - `telemetry.cljc`: the `:vm/resources` component.
- **Kernels:** `ast_walker.cljc`, `semantic.cljc`, `debruijn/stack.cljc`,
  and `debruijn/register.cljc` read the FFI pair from `:resources`,
  thread the secret, source, and attacher, and pass the child secret in
  `spawn-module`. `debruijn_register_effects.cljc` renames the stale key
  to `:resource-updates`.
- **Compositions:**
  - `dao/await.cljc`: env streams are attached into `:resources`, and it
    mints a secret;
  - `datomworld/demo/continuation_handoff.cljc`: `resource-keys` reads
    `:resources`;
  - `yin/repl.cljc`, `clj/yin/demo.clj`, the three cljs demos, and
    `cljd/yin/register_bench_cljd.cljd`: a minted secret.

## Tests added (all in `test/yin/vm/linker_require_test.cljc`)

Each test below runs on all four backends unless marked.

- **`a-forged-resource-key-reads-nothing-test`** (rewritten). A module's
  forged `:store-get` reads of every predictable id return nil. So do the
  task's own top-level forged reads, which is the P0 path. The task's
  store holds only symbols.
- **`a-forged-reference-fails-at-effect-dispatch-test`.** The task's own
  sealed reference resolves. The following are refused as
  `:forged-resource-reference`, with the effect and id named:
  - a literal naming the live stream with no seal;
  - a wrong seal;
  - the seal of another task's secret on the same id;
  - the FFI pair named as a stream (`:yin/call-in`) or as a cell
    (`:yin/call-out-cursor`);
  - a stream reference relabelled as a cursor reference.
- **`a-fabricated-reference-export-fails-the-lift-test`** (strengthened).
  The child now holds a live stream at the literal's id, and the lift
  still refuses it: `:yin.k/non-portable` with kind
  `:forged-resource-reference`.
- **`a-reference-carrying-export-lifts-and-lowers-test`.** A module
  exports a stream and a cursor, and the program puts 5 and reads it back
  through them. The test checks that:
  - the slice carries markers only, with no seal and no handle, plus one
    cell;
  - the lowered references are authentic under the receiver's secret;
  - the lowered cell names the lowered attachment;
  - no store key is created.
- **`a-reference-lowers-only-where-it-can-attach-test`.** With no
  attacher the install is refused as `:yin.k/unsatisfied`; with no child
  secret source it is refused as `:no-capability-secret`.
- **`an-origin-the-scheduler-never-verified-is-foreign-test`** (stack
  only; fault-injected child). Act 2 refuses with `:foreign-image` naming
  the segment, nothing is published, and the program sees the error.

In `engine_test`:

- A secretless task issues no reference and creates no stream.
- References are sealed.
- An unknown reference is refused as forged.

## Existing tests changed, and why

No assertion was weakened. Several were tightened, as noted.

1. **Secrets added to test compositions.** Every test composition that
   makes streams now also passes a deterministic `:capability-secret`
   (`tu/secret`, defined in `test_utils`, and also the default in
   `tu/create-vm`). This was necessary because references now require a
   secret. The files:
   - under `test/yin/vm/`: `attach_image`, `completion`,
     `debruijn/register`, `debruijn/stack_effects`,
     `debruijn/stack_parity`, `debruijn_register_benchmark`, `engine`,
     `ffi`, `rule_r`, `semantic_engine`, `semantic_ffi`,
     `semantic_stream_observer`, `semantic`, `telemetry`, `ucf`,
     `ast_walker`;
   - `test/datomworld/demo/continuation_handoff_test`.
2. **Handle lookups.** `(get (vm/store x) id)` for a stream handle, cursor
   cell, or FFI pair became `(get (:resources x) id)`, because the handle
   is no longer in the store (r8). Every assertion that the FFI pair
   exists now checks `:resources`. Where the file made it cheap, I added
   a new negative assertion that the store does not hold the pair
   (`telemetry_test` bridge, `engine_test`).
3. **`engine_test` literal references.** The handler tests used to pass
   literal references `{:type :stream-ref :id :stream-0}` after
   `handle-make`. They now thread the sealed reference the handler
   returned; a literal is now a forgery by design.
   - The stream-make test checks the `:type`/`:id` shape, a string seal,
     the handle in `:resources`, and its absence from `:store`.
   - The refusing-reader case installs its reader with
     `engine/attach-resource` instead of `assoc` into `:store`.
   - "An unknown stream reference is an error" became "...is refused as
     forged", which asserts the reason: tighter.
4. **`debruijn/stack_effects_test`:**
   - `stream-make-put-next-round-trip-test` read the stream back on a
     *fresh VM* given the first VM's store. That relied on handles in the
     store, which r8 forbids. It now loads the second program into the
     same task (`dvm/load-image`), which is what "a second program over
     the same store" means once resources are private.
   - `stream-errors-name-their-outcome-test`: the unknown-reference case
     now expects the `:forged-resource-reference` error, with its data
     asserted exactly. The closed-stream case builds the task's own
     reference with `engine/issue-ref` and runs in the same task, rather
     than on a fresh VM given a copied store.
   - `a-full-stream-parks-the-writer-and-retries-test`: the scripted
     handle is handed over with `engine/attach-resource`, and the image
     embeds the sealed reference, instead of the handle being injected
     through `:store`. `:stream-id` is asserted equal to the attached id.
5. **`debruijn/register_test`:** in `stream-blocking-writer-test`, the
   scripted handle is attached the same way and the image is loaded with
   `rvm/load-image`.
6. **`rule_r_test`:** `a-ready-entry-carries-only-minted-keys` now uses
   `:resource-updates` and rule `:resource-update-key`. It also asserts
   the update lands in `:resources` and that the store is untouched:
   tighter.
7. **`completion_test`:** the stream is now expected under
   `:yin.k/resources`, not `:yin.k/store` (r8). The store keys are still
   asserted exactly, and there is a new assertion that `b`'s resources are
   empty. In `blocked-reader-wait-entry-is-a-root`, the "cursor entry and
   its stream" count moved to `:yin.k/resources`, with a new assertion
   that the store slice is empty.
8. **`semantic_test`:** in `blocked-entries-are-pure-data-test`, the
   entry's `:cursor-ref` is now the task's sealed reference. The exact
   expected value is computed with `engine/issue-ref`; the equality is
   still exact.
9. **`telemetry_test`:**
   - The FFI pair and the stream and cursor identities are read from the
     new `:vm/resources` summary, with every identity and position
     assertion kept.
   - A new assertion checks that the store summary no longer holds the
     stream.
10. **`yang/clojure/stream_eval_test`:** the cross-VM parity comparison
    replaces each 64-hex seal with `:seal <seal>` before comparing. Each
    REPL session mints its own secret, so the seals differ by design, in
    the same way the test already masks medium UUIDs. A reference must
    still print with a seal on every VM.
11. **`store_write_audit_test`:** the allowlist entries for the engine's
    handlers and resolver, and for `dao.await`, are removed, because
    those sites no longer write the store at all.
12. **`attach_image_test`:** `stream-of` reads `:resources`.

## Checks

| Check | Result |
|---|---|
| JVM, full lane (final run) | 2,166 tests / 182,516 assertions, 0 failures, 0 errors |
| Node, full lane | 2,079 tests / 49,247 assertions, 0 failures, 0 errors |
| clj-kondo (`mise exec -- clojure -M:kondo`) on every changed clj/cljc/cljs file | 0 errors |
| cljstyle check | clean |
| ASCII and 80 columns on every added or edited line | 0 violations |

- **JVM.** The run above was on the final tree. Before it I ran the
  touched namespaces, plus the hash-registry lint, after the last edits:
  121 tests / 4,235 assertions, 0 failures.
- **Node.** This ran before the last formatting-only and docstring edits.
- **Kondo.** 7 warnings remain, all pre-existing: I confirmed the two in
  changed files against their HEAD copies, and the other five are the
  known `vm.cljc` and `ast_walker.cljc` warnings.
- **ASCII/80.** Edited lines that carried a `§` or an em dash were made
  ASCII.

## Not done, and observations

- **Dart lane:** not run (the orchestrator runs it). Cross-host notes:
  - `random-uuid` is already used in cljc by `ringbuffer` and `repl`;
  - catches use `#?(:cljd Object ...)`;
  - no `#'` private access;
  - `reify` and `extend-type` of protocols from other namespaces are used
    in the tests (`FaultyTask` now also implements `vm/IVM`);
  - the walker record gained `capability-secret`, `secret-source`, and
    `attach-stream`, and all are threaded through `cesk-return`.
- **The seal is a sha256 content hash over `[tag secret kind id]`, not an
  HMAC.** `dao.jing` exposes no MAC. Because the canonical encoding is
  length-prefixed, this is sound for this use, but an HMAC from a library
  would be the stronger primitive if one is added.
- **The UCF doc is unchanged** (S5 owns it). It still describes the
  `:yin.k/stream` and `:yin.k/cursor-ref` decode targets as store entries
  (7.5.1 and 7.5.3).
- **Parked-continuation ids can still be guessed.** A program names them
  in `[:resume parked-id]` with a predictable id. The spec's r10 list is
  streams, cursors, and FFI cells, so I left this alone, but it may be
  worth a later look.

Status: COMPLETE
