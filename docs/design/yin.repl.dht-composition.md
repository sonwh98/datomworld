# yin.repl DHT composition: the engine depends only on dao.jing

Status: governing spec for the DHT-boundary refactor. This document
synthesizes the architect charter
(`collab/dao-stream/1794062607000-architect-refactor-first-ruling-gpt6astra.findings.md`)
and its supplement
(`collab/dao-stream/1794109245000-architect-charter-supplement-gaps.gpt-6-astra.findings.md`).
It invents no new design: every rule below is drawn from those two
artifacts and from the owner requirements recorded in section 1.
Implementation follows this spec; where code and this spec disagree, the
spec governs until an architect round amends it.

## 1. Owner requirements and scope

The owner requirements, stated in conversation and recorded verbatim
where quoted, bound everything that follows:

> "yin.repl depends only on dao.jing. It should not know anything about
> dao.jing.dht so that any implementation of dao.jing can be swapped out"

> "dao.stream is framed as a stream of bytes"

The second ruling has a consequence this spec carries through: `dao.stream`
stays a byte stream; chunking and framing are transport-layer concerns
below the logical stream interface. The earlier chunk-contract addendum
that promoted transport chunks and acknowledgements into the stream
contract was superseded by this ruling and does not survive.

Two further owner rulings from the same conversation apply throughout.
Frame budgets derive from the transport composition and are never
hardcoded by consumers. `dao.space` implements semantic page cuts. Neither
ruling is a local guideline: frame budgets, page cuts, and the stream
contract are treated below as invariants, not preferences.

The charter's disposition is refactor-first for the DHT boundary.
Independently verified fixes may merge separately. R7 — byte-cutting the
existing coupled REPL composition on `nightly-reds-fix` before extraction
— is overturned. R1–R3 remain storage work within the refactor sequence,
subject to the corrections in section 12. The properties that passed
architectural review are transport ignorance in the engine, explicit
composition ownership, transport-derived framing limits, semantic page
cutting in storage, unchanged manifest schema and count-cap meaning,
independent evaluator and index observers, and separation of local
durability from network acknowledgement. Runtime preservation and
readiness to merge remain unverified until the gates in section 10 pass.

This spec governs one refactor: separating the engine in `yin.repl` from
every DHT concern so that the engine's storage dependency is `dao.jing`
alone. The relocated module is a small public composition surface
outside `yin.repl` — illustratively `open`, `step`, and `close!` — that
owns the engine value alongside transport state. The engine does not own
or step that transport state. For naming clarity the supplement uses
`yin.shell.*` as the proposed composition namespace family, outside
`yin.repl`. Those are target names, not existing APIs.

## 2. The dependency rule

The engine depends only on `dao.jing` for its storage implementation.
That sentence constrains the storage-implementation dependency. It does
not remove the engine's legitimate `dao.space` indexing and query
dependencies or its `dao.stream` dependencies, which remain. What it
forbids is any knowledge of a particular storage backend inside the
engine tree: no reference to `dao.jing.dht`, and no indirect reference
that reaches the same implementation through another namespace.

The rule is stated as two invariants, enforced by the gates in
section 10. Gate A requires zero storage-implementation references in the
engine tree. Gate B requires the retained engine suite to pass with
explicitly injected non-DHT storage. Both gates must hold for the
boundary merge; neither alone is sufficient. A namespace move that
renames `:dht` to an opaque runner while the engine continues to
manipulate that runner's state does not satisfy the rule. Renaming is
not extraction.

The current coupling violates the rule at construction and beyond it.
Engine construction selects and opens the DHT composition and then
retains its node (`src/cljc/yin/repl.cljc:942`). Linker progression,
query services, rollback state, and event filtering also carry DHT
knowledge (`src/cljc/yin/repl.cljc:1355`,
`src/cljc/yin/repl/query.cljc:66`). Extracting only the module named in
the dispute therefore fails the boundary gate: the adapters in
section 7 must move with the module.

A necessary dependency follow-through applies to the linker. Moving the
calls listed in section 6 is insufficient if the engine still imports
`yin.vm.linker.interpreter`, which itself imports `dao.space.dht` and
`yin.vm.linker.dht` (`src/cljc/yin/vm/linker/interpreter.cljc:35`). The
refactor must split that interpreter's generic pair, retirement, and
admission machinery from DHT serving, or move ownership of the
interpreter entirely to composition. Its current `round [linker node]`
interface cannot remain an engine dependency.

## 3. Ownership split and ordering contract

The amended boundary is this: the engine receives a `dao.jing` content
handle and explicit service media; composition owns storage selection,
durable publication, and every DHT concern. The split is exhaustive.
Anything not listed under engine ownership below belongs to composition.

| Boundary | Minimum contract |
|---|---|
| Engine construction | A `dao.jing` content-store handle, an immutable generic publication policy, validated recovery data, and explicit request/result streams. No peer, cookie, repair-slot, or DHT-node options. |
| Engine publication output | A correlated publication request containing the round and an opaque manifest address, emitted after the necessary content has materialized and read back successfully. |
| Composition publication result | Explicit local durable-publication success or failure. Network dissemination results remain distinct from local durability. |
| Recovery and link input | Validated recovery installation and generic content/link completion data. The engine does not decode DHT event keywords. |
| Composition ownership | Store opening, HEAD persistence, announce, signed-head deposit and following, hydration, transport stepping, scheduling, banners, refusal rendering, and shutdown. |

