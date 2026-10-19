ExitPlanMode is not available in this session, so the design below is the deliverable. It is also saved at `/Users/sto/.claude/plans/created-gmt-2026-10-12-08-00-00-gmt-humming-pelican.md`.

Completed-GMT: 2026-10-12 05:58:41 GMT
Completed-Local: 2026-10-12 12:58:41 +07 (Asia/Bangkok)

**Verdict.** The admission pair stands as accepted in round 2: two ring media beside the link pair, a question as plain data bound to the resolution it was asked about, one interpreter answering, neutral vocabulary, reserved-empty conditions, trust separate from safety, session-wide exposure. F1 and F5 are kept verbatim. Four things change against round 2. Retirement is the half-open interval `base <= k < parked` with absent counters as zero, and descendant origins match on exact origin or `origin + "."`. The host hook is a consuming bounded drain through one shared `admission/drain` with gap recovery, and it rechecks only on a retained pending id. The admission successor rides the link raise and is restored by `carry-served`. The ShiBi contract states the composition-owned cost bound, and AD-0 gets an executable doc-versus-code check. This replaces the round-2 design in full.

## 1. The datums

The admission id is `[link-id serial]`; `serial` is a per-composition counter never reused, so a retired question and a later question for the same link id never share an id.

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

No session or subject travels in the question; the subject is the interpreter's context (section 6). Direct `:name-env` entries, provenance `:composition`, are not asked. Non-DHT sources have no pair. The link response on allow gains `:yin.admit/id`, `:yin.admit/decision :allow`, `:yin.admit/asserters` and `:yin.admit/address`. A refusal copies into the link refusal shape with the answer's reason and open keys. A malformed answer refuses `:yin.admit/malformed-answer`; non-empty conditions refuse `:yin.admit/unsupported-condition`; both are final for that link. A nil `:admission` on a DHT source refuses `:yin.admit/unconfigured`.

## 2. The admission state and the serve round (F1 FIXED, kept)

`link/serve` takes `{:pair :source :dht :admission}` and returns `{:pair :pending :progress? :dht :admission}`.

```clojure
{:pair    {:questions q-medium :answers a-medium :cursor c}  ; c: the composition's answer cursor
 :asked   {[:t0 7] question}        ; the retained question, at most one per pair in v1
 :answers {[[:t0 7] 3] answer}      ; drained, keyed by admission id, first answer wins
 :serial  4}
```

The dht branch of `answer` (link.cljc 392-405) becomes three cases, in this order, replacing the `:admit` call at 397-399:

1. **Asked and unanswered.** `(get-in admission [:asked link-id])` exists and `:answers` has nothing under its id: `::pending`, reported with `:admission <id>` in the pending entry beside `:name` and `:yin.link/id`. No re-resolution.
2. **Asked and answered.** An allow runs `(dht-attempt node declared request (:yin.admit/address asked))` with the retained address (`dht-attempt` already takes an explicit address, link.cljc 330), never a fresh `resolve-name`. A refuse is the body. When the response lands, serve drops the asked entry and its answer.
3. **Not asked.** `resolve-name` as today. `:ok` mints the question with the next serial, appends it to the question medium, records it under `:asked`, and reports `::pending` with `:admission`. A question append counts as `:progress? true` (F3 depends on this). A refused append leaves nothing asked and reports no progress.

**Draining.** One public `(admission/drain admission budget)` returns the successor admission state. It reads the answer medium with `stream/next` from `:pair :cursor`, at most `budget` elements, keeps an answer only if its id is under `:asked` and not already under `:answers`, consumes and drops duplicates and answers for unknown or retired ids, and persists the cursor past everything read. On `:dao.stream/gap` it adopts the recovery cursor the transport returns (ringbuffer.cljc 91-99: the earliest retained position, never an evicted one) and continues within the budget. On `:dao.stream/blocked` or `:dao.stream/end` it stops. Serve calls `drain` with `serve-budget` at the start of each round; the host hook (section 4) calls the same function. An answer evicted before any drain read it is lost and its question stays open until retire; with capacity 1024 and 64 drained per tick this needs 1024 answers between two ticks, a host assembly defect, not an operating condition, as the pair-capacity docstring (link.cljc 43-49) already says.

