Created-GMT: 2026-11-03 01:20:00 GMT
Created-Local: 2026-11-03 08:20:00 +07 (Asia/Bangkok)
Coding-Agent: agy
Session-ID: pending (agy-generated; capture conversation_id)

# Task: BLE track review r6 — confirmation of the r5 fixes (reconstruction, second pass)

Role: Adversarial Code Reviewer and Security Auditor

Implementers:
- Model: gemini-3.8-flash-high | Assigned: 2026-11-03 08:20:00 +07 | Status: active | Rationale: family-independent review (implementer seat is GLM-family); claude is billing-walled and codex is out per routing-status 2026-11-03

Background: the BLE track was reconstructed into
/Users/sto/workspace/datomworld-ble2 (branch ble-track2) after worktree
loss. An independent review round (r5, codex gpt-6.1-sol) returned NOT
CONFIRMED with findings; ALL are now applied in that worktree. Your task:
confirm each fix firsthand with repository evidence, and flag anything the
fixes broke. Dispositions to check:

1. P1 startup sequencing (dart.cljd start-radio!): the async
   `start-peripheral!` is now `await`ed before its value is stored as the
   peripheral and before `[:radio-up identity]` fires. Additionally its
   internal catch no longer swallows a refusal: it rethrows, so
   start-radio!'s catch deposits bind-failed exactly once and no bound is
   ever deposited when either role fails (design 3's "no writer").
2. P1 TX-only peers (dart.cljd perform-send-frame!): a link with no RX
   characteristic now feeds `[:send-fail peer "no rx characteristic"]`
   instead of silently answering ok.
3. P2 subscriptions at bind (dart.cljd bind!): `adapter-events!` and
   `scan-events!` now return their subscription and both are registered
   in `!subs`; `stop-radio` cancels them. Cancellation futures get
   `.catchError` (sync throws stay under the try).
4. Documentation contradictions: links.cljc `close!` docstring now says
   "one closed event followed by a link-down for every live link"
   (matching the code's deposit order); mesh.cljc step-event!'s closed
   comment no longer claims "the same rule a gap gets below" -- it states
   the closed/gap distinction (closed clears, gap leaves stale).
5. Assertion-count delta the r5 reviewer flagged (51/203 vs lost tree's
   50/198): accepted as reconstruction noise of a from-context rewrite --
   the +1 test is the inbound-ceiling law (+3 assertions) and +2
   assertions live in existing tests. Recorded, not chased.

Known-accepted residuals (do not re-flag): the two kondo
`Uint8List.fromList` unresolved-symbol reports on cljd files (false
positive; same signature as the long-landed cbor/cljd.cljd); the sandbox
dart test cannot reach a plugin call (the design section 8 device-test
plan covers the real-radio legs).

Verification run by the orchestrator after these fixes: JVM 51 tests / 203
assertions / 0 failures; Dart lane (4 namespaces) 52/52; `cljd compile
dao.stream.ble.dart` clean; kondo clean except the two known false
positives; the Node full-suite rerun is in flight (previous Node run on
pre-fix code: 4312 tests / 152662 assertions / 0 failures).

Do not edit anything (read-only plan/sandbox). Cite repository evidence for
every confirm or dispute. One verdict line:
CONFIRMED | CONFIRMED WITH DEFECTS (list) | NOT CONFIRMED (list).

Begin the final response exactly with:
Completed-GMT: <YYYY-MM-DD HH:MM:SS GMT>
Completed-Local: <YYYY-MM-DD HH:MM:SS local-timezone-name>