Composition additionally owns store opening and locking, recovery
validation, atomic durable HEAD replacement, signed-head deposit and
following, announce, hydration, repair, transport stepping, rendering,
and shutdown. The engine owns evaluation, indexing over the injected
handle, preparation of publication requests, and consumption of generic
publication results, recovery installations, and link and query
completions. The linker, query, rollback, and event-filtering adapters
are composition-side interpreters of explicit streams; they are not
injected callbacks and not an opaque runner whose internal state the
engine still manipulates (section 7).

The ordering contract is preserved exactly. Content is materialized and
read back, then the durable HEAD write succeeds, and only then does
announce or deposit proceed:

**materialize/read-back → durable HEAD success → announce/deposit**

The engine's published frontier is promoted only after durable HEAD
success. Replacing `:after-publish` with request and result data must
preserve that ordering and the existing synchronous local-publication
behavior. It must not introduce network waiting or premature success.
A dissemination failure must not erase an already successful local
durable publication: local durability and network acknowledgement are
separate outcomes, and the engine reports the round settled on the
former alone.

Head following and hydration are distinct. Head following updates the
external resolver; it does not automatically replace the session's local
index. Explicit pinned hydration installs recovery before evaluation is
admitted. Ordinary following continues without blocking evaluation.

## 4. The dao.jing surface the engine may use

The current `dao.jing` surface is a content store, not a publication
service. There is no existing `dao.jing/submit-publication` or
recovery-install function, and HEAD and recovery must not be disguised
as existing Jing APIs. The engine and its indexing helpers may use the
following existing entry points, with the contracts as approved in the
supplement. These are the only `dao.jing` signatures the engine side of
the seam may depend on; a replacement backend must satisfy them all.

| Existing entry point | Contract |
|---|---|
| `dao.jing/materialize!` (`src/cljc/dao/jing.cljc:365`) `[handle payload]`, `[handle payload opts]` | Canonically encodes once, hashes those bytes, stores them, and returns the segment address after successful insertion or presence verification. `opts` supports `:algorithm`. |
| `dao.jing/get` (`src/cljc/dao/jing.cljc:435`) `[handle address not-found]` | Returns hash-verified, decoded content or the identical supplied sentinel. An invalid address or corrupt content throws. |
| `dao.jing/observer-state` (`src/cljc/dao/jing.cljc:472`) `[members]` | Creates explicit intake state from `{:stream … :cursor …}` members. |
| `dao.jing/observe-step!` (`src/cljc/dao/jing.cljc:523`) `[handle state]` | Materializes at most one payload; returns successor state, stream signal and, on success, address. A throwing write does not advance its cursor. |
| `dao.jing/adopt-cursor` (`src/cljc/dao/jing.cljc:512`) `[state index cursor]` | Explicit cursor adoption only; no implicit recovery or cursor arithmetic. |
| `dao.jing/segment-key` (`src/cljc/dao/jing.cljc:175`) `[v]`, `[v opts]` | Derives a content address independently of backend. |
| `dao.jing/segment-address?` (`src/cljc/dao/jing.cljc:223`), `dao.jing/segment-matches?` (`src/cljc/dao/jing.cljc:255`) | Generic address and payload validation. |
| `dao.jing/canonical-bytes` (`src/cljc/dao/jing.cljc:153`) `[v]` | Canonical encoding for generic sizing and verification where required. |
| `dao.jing/close!` (`src/cljc/dao/jing.cljc:457`) `[handle]` | Existing generic lifecycle operation; composition owns its invocation for an injected store. |

The underlying handle is plain data with these actual signatures:

```clojure
{:put-bytes-fn (fn [address bytes] ...)       ; => :inserted | :present, or throws
 :get-bytes-fn (fn [address not-found] ...)   ; => bytes | identical not-found
 :close-fn     (fn [] ...)}                  ; optional
```

Every replacement backend must provide immutable, address-consistent
snapshots; idempotent insertion; immediate read-after-success; unchanged
not-found identity; and explicit failure. It cannot overwrite content,
return queued network work as insertion success, or require the engine
to step transport before a successful local write becomes readable.
Retained content must remain available for the lifetime of live index
references.

Persistence is a declared property. The memory backend retains content
for its lifetime; a persistent backend must satisfy its crash-durability
promise. Network replication is a separate outcome and is never reported
as local durability. `materialize!` verifies a `:present` result by
reading and comparing bytes; it trusts an `:inserted` report, so the
publication path's explicit read-back remains necessary.

