Created-GMT: 2026-10-12 12:30:00 GMT
Created-Local: 2026-10-12 19:30:00 +07 (Asia/Ho_Chi_Minh)
Coding-Agent: codex (gpt-6.1-sol)
Session-ID: pending (provider-generated; not captured in stdout artifact)

Completed-GMT: 2026-10-12 12:35:01 GMT  
Completed-Local: 2026-10-12 19:35:01 +07 (Asia/Ho_Chi_Minh)

**REJECT**

The relocation is architecturally appropriate, but the design contains source-contradicted layering and exposure claims, and leaves the automatic-resume handoff underspecified. Its AL-1 acceptance also cannot hold with the stated edit boundary.

1. **P1 — The content-driver inversion claim is false.**  
   Design: `collab/1792260000000-architect-admission-in-linker.claude-fable-5-1.findings.md:6,25`. Source: [driver.clj:20](/Users/sto/workspace/datomworld/src/clj/dao/jing/content/driver.clj:20).

   `dao.jing.content.driver` requires only `dao.jing`, `dao.jing.content.step`, and `dao.stream`. Its docstring identifies `yin.repl.link` as a downstream consumer through the coordinate opener; that is the opposite dependency direction. It requires neither REPL namespace to link.

   The other two facts are true: [dht_import_test.cljc:46](/Users/sto/workspace/datomworld/test/yang/python/antlr/dht_import_test.cljc:46) requires both namespaces, and [linker_require_test.cljc:34](/Users/sto/workspace/datomworld/test/yin/vm/linker_require_test.cljc:34) requires `yin.repl.link`.

   **Exact fix:** Remove the driver from the inversion evidence and re-require list; update its consumer docstring after relocation. Retain the move’s justification from the two verified consumers and the actual request-serving semantics. **Owner:** architect.

2. **P1 — “Never crosses a task boundary” contradicts registry inheritance; changing importer on `(lang ...)` does not create isolation.**  
   Design: findings file `:47–49`. Sources: [module.cljc:552](/Users/sto/workspace/datomworld/src/cljc/yin/vm/module.cljc:552), [engine.cljc:1289](/Users/sto/workspace/datomworld/src/cljc/yin/vm/engine.cljc:1289), [engine.cljc:1396](/Users/sto/workspace/datomworld/src/cljc/yin/vm/engine.cljc:1396), [repl.cljc:1872](/Users/sto/workspace/datomworld/src/cljc/yin/repl.cljc:1872).

   Install children inherit admitted entries, and dependencies installed by children transfer to parents. Authorization therefore already crosses task boundaries within an installation family. Today `(lang ...)` changes only `:lang`, preserving the VM and registry.

   With AL-3’s proposed restamping, Clojure can admit M, then Python in the same task can obtain M through the registry hit without consulting a verifier that refuses Python. Calling the session “legacy” does not resolve the contradiction between a lifetime importer and an importer changed at language selection. Ruling 18 prohibits merging runtime state across frontend revisions; it does not establish that today’s REPL allocates isolated tasks.

   **Exact fix:** State that admission follows the registry and its installation-family inheritance. Choose one explicit AL-3 rule:
   
   - Keep importer immutable for that family, document mixed-language registry reuse, and defer frontend isolation to the profile-task slice; or
   - Make language/profile selection choose a separate task and registry before claiming frontend isolation.

   Add a same-session Clojure → Python acceptance case, alongside the two-session case. Retain cache hits without questions only within the chosen exposure domain. **Owner:** architect and frontend/VM engineer.

