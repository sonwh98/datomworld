Created-GMT: 2026-09-27 19:35:00 GMT
Created-Local: 2026-09-28 02:35:00 +0700
Coding-Agent: codex
Session-ID: pending

# Task: Slice 7 Gate — pair channel, meeting, relay with leases

Role: Lead System Architect (review + sign-off)

Slice 7 of the accepted dao.stream.remote implementation is in the
uncommitted working tree of /Users/sto/workspace/datomworld as NEW
FILES (implemented by a claude CLI session; find them:
src/cljc/dao/stream/remote_pair.cljc, remote_meet.cljc or the names
the implementer chose, plus their test files -- look for pair/meet in
src/cljc/dao/stream/ and test/dao/stream/). Implementer trail:
collab/1790497441896-vm-engineer-dao-stream-remote-slice7.r2.claude.stdout.log
(minimal -- the session ended waiting on a background Dart run; the
tree is the record).

The contract: docs/design/dao.stream.remote.md section 3.3 (the pair
channel: a gap on the pair's in ends that link with append-unknown;
attach on the same pair descriptor resumes), section 4 (meeting and
relay conventions: the meet-requests entry, the pair bound and
refusing gate, lease-governed pair lifetime, relay-as-convention --
M is a peer whose table contains pairs, not a role), sections 2.4-2.5
(reflection semantics, channel loss, resend-after), and
docs/design/dao.lease.md (pair leases: served entries and relay pairs
as lease subjects). Plan slice-7 row and its proof matrix: restricted
NAT punch, symmetric-NAT relay, lease reclaim as not-found, reconnect
within tolerance, meeting-gate refusal past the bound, the 3.3
pair-gap rule.

Adversarial focus:
1. The pair channel: gap-to-loss semantics per 3.3 exactly; resume on
   the same pair descriptor; append-unknown reporting.
2. The meeting convention: the pair bound refusing gate (present
   refusal, not silence); lease-governed lifetime (reclaim observed
   as not-found/gone); reconnect within tolerance finds the pair
   intact.
3. The proof matrix: restricted-NAT punch and symmetric-NAT relay
   (simulated -- check the simulations are honest: restricted allows
   outbound-only; symmetric forces relay), not just happy paths.
4. No privileged node: M is a peer whose table contains pairs; no
   server/client roles in the modules.
5. Hygiene on all added lines; the tests assert real sequences
   (no weakened assertions).

Orchestrator evidence (do not rerun suites): the union tree with all
in-flight slices present is GREEN on all three lanes: JVM
2,266/183,166/0 (pair/meet tests included in the count), Node
2,173/49,777/0, Dart 2,134 passed.

Do not edit files. Cite file:line evidence.

Begin the final response exactly with:
Completed-GMT: <YYYY-MM-DD HH:MM:SS GMT>
Completed-Local: <YYYY-MM-DD HH:MM:SS local-timezone-name>

Report findings as:
P0-P3 | file:line | evidence | concrete fix

End with exactly two lines:
Verdict: READY
Sign-off: GRANTED
or
Verdict: REQUEST CHANGES
Sign-off: DENIED
