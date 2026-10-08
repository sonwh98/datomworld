# Architect sign-off: linker over DHT, slice L2 (module load and link over the DHT by address)

Role: Architect (read-only).
- Worktree: /Users/sto/workspace/datomworld-linker-l2, branch linker-l2 (linker-dht + L0 + L1). L2 is uncommitted; use `git diff` and `git status`.
- The author is claude-opus-5-5.
- Contract: docs/design/yin.vm.linker.dht.md §4.2 (the closure walker), §7.4 (transitive requires / dependency bindings), §9, §10, §12 L2 (as amended by the L0 Architect: the publisher walks the closure it minted).
- Owner decisions: collab/1790808000000-orchestrator-linker-over-dht-owner-decisions.md.
- Report: collab/1790812000000-engineer-linker-L2.claude-opus-5-5.report.md.

Verify adversarially:
- Every L2 acceptance bullet.
- The walker's outcomes (missing / complete / invalid with closed codes and bounds) cover all four formats and transitive requires, with no blob unwalked.
- The publisher refuses an incomplete closure.
- Linking one format never marks a load :loaded.
- Dependency bindings: matching / missing / conflicting / consensus / direct, with real Ed25519.
- Mesh load, then link with no further fetch, and B0-equality.
- Failures carry miss causes.
- link-before-loaded is refused.

RULE on the author's decisions:
1. snapshots and names are built in L2 (HEAD excluded, deferred to L4).
2. snapshots reads the node's internal (:loads node). Should dao.space.dht expose a public list-loads?
3. The two new refusal reasons are not in §9. Should §9 be amended?
4. The untested :closure-incomplete mapping.
5. Consensus evidence is read from names provenance.

Also check: owner invariants (plain Clojure and REPL share one path; derive, don't persist; dao.jing passive; P2P) and portability traps.

End with an explicit verdict: SIGN-OFF GRANTED or WITHHELD, with findings as a Severity | file:line | issue | fix table, then the rulings.
