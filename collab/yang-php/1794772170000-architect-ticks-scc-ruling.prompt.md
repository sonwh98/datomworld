Created-GMT: 2026-11-14 (dispatch time)
Coding-Agent: codex
Session-ID: pending

# Task: Architect ruling — the ticks instrumentation vs the linker's undischarged-deferred-reference rule (the cyclic mutual-include SCC)

Role: Architect (read-only design consult; you keep the eval-bridge design and the linker admission contract is in scope)

## The failure
`yang.php.antlr.include-test/p6-fixtures-linked-on-every-vm-test` (the include gate at master f8fc4546+) — the mutual-include fixture: `include_once "a.php"` resolves nothing at :semantic/:stack (the nightly's 3 failures; the laptop reproduction confirms).

## The diagnosis (include-fix r1, the engineer's findings — READ FIRST)
collab/yang-php/1794771821000-engineer-include-fix-r1.codex.findings.md (committed on codex/include-fix-r1 @ 797c72b4; on origin too):
- The fixture files publish successfully; the alias `phpm.a$$php` links; the SCC bundle `phpscc.a$$php+b$$php` link REFUSES `{:reason :undeclared-free, :name m1-body}`.
- The SCC emitter defines member bodies as MUTUALLY REFERRING DEFERRED LAMBDAS. Linker's `undischarged` rule only discharges a deferred body reference when an unconditional definition precedes every application site.
- The ticks instrumentation introduces hook calls into the module and prepends a php.sp require — this interacts with the conservative discharge rule. A wrapper change experiment still refused; reverted.
- The engineer asks: the intended contract for instrumented SCCs and linker dominance — how the hook is made available while all mutually referenced body bindings are established before application sites, without weakening admission or running guest code prematurely.

## Rule with
1. The intended contract: how the linker's discharge rule interacts with instrumentation-inserted calls (the ticks hook calls are compiler-inserted, not guest-authored — do they change what "unconditional definition precedes every application site" means?).
2. The fix shape: (a) amend the linker's discharge rule for instrumentation-introduced references (the design amendment + the admission-safety analysis), (b) change the instrumentation's insertion shape so the discharge rule is satisfied (e.g. the hook require/reference placement), or (c) another shape you identify.
3. The decision table: the fixture rows (the mutual include, the ordering), the guard conditions, and what must NOT weaken (admission, no premature guest execution).
4. The fixture implications for the include gate's expectations (the master-green seat's instruction: the fixture's expected ["ab" [] nil] is the bar — does the ruling preserve it?).
Evidence to read: the findings (above), the linker's undischarged rule implementation (yin/vm/linker — locate it), the SCC emitter's deferred-lambda shape (yang.php/antlr — the SCC bundle emission), the P7-b ticks instrumentation (the :line :after hooks on the shared core), the lifecycle oracle record (collab/orch-lane/p7-lifecycle-probes.txt).
Deliverable: collab/yang-php/<ts>-architect-ticks-scc-ruling.findings.md. Read-only consult.