Semantic operations above Jing remain available and unchanged in role.
`dao.space.transactor/publish!` (`src/cljc/dao/space/transactor.cljc:304`)
`[log]`, `[log opts]` builds and emits an index publication into intake.
`dao.space.index/read-manifest` (`src/cljc/dao/space/index.cljc:306`)
`[content-store manifest-address]` fetches and validates a manifest.
`dao.space.index/read-datoms` (`src/cljc/dao/space/index.cljc:332`)
`[content-store manifest-address]` walks EAVT through `jing/get`; it
does not alone validate all four index closures.
`dao.space.index/restore`
(`src/cljc/dao/space/index.cljc:1344`)
`[candidate {:keys [content-store intake mode schema ref-type verify?]}]`
restores index state; its current signature lacks a byte-policy input,
which is required implementation work under section 5.
`yin.repl.index/rehydrate` (`src/cljc/yin/repl/index.cljc:189`)
`[indexer {:keys [manifest datoms]}]` installs recovered history and
derives allocator authority from it.
`yin.repl.index/commit-names` (`src/cljc/yin/repl/index.cljc:490`)
`[indexer signed round]` records supplied envelope and proof facts as
one transaction.

The new seam, distinct from those existing signatures, is the engine's
injected inputs and its publication protocol. Engine construction
extends `create-state`'s options with the following proposed inputs.
These are the seam to implement; they are not existing APIs:

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

The records travel on the existing `dao.stream/append!`
`[handle value]` and `dao.stream/next` `[handle cursor]` operations.
Identifiers must be unique within the session and must survive
evaluation rollback. Stream append success means the request was
accepted; it does not mean publication completed.

Two explicit engine operations complete the seam, with these proposed
signatures:

```clojure
yin.repl/accept-publication-result [state result] => state'
yin.repl/install-recovery [state {:keys [manifest datoms]}] => state'
```

Their required behavior is normative. The engine retains the prepared
frontier separately and promotes the manifest, published count, and
verified cursor only for the matching successful result. A refusal
preserves the prior published frontier, and duplicate or stale results
cannot promote another round. Synchronous local completion is preserved
at the composition's public evaluation boundary: the composition drives
the engine, services local publication, and delivers the result before
reporting that round settled. The engine never waits for network
acknowledgement and never receives a callback. Recovery is installed
before evaluation is admitted. Composition validates the manifest and
all four reachable indexes, following the existing `validated-snapshot`
behavior (`src/cljc/dao/space/store.cljc:192`). Installation derives
entity and transaction authority from history and discards saved live
cursors. Publication policy is immutable for a live publication context;
a changed or unknown byte policy requires closure revalidation or
reconstruction before reuse. The manifest remains exactly
`{:indexes … :count … :branching-factor …}` throughout.

## 5. Publication policy and frame budgets

R5 lives at the transport/store composition boundary. The effective
transport configuration is resolved once and used consistently for the
DHT state, the store refusal, and the declared payload capability. The
current wiring constructs the store front using defaults
(`src/cljc/dao/space/dht.cljc:281`); supporting configurable budgets
must update that wiring too, or the three consumers of the configuration
will disagree.

The payload budget is derived, never hardcoded. It must satisfy:

```text
maximum encoded store-message size(payload-budget) + margin ≤ effective message bound
```

The derivation uses the actual wire encoder, the maximal query
identifier, the cookie, the supported address forms, and the byte-string
header widths. A measured envelope for one payload size is insufficient
as a universal subtraction. The final full-message refusal is preserved
as the last resort. Neither `dao.jing` nor the engine acquires DHT
semantics under this derivation, and `dao.space` remains responsible for
semantic page cuts: the transport composition states how many bytes a
message may carry, and storage decides where pages are cut within that
budget.

The hardcoded publication branching factor `64` in the current
composition (`src/cljc/yin/repl/dht.cljc:367–371`), introduced as a
message-size workaround, dies when generic byte-bounded publication
activates. It is not relocated as permanent policy. The generic
publication policy that replaces it is the immutable
`:publication-policy` of section 4, carrying `:branching-factor` and
`:max-payload-bytes`, where a `nil` byte ceiling means no declared byte
ceiling.

The manifest schema does not change. The store composition may supply a
generic `dao.space` publication profile containing `:branching-factor`
and the byte capability, and the comparison at
`src/cljc/yin/repl/index.cljc:368` can be restated against that declared
policy without a manifest schema change. Strictly, the branching factor
is an index construction policy, not an intrinsic byte-store capacity.
The manifest stays exactly `{:indexes … :count … :branching-factor …}`,
and its branching factor continues to mean the count cap used for
construction and restoration. That comparison is preserved against the
generic publication policy, retaining the other frontier checks.
Budget compatibility is tracked in live composition state. A changed or
unknown policy requires revalidation or reconstruction, not a new
content-hashed manifest field.

The charter's two related storage corrections are part of this policy.
First, the count-cap compatibility check protects frontier reuse, but
the subsequent `restore` currently receives no publication budget
(`src/cljc/yin/repl/index.cljc:368`). The byte policy must be threaded
through bulk, session, restore, and incremental publication so both
paths see the same number. Second, the recorder consulted for reuse
contains emitted dirty blobs, not necessarily every reachable blob
(`src/cljc/dao/space/index.cljc:1180`). Checking it alone cannot certify
a reused graph against a newly lowered budget. Reuse is permitted only
under the same policy; otherwise the closure is verified or rebuilt
before publishing.

