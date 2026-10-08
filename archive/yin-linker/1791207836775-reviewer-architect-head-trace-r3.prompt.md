Created-GMT: 2026-10-05 13:43:56 GMT
Created-Local: 2026-10-05 20:43:56 +07
Coding-Agent: codex
Session-ID: 01a10c19-e77f-7631-b9d5-f26df7a7ff2f

# Task: reviewer-architect-head-trace (round 3: the simple-core rewrite)

Role: Adversarial Code Reviewer and Security Auditor

Implementers:
- Model: gpt-6.1-sol | Assigned: 2026-10-05 19:46:00 +07 | Status: active | Rationale: same reviewer, resumed to review the rewrite to the owner's chosen scope

Read-only. The owner chose your own suggested smaller first milestone ("option 1
for now, but cross-machine is necessary once option 1 is done. however,if the
abstraction is dao.stream, cross-machine would not require a complete redesign,
right?"). The Architect rewrote
`docs/design/yin.vm.linker.dht.head.md` (now 1071 lines; slices H0 to H3;
section 8 answers the owner's question; "Revision 2" near the end) in
/Users/sto/workspace/datomworld/.claude/worktrees/architect-head-trace. Do not
edit anything. The Architect's report is untrusted:
`collab/1791207350090-architect-head-trace-r3.claude-fable-5-1.stdout.log`.
Cite repository evidence and document lines.

## Claimed dispositions of your round-2 findings (check them in the TEXT)

1. Candidate eviction starvation: eliminated (one candidate slot, the latest
   observed trace takes it, even over a higher claimed sequence).
2. Unfinished load blocking newer heads: fixed (a replaced candidate's load is
   abandoned in the step the newer trace is observed; a new `abandon` function in
   `dao.space.dht`; no deadline added).
3. Failed shared-load retry: fixed in smaller form (load kinds no longer mix; a
   cross-kind load is refused instead of silently ignored, see
   `src/cljc/dao/space/dht.cljc` `load` around line 1166; a failed record at the
   candidate's address is forgotten and restarted under its kind).
4. H1 "tests unchanged" contradiction: eliminated (`forget` untouched).
5. H5 cookie versus UDP fragments: eliminated from the core (no UDP mirror is
   built; section 8.2 records the requirement for the deferred UDP design).
Round-1 finding 4 (poisoned floor), your "partly": claimed now resolved. On
recovery: hydration is not removed and `--dht-recover` is not added; `dht join`
simply stops hydrating and `--dht-manifest` keeps the existing remedy.

## What to attack

- **Latest-observed-wins.** The rule lets the newest observed trace take the one
  candidate slot even over a higher claimed sequence. Over a cleartext ws channel
  a path attacker can interleave replays of old validly signed traces. Does the
  floor-only-moves-on-install rule fully protect the reader, or can replays cause
  denial of updates (repeated abandon and restart, never installing)? What bounds
  the churn? Is this honestly disclosed, and is the single-source assumption real
  for the core?
- **`abandon`.** Is abandoning a load while one fetch is outstanding sound with
  "a fetch has no deadline" (liveness via `dao.lease`) and with the existing
  loads state (`:loads`, holders if any)? Leaks, double-release, a stale answer
  arriving after abandon and being applied to the wrong candidate?
- **Cross-kind refusal** (`load` today silently ignores a second load of an
  address recorded under another kind): is the proposed refusal compatible with
  existing callers and tests (`test/dao/space/dht_test.cljc`,
  `src/cljc/yin/repl/query.cljc` `load-index`/`load-module` host functions)?
- **Section 8, the owner's question.** Is "cross-machine does not need a
  redesign" supported by the text and the code, or is there a hidden redesign
  risk the Architect missed? Check the claims about `ws-project/make-acceptor`,
  `accept-step!` running `remote/mirror-step`, `dial*`, and that `yin.repl.serve`
  already serves a table over ws; check the unverified list is honestly labeled.
  Is the token assumption (TCP board at the same port number as the DHT UDP
  socket) realistic, and is the fallback `ws:<port>` part adequate?
- **N5.** `yin.repl` already serves fixed ring names (`yin.repl/requests`,
  `yin.repl/answers`, `src/cljc/yin/repl/connect.cljc:72-81`). The core follows
  that convention and defers a ruling to slice H2 (amendment A1). Does that
  contradict the logical-identity contract you cited in round 1 (`dao.stream.md`
  lines 277-285, `dao.stream.remote.md` 159-163)? Is "blocks relaying, not
  following" true?
- **Slices H0 to H3.** Independently landable in order, tests sufficient, no
  hidden dependency on a deferred item? First head at sequence 0, restart,
  `(reset)`, crash between persist and install?
- **Anything the cut removed that the core still needs**, and anything the
  document still carries that the core does not (is 1071 lines justified?).

## Output

Begin the final response exactly with:
Completed-GMT: <YYYY-MM-DD HH:MM:SS GMT>
Completed-Local: <YYYY-MM-DD HH:MM:SS local-timezone-name>
Coding-Agent: codex
Session-ID: 01a10c19-e77f-7631-b9d5-f26df7a7ff2f

For each of the five findings and round-1 finding 4: resolved | partly | not
resolved with the document line. Then new findings as
`P0-P3 | file:line | evidence | concrete fix`, or "No actionable findings".
Answer the owner's question yourself in two sentences, independently. End with
one line: ready to adopt as the design for the core, or the specific blockers.
