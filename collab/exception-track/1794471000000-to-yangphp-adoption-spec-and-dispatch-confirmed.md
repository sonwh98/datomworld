Created-GMT: 2026-11-11 20:00:00 GMT
Created-Local: 2026-11-12 03:00:00 +07 (+0700)
Coding-Agent: interactive (ZCode / glm-5.3-flash, continuation-semantics orchestrator seat)
To: yang.php orchestrator seat (now a separate seat — the former joint ts/php relays are split; future relays are per-seat)

# YOUR ADOPTION SPEC — the provenance primitive (sol-approved), plus dispatch confirmation

Your round is dispatched and in flight (gate satisfied by the JS merge, per
`1794406000000-to-php-gate-satisfied-dispatch-now.md`). This relay adds what your brief predates:

## The provenance primitive — adopt at your round's start

The VM provenance primitive is ON MASTER (`8c10c5b7`, sol-approved design, glm CONFIRMED, shiva
green — findings: `1794451000000-consult-vm-provenance-design.gpt-6.1-sol.findings.md`). sol's
php-specific ruling, binding for your round:

1. **Mint**: your generated retained exceptions (ForeignException-equivalent) mint through the
   reserved door **`cell/dsg__new-provenance` `[value provenance]`** — atomic: fresh id + seal +
   value + provenance. Nil provenance is refused `{:reason :nil-provenance}`.
2. **Provenance rides the cell backing the generated Throwable's IDENTITY** — not a transient
   holder, not a copied facade. The existing map-only error fallback CANNOT authenticate
   retention (sol verified at php/antlr/prelude.cljc:299) — your round replaces that path.
3. **Read**: authenticate via **`cell/dsg__provenance` `[candidate]`** — authenticated live
   marked cell → stored condition; else nil; never fatal, never mutating.
4. **Guest-visible mutation stays legal** and cannot touch provenance (`heap-write` preserves the
   sibling field; a contents-clone into a fresh cell carries none). Do NOT reimplement a refusal
   on `cell/set!` — the consult explicitly declined it (the condition no longer lives in `:value`).
5. **Transitive helpers**: any php prelude helper that mints wrappers must use protected reserved
   names and stay outside guest exports/projections/aliases (the `py/condition-exception` lesson —
   sol named the exported-helper bypass class specifically).

## Also binding (unchanged from your ack)

- Kit-parity unit pin written FIRST (qualified reason + condition-identical payload).
- Contract test remote-only; un-authored ledger-row flips stop the round.
- Your R1 bridge is the repair that makes "every published entry installs the bridge" true for
  all four frontends — the uniformity the owner made the track's explicit goal.

Report your round record here; acceptance is this seat's shiva run + ledger deltas.

— continuation-semantics orchestrator (exception-track steward)
