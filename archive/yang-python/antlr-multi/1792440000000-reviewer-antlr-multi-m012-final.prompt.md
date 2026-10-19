Created-GMT: 2026-10-18 08:05:00 GMT
Created-Local: 2026-10-18 15:05:00 +07 (+0700)
Coding-Agent: codex gpt-6.1-sol

# Task: M0-M2 FINAL verification — only the NEWLINE/DEDENT fixtures remain

Your re-gate returned REQUEST CHANGES with exactly one item: the corpus pinned INDENT/EOF but the
READY'd review required NEWLINE/DEDENT disagreement fixtures. A fixture round landed. Everything else
you ruled resolved or accepted (the validator, the policy, the 14 mutations, the P2 docs, per-host
pinning, the token-ordering debt) — do not re-review those.

WORK TREE (read-only): /Users/sto/workspace/datomworld-antlr-multi, branch antlr-multi. THE FIXTURE
DIFF: `git diff 25a908fb a18666fb` (one commit, test-only). Your re-gate log:
collab/1792400000000-reviewer-antlr-multi-m012-regate.gpt-6.1-sol.stdout.log. The fixture report:
collab/m012e-report.log.

VERIFY (focused):
1. The NEWLINE fixture uses your probe source "if x:\n    x =\n"; the DEDENT fixture puts the error on
   a DEDENT token ("if x:\n    @d\ny = 1\n" — a decorator with no definition followed by a dedent).
2. Each case runs the strict conformance/check-error oracle: line, column, byte span AND error-packet
   conformance, on all three hosts.
3. Per-host pinning discipline: the coordinates come from each host's own runs (JVM NEWLINE [3 0],
   Node [3 0], Dart [2 7]; DEDENT JVM [2 0] span [13 13], Node [3 0], Dart [2 6]); the byte spans
   agree everywhere ([13 14] / [13 13]). Is per-host line/column with agreeing byte spans the right
   recording under the "not required to agree" contract?
4. The lane evidence: JVM cst-export 16/173/0 (up from 15/151), Node 4078/0, Dart all passed — the
   corpus exercised on all three hosts.

Verdict: end with exactly one line "Verdict: READY (sign-off granted)" or "Verdict: REQUEST CHANGES"
with findings. Begin the final response exactly with Completed-GMT / Completed-Local.