**Ruling: a question-bound link is never refreshed.** Serve stops at the first pending request (link.cljc 451-472), so at most one question is open per pair, bound to the entry it was minted from until an answer lands or the request is retired. If the retained address fails to load after an allow, the link is refused as today and the asked entry goes with it. The user re-requires; the new link id gets a new question over the head as it then is.

**Registry record (P2-4).** `engine/link-install` (engine.cljc 1390) rebuilds the entry through `module/link-module` (module.cljc 88-98), whose fixed map drops the response's admission keys. The edit: after `module-entry` and before `receive-module`, assoc onto the entry an `:admission` map `{:yin.admit/id :yin.admit/decision :yin.admit/asserters :yin.admit/address}` copied from the response when it carries `:yin.admit/decision :allow`. `spawn-module` inheritance (engine.cljc 1289-1307) and dependency transfer (1396-1403) copy entries whole minus `:bindings`, so the record is inherited and transferred without further edits. Direct `:name-env` entries carry no `:admission` key.

**F1 test.** Publish M1 for `pym.m` under principal B. Import on a reader that declares B but trusts nobody in process, so the question stays open. Publish a new head of B moving `pym.m` to M2 and step the world until the follower installs it. Append an allow for the open admission id. Drive. Assert the response's `:yin.admit/address` is M1's content address, the response's `:manifest` value equals M1's manifest value, the node holds a module record for M1 and none for M2, the installed registry entry and an install child's inherited entry both carry `:admission` with that id and address, and the pending entry's `:admission` id never changed.

## 3. Retirement (F2, fixed: P1-1)

`mint-link-id` (module.cljc 492-498) emits the **current** counter then increments: base counter 0 mints `[:t0 0]`. Child origins are minted the same way (engine.cljc 1287-1288: origin `:t0.<n>` with `n` the current `:origins`, then `(inc n)`). Both are half-open intervals.

**Run descriptor.** `{:origin :t0 :ids [from until] :children [from until]}` with every counter normalized by `(or counter 0)`:

- `:ids` is `[(:id-counter base) (:id-counter parked)]`.
- `:children` is `[(:origins base) (:origins parked)]`.

`base` is `(:base parked)`; `parked` is the VM after `engine/abandon-installs` and the `abandon-link` fold, the `vm` already bound in `abandon-pending` (repl.cljc 1391-1432). `carry-link-identity` (1217-1229) takes the max of the same two counters, so the upper bound is what the base inherits.

**Retire predicate.** `(retire {:pair :admission} run)` returns `{:pair :admission :retired [ids]}`. Walking with `stream/next` from the pair's request cursor, a request with id `[o k]` is retired iff:

- `o = (:origin run)` and `from <= k < until` of `:ids`; or
- for some `m` with `from <= m < until` of `:children`, with `child = (str (name origin) "." m)`, `(name o)` equals `child` or starts with `(str child ".")`.

Never a raw prefix: `"t0.10"` does not start with `"t0.1."`, so `:t0.10` never matches descendant root `:t0.1`. Nested install children (`:t0.4.0`, `:t0.4.0.2`) match through the `"t0.4."` prefix. Each retired request advances the cursor. The first request that is not retired stops the walk, keeping the link.cljc 451 stop-at-first-unanswered rule, so an id outside the run is never skipped. Retire removes every `:asked` entry whose link id was retired and every `:answers` entry under those; a later answer for a retired id correlates to nothing and `drain` drops it. No response is appended for a retired request: the engine already retired the id (`engine/abandon-link`, 1668-1687) and a response would be `:late`.

**Smallest REPL change.** `abandon-pending` computes the descriptor from `(:base parked)` and `vm`, calls `link/retire` on `:link-pair` and `:admission`, and assocs both results on the returned state beside the existing `query/discard-answers`. The docstring sentence "The linker side learns nothing" (1398) becomes "the linker side retires the run's requests". The link policy's abandon runs the same function.

**F2 test (kept).** Open question for `pym.e`, drive stops. `(abandon)`. Require `pym.m`, trusted, on the same session: it completes, and the response medium holds no response for the retired id. Append a late allow for the retired id, drive again: nothing is loaded, nothing printed, the pending set stays empty.

