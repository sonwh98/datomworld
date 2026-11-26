---
name: git-state-without-shell
description: Determine branch, commit and worktree layout in this repo using only read_file/list_directory when no shell tool is available
source: auto-skill
extracted_at: '2026-11-07T09:52:01.062Z'
---

# Reading git state with file tools only

Sessions here sometimes have **no `run_shell_command`** (confirm first: `tool_search` for
`shell`/`exec`/`run_shell_command`; if only `computer_use`/worktree tools come back, there is
no shell). `git status`, `git diff --stat`, `git log` and branch checkouts are then impossible —
but the state is still readable from `.git`:

| Question | Read |
|---|---|
| Branch of the main tree | `/…/datomworld/.git/HEAD` (`ref: refs/heads/<branch>`) |
| Branch tip SHA | `/…/datomworld/.git/refs/heads/<branch>` (loose ref, plain text) |
| Which worktrees exist | `list_directory /…/datomworld/.git/worktrees` |
| Branch of a worktree | `/…/datomworld/.git/worktrees/<name>/HEAD` |

**Gotcha:** `.git/refs/**` is git-ignored, so `list_directory` returns *0 items* unless you pass
`file_filtering_options: {"respect_git_ignore": false}`. Same for `.git/worktrees`.

## A brief naming a branch may point at the wrong tree

This repo runs many seats in parallel worktrees (`datomworld-f12`, `-b1`, `-j6g`, `dw-f12`, …).
Dispatch briefs routinely assert "branch X is checked out on the laptop working tree" and be
**stale** — the main tree is often on `master` while the branch lives in a worktree.

Verify before editing:
1. Read `.git/HEAD`. If it is not the branch the brief names, do **not** edit the main tree.
2. List `.git/worktrees`, read each `<name>/HEAD`, find the one on that branch.
3. Read `.git/refs/heads/<branch>` and compare with the SHA the brief quotes (e.g. `4f772d61…`).
4. Edit files in that worktree by absolute path (`/Users/sto/workspace/datomworld-f12/src/…`).
   Reading a second copy of the same file across both trees is also the fastest way to diff a
   fix by eye when `git diff` is unavailable.

**Step 4 often does not work — test it before promising it.** In non-interactive sessions the
tool sandbox is usually the *project root only*: `read_file` and `list_directory` on a sibling
worktree (`/Users/sto/workspace/datomworld-r3-orch/src/…`) and even on `/Users/sto/workspace`
return *"requires permission … but that permission was declined (non-interactive mode cannot
prompt for confirmation)"*. `.git/worktrees/**` **inside** the project is still readable, so you
can name the worktree and its branch but not read a line of its source. Probe with one cheap
`read_file` early; do not discover this after planning around it.

When the commit under review is unreachable that way, and there is no shell to `git show` it:

- Grep the **current** checkout for the symbols the brief names. Zero hits proves the branch is
  not merged here, so the checkout is the *pre-fix baseline* — often the single most useful piece
  of evidence (it shows what the change displaced).
- Shared layers the branch did not touch are still authoritative. A review log's own
  `git diff --stat` names the touched files; anything outside that list can be cited from the
  current checkout, and line numbers frequently still match (verify one anchor before trusting it).
- Take the changed files' line numbers from the reviewer logs, and **label every citation by which
  tree it came from** ("R3-branch numbers quoted from <log>; numbers I verified are at master
  `<sha>`"). Never present reconstructed numbers as read ones.
- Sub-agents inherit the same denial — do not burn a delegation on it.

Report the landing spot explicitly (which worktree, which SHA it is on) so the orchestrator can
stage/commit; state that `git diff --stat` was not run and give the insertion count derived from
the file's total line count before/after instead.
