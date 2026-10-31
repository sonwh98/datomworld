Created-GMT: 2026-10-05 16:27:21 GMT
Created-Local: 2026-10-05 23:27:21 +07
Coding-Agent: claude
Session-ID: 86570e56-5476-480f-a673-0ec2af4564a5

# Task: architect-h1-rulings

Role: Lead System Architect

Implementers:
- Model: claude-fable-5-1 | Assigned: 2026-10-05 23:27:21 +07 | Status: active | Rationale: owner prefers fable for Architect rulings; three design questions the orchestrator may not decide

Perform a read-only architecture ruling on three questions the H1 engineer raised
while implementing slice H1 of `docs/design/yin.vm.linker.dht.head.md`. The tree is
/Users/sto/workspace/datomworld/.claude/worktrees/head-h1; the H1 implementation is
UNCOMMITTED in the working tree (`git diff` shows it; the engineer's report is
`collab/1791215841254-storage-engineer-head-h1.claude-opus-5-5.stdout.log`, untrusted
and to be checked). Do not edit any file.

## The three questions (each verified by the orchestrator to rest on real code)

1. **Section 5.5 contradicts itself.** Line ~380 says the follower "never forgets or
   abandons a record that is not of the candidate kind", but the Unloadable rule of
   5.5 and H1's "A failed load made by hand" criterion require the follower to
   forget a FAILED index-kind record at the candidate's address and restart it
   under the kind it had. The engineer implemented the more specific rule. Rule on
   the exact rewording of 5.5 that is both safe and consistent with the H1 test.
2. **Section 7 says "Following never makes the node `busy?`", but a loading
   candidate does.** `dao.space.dht/busy?` (`src/cljc/dao/space/dht.cljc` about line
   1461) is true while any record is `:loading`. Choose: (a) correct the sentence
   (a candidate load is work, so the node is busy while one loads, and say what
   that costs the tick cadence and `yin.repl`'s idle back-off); or (b) make `busy?`
   ignore loads of a kind the owner marks as background, without `dao.space.dht`
   learning anything about heads (kinds are opaque keywords). Decide, with the
   consequence for `main.cljc`'s cadence (`moved?`, the idle curve) and for any
   test that relies on `busy?`.
3. **`yin/repl/link.cljc` (about line 313) forgets any `:failed` record** when a
   `require` resolves to that address, including a follower's failed candidate
   record at the same address. The follower recovers by reloading but without waiting
   the retry delay. Decide whether this is acceptable, or whether the follower's
   candidate and a `require`-initiated module load of one address can collide in a
   way that violates 5.5's ownership rule (shared candidate records, last-owner
   release). Say whether a fix belongs in H1 (a named change to `link.cljc`), a later
   slice, or is outside the design.

A fourth, small point: the engineer also amended `docs/design/yin.vm.linker.dht.md`
section 4.3, which the design's H1 amendment list does not name, because its "left as
it is" rule had become false. Say whether that amendment is right.

Read first: docs/design/yin.vm.linker.dht.head.md (5.5, 5.6, 5.7, 7, 11 H1),
src/cljc/dao/space/dht.cljc, src/cljc/yin/repl/link.cljc,
src/cljc/yin/vm/linker/head.cljc, src/cljc/yin/repl/main.cljc (cadence, `moved?`),
docs/design/yin.vm.linker.dht.md (4.3, 7.2, 9).

Begin the final response exactly with:
Completed-GMT: <YYYY-MM-DD HH:MM:SS GMT>
Completed-Local: <YYYY-MM-DD HH:MM:SS local-timezone-name>
Coding-Agent: claude
Session-ID: 86570e56-5476-480f-a673-0ec2af4564a5

Then give each ruling in its own short paragraph (decision first), followed by:
severity | file:line | invariant/evidence | recommended correction, and the exact
replacement text for any sentence of the design that must change.
