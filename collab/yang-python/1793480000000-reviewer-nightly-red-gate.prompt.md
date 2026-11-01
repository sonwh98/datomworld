Created-GMT: 2026-10-31 03:05:00 GMT
Created-Local: 2026-10-31 10:05:00 +07 (+0700)
Coding-Agent: kimi (fresh read-only session)

# Task: the nightly-red landing gate — the dht_import fixture and the linked_prelude expectations

Role: Architect landing gate (read-only; your sign-off authorizes the merge under the
commit-authority rule). Reviewer independence: the implementer was codex gpt-6-luna; you are a
different family.

WORK CHECKOUT (read-only): /Users/sto/workspace/datomworld-nightr.
BRANCH nightly-red-fix, tip 78c3bf29. THE DIFF: `git diff origin/master nightly-red-fix` — two
test-only commits: the dht_import fixture fix (the stand-in manifest stored under its minted
address; the post-refusal assertion strengthened to check no additional content address was
published) and the linked_prelude module-shape expectations refreshed for the yang.call emission.

THE PEER BRIEF: /Users/sto/workspace/datomworld/collab/1793456746000-orchestrator-yangpython-nightly-red.prompt.md
(the nightly's 3 JVM failures on both hosts; the adjudication rule: fix yang.python's stale
fixtures, never dao.jing's semantics).

VERIFY:
1. THE FIXTURE FIX: the stand-in address is now a real content address minted by the store, the
   unpublished-import refusal path still exercises (k stays unpublished when t publishes), and the
   strengthened post-refusal assertion (no additional content address published) is a real
   improvement, not a tautology.
2. THE EXPECTATIONS: the linked_prelude updates track the yang.call emission's module shape
   faithfully — the runtime placeholders first, the definitions in order, Rule R and the
   stripped-symbol invariants intact. No invariant weakened to make a test pass.
3. THE ADJUDICATION: dao.jing untouched (grep the diff); the H1 guard's behavior respected; the
   defect location correctly attributed to the stale fixtures/expectations.
4. THE EVIDENCE (shiva, 78c3bf29): the nightly-identical fast-tier selection — dht-import 4/31/0,
   linked-prelude 6/42/0, float-address 12/105/0; kondo 0/0; cljstyle clean. The slow DHT bodies
   (81 failures unfiltered) are EXCLUDED from this gate by design: they belong to tonight's nightly
   and a diagnosis slice (the fast-tier gate the nightly ran selects exactly this tier).
5. THE SCOPE: test-only diff; no dao.jing changes; no production code touched.

Verdict: end with exactly one line "Verdict: READY (sign-off granted)" or "Verdict: REQUEST CHANGES"
with findings. Begin the final response with Completed-GMT / Completed-Local / Coding-Agent /
Session-ID (provider metadata; say so explicitly if unavailable).
