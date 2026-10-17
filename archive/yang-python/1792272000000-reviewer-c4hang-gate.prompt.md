Created-GMT: 2026-10-16 20:40:00 GMT
Created-Local: 2026-10-17 03:40:00 +07 (+0700)
Coding-Agent: agy
Session-ID: ec14f365-1f16-467d-a9c2-386fa61d7c53

# Task: Independent gate — the REPL shutdown-join fix and the comprehension regression test

Role: Reviewer (adversarial-lite landing gate; gemini-3.8-flash over the opus author per reviewer independence)

WORK TREE (read-only): /Users/sto/workspace/datomworld-c4hang (branch c4hang-fix; the diff over its base:
git diff HEAD — src/cljc/yin/repl/main.cljc (+1/-1 at ~:1349) + test/yin/repl/dht_process_test.cljc (the
new regression test appended at the end)).

CONTEXT: the owner reported the REPL hanging at the prompt after `(lang :yang.python/antlr)` + a filtered
comprehension submission. The round's findings: (1) the spawned-session test couldn't work because on
stdin EOF the REPL queues (quit) but -main joins the poller for only stop-join-millis (~5s) then halts
unconditionally (main.cljc:1345-1346), discarding queued slow work — fixed by joining indefinitely (the
poller self-terminates after a shutdown drain bounded by stop-ticks; the headless path already waited this
way); (2) the test's regex was a character-class bug (matched any space) — fixed to escaped form plus a
print line; (3) the linker undischarged spin from the owner's thread dump did NOT reproduce on this branch
(all stores, all five VMs, ~16s CPU) — left as needing the owner's exact steps; (4) a silent assignment
not reprinting the prompt is a SEPARATE known defect with its own pending fix (not in this diff).

Also in the same worktree's sibling (NOT in this diff): a prompt-owed? fix for that separate defect —
ignore it for this review.

REVIEW adversarially:
1. The indefinite join: is discarding stop-join-millis safe on EVERY shutdown path (headless, served,
   DHT-attached)? Can the poller legitimately run forever now (no drain bound), and what stops it?
   stop-ticks bounds the drain per the engineer — verify in the code.
2. The regression test: does it genuinely reproduce the owner's shape (the lang switch + the exact
   comprehension line) and would it catch the hang's return? The escaped regex now actually asserts the
   printed value?
3. Anything behavioral beyond the join change; scope discipline.

Verdict: end with exactly one line "Verdict: READY (sign-off granted)" or "Verdict: REQUEST CHANGES" with
severity-tagged findings and file:line evidence. Begin the final response exactly with:
Completed-GMT / Completed-Local
