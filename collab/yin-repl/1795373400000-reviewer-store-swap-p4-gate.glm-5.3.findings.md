Created-GMT: 2026-11-22 18:07:48 GMT
Created-Local: 2026-11-23 01:07:48 ICT (+07)
Coding-Agent: glm-5.3 (routine correctness review seat)
Session-ID: 1ba02c6c-a8d3-4eea-8377-14aff23bf770

# Task: store-swap Slice 4 — documentation review (yin.repl.md, yin.repl.dao.space-index.md)

Reviewed: uncommitted diff on store-swap-p1 (src/cljc/yin/vm/docs/yin.repl.md
§"Switching Index Stores", docs/design/yin.repl.dao.space-index.md §"Durable
store: startup, in-session switching, HEAD, and the directory lock") against
the actual code in this worktree. Every string, option, and behavioral claim
was checked at its cited implementation site; no file was edited.

## Claims verified against code (all accurate)

- Refusal/confirmation strings, exact:
  - "cannot switch index store while the session holds evaluated state; run
    (reset) first" — repl.cljc:2540 (swap-refusal), prefixed "Error: " at
    main.cljc:496 / repl.cljc:2606.
  - "cannot switch index store while a require is pending; wait for it to
    complete or run (abandon) and (reset) first" — repl.cljc:2536-2537.
  - "Switched index store to mem (code index cleared)" / "file:<dir> (empty
    code index)" / "(recovered HEAD <manifest>, N transactions)" — swap-summary
    repl.cljc:2579-2587; the manifest prints as the segment-address keyword
    `:segment/<algorithm>-<digest>` (dao/jing.cljc:241-275
    parse-segment-address), so the doc's `:segment/...` example shape is
    correct; N = distinct `t` (nth 3) over the recovered datoms.
  - "Error: store options require dht:<dir>" — shell/cli.cljc:629 (and the
    Gambit twin, jolt yin.jolt:66).
  - Lock-conflict hint "(currently held by this session; switch to mem first
    to release the directory lock)" — repl.cljc:2590-2599 (format-swap-error).
  - "Error: (use-store ...) is a local host command and cannot be run over a
    remote endpoint" — serve.cljc:353-356.
  - Gambit C4 message "blocking ffi is unsupported on the gambit target —
    GC-safe blocking is unqualified on Gambit 4.9.8 (no verified release/claim
    transition)" — verified in the Slice 3 gate against
    host/gambit/yin/dao/posix/file.jolt and the recorded transcript.
  - :manifest startup-pin refusal paraphrase matches cli.cljc:625-627.
- Option list matches parse-store-command / store-command-option exactly
  (cli.cljc:575-639): :peer/:peers (:578-581, localhost→127.0.0.1 via
  peer-text :331-334), :publish boolean (:582-584), :bind/:port/:listen
  (:585-587; listen-options :565-572 accepts "host:port", "[v6]:port",
  "port"; a bind with no peers refuses, dht-spec :253-257), :key existing
  file (:589, load-key :136-155 refuses a missing file), :principal/
  :principals 64-lowercase-hex (:590-592, parse-principal :107-115),
  :trust "ed25519:<64-hex>" via state/parse-trust (:593-594), :follow
  "<64hex>@<loopback host:port>" with declared-principal requirement
  (:595-596, parse-follow :76-104, dht-spec :269-275), :join token adds the
  peer, declares the principal, and follows its board (:597-603, parse-token
  :337+), :max-inbound-bytes nonnegative integer (:588, dht.cljc:214-215),
  unknown option refused (:604). :trust restored on subsequent (reset) via
  :initial-admission (store-session repl.cljc:1086; reset restores it at
  repl.cljc:2695 configure-admission).
- Quiesce requirement and (reset) interaction: the only writer of
  :quiesced? false is eval-program (repl.cljc:2333), set at evaluation entry
  regardless of outcome — so "including after an evaluation error" is
  accurate; make-session ends :quiesced? true (repl.cljc:1016), so startup,
  (reset), (vm ...), and a completed (use-store ...) all leave a quiesced
  session. The listed inspection commands are pure formatters that never
  route through eval-program (compile repl.cljc:2686, trusted :2691,
  repl-state :2701, host :2704; help is static text). (reset) and (vm :type)
  consult store/retain-on-reset? (repl.cljc:~2510 rebuild-session, ~2673 vm
  branch), matching the doc's durable-retention-vs-empty-mem claim.
