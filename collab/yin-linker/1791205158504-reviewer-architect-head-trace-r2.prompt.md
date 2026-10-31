Created-GMT: 2026-10-05 12:59:18 GMT
Created-Local: 2026-10-05 19:59:18 +07
Coding-Agent: codex
Session-ID: 01a10c19-e77f-7631-b9d5-f26df7a7ff2f

# Task: reviewer-architect-head-trace (round 2: confirm the revision)

Role: Adversarial Code Reviewer and Security Auditor

Implementers:
- Model: gpt-6.1-sol | Assigned: 2026-10-05 19:46:00 +07 | Status: active | Rationale: same reviewer, resumed to confirm its own findings were resolved

Read-only again. The Architect revised
`docs/design/yin.vm.linker.dht.head.md` (now 1439 lines; section "Revision 1"
near the end lists the changes) in
/Users/sto/workspace/datomworld/.claude/worktrees/architect-head-trace.
Do not edit anything. Treat the Architect's report
(`collab/1791204609893-architect-head-trace-r2.claude-fable-5-1.stdout.log`) as
untrusted and cite repository evidence and document lines.

## Your round-1 findings and the claimed resolutions (check each in the TEXT)

1. First contact rejects sequence zero: floor is now a sequence or `nil`; `nil`
   accepts any valid sequence including 0; tests added in H0 and H2.
2. Loaded candidates resolvable before installation: loads gain holders; the
   snapshot set is HEAD, explicitly held loads and installed heads, never a
   candidate. The Architect says its earlier "no change to `snapshots`" was
   wrong. (Check against `src/cljc/yin/vm/linker/dht.cljc` `snapshots` and
   `src/cljc/dao/space/dht.cljc` `loaded-indexes`.)
3. Board alias as logical identity: a name is lookup data only; a named
   `descriptor` request resolves it to the ring's real descriptor and the reader
   then attaches normally; H1 lists the `dao.stream.remote.md` amendments.
4. Poisoned floor: the floor is the installed sequence only; candidates are
   bounded scheduling state; a sequence mismatch is discarded and remembered; an
   unloadable candidate does not block a lower loadable one.
5. Crash window: order is confirm, persist, install; nothing resolves, reports or
   re-serves a head before its trace is durable; a failed write installs nothing
   and is retried.
6. Public UDP reflector: tables are served on loopback only through H4; a new
   slice H5 adds the return-path proof by reusing the DHT cookie and then lifts
   the restriction, starting with its own design note and review.
7. Shared loads: holders; the follower releases only its own; `forget` becomes
   release of the explicit holder.
8. Recovery: hydration leaves the reader path only; `--dht-recover` keeps it as an
   explicit key-requiring publisher act; H4 amends the remedy sentence in
   `docs/design/yin.vm.linker.dht.md` 6.5.

Also claimed: the derived sequence is proven for the REPL index path only and
enforced elsewhere (`deposit!` takes datoms, never a sequence); the poll-interval
guarantee is conditional on both datagrams being delivered.

## What to attack now

- Is each of the eight actually resolved in the design text, the tests and the
  slice completion criteria, or only asserted? Cite document lines.
- The four defects the Architect says it found in its own first draft (N1 a
  durable-less follower acting as a relay, N2 evict-oldest attachments under a
  spoofed-source flood, N3 the token printed on any socket, N4 `(reset)` in the
  shared-greatest-`t` cases): are the fixes sound?
- The new surface the revision creates: the `descriptor` request and its wire
  change to `dao.stream.remote`, load holders, `heads.edn` persistence ordering,
  the discarded-and-remembered candidate set (bounded?), `--dht-recover`, H5.
  Do any of them contradict an invariant (apply independent of rpc, no privileged
  node, clock-free linker, derive rather than persist, the DHT's "does nothing
  else" contract) or introduce a new P0 to P2?
- Slice order: can H0 to H5 still land independently, in order? Is H5's claim
  that it "amends the frozen DHT contract" consistent with dao.jing.dht.md?
- Scope: the document grew from 902 to 1439 lines. Is any of the growth
  unnecessary for the owner's goal, and is there a simpler sound core the owner
  could adopt first? Say plainly if so.

## Output

Begin the final response exactly with:
Completed-GMT: <YYYY-MM-DD HH:MM:SS GMT>
Completed-Local: <YYYY-MM-DD HH:MM:SS local-timezone-name>
Coding-Agent: codex
Session-ID: 01a10c19-e77f-7631-b9d5-f26df7a7ff2f

For each of the eight: resolved | partly | not resolved, with the document line.
Then new findings as `P0-P3 | file:line | evidence | concrete fix`, or "No
actionable findings". End with one line: ready to adopt as a design document, or
the specific blockers.