Byte grouping also changes occupancy assumptions in the B-tree. Large
separator datoms can prevent branch grouping from reducing the node
count (`src/cljc/dao/data/btree.cljc:2031`). Underfull-node mutation
behavior and a terminating refusal for an unrepresentable tree must be
specified and tested before byte cuts are enabled. These are storage
behaviors, verified in section 8, not composition wiring.

## 6. Coupling inventory — the refactor checklist

Paths below are relative to `src/cljc/`. Locations identify the current
code, before relocation. This inventory is the refactor checklist: each
row must reach its disposition before Gate A can pass. A literal search
finds nine `dao.jing.dht` references inside `yin/repl/`, plus one in
sibling `yin/repl.cljc`:

| File:line | What it does | Disposition |
|---|---|---|
| `yin/repl/dht.cljc:3` | References the DHT design in module documentation. | **moves to composition** |
| `yin/repl/dht.cljc:520,522,524,527,528,529` | Renders `solo`, `unpublished`, `too-few-peers`, `oversize`, `absent`, and `busy` reasons. | **moves to composition** |
| `yin/repl/store.cljc:37` | Describes the DHT-backed store scheme. | **moves to composition** |
| `yin/repl/store.cljc:126` | Documents the DHT inbound-byte bound. | **moves to composition** |
| `yin/repl.cljc:877` | Describes engine construction of a DHT node. | **dies** from the engine contract; composition documents its own construction. |

That literal inventory is insufficient by itself. Most executable
coupling uses `dao.space.dht`, `yin.vm.linker.dht`, or `repl.dht`
aliases. The following completes the checklist, including ownership and
configuration dependencies. Target composition namespaces are the
proposed `yin.shell.*` family outside `yin.repl`.

| File:line | What it does | Disposition |
|---|---|---|
| `yin/repl/dht.cljc:55`, `65–69` | Imports DHT, signed-name linker, head follower/board, and signing services. | **moves to composition**: relocate the composition module to `yin.shell.dht`. |
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
| `yin/repl.cljc:22` | Requires `yin.repl.dht`. | **dies**. |
| `yin/repl.cljc:249–258,844–897` | DHT CLI/help and construction documentation, including key/follower configuration. | DHT-specific text **moves to composition**; engine documentation describes generic injected services. |
| `yin/repl.cljc:775,792–793` | Constructs the linker; obtains publication options and an `:after-publish` callback from the store. | **becomes a dao.jing-generic call**: injected linker media, generic policy, publication request/result media. The callback bridge dies. |
| `yin/repl.cljc:922,930–965` | Selects store implementation; passes `:dht?`, principals, key and follow configuration; calls `followed-principals`/`open`. This is the charter's `942` region. | **moves to composition**. Engine construction accepts a prepared handle and service endpoints. |
| `yin/repl.cljc:977–992` | Strips runner, retains node, store spec, recovery, and signing key in engine state. | Node/spec/key ownership **moves to composition**; `:dht` and `:dht-key` engine fields **die**. Recovery remains generic. |
| `yin/repl.cljc:1279,1293` | Carries/restores DHT successor through evaluation failure. | **moves to composition**; engine rollback retains generic settlement identities/cursors only. |
| `yin/repl.cljc:1303–1307` | Builds service context including DHT signing key. | Key-bearing context **moves to composition**; requesting round/module/primitive data remains explicit request data. |
| `yin/repl.cljc:1357–1376` | Calls linker with node; calls query service with node; installs both successors. | **moves to composition**. Engine drives/consumes generic link and call streams. |
| `yin/repl.cljc:2069–2075` | Advances linker/node on ticks and compares node successors. | **moves to composition**. |
| `yin/repl.cljc:2085–2091` | Filters `:dao.space.dht/event` and `:yin.head/event` against parked manifest/principal waits. | **moves to composition**; engine receives correlated generic readiness/completion events. |
| `yin/repl/index.cljc:140–166`, `419–450` | Stores/calls `after-publish`, then promotes the published frontier. | **becomes a dao.jing-generic call**: prepare publication, emit request, promote only upon matching publication success. |
| `yin/repl/index.cljc:368–377,402–412` | Compares manifest count cap with `publish-opts`; restores and publishes reused trees. | **becomes a dao.jing-generic call** using explicit immutable publication policy. Preserve count-cap comparison; add byte-policy compatibility and restore threading. |
| `yin/repl/index.cljc:490–525` | Commits signed name-envelope data and publishes it, currently including synchronous HEAD callback. | **becomes a dao.jing-generic call**. Composition supplies envelopes; indexer records them without signing, resolving, or announcing. |
| `yin/repl/index.cljc:13,276,493` | DHT-design references in otherwise generic storage documentation. | DHT references **die**; document the generic materialization/publication guarantees. |
| `yin/repl/query.cljc:66,73,78–79` | Imports DHT, REPL DHT helpers, signed-name linker, and head services. | **moves to composition**. |
| `yin/repl/query.cljc:53–61,93–142,177–230,235–248` | Defines and automatically activates `dao.space.dht`, `yin.link`, and `yin.head` host modules. | **moves to composition**. Inject module registrations through a generic mechanism; retain ordinary `dao.space.query` locally. |
| `yin/repl/query.cljc:648–745` | DHT status/refusal conversion and load/status/retry/cancel/query/module operations. Calls at `654,708–731`. | **moves to composition**. |
| `yin/repl/query.cljc:748–866` | Dispatches head/link operations, derives linked modules (`764`), resolves names (`792`), publishes signed names (`809`), commits envelopes (`818`), reads heads/moves (`860–863`). | DHT services **move to composition**; neutral registry projection and envelope commit use generic calls. |
| `yin/repl/query.cljc:874–925` | Owns a DHT node in `serve`, dispatches remote services, returns node/indexer successors. | Remote dispatch **moves to composition**. Local query serving retains bounded request/response semantics without node state. |

