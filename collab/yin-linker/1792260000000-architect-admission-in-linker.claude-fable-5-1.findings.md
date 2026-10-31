Reading is done and the plan file is saved. Here is the design.

Completed-GMT: 2026-10-12 12:29 GMT
Completed-Local: 2026-10-12 19:29 +07 (Asia/Bangkok)

**Verdict.** The admission protocol accepted in round 3 is right; its home is wrong. The whole link-pair interpreter, not only admission, sits in the REPL today. Three facts prove the inversion, all verified in the source: the Python DHT import test, the VM-level `linker_require_test`, and the JVM content driver `dao.jing.content.driver` each require `yin.repl.link` or `yin.repl.admission` in order to link. The move is two namespace relocations plus one importer key, and the REPL shrinks to configuration and cadence. The datum shapes, the retained-entry binding, the half-open retirement interval, the consuming drain with gap recovery, the ShiBi verify contract and fail-closed reasons are kept as inputs and re-derived at the linker level below.

## 1. Layering

**The rule.** A thing is linker semantics if a plain Clojure consumer of the linker (dht.md section 10, the memory "plain Clojure DHT query path") must have it to resolve, ask, bind, deliver, discharge or retire a link. A thing is REPL if deleting the REPL deletes the need for it: when to step, what to print, which lines to retain, which principals this session names.

**What is linker semantics today in `yin.repl.link`:** the link pair minting, the source composition (principals, follow, derivation, admission policy), the serve round over the link requests, name resolution via `linker.dht/resolve-name`, the DHT load attempt and dependency-binding check, the admission ask/park/bind (`dht-answer`, `admitted`), the response append, and `retire` with its run descriptor. All of it is step 5 and 6 of linker.md 7.2 and the interpreter box of 6.1. **What is linker semantics in `yin.repl.admission`:** everything. **What is REPL in `yin.repl`:** `drive-links` as a cadence loop, `recheck-*` as tick hooks, `pending-text`, `abandon-pending`'s typed-line handling, error rollback carrying, the trust commands, `create-state` options, `--dht-trust`.

**The DHT link source is already in the wrong layer.** Yes. `yin.vm.linker.dht` holds resolve, load and link, but the thing that reads a request and decides pending/refused/ok lives only in the REPL.

**Target layering.**

| Lives in | Contents |
|---|---|
| `yin.vm.linker.interpreter` (new, from `yin.repl.link`) | `make-pair`, `source` (was `composition`), `make` (mints link pair, admission pair and in-process interpreter state as one value), `round`, `retire`, `run-descriptor`, `authority`, the formats table, the capacity and budget constants |
| `yin.vm.linker.admission` (new, from `yin.repl.admission`) | unchanged protocol: `ask`, `drain`, `judge`, `settle`, `forget`, `policy`, `interpreter`, `serve`, `reasons`, `trusted-asserter`, `context` |
| `yin.vm.module` / `yin.vm.engine` | the optional `:yin.link/importer` request key (section 3); the `:admission` registry record as landed |
| `yin.repl` | one per-session value `:linker` (the `make` result), `:link-source` as config, `drive-links` calling `round`, one tick hook, trust commands, `--dht-trust`, `:admission` option, `:external` |

`yin.repl.link` and `yin.repl.admission` are deleted, not aliased. Consumers re-require: `yin.repl`, `yin.repl.dht` (uses `authority`), `dao.jing.content.driver`, `linker_require_test`, `linked_harness`, `linked_prelude_test`, `dht_import_test`, `require_test`, `serve_test`, `python_antlr_test`.

Risk: eleven requiring files and the generated `test/cljd-out` tests; mechanical but wide. What I would not do: keep a shim namespace (no backward compat needed), or push admission into `yin.vm.linker/step`, which is the content-pair core and knows no names.

## 2. The linker's admission boundary

**Datums.** The question and both answer shapes are kept verbatim from 7.5.1, with one added optional key, `:yin.admit/importer` (section 3). Allow key set stays closed at id, decision, conditions plus nothing; a refusal keeps reason and open keys.

**Who mints.** `interpreter/make` mints the link pair and, only when the source has signed names (`:dht?`), the admission pair and its answer cursor. A local or remote content source has no admission pair, which closes the AD-1 review's finding 4. The composition stores the result; the VM's half of the link pair goes into its `:resources` as today.

