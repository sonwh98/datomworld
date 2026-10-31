Completed-GMT: 2026-10-06 14:12:06 GMT
Completed-Local: 2026-10-06 21:12:06 +0700

CHANGES

1. **Advance the scan across distinct sources, preserving first-match selection within each source.** [reader.cljc:498](/Users/sto/workspace/datomworld-d12/src/cljc/yin/vm/ucf/holder/reader.cljc:498) advances past the first candidate matching the saved source. With two waiters sharing a cursor, an acknowledged blocked read leaves both candidates with the same source; live execution next selects the second waiter, while replay selects the first. A subsequent successful read therefore wakes different continuations. Repeated blocked observations can also keep selecting the second alias indefinitely, starving a later distinct source. Scan distinct source groups deterministically and always select their first candidate in canonical candidate order. Add regressions for two aliases followed by a ready distinct stream, and blocked-then-successful delivery proving identical live/replay continuation selection; include equal-source mint candidates.

The failed-mint correction is sound: validated successful positions alone are installed, while failures remain recorded inputs without making cells readable or links sendable. The new distinct-stream progress test passes over the equal-source case that exposes the remaining defect. No files were edited and no suites were run.