CLI, startup, and lifecycle touchpoints:

| File:line | What it does | Disposition |
|---|---|---|
| `yin/repl/store.cljc:9,28,35–181` | DHT scheme parsing, datagram validation, defaults, Jolt refusal, and checked specs. | **moves to composition**, into the store-selection factory. |
| `yin/repl/store.cljc:184–199` | Opens memory/file stores and redirects DHT opening. | Factory **moves to composition**; the DHT redirect **dies**. |
| `yin/repl/store.cljc:202–216`; `yin/repl.cljc:1846,1904` | Uses concrete durable-directory state to choose reset behavior and close resources. | **becomes a dao.jing-generic call** for reset policy; resource ownership **moves to composition**. Supply reset-retention policy explicitly rather than deriving it from `:durable-dir`. |
| `yin/repl/state.cljc:1–27,44–69` | Saves DHT flags, distinguishes one-shot flags, parses trust principals. | Saved configuration **moves to composition**. Neutral principal parsing may move to the existing signing/admission layer; engine trust commands must not import CLI state. |
| `yin/repl/main.cljc:28` | Requires REPL DHT composition. | **moves to composition** with the host entrypoint. |
| `yin/repl/main.cljc:78–350,375–497,526–631` | Peer/follow/principal/key/token parsing, DHT subcommands, store specs and flags. | **moves to composition**. |
| `yin/repl/main.cljc:634–656,821–889` | Constructs engine with DHT options, loads key, injects WebSocket/head persistence, prints followed heads (`886`). | **moves to composition**; construction hands only generic inputs inward. |
| `yin/repl/main.cljc:720–780,942–989` | DHT help and banners; `banner` call at `969`. | **moves to composition**. |
| `yin/repl/main.cljc:933–934` | Closes head services and store. | **moves to composition** under one resource owner. |
| `yin/repl/main.cljc:1074,1077,1080,1115,1134` | Steps DHT, handles refusal/admission, keeps ticker busy, chooses exit status. | **moves to composition**. |
| `yin/repl/main.cljc:1195,1205,1218,1232,1274,1521,1659` | Steps/stops/drains DHT services across shared, Node, and Dart shutdown paths. | **moves to composition**. |

The search found no additional executable DHT references in the other
files under `src/cljc/yin/repl/`. The linker follow-through of section 2
applies in addition to this table: the engine's dependency closure must
also stop loading the DHT implementation through
`yin.vm.linker.interpreter`.

## 7. Adapter dispositions

The four adapters that currently carry DHT knowledge inside the engine
are replaced by explicit stream interpreters on the composition side.
None of them is an injected callback, and none is an opaque runner whose
internal state the engine continues to manipulate. Each consumes
correlated generic media and returns progress the engine can observe
without naming a node.

| Adapter | Target composition-side interface |
|---|---|
| **Linker progression** | Proposed `serve-links [composition budget] => {:composition … :progress? …}` consumes the existing link request/response media. Composition owns the current `link/round [linker node]` successor; the engine sees correlated decisions and generic pending-work identities, never the node. |
| **Query services** | Proposed `serve-services [composition budget] => {:composition … :progress? …}` interprets externally registered `dao.space.dht`, `yin.link`, and `yin.head` calls through `dao.stream.apply` envelopes. Local `dao.space.query` stays engine-side; signed publication requests a generic envelope commit and awaits local publication completion. |
| **Rollback state** | Service cursors, settled request identities, linker successor, and transport state remain in composition across VM rollback. Generic retirement/abandon records identify the run; engine rollback preserves its minted identities and consumes existing settlements without replaying side effects. |
| **Event filtering** | Proposed `completion-events [composition events] => [generic-event …]` translates load/head outcomes to correlated request or dependency identities. Engine rechecks only matching parked work, ignores unrelated events, and resumes each completion once; DHT/head keywords never cross inward. |

The engine's side of each adapter is data only: request records,
completion records, and the generic operations of section 4. Composition
owns the node, interprets the events, and renders any DHT-specific
outcome before a generic result crosses inward.

