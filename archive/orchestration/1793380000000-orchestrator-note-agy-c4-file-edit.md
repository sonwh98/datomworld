Created-GMT: 2026-10-16 11:00:00 GMT
Created-Local: 2026-10-16 18:00:00 +07
Coding-Agent: claude (orchestrator, semantic-register-vm track)
Session-ID: e77b6b18-7f5d-48ec-b8f4-0293f11ad5eb
# Note for the yang.python (agy) orchestrator: one more C4 test file edited under the freeze rule; srvm-phase8 is now c11f9299

Edited (C4 file, test only, no src/yang): test/yang/python/antlr/prelude_parity_test.cljc, in srvm-phase8 c11f9299 (merge of srvm-node-slow-c4-floats). The three py/float-mod rows with bare float literals 1.0E20, -1.0E20, 1.0E300 are now py/mod over {:py/float ...} payloads with {:py/float ...} expectations, and prelude-semantics-on-every-vm drops its float-mod special case. WHY: on Node, a bare integral Number above 2^53 is an integer in dao.jing.cbor and is refused ("unsafe integral JavaScript number") when a vector is content-addressed; since 8c every VM except the AST walker loads through the address-minting path, so the Node --slow lane failed prelude-semantics-on-every-vm-test on :semantic, :stack and :register. The production Python lowerer already wraps floats as float64 (no src change). If you rebased onto 9fab76c6 or 47249651, take this file from srvm-phase8 or re-apply the three rows.
Also new in srvm-phase8 since 9fab76c6: frame/rows NaN fixes (free-env and decode* compare by content), docs rename (yin.vm.semantic.md), ledger rows.
Gate record of srvm-phase8 on Node --slow (bb test:sub:cljs yang.python --slow, jupiter): 257 tests, only reds were dht-import-test (66, your fixture) and the 3 above (now fixed). No further code changes are planned before the merge to master; the freeze lifts when you have rebased.
