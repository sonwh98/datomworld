# Dispatch: one-envelope migration slice 2 — remote correlation integrity + pair channel loss

Role: dao.stream engineer. Implement migration slice 2 of the
one-envelope ruling
(collab/1790575143000-architect-one-envelope-ruling.gpt-6-sol.findings.md
— read its "Migration order" item 2 and the "Identity" section).
These are the two independent remote-core fixes the ruling ordered
BEFORE the wire-vocabulary commit. Fable's final sign-off flagged
both (P3 forgeable answer, P2-2 never-ending pair).

## Change 1: absorb! identity check (src/cljc/dao/stream/remote.cljc:401-438)

Today absorb! accepts any well-formed answer whose id is outstanding
-- it looks up ONLY by id (:dao.stream.remote/id v). The mirror
echoes the request identity, so a well-formed answer also carries
:dao.stream/identity. A hostile or buggy writer who can append to a
reader can currently complete someone else's request.

Ruled behavior: an answer is accepted only when BOTH the correlation
id AND the :dao.stream/identity match the outstanding entry's
reflection identity. The check must run BEFORE any state change --
today the (swap! link update :outstanding dissoc id) and
(swap! refl update :ids disj id) at :413-414 happen before the cond;
the identity gate must precede them. A mismatched answer is a
diagnostic only: the request stays outstanding, the reflection's id
set is untouched, nothing is filed, nothing emitted, no error
raised. Find where the reflection's own identity lives (the refl
record / its descriptor) and compare against it.

## Change 2: pair reader ends on in-stream not-found/channel-gone
(src/cljc/dao/stream/remote_pair.cljc:94-110)

The pair reader's 'next' translates only :dao.stream/gap and
:dao.stream/end from the in stream into :dao.stream/end. A reclaimed
relay pair makes the in reflection answer :dao.stream/transport-error
with :dao.stream.remote/reason :dao.stream.remote/not-found (or
channel-gone); that falls through the default branch raw, the outer
link's drain! (remote.cljc:443+) ignores every outcome except
ok/blocked/end/gap, channel-loss! never runs, outstanding appends
never get append-unknown, and inner cursor/next keep answering
retry?/blocked forever.

Ruled behavior: on in-stream :dao.stream/transport-error whose
reason is not-found or channel-gone, end the pair binding exactly as
gap/end do (reset! ended? true, return :dao.stream/end) so the outer
link observes the loss and abandons outstanding appends with
append-unknown. Do not change gap/end behavior; do not end on other
transport errors (retryable ones keep their current pass-through).

## Test pins (test/dao/stream/remote_test.cljc, test/dao/stream/remote_pair_test.cljc)

1. A wrong-identity answer (correct id, different identity) is
   dropped as a diagnostic: the request REMAINS outstanding, and the
   correct answer arriving afterwards still completes it normally.
2. A pair whose in stream answers not-found (e.g. the relay pair was
   reclaimed) ends the binding: the outer link's drain! runs
   channel-loss! -- outstanding appends observe append-unknown -- and
   no waiter is left polling forever. (There is an existing gap-path
   pin to model the assertion shape on.)
3. Retryable transport errors on in do NOT end the binding
   (regression guard for the pass-through).

## Constraints

- Allowed files: src/cljc/dao/stream/remote.cljc,
  src/cljc/dao/stream/remote_pair.cljc,
  test/dao/stream/remote_test.cljc,
  test/dao/stream/remote_pair_test.cljc ONLY.
- Run via mise from repo root: focused namespaces first
  (mise exec -- clojure -M:test -n dao.stream.remote-test etc.),
  then the full JVM suite. All green before reporting (the known
  yin.repl.main-test intermittent is the ONE tolerated failure if it
  appears; note it explicitly).
- cljstyle check on touched files.
- Do NOT git add or commit -- the orchestrator stages and commits
  after the gate.
- Report to collab/1790577111000-vm-engineer-remote-core-correlation.glm-flash.report.md:
  changes with line refs, exact test commands + counts, git status
  output.
