Created-GMT: 2026-10-06 10:46:41 GMT
Created-Local: 2026-10-06 17:46:41 +07
Coding-Agent: claude
Session-ID: d8d95e30-f2d2-443b-9aa9-36e7001a904e

# Task: architect-head-h3-followups

Role: Lead System Architect

Implementers:
- Model: claude-fable-5-1 | Assigned: 2026-10-06 14:59:28 +07 | Status: active | Rationale: same Architect who signed off H3 and who asked for several of these follow-ups; owner: commit, merge and push are allowed if an Architect signs off

Read-only sign-off of the H3 follow-ups, UNCOMMITTED in
/Users/sto/workspace/datomworld/.claude/worktrees/head-h3-followups (a worktree of master
22669c33, which already has H3). Do not edit any file. The engineer's report is untrusted:
`collab/1791281512129-repl-engineer-head-h3-followups.claude-opus-5-5.stdout.log` in that
worktree. Changed (use `git diff`; new file `test/dao/space/store/fs_test.cljc`):
`src/cljc/yin/repl/dht.cljc`, `src/cljc/yin/repl/query.cljc`,
`src/cljc/dao/space/store/fs.cljc`, `test/yin/repl/dht_head_test.cljc`, and one design
sentence block I added (8.3's unverified list gains the lost-request bullet you asked for).

## What was done (judge each against your own follow-up notes)

1. All leading U+FEFF stripped after decoding (private `without-boms`), double and triple
   BOM tests, a BOM inside a keyword still refused.
2. `read-bounded`, `byte-length`, `decode-utf8` (and the private `not-regular`) MOVED
   unchanged into `dao.space.store.fs` beside `read-file-text`, public, with their own
   tests; `dht.cljc` calls `fs/...`; `fs.cljc` gained only `dart:convert`/`Uint8List`
   (Dart branch). Check design 5.10's "host seams, all existing" is now true, and that
   `fs` has no REPL dependency.
3. A refused-deposit test (a trace this process did not sign, high sequence number on the
   board; the HEAD move is refused `:yin.head/seq-regression`; reported once; the
   evaluation still answers; the next deposit lands). No code change.
4. `moved-lines` (now public) attributes each name to the principal(s) that moved it via a
   private `movers`: with two or more installs it re-folds the names with only that
   principal's head set back to its `before` value; if no single install explains the move
   all installs are named; nothing stored. The test uses a second publisher shell and calls
   `moved-lines` with both `:installed` events (it does not force one tick).
5. NOT done, rationale: the sequence is the greatest `t` over the index (`head.cljc` about
   150); the manifest has no `t` bound; `:max-t` exists only in the in-memory index state
   and threading it through the store's `:head-fn` contract is outside the files and not
   proven equal to the row maximum (followers recheck at `head.cljc` about 328). Left as is.
6. NOT done, rationale: the double-backoff sequence cannot happen: an attached dial never
   becomes `:lost` (`head/ws.cljc` about 337) and a lost source clears the follower's
   reader in the same poll (`head.cljc` about 578); the engineer wrote a test, found the
   delay was one step with and without a guard, and reverted both.
7. `repl.dht/linked-registry` is now public and takes a module registry; `query/
   session-modules` calls it for its linked half. The host module holds no rule.

## Questions to rule on

a. Items 5 and 6: do you agree they are correctly NOT done (is there a safe derivation the
   engineer missed for 5; is the 6 argument sound, including the case of a dial that is
   still `:resolving` when its source is lost)?
b. Item 4's "re-fold with that principal's head set back" derivation: sound and cheap
   enough, or an unwelcome second fold in the host (derive, don't persist; no rule in the
   host module)? Is `moved-lines` being public a problem?
c. Item 2: any behaviour difference in the moved code (messages, branches, the check-to-
   open limit comment), and is the new public surface of `dao.space.store.fs` acceptable
   for a seam that "below dao.stream is plumbing"?
d. The engineer suspects a BOM BETWEEN tokens (not leading) is whitespace on Node and
   refused on the JVM: a cross-host difference that predates this work. Blocker or a
   recorded follow-up?

## Output

Begin the final response exactly with:
Completed-GMT: <YYYY-MM-DD HH:MM:SS GMT>
Completed-Local: <YYYY-MM-DD HH:MM:SS local-timezone-name>
Coding-Agent: claude
Session-ID: d8d95e30-f2d2-443b-9aa9-36e7001a904e

First line after the header: "SIGNED OFF" or "NOT SIGNED OFF", then blockers as severity |
file:line | invariant | correction, then rulings a to d, then short notes. Do not sign off
what you could not read.
