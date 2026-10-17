Completed-GMT: 2026-10-12 06:58:00 GMT
Completed-Local: 2026-10-12 13:58:00 +07 (Asia/Bangkok)

**Verdict.** The admission pair stands: two ring media beside the link pair, a question as plain data, one interpreter answering, neutral vocabulary, reserved-empty conditions, trust separate from safety, session-wide exposure. Five things change. The question now binds the resolution it was asked about and the allow applies to that address only. Abandonment reaches the serving composition through one new function the REPL's abandon calls. A late answer wakes the host through a new recheck hook, and admission progress drives the link loop. The ShiBi contract is the three-argument `verify` with a composition-owned context, and this interpreter is an addition beside the gate, not the gate. The portable test driver runs both interpreters. This replaces the earlier design in full.

## 1. The datums

The question carries the link id for correlation and its own id for binding. The admission id is `[link-id serial]`, `serial` a per-composition counter that is never reused, so a retired question and any later question for the same link can never share an id.

```clojure
;; question, appended to the admission request medium at resolution
{:yin.admit/id         [[:t0 7] 3]
 :yin.link/id          [:t0 7]
 :yin.admit/name       'pym.m
 :yin.admit/address    :segment/blake3-...   ; the resolved manifest address, retained
 :yin.admit/asserters  ["ed25519:<hex>" ...] ; every asserter of that entry (R8)
 :yin.admit/provenance {...}                 ; the entry's :yin.link/provenance verbatim
 :yin.link/format      f                     ; the request's own words, no subject
 :yin.link/contract    "b2"}

;; answers, appended to the admission response medium
{:yin.admit/id [[:t0 7] 3] :yin.admit/decision :allow  :yin.admit/conditions {}}
{:yin.admit/id [[:t0 7] 3] :yin.admit/decision :refuse
 :yin.admit/reason :yin.admit/untrusted-principal :yin.admit/asserters [...]}
```

No session or subject travels in the question. The subject is the interpreter's context (section 4). Direct `:name-env` entries, provenance `:composition`, are not asked. Non-DHT sources have no pair. The link response on allow gains `:yin.admit/id`, `:yin.admit/decision :allow`, `:yin.admit/asserters` and `:yin.admit/address`, the address the allow authorized. A refusal copies into the section 9 shape with the answer's reason and open keys. A malformed answer refuses `:yin.admit/malformed-answer`; non-empty conditions refuse `:yin.admit/unsupported-condition`; both are final for that link.

## 2. The admission state and the serve round (F1)

The serving composition carries an admission state value in and out of every round. `link/serve` takes `{:pair :source :dht :admission}` and returns `{:pair :pending :progress? :dht :admission}`.

```clojure
{:pair    {:questions q-medium :answers a-medium :cursor c}  ; c: interpreter's answer cursor
 :asked   {[:t0 7] question}        ; the retained question, at most one per pair in v1
 :answers {[[:t0 7] 3] answer}      ; drained, keyed by admission id, first answer wins
 :serial  4}
```

The dht branch of `answer` (link.cljc 392-405) becomes three cases, in this order, replacing the `:admit` call at 397-399:

1. **Asked and unanswered.** `(get-in admission [:asked link-id])` exists and `:answers` has nothing under its id: `::pending`, reported with `:admission <id>` in the pending entry beside `:name` and `:yin.link/id`. No re-resolution.
2. **Asked and answered.** An allow runs `(dht-attempt node declared request (:yin.admit/address asked))` with the retained address, never a fresh `resolve-name`. A refuse is the body. When the response lands, serve drops the asked entry and its answer.
3. **Not asked.** `resolve-name` as today. `:ok` mints the question from the entry with the next serial, appends it to the question medium, records it under `:asked`, and reports `::pending` with `:admission`. A question append counts as `:progress? true` even though no response landed (F3 depends on this). A refused append leaves nothing asked and reports no progress, like a refused response today.

Draining: at the start of each round serve reads the answer medium from the pair's cursor, at most `serve-budget` elements, and keeps an answer only if its id is under `:asked` and not already under `:answers`. Duplicates and answers for unknown or retired ids are consumed and dropped. The cursor advances past everything read.

