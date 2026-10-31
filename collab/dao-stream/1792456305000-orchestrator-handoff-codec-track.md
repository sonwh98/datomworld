Created-GMT: 2026-10-20 00:31:45 GMT
Created-Local: 2026-10-20 07:31:45 +07
Coding-Agent: glm (ZCode orchestrator seat, glm-5.3)
Session-ID: not-applicable (interactive seat)

# Task: Orchestrator seat handoff - dao.stream codec track (CBOR and Transit as peers, open to new encodings)

Role: Lead Engineering Orchestrator

Implementers:
- Model: <successor> | Assigned: <on pickup> | Status: active | Rationale: owner asked for an independent orchestrator on this track

Coordinate the dao.stream codec redesign in /Users/sto/workspace/datomworld. This track is INDEPENDENT of any other track; see "Do not touch" below.

## The owner's direction (verbatim quotes, kept apart from my paraphrase)

- "redesign the system so that cbor and transit are peer encoding. one can be swapped out for the other without breaking client callsites. In addition, the design should be open to allow future encodin like protocol buffers or apache arrow" (2026-10-20; typo "encodin" is the owner's)
- "the codec redesign should be independent of jolt design doc. an independent orchestrator should be able to work on it independent of this track" (2026-10-20)
- Discussion points from the same day (context, NOT decisions): CBOR's portable domain is a superset of Transit's (metadata, list/vector, byte strings); the repo has no backward-compatibility need (dev-only, clean breaks are the default); wire size and speed of the two codecs were NEVER measured (no benchmark exists in the repo; any efficiency claim is general knowledge, not evidence).

## Read first

- docs/agents/roles/orchestrator.md (your role: secretary; complex judgment goes to an Architect delegate or the owner), docs/agents/team.md, docs/agents/delegate-invocation-reference.md, docs/agents/build-n-test.md, docs/agents/routing-status.md (gitignored, never commit) and the tail of docs/orchestrator-log.md.
- docs/design/dao.stream.codec.md : the design. Status "design only; nothing commissioned". Slices C0-C4 plus owner-gated C5, test-impact table, reversal conditions, decision record.
- docs/design/datom.world.md (doctrine: dao.stream is the one boundary; implementations do not add contracts), docs/design/dao.stream.ws.md (codec profile text), docs/design/dao.data.md, docs/design/dao.stream.wasm.md and datom.world.abi.md (the wasm boundary must not grow a second codec ABI).
- collab/1792455434000-architect-stream-codec-design.claude-fable-5-1.findings.md (the architect's rationale, 1st round) and, when present, collab/1792456214000-architect-stream-codec-standalone.claude-fable-5-1.findings.md (the correction round that makes the doc stand alone).

Every claim here is what the outgoing seat believed at handoff time, not fact: re-derive from git log, git status, the real files before acting.

## State at handoff (2026-10-20 ~07:30 +07)

- Tree: master@212c45dd. docs/design/dao.stream.codec.md is UNTRACKED (571 lines when last read). Nothing about this track is committed, staged or commissioned. No src/ or test/ edits exist for it.
- A CORRECTION ROUND was in flight: claude-fable-5-1 was removing nine references to a different track from dao.stream.codec.md (grep -ci jolt must be 0), adding a "For a successor orchestrator" section and fixing a test-count error (the brief said 20 test files; a grep finds 22 namespaces). Check whether it finished: grep -ci jolt docs/design/dao.stream.codec.md, and read the findings file above. If it did not finish or the grep is not 0, re-dispatch the correction to the architect (resume the session below) before anything else.
- The design in one paragraph (verify against the doc): both codecs are already five-key profile maps. They are unequal because Transit holds three privileges: it is the hard-coded default in five places of dao.stream.ws (ws.cljc:44,75,92,557,647) and in the three host adapters (jvm.clj default-codecs, node.cljs, dart.cljd), it hosts the portable-domain predicates that dao.stream.cbor, dao.data and dao.jing.stream require, and dao.jing.stream names it. The design adds a codec-contract namespace (dao.stream.codec) holding the profile gate, key vocabulary and base domain; each codec requires only that; the transport has no default; the composition passes the profile as data; one base domain plus per-codec supersets with a tested law (every base value is accepted and round-trips by every codec); one capability (bytes?). Protobuf and Arrow are argued to fit the same stateless profile (a codec namespace is a function from parameters to a profile; schema identity goes into the negotiated name). The wire does not change; the profile never reaches the wasm import surface.
- Important fact found by the architect: the production composition dao.stream.remote-channel passes no codec at all, so the whole yin.repl network path rides on the Transit default today. Threading a codec from the channel spec through serve/connect is part of the work.

## Owner decisions still open (do not decide them; ask the owner)

1. Which codec profile yin.repl selects by default once no shared default exists. The architect recommends CBOR (the one profile every host speaks). Until the owner records it, Transit is passed explicitly so wire bytes stay identical.
2. Whether the 571-line ClojureDart Transit port stays once the default is CBOR.
3. Whether the :ws/* profile keys stay or move to the codec namespace.
4. Whether to retire Transit entirely (not costed; no benchmark exists). Raised in discussion; it is the owner's call and may need a measured size/speed comparison first.
Also owner-gated: commissioning each slice (nothing is commissioned), committing, and any merge.

## Delegates and routing

- Architect: claude-fable-5-1, resumable session 8c79f881-a441-4e88-9098-a7d3b7a75d13 (invoke as claude --model claude-fable-5-1 --resume <id> --permission-mode acceptEdits --allowed-tools "Read,Grep,Glob,Edit,Write,Bash(ls *),Bash(wc *),Bash(sed *)" -p ...; use the flat claude CLI, NOT claude-cloud, whose API credits were reported low; never plan mode, it traps headless runs). That session also carries another track's context; for independence prefer a FRESH Fable session for your own rounds, quoting the design doc.
- Sign-off: the design doc is Claude-authored, so a NON-Claude architect must sign it off before it is relied on: codex gpt-6-astra (codex exec resume rejects -s; always pass -m; reserved for architectural review under team.md; strict rate limits) or glm-5.3. Use a fresh thread, not the one that carries the other track.
- Implementation of C0-C4 (when the owner commissions): claude, codex or agy by team.md capability; the host adapters are cljs/cljd/clj so tri-host verification is mandatory (build-n-test.md gate ladder). Reviewers must be a different family from the implementer.
- Availability (owner relays, see routing-status.md, re-read it): claude, codex, glm available; agy/argon at 2%, do not use.

## Verification facts you will need

- dao.stream is a foundation subsystem: per build-n-test.md a change here makes G2 (bb test:changed) run nearly the full suite; plan on bb test:sub dao.stream, then the three lanes, and shiva/jupiter for the slow tier (see "Remote test servers"; push a feature branch, never rsync, never test in ~/workspace/datomworld on a server; jupiter's mise is broken in non-interactive ssh as of 2026-10-20, shiva works).
- Test impact: 22 test namespaces mention dao.stream.transit (the doc has the table). Each slice's acceptance is "unchanged lane test counts plus unchanged cross-host peer suites".
- Known Linux-only failure unrelated to this track: dao.stream.datagram.dart-test/refusals-and-lifecycle-events (build-n-test.md "Known differences").
- Worktree traps: run mise trust and npm ci in a fresh worktree; never run two lanes that both trigger gen:antlr in the same worktree; ClojureDart reader traps are in memory/cljd notes (reader conditionals need :cljd FIRST, duplicate protocol params, nil in chunked for).
- Commit convention: <type>(<scope>): summary per docs/agents/format.md, no Co-Authored-By. collab/ is never committed. Commit only after independent review sign-off.

## Do not touch

- docs/design/yin.repl-jolt-implementation.md, build/jolt-spike/, mise.toml, and the collab/ files of another orchestrator's lane: another seat owns them (it depends on this track only through "its J6 slice needs C0-C4 landed"). Do not edit its uncommitted work. mise.toml and docs/orchestrator-log.md are shared uncommitted files: append your own log entry, never rewrite others.
- Do not decide the open owner decisions above, widen scope to implement a codec before the owner commissions a slice, or stage/commit without explicit owner instruction.

## First steps

1. Re-derive state: git log/status; read the doc; check the correction round (above).
2. Report the four open owner decisions to the owner; ask which slice, if any, to commission and in which order (the architect's suggested first slice is C0; read its acceptance in the doc).
3. Send the standalone doc to a non-Claude architect for sign-off (fresh thread).
4. Only after sign-off and an explicit commission, brief an implementer using the role template for its role.

Begin your final response exactly with Completed-GMT / Completed-Local / Coding-Agent / Session-ID fields as in docs/agents/roles/orchestrator.md, and append your final handoff entry to docs/orchestrator-log.md.

## Addendum (2026-10-20 ~07:45 +07): correction round COMPLETE
The in-flight correction round finished and the outgoing seat verified it: grep -ci jolt docs/design/dao.stream.codec.md = 0 (was 9). The doc is now 631 lines and has two sections the earlier text lacked: section 10 "Open owner decisions" (OQ1 yin.repl default profile, OQ2 the cljd Transit port, OQ3 :ws/* keys vs the codec namespace) and section 11 "For a successor orchestrator" (what is decided, start with C0, exact bb commands per gate, slow tier once after C4, non-Claude sign-off rule). The doc cites 22 test namespaces (not 20). The other track's design doc now mentions this one only as a dependency (J6 needs C0-C4 landed). Rationale: collab/1792456214000-architect-stream-codec-standalone.claude-fable-5-1.findings.md. The sign-off by a non-Claude architect has NOT been requested; the owner had not said who should send it, so it is your first action (fresh thread, not one carrying the other track). Open owner decision 4 (retire Transit) is not in the doc's section 10; raise it with the owner.

## Addendum 2 (2026-10-20 ~07:50 +07): who orchestrates, and routing
Owner, verbatim: "argon has an independent pool just like fable. orchestration goes to agy using gemini-3.8-flash". The successor seat is agy running gemini-3.8-flash. Invoke per docs/agents/delegate-invocation-reference.md (agy: --model gemini-3.8-flash --effort <effort>, --mode plan --sandbox for review, accept-edits for authorized edits; capture conversation_id; no --print-timeout). A flash-tier seat is secretarial by design (orchestrator.md "Scope of judgment"): send every design or ambiguity question to an Architect delegate (fresh claude-fable-5-1 session, or a non-Claude architect for sign-off) or to the owner; do not adjudicate findings yourself. The 2% credit figure the owner mentioned belongs to the gemini-4-argon pool, which is separate; do not dispatch to argon.

## Addendum 3 (2026-10-20 ~07:55 +07): role-to-model routing (owner)
Owner, verbatim: "codex should be reserved for architectural review with astra. fable for designing and architecture planning. opus-5.5 for implementation, cmd for review". For this track: design/architecture rounds -> claude-fable-5-1 (fresh session, flat claude CLI); the sign-off of dao.stream.codec.md and of each slice's architecture -> codex gpt-6-astra (architectural review only); slices C0-C4 implementation -> claude-opus-5-5 (when the owner commissions a slice); slice review -> cmd (read-only/plan). Opus-authored code is reviewed by cmd, never by a Claude model; Fable-authored design is signed off by astra. This supersedes the routing paragraph above where they differ.
