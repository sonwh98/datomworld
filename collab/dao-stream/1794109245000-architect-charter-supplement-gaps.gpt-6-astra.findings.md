Completed-GMT: 2026-11-08 13:56:18 GMT  
Completed-Local: 2026-11-08 20:56:18 Asia/Ho_Chi_Minh

**The refactor may start. The amended boundary is: the engine receives a `dao.jing` content handle and explicit service media; composition owns storage selection, durable publication, and every DHT concern.**

Reviewed HEAD `186583d9ebab7c47fc3b338334fb6787e0393a51` against the [approved charter](/Users/sto/workspace/datomworld/collab/dao-stream/1794062607000-architect-refactor-first-ruling-gpt6astra.findings.md). No files edited or tests run. The green nightly status is supplied context, not a result reverified here.

**1. Coupling inventory → dispositions**

Paths below are relative to `src/cljc/`. Locations identify the current code, before relocation.

A literal search finds **nine `dao.jing.dht` references inside `yin/repl/`**, plus one in sibling `yin/repl.cljc`:

| File:line | What it does | Disposition |
|---|---|---|
| `yin/repl/dht.cljc:3` | References the DHT design in module documentation. | **moves to composition** |
| `yin/repl/dht.cljc:520,522,524,527,528,529` | Renders `solo`, `unpublished`, `too-few-peers`, `oversize`, `absent`, and `busy` reasons. | **moves to composition** |
| `yin/repl/store.cljc:37` | Describes the DHT-backed store scheme. | **moves to composition** |
| `yin/repl/store.cljc:126` | Documents the DHT inbound-byte bound. | **moves to composition** |
| `yin/repl.cljc:877` | Describes engine construction of a DHT node. | **dies** from the engine contract; composition documents its own construction. |

That literal inventory is insufficient by itself: most executable coupling uses `dao.space.dht`, `yin.vm.linker.dht`, or `repl.dht` aliases. The following completes the checklist, including ownership and configuration dependencies.

For naming clarity, this supplement uses **`yin.shell.*` as the proposed composition namespace family**, outside `yin.repl`. These are target names, not existing APIs.

| File:line | What it does | Disposition |
|---|---|---|
| [`yin/repl/dht.cljc:55`](/Users/sto/workspace/datomworld/src/cljc/yin/repl/dht.cljc:55), `65–69` | Imports DHT, signed-name linker, head follower/board, and signing services. | **moves to composition**: relocate the composition module to `yin.shell.dht`. |
| `yin/repl/dht.cljc:73–92` | Selects inbound capacity and repair-slot defaults. | **moves to composition**; repair capacity remains measurement-driven. |
| `yin/repl/dht.cljc:94–224` | Parses, validates, reads, and renders `heads.edn`; refuses invalid head startup. | **moves to composition**. |
| `yin/repl/dht.cljc:226–264` | Builds the publisher board and deposits signed head traces; calls `head/deposit!` at `237`, `head/board!` at `253`. | **moves to composition**. |
| `yin/repl/dht.cljc:266–335` | Validates followed principals and restores the follower; `head/follow` at `324`. | **moves to composition**. |
| `yin/repl/dht.cljc:337–379` | Composes follower/publisher state, starts pinned hydration (`360`), obtains DHT store (`366`), wraps HEAD with announce/deposit (`372–375`), and returns `:dht`. | **moves to composition**. Only the generic content handle and recovery data cross inward. |
| `yin/repl/dht.cljc:367–371` | Hardcodes publication branching factor `64` as a message-size workaround. | **dies** when generic byte-bounded publication activates; do not relocate it as permanent policy. |
| `yin/repl/dht.cljc:389–440` | Opens durable storage, joins transport (`422`), chooses bind implementation (`429`), and cleans up failed assembly (`439`). | **moves to composition**. |
| `yin/repl/dht.cljc:443–448` | `without-runner` removes the node from a mixed store/node handle. | **dies**. Construct separate engine and composition values initially. |
| `yin/repl/dht.cljc:451–507` | Accesses follower; stops dials/board; checks stopped state; performs final board servicing. Calls at `472,476,487,502–503`. | **moves to composition**. |
| `yin/repl/dht.cljc:511–640` | Renders DHT publication/load/head events, acknowledgements, failures, and join tokens; includes `ack-peers` at `525` and event decoding at `577,585,622`. | **moves to composition**. |
| `yin/repl/dht.cljc:642–725` | Serves/steps the head board, interprets bind events, and renders board lifecycle. Calls at `651,683,688`. | **moves to composition**. |
| `yin/repl/dht.cljc:728–745` | Reads hydrated datoms (`734`), persists local HEAD (`735`), deposits trace, and directly replaces engine index state. | Loading/persistence **moves to composition**; installation **becomes a dao.jing-generic call** carrying validated recovery data. |
| `yin/repl/dht.cljc:748–897` | Reconnects/dials followers, attaches handles, persists accepted heads before installation, retries waiting candidates. Calls at `759,801,811,813,816,863,875,885`. | **moves to composition**. |
| `yin/repl/dht.cljc:900–907` | `linked-registry` derives `{module-name manifest-address}` from a VM registry. | **becomes a dao.jing-generic call**: keep the pure registry projection in a neutral module helper; remove the DHT dependency used to reach it. |
| `yin/repl/dht.cljc:910–1027` | Resolves moved names, compares installed heads, steps followers, and filters source-loss/confirmation/install events. Calls/accesses at `914,931–936,993,1003,1013,1018`. | **moves to composition**. Following changes the resolver, not the local session index. |
| `yin/repl/dht.cljc:1031–1117` | Steps transport (`1044`), decodes bind/load/refusal state (`1048,1060,1071–1072`), gates hydration, reports busy state (`1101`), and prints followed heads (`1117`). | **moves to composition**; engine receives generic completions and explicit recovery installation. |
| `yin/repl/dht.cljc:1120–1156` | Renders DHT startup banner and configuration warnings. | **moves to composition**. |

