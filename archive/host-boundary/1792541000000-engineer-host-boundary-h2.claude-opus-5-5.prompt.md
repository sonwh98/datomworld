Created-GMT: 2026-10-21 03:50:00 GMT
Coding-Agent: claude
Session-ID: fresh

# Task: Implement Slice H2 (Consumer Migration and Deletion of Legacy Host Shims)
Role: Implementation Engineer
Model: claude-opus-5-5

Working Directory: /Users/sto/workspace/datomworld-host-h2
Branch: host-h2
Governing Document: /Users/sto/workspace/datomworld/docs/design/dao.stream.host-boundary.md (r10, §2.4, §3.1, §4.2 Table B, §8 H2, §8.1, §8.2)

Objectives per §8 H2:
1. Consumer Migration from `yin.vm.integer.host` to `dao.host.integer`:
   - `yin/vm.cljc:32`, `yin/vm/engine.cljc:38`, `yin/vm/values.cljc:30`, `yin/vm/data.cljc:41`, `yin/vm/integer.cljc:101`.
   - `test/yang/python/antlr/{int_heap,int_literal,int_ops}_test.cljc`.
   - Delete `yin/vm/integer/host.cljc`. Docstring in `src/cljc/dao/host/integer.cljc:14` reworded.
   - Pre-deletion and post-deletion gate per §8: closure tool reports `yin.vm.integer.host` required by no file.
2. Consumer Migration to `dao.host.{bytes,text,float,number}`:
   - `dao.jing.cbor` drops internal copies for `dao.host.*`.
   - `debruijn_code.cljc:232-242,315`, `debruijn.cljc:167,241,310-321,855-885`, `chunks`, `jing.dht`, `linker:1232`, `sign:55-66`, `datagram:63-66`.
   - `yin.vm.integer` float-text kernel delegates to `dao.host.float` and its private copy is deleted.
   - The eight H0 portability corrections (§3.1): `bytes->str`, PHP literals, index comparator, packet code-points, safe-integer checks, byte lengths.
3. Arrays Hardening (`dao.data.arrays`):
   - Stable sort (`asort`) and prevalidation before any write in `acopy` on JS and Dart (§2.4 behaviour change).
   - Add no-partial-write tests and overlap tests (Table D).
4. Gate Tool and Allowlist Updates:
   - Implement Host-Library Gate (§8.2) as library mode in `test/dao/host/gate_test.clj`.
   - Update `docs/design/host-boundary/closure-{clj,cljs,cljd}.edn` and list 1 in `gate_test.clj`: remove migrated shims, retire `profile-additions` entries in `gate_test.clj` as the closure reaches them.
5. Verification:
   - `bb test:sub:clj dao.host` and `bb test:sub:clj dao.stream` pass 0 errors.

Write your report to:
/Users/sto/workspace/datomworld/collab/1792541000000-engineer-host-boundary-h2.claude-opus-5-5.findings.md
