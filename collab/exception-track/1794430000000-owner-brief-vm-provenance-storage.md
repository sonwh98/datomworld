Created-GMT: 2026-11-11 1x:xx GMT
Created-Local: 2026-11-11 1x:xx +0700
From: yang.python seat (round owner: exception-py-r2r3)
To: the owner (relayed)
Re: authorization decision — private sealed-entry storage for retained-condition provenance

# OWNER BRIEF — one decision, with a recommendation

## The situation in three sentences

The step-7 review confirmed (executed probe, all three JVM backends) that the py round's retained-condition provenance is forgeable: admitted Clojure source can clone a python exception cell and add the retention slot, and egress forwards the forged condition. The correct fix needs **private storage on each wrapper's sealed heap entry** — a change to `yin.vm.engine`/`yin.vm.module` plus Python initialization — which is outside the python seat's ceded surface. The fix round stopped honestly with its acceptance pins already written and red (3 tests / 12 assertions / 9 failures, reproducing the reviewer's exact forgery).

## Why no smaller fix exists

- Guest-visible storage (the cell's map) is forgeable by construction — that IS the confirmed defect.
- A python-side identity map is either an ambient registry (ruling v2 §R2 forbids) or has the wrong lifetime.
- The sealed heap entry is the only storage that is with the object, unreachable by guest cell operations, and reclaimed with it.
- Precedent: the JS round fixed the identical P1 inside `js.rt/*` (their own runtime surface). Python's wrappers live one layer lower, in VM cells, so the same fix class needs the VM seat's surface.

## The decision requested

**Authorize a bounded VM change** for this fix:

1. **Private per-sealed-entry storage**: write-once at wrapper creation (by the host, at the moment the runtime mints the wrapper), read only by the host's identity check, never guest-visible, reclaimed with the entry (no leak, no registry).
2. **Scope guard**: no new guest-visible vocabulary unless strictly required; if the cell module's public surface must change, the full `cell/authentic?` rigor applies (declared API change, architect second opinion before landing).
3. **Acceptance instrument** (already written, currently red): the reviewer's exact forgery pair (clojure `forge` + python raise → must emit a fresh `:yang.call/thrown` with the clone as payload, never the forged condition), a cell-clone-of-genuine pin, and an overwrite-attempt pin — plus the eight ledger fixtures and parity pins staying green.

## Recommendation

**Authorize, with the second-opinion clause.** The defect is real (executed proof), the fix class is proven (the JS round shipped its analogue), the surface change is minimal and invisible to guests, and the acceptance instrument is already in hand. Without it, the py round's R2/R3 acceptance is structurally incomplete: the retention property cannot be made authentic at any layer the python seat owns.

Also pending your word (separate, small): the P2 `type` builtin at prelude.cljc:3452 — accept it (my recommendation: it is harmless, already in the scanner goldens) and I re-scope my staged round to the remaining semantic pins, or strip it from the branch.

— yang.python seat
