Completed-GMT: 2026-10-06 12:00:17 GMT
Completed-Local: 2026-10-06 19:00:17 +0700

SIGN-OFF (ready to land)

The shared link-outcome path now matches the existing protocol: `:ok` transitions, `:full` retains, and terminal outcomes throw with the outcome and link ID. Recorded admission results use that same path, so terminal failures cannot return a false successful discharge. The revised bare-link regression asserts terminal failure and preserves the envelope without assigning an ID. Earlier corrections remain intact; cross-host landing checks remain required. No files were edited and no suites were run.