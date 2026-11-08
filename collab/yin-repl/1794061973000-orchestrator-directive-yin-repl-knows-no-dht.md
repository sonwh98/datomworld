Created-GMT: 2026-11-07 12:3x GMT
Created-Local: 2026-11-07 19:3x +07
Coding-Agent: interactive (ZCode / glm-5.3-flash, master-green orchestrator seat)
Session-ID: not-applicable (interactive seat)

# OWNER DIRECTIVE: yin.repl must not know the DHT exists — its knowledge stops at dao.jing

## The owner's words (2026-11-07, during the byte-cut layering discussion, verbatim)

> "the yin REPL should not have any knowledge of DHT. It should only have
> knowledge of dao.jing"

## Current state (verified by grep, this seat)

- src/cljc/yin/repl/dht.cljc — 1139 lines INSIDE yin.repl composing
  dao.jing.dht directly: store node composition, publish-opts (fix 5's
  :branching-factor 64 lives here), head-fn/announce!/deposit-head!,
  repair machinery awareness, follower/head logic, banner/step/refusal.
- src/cljc/yin/repl.cljc (:876-976, :2086) and src/cljc/yin/repl/main.cljc
  (:886-1077) call repl.dht/* throughout (step-owner :dht, open/close!,
  follow-lines, without-runner, banner, refusal).
- src/cljc/yin/repl/index.cljc:369 reads :branching-factor from publish-opts
  (a transport constant reaching into REPL index logic).

## The principle, stated as the target architecture

yin.repl = the REPL ENGINE: session, evaluation, indexer, publication
requests expressed in dao.jing terms only (content store, publish-opts as
generic storage options, manifest addresses as opaque content addresses).
DHT-specific behavior — announce, head following, repair cadence awareness,
message budgets, branching overrides — belongs ABOVE or BESIDE yin.repl:

  composition layer (a new module beside yin.repl, e.g. the session shell
  that today is yin.repl.dht) ── composes yin.repl + a dao.jing.dht store
        │
  yin.repl (engine) ── speaks ONLY dao.jing: "publish this round",
        "here is my manifest", "give me a content store"
        │
  dao.jing (storage contract: address → bytes; publish-opts; budget
        derivation happens HERE per R5, as a composition property)

Consequences:
1. fix 5's :branching-factor 64 override and any budget constant move OUT of
   yin.repl.* — into the composition layer or the dao.jing.dht store's own
   configuration (the store knows its bound; R5's derivation sits at the
   composition boundary, consistent with the owner's byte-stream-foundation
   ruling: the transport exposes the budget, framers frame to it).
2. yin.repl.dht as a NAMESPACE either moves out of the yin.repl tree or is
   reduced to a thin composition shim; the engine's call sites (step, open,
   follow, banner) become composition-layer calls that drive the engine
   through its public API.
3. index.cljc:369's branching-factor read is a leak — the frontier-gate
   comparison must be restated in dao.jing terms (e.g. the store reports
   its own capability; the engine compares what the STORE declares, not a
   transport constant it was handed).

## Sequencing (orchestrator's proposal, for the reconciliation round)

- The byte-cut implementation (R1-R7 + the owner's layering rulings) proceeds
  on nightly-reds-fix, but MUST NOT cement DHT knowledge deeper into yin.repl:
  budget derivation and page-shape options go to the composition/store layer
  from the first patch, not into yin.repl namespaces.
- The full relocation (1139 lines + call sites) is its own design slice AFTER
  master-green: an Architect round to specify the composition module's API
  (what the engine exposes vs what the composition drives), then an
  implementation round. Do not fold into nightly-reds-fix.

— the master-green orchestrator seat
