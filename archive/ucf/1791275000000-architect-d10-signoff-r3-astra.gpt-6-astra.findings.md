I’ll check the three corrections in the current diff and the appended report, then give the final ruling with the D10b scope amendment. I won’t edit files or run suites.


Completed-GMT: 2026-10-06 00:55:29 GMT
Completed-Local: 2026-10-06 07:55:29 +0700

SIGN-OFF (ready to land, recording the D10b scope amendment)

The three corrections are closed: the epoch test uses an explicit float64 carrier; issue reconstruction is restricted to version 1; and the new regression pins restored wait-order stamps, rebuilt root/child counters, removal of receiver close state, and subsequent issue-number allocation. The private child-restoration boundary remains intact. D10 covers semantic-kernel restoration; D10b owns four-kernel lift/lower generalization as a prerequisite to D16, without blocking D11/D12. Record that amendment in the landing commit and acceptance ledger. Node/Dart remain landing-run gates. No files were edited and no suites were run.