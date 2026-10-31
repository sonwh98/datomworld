# Architect sign-off: linker over DHT, slice L0 (publisher, Ed25519 signing, local runtime)

Role: Architect (read-only). You wrote the contract, docs/design/yin.vm.linker.dht.md, so judge L0 against §5.2, §5.3, §6 and §12 L0.
- Worktree: /Users/sto/workspace/datomworld-linker-l0, branch linker-l0, from linker-dht a1f41db3. L0 is uncommitted; use `git diff`.
- The author is codex gpt-6-sol.
- Report: collab/1790812000000-engineer-linker-L0.gpt-6-sol.report.md.

Verify adversarially every L0 bullet:
- The signing vectors: RFC 8032 plus canonical envelopes, on all three hosts.
- Tamper → :bad-proof.
- Key-file refusals.
- The publisher replacing both helpers.
- The footprint cases.
- Undefined exports write nothing.
- The shared local-runtime.
Also check that crypto comes only from host primitives or vetted libraries:
- JDK Ed25519;
- node crypto;
- Dart ed25519_edwards 0.3.2. Is it synchronous? Is it acceptable?

RULE on the two open items:
1. **Store corpus, semantic format.** §12 expects `:links` semantic `:refused`, but the existing semantic scanner discharges the read because the definition dominates every application. The test `a-definition-dominating-every-application-discharges-a-body-occurrence` pins that policy. So the publisher reports `:ok`.
   - Is the contract bullet wrong? If so, give the amended text for §12 (and §5.3 if needed).
   - Or must the linker change? It should not, in this slice.
2. **pubspec.lock.** It is outside L0's file list, so the engineer restored it to HEAD after `flutter pub get`. The lock is now inconsistent with pubspec.yaml's new ed25519_edwards pin, and a fresh checkout must run pub get. Should L0 include the updated pubspec.lock? The orchestrator leans yes.
3. The manifest test's helper adds a legacy synthetic export to a separately materialized fixture manifest after calling the publisher. Is that acceptable?

End with an explicit verdict: SIGN-OFF GRANTED or WITHHELD. Give findings as a Severity | file:line | issue | fix table, then the rulings, including exact amended contract text if needed.