Engine, index, and query touchpoints:

| File:line | What it does | Disposition |
|---|---|---|
| [`yin/repl.cljc:22`](/Users/sto/workspace/datomworld/src/cljc/yin/repl.cljc:22) | Requires `yin.repl.dht`. | **dies**. |
| `yin/repl.cljc:249–258,844–897` | DHT CLI/help and construction documentation, including key/follower configuration. | DHT-specific text **moves to composition**; engine documentation describes generic injected services. |
| `yin/repl.cljc:775,792–793` | Constructs the linker; obtains publication options and an `:after-publish` callback from the store. | **becomes a dao.jing-generic call**: injected linker media, generic policy, publication request/result media. The callback bridge dies. |
| `yin/repl.cljc:922,930–965` | Selects store implementation; passes `:dht?`, principals, key and follow configuration; calls `followed-principals`/`open`. This is the charter’s `942` region. | **moves to composition**. Engine construction accepts a prepared handle and service endpoints. |
| `yin/repl.cljc:977–992` | Strips runner, retains node, store spec, recovery, and signing key in engine state. | Node/spec/key ownership **moves to composition**; `:dht` and `:dht-key` engine fields **die**. Recovery remains generic. |
| `yin/repl.cljc:1279,1293` | Carries/restores DHT successor through evaluation failure. | **moves to composition**; engine rollback retains generic settlement identities/cursors only. |
| `yin/repl.cljc:1303–1307` | Builds service context including DHT signing key. | Key-bearing context **moves to composition**; requesting round/module/primitive data remains explicit request data. |
| `yin/repl.cljc:1357–1376` | Calls linker with node; calls query service with node; installs both successors. | **moves to composition**. Engine drives/consumes generic link and call streams. |
| `yin/repl.cljc:2069–2075` | Advances linker/node on ticks and compares node successors. | **moves to composition**. |
| `yin/repl.cljc:2085–2091` | Filters `:dao.space.dht/event` and `:yin.head/event` against parked manifest/principal waits. | **moves to composition**; engine receives correlated generic readiness/completion events. |
| [`yin/repl/index.cljc:140–166`](/Users/sto/workspace/datomworld/src/cljc/yin/repl/index.cljc:140), `419–450` | Stores/calls `after-publish`, then promotes the published frontier. | **becomes a dao.jing-generic call**: prepare publication, emit request, promote only upon matching publication success. |
| `yin/repl/index.cljc:368–377,402–412` | Compares manifest count cap with `publish-opts`; restores and publishes reused trees. | **becomes a dao.jing-generic call** using explicit immutable publication policy. Preserve count-cap comparison; add byte-policy compatibility and restore threading. |
| `yin/repl/index.cljc:490–525` | Commits signed name-envelope data and publishes it, currently including synchronous HEAD callback. | **becomes a dao.jing-generic call**. Composition supplies envelopes; indexer records them without signing, resolving, or announcing. |
| `yin/repl/index.cljc:13,276,493` | DHT-design references in otherwise generic storage documentation. | DHT references **die**; document the generic materialization/publication guarantees. |
| [`yin/repl/query.cljc:66,73,78–79`](/Users/sto/workspace/datomworld/src/cljc/yin/repl/query.cljc:66) | Imports DHT, REPL DHT helpers, signed-name linker, and head services. | **moves to composition**. |
| `yin/repl/query.cljc:53–61,93–142,177–230,235–248` | Defines and automatically activates `dao.space.dht`, `yin.link`, and `yin.head` host modules. | **moves to composition**. Inject module registrations through a generic mechanism; retain ordinary `dao.space.query` locally. |
| `yin/repl/query.cljc:648–745` | DHT status/refusal conversion and load/status/retry/cancel/query/module operations. Calls at `654,708–731`. | **moves to composition**. |
| `yin/repl/query.cljc:748–866` | Dispatches head/link operations, derives linked modules (`764`), resolves names (`792`), publishes signed names (`809`), commits envelopes (`818`), reads heads/moves (`860–863`). | DHT services **move to composition**; neutral registry projection and envelope commit use generic calls. |
| `yin/repl/query.cljc:874–925` | Owns a DHT node in `serve`, dispatches remote services, returns node/indexer successors. | Remote dispatch **moves to composition**. Local query serving retains bounded request/response semantics without node state. |

