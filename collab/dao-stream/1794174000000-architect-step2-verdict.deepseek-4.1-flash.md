Created-GMT: 2026-11-09 20:10:00 GMT
Created-Local: 2026-11-10 03:10:00 +07
Model: deepseek-4.1-flash
Role: Lead System Architect
Session-Type: read-only
Worktree: /Users/sto/workspace/datomworld-yin-repl-dht
Branch: yin-repl-dht-composition
Review-Target: Step 2 Remediation (Findings 1-6 from previous review)

# Lead Architect Re-Review: Step 2 Remediation — Final Verdict

## Finding 1: Dependency Closure (§2) — **SATISFIED**

**Evidence:**
- `yin.vm.linker.pair` extracted as separate namespace at `src/cljc/yin/vm/linker/pair.cljc`
- `yin.repl.cljc:39` imports `yin.vm.linker.pair`, not `yin.vm.linker.interpreter`
- `yin.vm.linker.pair` has zero executable DHT imports (only documentation references)
- `yin.vm.linker.interpreter:35,38` retains DHT imports (`dao.space.dht`, `yin.vm.linker.dht`)
- Engine tree (`yin.repl`, `yin.repl.index`, `yin.repl.query`) has no executable `dao.jing.dht`, `dao.space.dht`, or `yin.vm.linker.dht` dependencies

**Verification:** Gate A audit (§10) passes — the engine's dependency closure stops loading DHT through `yin.vm.linker.interpreter`.

---

## Finding 2: In-Engine HEAD Execution (§3, §4) — **SATISFIED**

**Evidence:**
- `service-local!` removed from `yin.repl.index` (no matches found)
- `yin.repl.index/emit-publication` (line 580) emits pure data request `{:op :publish :id :round :manifest}` via `publication/emit!`
- Frontier promotion occurs only in `accept-publication-result` (line 509) upon matching `{:op :publication-result ...}`
- `yin.repl.publication/engine-media` (line 134) strips `:local` durable HEAD writer from engine's view
- `service-local!` exists in `yin.repl.publication` (line 94) but is documented as "the composition's call, never the engine indexer's"

**Verification:** Engine emits request and awaits result; composition services the request. Ordering contract preserved.

---

## Finding 3: Query Deferral Wiring (§6, §7) — **SATISFIED**

**Evidence:**
- `yin.repl.cljc:1026` extracts `:registered-ops (query/registered-ops host-modules)` at construction
- `yin.repl.cljc:1519` passes `:registered-ops (:registered-ops state)` to `query/serve`
- `yin.repl.query/registered-ops` (line 151) extracts `:ops` from host module registrations
- `yin.repl.query/foreign-request?` (line 622) checks if request is registered composition operation
- `yin.repl.query/serve` (line 667) defers foreign requests with `(done request)` returning `:deferred`
- Unregistered operations refused with `:dao.stream.apply/unknown-operation` (line 585)

**Verification:** Registered composition queries deferred; unregistered foreign ops fail correctly.

---

## Finding 4: Parked VM Resumption (§7) — **SATISFIED**

**Evidence:**
- `yin.shell.session/recheck-on-tick` (line 109) calls `(repl/recheck-pending engine nil true)` when `:progress?` is true
- `yin.repl/recheck-pending` (line 2203) has 3-arity accepting `initial-progress` parameter
- `recheck-pending*` (line 2159) passes `initial-progress` to `drive-links`
- `drive-links` (line 1491) accepts `initial-progress` and uses it to initialize loop state (lines 1497-1498)
- When `initial?` is true, first iteration skips linker round and runs VM immediately (line 1510, 1528-1531)

**Verification:** Composition can pass `initial-progress? true` to promptly wake parked VM.

---

## Finding 5: Pending Publication Queueing & Serialization (§3, §4) — **SATISFIED**

**Evidence:**
- `:pending-publications` queue (vector) holds outstanding requests in `yin.repl.index`
- `emit-publication` (line 599-601) appends to queue with `(conj (:pending-publications ix) {:request request :promotes promotes})`
- Each entry carries its own `:promotes` frontier — consecutive commits don't clobber
- `settle-publication` (line 547) consumes results "oldest first" via recursion
- `accept-publication-result` (line 509) matches by `:id`, `:round`, `:manifest` — only correct request settled
- `advances?` (line 471) prevents out-of-order results from regressing frontier
- `pending-publication` (line 560) returns oldest outstanding request

**Verification:** Sequential settlement without clobbering; queue preserves independent frontiers.

---

## Finding 6: Signed Publication Completion (§7) — **SATISFIED**

**Evidence:**
- `yin.shell.query/link-answer` (line 324) documented: "`publish` awaits that publication's completion rather than reporting success over a request nobody has serviced"
- `outstanding-publication?` (line 315) checks `repl.index/pending-publications` for pending round
- Lines 351-359: Already-committed publish checks `outstanding-publication?` — if true, answers `parked`; otherwise answers from settlement
- Lines 420-427: New publish with outstanding publication stores answer in `awaiting` and returns `parked`
- Lines 429-432: No outstanding publication — answers immediately with refusal or `ok`

**Verification:** `link-answer` awaits publication completion; no premature `:ok` acknowledgement.

---

# Architectural Determination

## **STEP 2 SIGN-OFF: APPROVED**

All six P1 blockers from the previous review are satisfied. The remediation correctly implements the specification's invariants:

1. **Dependency closure** — engine depends only on `dao.jing`; DHT implementation loaded only through composition
2. **Ownership split** — engine emits publication requests; composition services them; ordering contract preserved
3. **Query deferral** — registered operations deferred to composition; unregistered operations refused
4. **Parked VM resumption** — composition can promptly wake VM with `initial-progress? true`
5. **Publication queueing** — sequential settlement without clobbering; independent frontiers preserved
6. **Signed publication** — awaits local durable-publication completion before acknowledging

The engine now owns no DHT state or dependency. Local synchronous behavior is preserved. The boundary is established per §9 commit sequence exit condition: "Engine owns no DHT state or dependency; local synchronous behavior preserved."

**Next step:** Proceed to Commit 3 (Generic byte-budget enforcement) per the specification's commit sequence.
