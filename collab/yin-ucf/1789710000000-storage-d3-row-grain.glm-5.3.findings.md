Coding-Agent: glm
Model: glm-5.3
Role: Storage & Indexing Specialist

# D3 recommendation: dao.jing row storage grain

The recommendation is complete and written to the plan file. Since neither `ExitPlanMode` nor `AskUserQuestion` is available in this session's toolset, here is the deliverable itself:

## D3 recommendation — resolved

**Approve: individual rows as the canonical stored grain, with a pack allowed only as an optional transport envelope.** No irreducible owner judgment call remains.

**Every claim in the brief verified against source:**
- "A pack needs no new write primitive" — **confirmed**: `materialize!` takes any opaque value (`jing.cljc:334-386`); a vector of row bodies is one supported CBOR value.
- "Pack loses real dedup" — **confirmed**, scoped to *cross-tree* sharing (intra-tree DAG sharing survives inside a pack). Cross-tree sharing is a named design payoff (§4.4, §6.1).
- The walked-back invariant argument — **the walk-back is correct**: a pack is one opaque value; refs inside it still resolve explicitly; the invariant doesn't discriminate.
- Metadata prerequisite (task item 4) — **accurate and orthogonal to grain**: CBOR plan fixes storage/transport, leaves the `dao.stream` Transit intake half open (`transit.cljc:6`). One precision added: the stream path fails at *load-time merkle mismatch* (silent address divergence), not store-corruption — the test belongs where it fires.

**The storage mechanics that decide it** (grounded in the pinned Boring `:canonical` profile, stringref off):
- A child ref costs ~102 bytes; rows run ~35–350 B. **A pack saves ~zero encoding bytes** (no cross-row string dedup exists with stringref off) — its only wins are round trips and store entries.
- mem/file: row gets are free (map lookups). remote: one-call-in-flight client makes N fetches brutal — the only real cost. DHT: 1200-byte budget busts packs at ~3 rows — physics keeps DHT row-grain regardless.
- Hash verification: identical either way (merkle closure is per-row); the pack *adds* one hash.
- Pack-canonical would break §4.1's root-id naming (derivation records, manifests, `:yin.code/hash`) or force a root→pack alias table — exactly the mutable-root/alias surface dao.jing and UCF §7.3.4 refuse.

**`​:yin.k/carried`**: unchanged per UCF §7.3.4 — canonical instruction vectors inline, verified hash+grammar, "vectors, not batches." A row tree, if ever carried, travels as its row set named by root id, never a pack.

**Loader interface**: `load-rows : handle root → {:root :rows}` (BFS fetch) in front of U2/U3, which are already grain-agnostic. Pack = exact reachable closure in pinned first-encounter BFS order (deterministic → identical trees share a pack address), verified row-by-row on receipt. Deferred with trigger: a `:jing/get-batch`/`get-tree` handler — `serve-content!` already serves any `{op fn}` map (`remote.cljc:882`), so it's a handler addition when remote loading gets hot.

Full reasoning, byte arithmetic, and the post-approval doc steps are in the plan file. No implementation was done, per the task's own constraint.
