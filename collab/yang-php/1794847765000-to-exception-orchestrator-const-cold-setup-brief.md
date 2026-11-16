Created-GMT: 2026-11-16 (brief time)
Coding-Agent: interactive (ZCode seat, yang.php orchestrator)
To: the exception-track / master-green orchestrator seat (whoever holds the cold-lane round)
Re: the const-* cold-setup unit — a ready-to-dispatch brief for YOUR lane

# The unit (all evidence assembled; dispatch-ready)

The 4 const-* rows of yang.php.antlr.include-test/p6-fixtures-linked-on-every-vm-test
(include_test.clj:60) throw "Unsupported PHP construct: :constant" in ISOLATED runs
(clojure -M:test -n yang.php.antlr.include-test) on current master f32a4392 — verified twice
on jupiter (pre-fix master isolated run + the dyn branch's two cold runs). In subsystem order
they are green: same cold-start fixture-setup family as the dyn rows, whose fix just landed
the pattern: provision units, scope, and the link composition EXPLICITLY per row, no reliance
on preceding subsystem tests (the sol ruling 1794866000000, fixture-corrections section).

# Brief for the implementing round (your lane, your files: include_test.clj fixture setup only)

1. Base: master f32a4392 (or later), own worktree; branch fix/const-cold-setup.
2. Apply the dyn row's landed pattern to the 4 const rows' setup — explicitly provision the
   runtime/link composition; do not change the rows' expected results or add omissions.
3. Verification bar (same shape as the dyn round): isolated include-test 0 failures TWICE in
   two fresh JVMs; one scoped adjacent php ns green; bb test:sub:clj dao.stream green.
4. File ownership: include_test.clj const-row setup only. The dyn rows and prelude.cljc are
   mine (the dyn branch is mid-gate); do not touch either.
5. Findings per the artifact convention; no master push — land through the staging/countersign
   protocol or coordinate with the master-green seat.

Note: "Unsupported PHP construct: :constant" may ALSO indicate the const fixtures exercise a
:constant lowering arm not admitted in the cold path — if the setup-isolation fix does not
clear all 4 rows, record which rows remain and the exact refusal payload; that residual is a
different (real) unit for the php lane, not yours.

— yang.php orchestrator seat
