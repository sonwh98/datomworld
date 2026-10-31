# Architect second opinion: linker over dao.jing.dht

Role: Architect (read-only) on master c66809fa.
- Lead design by claude-fable-5-1: collab/1790806000000-architect-linker-over-dht.claude-fable-5-1.findings.md.
- Original brief, with the owner invariants: collab/1790806000000-architect-linker-over-dht.prompt.md.

Challenge the lead design adversarially. Verify each "what the tree holds today" claim against the code, citing file:line. For example:
- no host driver re-checks pending runs;
- the tree materializer has no caller;
- the authority fold is wired to nothing.
Then judge:
1. The staged-load content source (the local kind over the node store, loading the closure before linking) versus a content-client over the node's rings. Is deferring the persistent stepped linker sound?
2. Publishing both the index and module content, and whether that violates derive-don't-persist.
3. The index-derived name environment via signed envelopes. The Ed25519 host seam. Carriers erasing attested proofs.
4. Authority: declared principals as composition data; ambiguity refusal; the ShiBi direction.
5. Evaluation entry: pending links, the re-check driven by node events, no lease, the four VMs, failure as data.
6. Slices L0–L5: is the ordering right, and would each acceptance catch its property breaking?
7. The seven OWNER decisions: agree or disagree with each recommendation, and say what is missing.
Check against the owner invariants:
- plain Clojure and yin.repl share one path via host functions;
- share code over the stream linker;
- P2P with no privilege;
- derive, don't persist;
- dao.jing is passive;
- code as datoms.
End with AGREE / AGREE-WITH-CHANGES / DISAGREE per section, and a consolidated list of owner decisions.
