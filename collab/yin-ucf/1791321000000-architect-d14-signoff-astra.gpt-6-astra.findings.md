The recovery seam now uses the shared value/cell codec and checks the snapshot against the published body before attachment. I’m checking the restart paths next, particularly how they distinguish retained exit obligations from authority to resume execution.


Completed-GMT: 2026-10-06 22:39:37 GMT
Completed-Local: 2026-10-07 05:39:37 +0700

SIGN-OFF (ready to land)

The rebuild satisfies the ruling: the separately versioned recovery object uses the shared value/cell codec, captures transitive dependencies, validates before attachment, and reconstructs fresh-receiver state fenced without program IO. Journaled retry intents preserve unknown delivery; exit ordering follows report, matching release and authoritative closure before successor admission. The enrollment, ordered recovery, stalled-state, journal-read, tenure and abort corrections are present with targeted regressions. I accept the conservative restart behavior: uncommitted reports gain no authority from saved tenure; cleanup and reconciliation preserve an already committed exit or return to candidacy. Reviewed read-only; no suites run.