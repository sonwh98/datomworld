Created-GMT: 2026-10-19 23:54:19 GMT
Created-Local: 2026-10-20 06:54:19 +07
Coding-Agent: claude
Session-ID: 8c79f881-a441-4e88-9098-a7d3b7a75d13
# Findings: architect rulings on the four open jolt design points
Role: Architect
Implementers:
- Model: claude-fable-5-1 | Assigned: 2026-10-20 06:54:19 +07 | Status: done | Rationale: Fable is the preferred Architect again; these are architect rulings the document deliberately left open

Document edited in place: `docs/design/yin.repl-jolt-implementation.md`. §13.4 is now "Architect rulings (owner may overrule)". Nothing staged or committed; no plan mode.

## The four rulings

1. **Transit off jolt: narrowed option (c) plus a stub-free (a).** The portable-domain predicates (`safe-number?`, `portable-value?`, `validate-portable`, `valid-descriptor?`) move out of `dao.stream.transit` into a shared namespace (proposed `dao.stream.portable`); `dao.data`, `dao.stream.cbor` and `dao.jing.stream` require that instead. `dao.stream.transit` gets a `:jolt`-first spliced require arm that requires nothing; on jolt `encode`/`decode` hit the existing throwing `:default` arms and `profile` is `nil`, so every `dao.stream.ws` Transit default refuses at composition through existing throws. `dao.stream.ws` is not edited. Decisive fact: option (b) as written is impossible, because `dao.stream.cbor` (`cbor.cljc:20,64`) and `dao.jing.stream` (`:26,37-39`) also require `dao.stream.transit`. Reversal: owner reverses "no Transit port" (the empty arm becomes the port's require), or jolt's reader cannot splice `#?@` in `ns` (fallback: a one-form `.jolt` namespace, same tests). Flagged for the owner, not decided: dropping the Transit default from `dao.stream.ws` on all lanes (full option c).
2. **Substrate: `jolt.socket.native` + `jolt.io-poller` directly, no `jolt.socket` shim.** Decisive fact (V): the shim's write path loops over `c-send` behind `poller/wait-ready fd :write` (`socket.clj:349,719-724`), i.e. a waiting `append!`, which the contract forbids; plus the documented recv-error-as-EOF divergence. J0b already ran this substrate for UDP (S). Falsifiable: J6 closure excludes `jolt.socket`; `send!` never waits; recv error never becomes a clean close. Reversal: J6 evidence of a poller capability gap for a non-fiber driver that only the shim fills.
3. **1009/1006: assert the sender's declared wire behaviour; jolt sends 1009.** The shared test already asserts only local observables (`ws_test.cljc:396-415`); the wire code is a seam property `dao.stream.ws.md` documents per host. New §9.6 matrix: jolt→any = 1009 close frame; JVM client→jolt = no close frame, jolt reads EOF/reset as 1006 and deposits one `:ws/closed`; browser→jolt = 1000 + reason (recorded, gated); Node/Dart/http-kit→jolt = R, filled from recorded seam behaviour at J6. Reversal: a repaired JVM client seam changes its row only.
4. **Pin: keep `96d7b866`; the first upgrade-gate run moves to a release tag.** Verified read-only in the checkout: untagged main head (2026-10-19), `git describe` = `vnightly-16-g96d7b866`, nearest release tag `v0.8.23` (a0ac71c3, 2026-10-17). All S and J0b evidence describes it; re-pinning now discards that for a two-day-older tag with no needed fix. Q5's tag preference is sequenced, not set aside. Reversal: a J0a/J1 blocker fixed only in a later tag, or an owner instruction that pins must be tags.

## What I changed in the document

Change note under Status; §1, §3 (ws:// owner-settled); §2; §4.2, §4.4 (Transit absent, not "unexercised"); §5 Sockets row; §6.4 pin facts; §9.2 last row; §9.3; §9.4 rewritten (mechanism, slice consequences); §9.6 bullets plus the frame-too-large matrix; §10.1 J0a/J2/J6 rows; §12; §13.1 two owner rows (licensing, wss); §13.3 emptied; §13.4 rulings; Appendix A W1/W2/W3/W7; Appendix B "Reopened 2026-10-20". Settled owner decisions, §13.2 and the owner's words untouched.

## Not verified

- Nothing built, loaded or tested; no jolt code ran. `#?@` splicing in an `ns` form under jolt's reader is R.
- That `dao.stream.transit` with an empty arm compiles under jolt (`finite-number?`'s `:clj` arm names `java.lang.*`; a `:jolt` arm is needed there anyway for `dao.stream.cbor`).
- Node, Dart and http-kit frame-too-large wire codes come from docstrings, not observation.
- The checkout's tag list is what it fetched; a newer upstream tag may exist.
- Owner's licensing/wss wording was taken from the brief.
