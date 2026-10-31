Coding-Agent: codex
Session-ID: 01a0e8c9-ba64-71d3-988e-fbacc14e5a82
Model: gpt-6-sol

Completed-GMT: 2026-09-28 16:12:41 GMT  
Completed-Local: 2026-09-28 23:12:41 Asia/Ho_Chi_Minh

**P1 | [yin.vm.cljc:2045](/Users/sto/workspace/datomworld/src/cljc/yin/vm.cljc:2045), [ffi.cljc:94](/Users/sto/workspace/datomworld/src/cljc/yin/vm/ffi.cljc:94) |** A fresh VM starts reading call-out at `:oldest`, mints its own call IDs from zero, and accepts a response by ID alone. The implementer reproduced a second VM accepting the first VM’s result. The exported endpoint does not enforce the stated one-caller-per-pair mitigation. **Fix:** ask the Architect to choose a correlation scheme that distinguishes VM calls across callers, or make an exclusive caller tenure an enforced part of the composition before calling remote FFI complete. Add a two-VM regression test.

**P2 | [holder.cljc:213](/Users/sto/workspace/datomworld/src/cljc/yin/vm/ffi/remote_serve/holder.cljc:213) |** A `lease-grants` cursor gap falls through to an unchanged holder state. Every later step retries the same gapped cursor, so the holder never observes its grant or reports that it was lost. **Fix:** make gap an explicit terminal loss outcome and test an evicted grant.

**P2 | [yin.vm.cljc:2045](/Users/sto/workspace/datomworld/src/cljc/yin/vm.cljc:2045), [responder_test.cljc:278](/Users/sto/workspace/datomworld/test/yin/vm/ffi/remote_serve/responder_test.cljc:278) |** VM construction needs a synchronous call-out cursor, while a reflection’s first cursor answer can be retry. Only the test helper performs the required pre-poll. **Fix:** provide and document a production readiness step that obtains and files the cursor before VM construction, or make VM construction handle retry explicitly.

**Q1–Q5:** Q1 is a blocking correctness defect requiring an Architect decision on the fix. Q2 needs a production guard. Q3’s generic malformed-envelope exception is terminal, but it obscures known request loss; use a specific portable loss result in the same VM error-path change. Q4’s separate responder and holder namespaces are sound: the responder interprets apply values while the binding serves stream operations. Q5’s handler authority, renewal authority, and deployment capacities and timings remain owner policy decisions; they need concrete deployment choices, but do not require a new architecture ruling before this code can be committed. Serving `lease-proposals` is outside this slice.

The supplied cross-platform test results pass, but they do not cover the fresh-VM correlation failure or an evicted grant.

Verdict: REQUEST CHANGES  
Sign-off: WITHHELD
