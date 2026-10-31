# Linker over dao.jing.dht — OWNER DECISIONS (2026-10-01)

Inputs:
- Lead design (fable): collab/1790806000000-architect-linker-over-dht.claude-fable-5-1.findings.md
- Second opinion (gpt-6-sol, AGREE-WITH-CHANGES): collab/1790806000000-architect-linker-over-dht-second-opinion.gpt-6-sol.findings.md

Owner answers (verbatim selections):
1. Rows: "Every round". Materialize every evaluated program's rows into the index store each round (fable's recommendation; gpt-6-sol disagreed). This also settles the :yin/address contract: an index address fact names retrievable content.
2. Keys: "Loaded from a file (stable)". A durable publisher identity, with key loss and replacement behaviour to be defined. Tests may use ephemeral keys.
3. Dart: "Add Ed25519 for Dart now". Signed name resolution on JVM, Node and Dart, with cross-host signature vectors.
4. Same name, same address from two declared principals: "Consensus: resolve" (the current fold behaviour). Different addresses still refuse as ambiguous.

Adopted as the Architects' shared recommendation (the orchestrator stated these; the owner did not object):
- Explicit named-export publication, derived from indexed code.
- A configured DHT link source may start a bounded load on require; the banner and help say so; pending and failure are data.
- Names come from explicitly loaded snapshots only.
- Deferred: the persistent stepped ring linker, latest-root discovery, and the lease link policy.

5. Repair (2026-10-01): "Automatic retry while open". The node re-sends failed blobs in the background, within the backlog bound, while it runs. This replaces explicit-only retry!.

6. Dangling retraction (2026-10-01): "Global diagnostic". A retraction whose assertion is outside the loaded snapshot set is reported once as a global diagnostic, not attached to a name. No envelope change.
