Completed-GMT: 2026-09-26 12:03:17 GMT
Completed-Local: 2026-09-26 19:03:17 +07
Created-GMT: 2026-09-26 12:35:00 GMT
Created-Local: 2026-09-26 19:35:00 +0700
Coding-Agent: claude
Session-ID: 59731830-4c7a-442c-ab9a-350c68c8a122 (resumed)

# Report: S3c, the REPL and dao.await compositions mint install children's secrets

Worktree: /Users/sto/workspace/datomworld-m4-s3 (branch m4-s3, uncommitted).

The clocks disagree: `date` reported 12:03:17 GMT when I finished, earlier
than the brief's Created-GMT of 12:35:00. I have recorded what `date` gave.

## Summary

The re-gate's P1 is fixed. Both compositions now supply a source that
mints a fresh secret for each install child. The engine is unchanged and
still uses no clock and no RNG: it only calls the composition's source.

## The fix

The whole diff is two source edits:

- **`src/cljc/yin/repl.cljc`** (`make-vm`): beside the root
  `:capability-secret`, it now passes `:secret-source (fn [_origin] (str
  (random-uuid)))`. The REPL is the composition, and this is its own
  random source.
- **`src/cljc/dao/await.cljc`** (`run`): it passes `:secret-source (or
  (:secret-source opts) (fn [_origin] (str (random-uuid))))`. A caller
  can still inject its own source, which keeps tests deterministic. The
  docstring says so.

## Tests added (in `test/yin/vm/linker_require_test.cljc`)

Both end-to-end tests install the same module. Its body makes a stream,
writes 7, and reads it back through a cursor, so the install child must
issue both a stream reference and a cursor reference.

- **`a-stream-using-module-installs-through-the-repl-composition-test`**
  - It builds the task with `yin.repl/make-vm :ast-walker`. Only the link
    pair is added, because the shell does not wire one until M5. The
    stub responder answers the link request.
  - It asserts the task carries a secret source, and that the program
    resumes with 7.
- **`a-stream-using-module-installs-through-dao-await-test`**
  - It drives `await/run` and `await/resume` with the link pair in the
    options, and asserts the default source is present and the value is
    7.
  - A second case injects a caller source. It asserts that source was
    the one used, minting exactly one child secret, for origin `:t0.0`.
- **Deliberately omitted source.** The existing test is kept unchanged:
  `a-reference-lowers-only-where-it-can-attach-test`, in its "a child
  minted no secret can issue no reference" case. It builds a composition
  with `:secret-source nil`, and the install is refused with
  `:no-capability-secret`.

I did not watch the new tests fail against the old code. Without the fix,
the child has no secret, so `issue-ref` refuses its `stream/make` with
`:no-capability-secret` and both installs are refused.

## Checks

- **JVM, touched namespaces only.** 91 tests / 1,038 assertions, 0
  failures, 0 errors:
  - `yin.vm.linker-require-test`: 26 tests / 503 assertions;
  - `dao.await-test`, `yin.repl-test` and
    `yang.clojure.stream-eval-test`: 65 tests / 535 assertions.
- **clj-kondo** (via `mise exec -- clojure -M:kondo`) on every changed
  file: 0 errors. The remaining warnings are the known pre-existing ones.
- **cljstyle**: clean.
- **ASCII and 80 columns** on added lines: 0 violations.

## Observation: a stream kept in a module's store still needs an attacher

A module that keeps a stream reference in its own store (for example
`(def s (stream/make))`) exports that store snapshot. Lowering it needs
the receiver's `:attach-stream`, which neither the REPL nor `dao.await`
supplies, so such an install fails closed with `:yin.k/unsatisfied` (r9).
The module in these tests uses its stream through a local binding, so its
store holds only the value 7.

Wiring an attacher would mean these compositions keep a directory of the
streams they create. That is outside this brief, so I left it for M5 or a
later fix.

## Unrun

- The full JVM, Node, and Dart lanes (the orchestrator runs them).

Status: COMPLETE
