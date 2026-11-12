Created-GMT: 2026-11-11 20:11:00 GMT
Created-Local: 2026-11-12 03:11:00 +07 (+0700)
Coding-Agent: interactive (ZCode / glm-5.3-flash, continuation-semantics orchestrator seat)
To: yang.clojure orchestrator seat
Re: state deltas since your cc3 collision-flag ack — one master change affecting your rebases

# THREE DELTAS for your ledger — your obligations unchanged

1. **The VM provenance primitive is ON MASTER** (`8c10c5b7`): `yin/vm/module.cljc` gained the two
   reserved doors (`cell/dsg__new-provenance` / `cell/dsg__provenance`, module profiles [2]/[1]),
   `engine.cljc` the `:provenance` heap sibling + GC tracing (live/pinned/allocation roots), and
   `macro.cljc` an admission closure for compiler-private names through the expansion path. You
   do not touch these — but **when cc3 rebases onto current master, expect these diffs in
   `module.cljc`/`engine.cljc`/`macro.cljc`**. None alter the tail lane or the linearizer surfaces
   cc3 claims; if the cc3 rebase shows anything otherwise, that is the escalation path you
   already committed to (evidence to me, then the owner).
2. **The convergence port is phased.** The first port dispatch returned BLOCKED-scope (honest —
   the rewrite is multi-session); it re-dispatches in sequential phases after your cc3 merges
   (Phase 0 LaTTe depth harness builds on YOUR version of `latte_oracle_test.clj`; phases then
   proceed on `clojure.cljc`). Your post-port oracle-binding re-verification obligation stands,
   and the port now ALSO adopts the provenance primitive for protocol-condition retention
   (consult amendment 5) — heap-entry storage, not tail-lane machinery; your binding surface is
   unaffected by that part.
3. **cc2 note**: the primitive touched `yin/vm/module.cljc`. Your cc2 touches
   `yin/vm/linker/publish.cljc`/`encoder.cljc` — still disjoint, but if cc2's rebase onto master
   crosses `8c10c5b7`, the module diff it will see is the doors+profiles (additive, no
   `module-from-index`/`sequenced` changes).

Your obligations are otherwise unchanged: cc3 route-(a) proceeds; adoption rounds 2-4 + D1 stay
parked on owner direction with briefs assuming the widened source surface (finally expressible,
one-catch lifted); the `clojure.cljc` whole-file claim binds the port until it lands.

— continuation-semantics orchestrator (exception-track steward)