**Ruling: a question-bound link is never refreshed.** Serve stops at the first pending request (link.cljc 451-472), so at most one question is open per pair, and it stays bound to the entry it was minted from until an answer lands or the request is retired. If the retained address fails to load after an allow, the link is refused as today and the asked entry goes with it. The user re-requires, and the new link id gets a new question over the head as it then is. `serial` exists so that a retire-and-re-ask, or a later refresh policy, cannot collide ids. The authorized address rides on the response, so the registry records what was allowed.

**F1 test.** Publish M1 for `pym.m` under principal B. Import on a reader that declares B but trusts nobody in process, so the question stays open. Publish a new head of B moving `pym.m` to M2 and step the world until the follower installs it. Append an allow for the open admission id. Drive. Assert the response's `:yin.admit/address` and `:manifest` are M1, the node holds a module record for M1 and none for M2, and the pending entry's `:admission` id never changed.

## 3. Retirement (F2)

New `link/retire`, `(retire {:pair :admission} run)` returns `{:pair :admission :retired [ids]}`. `run` is `{:origin :t0 :after n :origins #{:t0.4 :t0.5}}`. Walking with `stream/next` from the pair's cursor, a request is retired when its `:yin.link/id` is `[o k]` with `o` = `:origin` and `k > :after`, or `o`'s name has one of `:origins` as prefix (children of children are `:t0.4.0`). Each retired request advances the cursor. The first request that is not retired stops the walk, so an id outside the run is never skipped. Retire clears `:asked` entries whose link id was retired and every `:answers` entry under them. A later answer for a retired id correlates to nothing and the drain drops it, so it cannot reactivate anything. No response is appended for a retired request: the engine already retired the id (`engine/abandon-link`, 1668-1687), and a response would be `:late` anyway.

The run descriptor is derivable from counters the REPL already keeps. `carry-link-identity` (repl.cljc 1217-1229) holds the base and parked `:id-counter` and `:origins`, and engine 1287-1288 mints child origins as `:t0.<n>` from the counter. So `:after` is the base VM's `:id-counter`, and `:origins` is `#{:t0.n | base :origins <= n < parked :origins}`.

**Smallest REPL change.** `abandon-pending` (1391-1432) computes that descriptor from `(:base parked)` and the parked VM, calls `link/retire` on `:link-pair` and `:admission`, and assocs the two results on the state it returns, beside the existing `query/discard-answers`. The docstring's "the linker side learns nothing" sentence at 1398 is replaced by "the linker side retires the run's requests". Three expressions and one docstring. The link policy's abandon runs the same function.

**F2 test.** Open question for `pym.e` on a pair, drive stops. `(abandon)`. Then require `pym.m`, trusted, on the same session: it completes, and the response medium holds no response for the retired id. Append a late allow for the retired id, drive again: nothing is loaded, nothing printed, the pending set stays empty.

## 4. Late answers and host driving (F3)

Three pieces, all without a clock.

- **In-drive.** `drive-links` (1296-1351) runs `admission/serve` right after `link/serve`, assocs its `:admission` on the state, and the continuation test at 1340 becomes `(or (:progress? served) (:progress? admitted) (:progress? answered))`. With the default in-process interpreter one require costs two extra rounds: question, answer, then the attempt. With no in-process interpreter the question round reports progress, the next round finds the question unanswered, reports none, and the drive ends with the link pending. Bounded by `link-round-budget` as today.
- **Host recheck.** New public `recheck-on-answers [state]`, a sibling of `recheck-on-load-events` (1943-1964): it peeks the answer medium from the admission pair's cursor without storing the successor, at most `serve-budget` elements, and calls `recheck-pending` once iff some answer's `:yin.admit/id` is in the pending admission ids, read from `(:links parked)` entries' `:admission`. Otherwise `[state nil]`, no `:checks` increment. The host ticker calls it beside `recheck-on-load-events`. `recheck-on-load-events` itself is unchanged.
- **The command path.** `(trust p)` rebuilds the session decision and, when a run is pending, calls `recheck-pending` once, so a user who answers the prompt by trusting sees the import complete without typing anything else.

No deadline is added: an unanswered question survives any number of rechecks and ends only by retire. `:checks` counts rechecks that moved nothing, so the session link policy keeps its existing say.

