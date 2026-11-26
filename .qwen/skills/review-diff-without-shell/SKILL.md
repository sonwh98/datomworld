---
name: review-diff-without-shell
description: Produce a defensible diff-of-record and coverage audit for a code review when the session has no shell — grep '^' as a line-count oracle, its ~50k truncation trap, set/superset comparisons, and localizing deleted assertions without git diff
source: auto-skill
extracted_at: '2026-11-12T23:48:41.377Z'
---

# Reviewing a branch with no shell at all

Dispatch briefs here routinely demand `git diff origin/master...<sha> --stat`, "confirm the test
counts, do not trust them", and "hunt the deleted-coverage pattern". When `tool_search` for
`shell`/`exec`/`run`/`bash`/`monitor` returns only `computer_use`/worktree tools, **none of that is
runnable** — and `agent` subagents inherit the same absence, so do not burn a delegation hoping the
child has tools the parent lacks. A complete, citable review is still possible. Three rounds of the
exception-track port review (2026-11-13) ran entirely this way and found a P0-class kit conflict,
an unclosed ruling requirement, four lost assertions, a stale evidence artifact, and a success
metric the brief had misattributed.

Get branch/base/master SHAs first via the `git-state-without-shell` skill. Then:

## 0. On a *delta* re-review, your own last findings file is the only snapshot of the old tip

The worktree has already moved to the new commit, so the intermediate tree is unreachable — no
`git show`, no second checkout. The line counts you recorded in your previous `*.findings.md` are
the "before" side. Keep a per-file table in every report (`| path | master | <sha-A> | <sha-B> |`)
precisely so the next round can diff against it without git; re-measure the same paths and the
per-commit deltas fall out. Read the branch reflog tail for the new commits' subjects — they tell
you which round touched what before you measure anything.

## 1. Establish that a two-tree comparison *is* the diff of record

`git merge-base` is unavailable, so prove it instead. Read `.git/logs/refs/heads/master` (it is
shared across worktrees; page to the **tail** — it can be 800+ lines) and list every commit after
the branch's base. If they are all `docs(…)`/`chore(archive)`, then for `src/` and `test/` the
current master tree **equals** the merge-base tree, and comparing the two working trees gives
exactly `origin/master...<sha>`. Say this reasoning out loud in the report — it is what makes the
substitute evidence admissible. Name the one file the docs commits did touch under `src/` (here
`src/cljc/yin/vm/docs/yin.repl.md`) so its delta is not misread as a branch change.

**Refs move mid-review.** Another seat advanced `master` twice during one round (`868e334a` →
`2506a81e` → `f554e65e`). Re-read `.git/refs/heads/<branch>` and `master` at the **end**, report the
landing SHA, and re-measure anything you measured early if master moved.

## 2. `grep_search` with pattern `^` is an exact line-count oracle

It reports the true `Found N matches` even when it displays one line and truncates the rest, so one
cheap call per path gives a byte-difference proxy. This is the whole method:

```
grep_search path=<tree-A>/src/x/y.cljc  pattern="^"  limit=1   →  "Found 3098 matches"
grep_search path=<tree-B>/src/x/y.cljc  pattern="^"  limit=1   →  "Found 3267 matches"
```

**The trap that will burn you: aggregates above ~50k matches silently truncate and return a
plausible-looking wrong number.** `test/yang` reported 53750 vs 57162 (a "−3412 line" delta that
read exactly like a mass test deletion), while its own subdirectories summed to ~95k per tree and
were **all equal**. Trust per-file and small-directory counts; whenever a directory's parts sum
higher than its whole, discard the whole and re-derive. Cross-check every aggregate against its
parts before believing a scary number.

**Equal counts are not equal bytes.** Pair each count with a content probe: grep the *same* symbol
alternation in both trees and compare match counts (`retained|dsg__new-provenance|handler-stack` →
166 = 166; a 12-name alternation → 429 = 429). Count + probe + identical file set is as close to
"diff is empty" as reading can get, and the report must say it is a substitute, not `git diff`.

