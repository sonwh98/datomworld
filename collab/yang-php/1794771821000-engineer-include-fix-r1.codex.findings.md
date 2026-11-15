Created-GMT: 2026-11-15 19:43:41 GMT
Created-Local: 2026-11-16 02:43:41 +0700
Coding-Agent: codex
Session-ID: pending

# Include fix r1: cyclic bundle rejected by linker after instrumentation

Branch based on local `origin/master` fc7e9353. No implementation change is retained. Design decision needed before proceeding.

## Evidence

- The requested slow test was started with the exact scoped command and a 2400-second subprocess timeout. It was manually interrupted after approximately 900 seconds without an assertion result; this is not a completed reproduction or a test timeout.
- A direct probe published the actual `a.php` / `b.php` mutual-include SCC and applied the ticks site-set stage to each member alias and its bundle. Publication and all three stage decisions were `:ok`.
- A single-semantic-VM run of the same fixture, tracing linker attempts, reproduced the warning and empty output. The alias `phpm.a$$php` linked successfully, but the subsequent `phpscc.a$$php+b$$php` link returned `{:status :refused, :reason :undeclared-free, :name m1-body}`. The PHP diagnostic retains that refusal in `:yang.php/refusal`. Thus the files are published; the guest warning masks a cyclic bundle link refusal.
- The SCC emitter defines member bodies as mutually referring deferred lambdas. Linker's `undischarged` rule only discharges a deferred body reference when an unconditional definition precedes every application site. The ticks stage introduces hook calls into the module and prepends a `php.sp` require; this interacts with that conservative rule. The exact individual application sites responsible have not been separately enumerated.
- An experiment replacing the stage's outer `u/then` with a module-level wide application, retaining the hook require before the module, still produced the same `m1-body` refusal and empty result. It was reverted. No assertion or fixture was changed.

## Decision needed

Determine the intended contract for instrumented SCCs and linker dominance: in particular, how the hook is made available while all mutually referenced body bindings are provably established before application sites, without weakening admission or running guest code prematurely. A potential placeholder/initialization ordering needs review against hook availability, module initialization, and all supported backends; it has not been implemented or validated here. This is a design boundary, not evidence that the stage itself refuses the bundle.

## Validation and limitations

No green include or tick verification is claimed. Tick fixtures, P7-b oracle folds, clj-kondo and cljstyle were not run because the implementation is unresolved. An incorrectly invoked scratch probe accidentally started the broad test runner; it was interrupted and its unrelated failures are not a validation result. No eval-bridge files were touched.
