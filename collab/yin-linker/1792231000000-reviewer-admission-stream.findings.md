Completed-GMT: 2026-10-12 05:39:05 GMT
Completed-Local: 2026-10-12 12:39:05 +07 (Asia/Ho_Chi_Minh)

**REJECT**

1. **P1 — An allow is not bound to the resolution it authorized.**  
   Design `collab/1792230000000-architect-admission-stream.claude-fable-5-1.findings.md:32` stores answers by link id, then re-attempts resolution. Currently, `src/cljc/yin/repl/link.cljc:393` resolves the name afresh each round. While admission waits, a head update can change its address or asserters. An allow for manifest M1 can consequently authorize fetching M2 under the same request id.

   **Exact fix:** Retain the complete question and resolved entry as pending state. Apply its answer only to that entry and pass its retained address to `dht-attempt`. If resolution must be refreshed, retire the old question and mint a new admission correlation id. Test a head change between question and answer.

2. **P1 — Abandonment does not release the admission queue.**  
   Design line 32 says abandonment ends admission and late answers are skipped. However, `src/cljc/yin/repl.cljc:1398` explicitly leaves the linker unaware of abandonment, while `src/cljc/yin/repl/link.cljc:472` retains its cursor at the first unanswered request. With an indefinitely unanswered admission question, `(abandon)` clears the VM wait but leaves that request blocking every subsequent require on the surviving pair. This persists after the user abandons the supposedly blocking work.

   **Exact fix:** Specify an explicit retirement path carrying abandoned link/admission ids to the serving composition. Advance past retired requests, discard their retained questions and answers, and ignore later answers without reactivating them. Test unanswered admission → abandon → successful unrelated require on the same pair.

3. **P1 — Late admission answers have no specified automatic resume path.**  
   Design lines 32 and 41 specify interpreter placement but omit admission readiness from host driving. `src/cljc/yin/repl.cljc:1943–1964` rechecks only matching load or head events. Admission occurs before loading, so a late answer produces neither event. Additionally, `drive-links` at line 1340 continues only when an existing interpreter reports progress; admission progress must participate in that decision.

   **Exact fix:** Include question publication and admission-interpreter progress in drive continuation. Add bounded polling/rechecking of admission responses to the host drive, with correlation to pending admission ids. Preserve no-deadline semantics. Test an answer arriving after the initial drive has stopped, with no input line or DHT load event.

4. **P2 — The claimed ShiBi adapter contract is different from the documented contract.**  
   Design line 30 calls `(verify decision question)` “exactly” the existing seam. `docs/design/dao.shibi.md:40–45` requires decision, context, and request; lines 53–62 also require bounded synchronous verification and defined handling of absent/ended decision snapshots. The proposed importer map contains format and contract, not an authorization subject, and the optional session field does not define one.

   **Exact fix:** Define `(verify decision context question)`, with explicit composition-owned subject/session context and defined absent/ended snapshot behavior. Keep authorization vocabulary in the interpreter’s context and policy, outside linker data. Document this request/answer interpreter as an additional attachment, consistent with ShiBi’s explicit non-goal at lines 65–70.

5. **P2 — AD-1/AD-2 omit the portable test driver changes needed to answer admission.**  
   Design lines 39 and 47–48 change `source` and refusal assertions, but `test/yang/python/antlr/dht_import_test.cljc:300–304` drives only `link/serve` and the mesh. Merely composing an admission pair supplies no interpreter round; trusted imports will remain pending. This cannot wait for AD-3’s production REPL wiring.

   **Exact fix:** In AD-1, expose the admission pair’s successor state and update portable composition drivers to run and retain both interpreters. Update `run-over-dht` in AD-2 accordingly. Cover unsupported conditions, duplicate/late answers, abandonment, and resolution changes alongside the listed acceptance cases. The published DHT artifact can remain unchanged.

The original cross-frontend P1 bypass **is closed by a uniformly enforced session policy**, provided every DHT link in that session uses it. The cache return at `module.cljc:552–554` and `(lang ...)` preserving the registry then reuse a prior session authorization. The exposure explanation nevertheless overstates task isolation: install children inherit registry entries (`engine.cljc:1289–1307`), and parents receive dependencies installed by children (`engine.cljc:1396–1403`). Authorization therefore follows the session and inherited registry, rather than requiring a fresh answer for each task.

“Untrust is not retroactive” means cached requires, inherited entries, existing closures, and installed code remain usable; it affects later unanswered/new admission questions. Reset must establish a fresh registry.

The separation from safety is sound: primitive/profile and exact dependency-pin discharge remain independent (`linker.cljc:1020–1075`), as do export-origin checks (`engine.cljc:1347–1383`). Empty conditions in v1 are appropriate. The proposal introduces no additional code identity, fetch deadline, privileged P2P node, or observer-to-observer dependency.

Read-only review completed; no files edited or suites run.
