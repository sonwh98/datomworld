Created-GMT: 2026-11-24
Coding-Agent: codex
Re: Assignment B remint amendment, b825db65 baseline

# Findings

Fresh `bb gen:antlr` completed (target/remint-amend/gen-antlr-retry.log). The first attempt failed on sandbox-denied network access; copied only cached upstream source inputs from the local datomworld-remint-final checkout into this worktree, then reran. The generator checked every input against the manifest digests and generated its outputs locally. No generated output was copied or shared.

The five float addresses, in order prelude root, program root, envelope root, derivation record, prelude subtree:

- `2af56b0e17cc8e1a94fcde9fb260b0130822f25781af09e79db35736005d9553` → `faef20ee7f8872f561ceff23d844c1cc194b00a05176f3e9a08a1de5788c6b24`
- `20cbbfee8cbf2a080267e2e86943756cb1c9bae6e7975cc6567ce1b4dedb7f79` → `918a28a60730e7fddd8b23f18b1c01c393f25b7822dc4e29750ad5132825e26f`
- `b4fe46fe6799d22b59aa4112dc09e78ad7ccae3204245e7d1752634ff607e161` → `5a6799c25743d6baddaee7135ef72d795004758fbc3cc3b2cba980e261e1a4c1`
- `7b8f1414bcbc782f72bb11252746a1063063b691133cf2a9512dfa496fb7f765` → `b19b60f3397d3b93bc247d5e0282f4f9e0ed40110eaf1acfa6d652aaa55d363f`
- `32ab99425d0a4664c25e04273066f4874f9e4a84a0243913943069c03f22e4a6` → `f5d7d13280f3e500451da67e812d757c77da694850d0584669110d11978c2057`

Existing linked manifest golden `segment/blake3-6e60da0c73eb89861bcdc4c5c53f59bdf5e8ae6ba88b5b09c65eab543497259e` holds. Independent publication using the profile registry and the same `yang.call` dependency produced exactly that address, so the stop condition was not met. The `py` module lock moved from `d7568c166dbcbf8ac0373bc2f1c38925c9a4f3e7c2a494a5103e164e3f9cd93a` to that full `6e60...` address; its three derivation and two index addresses also changed, as shown in the source diff.

The first REPL run exposed the dependent `py-v1` descriptor revision lock, which embeds the newly minted module closure. Re-derived both revision pins: descriptor `b5a8e78e8a3ce1adcabe96f08481cd936cf5911b6d4de22176f3a98f690aad49` → `168762efff394feab7a9859dcb9f4aaa4fd339424057a656a40b503fb149b45e`; runtime `9f64a4ea1eac5d48a9ba97d5e6a20b5b4458909ce5cfb57b3d515e3bd635f990` → `d91f4a0bb4032671e1acf8e31410368b741e18193e8227e69c3275238c4b9531`. Other revision is unchanged.

Two independent JVM derivations of all five float pins, the linked manifest, the profile publication and its derivation/index pins are byte-identical: both logs SHA-256 `9fc2e6763851042a3efff04414d80f678ff0841aa2e2758a5faf38099aae953b` (target/remint-amend/derive-{1,2}.log; script alongside). Two independent descriptor/runtime derivations are byte-identical: both logs SHA-256 `fc7055a4d747c43d8244172bd102844b935152a86c8ca9d9be85223f77798c07` (revision-{1,2}.log).

The two fresh JVM pin gates each passed 26 tests, 230 assertions, 0 failures, 0 errors (pins-gate-{1,2}.log). The initial REPL gate was interrupted after descriptor-address mismatch errors; after correcting the dependent locks, the focused `probe-buffers-across-lines-test` passed 1 test, 7 assertions, 0 failures, 0 errors. Full corrected REPL gate: INCOMPLETE. It was interrupted after more than 15 minutes without a test-runner summary or any failure output; see repl-gate-corrected.log. That r1 run did not establish a green result; the later r2 runs supersede it (see 1795539635-engineer-remint-r2.gpt-6.1-terra.findings.md).

The zipmap form in `test/yin/vm/data_test.cljc` remains intact. No commit made.

Custody correction (r3): commit d32c8179 mentions a REPL pass and dao.stream gate, but this r1 custody contains neither a completed full REPL pass nor a dao.stream run. The later r2 REPL logs document two passes; no dao.stream result is claimed here.
