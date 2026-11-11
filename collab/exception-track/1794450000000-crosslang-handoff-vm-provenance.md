Created-GMT: 2026-11-11 1x:xx GMT
Created-Local: 2026-11-11 1x:xx +0700
From: yang.python orchestrator seat (round owner: exception-py-r2r3)
To: a CROSS-LANGUAGE orchestrator taking responsibility for the VM provenance-storage work
Status: OWNER-AUTHORIZED (2026-11-11, "yes" + two conditions: engineer/architect consult; cross-frontend awareness ruling). You own what follows.

# HANDOFF — the VM provenance-storage work: a cross-language implementation brief

You are taking over a piece of work that exceeds any single frontend seat: a VM-level change that four frontends will depend on, plus the design ruling that keeps them from diverging. This brief is self-contained. Read the three authority documents it names, verify nothing, trust the file:line evidence — it was all gathered in-tree today.

## 1. The defect (proven, not hypothesized)

The exception track's step-7 review (`collab/exception-track/1794402000000-architect-py-r2r3-step7-review.gpt-6.1-sol.stdout.log`, verdict FIX-LIST) confirmed by EXECUTED PROBE on all three JVM vector backends:

- Python exception wrappers stamp their retained condition (the `{:yang.call/reason ... :yang.call/value ...}` record that must cross guest-call boundaries intact, ruling v2 §R2/R3) into **the guest-visible cell map** (`:py/retained-condition` key).
- Any admitted guest program can forge that stamp. Executed: Clojure source `(defn forge [e condition] (cell/new (assoc (cell/get e) :py/retained-condition condition)))`; Python created `Exception('fresh')`, passed it through `forge`, raised the clone. Egress (python prelude.cljc:917) forwarded the attacker's condition as if runtime-minted (`:forged-condition-forwarded true`). The same cell ops overwrite a GENUINE wrapper's stamp.

## 2. Why the fix is a VM change (the heap anatomy — verified in-tree today)

- `cell/new` allocates a heap entry (yin.vm/engine.cljc:2773): fresh id, sealed `:cell-ref`, `heap-write` of the content.
- The guest-visible map is **the entry's `:value` field**: `cell/get` answers only `[:heap id :value]` (:2780, "the content is answered as data, never re-interpreted"); `cell/set!` writes only `:value` (:2784). The guest has NO operation addressing any other field of the entry.
- Therefore: the stamp must move OUT of `:value` into a **host-only sibling field on the entry** (e.g. `:yang.call/provenance`, sibling of `:seal`/`:value`) — write-once when the runtime mints a wrapper, read only by the host's identity check, never guest-visible, reclaimed by the existing GC with the entry (it lives in the same heap map; no registry, no leak).
- `cell/authentic?` alone is insufficient — the forged clone is a genuinely allocated cell with a genuine seal. The lie is in the `:value` content, not the cell's identity.
- The JS seat fixed the SAME P1 differently: inside `js.rt/*` (commits `31287b17` "private identity-store provenance — membership, not wrapper metadata" + `1697b257`), because JS wrappers are js-runtime objects — their store lives in their own surface, one layer ABOVE the VM heap. Python wrappers are VM cells; python cannot replicate the js.rt approach in-surface. PHP wrappers will presumably also be VM cells → PHP inherits this problem the moment its R1+R2+R3 round starts.

## 3. The owner's decision (verbatim conditions)

Authorized ("yes"), with: (a) **consult with an engineer or architect** on the design before landing; (b) **rule on cross-frontend awareness** — do the other yang.* compilers need to apply this in their pipelines?

## 4. What YOU now own (the cross-language responsibility)

1. **The consult**: run the engineer/architect design review of the storage design — host-only entry field, write-once at wrapper mint, host-identity read, GC-with-entry, no guest-visible vocabulary unless unavoidable (if unavoidable: full `cell/authentic?` rigor — declared API change, second opinion). Route per routing-status.md; sol carried the deep review rounds well this arc.
2. **The cross-frontend ruling** (the owner's condition (b)): recommend a SHARED primitive — one VM provenance mechanism all wrapper-minting frontends adopt — rather than three divergent schemes. The known consumers: python (now), php (at its round's start), clojure (open question: its outcome channel is internal per decision (A); whether its boundary retained wrappers need VM provenance is part of the ruling), js (already self-sufficient via js.rt — decide whether to migrate it onto the shared primitive or grandfather it; migration is NOT urgent, js's fix is sound).
3. **The surface sequencing**: the change touches `yin.vm.engine`/`yin.vm.module` (+ python init/attachment; + php/js/clojure adoption as ruled). The yin.vm seat owns the VM surface — notify them via the surface registry's queue rule (`1794343000000-exception-track-surface-registry.md`) and check contention before any round writes there. Designate the writing seat per round (a yin.vm-seat round for the engine field, then per-frontend adoption rounds), one writer per file+region as always.
4. **The acceptance instrument** (already written, currently RED on all three backends — do not rewrite it, it is the contract): the three forgery pins in `test/yang/call/python_exception_test.cljc:108` — the reviewer's exact forge pair (after the fix: a fresh `:yang.call/thrown` whose payload is the clone, never the forged condition), a cell-clone-of-genuine pin, and an overwrite-attempt pin. No-regression gate: the eight python ledger fixtures + the parity pins (`be87df74`) + the shared suites.
5. **The ledger**: append the round delta per the ledger's append-by-update rule when accepted; the py round (`exception-py-r2r3`, tip `16c6294f` + goldens `04cb7887`) is otherwise ACCEPTED and merge-held only on this fix.

## 5. The constraints that bind you (so the brief is complete)

- Ruling v2 (`1794960000000-owner-ruling-exception-uniformity-v2.md`) §R2's lifetime rules: identity-owned, outside source-writable attributes, no ambient registry, wrapper non-stacking, formatting never invokes foreign methods.
- Decision (A) (`1794341000000`): no internal-encoding convergence — the storage primitive is shared INFRASTRUCTURE, not encoding convergence.
- R5: nothing may route on condition-map shape; the provenance check is identity, not shape.
- The `^:slow` gates stay; the contract test is never run locally on the laptop (owner ruling); remote acceptance runs carry the overlay-inspection rule (post-checkout `git diff --stat` must equal the intended overlay — two contamination incidents this arc).
- The surface registry's one-writer rule and the branch registry (`exception-py-r2r3` is the python seat's; the VM round needs its own branch and its own owner — designate it).

## 6. State of record when you take over

- py branch `exception-py-r2r3` @ `16c6294f` (+ `04cb7887`): ACCEPTED in the ledger (24 rows green, xtrack-py2 no-overlay), merge-held on this fix.
- js branch `exception-js-r1r3` @ `40284c15`: js5 re-acceptance in flight.
- clojure branch `exception-r3-impl` @ `fbd8da0f`: accepted (18 rows green, xtrack-r3A4); clojure now additionally carries the owner-directed continuation-port work.
- php round: not started; its gate was js5-green.
- The fix round's artifacts: pins in the worktree `/Users/sto/workspace/datomworld-f12` (branch `exception-py-r2r3`, uncommitted — the round stopped pre-code awaiting your authorization chain; commit them as the VM round's G0 or fold them into the VM round's brief, your call as the sequencing owner).
- My seat hands off ROUND OWNERSHIP of the cross-language fix but remains the python ADOPTION owner: once the primitive lands, the python round wires its wrapper minting/reading onto it and re-runs the same gates.

— yang.python orchestrator seat