**A *negative* grep is the cheap way to read a huge single-line file.** Generated `.edn` artifacts
here are often one comment line plus a multi-thousand-token map on line 2 — `read_file` on them
costs ~6k tokens. To confirm a field was updated, grep for the *stale* values and want zero hits:
`grep 'working-tree|8e1dab6d|f15423c2' depth_post_port.edn` → "No matches found" costs nothing and
proves the revision was re-stamped. Then read only the small human-readable companion (the `.md`
header/footer) for the positive value.

**Cost trap: never `grep_search` with `path=<worktree root>`.** `.cpcache/`, `target/` and `build/`
hold single-line JSON gigabytes-scale basis/manifest files. Grepping the root for a dependency SHA
returned 331 matches and ~15k tokens of `*.basis` classpath dump. Scope every grep to `src/`,
`test/`, a subdirectory, or one file — and use the `glob` parameter (`*.cljc`, `*.edn`) rather than
a path fragment to narrow.

## 3. Close the accounting — that is how you know the file set is complete

Compare file *sets* first (`glob **/*` in both trees; a ±N difference names the added/removed files
immediately). Then per-file counts. Finally **reconcile**: the sum of per-file deltas must equal the
parent directory's delta. In the port review, `src/cljc/yang` measured −71 and the two changed files
measured −169 and +98 — which sums to −71, proving nothing else in that tree changed. An
unreconciled residual means a file you have not found yet.

## 4. Hunt deleted coverage without `git diff`

The deleted/weakened-expectation pattern is the thing most worth catching, and it is findable:

1. **deftest name sets.** `grep '^\(deftest'` in both copies of a test file. A strict superset
   proves no test was deleted; the added names name the new work. (95 ⊂ 103, and 36 = 36.)
2. **Assertion density.** `grep '\(is '` per file. A *drop* while the file grew is the smell
   (4139 → 4165 lines but 318 → 316 assertions).
3. **Localize by line-span arithmetic.** Subtract consecutive `deftest` line numbers to get each
   test's span, then diff the spans across trees. Shrunk spans are the only places assertions can
   have vanished — two spans (−9 and −8 lines) accounted for the entire −2. Read just those, in both
   trees, and you have a byte-level answer for ~40 lines of reading instead of 8000.
4. **Judge, don't just count.** Each loss needs a verdict: dead pin over a surface the change
   deleted (legitimate — say which surface) vs. a weakened expectation (finding). Two static
   refusals inverted to acceptances was a genuine semantic relaxation that belonged in the merge
   request even though the assertion *count* was unchanged.

## 5. Mine sibling seats' `*.stdout.log` for real git output

Collab logs are JSON-lines and embed verbatim `git show --stat` / `git diff` output from seats that
*did* have a shell — commit messages, per-file stats, even full hunks. Grep them for
`git show --stat|git diff --stat|files changed`. **Never grep them for a path fragment** like
`src/clj`: every log embeds a `cat docs/agents/build-n-test.md` dump and one such grep cost ~10k
tokens. See `collab-prompt-dispatch` for reading these logs without drowning.

## 6. Report discipline

- State up front, per required command, `NOT RUN` — including the test counts the brief told you to
  confirm. Corroborate instead (103 `deftest` forms ↔ "103 tests"; 993 assertions ↔ the pre-change
  baseline quoted in a ruling) and label the result *corroborated, not verified*.
- Label every line-number citation by tree (`B:` branch worktree / `M:` master checkout). Never
  present a reconstructed number as a read one.
- Predict what a real run will show, so the next seat can falsify you: "the focused suite is now 105
  deftests, not 103 — a changed count is expected, not a regression"; "this kit test must fail on
  this branch".
- End with copy-pasteable commands for a seat that has a shell, including the `git diff` forms you
  could not run.
- Get the `Completed-*` clock from `web_fetch` on `timeapi.io` (see `collab-prompt-dispatch`); do not
  invent a time and do not reuse the brief's `Created-*` stamps.
