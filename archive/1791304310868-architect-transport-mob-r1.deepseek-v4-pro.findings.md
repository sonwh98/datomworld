# Architect review: transport boundary mob (R1)

This file holds the Lead System Architect findings for
`collab/1791304310868-architect-transport-mob-r1.prompt.md`. The deliverable
is the review report below (the collab `.findings.md` artifact). It is
read-only analysis; no source was edited.

---

Completed-GMT: 2026-10-06 16:48 GMT
Completed-Local: 2026-10-06 23:48 +07
Coding-Agent: deepseek-v4-pro
Session-ID: 0ab62ae7-5a5e-4caa-bc7b-7b09455efb3e

# Task: Transport boundary mob review
Role: Lead System Architect

## Executive verdict

The boundary is nearly right already: `yin.vm.linker.head` (the follower) is
transport-agnostic — it holds a reader handle and calls only `cursor`/`next`
(head.cljc:394-400, 520-556); `dao.stream.remote` is transport-agnostic — its
link already parameterises `:resend-after` and `:budget` as "unbounded on an
ordered reliable channel and small on udp" (remote.cljc:993-997); and
`yin.vm.linker.head.ws` is a thin composition. Astra's proposal is the correct
direction, and I agree with it on every point **except two places where it must
be sharpened**: (a) "supervision inside stream" is right about the *signal* but
wrong if it drags the *redial decision* below `dao.stream` — the decision is
domain repair and stays in yin; and (b) the prior finding's liveness mechanism
(watch the projection's reading cursor) is exactly the "raw irrelevant traffic"
the owner forbids, so it must be replaced, not retained. I disagree with the
prior finding's idlest-first session eviction: reject-newcomers is correct and
the owner's words settle it.

## D1 — transport-independent boundary / generalising head.ws — AGREE, sharpened

Astra: move ws descriptors, host seams, sessions and half-open supervision below
`dao.stream`; keep board name/table and domain retry in yin.

**Agree** on what stays in yin: the board name map (`"yin.head/" principal`,
head.ws:58-62) and the redial/delay-doubling policy (`drop-dial`, dht.cljc:680-687)
are domain repair and belong to the follower. Nothing transport-shaped may creep
into `yin.vm.linker.head`.

**Agree** that `channel-descriptor` (head.ws:120-129) and the socket host seams
are transport knowledge that a UDP composition would reimplement; they belong in
`dao.stream.*`. The current descriptor hardcodes `ws://host:port/head`.

**Disagree (sharpen) on supervision ownership.** The liveness *signal* — inbound
correlated answer movement — is already the link's own state (`:outstanding`,
`:filed`, `channel-gone?`, remote.cljc:289-313, 490-539). The prior finding's
recommendation to watch `ws-project/reading-cursor` of the dial's projection
reads raw traffic-medium progress, which advances on any ws frame. That is the
owner's "raw irrelevant traffic." The correct boundary is: the link exposes a new
accessor for **correlated inbound progress** (an answered/absorbed answer moving a
per-link cursor), and yin's `step-link` watches *that* to decide `:yin.head/no-answer`.
The mechanism is stream-level; the threshold/redial decision is domain. This keeps
"supervision inside stream" true for the signal while keeping the policy in yin.

The head cursor is also disqualified (a silent-but-alive publisher has a static
board — cursor never moves — yet is alive), which the owner's "never head cursor"
states directly.

## D2 — TCP now, UDP-later guarantees — AGREE