CLI, startup, and lifecycle touchpoints:

| File:line | What it does | Disposition |
|---|---|---|
| [`yin/repl/store.cljc:9,28,35–181`](/Users/sto/workspace/datomworld/src/cljc/yin/repl/store.cljc:35) | DHT scheme parsing, datagram validation, defaults, Jolt refusal, and checked specs. | **moves to composition**, into the store-selection factory. |
| `yin/repl/store.cljc:184–199` | Opens memory/file stores and redirects DHT opening. | Factory **moves to composition**; the DHT redirect **dies**. |
| `yin/repl/store.cljc:202–216`; `yin/repl.cljc:1846,1904` | Uses concrete durable-directory state to choose reset behavior and close resources. | **becomes a dao.jing-generic call** for reset policy; resource ownership **moves to composition**. Supply reset-retention policy explicitly rather than deriving it from `:durable-dir`. |
| [`yin/repl/state.cljc:1–27,44–69`](/Users/sto/workspace/datomworld/src/cljc/yin/repl/state.cljc:1) | Saves DHT flags, distinguishes one-shot flags, parses trust principals. | Saved configuration **moves to composition**. Neutral principal parsing may move to the existing signing/admission layer; engine trust commands must not import CLI state. |
| [`yin/repl/main.cljc:28`](/Users/sto/workspace/datomworld/src/cljc/yin/repl/main.cljc:28) | Requires REPL DHT composition. | **moves to composition** with the host entrypoint. |
| `yin/repl/main.cljc:78–350,375–497,526–631` | Peer/follow/principal/key/token parsing, DHT subcommands, store specs and flags. | **moves to composition**. |
| `yin/repl/main.cljc:634–656,821–889` | Constructs engine with DHT options, loads key, injects WebSocket/head persistence, prints followed heads (`886`). | **moves to composition**; construction hands only generic inputs inward. |
| `yin/repl/main.cljc:720–780,942–989` | DHT help and banners; `banner` call at `969`. | **moves to composition**. |
| `yin/repl/main.cljc:933–934` | Closes head services and store. | **moves to composition** under one resource owner. |
| `yin/repl/main.cljc:1074,1077,1080,1115,1134` | Steps DHT, handles refusal/admission, keeps ticker busy, chooses exit status. | **moves to composition**. |
| `yin/repl/main.cljc:1195,1205,1218,1232,1274,1521,1659` | Steps/stops/drains DHT services across shared, Node, and Dart shutdown paths. | **moves to composition**. |

The search found no additional executable DHT references in the other files under `src/cljc/yin/repl/`.

**Necessary dependency follow-through:** moving these calls is insufficient if the engine still imports the current [`yin.vm.linker.interpreter`](/Users/sto/workspace/datomworld/src/cljc/yin/vm/linker/interpreter.cljc:35), which itself imports `dao.space.dht` and `yin.vm.linker.dht`. Split its generic pair/retirement/admission machinery from DHT serving, or move ownership of that interpreter entirely to composition. Its current `round [linker node]` interface cannot remain an engine dependency.

**2. Signature-level contract**

