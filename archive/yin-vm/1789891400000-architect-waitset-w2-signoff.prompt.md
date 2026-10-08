Created-GMT: 2026-09-20 08:00:00 GMT
Created-Local: 2026-09-20 15:00:00 +07 (Indochina Time)
Session-ID: 01a0bacb-3b91-7190-8412-3f1e85bb552a (resumed — your consensus/W1 thread)
# Task: architect sign-off — dao.stream.waitset Phase W2 (engine integration)

You signed W1. W2 — the engine becoming the waitset's first consumer — is
now built by glm-5.3-full and adversarially reviewed by fable-5-1
(resumed session). Verified by the orchestrator: engine focused 19 tests /
82 assertions / 0 failures (18 existing UNCHANGED + 1 new diagnostic-path
test); JVM full 1541 / 168704 / 0; CLJS 1457 / 38573 / 0; CLJD run by the
orchestrator — zero yin.vm failures (only the 29 pre-existing voxel
failures from an un-merged sibling branch).

Under review — the delta in this working tree
(`/Users/sto/workspace/worktree-w2`): `src/cljc/yin/vm/engine.cljc`
(`check-wait-set` public and one-argument, internally `waitset/check` with
the two-function resolver; `augment-wait-entry`/`poll-wait-entry` deleted;
diagnostics raise before restoration) and the one added engine test.

The reviewer found no P0/P1/P2. Four P3 notes carried, none blocking: a
hand-built `:next` entry without `:cursor-ref` would resolve through the
writer branch (no park site builds one); the third diagnostic
(`invalid-answer`) lacks an engine test (the library tests cover it);
the diagnostic error data embeds the whole entry; and a pre-existing
shared-cursor resume-time note outside this delta.

Your sign-off questions:
1. Is the integration faithful to the consensus's item 1 (public function
   preserved, extraction clean) and item 7's diagnostic ordering?
2. Do the disclosed behavior differences (`:cursor nil` on terminal
   readers; `:store-updates` always computed) stay inside "unchanged
   suites, unchanged semantics"?
3. Any architectural objection to carrying the four P3 notes as recorded
   follow-ups?

Do not relitigate closed findings. End with exactly one line:
`SIGN-OFF: <granted | denied> — <one sentence>`