> *Deferred §7 follow-up (recorded during master repair):* `yin.shell.session/serve`
> currently passes composition-wide `:progress?` into `yin.repl/recheck-pending`
> (`recheck-on-tick` up to `repl/link-round-budget` rounds), which pins `:checks`
> to `0` while background link/service traffic moves; separating run-scoped
> `:run-progress?` and consolidating the four fixture drive helpers
> (`admission_test/tick!+type!`, `dht_end_to_end_test/step-world+eval-at`,
> `dht_head_test/type!`, `dht_test/step-world+eval-shell`) onto a shared
> `yin.shell.session` entry point is deferred to a dedicated follow-up.

## 8. Invariants to pin before relocation

A partially red integration suite permits starting the refactor only
when failures are classified and narrower invariant tests provide a
trustworthy oracle. The following invariants are pinned green, at the
narrower level, before their implementation moves. Known failing
end-to-end fixtures remain as failing acceptance tests until the
completed refactor fixes them. If a Node compilation or composition
failure prevents those pins from running, that test path is restored
first: absence of results is not evidence of preservation.

**Frontier gate.** Manifest identity, published transaction count,
count-cap compatibility, cursor ownership, and expected suffix length
must agree. Stale, ahead, or foreign cursors reconstruct fully. Missing
content or failed HEAD persistence must not advance the frontier.
Existing fixtures include `test/yin/repl/index_test.cljc:385` and
`test/yin/repl/index_test.cljc:421`.

**Restore Settings threading.** The manifest's count cap reaches every
restored node and recording storage. Strong session references and
durable refault sources remain intact. Recovery derives allocator
authority from history rather than saved process cursors. Byte policy
reaches the restored publisher separately.

**Repair cadence.** One owner advances transport state using supplied
time. Fresh writes retain capacity while repairs progress; queues stay
bounded; delayed future repairs do not imply perpetual busy status. The
twenty-publication/400-tick fixture's requirement for both active repair
and subsequent idle periods is preserved.

**Cross-host parity.** Identical supported inputs, publication path, and
policy yield identical bytes and addresses across JVM, Node, and Dart.
Bulk and incremental paths need logical equivalence; they need not
produce identical tree shapes.

**Lifecycle and causality.** Hydration gates input; failed bind or load
refuses rather than starting empty; `heads.edn` persistence precedes head
installation; relevant completions resume parked work once; rollback
preserves already-settled effects; shutdown closes resources once.

Storage acceptance, once the boundary is established, additionally
requires bulk and incremental publication, restore and mutation, empty
and singleton trees, oversized input refusal, manifest compatibility,
and cross-host determinism. Integration acceptance requires the affected
JVM, Node, and Dart matrix, with slow test bodies actually enabled. The
repository's scoped gate ladder is followed; repository-wide green is
not a prerequisite for starting this work.

## 9. Commit sequence and exit conditions

The refactor proceeds as a reviewable sequence on its own branch.
Intermediate commits may remain on the refactor branch. The completed
boundary change merges only when the affected DHT-family tests pass,
including acknowledgement, the Dart ticker-idle fixture, and the
identified Node composition failure. DHT-family reds are not greened
first on the coupled architecture, and neither fix 5's `64` override nor
`cb2cbe11` is merged as a prerequisite baseline repair. Their
measurements remain useful evidence; their placement and constants are
not architectural commitments.

Boundary-neutral reds — the PHP frontend installation, the compile-EOF
regression pin, the golden correction, the stack-size prepend, and Linux
V8 sizing — may merge before or during this work as isolated changes,
each gated on its previously failing tests and affected host behavior.
The prepend must preserve arguments, namespace enumeration, output, and
exit status. Stack sizing must produce the guest recursion error rather
than a host crash.

R1–R3 implementation may proceed against the agreed generic policy while
extraction proceeds, but their DHT activation belongs after the boundary
is established. They are not a prerequisite patch to the existing
REPL/DHT seam. Their independently reviewed commits are preferably merged
with the completed refactor stack.

| Commit/review step | Exit condition |
|---|---|
| 1. Pin baseline contracts and parameterize non-DHT fixtures | Existing invariants green; new publication-result expectations explicit. |
| 2. Separate engine/composition and extract the four adapters | Engine owns no DHT state or dependency; local synchronous behavior preserved. |
| 3. Generic byte-budget enforcement across bulk, incremental, restore and reuse | Oversize refusal, terminating branch construction, policy compatibility and unchanged manifest proved. |
| 4. Wire durable/DHT compositions, CLI, recovery and shutdown | HEAD ordering, hydration admission and resource ownership preserved on every host. |
| 5. Measure and tune repair scheduling | Fresh writes progress; queues bounded; acknowledgement and ticker-idle fixtures pass. |
| 6. Independent review, affected host matrix, corrections and landing | Both swap gates and all affected composition/storage acceptance tests pass. |

Before moving behavior, the contract pins of section 8 are established
and existing failures are recorded against exact revisions. The merge
gate is the affected DHT-family tests passing, not an aggregate count:
a known failure closed by signature and no unexplained affected-test
regression. Section 11 governs how failures are tracked while the work
is in flight.

## 10. Swappability gates (acceptance criteria)

The two gates below are the acceptance criteria for the dependency rule
of section 2. Both must pass for the boundary merge. They are stated as
invariants, not as aspirations: a refactor that leaves either gate red
has not established the boundary, however clean the namespace layout
appears.

