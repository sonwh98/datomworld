Created-GMT: 2026-10-05 22:30:53 GMT
Created-Local: 2026-10-06 05:30:53 +07
Coding-Agent: claude
Session-ID: 820fb1cc-b82c-4ee6-96c7-7a170107243d

# Task: head-h2 (round 5: one Architect blocker)

Role: DaoStream and Network Engineer

Implementers:
- Model: claude-opus-5-5 | Assigned: 2026-10-06 04:05:48 +07 | Status: active | Rationale: same engineer, resumed to fix the single blocker of the Architect's implementation sign-off

The Architect (read-only sign-off, `collab/1791239192386-architect-head-link-h2-signoff.claude-fable-5-1.stdout.log`
in the head-link worktree; untrusted, verify) signed off everything in H2 except ONE
low-severity blocker:

`src/cljc/yin/vm/linker/head/ws.cljc` `loopback?` (about lines 83-93): `str/split`
with `#"\."` drops trailing empty strings, so `"127.0.0.1."` and `"127.0.0.1.."`
split to four parts and are accepted. A string with a trailing dot is not a literal; it
falls through to the host's name resolver, which is the reason `localhost` is refused
(head design 5.1 "Loopback only"; H2 bullet "A bind host that is not a loopback literal
composes no endpoint").

Fix, and NOTHING else: make the IPv4 branch refuse a host that ends in `.` (for
example split with a limit of -1, or an explicit check), portable CLJ, CLJS and CLJD
(check how `str/split` with a limit behaves on each and avoid anything host-specific).
Add `"127.0.0.1."` and `"127.0.0.1.."` (and, if cheap, `".127.0.0.1"`) to the refused
vector in `a-bind-host-that-is-not-a-loopback-literal-composes-no-endpoint` in
`test/yin/vm/linker/head_ws_test.cljc`, and confirm the existing accepted literals
(127.0.0.1, 127.1.2.3 and the IPv6 loopback forms) still pass. Files: only
`src/cljc/yin/vm/linker/head/ws.cljc` and `test/yin/vm/linker/head_ws_test.cljc`.

Process rules unchanged: no git, no formatter, no Node or Dart runs, no background
processes, no `clj -M:test -e`. Verify in the foreground with the same 21-namespace
combined run and `clj -M:kondo --lint` on the two files, with assertion counts.

Begin the final response exactly with:
Completed-GMT: <YYYY-MM-DD HH:MM:SS GMT>
Completed-Local: <YYYY-MM-DD HH:MM:SS local-timezone-name>
Coding-Agent: claude
Session-ID: 820fb1cc-b82c-4ee6-96c7-7a170107243d

Then report: the exact change, the tests that pin it (and that they fail without it),
and the commands and outcomes with assertion counts.