The link already encodes the UDP hooks: `:resend-after` and `:budget` are
composition policy, and `refl-append` + `channel-loss!` already emit
`append-unknown` on a lost append rather than a false exactly-once
(remote.cljc:732-753, 542-555). The boundary contract must **pin**, not invent:
outcomes verbatim (`bare-outcome`), correlation by asker-minted id, cursor/identity
preservation (the mirror "never constructs, parses or rewrites a cursor, anchor or
outcome"), gap→recovery-cursor loss semantics, and append ambiguity. Astra's "not
pretend unconditional exactly-once" is already the code's behaviour; the contract
just makes it an invariant. TCP now is correct per the owner ("just do TCP"); the
risk is that TCP's ordering masks bugs — S1-S4 tests must not assume ordered
delivery.

## D3 — ownership of session/resource/step/liveness — AGREE (reject > evict)

Prior finding recommended "close the idlest session and adopt the new one." Astra
and the owner say **reject newcomers at the cap**. I agree with reject: evicting a
healthy-but-idle reader silently drops it, forcing a redial and a possible
first-contact re-fetch; reject gives a clean refusal (close 1013 / refuse upgrade),
which is already `serve.cljc`'s slot-starvation shape. `accept-step!` today has no
cap at all — `adopt!` grows `:sessions` unbounded and `reaped` removes only closed
projections (ws_project.cljc:197-250).

Reject is **only sound together with** half-open supervision (D1): a half-open
session is never closed by any host today (no pings, no idle timeout), so a cap
that rejects newcomers would fill permanently with dead sessions. Cap + supervision
land together or not at all.

Ownership map (clean): session cap → `dao.stream.ws-project`; step budget →
`remote` + `ws-project`; inbound/outbound byte bounds → host listeners; liveness
signal → `dao.stream.remote` link; liveness policy → yin. No transport knowledge in
`yin.vm.linker.head`.

## D4 — advertised host for wildcard — AGREE, host-seam requirement

`serve.cljc:213-214` already refuses a wildcard bind without an explicit
advertised host. Astra's "concrete bind if available, else route-selected via host
effect/result" is right, with one requirement: route-selection must be a **host
effect** — the `:bind!` result carries the address the host actually resolved (the
default-route interface), never yin guessing. A wildcard bind does not get a
concrete address from the kernel, so the host seam must produce one; when it cannot
(multiple routes, NAT), the composition refuses for an explicit `--dht-advertise`.
Never print `0.0.0.0` in a token, never claim zero-config reachability. The
node's `:bound` event already carries `host`/`port` (dht.cljc:969-972); that is the
place the concrete address surfaces.

## D5 — slice refinement, contract-first — AGREE

Order: (1) write the boundary contract (amend `dao.stream.remote.md` / `ws.md`
with D1-D4 invariants, the link's inbound-progress accessor, the session cap,
step budgets, and byte bounds) **before** slicing; (2) S1 sessions + per-call
budgets in `dao.stream.*`; (3) S2 remote budgets + `:pending-frames` cap; (4) S3
liveness cleanup + `head.ws/stop` + IPv6 brackets + **host byte limits**; (5) S4
gate lift + advertised host + banner. Host byte limits (Node `maxPayload`,
http-kit `:max-ws`, Dart inbound cap) and the `:pending-frames` cap must land
**before** S4, else off-loopback exposure is unbounded (the prior finding flagged
Node 100 MiB / http-kit 4 MiB / Dart none, and `:pending-frames` unbounded).

## Strongest risks

1. **Liveness signal.** Getting this wrong is the crux: raw-traffic/projection
   cursor → false-redial storm on a silent-but-alive publisher; head cursor → a
   dead publisher never detected. Only correlated inbound answer movement is
   correct. This is also where the prior finding is wrong and must be overridden.
2. **Cap fills with half-opens.** Reject-newcomers without supervision is permanent
   denial. The two are one landing unit.
3. **Advertised host.** Route-selection cannot be universal; NAT/two-route must
   refuse for explicit override, or the token is undialable.
4. **Unbounded bytes off loopback.** Without host byte limits + pending-frames cap,
   a flooder OOMs or starves the single tick owner.
5. **Boundary leak.** If S1-S3 sprinkle transport knowledge into yin (a ws-frame
   read, a projection cursor, host bytes), the UDP swap re-breaks. The contract
   must enumerate what yin may observe (handle outcomes, correlated inbound
   progress) vs. may not (ws frames, projection cursor, host byte counts).

## Acceptance criteria

- A held connection beyond the cap is refused, `:sessions` never exceeds the cap,
  and the idlest *healthy* session is never evicted.
- A half-open (silent) connection is closed by stream supervision and its slot
  freed within a bounded tick count, independent of head cursor and raw ws traffic.
- The follower's `:answered` advances only on correlated inbound protocol progress,
  never on a locally-produced `blocked` (fixes head.cljc:552's false advance).
- `head.ws` exposes no ws descriptor construction, host seam, or socket logic a UDP
  composition would reimplement; board name/table + redial/delay are the only
  yin-domain residue.
- A UDP-policy link (`resend-after` k>0) preserves outcomes verbatim, id
  correlation, cursor/identity, gap→recovery-cursor, and append-unknown on loss —
  asserted by test, not deferred.
- A wildcard bind advertises the host-reported concrete address when one resolves,
  else refuses for `--dht-advertise`; no token ever prints `0.0.0.0`.
- Host byte limits are set on all three hosts and a flooder closes rather than
  OOMs, before the loopback gate lifts.
- `mirror-step`/`step!` have a finite aggregate event budget; a throwing session
  closes itself and does not stop the tick for other sessions.
- IPv6 dial (bracketed literal) works on all three hosts; a real-socket crossing
  passes on Dart.

## How the consensus was reached (mob synthesis)

The coordination brief forbids invoking delegates, so this is a single-seat
synthesis of the three inputs: the owner's invariants, Astra's proposal, and the
prior architect's section-8.3 findings (claude-fable-5-1). Where they disagree, the
owner's words are the tiebreak. Points settled: (1) liveness = correlated inbound
progress, overriding the prior projection-cursor watch (owner + Astra agree); (2)
reject-newcomers over idlest-evict (owner + Astra); (3) contract-before-slices and
byte-limits-before-gate-lift (Astra, uncontested); (4) supervision split — signal
in stream, policy in yin (my refinement, resolving Astra's "inside stream" against
the domain-repair placement). Two items remain owner-open, unchanged from section
13: the off-loopback token grammar (8.2 risk 3) and question 4/5 (ws acceptability,
safety-without-progress) — neither blocks writing the boundary contract, which is
the next slice's input.