### Gate A — zero storage-implementation references

Run from the repository root:

```sh
if rg --hidden --no-ignore -n -F -- 'dao.jing.dht' \
    src/cljc/yin/repl/ src/cljc/yin/repl.cljc
then
    exit 1
else
    test "$?" -eq 1
fi
```

This includes the requested tree and its sibling engine file, checks
comments and docstrings too, and fails on a search error. There is no
exclusion for a thin shim: DHT composition must leave the tree.

The same review must reject indirect evasion. Run this companion audit
and require no implementation dependencies:

```sh
rg -n \
  'dao\.space\.dht|yin\.repl\.dht|yin\.vm\.linker\.(dht|head)|:dht\b|--dht-' \
  src/cljc/yin/repl/ src/cljc/yin/repl.cljc
```

The engine's dependency closure must also stop loading the DHT
implementation through `yin.vm.linker.interpreter`. Moving imports into
another engine-owned helper does not pass. The audit is over the closure
the engine actually loads, not over the files that happen to mention a
string.

### Gate B — the engine suite passes with explicitly injected non-DHT storage

Use a common test constructor and composition fixture, with two backend
selections:

- Memory: the existing `dao.jing.mem/create-content-mem []`.
- File: the existing `dao.space.store/open [dir]`, which wraps
  `dao.jing.file/create-content-file [path]` and supplies locking, HEAD,
  and validated recovery.

The current injection point already exists:

```clojure
(repl.frontends/create-state {:index-store handle ...})
```

For the refactor, the fixture passes the pure byte handle plus the new
policy, recovery, and media inputs of section 4 separately. It owns the
publication interpreter: the memory composition acknowledges local
completion without claiming persistence; the file composition calls the
existing `:head-fn [manifest-address]`, then emits success or refusal.
It pumps local requests and results and closes resources. It constructs
no DHT node, socket, follower, or repair scheduler.

The retained engine suite is parameterized over this fixture; the proof
does not merely rely on default memory construction. Linker tests
receive the explicit local `:content-store`/`:name-env` arrangement
already used at `test/yin/repl/require_test.cljc:134`.

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

Narrow tests are added for the genuinely new protocol: delayed or failed
publication results do not promote the frontier; duplicate or wrong-round
results cannot settle it; rollback does not replay a settled service
request. A fresh engine-only JVM test process is started and the test
asserts that the DHT namespaces were not loaded.

The whole retained engine suite, including this subset, is required on
JVM, Node, and Dart, with guarded bodies enabled (`DATOM_SLOW_TESTS=1`
where applicable). Actual executed tests are recorded, not only
namespace loading.

DHT-specific tests move with composition. They are not deleted and are
not counted as the non-DHT proof. The boundary merge still requires their
existing assertions — including eight distinct acknowledged publications,
the twenty-round/400-tick idle fixture, head-follow persistence,
bind/hydration refusal, and shutdown — to pass on the affected host
matrix. One correction from the charter applies to that set: the "eight
distinct" assertion at `test/yin/repl/dht_test.cljc:2097` counts manifest
addresses, not acknowledgement timestamps. A failure there is diagnosed
as failed publication or HEAD advancement, not relaxed as a supposed
timing pin.

## 11. Nightly exposure during the refactor window

An exact failure ledger is maintained, keyed by revision, host and
runtime, test identifier, assertion signature, reproduction command,
evidence path, and responsible change. Master failures are distinguished
from workaround-branch regressions: the recorded Dart evidence says
ticker-idle passed on master `3d2f20db` and failed with fix 5.

Affected lanes continue to execute and complete logs are retained. A
result is classified as a known boundary failure only when its signature
matches the recorded failure. A new assertion, timeout, compile failure,
missing namespace, skipped slow body, or incomplete runner summary is a
new or unverified result, even inside an already-red namespace.

New failures are reproduced narrowly, then base and candidate are
compared under the same environment. The responsible change is held
until resolved. Exclusions are not widened, pins are not weakened, and an
unchanged aggregate failure count is not treated as "no regression."
Unrelated verified fixes may continue merging. The boundary merge
requires its known failures closed and no unexplained affected-test
regressions.

Two charter corrections to common misreadings belong here. The earlier
proposal that promoted transport chunks and acknowledgements into the
stream contract is superseded by the owner ruling of section 1:
fragmentation, reassembly, and transport acknowledgements stay below the
logical stream interface. The existing generic `dao.stream` API
additionally describes whole values; this refactor does not authorize
rewriting those values or its operations into a universal chunk
protocol.

## 12. R-set adaptation

