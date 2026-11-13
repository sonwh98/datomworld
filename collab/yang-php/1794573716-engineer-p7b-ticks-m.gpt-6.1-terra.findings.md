Created-Local: 2026-11-13 19:41:56 +0700
Coding-Agent: codex
Role: Engineer
Status: incomplete; oracle adjudication and general adjacent suppression remain open.

Twelve new fixtures record the current engine's `:out` as a provisional expectation; all remain `:oracle :pending`. The corpus has not been run on PHP. The engine harness returns output only here; the oracle must also check exit and diagnostics. The existing :unregister-executing fixture remains in the corpus; :dispatch-self-remove adds a second handler to reveal iteration effects.

Red-first evidence: :adjacent-directives was first tested with `TAT`, derived from the Directive syntax row of the counter decision table (adjacent duplicate tick emission is suppressed). The engine produced `TTAT`. Its fixture now records the current engine value, pending oracle adjudication. General suppression was not implemented: the lowering tree's administrative nodes do not identify Zend instruction adjacency, and treating arbitrary neighboring marked trees as adjacent instructions would be unsound. This is unfinished requested work, not a parity claim. No other engine behavior was changed.

Verification and push status are recorded above the final corpus table below.

| oracle-adjudicate fixture id | Question answered |
| --- | --- |
| :do-while-selection | Which completions around and within a do/while produce ticks? |
| :while-selection | Which completions around and within a while produce ticks? |
| :for-selection | Which completions around and within a for produce ticks? |
| :foreach-selection | Which completions around and within a foreach produce ticks? |
| :switch-selection | Which completions around and within a selected switch case produce ticks? |
| :adjacent-directives | Are the ticks emitted by consecutive semicolon directives suppressed as adjacent? |
| :adjacent-declares | How do adjacent zero-block declare completions and surrounding statements count? |
| :nested-zero-n2 | Does the request count survive a zero block inside an N=2 region, including the declare completion? |
| :dispatch-add | Does a handler registered during dispatch run in the current dispatch or a later one? |
| :dispatch-remove | Does removing a not-yet-invoked handler during dispatch affect the current dispatch? |
| :dispatch-self-remove | How does self-removal affect the current dispatch and the next dispatch with another handler present? |
| :foreach-generator-yield | When does a yield inside foreach complete its tick site across resumes? |
