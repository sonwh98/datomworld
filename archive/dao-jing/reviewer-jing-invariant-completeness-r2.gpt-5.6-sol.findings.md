I’ll verify the four edited invariant entries in place and check only their immediate interactions with E4/E8 and the Decision 1 signal table. No tests or edits.
Completed-GMT: 2026-09-07 10:46:57 GMT  
Completed-Local: 2026-09-07 17:46:57 Asia/Bangkok  
Coding-Agent: codex  
Session-ID: 01a0776c-dbcf-7343-a6de-ef18f705fec7

- E8 | ADDRESSED | The invariant now covers empty pools, all non-ended members blocked, all-ended pools, and retained payloads from closed members (`dao.jing.implementation-plan.md:159-161`). This matches Decision 1’s walk-exhausted rule and signal table (`:blocked` when every non-ended member blocks; `:end` when every member ends), with no contradiction.

- A3 | ADDRESSED | It now requires distinctness both across types and within a type (`dao.jing.implementation-plan.md:45-48`). The previously missing within-type coverage is explicit.

- B7 | ADDRESSED | The keyword case remains classified as `[T✗]`, but B5’s executable test is explicitly required to include keyword payloads and the former sentinel value (`dao.jing.implementation-plan.md:80-86`). Thus the implementation property remains executable rather than prose-only.

- E12 | ADDRESSED | The old malformed-reader exception is explicitly recorded as a deliberate `[T✗]` drop; E4’s `transport-error` data classification preserves the important guarantees that nothing is addressed and no cursor advances (`dao.jing.implementation-plan.md:173-180`). This is consistent with Decision 1’s defect signaling.

No new contradiction or regression was introduced by these edits.

The invariant list is now complete enough to delete the 54 old tests against.
