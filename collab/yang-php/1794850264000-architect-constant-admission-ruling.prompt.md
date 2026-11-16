Created-GMT: 2026-11-17 (dispatch time)
Coding-Agent: codex (gpt-6-astra, jupiter POOL 2, read-only)
Session-ID: 01a1e569-b991-70d2-b81a-b5bafb3a0501 (codex thread.started, jupiter POOL 2)

# Task: Architect ruling — bare global constant reads: the :constant admission gap in lower.cljc

Role: Architect (read-only consult).

## The unit (routed to the yang.php seat by the master-green seat's findings-only round)
The 4 const-* rows of yang.php.antlr.include-test/p6-fixtures-linked-on-every-vm-test throw
"Unsupported PHP construct: :constant" from the LOWERER — constant-node refuses bare global
constant reads (lower.cljc) at admission, before linked execution, independent of runtime
composition. Evidence of record: the per-row payloads in
collab/exception-track/1794854000000-engineer-dyn-continuation-corruption-isolated.codex-gpt-6.1-terra.findings.md
(:const-declare-and-read span [41 42], :const-redeclare-warns [37 38], :const-undefined-error
[17 21], :const-define-redefinition [53 54] — spans are per-fixture-program character offsets).
The rows are green in subsystem order but red isolated: the order-dependence is NOT the root
cause (the refusal is compile-time); explain why subsystem order passes if you can (suspect:
a warm path elsewhere admits constants — locate it).

## Rule with (real PHP 8.3 is the reference — cite php-src where it decides)
1. **Intended admission contract**: should yang.php admit bare global constant reads (the
   `CONSTANT_NAME` expression node), or is the refusal correct and the fixtures over-reach?
   The fixtures assert: declare+read, redeclare warns, undefined-constant is an error
   (E_ERROR "Undefined constant"), redefinition. PHP 8.3 semantics: constants live in a global
   per-request table; `define()` at runtime; bare reads resolve at execution with an
   E_ERROR-class uncatchable Error since 8.0 (not a notice). Check what the 4 fixtures actually
   assert vs PHP rules.
2. **The fix shape** if admission is intended: (a) a constant-node lowering arm (read-time
   resolution through the runtime constant table; undefined => the PHP 8.3 Error shape, mapped
   per the landed astra class-fatal ruling 1794798003000 — uncatchable fatal, errno 8 class
   Error, status 255) vs (b) lower to an existing admitted node class. Name the exact loci
   (lower.cljc constant-node site; prelude runtime support if needed; the diagnostic channel).
3. **define()/redeclaration semantics**: define() on an existing name warns (E_WARNING in
   PHP 8, false return) but does NOT fatal — the fixture :const-redeclare-warns and
   :const-define-redefinition rows pin this. Confirm the warn channel (php/warn-record errno?)
   and that redefinition is not fatal.
4. **Non-weakening bar**: no relaxation of the site-set admission, the linker discharge rule,
   or the guest-fatal contract; the refusal for genuinely unsupported constructs stays.
5. **Decision table**: per-row expected results after the fix (all 4 rows), the guard rows
   (undefined constant error shape, redeclaration warn, valid read), and what must NOT change.

## Evidence
- dw-regfix @ 04f98c48 (current master content) or dw-dynfix @ its staging-based head — read-only.
- The landed astra class-fatal ruling: collab/yang-php/1794798003000-architect-class-fatal-ruling.findings.md.
- The lifecycle oracle: collab/orch-lane/p7-lifecycle-probes.txt (constants within eval — if
  touched, the incremental-registration rule applies).
Deliverable: inline (read-only session; the seat files it) as
collab/yang-php/<ts>-architect-constant-admission-ruling.findings.md content.
