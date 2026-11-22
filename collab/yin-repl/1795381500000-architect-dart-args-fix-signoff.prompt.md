Created-GMT: 2026-11-23 08:35:00 GMT
Created-Local: 2026-11-23 15:35:00 ICT (+07)
Coding-Agent: interactive (ZCode / glm-5.3-flash, orchestrator seat)
Session-ID: 29f740e7-285e-4b94-a559-27455325c321 (resume of your design conversation)

# Task: Architect sign-off amendment — dart-args test fix

Role: Lead System Architect (commit sign-off amendment)

Implementers:
- Model: gemini-4-argon-high | Assigned: 2026-11-23 15:35 ICT | Status: active | Rationale: extends your sign-off to one additional file

One additional change on top of your APPROVED sign-off (13a94312, uncommitted):
git diff test/yin/repl/dht_args_test.cljc in
/Users/sto/workspace/datomworld-store-swap (7 insertions, 2 deletions).

Background: on Dart this namespace could not compile before the feature
(verified at base ea8e8e15 and master tip 4a8ef025 — cljd compile rc=1 at
both); after the feature it compiles and two tests failed on Dart because
yin.shell.cli/home-dir* is #?(:cljd nil ...) BY DESIGN and the tests called
main/startup without explicit --dir/--key. The fix supplies explicit paths;
assertions unchanged; src/ untouched.

Review record: glm routine review APPROVED
(collab/yin-repl/1795381000000-reviewer-dart-args-fix-gate.glm-5.3.findings.md).
Verification: JVM 4/26 green incl. with home discovery disabled; Node with
HOME unset green; Dart on jupiter green (compile rc=0, all 4 tests passed).

End with EXACTLY one line:
SIGN-OFF: APPROVED — test/yin/repl/dht_args_test.cljc (dart-args host-free fix)
or
SIGN-OFF: REFUSED — <what must change>

Begin the final response exactly with:
Completed-GMT: <YYYY-MM-DD HH:MM:SS GMT>
Completed-Local: <YYYY-MM-DD HH:MM:SS local-timezone-name>

Do not edit files or run suites.
