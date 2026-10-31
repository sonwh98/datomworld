Completed-GMT: 2026-10-05 12:48:21 GMT
Completed-Local: 2026-10-05 19:48:21 Asia/Ho_Chi_Minh
Coding-Agent: codex
Session-ID: 01a10c19-e77f-7631-b9d5-f26df7a7ff2f

Read-only review completed. No files edited. Findings below cite the proposal as `docs/design/yin.vm.linker.dht.head.md`.

1. **P1 | docs/design/yin.vm.linker.dht.head.md:338 | First contact rejects a valid sequence zero.**  
   Evidence: the initial floor is `0`; equal sequence requires an existing matching manifest or produces equivocation (lines 350–353). However, `src/cljc/dao/space/transactor.cljc:117–131` allocates the first transaction at `t = 0`. A first published index can therefore have greatest `t = 0` and never become wanted.  
   **Concrete fix:** represent absence of a floor explicitly and accept any valid nonnegative sequence on first contact. Add a first-transaction-zero test to H0/H2.

2. **P1 | docs/design/yin.vm.linker.dht.head.md:382 | Loaded candidates enter resolution before installation.**  
   Evidence: the proposed snapshot set includes *every* loaded index. `src/cljc/yin/vm/linker/dht.cljc:153–160` already implements that rule; `src/cljc/dao/space/dht.cljc:1214–1227` exposes any completed index load. Thus a candidate becomes eligible for name resolution before its head sequence is confirmed, and a rejected `:seq-mismatch` candidate remains eligible unless explicitly removed. Superseded candidates have the same problem. H2’s suggestion that snapshots need no change is incorrect.  
   **Concrete fix:** distinguish explicit pins from follower candidate loads. Construct snapshots from HEAD, explicit pins, and installed followed heads only. Test rejected and superseded candidates containing newer signed name assertions.

3. **P1 | docs/design/yin.vm.linker.dht.head.md:160 | Board aliases violate the governing logical identity contract.**  
   Evidence: the conventional principal name identifies different rings on different relays and after restart. `docs/design/dao.stream.md:277–285` requires descriptor identity to identify one transport instance’s sequence and explicitly gives copies their own identities. `docs/design/dao.stream.remote.md:159–163` preserves that rule. Although table lookup accepts aliases, `src/cljc/dao/stream/remote.cljc:509–519` still reports the attached alias as the reflection’s logical identity; learning the source descriptor does not replace it (`374–381`). A sentence permitting table aliases does not resolve this conflict.  
   **Concrete fix:** separate board lookup names from logical stream identities, with an explicit discovery/attachment transition to the actual ring descriptor. Amend the affected contracts and H1 implementation scope accordingly.

4. **P1 | docs/design/yin.vm.linker.dht.head.md:337 | An invalid wanted trace can poison the acceptance floor.**  
   Evidence: the floor includes the wanted sequence before index validation. A signed trace claiming sequence 1,000,000 over an index whose greatest `t` is 10 becomes wanted; subsequent legitimate heads at 11 and 12 are stale. Lines 247–250 specify refusal after loading but no removal/reconsideration rule. Lines 517–518 additionally forbid lowering the floor. A relay can replay such a previously signed trace without possessing the key.  
   **Concrete fix:** separate the confirmed rollback floor from candidate scheduling priority. Rejecting a sequence mismatch must discard the candidate and permit valid successors above the installed floor. Add arrival-order tests containing an invalid high-sequence candidate.

5. **P1 | docs/design/yin.vm.linker.dht.head.md:446 | The documented crash window permits rollback after installation.**  
   Evidence: lines 447–448 explicitly resume the previous persisted head if a crash occurs between installation and persistence. A reader that used sequence 20 can restart at sequence 19; a withholding relay can keep it there. This contradicts the floor-survives-restart guarantee at line 517 and the replay protection stated at lines 361–363. Atomic replacement prevents torn files, but does not make the preceding installation durable.  
   **Concrete fix:** persist the verified installed trace before exposing the new snapshot, emitting installation, or re-serving it. Specify persistence-failure behavior and test crashes at that boundary.

6. **P1 | docs/design/yin.vm.linker.dht.head.md:714 | H1 deliberately introduces an ungated public UDP reflector.**  
   Evidence: the proposal acknowledges larger replies to spoofable source addresses and defers return-path proof. `src/cljc/dao/stream/remote.cljc:148–182` answers requests through the channel; `src/cljc/dao/stream/udp.cljc:204–232` sends and fragments replies without address validation. Bounding attachments limits retained state, not reflected traffic. The existing DHT hardening requires no larger reply to an unproven source (`docs/design/dao.jing.dht.md:696–704`). Sharing its socket does not inherit that protection.  
   **Concrete fix:** include return-path validation and prevalidation reply bounds in H1, or restrict the new serving surface to loopback until hardened. Measuring amplification is insufficient as a public-serving completion criterion.

7. **P2 | docs/design/yin.vm.linker.dht.head.md:371 | Forgetting a previous head can remove another consumer’s snapshot.**  
   Evidence: loads are keyed solely by manifest address (`src/cljc/dao/space/dht.cljc:1166–1170`), and `forget` removes that shared record (`1204–1211`). Two principals can name the same index; a user can also explicitly load that manifest. Moving one principal and forgetting its old load then removes an index still installed for another principal or retained as a manual pin.  
   **Concrete fix:** define load ownership/references and forget only when no installed head, explicit pin, or pending consumer needs the record. Add shared-manifest and manual-pin overlap tests.

8. **P2 | docs/design/yin.vm.linker.dht.head.md:258 | The prescribed recovery mechanism is removed by this design.**  
   Evidence: recovery with the retained key requires hydrating the last published manifest, matching `docs/design/yin.vm.linker.dht.md:1679–1680`. But lines 501–503 remove hydration, and the replacement `--dht-manifest` expressly leaves HEAD untouched (lines 423–426 and H4). Following likewise never writes HEAD. No replacement publisher recovery operation is specified.  
   **Concrete fix:** define an explicit recovery path into a fresh publisher directory that restores transaction history and HEAD, or change the remedy to require a new key and amend the governing recovery guidance.

The checked REPL paths support derived sequence monotonicity: publication follows committed transactions (`yin/repl/index.cljc:378–397`, `400–435`), rehydration preserves `t` (`189–219`), and reset carries history forward (`222–233`). Empty rounds do not publish through `step`. This supports the approach, but does not establish the document’s universal claim for every possible plain-API HEAD write.

The capacity-1 ring claims are supported: oldest uses the earliest retained position, eviction returns that recovery position, and a fresh ring identity produces cursor mismatch (`dao/stream/ringbuffer.cljc:23–33`, `55–105`). The mirror delegates these outcomes (`dao/stream/remote.cljc:185–196`). Domain separation, declared-principal authority, separate db-values, and clock-free interpretation are sound choices. First-contact replay and withholding are correctly disclosed. The one-poll observation guarantee at lines 198–199 should nevertheless be conditional on successful transport delivery.

For section 12, existing rulings answer **question 2’s source separation**: publisher facts remain a separate db-value, and combining sources requires explicit union. They do not determine bare `q`’s default. The owner’s quoted “whatever the HEAD is” supports **question 4’s every-HEAD-move interpretation**. No cited ruling settles live-session relinking in **question 1** or default relay behavior in **question 3**. The no-backward-compatibility ruling supports rejecting old tokens.

Blocked from adoption by findings 1–6; resolve shared-load ownership and publisher recovery before implementation.