**Where the wait lives.** In the request/response protocol of 6.1 and 7.2 a parked link is a request the interpreter's request cursor has not passed and a retained question under `:asked`. Nothing is appended on the response medium until the answer lands, so the VM's `:link-response` wait entry never learns that admission exists. This is already the landed behaviour and it is exactly where it belongs: the wait is the interpreter's cursor position, no new state.

**One round.** `(interpreter/round value node budget)` does, in order: drain answers, serve link requests (stop at first pending), serve the in-process interpreter when the value holds one. It returns the successor value, the node, `:pending` entries and `:progress?`. Progress is: a response appended, a question appended, or a drained answer that matched a retained question. The last clause is new and is what lets the REPL drop its admission-specific hook.

**How an interpreter attaches.** In process: the `:admission` policy builds the default `trusted-asserter` over decision = own principal plus `:trusted`, or a composed `:verify` over a static `:decision` or a capacity-1 `:decision-medium`. External: `:admission :external` keeps the pair and runs no interpreter; another process reads the question medium and appends answers, and over the network the two media are exposed as any dao.stream is, with no privileged node. ShiBi attaches as either, with no linker edit; `dao.shibi.md`'s first-consumer paragraph is reworded to name the linker.

**Authority versus admission.** Declared principals (R8, `:principals`) decide which assertions count in the fold. Trusted principals decide which resolved entries an importer accepts. They are independent composition inputs; the AD-1 review's finding 1 stands and is written into the section. A `:composition` provenance entry is never asked.

**Discharge and L-d.** The question is asked after resolve and before load; discharge is the receiver's step 5b after delivery and is untouched. An admission refusal is a section 9 link refusal, so try-require (L-d) catches it exactly as it catches `:absent`. A transitive dependency another principal asserts is asked on its own request when the install child requires it by name.

## 3. Cross-language use

**What the linker knows about the importer.** Today nothing: the request carries format and contract, which name the VM, not the frontend. Ruling: the link request gains an optional `:yin.link/importer`, the frontend `[id revision]` as a two-element vector, set by the composition on the VM state (`:importer`), copied by `module/require-handler` into the envelope, inherited by install children beside `:origin` and `:ancestry`, and copied verbatim into the question as `:yin.admit/importer`. The REPL stamps it from the selected frontend at session build and at `(lang ...)`. The request schema's closed key set and portability check admit this key and its vector shape. The default interpreter ignores it; a ShiBi verify can allow Clojure and refuse Python for one address.

**Registry hit.** Admission governs entry into a task's registry. A hit answers without a question, within that task, as 7.5.7 says. Isolation between frontends is per task keyed by `[id revision]`: ruling 18 (runtime state never merges across frontend revisions) already demands one Python task per profile, and the mob's Q1 record says the same. So the mob's conflict dissolves: shared within one task by construction, isolated across tasks by `[id revision]`. A legacy mixed session that runs two frontends on one task has chosen one importer per task and is told so in the doc. The exposure rule, stated at the linker level: an allow admits one address into one task's registry for the task's life; it names the importer the task declared; it never crosses a task boundary.

What I would not do: put the importer into the interpreter's context (that is REPL knowledge again), or ask again on a registry hit.

## 4. What to do with what landed

| AD-1 piece | Ruling |
|---|---|
| `yin.repl.admission` | MOVE to `yin.vm.linker.admission`; `context` drops `:yin.admit/session` (the importer travels in the question; the shell token was a REPL notion) |
| admission pair in `make-session` | MOVE into `interpreter/make`; minted only for a `:dht?` source |
| `serve-admission`, `admission-context`, `:admitter` in `drive-links` | DELETE from the REPL; `round` does it |
| `recheck-on-answers` | DELETE; the one existing tick hook runs one `round` and rechecks iff it reports progress |
| `link-raise` / `carry-served` | carry one `::linker` value instead of three keys |
| `abandon-pending` | one call to `interpreter/retire` on the value |
| `--dht-trust`, boot and `create-state` `:admission`, `:external` | KEEP, configuration |
| engine `:admission` registry record | KEEP as landed |
| `admission_doc_test` | MOVE to `test/yin/vm/`, still reads 7.5 |