“Only `dao.jing`” constrains the **storage implementation dependency**. It does not remove the engine’s legitimate `dao.space` indexing/query or `dao.stream` dependencies.

The current `dao.jing` surface is a content store, not a publication service. **There is no existing `dao.jing/submit-publication` or recovery-install function.** HEAD and recovery must not be disguised as existing Jing APIs.

These are the actual content-facing signatures approved for the engine and its indexing helpers:

| Existing entry point | Contract |
|---|---|
| [`dao.jing/materialize!`](/Users/sto/workspace/datomworld/src/cljc/dao/jing.cljc:365) `[handle payload]`, `[handle payload opts]` | Canonically encodes once, hashes those bytes, stores them, returns the segment address after successful insertion/presence verification. `opts` supports `:algorithm`. |
| `dao.jing/get [handle address not-found]` (`435`) | Returns hash-verified, decoded content or the identical supplied sentinel. Invalid address or corrupt content throws. |
| `dao.jing/observer-state [members]` (`472`) | Creates explicit intake state from `{:stream … :cursor …}` members. |
| `dao.jing/observe-step! [handle state]` (`523`) | Materializes at most one payload; returns successor state, stream signal and, on success, address. A throwing write does not advance its cursor. |
| `dao.jing/adopt-cursor [state index cursor]` (`512`) | Explicit cursor adoption only; no implicit recovery or cursor arithmetic. |
| `dao.jing/segment-key [v]`, `[v opts]` (`175`) | Derives content address independently of backend. |
| `dao.jing/segment-address? [x]` (`223`), `segment-matches? [address payload]` (`255`) | Generic address and payload validation. |
| `dao.jing/canonical-bytes [v]` (`153`) | Canonical encoding for generic sizing/verification where required. |
| `dao.jing/close! [handle]` (`457`) | Existing generic lifecycle operation; **composition owns its invocation** for an injected store. |

The underlying handle has these actual signatures:

```clojure
{:put-bytes-fn (fn [address bytes] ...)       ; => :inserted | :present, or throws
 :get-bytes-fn (fn [address not-found] ...)   ; => bytes | identical not-found
 :close-fn     (fn [] ...)}                  ; optional
```

Every replacement backend must provide immutable, address-consistent snapshots; idempotent insertion; immediate read-after-success; unchanged not-found identity; and explicit failure. It cannot overwrite content, return queued network work as insertion success, or require the engine to step transport before a successful local write becomes readable. Retained content must remain available for the lifetime of live index references.

Persistence is a declared property: memory retains content for its lifetime; a persistent backend must satisfy its crash-durability promise. Network replication is a separate outcome. `materialize!` verifies `:present` by reading and comparing bytes; it trusts an `:inserted` report, so publication’s explicit read-back remains necessary.

Existing semantic operations above Jing remain:

| Existing signature | Role |
|---|---|
| `dao.space.transactor/publish! [log]`, `[log opts]` (`transactor.cljc:304`) | Builds/emits an index publication into intake. |
| `dao.space.index/read-manifest [content-store manifest-address]` (`index.cljc:306`) | Fetches and validates a manifest. |
| `dao.space.index/read-datoms [content-store manifest-address]` (`332`) | Walks EAVT through `jing/get`; **does not alone validate all four index closures**. |
| `dao.space.index/restore [candidate {:keys [content-store intake mode schema ref-type verify?]}]` (`1344`) | Restores index state. Current signature lacks byte-policy input; that is required implementation work. |
| `yin.repl.index/rehydrate [indexer {:keys [manifest datoms]}]` (`189`) | Installs recovered history and derives allocator authority from it. |
| `yin.repl.index/commit-names [indexer signed round]` (`490`) | Records supplied envelope/proof facts as one transaction. |

The **new seam to implement**, distinct from those existing signatures, is:

```clojure
;; Proposed engine construction inputs, extending create-state's options:
{:index-store        jing-handle
 :publication-policy {:branching-factor n
                      :max-payload-bytes b} ; nil means no declared byte ceiling
 :publication-out    request-writer
 :publication-in     result-reader
 :publication-cursor minted-result-cursor
 :recovery           {:manifest address-or-nil :datoms datoms-or-nil}
 :retain-index-on-reset? boolean
 ;; Generic link/call media and module registrations supplied separately.
 }

;; Publication request, emitted only after content/read-back succeeds:
{:op :publish
 :id id
 :round round
 :manifest manifest-address}

;; Matching local publication result:
{:op :publication-result
 :id id
 :round round
 :manifest manifest-address
 :status :published              ; or :refused, with :reason
 :durability :persistent}        ; :memory for the ephemeral composition
```