3. **P1 — `round` progress alone does not specify a safe tick-to-VM handoff.**  
   Design: findings file `:37,60,87`. Source: [repl.cljc:1371](/Users/sto/workspace/datomworld/src/cljc/yin/repl.cljc:1371), [repl.cljc:1398](/Users/sto/workspace/datomworld/src/cljc/yin/repl.cljc:1398), [repl.cljc:1956](/Users/sto/workspace/datomworld/src/cljc/yin/repl.cljc:1956).

   The proposed hook runs a `round`, then rechecks when that round reports progress. A late allow can make that first round append the link response and advance the request cursor. A subsequent `recheck-pending` calls `drive-links`; its own round now finds no work and reports no progress. The existing driver consequently never calls `vm/run` to consume the already-appended response.

   The consuming drain eliminates answer-window starvation, but its progress flag must reach the waiting VM. The stated protocol does not establish that handoff.

   **Exact fix:** Specify that the tick retains the round successor and passes its response/progress into the pending driver, which runs the VM once before requiring another interpreter round to report progress. Alternatively, make the pending driver perform the tick’s round itself. Preserve one bounded drain per intended round and successor state before any raising VM run.

   **Acceptance:** External allow arrives after the initial drive stops; the tick’s round delivers the response; no further question, input line, load event, or answer is needed to complete. Also exercise a decision snapshot becoming available after parking. **Owner:** linker/REPL engineer, AL-2.

4. **P1 — AL-1’s edit boundary and acceptance are incompatible.**  
   Design: findings file `:25,86`.

   AL-1 deletes the old namespaces and renames `composition` to `source`, introduces `make`/`round`, and changes topology, while limiting REPL changes to requires. Existing REPL calls and state construction still use `composition`, `serve`, and separately minted admission state. A requires-only edit cannot use the proposed final interface.

   The consumer inventory also omits [derive_test.cljc:12](/Users/sto/workspace/datomworld/test/yin/vm/semantic_register/derive_test.cljc:12). The move/rename affects the path-and-function allowlist in [store_write_audit_test.clj:331](/Users/sto/workspace/datomworld/test/yin/vm/store_write_audit_test.clj:331).

   The literal empty grep cannot pass: legitimate matches include the publication datum `:yin.repl/root`, transport error keywords, docstrings, and `src/cljc/yin/vm/docs/yin.repl.md`.

   **Exact fix:** Make AL-1 a namespace relocation preserving callable API and session topology; put API/topology reduction in AL-2. Alternatively, include the necessary REPL adaptation in AL-1 explicitly. Inventory all require forms, update the store-write allowlist, and replace the literal grep with a namespace-dependency check that excludes documentation and datum vocabulary. Regenerate renamed Dart namespaces and remove stale outputs before relying on a glob-based run. **Owner:** linker engineer.

5. **P2, non-blocking — Narrow the AL-2 lexical assertion and state the complete gate ladder.**  
   Design: findings file `:23,79–89`. Sources: [repl.cljc:944](/Users/sto/workspace/datomworld/src/cljc/yin/repl.cljc:944), [build-n-test.md:126](/Users/sto/workspace/datomworld/docs/agents/build-n-test.md:126).

   Keeping `create-state` admission configuration necessarily permits executable occurrences of `admission` in `repl.cljc`; AL-4 also edits `:link-source :admission :trusted`. “Only option docstrings and pending-text” is therefore inconsistent with the retained configuration role.

   `bb test:sub yin.vm yin.repl yang.python` is a checkpoint selection, not a replacement for changed-dependent, master, and slow phase-boundary gates.

   **Exact fix:** Assert absence of REPL-owned admission protocol state, drain/judge/serve calls, and admission-specific hooks; allow configuration plumbing and display text. Explicitly retain G0–G4, with quick gates per landing and slow gates at phase boundaries. Add `dao.jing` selection if its files are actually edited. **Owner:** architect/test owner.

The remaining requested rulings follow from source inspection:

- **Layering:** Moving the request interpreter, resolution/admission binding, delivery, and retirement into `yin.vm.linker.interpreter`, with the protocol in `yin.vm.linker.admission`, is sound. No dependency cycle is inherent in that move: follow/authority/configuration must remain supplied data, and neither new namespace should require `yin.repl.dht`. Shell tokens, pending text, and typed-line handling stay outside. The tables are functionally coherent once findings 1 and 4 are corrected.

