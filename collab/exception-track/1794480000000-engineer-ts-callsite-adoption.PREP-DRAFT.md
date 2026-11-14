Created-Local: 2026-11-12 00:2x +07 (Asia/Ho_Chi_Minh)
Coding-Agent: interactive (ZCode / GLM seat — yang.typescript orchestrator)
Session-ID: n/a (PREP DRAFT — not dispatched)

# ***PREP DRAFT — DO NOT DISPATCH UNTIL PHP-ADOPTION ACCEPTANCE***
# Gate state (steward's formal closure sync, collab/exception-track/1794481000000, 2026-11-12):
#   (1) cc3 — CLOSED, merged 2c80a421; the occurrence contract binds against master.
#   (2) php-adoption precedence — the ONLY serializer; php adoption in flight (their 31d2388c),
#       acceptance = steward shiva run + ledger; dispatch this round when that lands.
#   (3) final brief — DELEGATED TO THIS SEAT ("yours... no steward action blocking"): on
#       finalization, THIS document IS the brief (replace this header with the artifact-protocol
#       header + Created stamps + session UUID; no external paper needed).
# Rebase awareness at finalization (all on master, none touch the claimed surfaces): yin.shell
# steps 1–4 landed; test/yang/clojure_test.clj moved (509 lines); yin/vm/linker/pair.cljc +675;
# provenance primitive 8c10c5b7; python adoption 8018005d (its F2 finding explicitly mirrors the
# ts export-audit pin idiom — cite as the pattern of record).

# Task: ts callsite-provenance adoption — :yang/call-site v1 on every final :application occurrence

Role: Yang Compiler and Universal AST Engineer

Implementers:
- Model: (TBD at dispatch: muse-wonder-1.0 for the emission surface; spark for mechanical-only deltas) | Status: pending

## Read first
- `/Users/sto/workspace/datomworld/collab/yang-clojure/1794670000000-architect-callsite-provenance-generalization.gpt-6-astra.findings.md` — THE DELIVERED DESIGN (64KB).
- `/Users/sto/workspace/datomworld/test/yang/clojure_callsite_test.cljc` — the reference implementation to MIRROR its shape (32 deftests: the equality gate, validation failures, safepoint-independence check, canonical invariance).
- `/Users/sto/workspace/datomworld/src/cljc/yang/clojure.cljc:52-67` — the reserved key: `:yang/call-site`, version `1`, coordinate tuple `[origin root-address path :yang/call-site 1]`; provenance stays OUT of hashed content (design invariant).
- `/Users/sto/workspace/datomworld/collab/yang-call/1794980000000-orchestrator-two-relays-equality-and-h36-audit-inputs.md` part 1 + `/Users/sto/workspace/datomworld/collab/yang-call/1795010000000-orchestrator-reply-equality-gate-per-frontend.md` — ADOPTED RULING: the cross-host occurrence-key EQUALITY GATE is part of THIS frontend's own acceptance (same source on JVM/Node/Dart → identical occurrence coordinates; never cross-frontend).
- `docs/design/yang.antlr.md` §8.10 + §8.10.7; `docs/design/yang.typescript.md` §16.

## Requirements (from the handoff of record + the design)
1. The reserved key `:yang/call-site` version `1` goes into the **ts frontend-metadata side table**
   for EVERY final `:application` occurrence on PRODUCTION compilation paths (not test
   instrumentation only).
2. Provenance never enters hashed content; the UAST remains retrievable (unitarity invariant).
3. Equality gate at acceptance: the same source compiled on the ts lanes yields IDENTICAL
   occurrence coordinates (JVM/Node/Dart; suite-scale on shiva/jupiter per the standing rules).
4. Mirror the clojure reference test shape (ts-side namespaces under test/yang/).

## Collision + drift cautions (recorded at prep time)
- The exception-track VM primitive `:provenance` + reserved doors `cell/dsg__new-provenance`/
  `cell/dsg__provenance` landed as `8c10c5b7` — a DIFFERENT key family; do not re-mint or reuse
  the doors; cite the distinction in the test headers.
- cc3 (transform/session C1 preservation) is actively landing and touches UAST packet
  provenance (`:kind` slot reads, U1 event rows) — the adoption branch MUST cut from current
  master at dispatch; re-run the equality check against the post-cc3 golden, not the morning one.
- The `:global` tag precedent (docs "Derive, don't persist"): if any fact here is query-derivable
  over dao.space, the design says query — the astra design already decided side-table; flag any
  deviation as a STOP condition.

## Lanes (RE-ARMED 2026-11-13 per routing-status; the earlier muse-wonder plan is DEAD)
Board state that binds this dispatch (check fresh at dispatch — all dated entries):
- DARK until ~Nov 16: muse-wonder-1.0 (and spark untested/quality-risk), agy argon,
  agy claude-opus/sonnet (shared window ~Nov 17), glm-5.3 (weekly window Nov 16).
- OWNER-CORRECTED POOL MODEL: only CODEX has per-host pools (POOL1 = laptop+shiva shared,
  POOL2 = jupiter, bwrap FIXED Nov 13 02:4x — RE-PROBE before relying). Every OTHER provider
  draws ONE meter across all three hosts — host-switching does not escape a qwen/deepseek wall.
- Primary plan: implementer **qwen3.8-max** (live, execution-capable; positional-prompt-first
  argument order per the documented quirk; jupiter runs need the nvm PATH export);
  reviewer **sol gpt-6.1-sol** (family-independent vs Qwen author; POOL1 resets ~hourly; if
  walled, POOL2 jupiter codex after one probe — resume-continuity impossible on jupiter,
  route a FRESH thread with this full arc).
- Alternate plan if qwen walls first: implementer **codex gpt-6.1-terra** (execution-capable,
  moderate-complexity per team.md — compiler-lowering work then needs the "focused tests +
  independent review" validation it prescribes) and reviewer flips to **qwen3.8-max** (family
  ≠ GPT). DEEPSEEK IS OFF THE MENU (owner relay 2026-11-13: credits running low — use codex
  instead; deepseek-flash additionally stays banned for invariant work).
- Gates: focused local selectors only; G1 `bb test:sub yang.typescript` + equality legs on shiva;
  kit namespaces in G0 (the landed exception tests are now baseline regressions).
- Branch plan: `callsite-ts-adoption` cut from current master at dispatch, own worktree.
