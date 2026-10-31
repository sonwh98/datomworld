Created-GMT: 2026-10-01 14:20:00 GMT
Created-Local: 2026-10-01 21:20:00 +0700
Coding-Agent: zcode (GLM-5.3-Flash subagent)
Session-ID: zcode-subagent (yin.repl DHT loading docs)

# Task: Document the DHT publish/require flow in yin.repl.md

Role: Docs / Scoped Subagent (ZCode subagent, GLM-5.3-Flash)

Repository: /Users/sto/workspace/datomworld (branch master @ df7cf1f4).
Target file: src/cljc/yin/vm/docs/yin.repl.md — add a section documenting
the DHT-backed module loading flow that the linker-over-DHT epic (L0-L5,
just landed) delivered, so a user can actually do it.

Read first, for accuracy (treat as sources, verify against the code):
- src/cljc/yin/vm/docs/yin.repl.md — the existing structure, tone, and
  where the new section belongs
- src/cljc/yin/repl/main.cljc — the actual CLI flags: dht:<dir>,
  --dht-peer, --dht-keygen, --dht-key, --dht-publish, --dht-manifest,
  --dht-principal; the banner line naming the signing principal
- src/cljc/yin/repl/link.cljc and src/cljc/yin/vm/linker/publish.cljc —
  what (yin.link/publish 'my.lib '[f g]) does: manifest materialization
  (canonical tree + per-format derivation records), Ed25519 signing from
  the key file, DHT publication with rows every round, the
  "dht: published :segment/... — acknowledged" output
- src/cljc/yin/repl.cljc — require parking (";; require pending:
  my.lib") and resumption on the load event
- test/yin/repl/dht_process_test.clj and
  test/yin/vm/linker/dht_end_to_end_test.cljc — the proven end-to-end
  scenarios (publish in process A; require by name in process B on JVM
  and Node readers, on all four VMs via (vm :type); the plain-Clojure
  leg: dao.space.dht/join -> load-index -> names -> load-module -> link
  with section 9 failures as data)
- docs/design/yin.vm.linker.dht.md — the design, for the verification
  chain: every fetched byte verified against its address, the manifest
  must declare the name it was resolved under, Ed25519 principal
  verification, same-address consensus, automatic repair, isolated
  store slice, dangling retraction as a global diagnostic

Section content (a user-facing how-to, in the doc's own voice):
1. Starting yin.repl with a DHT store (the flags, what each means).
2. Publishing a module by signed name (publish, the banner, the
   acknowledged line, what the manifest contains).
3. Loading it in another yin.repl (principal + manifest address or
   join; require; pending; per-VM evaluation).
4. The trust chain in brief (address verification at every hop, signed
   names, name-declares-itself, consensus and repair) with a pointer
   to docs/design/yin.vm.linker.dht.md for the design.
5. The plain-Clojure path for non-REPL clients.
6. Operational notes: keep the key file to keep publishing under the
   same principal; receivers verify against the publisher's principal;
   rebuild the node reader (bb build:yin-repl-node) before the process
   tests; fresh worktrees need clj -M:antlr-gen before JVM test runs.

Constraints: touch ONLY src/cljc/yin/vm/docs/yin.repl.md; match the
existing document's voice and structure; pure ASCII, <= 80 columns on
every added line; no commit/stage; no other files.

Begin the final response exactly with:
Completed-GMT: <YYYY-MM-DD HH:MM:SS GMT>
Completed-Local: <YYYY-MM-DD HH:MM:SS local-timezone-name>

End with exactly one line:
Status: COMPLETE
or
Status: BLOCKED — <reason>