**P1-1 acceptance (new).** (i) Session with absent/zero counters: the first require mints `[:t0 0]`, opens an unanswered admission, `(abandon)`, an unrelated trusted require completes on the same pair. (ii) Nonzero base: two trusted requires first, then a run whose module installs a child that requires another untrusted module (origins `:t0.<n>` and `:t0.<n>.0`); abandon; an unrelated trusted require completes, and `:retired` holds exactly the run's root and child ids. (iii) `retire` over a pair holding requests from `:t0.1`, `:t0.1.0` and `:t0.10` with `:children [1 2]` retires the first two and stops at the `:t0.10` request.

## 4. Late answers and host driving (F3, fixed: P1-2)

All without a clock.

- **In-drive.** `drive-links` (1296-1351) runs `admission/serve` right after `link/serve`, assocs its `:admission` on the state before `vm/run` (so `link-raise` sees it), and the continuation test at 1340 becomes `(or (:progress? served) (:progress? admitted) (:progress? answered))`. With the in-process interpreter one require costs two extra rounds. With none, the drive ends with the link pending, bounded by `link-round-budget` as today.
- **Host recheck.** New public `(recheck-on-answers state)`, a sibling of `recheck-on-load-events` (1943-1964), called beside it by `yin.repl.main/step-all` (main.cljc 1016). It runs `(admission/drain (:admission state) serve-budget)`, **persists the successor** on `state` whether or not anything matched, then calls `recheck-pending` once iff the retained `:answers` map holds a key whose link id is among the pending entries' `:admission` ids, read from `(get-in state [:pending-run :links])`. Otherwise it answers `[state' nil]` with the drained state and no `:checks` change. Irrelevant answers advance the cursor and are gone; a valid answer behind `serve-budget` irrelevant ones is drained on the next tick. With no pending run the hook still drains and answers `[state' nil]`. `recheck-on-load-events` is unchanged.
- **The command path.** `(trust p)` rebuilds the session decision and, when a run is pending, calls `recheck-pending` once.

No deadline: an unanswered question ends only by retire. `:checks` counts rechecks that moved nothing (repl.cljc 1898), so the session link policy keeps its say.

**F3 test (kept).** Session composed with an external admission interpreter (none in process). Require `pym.m`; the initial drive ends pending with `:admission` on the pending entry. `recheck-on-load-events` with unrelated events answers `[state nil]`. Append an allow. `recheck-on-answers` returns text, the next drive loads and links, no input line, no load event.

**P1-2 acceptance (new).** (i) After the initial drive stops, append `serve-budget` answers for unknown ids and then the valid allow; call `recheck-on-answers` alone, at most twice: the require completes, and `:checks` is 0 after the first call. (ii) Gap recovery: a pair built with capacity 4 (`make-pair` gains an optional capacity, default `pair-capacity`); mint the cursor at the oldest end, append six answers for unknown ids then the valid allow; `recheck-on-answers` adopts the recovery cursor on the gap outcome and the require completes within two calls. (iii) `serve-budget` irrelevant answers and no allow, three hook calls: `:checks` stays 0 and the cursor has advanced past all of them.

## 5. Error rollback carries admission (P2-3)

`link-raise` (repl.cljc 1232-1245) adds `::admission (:admission state)` to the exception data beside `::link-pair`. `carry-served` (1249-1258) adds `(::admission d) (assoc :admission (::admission d))`. Because `drive-links` assocs the admission successor on `state` before the `vm/run` that may raise, the carried value is the one after the serve that answered: asked entry dropped, answer consumed, cursor and serial advanced. Both callers of `carry-served` (the eval path and the catch in `recheck-pending*`, 1905-1925) restore it with no further edit. `abandon-pending` does not use `carry-served`; it uses `retire` on the live state.

**Acceptance.** Allow, then an install that raises, then a require of another module: the new question's serial is greater than the failed one's, `:asked` holds no entry for the failed link id, the answer map holds nothing under the failed admission id, and the next drive reports progress only for the new question.

## 6. The ShiBi contract (F4, completed)

`yin.repl.admission` is composed from `{:trusted #{...} :verify f :decision d :decision-medium m}` and answers with `(verify decision context question)`: nil allows, a reason map refuses. The context is composition-owned, built by `admission/context` from the session: `{:yin.admit/subject "ed25519:<hex>" | nil, :yin.admit/session <shell token now; [id revision] once the REPL F2 slice lands>}`. Authorization vocabulary stays in context and policy; the question and the linker carry none.

**Cost bound (dao.shibi.md 53-56).** `verify` runs synchronously inside one serve round on one snapshot; its cost per question is bounded by the composition, and the interpreter answers at most `serve-budget` questions per round. `verify` opens, fetches and waits for nothing; a rule that would need a remote read is a rule the decision snapshot must already hold.

The decision snapshot is the static `:decision` value, or the newest value on a capacity-1 `:decision-medium`, read once per round. Absent snapshot: no answer, the question stays pending. Ended snapshot: refuse `:yin.admit/decision-ended`. Default policy: `decision` is `#{own principal} ∪ :trusted`, allow when some asserter is in it, else `:yin.admit/untrusted-principal` naming the asserters. Policies compose as verify functions, first reason wins. One interpreter writes the answer medium.

This is not the gate seam: it reuses the `verify` signature and the snapshot, but a request read off a stream and answered one decision per request is the path dao.shibi.md 65-70 names as a non-goal for the gate, to be added beside `verify`. This design is that addition.

**F4 acceptance (executable, AD-1).** A recording `verify` asserts it received exactly the snapshot, the context and the question; absent snapshot leaves the question unanswered after a round and reports no progress; ended snapshot refuses `:yin.admit/decision-ended` with no load started; no key of the question or the link request is `:yin.admit/subject`, `:yin.admit/session` or namespaced `dao.shibi`.

## 7. Exposure, trust versus safety (unchanged)

Admission governs entry into a session's module registry; the registry follows the session. The cache hit (module.cljc 552-554) answers a require from the registry without a question; install children inherit entries (engine.cljc 1289-1307); parents receive what children installed (1396-1403); `(lang ...)` preserves the registry (repl.cljc 1799, per the review). One allow authorizes every later require of that module in that session; `(reset)` and `(vm ...)` establish a fresh registry and fresh admission state. Untrust changes only later answers. The interpreter sees names, addresses, asserters and provenance, never bytes; the linker and L-e verify closure origin, store ownership, dependency pins and the primitives profile whatever the answer says. Conditions must be empty.

## 8. The edit list

- **(a) Python policy.** Delete `principal-admission` from publication.cljc (130-148) with the ruling 19 paragraph of its docstring. The `:admit` option and its paragraph leave `link/composition` (link.cljc 147-153, 166). The default `yin.repl.admission` policy answers `:yin.admit/untrusted-principal`.
- **(b) Tests.** `foreign-principals-are-refused-until-l-e-test` (dht_import_test.cljc 111-136) moves to `test/yin/repl/admission_test.cljc` as `an-untrusted-principal-is-refused-by-the-default-interpreter-test`. `foreign-principal-is-refused` (454-474) becomes `an-untrusted-principal-is-refused-on-every-vm`; an allow case trusts B. `run-over-dht` (281-311) mints `(admission/make-state)` beside `make-pair`, passes `:admission` to `link/serve`, runs `admission/serve` on the result with the source's policy, and carries both successors (F5, kept). `source` (314-320) composes `:admission {:trusted #{pa}}`.
- **(c) Docs.** yang.antlr.md 8.5.6: ruling 19 keeps its safety text plus one sentence that trust is the admission interpreter's; the L-e row blocks "exposure of untrusted-origin closures"; the I3 landing note and gap paragraph point to section 7.5. yin.vm.linker.dht.md: 7.4 unchanged except the mob Q4 fix; 8.2 gains the `recheck-on-answers` hook beside R11.
- **(d) REPL.** `make-session` (732-770) mints the admission state beside the link pair; `create-state` takes `:admission` beside `:link-policy`, defaulting to the own key from `:dht-key`; `drive-links`, `abandon-pending`, `link-raise`, `carry-served`, `recheck-on-answers`, `(trust p)`, `(untrust p)`, `(trusted)`; the `:admission` entry key in `link-install`; `make-pair` optional capacity.
- **(e) Spec home.** New section 7.5 "Admission: the trust question" in yin.vm.linker.dht.md: both datums, the state value, the binding rule, the retire interval and prefix rule, the drain and gap rule, the recheck hook, the cost bound, the absent/ended rules, the fail-closed reasons. One paragraph in dao.shibi.md after line 70 naming this interpreter as the addition beside `verify`.

## 9. Slices

**The parked linked-REPL tests are LOST.** `collab/1792201000000-parked-linked-repl-tests.cljc.txt` is not on disk and was never committed. AD-3 rewrites them from the acceptance lines below (F2, F3, P1-1, P1-2, P2-3), not from memory of the parked file.

- **AD-0 docs.** Section 7.5, edits (c) and (e). Architect or writer seat. Acceptance, executable: a portable test `test/yin/repl/admission_doc_test.cljc` (the read-text! helper pattern of dht_test.cljc 78-86) reads section 7.5 and asserts that every keyword in `admission/reasons` (a public set the code refuses from) appears in the section text, that the section names `serve-budget` and `pair-capacity` with the values the vars hold, and that the retire rule text contains the literal `origin + "."`. It fails when doc and code diverge; it lands with AD-1 since it needs `admission/reasons`.
- **AD-1 seam.** `yin.repl.admission` with `make-state`, `context`, `serve`, `drain`, `reasons`; admission state through `link/serve`; `link/retire`; `:admit` removed; the `link-install` admission record; `make-session` default state; nil `:admission` refuses `:yin.admit/unconfigured`. Linker engineer. Portable tests in `admission_test.cljc` on hand-made questions and media. Acceptance: a trusted asserter's module imports on every VM with the admission record and address in the installed and inherited registry entries; an untrusted one is refused with no load started; a question append reports progress; an unanswered question survives sixty-four rounds unchanged; a duplicate allow is dropped and the first stands; an answer for an unknown id is consumed and dropped; non-empty conditions refuse `:yin.admit/unsupported-condition`; a malformed answer refuses `:yin.admit/malformed-answer`; `retire` passes P1-1 (i)-(iii); `drain` passes P1-2 (ii) gap recovery at the seam; the F4 executable acceptance; the F1 head-change test.
- **AD-2 Python.** (a) and (b). Compiler engineer, coordinated with the yang.python orchestrator through collab. Acceptance: no `:yang.python.antlr/foreign-principal` remains; `run-over-dht` runs both interpreters; the published DHT artifact is unchanged; the F1 test lives here because it needs a second head.
- **AD-3 REPL.** (d), `recheck-on-answers`, the trust commands, the rewritten linked-REPL tests in `test/yin/repl/admission_repl_test.cljc`. VM engineer. Acceptance: `(trust p)` then an import of p's module completes in the same session; `(untrust p)` refuses the next question of a new session and leaves the cached module usable; the F2 abandon test; P1-1 (i) and (ii) through `(abandon)`; the F3 late-answer test; P1-2 (i) and (iii) through the host hook; the P2-3 rollback acceptance; `(trusted)` prints own plus trusted; `(reset)` drops the trusted set unless given in `create-state`.

Order: AD-1 (with AD-0's test), then AD-2 and AD-3 in either order.

**Must not land before L-e.** Anything that claims untrusted-origin imports are safe; a default decision other than own principals; honoring any non-empty condition; a weakening of profile or dependency-pin discharge (linker.cljc 1020-1075) or export-origin validation (engine.cljc 1347-1383); admission on the content pair or in any frontend. The register-VM cutover is unaffected: everything here is AST-level data on streams.

## 10. What I would not do

No refresh of a bound question in v1. No retired-id set beyond dropping `:asked`. No timer or deadline in `recheck-on-answers`. No re-answer protocol for an evicted answer. No peeking read anywhere: every read persists its cursor.

## Unverified

- The parked tests file is absent; I could not read it.
- `(lang ...)` preserving the registry (repl.cljc 1799), linker.cljc 1020-1075 and engine.cljc 1347-1383 are taken from the codex review, not re-read.
- The provenance key set beyond `:yin.module/asserted-by`.
- Which head event the follower emits when an installed principal's head moves; the F1 test may need `:confirmed` or `:installed` stepping.
- That `(:installs vm)` holds no child origin the `:origins` counter misses; the rule relies on engine.cljc 1287-1288 alone.
- Whether `medium` in link.cljc accepts a capacity other than `pair-capacity` without further plumbing (ringbuffer.cljc 24 takes one; `medium` itself was not read).
