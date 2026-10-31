Completed-GMT: 2026-10-05 13:46:05 GMT
Completed-Local: 2026-10-05 20:46:05 Asia/Ho_Chi_Minh
Coding-Agent: codex
Session-ID: 01a10c19-e77f-7631-b9d5-f26df7a7ff2f

Read-only review completed. No files edited. Verdicts concern the proposed design, not implementation.

| Previous finding | Verdict | Document evidence |
|---|---|---|
| 1. Candidate eviction starvation | **resolved** | One replaceable slot removes the capacity-based exclusion: 287–300. Replay churn remains a separate limitation below. |
| 2. Unfinished load blocking | **partly** | Immediate abandonment is specified at 293–300 and tested at 863–867, but ownership and client cleanup remain incomplete. |
| 3. Failed shared-load retry | **partly** | Restart under the existing kind is specified at 322–328 and tested at 870–876. Same-kind sharing remains unsafe. |
| 4. Unchanged-test contradiction | **resolved** | `forget` remains unchanged; affected cross-kind tests are explicitly migrated: 839–842. |
| 5. Cookie versus fragments | **resolved for the core** | No UDP mirror; the deferred design explicitly covers outer-fragment proof and affected files: 615–622. |
| Round-1 finding 4: poisoned floor | **resolved** | Only installation raises the floor: 280–285, 383–389. Invalid candidates cannot permanently raise it. |

New actionable findings:

- **P1 | docs/design/yin.vm.linker.dht.head.md:119 | The identity-contract conflict remains an adoption blocker.**  
  **Evidence:** fixed board names are used as mirror identities at 143–146, with the ruling postponed to H2 at 894–900. The governing contract expressly identifies one transport instance’s sequence and distinguishes copies (`docs/design/dao.stream.md:277–285`; `dao.stream.remote.md:159–163`). Existing REPL aliases (`yin/repl/connect.cljc:72–81`, `serve.cljc:224–225`) demonstrate the same inconsistency, not permission to extend it. The reflection reports the attached alias as identity (`dao/stream/remote.cljc:509–519`). This affects following across restart and across publishers sharing a principal, before relaying exists.  
  **Concrete fix:** settle A1 before adoption. Separate lookup names from ring identities, or explicitly amend the governing identity contract with coherent descriptor and cursor semantics. Remove the claim at 629–630 that this blocks only relaying.

- **P1 | docs/design/yin.vm.linker.dht.head.md:295 | A candidate kind does not establish exclusive ownership.**  
  **Evidence:** every principal uses `:yin.head/candidate` (301–302), while loads are keyed only by address (`src/cljc/dao/space/dht.cljc:1166–1170`). Two followed principals may name the same manifest and share that record. Replacing or rejecting either candidate then abandons or forgets the other’s load. Cross-kind refusal cannot prevent this same-kind collision. Up to 64 followed principals remain supported (540–541). Installation also lacks an explicit candidate-record cleanup transition, despite promising that manual `load-index` succeeds afterward (311–312).  
  **Concrete fix:** define ownership and release for shared candidate records, including installation. A small candidate-owner set is sufficient; it need not restore the entire previous holder design. Test two principals sharing a manifest, replacement/rejection of one, and manual loading after installation.

- **P2 | docs/design/yin.vm.linker.dht.head.md:298 | Removing a load does not guarantee fetch-client cleanup.**  
  **Evidence:** `advance-loads` skips `content.step/step` when no loading records remain (`src/cljc/dao/space/dht.cljc:1313–1321`). Consequently, abandoning the last load can leave client bookkeeping undrained after the DHT finishes. Moreover, a full request ring produces an *unsent* request (`dao/jing/content/step.cljc:224–228`), which has no DHT `get-ticks` deadline because it was never submitted. The proposed “nothing accumulates” and pending-state-empty test therefore need more than deleting `:loads`.  
  **Concrete fix:** specify retirement of abandoned client interests, safe handling of the retained unsent request, and draining without active loads. Preserve requests still needed by other loads. Test abandoning the last load both before submission and after submission, plus late replies and reloading the same address.

- **P1 | docs/design/yin.vm.linker.dht.head.md:307 | The new refusal is not translated by existing host load operations.**  
  **Evidence:** `yin.repl.query` directly calls `load-index` and `load-module` without catching DHT refusals (`src/cljc/yin/repl/query.cljc:665–674`); its driver invokes that path directly at 833–834. The existing refusal translation helper applies to publication operations (`625–633`). Thus the promised “told so” outcome at 311–312 can instead throw through the host interpreter. H1 introduces the refusal but lists no query-adapter change (828–835).  
  **Concrete fix:** include refusal translation for both host load operations in H1, returning the qualified refusal as stream data. Test conflicts through the host module, including a candidate load conflicting with a manual index load.

- **P2 | docs/design/yin.vm.linker.dht.head.md:713 | Replay protection and load-frequency limits are overstated.**  
  **Evidence:** old traces are stale only below the installed floor. With floor 10, previously signed heads 11 and 12 remain eligible and can alternate with head 13, repeatedly abandoning its unfinished load under 289–300. One configured URL does not authenticate a cleartext WebSocket path. The three-outcome security account at 713–720 omits this churn; the “loaded once per process” claim at 762 is also false when two rejected traces alternate, since only the last rejected id is retained (318–320).  
  **Concrete fix:** disclose update starvation by eligible replay and bound retained fetch work. Add alternating-replay tests and qualify the once-per-process claim. Any stronger progress guarantee must require authenticated transport or a reviewed scheduling policy; signatures alone provide integrity and rollback protection.

Sequence zero, restart/reset continuity, and persist-before-install remain concretely covered at 814–826, 877–882, and 931–943. Keeping hydration solely outside `dht join` preserves the existing publisher recovery remedy without another flag.

The WebSocket composition claims are supported: `ws-project/make-acceptor` accepts an arbitrary table (`161–189`), `accept-step!` calls `remote/mirror-step` per session (`270–276`), and `dial` composes reflected handles (`305–324`). `yin.repl.serve` already supplies a two-entry table (`224–225`, `288–291`). The cited mirror-call range should extend through line 276. Session bounds, step budgets, listener reuse, and TCP binding are honestly marked unverified at 694–704; handoff slots alone do not bound established sessions.

Using the same numeric TCP and UDP port is feasible, but requires two independent successful binds. The core specifies a clean bind failure; the reserved `ws:<port>` suffix addresses differing port numbers later, but does not solve differing advertised hosts, reverse-proxy paths, or two unreachable NAT endpoints.

The rewrite is materially simpler. Much of the 1071 lines is rationale and future transport planning that could move to a companion note; the core still needs explicit load ownership, cleanup, and identity semantics, so those cannot be cut merely to reduce length.

The owner’s question, independently: **Cross-machine following should preserve the signed trace, installed-head persistence, and linker resolution because the follower consumes a `dao.stream` reader handle. It still requires verified transport composition, reachability and resource controls, and resolution of the identity-contract conflict, so it is an extension rather than a complete redesign—not merely removing the loopback check.**

Blocked from adoption as the core design by unresolved stream identity, shared candidate ownership, abandonment cleanup, host refusal translation, and inaccurate replay guarantees.
