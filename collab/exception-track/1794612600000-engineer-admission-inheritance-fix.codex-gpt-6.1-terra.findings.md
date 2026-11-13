# Admission inheritance investigation — incomplete

Base: origin/master `746b2c34cdb7b4ccc00cf8b3d9a72c4172ba1122`. This branch contains this report only; no fix is proposed. The triage document named in the dispatch was absent from the checkout.

## Discriminator (jupiter, isolated worktree)

Generated worktree prerequisites with `bb gen:antlr`, rc 0. Initial attempts before generation failed at startup with `ClassNotFoundException: yang.php.antlr.gen.PhpLexer`, without running tests. After generation, `clojure -M:test -n yin.vm.linker.admission-test` at each revision gave:

- `4d0d3e70`: rc 0; Ran 38 tests containing 509 assertions; 0 failures, 0 errors.
- `c0216b9f`: rc 1; Ran 38 tests containing 509 assertions; 182 failures, 7 errors.
- `6445f37a`: rc 1; Ran 38 tests containing 509 assertions; 184 failures, 7 errors.

## Attribution and remaining work

The regression starts in step 2. The old shell fixture calls `yin.repl/eval-input`, `recheck-pending`, and `recheck-on-tick` directly, puts the DHT node in the engine, and passes `:dht-key`. The rewritten engine intentionally defers signed-name links to `yin.shell.session`, keeps transport outside the engine, and uses `:publisher-key`. Consequently these tests do not exercise the new composition, and absence of a recorded admission is not by itself evidence that module inheritance is broken. The new `yin.shell.linker/serve-links` returns pending identities, but `yin.shell.session/serve` discards them; the engine uses its parked `:links` for admission visibility and completion matching. Also, the old bounded same-tick continuation and successor rollback expectations require careful reconciliation with the new composition's single serve round. This is a composition/handoff contract question; this investigation does not establish that admission cannot survive the architecture, nor does it establish a safe complete fix.

An exploratory, uncommitted adaptation of the fixture to `yin.shell.session` and pending handoff, subsequently discarded, made the named inheritance test pass: `clojure -M:test -n yin.vm.linker.admission-test -v yin.vm.linker.admission-test/an-install-child-inherits-the-admission-of-a-preinstalled-entry-test`, rc 0, Ran 1 test containing 24 assertions, 0 failures, 0 errors. The exploratory full namespace remained red: rc 1, Ran 38 tests containing 507 assertions, 29 failures, 1 error. Those counts include experimental fixture changes and are not validation of a fix. One extra exploratory `clojure -M:test -e ...` inadvertently started the unfiltered test runner and was interrupted before a summary; no result is claimed.

The fix needs to preserve admission pending identities, bounded progress, and the entire successor across rollback in the composition while migrating the old shell tests to drive the real shell boundary without relaxing assertions. This will overlap `yin.shell.session` and possibly `yin.shell.linker`; coordinate landing with the refactor seat. No Family B/C files were changed.

Required Node/shiva, adjacent namespace, and `bb test:sub:clj dao.stream` verification were not run because no complete fix exists. Branch head is the commit containing this findings file; its parent is the base SHA above.
