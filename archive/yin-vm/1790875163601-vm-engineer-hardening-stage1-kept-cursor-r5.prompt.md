Created-GMT: 2026-10-02 15:42:00 GMT
Created-Local: 2026-10-02 22:42:00 +0700
Coding-Agent: glm-5.3 (CLI)
Session-ID: 4b857b1a-a63f-4d24-9408-db1bcda00bc4

# Task: Linker Hardening Stage 1 — Round 5 (Architect Gate Review Fixes)

Role: VM Runtime Engineer

Read `STAGE1-R5-BRIEF.md` in the current working directory `/Users/sto/workspace/datomworld-linker-hardening`.
The Architect review gate returned REQUEST CHANGES with 4 blocking P1 findings:
1. P1: `referenced-cells` omits wait-frame registers and envelopes.
2. P1: stream markers across complete reachable graph.
3. P1: child install validation and refusal propagation.
4. P1: frame and register validation (field types, bounds, safepoints, blocked non-empty).

Implement these 4 fixes in `src/cljc/yin/vm/ucf/handoff.cljc`, add test coverage in `test/yin/vm/ucf/handoff_test.cljc`, verify `bb test:clj` and `bb test:cljs` pass cleanly with 0/0 kondo and clean cljstyle.
Do not commit or stage. Produce clean deliverables and state completion clearly.
