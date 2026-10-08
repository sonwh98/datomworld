Created-GMT: 2026-10-06 10:46:41 GMT
Created-Local: 2026-10-06 17:46:41 +07
Coding-Agent: codex
Session-ID: 01a1103a-2d0e-7f82-b9a3-8f50d00edd21

# Task: reviewer-head-h3-followups

Role: Adversarial Code Reviewer and Security Auditor

Implementers:
- Model: gpt-6.1-sol | Assigned: 2026-10-06 14:59:28 +07 | Status: active | Rationale: same reviewer, resumed: these follow-ups close your own P3 (double BOM) and move code you reviewed

Read-only review of the H3 follow-ups, UNCOMMITTED in
/Users/sto/workspace/datomworld/.claude/worktrees/head-h3-followups (a worktree of master
22669c33, which already has H3 as a23809ea). Do not edit anything. The engineer's report is
untrusted: `collab/1791281512129-repl-engineer-head-h3-followups.claude-opus-5-5.stdout.log`
in that worktree. Changed (`git diff`; new `test/dao/space/store/fs_test.cljc`):
`src/cljc/yin/repl/dht.cljc`, `query.cljc`, `src/cljc/dao/space/store/fs.cljc`,
`test/yin/repl/dht_head_test.cljc`, and a design bullet (8.3).

Done: (1) all leading U+FEFF stripped after decoding on every host (your double-BOM P3);
(2) `read-bounded`, `byte-length`, `decode-utf8`, `not-regular` moved unchanged from
`dht.cljc` into `dao.space.store.fs`; (3) a refused-deposit test; (4) the moved line names
the principal that moved each name (`movers`, a re-fold with one principal's head reset);
(7) `linked-registry` made public and reused by `query/session-modules`. Not done, by the
engineer's argument: (5) the whole-index read per deposit (needs a new persisted `t` bound
or threading `:max-t` outside the files), (6) the double backoff (the sequence cannot
happen: an attached dial never becomes `:lost`, a lost source clears the follower's reader
in the same poll).

Verified by the orchestrator: kondo clean; 8 JVM namespaces 166 tests / 1714 assertions;
`yin.repl.dht-process-test` 3 tests / 131 assertions; the engineer ran `bb test:clj`
(3394 tests, 233184 assertions) and focused Dart runs; the full three-lane `bb test` is
running now. An Architect signs off separately (you may comment, do not adjudicate).

## What to attack

- **The move (2):** a byte-for-byte equivalence audit: same refusals and messages on every
  host branch, the 1 MiB bound exactly as before, the regular-file and symlink handling,
  the JVM/Node/Dart decode strictness, the accepted check-then-open comment; any caller
  still holding a private copy; new public surface in `fs` that could be misused; a
  ClojureDart compile risk in `fs.cljc` (the earlier failure was an invalid named-arg form
  in a Dart branch).
- **BOM (1):** triple and mixed BOMs, a BOM-only file, BOM then comment, BOM inside tokens
  (the engineer suspects Node treats a BOM BETWEEN tokens as whitespace and the JVM
  refuses it: reproduce on the JVM and say what Node does if you can run it), stripping
  after the size check, no way to defeat the single-form check.
- **Moved attribution (4):** is the re-fold correct when two principals move the same name
  to different addresses, when one install undoes another, when a principal installs twice
  in a tick, and when attribution is unknowable; does it stay bounded (cost with many
  followed principals, up to the cap of 64) and never throw; is `moved-lines` safe public?
- **Refused deposit (3):** is the new test vacuous or order-dependent (it puts a foreign
  trace on the real board and later restores the legitimate one)?
- **linked-registry (7):** behaviour identical, host module still holds no rule, no
  `:store`-keyed map literal introduced (the store-write audit).
- **The two NOT-done items:** do you agree with the engineer's arguments, or is there a
  cheap safe derivation (5) or a real double-backoff path (6, for example a dial still
  `:resolving` when its source is lost)?
- Any new P0 to P2 in the whole uncommitted change.

## Output

Begin the final response exactly with:
Completed-GMT: <YYYY-MM-DD HH:MM:SS GMT>
Completed-Local: <YYYY-MM-DD HH:MM:SS local-timezone-name>
Coding-Agent: codex
Session-ID: 01a1103a-2d0e-7f82-b9a3-8f50d00edd21

Findings as `P0-P3 | file:line | evidence | concrete fix` (reproduce what you can with
focused JVM evaluations), or "No actionable findings". End with one line: ready to commit
once the Node and Dart lanes pass, or the specific blockers.
