Created-GMT: 2026-11-11 1x:xx GMT
Created-Local: 2026-11-11 1x:xx +0700
Coding-Agent: codex
Session-ID: pending (provider-generated; capture thread.started.thread_id)

# Task: exception-py-r2r3 fix round — the P1 retained-condition provenance forgery (step-7 review FIX-LIST)

Role: Yang Compiler and Universal AST Engineer
Implementers:
- Model: codex gpt-6.1-sol | Assigned: 2026-11-11 | Status: active

WORKTREE: /Users/sto/workspace/datomworld-f12, branch `exception-py-r2r3` (tip 16c6294f + the golden regen 04cb7887). You are the only writer.

## The P1 (confirmed by an EXECUTED probe on all three JVM backends — not static)

Review record: `collab/exception-track/1794402000000-architect-py-r2r3-step7-review.gpt-6.1-sol.stdout.log` (last messages).

The reader at prelude.cljc:1058 authenticates retention using only `:py/type :instance` + an ordinary cell slot (`:py/retained-condition`), consumed at :917. Executed forgery: Clojure source `(defn forge [e condition] (cell/new (assoc (cell/get e) :py/retained-condition condition)))`, a fresh Python `Exception('fresh')` passed through it, raised from Python — egress forwarded the supplied `:yang.call/not-callable` condition on register/semantic/stack (`:forged-condition-forwarded true`). The same access overwrites a GENUINE wrapper's association. Guest cell storage cannot carry runtime provenance.

## The fix (the ruling v2 §R2 rule and the JS round's precedent)

Authenticate retention against the **exact runtime-created wrapper identity**, via provenance guest operations cannot mint, copy, or overwrite. Constraints:
- NO ambient registry (ruling v2 §R2): the provenance must live with the wrapper object itself — e.g. a host-side identity token minted at wrapper construction, stored OUTSIDE the guest-visible cell/map fields (a hidden field the guest attribute protocol cannot read or write — check how py attribute get/set dispatch works and where instance state actually lives; if instance state IS the guest-visible map, then the token must live in a host-side side-map keyed by object identity, which is NOT an ambient registry: it is per-wrapper, created with the wrapper, unreachable except through the host's own identity).
- `cell/authentic?` alone is INSUFFICIENT (the reviewer's note: the forged clone is a genuinely allocated cell).
- Preserve lifetime and reclamation (side-map entries die with the wrapper — weak/identity semantics, no leak).
- A COPY of a genuine wrapper (cell clone) must NOT authenticate (the copy lacks the minted token or the token does not match the copy's identity).
- Overwriting a genuine wrapper's guest-visible fields must not corrupt the retained association.

## Required pins (each failing before, passing after)

1. The reviewer's exact forgery pair: clojure `forge` + python raise → after the fix, egress emits a fresh `:yang.call/thrown` whose payload is the clone (NOT the forged condition).
2. Cell-clone of a genuine wrapper → does not forward the original condition (fresh thrown, payload the clone).
3. Overwrite attempt: guest sets `:py/retained-condition` on a genuine wrapper → forwarding still uses the runtime-retained original.
4. The genuine paths stay green: the existing eight ledger fixtures + the parity pins (be87df74).

## The P2 (do NOT fix in this round — scope discipline)

The `type` builtin at :3452 is outside the ceded surface; the steward and owner hold that decision (the python seat has a staged brief). Leave it untouched; note its presence in your report.

## Rules

Ceded regions only (:912/:1034/:1045/:1046, located by content). JVM lane only, focused `-n` runs in background nohup pattern (the command timeout kills foreground clj). Never run `yang.call.exception-contract-test` locally. Kondo 0/0 on touched files. If the prelude changes, regenerate the scanner goldens (`deferred #{:py}`, three-run byte-identical, abort-gate verifies untouched groups). Commit: (a) the fix + provenance token, (b) the three forgery pins, (c) goldens if regenerated.

## Deliverable

Final agent message: the fix design (where the token lives, how identity is checked, why it is not an ambient registry), the pins' before/after, suite counts, goldens evidence.