| Requirement | Disposition |
|---|---|
| **R1** | **Core survives in storage; not wholly as written.** Encode-once length accounting is sound for legal canonical leaf/branch encodings. Include separator datoms, addresses, framing, and level encoding. An oversized singleton may exist temporarily during planning, but must not escape as a successful publication. Branch construction must reduce or explicitly refuse. |
| **R2** | **Survives with completion requirements.** Check before intake emission; rebuild an oversized incremental index through the bounded bulk path. Use clean staging so rejected blobs and cached addresses cannot leak into the replacement publication. Validate the manifest too. Handle reused clean subgraphs under changed budgets. Recorder length checks need no extra encodes; rebuilding does. |
| **R3** | **Schema/count-cap rule survives as written.** Preserve configurable count caps, including existing small-factor fixtures; 512 remains the default, not a universal requirement. Retire the DHT-specific 64 override when bounded publication is active. Underfull restore/mutation behavior remains a required proof. |
| **R4** | **Measurement principle survives; sequencing is replaced.** Neither blanket preservation nor blanket reversal of `cb2cbe11` is justified. Measure repair capacity in the new composition before merge. No entitlement attaches to 16, 40, or 48 slots. |
| **R5** | **Moves into composition design.** Transport derives the capability; storage consumers receive generic policy. Full-message verification survives. Hardcoded REPL/index transport budgets die. |
| **R6** | **Survives and broadens.** Measure real REPL leaf and branch sizes, full wire sizes, counts per index, distinct addresses per round, repair occupancy, and idle behavior before pinning configuration. Include bulk, suffix, and restart paths. |
| **R7** | **Explicitly overturned.** Byte-cutting the existing coupled REPL composition on `nightly-reds-fix` before extraction is no longer the approved sequence. Worktree reconciliation, independent review, and cross-host verification remain valid requirements. |

## 13. Related debt: native recursion over deep structures

This section records a debt family the refactor must not worsen. It is
not a merge gate for the boundary, but any helper the refactor touches
is held to it.

Host helpers must not recurse over deep structures. A traversal whose
depth is a function of stored content — an index tree, a canonical
encoding, a recovered datom closure — will meet content deeper than any
host stack the three targets guarantee. Native recursion over such a
structure turns valid stored content into a host crash, and the crash
arrives only at the depth production data reaches, not at the depth
tests reach. The portable form is an explicit worklist or an iterative
walk with heap-allocated state, so depth costs heap, which is accounted,
rather than native stack, which is not.

Three instances of the family are in scope for the code this refactor
touches, and each is audited when its file is next edited for the
boundary:

- canonical encoding and sizing on the publication path
  (`dao.jing/canonical-bytes` and the encode-once accounting of
  section 5), where a deeply nested payload must not overflow the host
  stack while its byte length is being derived;
- B-tree blob grouping, branch construction, and restore walks
  (`dao.data.btree` and `dao.space.index/restore`), where separator
  datoms and tree depth grow with the stored index;
- recovery validation and index traversal
  (`dao.space.index/read-manifest`, `read-datoms`, and the
  `validated-snapshot` closure check), where all four index closures are
  walked before evaluation is admitted.

The audit criterion is uniform: for each of the three, a synthetic
structure deeper than the host's comfortable native depth must either
complete iteratively or refuse explicitly with a named reason. A
`StackOverflowError`, a JavaScript range error, or a Dart stack overflow
on valid content is a defect in the helper, not a limit of the content.
Fixing an instance is ordinary iterative rewriting of that helper; it
does not change any contract in sections 2–5 and is scheduled as
follow-up debt where the boundary work does not already rewrite the
helper.

## 14. Estimate

Plan eight working days; plausible range six to twelve, moderate
confidence. The estimate follows the charter's sequence, including its
storage and repair work — not just namespace relocation.

| Commit/review step | Working days | Exit condition |
|---|---:|---|
| 1. Pin baseline contracts and parameterize non-DHT fixtures | 0.5–1 | Existing invariants green; new publication-result expectations explicit. |
| 2. Separate engine/composition and extract the four adapters | 1.5–2.5 | Engine owns no DHT state or dependency; local synchronous behavior preserved. |
| 3. Generic byte-budget enforcement across bulk, incremental, restore and reuse | 1.5–3 | Oversize refusal, terminating branch construction, policy compatibility and unchanged manifest proved. |
| 4. Wire durable/DHT compositions, CLI, recovery and shutdown | 1–1.5 | HEAD ordering, hydration admission and resource ownership preserved on every host. |
| 5. Measure and tune repair scheduling | 0.5–1.5 | Fresh writes progress; queues bounded; acknowledgement and ticker-idle fixtures pass. |
| 6. Independent review, affected host matrix, corrections and landing | 1–2 | Both swap gates and all affected composition/storage acceptance tests pass. |
| **Total** | **6–11.5** | **Budget eight; reserve twelve.** |

The estimate assumes 30–90-minute implementation rounds, 1–2-hour
architectural review rounds, existing invariant fixtures being reused,
and the relayed agy/codex/muse/qwen fleet remaining available. It
includes review and rework rather than treating the first generated
patch as finished. It does not assume unlimited parallel speedup: the
adapter and publication boundary is a shared critical path.

Step 3 is most likely to exceed the estimate. Byte-bounded trees must
handle separator size, underfull mutation, non-reducing branch groups,
and reused clean subgraphs under changed policy. That is substantive
storage behavior with cross-host consequences. The runner and adapter
extraction is the next-largest risk, particularly preserving synchronous
query and publication behavior without callbacks or rolled-back service
effects.




