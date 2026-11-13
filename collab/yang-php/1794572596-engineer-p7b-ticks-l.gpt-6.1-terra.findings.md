Created-GMT: 2026-11-13 12:23:16 GMT
Coding-Agent: codex
Role: Engineer
Status: incomplete; do not treat this as P7-b acceptance

Red-first fixture mapping (all :oracle :pending):

| Ruling | Fixture | Expected | Red before change |
| --- | --- | --- | --- |
| An enclosing nonzero context selects completion of a nested N=0 declare | :nested-zero-completion | ATBT | ABT |
| A hoisted function retains the preceding semicolon directive's threshold | :hoisted-threshold | TFTT | TFT |
| Unregister an executing entry | :unregister-executing | ASB | ASBS |
| Reentry is guarded per entry, while sites in the called function still count | :per-entry-reentry | AHXYYBHXYY | existing per-entry guard; added as regression |

The ORACLE must adjudicate all four new fixtures, including exact output and successful exit without diagnostics. Their outputs are hand-derived, not measured PHP evidence. Existing pending fixtures remain pending as well.

Lowering now lowers top-level items in lexical order before arranging hoisted execution, and selects the completion of an explicitly zero-ticks block in an enclosing nonzero context. Registry entries retain callback identity while busy, allowing unregister to remove an executing entry. The entry remains guarded against reentry.

Still required: Zend's full excluded and compound statement selection (including do/while, for, foreach and switch); general adjacent tick suppression; fixtures for those cases, N=0 count preservation at N>1, and registry membership changes while dispatch is in progress. The dispatch still iterates a snapshot, so membership changes during a dispatch need explicit oracle adjudication and implementation. The zero-block rule does not implement general adjacent suppression. Do not claim final selection or registry parity from these partial fixes.

Verification recorded separately in the session; the enlarged tick-fixtures run passed 12 assertions; siteset plus shared safepoint were 16 tests / 50 assertions green; kondo and cljstyle were clean. The include-handoff gate passed 1 test / 3 assertions against the final code. No oracle pass performed.
