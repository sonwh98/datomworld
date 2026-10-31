Completed-GMT: 2026-09-02 23:41:07 GMT
Completed-Local: 2026-09-03 06:41:07 Asia/Ho_Chi_Minh

All cited files and lines below were verified against the working tree in this run. Nothing was edited; this is a proposal-only, read-only round.

## 1. Jobs (a)–(c)

### (a)1 — the fourth fabricated cursor: already explicit, verified at `yin.vm.implementation-plan.md:240-243`

The plan states it outright: "`call-in-cursor-key` is dropped. `vm.cljc:542` writes it and nothing in `src` reads it — four v1 tests assert only its presence... So the mints are three, not four." Verification this round: the write site is confirmed at `src/cljc/yin/vm.cljc:542` (the def is at 119-121; the only other occurrence in the file is the `empty-state` write), and no reader exists in `yin/vm/ffi.cljc` or `yin/vm/ast_walker.cljc`, both read in full — the bridge path reads `call-in-stream-key`, `call-out-stream-key`, and `call-out-cursor-key` only (`ffi.cljc:74,82,98,108`; `ast_walker.cljc:130,136,138,142`). r7's no-reader census stands uncontradicted. The all-or-nothing bullet at plan lines 228-233 names three mints, consistent with 240-243. No wording proposed.

### (a)2 — `:make-stream` vs explicit pair precedence: already explicit, verified at `yin.vm.implementation-plan.md:219-222`

The FFI-pair section states the v1 order: explicit `:call-in`/`:call-out` first, "matching v1's `(or (:call-in opts) …)` precedence at `vm.cljc:539-540`" — and `vm.cljc:539-540` is confirmed as `(or (:call-in opts) (open-local-stream))`. r7's note described an earlier draft; the current text already prefers the explicit handle.

One residual compression elsewhere in the same document can be misread as restoring `:make-stream`-first. Propose the alignment:

- **Anchor:** `yin.vm.implementation-plan.md:433-434`, the V3 phase, the phrase "and the FFI pair created through it with a declared capacity".
- **Replacement:** "and the FFI pair created through it when no explicit `:call-in`/`:call-out` pair is supplied, with a declared capacity".

No other statement in the document asserts the pair is unconditionally `:make-stream`-created; the normative section at 219-222 governs and is untouched.

### (a)3 — `ffi/attach` on a pairless VM: already explicit, verified at `yin.vm.implementation-plan.md:234-239`

The section says it in words: "`attach` runs on a built VM, so it applies the same rule: with no pair it has nothing to mint against and errors exactly as construction does." That is executable: the pairless attach error is the bridge-without-pair construction error of lines 223-227, raised before any mint attempt and before the store changes; a non-`ok` `cursor` outcome at attach follows the all-or-nothing rule of 228-233 — the attach fails with the outcome carried, VM state unchanged. Both readings are forced by the surrounding text; no tightening proposed.

### (b) — V1 RPC transition contract: propose the insertion

- **Anchor:** `yin.vm.implementation-plan.md`, the V1 phase paragraph (lines 417-425). Insert immediately after the sentence ending "...socket-free, with the pending-response discipline." (line 422) and before "`yin.vm.module` with an explicit registry value" (line 423).
- **Exact text:**