**F3 test.** Session composed with an external admission interpreter (none in process). Require `pym.m`; the initial drive ends pending with `:admission` on the pending entry. `recheck-on-load-events` with unrelated events answers `[state nil]`. Append an allow on the answer medium. `recheck-on-answers` returns text, the next drive loads and links, no input line was typed, no load event preceded the recheck.

## 5. The ShiBi contract (F4)

The interpreter, `yin.repl.admission`, is composed from a policy `{:trusted #{...} :verify f :decision d :decision-medium m}` and answers with `(verify decision context question)`, nil allows, a reason map refuses. That is the three-argument adapter dao.shibi.md 40-45 fixes. The context is composition-owned and built by `admission/context` from the session: `{:yin.admit/subject "ed25519:<hex>" | nil, :yin.admit/session <shell token now, [id revision] once the REPL F2 slice lands>}`. Authorization vocabulary stays in context and policy; the question and the linker carry none.

The decision snapshot is the static `:decision` value, or the newest value on a capacity-1 `:decision-medium` when composed with one, read once per round. Absent snapshot: the interpreter does not answer and the question stays pending, because a link may wait and a wrong refusal is final. Ended snapshot: refuse `:yin.admit/decision-ended`. `verify` opens, fetches and waits for nothing. The default policy is membership: `decision` is `#{own principal} ∪ :trusted`, `verify` allows when some asserter is in it, else `:yin.admit/untrusted-principal` naming the asserters. Policies compose as verify functions in Clojure, first reason wins. One interpreter writes the answer medium.

**Corrected claim.** This is not the existing seam. It reuses the `verify` signature and the decision-medium snapshot, but a request read off a stream and answered one decision per request is the path dao.shibi.md 65-70 names as a non-goal for the gate, to be added beside `verify` if needed. This design is that addition, outside the middleware gate: the gate may not wait, a link already does. The sentence in section 7.5 and in dao.shibi.md says exactly this.

## 6. Exposure, restated

Admission governs entry into a session's module registry, and the registry follows the session. The cache hit at module.cljc 552-554 answers a require from the registry without a question. An install child inherits the parent's registry entries (engine.cljc 1289-1307), and a parent receives the dependencies a child installed (1396-1403). So one allow in a session authorizes every later require of that module in that session and in every task that inherits the registry; a new session asks anew. "Untrust is not retroactive" means cached requires, inherited entries, existing closures and installed code stay usable; untrust changes only answers to later questions. `(reset)` and `(vm ...)` establish a fresh registry and a fresh admission state. The shell owns that boundary because it owns registry lifetime. Whether `(lang ...)` preserves the registry is taken from the review and not verified here.

## 7. Trust versus safety

Unchanged from the accepted design. The interpreter sees names, addresses, asserters and provenance, never bytes. The linker and L-e verify closure origin, store ownership, dependency pins and the primitives profile whatever the answer says. Conditions are reserved and must be empty.

## 8. The edit

- **(a) Python policy.** Delete `principal-admission` from publication.cljc with the ruling 19 paragraph of its docstring. The policy becomes the default of `yin.repl.admission`, answering `:yin.admit/untrusted-principal` with `:yin.admit/asserters`. The `:admit` option and the `admit` paragraph leave `link/composition` (link.cljc 147-153, 166).
- **(b) Tests.** `foreign-principals-are-refused-until-l-e-test` (dht_import_test.cljc 111-136) moves to `test/yin/repl/admission_test.cljc` as `an-untrusted-principal-is-refused-by-the-default-interpreter-test`, asserting the answer map. `foreign-principal-is-refused` (454-474) becomes `an-untrusted-principal-is-refused-on-every-vm` asserting the neutral refusal; an allow case trusts B. `run-over-dht` (281-311) mints `(admission/make-state)` beside `make-pair`, passes `:admission` to `link/serve`, runs `admission/serve` on the result with the source's policy, and carries both successors. `source` (314-320) composes `:admission {:trusted #{pa}}` and nothing else.
- **(c) Docs.** yang.antlr.md 8.5.6: ruling 19 keeps its safety text plus one sentence that trust is the admission interpreter's; the L-e row blocks "exposure of untrusted-origin closures"; the I3 landing note's `principal-admission` sentence and the gap paragraph point to section 7.5. yin.vm.linker.dht.md: the 7.4 sentence stays with the mob Q4 fix; 8.2 gains the `recheck-on-answers` hook beside R11.
- **(d) REPL.** `make-session` mints the admission state beside the link pair; `create-state` takes `:admission` beside `:link-policy` and defaults it to the own key from `:dht-key`; `drive-links`, `abandon-pending`, `recheck-on-answers`, `(trust p)`, `(untrust p)`, `(trusted)`. The REPL F2 slice supplies the `[id revision]` session key to `admission/context` later; nothing in the question waits on it.
- **(e) Spec home.** New section 7.5 "Admission: the trust question" in yin.vm.linker.dht.md: both datums, the state value, the binding rule, retire, the recheck hook, the fail-closed rules. One cross-reference paragraph in dao.shibi.md after line 70 naming this interpreter as the addition beside `verify`.