Use existing `dao.stream/append! [handle value]` and `dao.stream/next [handle cursor]` for transport of these records. IDs must be unique within the session and survive evaluation rollback. Stream append success means **request accepted**, not publication completed.

Add explicit engine operations, with these proposed signatures:

```clojure
yin.repl/accept-publication-result [state result] => state'
yin.repl/install-recovery [state {:keys [manifest datoms]}] => state'
```

Their required behavior:

- Retain the prepared frontier separately. Promote manifest, published count, and verified cursor only for its matching successful result. Refusal preserves the prior published frontier; duplicate/stale results cannot promote another round.
- Preserve synchronous local completion at the composition’s public evaluation boundary: drive engine → service local publication → deliver result before reporting that round settled. Do not wait for network acknowledgement, and do not introduce a callback into the engine.
- Install recovery before evaluation is admitted. Composition validates the manifest and all four reachable indexes, following the existing [`validated-snapshot`](/Users/sto/workspace/datomworld/src/cljc/dao/space/store.cljc:192) behavior. Installation derives entity/transaction authority from history and discards saved live cursors.
- Keep policy immutable for a live publication context. A changed or unknown byte policy requires closure revalidation/reconstruction before reuse. The existing manifest remains exactly `{:indexes … :count … :branching-factor …}`.

Composition adds store opening/locking, recovery validation, atomic durable HEAD replacement, signed-head deposit/following, announce, hydration, repair, transport stepping, rendering, and shutdown. Its ordering remains:

**materialize/read-back → durable HEAD success → announce/deposit**

A dissemination failure must not erase an already successful local durable publication. An ordinary followed head changes external resolution; only explicit pinned hydration installs session recovery. Transport derives the effective byte capability; `dao.space` implements semantic page cuts.

**3. Two swappability acceptance gates**

**Gate A — zero storage-implementation references.** Run from repository root:

```sh
if rg --hidden --no-ignore -n -F -- 'dao.jing.dht' \
    src/cljc/yin/repl/ src/cljc/yin/repl.cljc
then
    exit 1
else
    test "$?" -eq 1
fi
```

This includes the requested tree **and its sibling engine file**, checks comments/docstrings too, and fails on a search error. No exclusion for a “thin shim”: DHT composition must leave the tree.

The same review must reject indirect evasion. Run this companion audit and require no implementation dependencies:

```sh
rg -n \
  'dao\.space\.dht|yin\.repl\.dht|yin\.vm\.linker\.(dht|head)|:dht\b|--dht-' \
  src/cljc/yin/repl/ src/cljc/yin/repl.cljc
```

The engine’s dependency closure must also stop loading the DHT implementation through `yin.vm.linker.interpreter`. Moving imports into another engine-owned helper does not pass.

**Gate B — the engine suite passes with explicitly injected non-DHT storage.**

Use a common test constructor/composition fixture, with two backend selections:

- Memory: existing `dao.jing.mem/create-content-mem []`.
- File: existing `dao.space.store/open [dir]`, which wraps `dao.jing.file/create-content-file [path]` and supplies locking, HEAD and validated recovery.

The current injection point already exists:

```clojure
(repl.frontends/create-state {:index-store handle ...})
```

For the refactor, the fixture passes the pure byte handle plus the new policy/recovery/media inputs separately. It owns the publication interpreter: memory acknowledges local completion without claiming persistence; file calls the existing `:head-fn [manifest-address]`, then emits success or refusal. It pumps local request/results and closes resources. It constructs **no DHT node, socket, follower, or repair scheduler**.

Parameterize the retained engine suite over this fixture; do not merely rely on default memory construction. Give linker tests the explicit local `:content-store`/`:name-env` arrangement already used at [`require_test.cljc:134`](/Users/sto/workspace/datomworld/test/yin/repl/require_test.cljc:134).

The decisive subset is:

| Proof | Existing fixtures to retain/adapt |
|---|---|
| Evaluation and all supported VM paths | `yin.repl-test`; `yin.repl.index-test/every-evaluator-is-indexed-the-same-way`; AST index/query tests. |
| Publication through the injected handle | `index_test.cljc:146,269,316,344,362,522,576,592`. |
| Frontier compatibility and failure | `index_test.cljc:385,421,553,734`: stale/foreign cursors, unreadable dirty content, HEAD failure, and read-back ordering. |
| Recovery and continued allocation | `index_test.cljc:470,501`; file fixtures at `store_test.cljc:1009,1217,1372`. |
| Corruption/refusal, reset and lifecycle | `store_test.cljc:833,851,874,976,1056–1121,1298,1425`. Preserve intended memory/file reset differences. |
| Local linking and parked-work causality | `require_test.cljc:244,263,346,437,696,740,766`. Move its explicitly DHT-specific construction test at `805` to composition tests. |
| Query settlement survives rollback | `query_test.cljc:283,314,663`, including the guarded slow body. |

Add narrow tests for the genuinely new protocol: delayed/failed publication results do not promote the frontier; duplicate/wrong-round results cannot settle it; rollback does not replay a settled service request. Also start a fresh engine-only JVM test process and assert that the DHT namespaces were not loaded.

Require the **whole retained engine suite**, including this subset, on JVM/Node/Dart, with guarded bodies enabled (`DATOM_SLOW_TESTS=1` where applicable). Record actual executed tests, not only namespace loading.

DHT-specific tests move with composition; they are not deleted or counted as the non-DHT proof. The boundary merge still requires their existing assertions—including eight distinct acknowledged publications, the twenty-round/400-tick idle fixture, head-follow persistence, bind/hydration refusal, and shutdown—to pass on the affected host matrix.

**4. Adapter dispositions**

| Adapter | Target composition-side interface |
|---|---|
| **Linker progression** | Proposed `serve-links [composition budget] => {:composition … :progress? …}` consumes the existing link request/response media. Composition owns the current `link/round [linker node]` successor; the engine sees correlated decisions and generic pending-work identities, never the node. |
| **Query services** | Proposed `serve-services [composition budget] => {:composition … :progress? …}` interprets externally registered `dao.space.dht`, `yin.link`, and `yin.head` calls through `dao.stream.apply` envelopes. Local `dao.space.query` stays engine-side; signed publication requests a generic envelope commit and awaits local publication completion. |
| **Rollback state** | Service cursors, settled request identities, linker successor, and transport state remain in composition across VM rollback. Generic retirement/abandon records identify the run; engine rollback preserves its minted identities and consumes existing settlements without replaying side effects. |
| **Event filtering** | Proposed `completion-events [composition events] => [generic-event …]` translates load/head outcomes to correlated request or dependency identities. Engine rechecks only matching parked work, ignores unrelated events, and resumes each completion once; DHT/head keywords never cross inward. |

These are explicit stream interpreters, not injected callbacks or an opaque “runner” whose internal state the engine still manipulates.

**ETA: plan eight working days; plausible range six to twelve, moderate confidence.**

This follows the charter’s sequence, including its storage and repair work—not just namespace relocation.

| Commit/review step | Working days | Exit condition |
|---|---:|---|
| 1. Pin baseline contracts and parameterize non-DHT fixtures | 0.5–1 | Existing invariants green; new publication-result expectations explicit. |
| 2. Separate engine/composition and extract the four adapters | 1.5–2.5 | Engine owns no DHT state or dependency; local synchronous behavior preserved. |
| 3. Generic byte-budget enforcement across bulk, incremental, restore and reuse | 1.5–3 | Oversize refusal, terminating branch construction, policy compatibility and unchanged manifest proved. |
| 4. Wire durable/DHT compositions, CLI, recovery and shutdown | 1–1.5 | HEAD ordering, hydration admission and resource ownership preserved on every host. |
| 5. Measure and tune repair scheduling | 0.5–1.5 | Fresh writes progress; queues bounded; acknowledgement and ticker-idle fixtures pass. |
| 6. Independent review, affected host matrix, corrections and landing | 1–2 | Both swap gates and all affected composition/storage acceptance tests pass. |
| **Total** | **6–11.5** | **Budget eight; reserve twelve.** |

The estimate assumes 30–90-minute implementation rounds, 1–2-hour architectural review rounds, existing invariant fixtures being reused, and the relayed agy/codex/muse/qwen fleet remaining available. It includes review/rework rather than treating the first generated patch as finished. It does not assume unlimited parallel speedup: the adapter/publication boundary is a shared critical path.

**Step 3 is most likely to exceed the estimate.** Byte-bounded trees must handle separator size, underfull mutation, non-reducing branch groups, and reused clean subgraphs under changed policy. That is substantive storage behavior with cross-host consequences. The runner/adapter extraction is the next-largest risk, particularly preserving synchronous query/publication behavior without callbacks or rolled-back service effects.