```
**The transition algebra, stated once.** The REPL plan's R1 mirrors this
contract exactly; it is specified here because V1 owns the envelope and both
RPC namespaces consume it.

Client state includes a monotonic, never-reused safe-integer `:next-id`, and
allocation reserves and increments it before append. Encountering an ID
already unsent, outstanding, or completed — or exhausting the cross-host
safe-integer range — is a terminal allocator error and never overwrites a
request. While a request is unsent, `request!` retries that identical encoded
request and accepts no new operation. `request!` is total over `append!`:
`ok` moves the request to outstanding; `full` retains the identical encoded
request and its allocated ID; `closed`, `invalid-value`, and
`transport-error` complete it terminally.

`poll!` is total over `next`. `ok` advances to the exact returned successor
before decoding; `blocked` changes nothing; `gap` advances to the recovery
cursor and reports every outstanding request lost; `end`,
`cursor-mismatch`, `invalid-cursor`, and `transport-error` terminate the
reader binding without changing its cursor and report every outstanding
request lost. In every loss case an unsent request was never accepted and
remains eligible only for an explicit rebind decision by the driver.
Malformed and unsolicited responses are consumed once as diagnostics.

Server state includes `:request-cursor`, `:pending-response`,
`:pending-request-id`, `:pending-successor`, and `:terminal`. `serve-once!`
retries a pending response before reading another request. After a successful
`next` it retains the exact returned successor and runs the handler at most
once. `append!` `ok` advances once to that successor; `full` retains both
response and successor without advancing or re-running; `invalid-value`,
`closed`, and `transport-error` advance once, report the response
undeliverable, and terminate. Request-side `blocked`, `gap`, `end`,
`cursor-mismatch`, `invalid-cursor`, and `transport-error` follow their
corresponding unchanged, recovery-cursor (recording skipped requests), or
terminal transitions.

A malformed request never reaches a handler: with a usable ID it receives a
correlated malformed-request error; without one it produces a local
diagnostic and advances once. Envelope validation — an ID present and
non-nil, the op a keyword, the args a vector — and every request and
response constructor, predicate, correlation-ID rule, and
`:dao.stream.apply/…` key belong exclusively to `dao.stream.apply`.

The client's lifecycle-event transitions are fixed here so the WebSocket
decoder stays a filter over deposited envelopes: `:ws/opened` marks the
attachment established and changes no request or cursor; `:ws/closed` loses
every outstanding request and permits rebind; `:ws/ended`, `:ws/not-found`,
and `:ws/transport-error` terminate the binding and lose every outstanding
request, and whether to retry is the driver's decision, not the layer's;
`:ws/error` is a socket error the connection survived, forwarded as a
non-terminal diagnostic that retains the writer, cursor, and requests.
Unknown current or future event kinds are forwarded as non-terminal
diagnostics. These kinds are data from the deposited-event vocabulary
`dao.stream.ws.md` settles; naming them here requires no transport
dependency.
```

- **Amendments against sol's draft, and why** (everything else adopted in substance):
  1. Added the unsent-retry sentence and the unsent-loss-eligibility sentence. The REPL plan fixes both (`yin.repl.implementation-plan.md:185-188` and `206-207`); without them "every outstanding request lost" is ambiguous about `:unsent`.
  2. Added "(recording skipped requests)" to the server's request-side `gap` transition (REPL plan lines 270-271).
  3. Added the validation triple (REPL plan line 283-284) so V1 is self-contained rather than answerable only by cross-reference.
  4. Replaced sol's single ws-mapping sentence with the full transition list. Sol's version left the `:ws/opened`, `:ws/not-found`, and `:ws/transport-error` transitions unnamed, which is exactly the kind of gap an implementer fills by invention; the REPL plan already fixes them (lines 213-221) and the insertion carries the same answers, scoped as client transition data fixed in V1 and applied by the REPL plan's decoder.
- **Checks:** exhaustive over the contract's `append!` (five outcomes) and `next` (seven) sets, and over the REPL plan's server state keys (lines 244-250). Additive: it details what lines 384-396 already assign ("correlation matching, outstanding-request tracking, conservative loss and rebind") and affirms settled item 7. The V3/V4 bridge rules — fatal `gap` at the bridge cursor (435-438) and the once-only FFI state machine (445-448) — are the VM's own stricter consumer policy over a private pair and are unaffected; the algebra scopes itself to the RPC namespaces. No statement in the plan is invalidated.

### (c) — deferral bullet: propose the final wording

- **Anchor:** `yin.vm.implementation-plan.md:495-499`, the **Not in this plan** section. The section is a single run-in paragraph; to take the approved bullet in its approved form, convert it to a two-item list. Bullet one is the existing text verbatim, only the inline lead becoming the list header; bullet two is the approved content verbatim.
- **Exact replacement:**

```
**Not in this plan:**

- the real telemetry emit path, deferred to a later phase per *Telemetry is a
  stub*; `semantic`, `register`, `stack`; `macro`, `space`, `wasm`;
  `dao.space` in any form; migration of any existing consumer; an
  above-the-stream queue interpreter to restore destructive-take semantics.
- **Internal state as streams** — the log-structured CESK end-state,
  ready-queue-as-stream, stream fusion, and the store-the-irreducible
  storage invariant explored in
  [`yin.vm.streams-all-the-way-down.md`](./yin.vm.streams-all-the-way-down.md).
  The port keeps scheduler queues as plain data by design (the note's own
  calibration: synchronous-singular consumers pay boundary tax); those
  tiers get their own plans after V6 lands.
```

- **Checks:** all four named artifacts exist in the note (§3 log-structured CESK, §9.2 ready-queue pilot, §5 fusion, §6 storage invariant), and its calibration line is at `yin.vm.streams-all-the-way-down.md:71` ("If the consumer is synchronous and singular, the boundary is tax"). V6 is the plan's last phase (line 454), so "after V6 lands" is well-defined. No invalidation: the first bullet's queue-interpreter exclusion restores destructive-take semantics, a different tier from ready-queue-as-stream, and nothing else in the document references the note.

