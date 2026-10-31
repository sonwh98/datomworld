Completed-GMT: 2026-10-12 05:35:00 GMT
Completed-Local: 2026-10-12 12:35:00 +07 (Asia/Bangkok)

**Verdict.** Replace the `:admit` function hook with an admission pair on the DHT link source: two ring media beside the link pair, a question as plain data, one interpreter answering. The default interpreter allows the session's trusted principals, which is the importer's own keys unless the user adds more. ShiBi later supplies a different `verify` and a published decision medium, and the linker and Python code never change. The Python namespace loses its admission policy entirely. Trust is decided by the interpreter; safety stays L-e's and the linker's.

## Rulings

**Q1 The boundary.** The DHT link source asks after `resolve-name` answers `:ok` and before any load. Key sets are closed, values are scalars, keywords, symbols, addresses, the id vector, and maps or vectors of those, as the link pair already requires. Direct `:name-env` entries carry provenance `:composition` and are not asked about: the composition is the asserter. Non-DHT sources have no pair.

```clojure
;; question, on the admission request medium
{:yin.admit/id         [:t0 7]              ; the link request's :yin.link/id
 :yin.admit/name       'pym.m
 :yin.admit/address    :segment/blake3-...  ; the resolved manifest address
 :yin.admit/asserters  ["ed25519:<hex>" ...] ; every asserter, R8
 :yin.admit/provenance {...}                ; the entry's :yin.link/provenance verbatim
 :yin.admit/importer   {:yin.link/format f :yin.link/contract "b2"}
 :yin.admit/session    "<shell token or [id revision]>"} ; composition data, optional

;; answers, on the admission response medium
{:yin.admit/id [:t0 7] :yin.admit/decision :allow
 :yin.admit/conditions {}}                  ; reserved; must be empty in v1
{:yin.admit/id [:t0 7] :yin.admit/decision :refuse
 :yin.admit/reason :yin.admit/untrusted-principal
 :yin.admit/asserters ["ed25519:<hex>"]}
```

The link response copies a refusal into the section 9 shape with the answer's reason and open keys. An `:ok` response gains the admission record, `:yin.admit/decision :allow` and `:yin.admit/asserters`. A malformed answer, or one with non-empty conditions, is refused fail-closed as `:yin.admit/malformed-answer` or `:yin.admit/unsupported-condition`. Nothing in the vocabulary says foreign, capability, or L-e.

**Q2 Interpreters.** One interpreter per pair, one writer per medium. It lives in a new `yin.repl.admission` namespace, a sibling of `yin.repl.link`, so `yin.vm.linker*` is untouched. Its contract is the middleware gate's: `(serve {:pair p :verify f :decision d})` reads each unconsumed question, calls `(verify decision question)`, nil allows and a reason map refuses, appends the answer, and returns the advanced pair and a progress flag. The default `verify` is membership: some asserter is in the decision's trusted set. The default decision is the session's own principal plus an explicit `:trusted` set. ShiBi later replaces the static decision value with the newest value on a capacity-1 decision medium its index interpreter publishes, and replaces `verify` with its Datalog query. That is exactly the shape dao.shibi.md already fixes, and the question map is the request ShiBi binds. Composition of policies happens inside one interpreter by composing verify functions in Clojure, first reason wins. Several interpreters writing one answer medium is refused by design. Configuration is per session, never per frontend: `link/composition` takes `:admission {:trusted #{...} :verify f}` for the plain Clojure path, and `create-state` takes the same `:admission` beside `:link-policy` so reset and `(vm ...)` keep it. At the REPL the user runs `(trust "ed25519:<hex>")`, `(untrust ...)`, and `(trusted)`, which rebuild the session's decision value for later questions. Petnames stay local and never travel.

**Q3 Sync and async.** The question is asked at resolution so no bytes of an untrusted module are fetched. The link parks `::pending` naming `:admission [:t0 7]`, which `serve` reports beside `:manifest` and `:principals`. Each later round drains the answer medium into a per-pair map keyed by id before re-attempting, and the answered link proceeds to `dht-attempt`. There is no deadline. The link policy and `(abandon)` end it as they end any pending link, and a late answer is skipped like a late response. In the REPL, `drive-links` runs the interpreter right after `link/serve` in the same drive, so one extra round answers. On exposure: the only path into a task's module registry is the link response, and the install child copies the admission record into the registry entry. The step-2 cache hit is per registry, so a module admitted into one session is exposed only there, and a new session asks a new question. Untrust is not retroactive inside a task, and `(reset)` drops the registry. The shell owns that decision because it owns registry lifetime. The linker owns only the question. Deferred discharge is unchanged, and under L-d a refusal answer is an ordinary refusal while a pending answer stays pending.

