Created-Local: 2026-11-11 1x:xx +0700
Coding-Agent: interactive (ZCode / glm-5.3-flash, yang.python seat — round owner: exception-py-r2r3)

# To the steward — the P1 fix needs a VM-surface authorization decision (not mine to make); the round stopped honestly

Round state: dispatch 1794410000000 (sol, thread 01a1cac4). The three provenance pins are WRITTEN and confirmed failing on all three backends (3 tests / 12 assertions / 9 failures — exactly the forgery signature), kondo 0/0, no commits. The round stopped before writing any fix code because the correct fix exceeds my ceded surface:

**The finding**: python's exception wrappers are VM-sealed heap cells. Guest-visible storage (the cell's map) cannot carry runtime provenance — the reviewer's probe proved exactly that. The fix requires **private storage on each wrapper's sealed heap entry** (preserved across cell writes, reclaimed with the wrapper, no ambient registry) — i.e. changes to `yin.vm.engine` (sealed-entry storage), `yin.vm.module` (the cell vocabulary or a new effect), and Python initialization/attachment — plus focused GC/attachment tests.

**Why not a prelude-only fix**: any token stored in the guest-visible map is forgeable by the same cell ops the probe used. Any python-side side-map keyed by object identity is either an ambient registry (ruling v2 §R2 forbids) or dies with the wrong lifetime. The sealed heap entry is the only storage that is (a) with the object, (b) unreachable by guest cell ops, (c) reclaimed with it.

**Precedent**: the JS round fixed the same P1 inside `js.rt/*` (their runtime files — their surface). Python's wrapper lives one layer lower, in VM cells, so the analogous fix needs the VM seat's surface. This mirrors the `cell/authentic?` arc: a declared VM change, owner-authorized, second-opinioned.

## The authorization request (steward → owner per the cell/authentic? precedent)

Authorize a bounded VM change for this fix: private per-sealed-entry storage (write once at wrapper creation by the host, read by host identity check, never guest-visible, reclaimed with the entry). Scope guard: NO new guest-visible vocabulary beyond what the fix strictly requires; the cell module's public surface changes only if unavoidable, and then with the same rigor as `cell/authentic?` (declared API change, second opinion).

If authorized: I re-dispatch the round with the VM surface added to its scope, with the yin.vm seat notified via the registry (the registry's queue rule applies — if another seat's round touches engine/module, we sequence). The three red pins are the acceptance instrument for the fix; the eight ledger fixtures + parity pins are the no-regression gate.

— yang.python seat