## 2. (d) amendment review

**Applied-vs-trail:** the applied text matches the consensus trail. `dao.stream.md:254-266` is r2's A1 contract replacement verbatim. `dao.stream.ws.md`'s Serving handoff (205-270), server-minted identity (107-117), the traffic/handoff distinction (79-90), retained-state (186-190), Deposit Admission slot rule (329-333), and event groups (121-146) carry r2's A2 normative sentences with the orchestrator's merges preserving them. The wire rename is applied everywhere: no `{:ws/frame :ws/accepted}` survives, and the local `:ws/accepted` event (131-142) is distinct from the wire `:ws/accept`/`:ws/disclaim` frames (408-431). Elements (494-540) matches r1 A3 with the r2 rename; Deferred drops exactly the two settled bullets. The v2 plan carries r2's B3 precision revision including the no-global-order assertion (136-141), B1 (186-201), B2 (242-253), B4 (209-227), the Phase 4a ripples and six handoff-test obligations (304-343), and the settled wire gate (274-282). The REPL plan's "three spec answers, settled" section (431-448) matches what landed.

**Findings:**

| Severity | file:line | Evidence | Correction |
|---|---|---|---|
| Low | `dao.stream.ws.md:521-523` (with `:428`) | The Handshake forbids sending a value frame before `:ws/accept` (428), but the Elements protocol-failure list (521-523) classifies only malformed input: invalid Transit JSON, binary, unknown frame, missing key, out-of-domain value. A well-formed value frame arriving anyway is a named protocol failure whose receiver-side handling is unstated. Unreachable between conforming endpoints — the client's `append!` answers `full` until `:ws/opened` is deposited — so it is peer-robustness, not an execution blocker. | Add one item to the protocol-failure list: "a value frame received before `:ws/accept` has been sent". The existing decode-failure deposit and close-4002 teardown then apply verbatim; no new machinery. |

**What passed:**

- The r1 Critical stranding finding is closed by construction and I verified the closure reads: capacity-one slots, one-outstanding-offer discipline making eviction of an unacknowledged offer impossible (242-248), exhaustion closing the new socket without overwriting (250-253), stale-ack immunity from non-reused identities (239-240), and an identified owner in all five failure bullets (255-266), with the client resolving a premature close as `:ws/transport-error` per the Handshake rule (429-431).
- The handoff slots keep evict-oldest capacity-one retention and tie loss-prevention to the one-outstanding-offer discipline, "not a different retention mode" (329-333), so the contract's reject-mode rejection (settled item 5) is not quietly reintroduced.
- All seven manifest rows map to one of the contract's three honest exclusion reasons (`dao.stream.md:422-429`) and are complete against the contract's outcome tables; the `cursor` row's `closed`-via-closed-attachment matches the contract's Close section (461-464).
- The Phase 2 "where distinguishable" hedge for `:dao.stream/attachment` (196-197) resolves by the contract's own rule — independent close lifecycles make the ring buffer's attachments distinguishable — so the success map carries the value; noting the derivation so nobody reopens it. The bullet's placement inside the descriptor-property list in `dao.stream.md` is a presentational oddity only; the text scopes the value to the `attach!` success map and transport-named deposits, and nothing treats it as descriptor-carried.
- The settled wire gate names its prerequisite chain (Phase 4a conformance before Phase 5, 279-280); the descriptor-key gate still correctly blocks Phase 4; "Settled before Phase 1" items 1-9 are all present in the documents as stated.

**Cross-plan note:** no finding in (a)-(c) changes the transport plan. Job (b)'s lifecycle block consumes the `:ws/…` event kinds as data from a vocabulary `dao.stream.ws.md` already settles (event groups at 121-142), so the insertion is consistent with the transport documents as they stand, and no follow-up round is needed on that account. The one Low finding above is ws.md-local and does not touch the VM plan's wording.

## 3. (d) SIGN-OFF

**SIGN-OFF: GRANTED** on `docs/design/dao.stream.implementation-plan.md`.

Against the same bar applied to the VM plan: every phase names its deliverables, its outcome sets, and its test obligations, and every question a phase would otherwise decide under pressure is either settled in the document (the pre-Phase-1 rulings, the declaration-driven harness, the per-sequence oracle, the one-codec rule, the manifest) or explicitly gated with a named decision-holder (the descriptor-key gate before Phase 4; 4c's composition-policy list; the host-matrix declarations). The former Critical is closed structurally rather than by wording, the exclusion manifest is complete, and no step in Phases 1-5 requires an implementer to invent an architectural decision. The single Low finding above is a one-list-item peer-robustness repair and does not condition the grant; sequence it into the next ordinary document round.