**Q4 Trust versus safety.** Confirmed as two layers. The interpreter decides which principals the importer accepts and sees only names, addresses, asserters, and provenance, never bytes. The linker and L-e verify closure origin, store ownership, dependency pins, and the primitives profile at step 5b whatever the answer says. An allow never relaxes L-e. Conditions that bound what a trusted module may touch belong to the answer shape in principle, but v1 reserves the slot empty. Primitive and effect admission stay in L-a's profile match and ruling 19's remainder, because the linker already has that machinery and the interpreter has none.

## The edit

- **(a) Python policy.** Delete `principal-admission` from `publication.cljc` and the ruling 19 paragraph of its docstring. The policy becomes `yin.repl.admission/trusted-principals`, answering `:yin.admit/untrusted-principal` with `:yin.admit/asserters`.
- **(b) Tests.** Move the unit test to `test/yin/repl/admission_test.cljc` as `an-untrusted-principal-is-refused-by-the-default-interpreter-test`, asserting the answer map. In `dht_import_test.cljc`, `source` composes `:admission` and the existing `foreign-principal-is-refused` test asserts the neutral refusal on every VM, renamed `an-untrusted-principal-is-refused-on-every-vm-test`. Add an allow case where the reader trusts the publisher.
- **(c) Docs.** In yang.antlr.md 8.5.6: the ruling 19 paragraph keeps its safety text and gains one sentence that trust is the admission interpreter's. The L-e row loses "foreign-principal imports" as what it blocks and reads "exposure of untrusted-origin closures". The I3 landing note replaces the `principal-admission` sentence and the gap paragraph with a pointer to the admission section. In yin.vm.linker.dht.md the 7.4 sentence stays with the mob Q4 fix, because admission is not 7.4.
- **(d) F2.** The REPL mints the admission pair in `create-state` beside the link pair and stamps `:yin.admit/session`. F2 must provide the `[id revision]` session key and keep the module registry per session. The parked tests return as `admission_repl_test.cljc` once F2 lands.
- **(e) Spec home.** New section 7.5 "Admission: the trust question" in yin.vm.linker.dht.md, with a cross-reference paragraph in dao.shibi.md naming it ShiBi's first consumer shape.

## Slices and risks

- **AD-0** docs: section 7.5, the (c) edits. Architect or writer seat. Acceptance: the section names both shapes and the fail-closed rules.
- **AD-1** seam: `yin.repl.admission`, the pair in `composition`, `serve` parking and draining, default interpreter. Linker engineer. Tests portable on three hosts. Acceptance: a trusted publisher's module imports on every VM with the admission record in the registry; an untrusted one is refused with no load started; an unanswered question survives any number of rounds and ends only by abandon; a malformed answer refuses.
- **AD-2** Python: delete `principal-admission`, re-point the DHT import tests. Compiler engineer, coordinated with the yang.python orchestrator through collab, since that file is its track. Acceptance: no `:yang.python.antlr/foreign-principal` remains and the DHT import artifact is unchanged.
- **AD-3** REPL wiring after F2: `:admission` option, trust commands, parked tests. VM engineer. Acceptance: `(trust p)` then an import of p's module succeeds and `(untrust p)` refuses the next import in a new session.

What must not land before L-e: any claim that untrusted-origin imports are safe, any default other than own principals, and any non-empty condition. The register-VM cutover is unaffected: the design handles names, manifest addresses, and provenance only.

Risks: a pending question blocks the requests behind it in the serve round, as a pending load does today. Trust and declared authority (`:principals`) are now two sets, which users can confuse; the `(trusted)` command exists to show the difference.

I would not use a middleware gate on the content pair, because a gate may not wait and admission is about names, not bytes. I would not build a trust fact stream in v1, add retroactive revocation, or put admission in any frontend.

Unverified: `collab/1792201000000-parked-linked-repl-tests.cljc.txt` is not on disk, so I could not read the parked tests. I did not confirm the exact key set inside `:yin.link/provenance` beyond `:yin.module/asserted-by`.
