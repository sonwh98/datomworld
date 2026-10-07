Created-GMT: 2026-09-06 08:44:00 GMT
Created-Local: 2026-09-06 15:44:00 Asia/Ho_Chi_Minh
Coding-Agent: glm
Session-ID: pending (provider-generated)
# Task: Port dao.await to v2
Role: VM Runtime
Implementers:
- Model: glm-5.3

Goal: Port `src/cljc/dao/await.cljc` to `src/cljc/dao/await.cljc` (or `dao.await` namespace) so it runs entirely on `yin.vm` and `dao.stream`. 
Also port its tests (`test/dao/await_test.cljc`) to `test/dao/await_test.cljc`.
Rules:
- It must depend on NO v1 namespace (no `yin.vm`, no `dao.stream`, no `yin.vm.engine`, etc.).
- Ensure you adhere strictly to the "coexistence" rule: create NEW v2 namespaces and do not delete or touch the v1 namespaces.
- Run tests to verify the port.
