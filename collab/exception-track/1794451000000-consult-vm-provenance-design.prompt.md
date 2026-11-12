Created-GMT: 2026-11-11 14:25:00 GMT
Created-Local: 2026-11-11 21:25:00 +07 (+0700)
Coding-Agent: codex
Session-ID: 44588fb5-e1b7-44f5-9f7a-e512c5cd8111 (codex; capture at first turn)
# Task: architect consult + cross-frontend ruling — the VM provenance-storage primitive
Role: Architect (owner condition (a): design consult; condition (b): cross-frontend ruling — one consult covering both)
Implementers:
- Model: gpt-6.1-sol (codex) | Assigned: 2026-11-11 21:25 +0700 | Status: active | Rationale: the python handoff names sol ("carried the deep review rounds well this arc"); you also executed the step-7 forgery probe that proved the defect, so you know the attack shape firsthand.

## The design under consult (from the handoff `1794450000000-crosslang-handoff-vm-provenance.md` — read it, plus the brief's named authorities)

**The defect (you proved it)**: python exception wrappers stamp their retained condition into the
guest-visible cell map (`:py/retained-condition` key); any admitted guest can clone a wrapper cell
and forge the stamp; egress forwards the forged condition (executed on all three backends).

**The proposed fix**: move the stamp OUT of the entry's `:value` into a **host-only sibling field
on the heap entry** (proposed `:yang.call/provenance`, sibling of `:seal`/`:value`):
- write-once when the runtime mints a wrapper, via a **reserved-prefix door** (a name guest source
  cannot spell — the `cell/dsg__*` regime, like the declined `:retained` seam's mint);
- read only via a reserved query door (host identity check at egress);
- GC-with-entry (lives in the heap map; no registry, no leak);
- `cell/set!` writes only `:value` and must NOT disturb the sibling field; `heap-write` preserves it.

## Consult questions — rule on each with file:line evidence

1. **The heap-entry field**: is a sibling field on the heap entry the right storage (vs. a side
   map keyed by id, vs. a closure-carried carrier)? Verify against `heap-write`/`heap-write-retained`
   history (the declined `:retained` seam's diff is instructive — `git show e735ebb2` shows its
   removal; the design was sound, the authorization was not, for the outcome encoding).
2. **Write-once enforcement**: `heap-write` currently rewrites `:value`/`:seal` and must preserve
   the sibling. Is "preserve on write + no guest door writes it" sufficient, or does `cell/set!`
   need an explicit guard? (The declined seam refused writes on marked entries — is refusal needed
   here, or is the field simply not addressable by any guest-reachable effect?)
3. **The doors**: exact reserved names and arity for mint + read. Verify the reserved-prefix regime
   (`check-program-symbols!`, clojure.cljc:2482-2506 pattern) blocks guest spelling in ALL FOUR
   frontends — including the macro-expansion lane caveat astra flagged (known-gaps S2).
4. **GC-with-entry**: confirm the field is reclaimed with the entry (no separate lifetime), and
   that no lane (semantic/stack/register/debruijn) rebuilds heap entries in a way that drops it.
5. **Cross-frontend ruling (owner condition (b))**: rule the adoption map —
   - **python: adopt now** (the defect's owner).
   - **php: adopt at its round's start** (its wrappers will be VM cells; same exposure).
   - **clojure: the port consumes it for protocol-condition retention** — the port brief claimed
     "no retention object needed"; verify that claim against the R3 protocol-rethrow requirement
     (a clojure-native qualified condition caught and rethrown by clojure source: does the
     continuation encoding preserve the qualified reason without retention?). If retention IS
     needed, the port adopts this primitive in place of the witness — retiring the documented
     replay/mutation hole. Rule on it explicitly.
   - **js: grandfather the `js.rt` identity store** (shipped, sound) or migrate — recommend, with
     urgency.
6. **The uniformity question**: if multiple frontends adopt, is this ONE primitive with per-frontend
   mint/read doors, or per-frontend fields? Rule on the shape that keeps yin.vm minimal AND the
   frontends uniform (the owner's standing priorities: minimal kernel, no per-frontend compensation,
   four-frontend uniformity).

## Constraints (binding)

- Ruling v2 §R2 lifetime rules: identity-owned, outside source-writable attributes, no ambient
  registry, non-stacking, formatting never invokes foreign methods.
- R5: nothing routes on condition-map shape; the provenance check is identity/issuer, not shape.
- The owner's NO declined the OUTCOME-encoding wrapper's compensation (`:retained` for the outcome
  channel); the convergence directive (`1794372000000` FINAL DIRECTIVE) then moved clojure onto the
  continuation encoding. This primitive is for the continuation encoding's uniform R3 contract —
  rule within THAT frame; do not re-litigate the convergence.
- The acceptance contract is committed RED: `exception-py-r2r3` @ `0326a044`
  (`test/yang/call/python_exception_test.clj` forgery pins). Do not rewrite it.

## Deliverable

Per-question rulings with evidence; the exact design (field name, door names/arities, write/read/
GC semantics, lane impacts); the adoption map; and any AMENDMENTS to the proposed design. End with:
**DESIGN APPROVED AS PROPOSED / APPROVED WITH AMENDMENTS (list) / REDESIGN REQUIRED (why)**.
Read-only. Begin with Completed-GMT/Completed-Local.