- **Earlier P1 mapping:** Retained-resolution binding remains enforced by `admitted` at [link.cljc:399](/Users/sto/workspace/datomworld/src/cljc/yin/repl/link.cljc:399). Retirement remains enforced by normalized half-open intervals and delimiter-qualified descendants at [link.cljc:587](/Users/sto/workspace/datomworld/src/cljc/yin/repl/link.cljc:587). Starvation prevention remains the persisted consuming drain at [admission.cljc:110](/Users/sto/workspace/datomworld/src/cljc/yin/repl/admission.cljc:110), with full `[link-id serial]` correlation at line 95. Automatic late resume needs finding 3’s handoff. The earlier boot-trust P1 stays enforced by explicit trust independent of declared authorities. Rollback must carry the entire new interpreter value before VM execution can raise.

- **Importer envelope:** The optional key is compatible with the pair shape, section 9 response correlation, and engine waits. It requires both closed-key-set and portability-validator edits at [linker.cljc:1486](/Users/sto/workspace/datomworld/src/cljc/yin/vm/linker.cljc:1486): permit exactly the intended two-element portable vector, not arbitrary collections. All four kernels’ constructors and `spawn-module` implementations must preserve it; adding an engine child option alone is insufficient because kernels select options explicitly. Test malformed vectors, host/function elements, omission, and child propagation.

- **Q4:** The gap and engine placement are sound. The wording is slightly inaccurate: [linker.cljc:2088](/Users/sto/workspace/datomworld/src/cljc/yin/vm/linker.cljc:2088) recognizes a retained free name equal to the required module itself **or** qualified by it. Python’s imported dependency is supplied through a quoted require and module-object operations, rather than such a retained free name; Python still has other qualified names such as `py/import`. Check every manifest require against the halted child’s linked entry before lifting/publishing the installed slice; absent or unequal entries refuse. This is admission-independent and backend-neutral. Its acceptance should bypass the DHT early guard and cover inherited and freshly installed mismatches on all four VMs.

- **AD-1 / AD-2:** AD-2 first is architecturally safe when it removes the hook, option, implementation, documentation, and Python-specific assertions together, retaining the uniform admission seam and safety prerequisites. Current source still contains the hook; the available AD-2 log reports waiting for Node, so its completion is unverified. Keep AD-1’s validated allow shapes, registry records, binding, drain, retirement, and rollback. Move their ownership as proposed. AL-4 can remain configuration-only; rebuilding policy must preserve protocol cursors, retained questions, serials, and external mode. Untrust remains nonretroactive.

- **Invariants:** The redesign introduces no second code identity, privileged P2P node, capability-token semantics in the linker, or fetch deadline. Importer identity is policy input, not code identity. Allow must continue to leave profile, pin, and export-origin checks intact. The AST-level protocol is independent of the register-VM cutover, although importer propagation and Q4 acceptance must cover every backend.

Read-only review completed.

## Provenance correction — 2026-10-12 12:52 GMT

The body above was extracted from the Codex stdout's printed `nl` output of the **round-1** findings file, not from a round-2 final response. It is not a valid round-2 review and must not be used as the round-2 verdict. The captured stdout ends during source inspection and contains no final round-2 verdict. This correction preserves the mistaken append-only artifact and supersedes its body as evidence. A fresh, separately identified review is being requested against the same round-2 design.

## Follow-up disposition — 2026-10-12 12:54 GMT

The round-2 review is now available in `collab/1791809519945-reviewer-admission-in-linker-r2-confirm.gpt-6.1-sol.findings.md` (Codex thread `01a1302b-da7c-77e1-9319-eaaa651e0473`). Its verdict is APPROVE-WITH-NITS, with no open in-scope P0/P1 and one non-blocking P3 exact fix. The body above remains superseded as review evidence; it is retained only as an append-only provenance correction.
