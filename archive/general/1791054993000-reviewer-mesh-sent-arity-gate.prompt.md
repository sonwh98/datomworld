Created-GMT: 2026-10-03 19:16:33 GMT
Created-Local: 2026-10-04 02:16:33 +07 (+0700)
Coding-Agent: glm
Session-ID: 12d766dc-9807-4072-ae98-eec1806883cb

# Task: mesh/sent start arity (a Dart-lane quadratic-cost fix in test support), independent gate

Role: Adversarial Code Reviewer and Security Auditor

Implementers:
- Model: glm-5.3-flash | Assigned: 2026-10-04 02:17 +07 | Status: active | Rationale: routing-status 2026-10-03 (glm via CLI); different family from the Claude-family author; small static pass

Perform a read-only review of the UNCOMMITTED change in /Users/sto/workspace/datomworld-dart-dht (branch dart-dht-profile, master a4efc99a): `git diff` (2 files, +13/-11):
test/dao/jing/dht/mesh.cljc and test/dao/jing/dht_test.cljc.

Background: dao.jing.dht-test/unproven-chunks-never-amplify-or-allocate took about 62-72 s on Dart and 3.6 s on the JVM. Cause (measured by the engineer): the loop called
`(drop before (mesh/sent net))`, and `mesh/sent` decodes the WHOLE datagram log on every call, so the loop decoded about 547,000 datagrams to read about 1,046 new ones (quadratic; one decode is about
19x slower on Dart). Fix: `mesh/sent` gets a second arity `(sent net start)` that decodes only the log entries from index `start` onward, `(sent net)` = `(sent net 0)`, and the loop uses
`(mesh/sent net before)`. After the fix the test runs in about 1 s on Dart and the 8 namespaces that use mesh.cljc pass on the JVM (206 tests / 38,041 assertions / 0 failures); dao.jing.dht-test
passes on Dart (43 tests).

Check, with file:line evidence:
1. `(sent net)` is observably identical to the old one-argument function (same elements, same order, same keys; a vector in both cases). The new `(subvec (:log @net) start)` requires `:log` to be a
   vector: confirm every place that writes `:log` in mesh.cljc (and any test that resets/seeds it) keeps it a vector, so subvec cannot throw on a list, lazy seq or nil. What does `(sent net n)` do when
   `start` equals the log size (empty vector expected) or exceeds it (subvec throws IndexOutOfBounds: acceptable only if no caller can pass a larger index; `log-size` is the only caller's source)?
2. The loop change: `before` is `(mesh/log-size net)` taken before the step, so `(sent net before)` equals the old `(drop before (mesh/sent net))` element for element. `replies` is now a vector instead of a lazy seq:
   do `count`, `every?` and the other uses behave identically?
3. Every other caller of `mesh/sent` in the repo (grep test/ and src/ excluding test/cljd-out) still works unchanged with the one-argument arity.
4. Portability: the new code uses only `subvec`, `mapv` and existing helpers; it must behave the same on JVM, Node and Dart (`subvec` bounds behavior on ClojureDart, the vector type of `:log` on each host).
5. Nothing else changed: the assertions of the test are untouched, no wire format or codec behavior changed.

Do not edit. Do NOT run suites. Complete in one turn.

Begin the final response exactly with:
Completed-GMT: <YYYY-MM-DD HH:MM:SS GMT>
Completed-Local: <YYYY-MM-DD HH:MM:SS local-timezone-name>

Report actionable findings as:
P0-P3 | file:line | evidence | concrete fix
State "No actionable findings" when appropriate. End with an explicit ready-to-commit verdict.
