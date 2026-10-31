Created-GMT: 2026-10-16 21:40:00 GMT
Created-Local: 2026-10-17 04:40:00 +07 (+0700)
Coding-Agent: claude-cloud
Session-ID: 93cda4c1-ec00-4fd9-8fe6-1d63c63eac89 (resumed; round 6)

# Task: Revise both designs — round 6 (5 new P1s at the next Zend stratum + the convergence instruction)

Role: Lead System Architect

The codex round-5 review: /Users/sto/workspace/datomworld/collab/1792220000000-reviewer-ts-php-r5.gpt-6.1-sol.findings.md
— read whole. It CONFIRMS the round-4 counterexamples now trace correctly and the cursor/acquisition/
microtask findings are addressed. The new P1s (the next Zend stratum):
1. FE_FETCH_RW must not repeat the separating write fetch.
2. Unset cannot share the vivifying FETCH_W procedure.
3. SEND/RETURN lack temporary ownership and cleanup rules.
4. References to typed properties lose their persistent constraints.
5. Object finalization is conflated with freeing storage.

TWO PARTS:
A. Fix the five — extend the opcode-derived table with these handlers' rules and fixtures, same discipline
(zend source-derived, per-row fixtures).
B. THE CONVERGENCE MECHANISM: add a "Known engine-fidelity corners" subsection to the PHP design that
enumerates every Zend behavior still BEYOND the table's scope (each as a named corner with the deviation
it implies and the slice that would close it), and a matching statement that the support profile claims
only the specified fragment. This is the project's established pattern (honest supported-fragment claims
with recorded gaps) — the next review can then grant READY on the specified fragment while the corners
are tracked, instead of the loop walking all of Zend handler by handler. Rule the corners honestly: do
not use this to wave away anything the C4 support profile actually needs.

Begin the final response exactly with:
Completed-GMT: <YYYY-MM-DD HH:MM:SS GMT>
Completed-Local: <YYYY-MM-DD HH:MM:SS +0700>
