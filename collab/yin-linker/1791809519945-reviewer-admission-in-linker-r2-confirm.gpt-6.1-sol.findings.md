Completed-GMT: 2026-10-12 12:53:48 GMT  
Completed-Local: 2026-10-12 19:53:48 +07 (Asia/Ho_Chi_Minh)

**APPROVE-WITH-NITS**

**No open in-scope P0/P1.** No blocking P2 in newly introduced behavior was identified. This approves the revised architecture and slice plan; it does not certify implementation or suite results.

References to “design” below mean `collab/1792262000000-architect-admission-in-linker-r2.claude-fable-5-1.findings.md`.

1. **Round-1 P1: content-driver inversion — FIXED.**

   Design:16 correctly removes the driver from the dependency-inversion evidence. [driver.clj:20](/Users/sto/workspace/datomworld/src/clj/dao/jing/content/driver.clj:20) requires only `dao.jing`, `dao.jing.content.step`, and `dao.stream`.

   The verified downstream consumers remain: `dht_import_test.cljc:46–47`, `linker_require_test.cljc:34`, and `semantic_register/derive_test.cljc:12`.

   **Required fix:** None beyond the planned driver docstring update.

2. **Round-1 P1: exposure and frontend isolation — FIXED.**

   Design:10,43 withdraws importer identity and accurately places admission exposure in the registry’s installation family.

   [engine.cljc:1289](/Users/sto/workspace/datomworld/src/cljc/yin/vm/engine.cljc:1289) inherits and receives linked entries into the install child. [engine.cljc:1396](/Users/sto/workspace/datomworld/src/cljc/yin/vm/engine.cljc:1396) records admission on the installed entry and transfers child dependencies. [module.cljc:552](/Users/sto/workspace/datomworld/src/cljc/yin/vm/module.cljc:552) answers a registry hit before emitting a link request.

   “Untrust is not retroactive” matches the source: a retained answer is consumed without re-verification, and admitted registry entries remain usable. Reset and VM selection rebuild session VM/admission state at [repl.cljc:734](/Users/sto/workspace/datomworld/src/cljc/yin/repl.cljc:734).

   Language-blindness is complete in the proposed linker data, context, slices, and acceptance criteria. Frontend references explain an excluded concern; they introduce no frontend policy discriminator.

   **Required fix:** None.

3. **Round-1 P1: tick-to-VM handoff — FIXED in the design.**

   Design:47–54 explicitly carries the tick’s successor and progress into `drive-links`, runs the parked VM once before another round, and preserves the successor through rollback.

   This addresses the actual source gap: [drive-links:1323](/Users/sto/workspace/datomworld/src/cljc/yin/repl.cljc:1323) currently runs the VM only when its own serving iteration reports progress. [recheck-pending*:1945](/Users/sto/workspace/datomworld/src/cljc/yin/repl.cljc:1945) supplies no prior progress. Existing rollback machinery at [repl.cljc:1253](/Users/sto/workspace/datomworld/src/cljc/yin/repl.cljc:1253) establishes the appropriate carrier pattern.

   The proposed ordering avoids a duplicate interpreter round before the first VM run; later work remains bounded by `link-round-budget`. Acceptance (a) detects lost late-allow progress, (b) detects delayed decision-snapshot starvation, (c) detects successor loss on refusal, and (d) detects spurious rechecks.

   **Required fix:** None beyond implementing the specified handoff and acceptance cases.

4. **Round-1 P1: AL-1 relocation/API mismatch — FIXED.**

   Design:85 preserves every public var and session topology, including `composition`, `serve`, separate pairs, and existing hooks. API/topology reduction moves to AL-2.

   My require-form search matches the complete inventory at design:80. The public definitions in both current namespaces fit the stated relocation. The store-write allowlist at [store_write_audit_test.clj:331](/Users/sto/workspace/datomworld/test/yin/vm/store_write_audit_test.clj:331) has the planned path and `composition` entry.

   The replacement dependency check excludes the current documentation and keyword false positives; there are no current matches in its scanned source directories. Dart stale-output removal is explicitly required. [subsystems.edn:43](/Users/sto/workspace/datomworld/src/dev/subsystems.edn:43) assigns the relocated test namespace prefix to `yin.vm`.

   **Required fix:** None.

5. **Round-1 P2: AL-2 lexical assertion and gate ladder — FIXED.**

   Design:82,92 permits configuration and display while removing REPL-owned admission protocol state and calls. That is compatible with current configuration plumbing and the intended one-value ownership model.

   G0–G4 match [build-n-test.md:126](/Users/sto/workspace/datomworld/docs/agents/build-n-test.md:126), including changed-dependent coverage, slow gates, and `dao.jing` selection for the driver edit.

   **Required fix:** None.

6. **Q4, AD-2 compatibility, and retained AD-1 behavior — sound.**

   Design:76 correctly describes [declared-obligation:2088](/Users/sto/workspace/datomworld/src/cljc/yin/vm/linker.cljc:2088): it recognizes a retained name equal to the required module or qualified by it. Python module delivery uses quoted requires at [lower.cljc:1972](/Users/sto/workspace/datomworld/src/cljc/yang/python/antlr/lower.cljc:1972) and constructs those operands at line 2006.

   Checking every manifest pin against the halted child’s linked entry before `lift-slice` in [engine.cljc:1347](/Users/sto/workspace/datomworld/src/cljc/yin/vm/engine.cljc:1347) closes the inherited-registry gap. Bypassing the DHT early guard and covering inherited/fresh mismatches on all four VMs is appropriate acceptance.

   AD-2 first removes the current frontend hook at [link.cljc:443](/Users/sto/workspace/datomworld/src/cljc/yin/repl/link.cljc:443) and [publication.cljc:130](/Users/sto/workspace/datomworld/src/cljc/yang/python/antlr/publication.cljc:130). Its completion remains unverified.

   The fate table preserves validated allow shapes, resolution binding, consuming drain, retirement, registry records, and rollback. Source support includes `admission.cljc:95,110,180` and `link.cljc:399,587,620`. AL-3/AL-4 appropriately separate holder-context plumbing from trust commands and preserve protocol state during reconfiguration.

   **Required fix:** None; retain the stated L-e safety prerequisites.

7. **P3, non-blocking — make AL-2 hook consumers explicit and align `reconfigure` timing.**

   Design:20 places `reconfigure` “after AL-2,” while design:93 introduces it in AL-3. The require inventory is complete, but the hook-replacement edit list is implicit.

   [main.cljc:1050](/Users/sto/workspace/datomworld/src/cljc/yin/repl/main.cljc:1050) calls both old hooks. Direct test callers also exist in `admission_test.cljc`, `dht_test.cljc`, and `linker/dht_end_to_end_test.cljc`.

   **Exact fix:** Change design:20 to “after AL-2: `make`, `round`; after AL-3: `reconfigure`.” Add those host/test callers to AL-2’s explicit adaptation list, replacing the two host calls with one `recheck-on-tick` call and updating direct-hook tests. **Owner:** architect / REPL implementer.

The listed invariants hold: A remains the sole code identity; admission uses ordinary `dao.stream` exposure without a privileged peer; bounded rounds introduce no fetch deadline; no capability semantics enter linker data; register-VM cutover remains independent; and ruling 19’s safety checks remain mandatory regardless of trust.

Read-only review completed. No files edited or suites run.