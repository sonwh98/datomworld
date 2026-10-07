# Architect sign-off: linker over DHT, slice L4 (names in the index, publish, keys, rows every round)

Role: Architect (read-only).
- Worktree: /Users/sto/workspace/datomworld-linker-l2, branch linker-l4, from linker-l3 bd9bfae7. L4 is uncommitted (15 files); use `git diff`.
- The author is claude-opus-5-5.
- Contract: docs/design/yin.vm.linker.dht.md §5.1, §5.3, §5.4, §6 (envelope, proof, signed bytes, key file, republish), §7 (resolution, snapshot set, fold, transitive requires), §9, §10, §12 L4.
- Owner decisions: collab/1790808000000-orchestrator-linker-over-dht-owner-decisions.md (rows every round; stable file key; Ed25519 on Dart; same-address consensus; automatic repair).
- Owner invariants: plain Clojure and yin.repl share one path via host functions; dao.stream is P2P with no privilege; derive, don't persist; dao.jing is passive; code as datoms.
- Engineer report: the end of collab/1790831000000-engineer-linker-L4.claude-opus-5-5.stdout.log.

Verify adversarially:
- Every L4 acceptance bullet: rows each round; partial/repair; never-accepting peers; publish and refusals; republish; the fold over the snapshot set; only HEAD and loaded indexes folded; key file cases; sequence after restart.
- Signing uses only the L0 seam.
- The declared principals come from composition, never from the index.
- The REPL host functions (yin.link publish/names, dao.space.dht retry) are thin wrappers with no second path.
- The seed is never printed or persisted silently.
- Portability traps.

RULE on the engineer's findings:
1. **SPEC GAP §5.3:** a publisher's own `(require 'base)` typed as a separate program is not collected into the derived tree, so a reader fails `Unable to resolve symbol: base/f`. Options:
   - (a) 5.3 also collects each declared module's latest requiring program;
   - (b) the publisher emits the requires itself;
   - (c) other.
   Give exact amended contract text, and say whether it can ride L4 or needs a follow-up slice.
2. Section 9 name refusals now have their full shapes (:absent with :diagnostics, :ambiguous-name).
3. A §5.1 behaviour change: a write-refusing store fails at :materialize, and that round doesn't publish. Two existing tests updated.
4. HEAD is read from disk on every fold, so cost grows with index size.
5. (reset) drops host modules, including yin.link and dao.space.dht.
6. Dart cannot chmod the key file.
7. The 5.1 banner line lives in yin.repl.main.
8. The publish answer shape is {:module :address :links}; refusals are raised under the reason keyword.

End with an explicit verdict: SIGN-OFF GRANTED or WITHHELD, with findings as a Severity | file:line | issue | fix table, then the rulings.
