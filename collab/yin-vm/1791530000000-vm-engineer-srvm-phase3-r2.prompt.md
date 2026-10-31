Created-GMT: 2026-10-10 09:40:00 GMT
Created-Local: 2026-10-10 16:40:00 Asia/Bangkok

# Task: phase 3, round 2 -- rule 13 compares the whole vector; commit is handled for you

Role: VM Engineer (yin.vm), continuing session cc3ca0a9-1c0d-4d53-89e0-62880f542713

Your report was read and verified by the orchestrator: JVM and Node both
32 tests / 1128 assertions / 0 failures / 0 errors, kondo 0/0, cljstyle
clean, no trailing whitespace. Thank you for the precise ambiguity list;
all seven readings are accepted as implemented.

## The Architect ruling on your unresolved concern (design §3.4 item 13, amended)

Verbatim from the amended design:

> 13. **Canonical projection**: re-projecting the parsed trees of the
>     whole segment under §3.3 (minting, FIFO body queue order, label
>     placement, saturation) reproduces **the entire vector** exactly,
>     register ids and layout alike (`:noncanonical-registers` when ids
>     differ, `:noncanonical-layout` when bodies or labels are placed
>     differently). This is what makes A the unique projection rather
>     than one of several equivalent spellings: an id-only comparison
>     would accept a vector with two bodies swapped, which is not the
>     projection.

## Do, in this worktree only

1. Extend rule 13 in `code.cljc` to re-project the parsed segment under
   §3.3 and compare the ENTIRE vector: ids differing -> `{:rule
   :noncanonical-registers :pc p}` (first differing pc); layout differing
   with ids equal (bodies in non-FIFO order, labels placed differently)
   -> `{:rule :noncanonical-layout :pc p}`. Keep first-defect-wins order.
2. Add refusal rows in `code_test.cljc`: two bodies swapped with
   `body-pc`s adjusted (expect `:noncanonical-layout`); and, if your
   parse admits it, a label-placement variant. Keep every golden
   accepted.
3. Re-run G1 for the four namespaces on all three lanes, foreground,
   and `clj -M:kondo`, `cljstyle check`. Paste exact counts.
4. Do NOT attempt `git add`/`commit`: the orchestrator commits your work
   after verifying it. Do not stage `collab/`.
5. Overwrite your report `collab/1791530000000-vm-engineer-srvm-phase3.report.md`
   with the round-2 state (same header format; keep the ambiguity list;
   mark the body-order concern resolved by the ruling above).

Begin the final response exactly with:
Completed-GMT: <YYYY-MM-DD HH:MM:SS GMT>
Completed-Local: <YYYY-MM-DD HH:MM:SS Asia/Bangkok>
