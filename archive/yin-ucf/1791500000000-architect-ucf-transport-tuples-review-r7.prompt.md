Created-GMT: 2026-10-09 22:20:00 GMT
Created-Local: 2026-10-10 05:20:00 Asia/Bangkok

# Task: Round 7 -- row digest follows code addresses (owner ruling), reversing the SHA-256 pin

Role: Lead System Architect (continuing thread 01a121aa-4d3b-7bb1-90d7-eeb2b04e5d75)

After your round-6 READY_TO_MERGE the draft was merged to master
(4040fce9). The owner then asked, verbatim:

> "why are we using sha256? dao.jing uses something else that's faster"

and the orchestrator confirmed in code: `dao.jing/default-hash-algorithm`
is `:blake3`; `ucf/code-address` is one-argument `(jing/segment-key v)`,
so A is a BLAKE3 address today; only the de Bruijn H pins SHA-256
explicitly, and H is becoming a derived checksum under the owner's
2026-10-10 ruling "A is the only code identity". The SHA-256 pin in §3
was the orchestrator's error (following stale `:segment/sha256-…`
spellings in UCF prose and H's precedent), and your round-4 finding 6
corrected the spelling but not the choice.

One commit on top of 4040fce9 changes §3, §8 step 4 and §10: row ids
are `(jing/segment-key body)`, the same call and digest as A; uniqueness
comes from the carrier version (version 3 = Jing's default at version
3; a default change is a carrier version bump, as for A); addresses are
self-describing and verified under the algorithm they name; an id under
any other algorithm is `:yin.k/undecodable`. Run `git diff HEAD~1 HEAD`.

Check, read-only:
A. Does binding the digest to the carrier version (rather than naming an
   algorithm) still give the uniqueness a canonical address scheme
   needs, given that A already works this way?
B. Any interaction with §6 (leaf key is root BODY bytes, not a digest,
   so the numbering is algorithm-independent -- confirm) or with the
   re-emission check in §6/§8 step 6?
C. Anything else in the document that still assumes SHA-256.

Begin the final response exactly with:
Completed-GMT: <YYYY-MM-DD HH:MM:SS GMT>
Completed-Local: <YYYY-MM-DD HH:MM:SS Asia/Bangkok>

Then findings if any, and end with one line:
VERDICT: READY_TO_MERGE | CHANGES_REQUESTED.
