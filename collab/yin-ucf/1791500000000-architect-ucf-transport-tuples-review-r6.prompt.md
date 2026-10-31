Created-GMT: 2026-10-09 21:05:00 GMT
Created-Local: 2026-10-10 04:05:00 Asia/Bangkok

# Task: Round 6 -- bare-C class encoding and exact path construction

Role: Lead System Architect (continuing thread 01a121aa-4d3b-7bb1-90d7-eeb2b04e5d75)

Both round-5 findings taken. New HEAD is one commit on top of
`9d6c1cec`. Run `git diff HEAD~1 HEAD` and re-read §6 from disk.

1. CE now enumerates every identity-bearing position: `[:cell C]`
   values, the bare `cell-C` / `response-cell-C` slots of `:pending`
   rows, and the `:cells` census keys; all encode as `[:cell <label>]`,
   never the incoming id. The census is not expanded in CE(root) (the
   slot is the constant `[:cells]`) and census declaration occurrences
   contribute no positions, stated as consistent with §8 step 3's
   exclusion of census edges from reachability. Labels are CBOR byte
   strings and are embedded as byte strings wherever nested.
2. Path construction per slot category, one spelling each: fixed slot
   `[kind slot-index]`; ordered repeated entry `[kind i]` (frames as
   `[:task :frame i]`); unordered element `[kind :elem CE(e)]`; keyed
   pair `[kind :key CE(k)]` / `[kind :val CE(k)]` (structural keys as
   the datum); task boundary never crossed; the occurrence contributes
   no step; no source enumeration index for unordered containers. Search
   depth starts at 0; individualization label is the CBOR byte string of
   `[:ind depth old-label]`.

Check, read-only:
A. Is CE now total over the row table (every slot kind has exactly one
   encoding), and is renaming invariance now actually established, in
   particular for pending cell slots and the census?
B. Is the path grammar now a single spelling per occurrence, and does
   it compose correctly across nested rows (e.g. a cell inside a map
   value inside a closure's bindings inside a kframe env)?
C. Is "census declaration occurrences contribute no positions" the
   right choice, and consistent with the re-emission check (the census
   IS re-emitted and compared via the root id)?
D. Any remaining obstacle to calling the canonical byte procedure
   reproducible, and hence to step 12's universal safe-lowering claim?

Begin the final response exactly with:
Completed-GMT: <YYYY-MM-DD HH:MM:SS GMT>
Completed-Local: <YYYY-MM-DD HH:MM:SS Asia/Bangkok>

Then: severity | file:line | evidence | correction, for anything
remaining; the properties that passed; and end with one line:
VERDICT: READY_TO_MERGE | CHANGES_REQUESTED (merge publishes a Draft).
