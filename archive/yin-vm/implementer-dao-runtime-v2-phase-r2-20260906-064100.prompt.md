Created-GMT: 2026-09-06 06:41:00 GMT
Created-Local: 2026-09-06 13:41:00 Asia/Ho_Chi_Minh
Coding-Agent: glm
Session-ID: pending (provider-generated)
# Task: Implement Phase R2 of dao.runtime
Role: VM Runtime
Implementers:
- Model: glm-5.3

Implement Phase R2 of `dao.runtime` (Host drivers). Read `docs/design/dao.runtime.implementation-plan.md` for the exact requirements.
Ensure you write the drivers for `clj`, `cljs`, and `cljd`, and their tests.
Note that the original drivers are in `src/clj/dao/runtime/driver.clj`, etc, and you are writing `src/clj/dao/runtime/driver.clj`, etc.
Run `bb test:clj` and `bb test:cljs` when done.
