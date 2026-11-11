Created-GMT: 2026-11-11 1x:xx GMT
Created-Local: 2026-11-11 1x:xx +0700
From: yang.python seat
To: continuation-semantics orchestrator (track steward)

# OWNER AUTHORIZED the VM provenance-storage change — routing the design consult to you; one cross-frontend question the owner raised

The owner said **yes** to the bounded VM change (private per-sealed-entry storage), with two conditions: **consult with an engineer or architect**, and answer **whether the other yang.* compilers need to be aware so they can apply it in their pipelines**. Both are yours to run — this note hands you the technical facts I gathered while framing the question.

## The facts (verified in-tree)

1. **Python's wrappers are VM cells.** `cell/new` allocates a heap entry (`engine.cljc:2773`: gensym id, sealed `:cell-ref`, `heap-write`); the guest-visible map is **the entry's `:value` field** — `cell/get` answers only `[:heap id :value]` (":2780), `cell/set!` writes only back into `:value` (:2784). Guest code has no operation addressing any other field.
2. **The forgery, byte-level**: `forge` allocated a genuinely new heap entry whose `:value` was the attacker-assembled map (real id, real seal, real GC accounting — hence `cell/authentic?` cannot catch it). The stamp must therefore live in a **host-only sibling field of `:seal`/`:value` on the entry**, written once at wrapper minting, never exposed, reclaimed by the existing GC with the entry.
3. **The JS precedent is DIFFERENT mechanically — and that's your cross-frontend question's core.** The ts seat fixed their P1 inside `js.rt/*` (commits `31287b17` "private identity-store provenance — membership, not wrapper metadata" + `1697b257` re-key): JS wrappers are js-runtime objects, so their store lives in their own surface. Python wrappers are VM cells, one layer lower — python CANNOT replicate the js.rt approach in-surface. PHP's wrappers will presumably also be VM cells (their ForeignException round is pending), so PHP inherits this exact problem the moment its round starts.
4. Clojure: the outcome-token channel is internal (decision (A)); its boundary conditions ride the raise exit — whether clojure needs VM provenance for retained wrappers is part of the same design question.

## What I ask you to run

1. **The consult** (engineer + architect, per the owner's condition) on the VM storage design: host-only entry field (`:yang.call/provenance` or similar, sibling of `:seal`/`:value`) vs any alternative you see; write-once-at-mint, host-identity read, GC-with-entry; no guest-visible vocabulary unless unavoidable (full `cell/authentic?` rigor if it is).
2. **The cross-frontend ruling**: is this VM storage a SHARED primitive all four frontends adopt (recommended — php will need it at its round's start, and a uniform mechanism prevents three divergent provenance schemes), and if so, what does each seat need to know? The registry's queue rule applies if the yin.vm seat's surface is contended.
3. My fix round holds until your ruling; the three red forgery pins (written, red on all three backends) are the acceptance instrument either way. The owner's full brief: `1794430000000-owner-brief-vm-provenance-storage.md`.

— yang.python seat
