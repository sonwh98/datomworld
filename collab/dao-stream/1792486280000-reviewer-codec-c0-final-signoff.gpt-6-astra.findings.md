Created-Local: 2026-10-20 +07
Coding-Agent: codex
Role: Code Reviewer
Worktree: /Users/sto/workspace/datomworld-codec-c0
Authority: /Users/sto/workspace/datomworld/docs/design/dao.stream.codec.md (§3.1, §3.2, §4.4, §7.1 C0), Architect Ruling R2 / CD7

Verdict: NOT SIGNED OFF.

R1, R3, and R4 are resolved. All requested tests pass. One explicit R2 documentation condition remains unmet; no runtime defect was found in the fixes.

- R1 — PASS. `codec.cljc` uses a nonempty RFC 7230 tchar regex and validates both present name keys before accepting either complete profile shape. Malformed modern, legacy, and dual profiles are rejected. In addition to the three-lane regression tests, 44 independent checks passed, including malformed legacy names in otherwise valid dual profiles, control characters, Unicode, nil, and nonstrings.
- R2 — INCOMPLETE. `codec_test.cljc:93–101` explicitly cites Architect Ruling R2 / Decision CD7 and tests lists in D on CBOR. However, it does not cite `test/fixtures/codec/transit/exceptions.edn` or explain that the fixture test supplies the frozen Transit outcomes. Governing §4.4, “Baseline exception paths,” item 4, and ruling item 4 expressly condition this CBOR-only approach on naming the exclusion and citing `exceptions.edn`. Add that reference and delegation statement. The remaining “until the Transit owner decides” wording is stale; correcting that wording alone is nonblocking per the architect’s note. The design resolution itself is accepted: keep lists in D and leave Transit unchanged.
- R3 — PASS. The rejection corpus includes `(symbol "" "n")` and record-bearing symbol metadata as map key, map value, and nested symbol metadata. All three profile-asymmetry cases are asserted, including host-byte recognition and rejection by D. They pass on every lane.
- R4 — PASS. The literal `grep -rn "dao.stream.transit" src/` hits only the permitted Transit implementation, port, WebSocket files, and `jing/stream.cljc`. CBOR’s docstring is clean.

Independent verification: `bb test:sub dao.stream` exited 0. CLJ: 35 namespaces, 452 tests, 3,552 assertions; CLJS: 35 namespaces, 450 tests, 3,557 assertions; CLJD: 33 namespaces, 428 tests. Zero failures/errors. All seven `codec-fixtures-test` tests ran and passed on all three lanes (100%). Frozen fixtures and their test file are unchanged. Evidence: `target/profile/sub-{clj,cljs,cljd}.log`.

Sign-off statement: runtime verification is complete, but final acceptance remains pending the explicit R2 fixture citation. Commit recommendation: HOLD final C0 acceptance until that comment is corrected and inspected; retain the passing test evidence, then run required landing checks before merge. No code files edited by this review.
