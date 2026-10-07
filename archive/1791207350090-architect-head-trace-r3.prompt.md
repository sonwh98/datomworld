Created-GMT: 2026-10-05 13:35:50 GMT
Created-Local: 2026-10-05 20:35:50 +07
Coding-Agent: claude
Session-ID: c490a9e8-8546-47da-bdc1-73fb22f44275

# Task: architect-head-trace (round 3: rewrite around the simple core)

Role: Lead System Architect

Implementers:
- Model: claude-fable-5-1 | Assigned: 2026-10-05 19:33:59 +07 | Status: active | Rationale: same author, resumed to rewrite the design to the owner's chosen scope

## Edit authorization (unchanged scope)

You may edit exactly ONE file: `docs/design/yin.vm.linker.dht.head.md`. No other
file, no code, no tests, no git. A needed change to another design document is
recorded as an amendment inside this document.

## The owner's decision (quote, kept apart from the orchestrator's reading)

The orchestrator asked whether to cut the design to a simple core or fix the full
design. Owner, verbatim: "option 1 for now, but cross-machine is necessary once
option 1 is done. however,if the abstraction is dao.stream, cross-machine would
not require a complete redesign, right?"

Option 1, as the orchestrator offered it (a paraphrase): design around one
configured publisher source per principal, one candidate load at a time with
latest-wins, durable installed-head persistence, and loopback REPL following.
Relaying, multi-source scheduling and the H5 public-serving hardening move to
"deferred", each as its own later design.

## What to do

Rewrite the document around that core. It should become substantially SHORTER
than the 1439-line revision: remove the machinery the core does not need instead
of patching it. Keep what the core still needs and what round 1 and 2 proved:
the signed trace with its domain separation, the explicit nil floor (a first head
at sequence 0 must be accepted), the pure source-independent acceptance rule, the
confirm-persist-install order with failure handling, a snapshot set that never
contains an unaccepted candidate, loopback-only serving, `heads.edn`, and the
publisher recovery path if the core still removes hydration (decide whether
`--dht-recover` belongs in the core or is deferred with an interim documented
remedy; say which and why).

## The owner's question is a design requirement

"If the abstraction is dao.stream, cross-machine would not require a complete
redesign, right?" The document must ANSWER it, with evidence, in a dedicated
section. Specifically:

1. State which parts of the core are transport-agnostic and must stay so
   (the trace, its signature, the acceptance rule, persistence, reader
   semantics), and design the core so a second transport, or a second source,
   changes only plumbing (more cursors, a different exposure), not semantics.
   Make the pure acceptance rule's source-independence explicit so a single
   source is the degenerate case of many.
2. List, for relaying, multi-source scheduling and cross-machine serving, exactly
   what each would ADD and exactly which documents and code each would AMEND.
   If any of them would force a change to the core's trace, rule or persistence,
   say so plainly; that is the "complete redesign" risk and the owner wants it
   found now.
3. Evaluate the first cross-machine transport. The round-2 design chose UDP on
   the node's DHT socket, which created the reflection problem (H5). A
   connection-oriented transport (`dao.stream.ws`, the transport `yin.repl`'s
   `--port` already serves) has an inherent return path and no spoofable-source
   amplification. Compare ws against UDP-plus-H5 for the first cross-machine
   step: reflection exposure, NAT traversal (the owner's P2P and no-privileged-
   node invariant), whether the head ring can be served over ws with existing
   code, what the join token must carry for each. Recommend one, say what is
   unverified, and keep the other as a deferred alternative. Do not assume ws
   serves an arbitrary ring today: check `src/cljc/dao/stream/ws*.cljc`,
   `remote.cljc` and `ws_project.cljc` and report what is actually there.
4. State the one assumption cross-machine adds that loopback hid: locality is no
   longer a trust boundary. Show that nothing in the core relies on it.

## Findings that still apply to the core

The round-2 review (collab/1791205158504-reviewer-architect-head-trace-r2.gpt-6.1-sol.findings.md)
left five findings. For EACH say: eliminated by removing the machinery (and how),
or still applicable to the core and fixed in the text, tests and slice criteria:

- P1 candidate eviction starvation; P1 an unfinished load blocking newer heads
  (the fetch has no deadline, so a newer head must not wait on an older load;
  say how latest-wins detaches the old one without a deadline and without
  cleanup leaks); P2 retry of a failed shared load; P2 H1's "existing tests pass
  unchanged" is contradicted by `test/dao/space/dht_test.cljc` (the test
  `forget-clears-a-terminal-record-and-is-refused-while-loading` requires
  `forget` during a load to throw `::dht/loading`; the orchestrator verified
  it); P2 H5's cookie cannot gate UDP fragments (`src/cljc/dao/stream/udp.cljc`
  fragment envelopes carry no cookie; verified). Also finding #4 was only
  partly resolved: re-check it against the simplified rule.

## Keep

The 80-column ASCII style, the section structure where it still fits, a short
"Revision 2" note at the end, the questions only the owner can answer (re-derive
them: relay and H5 questions are now deferred decisions, not open questions, so
say what remains), and implementation slices that are independently landable with
their tests and completion criteria. Do not silently change decisions that none of
the above touches. If you find a NEW defect while rewriting, say so.

Begin the final response exactly with:
Completed-GMT: <YYYY-MM-DD HH:MM:SS GMT>
Completed-Local: <YYYY-MM-DD HH:MM:SS local-timezone-name>
Coding-Agent: claude
Session-ID: c490a9e8-8546-47da-bdc1-73fb22f44275

Then report: the new length; the answer to the owner's question in three
sentences; the ws versus UDP recommendation and what you verified; the
disposition of each of the five findings; any new defect; the owner questions as
they now stand.
