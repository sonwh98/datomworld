Completed-GMT: 2026-09-10 05:19:51 GMT
Coding-Agent: codex
Session-ID: 01a0868e-e9f2-7242-92e8-58d63e7f9574
Role: Routine Review (Phase 2, r3 confirm) | Model: gpt-6-astra

I’ll check the timing gate’s placement and accepted values, then review the remaining failure paths and revised coverage wording. I won’t rerun the verified lanes.

**One blocking hole remains in the gate; the interruption fixes remain sound.**

1. **P1 — positive integers can still overflow after submission.** [validate-timing-options!](/Users/sto/workspace/datomworld/src/cljc/dao/jing/remote.cljc:443) accepts `Long/MAX_VALUE`. Used as `:request-timeout-ms`, it passes validation, then the deadline addition at [remote.cljc:560](/Users/sto/workspace/datomworld/src/cljc/dao/jing/remote.cljc:560) throws an arithmetic overflow **after `request!`**. The stored allocator remains stale, preserving the same id-reuse defect. The constructor’s deadline addition has the corresponding post-attachment failure. Oversized positive BigInts also pass validation but cannot safely become JVM sleep durations.

   Define and enforce a supported upper bound, and make deadline arithmetic safe for that domain. Add boundary cases proving rejected values cause no submission or attachment. This is an incomplete repair of the tenth path, rather than a distinct eleventh.

The gate’s **placement is correct**, including for options supplied through `assoc`. Describing it as an entry gate is also correct, but the documentation’s “argument defects throw before the wire” claim currently exceeds its numeric coverage. I cannot call the enumeration complete until that gap closes.

Your close-test wording is now honest. My suggested protocol-var wrapper was unsuitable. **Another mechanism exists without changing production code:** replace the ordinary `jvm/connect!` function in the test with a scripted raw socket whose `:close!` records calls. Leave the real `WsHandle` and protocol dispatch intact; a pending establishment followed by interruption must invoke that recorder. Static review remains a defensible choice, but “no mechanism exists” would be incorrect.

**Not ready to commit yet.**
