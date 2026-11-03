Created-GMT: 2026-11-03 08:00:00 GMT
Created-Local: 2026-11-03 15:00:00 +07 (Asia/Bangkok)
Coding-Agent: claude
Session-ID: 4ff69b4a-1e08-43cc-bf55-fb1ca8336a5a

# Task: BLE track review r5 — reconstruction fidelity after worktree loss

Role: Adversarial Code Reviewer and Security Auditor

Implementers:
- Model: claude-sonnet-5-5 | Assigned: 2026-11-03 15:00:00 +07 | Status: active | Rationale: same reviewer, resumed session; you know the final state of round 4 better than anyone

Context: the reviewed-and-CONFIRMED worktree (r4 state) was lost from disk —
never committed, worktree deleted. It has been reconstructed in a NEW
worktree, `/Users/sto/workspace/datomworld-ble2`, branch `ble-track2`, based
on current master `ab3839b5`, with the rescued shared-file patch
(`archive/worktree-rescue/ble-track-uncommitted.patch`) applied. The source
files were re-authored to the r4-confirmed state from the implementer seat's
record: all of your r1 findings (16), r2 defects (2), r3 defect (1) are
re-applied, and the design doc carries every r1-r4 documentation edit.

Your task: verify the reconstruction against the state you CONFIRMED in r4.
Specifically check, with repository evidence:

1. The 16 r1 dispositions and the r2/r3 defect fixes are all present in
   `/Users/sto/workspace/datomworld-ble2` (same list as r3's prompt, plus:
   gap branch adopts the recovery cursor and continues with a stale-but-
   usable neighbor set; closed clears it; ttl clamp `(min wire configured)`;
   seq spent when at least one link accepted, spend remembered; eager
   fan-out answering {:outcomes [...] :first-refusal r}; no-op link-down for
   an unknown peer; :connecting accounting with pending dials counted and
   gated; inbound links exempt from the ceiling; close! deposits link-down
   per live link; prune-announced (no bang); sendData on tx-uuid; adapter
   state filter excluding unknown/turningOn; watcher + onValueReceived
   registered in !subs; no-tx path releases the device and feeds
   link-failed; the dart radio-up feed after both roles answer).
2. Any drift the reconstruction introduced that r4's confirmed state did
   not have — extra behavior, missing behavior, changed docstrings that
   contradict code. Flag it; do not assume the account above is complete.
3. The one intentional delta you will find: links_test now carries
   `an-inbound-link-always-deposits-whatever-the-ceiling` (3 links with
   :max-links 1, inbound exempt), a law your r2 finding demanded be
   decided and documented; it was implemented but had no dedicated test
   in the lost tree. Judge whether it states the law correctly.

Verification already run by the orchestrator on the reconstruction: JVM
51 tests / 203 assertions / 0 failures; Dart lane (4 namespaces) 52/52;
seam `cljd compile dao.stream.ble.dart` clean; kondo clean except the two
known Uint8List.fromList cljd false positives; Node full-suite rerun in
flight. Note the counts differ from the lost tree (50/198) exactly by the
new ceiling test (+1 test) and its assertions — if you find other deltas,
that is drift.

Do not edit anything. One verdict line:
CONFIRMED | CONFIRMED WITH DEFECTS (list) | NOT CONFIRMED (list).

Begin the final response exactly with:
Completed-GMT: <YYYY-MM-DD HH:MM:SS GMT>
Completed-Local: <YYYY-MM-DD HH:MM:SS local-timezone-name>
