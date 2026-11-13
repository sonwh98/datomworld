Created-GMT: 2026-11-12 16:33:15 GMT
Created-Local: 2026-11-12 23:33:15 +0700 (+0700)
Coding-Agent: glm
Session-ID: 6e2967a3-3d6a-4604-b3f0-2a61a74bb22f

# ADDENDUM (steward relay 1794479000000) — two pre-emption items binding on this round

1. **The nil-guard split decision (python's P3 finding class, F1)**: PHP's mint sites make the
   choice DELIBERATELY in this round — either (a) shape-check the condition at reception
   (non-map/nil condition -> the plain fresh TypeError/refusal path, NO provenance mint), or
   (b) record the fatal-on-nil contract explicitly in the round record. sol's r6/r7 admission
   work already refuses nil-provenance at the door; state which option you took in your
   findings. Reference: collab/exception-track/1794477000000-reviewer-py-adoption-step7.glm-5.3.findings.md (in the main checkout's collab/; the steward's relay is copied here).
2. **The export-surface audit pin mirror (F2)**: php has no twin of the ts/python
   "module-exports/runtime-keys contain no retention surface" pin. ADD it (one-line host-side
   assertion in the php kit test: no php/-namespaced definition reaches the mint/doors) —
   note: your php-mint-reachability-test from r4 already covers the definition-graph half;
   add the module-exports/runtime-keys surface half if it is not the same assertion.

Copy of the steward relay: collab/exception-track/1794479000000-to-yangphp-resume-turn-deltas.md (in the main checkout; also binding).
