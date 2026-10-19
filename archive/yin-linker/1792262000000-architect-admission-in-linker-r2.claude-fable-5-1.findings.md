Plan file saved. Here is the complete revised design.

Completed-GMT: 2026-10-12 14:21 GMT
Completed-Local: 2026-10-12 21:21 +07 (Asia/Bangkok)

**Verdict.** The relocation stands; the importer idea is withdrawn. Codex's four P1s were all correct and are fixed below. The content driver is dropped from the evidence, the exposure rule follows the registry and its installation family, the tick now hands its round's successor and progress to the pending driver, and AL-1 becomes a pure relocation with the API reduction in AL-2. The owner's correction supersedes section 3: there is no language at the linker level.

## 0. Owner correction, applied

All languages compile to the universal AST. yin.vm does not know the source language and the linker must not either. No linker datum carries a language, a frontend or an `[id revision]`: `:yin.link/importer` and `:yin.admit/importer` are deleted from this design, the closed request key set is not edited, and no kernel constructor or `spawn-module` changes. Admission is about principals (signing keys that asserted a name), addresses (A, the only code identity) and names. "Who is asking" is the holder: a composition-owned context the interpreter receives as the `context` argument of `(verify decision context question)`, today built by `admission/context` from the session's own key. A published module is AST at an address; any frontend may import it when the holder's policy admits that principal for that address. Registry reuse between frontends is not a linker security question. Frontend runtime-state isolation (ruling 18, Python's `py.rt` cells) is a frontend and session concern outside the linker and is not designed here.

## 1. Layering

Rule: linker semantics is what a plain Clojure consumer (dht.md section 10) must have to resolve, ask, bind, deliver, discharge or retire a link. REPL is what disappears with the REPL: cadence, display, retained lines, which principals this session names.

Evidence, verified: `test/yang/python/antlr/dht_import_test.cljc:46-47` requires both REPL namespaces to run a Python DHT import; `test/yin/vm/linker_require_test.cljc:34` and `test/yin/vm/semantic_register/derive_test.cljc:12` require `yin.repl.link` to mint a pair and serve a VM-level request. The request-serving semantics (resolve, ask, bind, deliver, retire) exists only in `yin.repl.link/serve`. Correction: `dao.jing.content.driver` requires only dao.jing, dao.jing.content.step and dao.stream (`src/clj/dao/jing/content/driver.clj:20`); its docstring names yin.repl.link as a downstream consumer. It is not evidence and is not re-required; its docstring is updated in AL-1.

| Lives in | Contents |
|---|---|
| `yin.vm.linker.interpreter` (from `yin.repl.link`) | pair minting, `composition`, `authority`, `serve`, `run-descriptor`, `retire`, `formats`, constants; after AL-2 also `make`, `round`, `reconfigure` |
| `yin.vm.linker.admission` (from `yin.repl.admission`) | protocol unchanged: `make-state`, `drain`, `ask`, `asked`, `answer-to`, `judge`, `settle`, `forget`, `context`, `trusted-asserter`, `policy`, `interpreter`, `serve`, `reasons` |
| `yin.vm.engine` / `yin.vm.module` | the `:admission` registry record as landed; the pinned-dependency check (section 5) |
| `yin.repl` | configuration (`:link-source`, `:admission` option, `--dht-trust`, `:external`), one `:linker` value after AL-2, one tick hook, trust commands, display text |

Neither new namespace requires any `yin.repl*` namespace; follow, authority and configuration stay supplied data. Old namespaces deleted, no shim.

## 2. The linker's admission boundary

Datums: question and answer shapes of 7.5.1 verbatim, no added key. Allow key set closed at id, decision, conditions; a refusal keeps reason and open keys.

Minting (AL-2): `interpreter/make` mints the link pair and, only for a `:dht?` source, the admission pair and answer cursor. A local or remote content source has no admission pair.

Where the wait lives: a parked link is a request the interpreter's request cursor has not passed plus a retained question under `:asked` (`link.cljc:399` and `:427`). Nothing lands on the response medium until the answer does, so the VM's wait entry never learns admission exists. No new state.

One round, `(interpreter/round linker node budget)`, in order: drain answers (one bounded drain per round, the `admission/drain` semantics), serve link requests stopping at the first pending, serve the in-process interpreter when the value holds one. Returns the successor value, the node, the pending entries and a progress flag. Progress is any of: a response appended, a question appended, an in-process answer appended, a drained answer matched a retained question. The successor is always carried, even without progress.

Attachment: in process, `:admission` builds the policy (`trusted-asserter` over own plus `:trusted`, or a composed `:verify` over a static `:decision` or a capacity-1 `:decision-medium`). External, `:admission :external` mints the pair and runs no interpreter; another process reads questions and appends answers over any dao.stream exposure, no privileged node. ShiBi attaches as either with no linker edit.

Authority versus admission: declared principals (R8) decide which assertions count in the fold; trusted principals decide which resolved entries the holder accepts. Independent inputs. A `:composition` provenance entry is never asked.

Discharge and L-d: ask after resolve, before load; discharge is step 5b after delivery, untouched. An admission refusal is a section 9 link refusal, so try-require catches it as it catches `:absent`. An install child's transitive requirement is asked on its own request.

**Exposure, stated exactly.** Admission governs entry into a registry. An allow admits one address into the requesting task's registry, and authorization follows that registry and its installation-family inheritance: `link-install` spawns the child over the parent's linked entries and receives each into it (`engine.cljc` ~1289-1307); the child's installs transfer back to the parent with the `:admission` record (~1396-1403); a name already resolvable in the registry answers with no link request and hence no question (`module.cljc` ~552-554). The exposure domain is the installation family for its life, not one task. It is about principal plus address plus holder context, and it is language-blind: any frontend compiling to a require of that name hits the same entry. "Untrust is not retroactive" means `(untrust p)` changes the decision snapshot for questions not yet answered; it evicts no registry entry, revokes no appended response, and leaves a question already answered allow allowed. `(reset)` and `(vm ...)` start a fresh registry and fresh admission state.

## 3. The tick-to-VM handoff

Chosen: the tick retains the round's successor and passes its progress into the pending driver, which runs the VM once before requiring another round to report progress. One hook replaces `recheck-on-load-events` and `recheck-on-answers`: `(recheck-on-tick state events)`.

1. Run one `round` on the session's `:linker` with the session node; assoc the successor and node into state before anything else, so a raising VM run carries it (`link-raise` keeps the one `::linker` key, `carry-served` restores it).
2. If the round reported progress, call the pending driver with progress carried in. `drive-links` gains an initial-progress argument: its first iteration runs the VM once without a round of its own (no second drain before that run), then continues the existing loop, bounded by `link-round-budget`.
3. Else, if the events hold a loaded or load-failed event for a manifest a pending link waits on, or an installed head of an awaited principal, recheck as today. The no-progress `:checks` counter and the link policy are unchanged.
4. Else nothing happens.

Acceptance (AL-2, three hosts): (a) a require parks on an external question and the initial drive stops; an allow is appended; one tick's round delivers the response and the run completes with no further question, input line, load event or answer. (b) The same with an empty decision medium at park time and a snapshot appended after parking: the in-process interpreter answers in one tick's round, the drain matches and the response lands inside the driver's loop, and the run completes. (c) A refusal arriving the same way raises as the VM's own with the successor `:linker` carried into the rolled-back state. (d) A tick with no progress and no relevant event rechecks nothing and leaves `:checks` unchanged.

## 4. What to do with what landed

| AD-1 piece | Ruling |
|---|---|
| `yin.repl.admission` | MOVE to `yin.vm.linker.admission` (AL-1); `context` keeps `:yin.admit/session` through AL-1, AL-3 replaces it |
| `yin.repl.link` | MOVE to `yin.vm.linker.interpreter` (AL-1), API preserved |
| admission pair in `make-session` | untouched in AL-1; AL-2 moves it into `make`, dht-only |
| `serve-admission`, `admission-context`, `:admitter`, `recheck-on-answers` | untouched in AL-1; AL-2 deletes them |
| `link-raise` / `carry-served` | AL-2: carry one `::linker` key |
| `abandon-pending` | AL-2: one `retire` call |
| `--dht-trust`, `:admission`, `:external` | KEEP, configuration |
| engine `:admission` registry record | KEEP |
| `admission_test`, `admission_doc_test` | MOVE to test/yin/vm/linker/ (AL-1) |

AD-2 (delete Python `principal-admission` and the `:admit` hook; `publication.cljc:130` still present) is compatible and lands first; AL-1 rebases over it. AD-3 becomes AL-4.

## 5. Trust versus safety; the pinned-dependency fix

Unchanged: trust is the interpreter's answer, safety is the linker's and L-e's, an allow never relaxes L-e, conditions stay empty.

Q4, wording corrected: `declared-obligation` (`linker.cljc` ~2088) recognizes a retained free name equal to the required module or qualified by it. Python's imported dependency arrives through a quoted require and module-object operations, not such a retained name, so no module obligation exists and discharge compares nothing; the DHT source's dependency-bindings check is the only guard. Fix, engine seat, lands with L-d, independent of admission: in the `linked` transition before lifting or publishing the installed slice, check every pinned require of the manifest against the halted child's linked entry for that name; absent or unequal refuses `:dependency-pin-mismatch`. Acceptance bypasses the DHT early guard and covers inherited and freshly installed mismatches on all four VMs. AL-0 corrects the false 7.4 sentence.

## 6. Slices, inventory, gates

Consumer inventory (require forms, verified by grep, excluding test/cljd-out): `src/cljc/yin/repl.cljc:19,23`; `src/cljc/yin/repl/link.cljc:40`; `src/cljc/yin/repl/dht.cljc:63`; `test/yang/python/antlr/dht_import_test.cljc:46-47`; `test/yang/python/antlr/linked_harness.cljc:20`; `test/yang/python/antlr/linked_prelude_test.cljc:23`; `test/yin/repl/admission_test.cljc:25,27`; `test/yin/repl/admission_doc_test.cljc:11-12`; `test/yin/repl/serve_test.cljc:22`; `test/yin/repl/python_antlr_test.cljc:31`; `test/yin/repl/require_test.cljc:28`; `test/yin/vm/linker_require_test.cljc:34`; `test/yin/vm/semantic_register/derive_test.cljc:12`. Docstring mentions (publication.cljc:132, driver.clj:7, repl.cljc, store_write_audit_test.clj:368, linker_require_test.cljc:1323) are text edits.

Gate ladder (docs/agents/build-n-test.md): G0 iterate `bb test:sub:clj <own>`; G1 checkpoint `bb test:sub yin.vm yin.repl yang.python`, adding `dao.jing` when a dao.jing file is edited (AL-1's driver docstring is one); G2 land `bb test:changed` on three lanes plus `bb test:sub <own> --slow` where slow tests exist; G3 master `bb test` after merge; G4 slow tier at phase boundaries, after AL-2 and after AL-4. Before any Dart lane after a rename, `rm -rf test/cljd-out` so the aggregator does not pick up stale generated files.

- **AL-0 docs.** dht.md 7.5 rewritten as the linker's admission with section 0's ruling, 7.5.7 replaced by section 2's exposure text, 7.4 corrected; linker.md new 6.5 and section 10 updated; dao.shibi.md first-consumer paragraph names the linker; yang.antlr.md ~3044 pointer; docs/agents/architecture.md gains the layering rule; src/cljc/yin/vm/docs/yin.repl.md pointer. Acceptance: `admission-doc-test` green on three hosts against the rewritten section.
- **AL-1 relocation** (linker seat). `git mv` both namespaces and both admission tests; every public var and the session topology preserved (`composition` and `serve` keep their names; `:link-pair`, `:admission`, `:admitter`, `serve-admission`, `recheck-on-answers` untouched); all require forms updated; store-write allowlist key moved to `src/cljc/yin/vm/linker/interpreter.cljc` with the same `composition` entry; driver docstring updated. Acceptance: admission tests, `require_test`, `dht_test`, `linker_require_test`, `derive_test` green on three hosts; the namespace-dependency check is empty:

```
grep -rn -E '^\s*\[yin\.repl(\.[a-z-]+)? ' src/cljc/yin/vm src/cljc/yang src/clj/dao --include='*.clj*'
```

  It matches require vectors only, so docs and datum vocabulary such as `:yin.repl/root` and error keywords do not match. `bb test:sub:list yin.vm` shows the two new test namespaces; slow `dht_import_test` green on the JVM. Collab note to the yang.python orchestrator before touching its tests.
- **AL-2 REPL reduction and handoff** (REPL seat, with the linker-side `make` and `round` in the same slice). Acceptance: section 3 (a) to (d); F3, P1-2 (i) and (iii), P2-3 against `round`; lexical: `repl.cljc` requires `yin.vm.linker.interpreter` and not `yin.vm.linker.admission`; no session keys `:admission` or `:admitter`; no calls to `drain`, `judge`, `ask`, `settle` or the admission `serve`; exactly one tick hook; configuration plumbing and display text allowed.
- **AL-3 holder context** (linker seat). `composition` gains an optional `:holder` (a principal id, or a token the verify understands); `context` answers the subject from it, defaulting to the own key's principal; `:yin.admit/session` deleted; `reconfigure` replaces the policy of a `:linker` value while preserving the interpreter's cursors, retained questions, serials and external mode. Acceptance, three hosts: a recording verify receives the subject the composition supplied; reconfiguring to a new trusted set answers a still-parked question under the new decision with cursors and serials unchanged.
- **AL-4 trust commands** (REPL seat). `(trust p)`, `(untrust p)`, `(trusted)` edit the `:trusted` configuration and call `reconfigure`. Acceptance: AD-3's list through `round`, plus untrust-not-retroactive from section 2.

Order: AD-2, AL-0, AL-1, AL-2, AL-3, AL-4. Slow tier after AL-2 and AL-4.

Must not land before L-e: any claim an untrusted-origin import is safe; a default decision beyond own principals; honoring a non-empty condition; weakening profile, pin or export-origin checks; admission in a frontend or on the content pair. The design is AST-level data on streams; the register-VM cutover does not touch it.

**Unverified.** AD-2 lane results (source still has the hook). That `bb test:sub` maps the relocated tests into `yin.vm` by prefix (subsystems.edn suggests yes). That `yin.repl.dht-process-test` needs no edit beyond requires. The Python no-retained-name claim comes from codex and I1, not a run. Nothing was run or edited; the plan file at `/Users/sto/.claude/plans/created-gmt-2026-10-12-14-00-00-gmt-curried-wreath.md` holds this same text.
