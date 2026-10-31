Completed-GMT: 2026-10-06 14:49:00 GMT
Completed-Local: 2026-10-06 21:49:00 +0700

SIGN-OFF (ready to land)

The scan now advances between distinct source groups and selects each group’s first candidate in canonical order, preserving replay’s continuation selection without reordering waits. The regressions cover alias-group progress, blocked-then-successful live/replay agreement, and equal-source mint selection. Failed-mint validation and acknowledgment-before-application remain intact. No files were edited and no suites were run.