- Design doc's open-first replacement is accurate: replace-store parses
  (via :parse-store-command) → opens (composition :open or store/open) →
  installs (shell/install-store) → releases the old session/store
  (release-store!) only after the commit point (main.cljc:449-483); any
  open/install failure releases only the partial replacement
  (:478-480) and the prior store, lock, and transport continue. The async
  post-swap refusal path matches step-all (main.cljc:912-918): :swapped-
  in-session? + composition refusal → replace_store {:spec :mem} and the
  "Recovered with a fresh mem index store after a store switch failed: ..."
  line instead of :running? false. Synchronous UDP bind-failed detection
  during open with pre-rethrow cleanup confirmed at shell/dht.cljc:663-673.
  Same-directory lock discipline: in-process lock contention + the bounce-
  through-mem hint (format-swap-error) and dht-spec's "with no peers the DHT
  store is solo and opens no socket" (:253-257) are described correctly.
- Preserved-keys claims: install-store merges the fresh session into the old
  state (repl.cljc:2568-2576), and make-session's fresh map (repl.cljc:996-
  1016) contains none of :lang/:frontends/:vm-type/:extra-primitives/
  :shell-token/:output-stream/:output-cursor/:link-policy/:custody, so all
  survive the merge (:link-policy/:custody are real state keys — create-
  state repl.cljc:1227/1229); :round advances (repl.cljc:2573). Value
  history is cleared (:2574-2575).
- Local routing / state.edn: 'use-store in local-command-heads
  (adapter.cljc:23) and command-heads + help text (repl.cljc:425-436); the
  remote refusal string verified above; no state/save-state! call anywhere
  on the swap path.
- DHT composition claims: host modules dao.space.dht, yin.link, yin.head
  registered by shell.query (query.cljc:6, 49-51); ring-departure resource
  release via release-store! → composition :close!/:close-store!
  (main.cljc:243-251).
- No overclaims found: every documented behavior maps to implemented code;
  the Gambit section matches the Slice 3 gate's verified behavior including
  the re-require note for host modules in the rebuilt session.

## Findings

LOW | src/cljc/yin/vm/docs/yin.repl.md:209 | "opens in solo mode (no peers,
no socket, fetch-only)" — for a peerless, socketless node "fetch-only"
asserts a capability it cannot exercise (nothing to fetch from, no socket);
the design's wording is "peers [], publish? false, no socket, no key"
(1795360785000 Decision 3) and dht-spec's own message says "solo and opens
no socket" (cli.cljc:255-256). Mitigating: "fetch-only" is established
in-repo terminology for publish?=false (yin.repl.md:244, :320 — though both
of those nodes carry peers and a socket). | Prefer "no peers, no socket, no
key — local-only until peers are given" (or drop "fetch-only" from the
solo-mode parenthetical).

INFO | src/cljc/yin/vm/docs/yin.repl.md:175-182 | The worked example shows
opening a fresh file:<dir> (empty code index), then (reset), then reopening
the same dir as "(recovered HEAD :segment/..., 3 transactions)" with no
intervening evaluated/published programs shown; a HEAD with transactions
exists only after rounds are published to that store. The output format
itself is correct (verified against swap-summary); only the elision could
mislead a reader into thinking reset+reopen fabricates history. | Add an
elision comment between the two opens, e.g. ";; ...evaluate programs on the
store; each published round advances HEAD...".

INFO | docs/design/yin.repl.dao.space-index.md:134-151 | The design doc
names the preserved key list including :output-stream/:output-cursor while
yin.repl.md:~196 lists a subset (:lang :frontends :vm-type :extra-primitives
:link-policy :custody :shell-token). Both are accurate (subset vs superset);
noted only so a future reader does not treat the user-doc list as
exhaustive. | None required; optionally align the two lists.

## Verdict

APPROVED