**AD-2** deletes `principal-admission` and the `:admit` hook. Compatible, and it should land first: it only removes, and its test edits are in files the move renames anyway. **AD-3** becomes AL-4: `(trust p)`, `(untrust p)`, `(trusted)` edit `:link-source :admission :trusted` and rebuild the in-process policy, which is configuration. Its linked-REPL acceptance (F2, F3, P1-1, P1-2, P2-3) is rewritten against `round`. **F2** is landed enough to supply `[id revision]`; the follow-on linked REPL profile slice (one task per profile) is where cross-language isolation becomes observable at the prompt.

## 5. Trust versus safety, and the pinned-dependency fix

Confirmed unchanged: trust is the interpreter's answer, safety is the linker's and L-e's, an allow never relaxes L-e, conditions stay reserved empty.

The mob's Q4: `declared-discharge` does compare a pinned manifest address with the installed entry, but only for a retained free name the manifest declares under `:yin.module/requires`. A Python import produces no qualified free name, so no obligation record exists and discharge compares nothing; the DHT source's `dependency-bindings` is the only guard. The fix belongs in the engine's `linked` transition (`link-install`), linker seat: for every `[name pin]` in the manifest's requires, the child's linked entry for that name must carry that manifest address, else refuse `:dependency-pin-mismatch`. It is independent of admission and lands with L-d. AL-0 corrects the false 7.4 sentence.

## 6. Docs and slices

**Docs.** `yin.vm.linker.dht.md` 7.5 rewritten as the linker's admission (same reason vocabulary, so the doc test keeps pinning it; one new pinned literal, the namespace name). `yin.vm.linker.md`: new 6.5 "the link-pair interpreter", 6.3 gains the importer key, section 10 file box and export list updated. `yang.antlr.md` lines near 3230 and 3044 point at the AL slices. `dao.shibi.md` first-consumer paragraph names the linker. `docs/agents/architecture.md` gains the one-sentence layering rule from section 1.

**Slices.** Gate per slice:

```
bb test:sub yin.vm yin.repl yang.python
```

- **AL-0 docs** (Architect or writer). Acceptance: `admission-spec-test` green against the rewritten section on three hosts.
- **AL-1 move** (linker seat). git mv both namespaces and their tests; `make`, `round`, dht-only pair; all consumers re-required; REPL changes limited to requires. Acceptance: the 21 admission tests, `require_test`, `dht_test`, `linker_require_test` green on JVM, Node and Dart; `grep -rn 'yin.repl' src/cljc/yin/vm src/cljc/yang src/clj/dao` is empty; `run-over-dht` in the Python test requires only `yin.vm.linker.*`; slow `dht_import_test` green on the JVM. A collab note to the yang.python orchestrator before touching its tests.
- **AL-2 REPL reduction** (REPL seat). One `:linker` value, `round` in `drive-links`, the hook fold, one carried key. Acceptance: F3 late answer completes through the single hook with no input line; P1-2 (i) and (iii); P2-3 rollback; the word `admission` in `repl.cljc` appears only in option docstrings and `pending-text`.
- **AL-3 importer** (linker and engine). Acceptance, every VM, three hosts: a recording verify receives `:yin.admit/importer` equal to the session's `[id revision]`; an install child's dependency question carries the parent's importer; two sessions over one published module, one Clojure and one Python, under a verify that allows only Clojure: the first links, the second refuses `:yin.admit/untrusted-principal` with no load started; a request without the key still links under the default policy.
- **AL-4 trust commands** (REPL seat). Acceptance as AD-3's list, through `round`.

Order: AD-2, AL-0, AL-1, AL-2, then AL-3 and AL-4 in either order. Slow tier at AL-1 and AL-3.

**Must not land before L-e:** anything claiming an untrusted-origin import is safe; a default decision beyond own principals; honoring a non-empty condition; any weakening of profile, pin or export-origin checks; admission in a frontend or on the content pair; any claim that a per-importer allow is isolation. Everything here is AST-level data on streams; the register-VM cutover does not touch it.

**Unverified.** That `linker_step_test` pins the closed request key set literally (AL-3 may need one assertion updated). The exact wording of rulings 18 and 19 beyond the summaries at yang.antlr.md 3027 to 3046. That `(lang ...)` preserves the registry, taken from the codex review. The Python no-qualified-free-name inference in section 5 comes from I1's description, not a run. AD-2's lane results: its log shows the engineer still waiting on Node.
