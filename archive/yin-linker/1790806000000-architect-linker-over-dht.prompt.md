# Architect design pass: load and evaluate code from dao.jing.dht via the linker

Role: Lead System Architect (docs/agents/roles/architect.md). This is a read-only DESIGN pass on master (c66809fa). Do not edit code.
Lead: claude-fable-5-1. A second opinion from gpt-6-sol or gemini follows.

## Owner request
Queued by the owner on 2026-10-01, after this exchange. The owner asked whether another yin.repl attached to the same dao.jing.dht can use dao.space.query to query stored code AND load that code for evaluation. The answer was: querying yes (S5); loading for evaluation no. Those are separate paths:
- The code index is datoms plus a manifest, in the `--index-store`.
- The linker (yin.repl.link, yin.vm.linker.md) resolves name → manifest address through a name environment, and links module images over a dao.jing content pair (`:content-store` / `:content-client`).
Owner, verbatim: "queue the linker design pass after S5".

## Binding owner invariants
- "plain clojure code should be able to query for code in the dht. the yin.repl should use the same path as the clojure repl via host-functions" (2026-10-01). Any load path must be a plain-Clojure API that yin.repl reuses through host functions.
- Share code over the stream linker: the linker boundary is dao.stream, and local vs remote linking is just a stream. See memory notes project_invariant_share_code_over_stream_linker and project_linker_is_stream_boundary, summarized in docs.
- dao.stream is P2P: no server/client, no privileged node, NAT-traversing in principle.
- Derive, don't persist: do not add structure a Datalog query over existing rows can derive.
- dao.jing is syntax and agents are semantics: the store stays passive and payload-agnostic.
- Code and continuations are datoms.

## Questions to answer
1. **Content source.** How the DHT becomes the linker's content source. Is it a DHT-backed `:content-client` over the `:jing/*` convention? How does it relate to `dao.space.dht/join`?
2. **What gets published.** Linkable module images, the code index, or both? Can the linker's image be DERIVED from the code index (datoms), or is it a separate artifact? Judge this against derive-don't-persist.
3. **Naming and discovery.** How does a second process learn name → manifest address? Weigh:
   - an index-derived name environment (a query over `:yin/name` datoms in a loaded index);
   - a published name-environment root;
   - handing addresses over some other way.
   Root or latest-root discovery was out of the DHT epic's scope; say what the minimal honest step is and what stays deferred.
4. **Authority and trust.** yin.vm.linker.authority exists. Who may bind a name? What stops a peer from shadowing a name? How does the ShiBi capability direction (memory: ShiBi is a tuple space) bear on it?
5. **Evaluation semantics.**
   - How loaded code enters a receiving session's VM: link-policy, pending links, lease.
   - Across all four VMs.
   - Failure outcomes as data.
6. **Slices.** A slice plan with acceptance criteria and the files each slice touches. Include a REPL end-to-end:
   - process A defines functions and publishes;
   - process B joins, loads by name, and evaluates;
   - plain Clojure does the same through the same API.
7. **OWNER decisions.** Anything that needs the owner's call.

## Read first
- docs/design/yin.vm.linker.md
- docs/design/yin.repl.link-policy.md
- docs/design/yin.vm.debruijn.linker.md
- docs/design/dao.jing.dht.md
- docs/design/yin.repl.dao.space-index.md
- src/cljc/yin/repl/link.cljc
- src/cljc/dao/space/dht.cljc
- src/cljc/yin/repl/dht.cljc
- src/cljc/yin/vm/linker*.cljc
- collab/1790795000000-repl-engineer-dht-s5-repl-integration.claude-opus-5-5.report*.md

Output: a findings document with the sections above, ending in a slice plan and a list of OWNER decisions.
