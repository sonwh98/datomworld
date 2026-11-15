# Eval bridge r14: incomplete

No implementation was made and the requested vertical slice is not green. The
plain-call path still raises `:yang.php/unsupported :eval`; the prefix path
still refuses it too. Running the requested `<?php echo eval("return 6 * 7;");`
through `e2e-harness/run-php` returned that diagnostic at span `[11 32]`,
so no evaluator executed the fixture and caller-scope visibility was not tested.

The existing `code/entry` handler reads a declared binding only from an already
installed module and checks the manifest's name, address and export. Its focused
JVM test passed (1 test, 9 assertions; 120-second timeout). The existing eval
service test passed (4 tests, 25 assertions; 180-second timeout); it covers
publication, stage admission and retrieval of an entry closure, but does not
execute that closure in a caller task. It currently asserts that a nested eval
request is unsupported and must be updated when that path is implemented.

Remaining work: add the caller-side request and correlated response flow, call
`compile-and-publish!` through the composition's direct coordinator, make the
published name and active address available to linker admission, retrieve the
installed entry, and invoke it with the caller's runtime, dynamic scope, context
and hooks. Then add the four-evaluator fixture including a caller-variable read,
and run the complete requested sequential gates and lint checks. None of those
gates was run in this session. No commit or push was made because the requested
green gate was not reached.
