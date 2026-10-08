# Architect sign-off: linker over DHT, slice L1 (staged load, backlog, publication result)

Role: Architect (read-only).
- Worktree: /Users/sto/workspace/datomworld-linker-l1, branch linker-l1, from linker-dht a1f41db3. L1 is uncommitted; use `git diff`.
- The author is claude-opus-5-5.
- Contract: docs/design/yin.vm.linker.dht.md §4.3, §5.5 (all of it), §9, §10, §11, and §12 L1, which lists the files and acceptance.
- Owner decisions: collab/1790808000000-orchestrator-linker-over-dht-owner-decisions.md (repair is automatic while open).
- Report: collab/1790812000000-engineer-linker-L1.claude-opus-5-5.report.md.

Verify adversarially:
1. Every L1 acceptance bullet, especially those the author flags as inferred or not directly asserted:
   - "No put ever waits."
   - "No repair cycle ever opens" for a shared-fact acknowledgement.
   - The ledger invariant, which is not re-checked in the REPL tests.
   - The REPL partial case, which refuses all non-manifest stores because chunk frames carry no address.
   Decide whether each is acceptable or must be asserted.
2. §5.5:
   - fixed ledgers;
   - separate fresh and repair queues with repair admission first;
   - batch and capacity arithmetic;
   - :max-open overflow, including the bug the author found and fixed;
   - shared-address accounting;
   - cancellation;
   - :republished on every result change;
   - backoff and stop conditions;
   - "outstanding until /replicated or /unacknowledged" vs the contract's pacing.
3. The generic load:
   - walk outcomes;
   - closed defect codes, equal on all hosts;
   - :busy vs :unaskable.
4. New public functions not named in the contract: backlog, publications, publication, ack-peers. Also the refusal ex-info codes for retry!, cancel! and forget. Acceptable, or should the contract name them?
5. The busy-client tests swap the node's internal request writer. Is that acceptable test access?
6. Owner invariants:
   - plain Clojure and REPL share one path;
   - no yin.* dependency in dao.space.dht;
   - dao.jing untouched;
   - P2P.
7. Portability traps.

End with an explicit verdict: SIGN-OFF GRANTED or WITHHELD, with findings as a Severity | file:line | issue | fix table.
