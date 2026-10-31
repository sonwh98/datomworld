Created-GMT: 2026-10-05 13:47:45 GMT
Created-Local: 2026-10-05 20:47:45 +07
Coding-Agent: claude
Session-ID: c490a9e8-8546-47da-bdc1-73fb22f44275

# Task: architect-head-trace (round 4: close the round-3 review)

Role: Lead System Architect

Implementers:
- Model: claude-fable-5-1 | Assigned: 2026-10-05 19:33:59 +07 | Status: active | Rationale: same author, resumed to close the review of the simple-core rewrite

## Edit authorization (unchanged)

Edit exactly ONE file: `docs/design/yin.vm.linker.dht.head.md`. No other file, no
code, no tests, no git. Needed changes to other documents are recorded as
amendments inside this document.

## Where we are

Independent reviewer (gpt-6.1-sol) round 3:
`collab/1791207836775-reviewer-architect-head-trace-r3.gpt-6.1-sol.findings.md`
(read it in full; untrusted, check each citation). Three of the earlier findings
are resolved, two partly, and it found five new ones. It agrees with you that
cross-machine following is an extension, not a complete redesign, and that the
ws composition claims are supported. The owner has seen three rounds; THIS IS THE
LAST AUTOMATIC ROUND. Whatever cannot be closed here must come out as an explicit
owner question or a named blocker, not as another vague deferral.

## The five new findings and what to do

1. **P1 identity-contract conflict (the board name).** The reviewer says A1 cannot
   be deferred to H2: a fixed board name used as a mirror identity conflicts with
   `docs/design/dao.stream.md` lines 277-285 and `dao.stream.remote.md` lines
   159-163, `remote.cljc:509-519` reports the attached alias as the reflection's
   identity, and it affects following across a restart and across publishers
   sharing a principal BEFORE any relay exists. Settle it now. Choose and justify
   one: (a) restore the round-2 approach (a name is lookup data only, resolved to
   the ring's real descriptor, the reader then attaches normally; list the
   `dao.stream.remote` change and amendments), (b) a coherent contract amendment
   that makes a well-known name a legal logical identity with defined cursor
   semantics across restart, or (c) something simpler you can show sound, for
   example a dedicated single-entry board endpoint whose address is the identity.
   If your choice amends the governing identity contract, put the amendment in
   section 13 as an OWNER question with your recommendation instead of deciding it
   silently, and state the fallback if the owner refuses. Correct section 8's
   claim that this "blocks relaying, not following" if the reviewer is right.
2. **P1 shared candidate ownership.** Loads are keyed by address
   (`dht.cljc:1166-1170`); two principals can name one manifest, so one's
   replacement or rejection abandons or forgets the other's load. Cross-kind
   refusal does not stop a same-kind collision. Define a minimal owner set for
   candidate records (it need not restore the whole earlier holder design),
   release on replacement, rejection and installation, and the missing
   candidate-cleanup transition at installation that makes a later manual
   `load-index` succeed. Test: two principals sharing a manifest, replacement or
   rejection of one, manual load after installation.
3. **P1 host refusal translation.** `src/cljc/yin/repl/query.cljc:665-674` calls
   `load-index` and `load-module` without catching DHT refusals; the
   refusal-translation helper covers publication ops only (about 625-633), so the
   new cross-kind refusal would throw through the host interpreter. Put refusal
   translation for both host load operations into H1, returning the qualified
   refusal as stream data, with a test (a candidate load against a manual index
   load through the host module).
4. **P2 abandon and fetch-client cleanup.** `advance-loads` skips
   `content.step/step` when no loading record remains (`dht.cljc:1313-1321`) so
   abandoning the last load can leave client bookkeeping undrained; a full request
   ring yields an unsent request with no `get-ticks` deadline
   (`dao/jing/content/step.cljc:224-228`). "Nothing accumulates" needs more than
   deleting `:loads`. Specify retirement of abandoned client interests, handling of
   the retained unsent request, and draining with no active load, preserving
   requests other loads still need. Tests: abandon the last load before and after
   submission, a late reply, reloading the same address. Also finish the partly
   resolved round-2 items (#2 ownership and client cleanup, #3 same-kind sharing)
   by pointing at the sections above.
5. **P2 replay and load-frequency claims are overstated.** With floor 10, signed
   heads 11 and 12 stay eligible and a path attacker on a cleartext ws channel
   can alternate them with 13, repeatedly abandoning 13's unfinished load; only the
   last rejected id is retained, so "loaded once per process" (about line 762) is
   false. Disclose update starvation by eligible replay in the security account,
   bound the retained fetch work, qualify the once-per-process claim, add
   alternating-replay tests, and say that a stronger progress guarantee needs an
   authenticated transport or a reviewed scheduling policy (the signature gives
   integrity and rollback protection, not liveness).

Also apply the reviewer's small corrections: the mirror-call citation range should
extend through line 276, and note that two independent binds (TCP and UDP at one
port number) and differing advertised hosts or reverse-proxy paths are not solved
by the reserved `ws:<port>` suffix.

## Optional, only if it lowers risk

The reviewer notes much of the 1071 lines is rationale and future transport
planning. You may tighten prose where it is redundant, but you may NOT delete
anything the core needs (ownership, cleanup, identity, refusal translation). Do not
create a second file.

Begin the final response exactly with:
Completed-GMT: <YYYY-MM-DD HH:MM:SS GMT>
Completed-Local: <YYYY-MM-DD HH:MM:SS local-timezone-name>
Coding-Agent: claude
Session-ID: c490a9e8-8546-47da-bdc1-73fb22f44275

Then report: the new length; per finding accepted | partly | disputed with the
section changed and the resolution in one line; the identity decision (a, b or c)
and why; any new defect; and the owner questions as they now stand, marking any
that block adoption.
