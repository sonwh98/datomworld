Created-GMT: $(date -u '+%Y-%m-%d %H:%M:%S GMT' 2>/dev/null || date '+%Y-%m-%d %H:%M:%S GMT')
Created-Local: $(date '+%Y-%m-%d %H:%M:%S %z') (+0700)
Coding-Agent: codex
Session-ID: pending

# Task: include-fix r1 — the include-test red on master (the ticks merge window's loader-arm regression)

Role: Engineer (implementation, yang.php). Branch: cut from current origin/master (fc7e9353's lineage — the landing state with the ticks merge + the eval-bridge).

## The red (reproduced twice: the nightly at f8fc4546 on shiva; the laptop at fc7e9353, timeout at 900s then the same 3 FAILs at 2400s)
`yang.php.antlr.include-test/p6-fixtures-linked-on-every-vm-test` — 3 failures, `inc-mutual-once` on :semantic/:stack/:register:
expected ["ab" [] nil]; actual ["", [[2 "include_once(a.php): Failed to open stream: ..."], [2 "include_once(): Failed opening ..."]], nil]
The guest's include_once "a.php" resolves NOTHING — the program runs empty and the mutual-include case collapses.

## The attribution window
4d0d3e70 → f8fc4546: the namespace was GREEN at 4d0d3e70 (its only reds were the python golden lanes). The window contains: the P7-b ticks merge (a8dd702f — declare(ticks)/register_tick_function on the shared :line :after mechanism; the ticks runtime changed statement dispatch on :line boundaries; the include fixture's loader arm is statement-boundary driven) and the eval-bridge rounds (r3-r8). The master-green seat's same-window fixes (golden re-mints, the engine :cljd one-liner, gate shapes) do not touch src/cljc/yang/php — ruled out.

## Scope
1. Reproduce scoped first: clojure -M:test -n yang.php.antlr.include-test -i :slow -v yang.php.antlr.include-test/p6-fixtures-linked-on-every-vm-test (the deftest is ^:slow; expect the 3 failures or the timeout — the linked fixture is heavy on this tip, 900-2400s).
2. Diagnose: the ticks :line dispatch vs the include loader arms (the include/require handling in the prelude — the loader resolves the fixture file through the include path), or the fixture resolution (the harness's file setup), or the :line :after hook's interaction with the include statement boundary.
3. Fix WITHOUT relaxing the assertion; the fixture's expected ["ab" [] nil] is the bar (the master-green seat's instruction).
4. Verify: the include test green (or completing with the fixture green); the tick fixtures stay green (84A); the include fix must not break the ticks behavior (the P7-b oracle folds stay green); kondo/cljstyle.
5. The eval-bridge surfaces (the coordinator/service/emitter) are NOT this round's — do not touch them (the bridge's remaining units are a separate round).

## Rules
Sequential lanes, explicit timeouts (the include fixture runs 900-2400s on this tip). Design gap -> stop + record. Fixtures :oracle :pending where real PHP must adjudicate. Findings: collab/yang-php/<ts>-engineer-include-fix-r1.<model>.findings.md. Conventional commits, push as you go.