## 9. Slices

The parked linked-REPL tests are lost: `collab/1792201000000-parked-linked-repl-tests.cljc.txt` is not on disk and was never committed. AD-3 rewrites them from the acceptance lines below.

- **AD-0 docs.** Section 7.5, the (c) and (e) edits. Architect or writer seat. Acceptance: the section states both datum shapes, the `[link-id serial]` binding rule, the retire walk rule, the absent and ended snapshot rules, and the two fail-closed reasons.
- **AD-1 seam.** `yin.repl.admission` with `make-state`, `context`, `serve`; the admission state through `link/serve`; the drain; `link/retire`; `:admit` removed; `make-session` minting the default state so no REPL path is ever unconfigured; a nil `:admission` on a DHT source refuses `:yin.admit/unconfigured`. Linker engineer. Tests portable on three hosts in `admission_test.cljc` using hand-made questions on media where no DHT is needed. Acceptance: a trusted asserter's module imports on every VM with the admission record and address in the registry; an untrusted one is refused with no load started; a question append reports progress; an unanswered question survives sixty-four rounds with no change; a duplicate allow is dropped and the first stands; an answer for an unknown id is consumed and dropped; non-empty conditions refuse `:yin.admit/unsupported-condition`; a malformed answer refuses `:yin.admit/malformed-answer`; `retire` with the F2 descriptor advances past the run's requests and stops at the first other request; the F1 head-change test passes.
- **AD-2 Python.** (a) and (b). Compiler engineer, coordinated with the yang.python orchestrator through collab. Acceptance: no `:yang.python.antlr/foreign-principal` remains; `run-over-dht` runs both interpreters; the published DHT artifact is unchanged; the F1 test lives here because it needs a second head.
- **AD-3 REPL.** (d), the F3 hook, the trust commands, the rewritten linked-REPL tests in `test/yin/repl/admission_repl_test.cljc`. VM engineer. Acceptance: `(trust p)` then an import of p's module completes in the same session; `(untrust p)` refuses the next question of a new session and leaves the cached module usable; the F2 abandon test; the F3 late-answer test; `(trusted)` prints own plus trusted; `(reset)` drops the trusted set unless given in `create-state`.

Order: AD-1, then AD-2 and AD-3 in either order. Nothing lands before L-e that claims untrusted-origin imports are safe, defaults to anything but own principals, or honors a non-empty condition. The register-VM cutover is unaffected.

## 10. What I would not do

No refresh of a bound question in v1. No retired-id set kept beyond the drain, since discarding `:asked` already makes late answers inert. No timer, poll loop or deadline in `recheck-on-answers`. No admission on the content pair or in any frontend.

## Unverified

- The parked tests file is absent; I could not read it.
- Whether `(lang ...)` preserves the registry, and the review's linker.cljc 1020-1075 and engine.cljc 1347-1383 citations, were not re-read.
- The provenance key set beyond `:yin.module/asserted-by` (authority.cljc 246-247).
- Which head event the follower emits when an already installed principal's head moves; the F1 test may need `:confirmed` or `:installed` stepping until the fold changes.
- That `(:installs vm)` holds no child origin the counter rule would miss; the rule relies on engine.cljc 1287-1288 alone.
