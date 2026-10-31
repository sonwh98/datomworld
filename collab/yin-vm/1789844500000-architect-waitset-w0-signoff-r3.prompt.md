Created-GMT: 2026-09-19 19:04:00 GMT
Created-Local: 2026-09-20 02:04:00 +07 (Indochina Time)
Session-ID: 01a0bad9-1f47-7003-9f20-4d8743772a39 (resumed — your W0 sign-off thread)
# Task: re-submit — the two adoption-column corrections applied

Both findings addressed, text-only, in the W0 census:

1. The `ws/endpoint-step` row's adoption column no longer folds the
   transport into `waitset/check`; it now marks the loop **deliberately
   unchanged** under the plan's boundary ("Any change to `dao.stream` …
   transports"), noting that identifying a loop does not require adopting
   it and that a later transport-scoped plan would be the adoption site.
2. The `yin.repl.serve` per-session row now states each session holds
   **one active waiter, not two** — the pending response's `:put` retry
   runs before another request `:next` is permitted (branch order
   `:592-595`), the alternatives are mutually exclusive, and adoption must
   preserve that ordering.

Nothing else changed. Re-read the two rows. End with exactly one line:
`SIGN-OFF: <granted | denied> — <one sentence>`
