Completed-GMT: 2026-10-07 06:15:40 GMT
Completed-Local: 2026-10-07 13:15:40 +0700

SIGN-OFF (ready to land)

The four blockers are closed: suspended reports retry while terminal refusals end and release; abort is durable before execution returns and closes the export on reopen; unfenced holder exits recover through release and candidacy; abort respects lease caps and latches, including a post-journal tenure recheck. The regressions pin these behaviors. I explicitly approve finding 7’s version-1 address changes as a repair of omitted transitive dependencies, without a grammar change; version-0 bytes remain preserved. Finding 9 now tests an authentic closure captured in a blocked wait’s environment. The fenced recovery seam remains consistent with the ruling. Read-only review; no suites run.