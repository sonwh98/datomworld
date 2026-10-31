Completed-GMT: 2026-10-05 13:01:16 GMT
Completed-Local: 2026-10-05 20:01:16 Asia/Ho_Chi_Minh
Coding-Agent: codex
Session-ID: 01a10c19-e77f-7631-b9d5-f26df7a7ff2f

Read-only review completed. No files edited. “Resolved” below means resolved in the proposed design and acceptance criteria, not implemented.

| Round-1 finding | Verdict | Document evidence |
|---|---|---|
| 1. Sequence zero | **resolved** | Nil floor accepts zero: 418–446. H0/H2 test it: 1029–1035, 1138–1140. |
| 2. Candidate snapshots | **resolved** | Explicit/installed holders determine snapshots; candidate-only loads are excluded: 523–529. H2 explicitly changes `snapshots`: 1125–1126; tests: 1142–1150. |
| 3. Alias identity | **resolved** | Lookup names resolve to actual ring descriptors: 196–235. Contract amendments and identity tests: 1066–1104. |
| 4. Poisoned floor | **partly** | Installed-only floor fixes rollback comparison: 417–460. Candidate scheduling can still exclude or block valid heads; findings below. |
| 5. Crash ordering | **resolved** | Confirm → persist → install: 596–617. Crash/write-failure criteria: 1174–1181, 1215–1221. |
| 6. Public reflector | **resolved for H0–H4** | Loopback restriction: 724–727; acceptance tests: 1105–1108, 1222–1223. Public serving remains contingent on separately reviewed H5. |
| 7. Shared loads | **resolved in principle** | Per-consumer holders and selective release: 505–521. Shared-manifest tests: 1182–1188. Retry and compatibility details remain incomplete. |
| 8. Publisher recovery | **resolved** | Explicit `--dht-recover`: 680–692. Governing-document amendment and recovery tests: 1233–1248. |

The revised snapshot rule correctly addresses the existing implementations: `src/cljc/yin/vm/linker/dht.cljc:153–160` folds every loaded index, and `src/cljc/dao/space/dht.cljc:1222–1227` currently supplies that unrestricted list.

New actionable findings:

- **P1 | docs/design/yin.vm.linker.dht.head.md:468 | Candidate eviction still permits starvation.**  
  **Evidence:** candidates retain unloadable traces during retries (490–496), but admission drops the least sequence above capacity (468–470; default capacity four at 873–875). Four unavailable signed high-sequence traces therefore cause a valid lower-sequence head to be discarded before it receives a load turn. This contradicts the claimed lower-loadable-head protection at 494–495 and 1158–1159. No key forgery is required to replay existing signed traces.  
  **Concrete fix:** make retry-delayed candidates yield admission capacity to fresh candidates, with bounded fair scheduling. Specify holder release on eviction. Test four unavailable higher candidates followed by a lower, locally loadable valid head.

- **P1 | docs/design/yin.vm.linker.dht.head.md:471 | An unfinished load can block newer heads.**  
  **Evidence:** only one candidate per principal may load; no preemption or bounded concurrent alternative is specified. Release defers removal until the load ends (512–514). Existing code waits while fetching has no completion (`src/cljc/dao/space/dht.cljc:1263–1266`). The proposal itself preserves deadline-free fetching (73–76). Consequently, the superseded-candidate criterion at 1160–1163 does not follow from the rules: head 12 cannot bypass an unfinished load of head 11.  
  **Concrete fix:** specify bounded concurrent candidate loads or explicit preemption/detachment with bounded cleanup. Test a permanently unfinished old candidate while a newer head is entirely local; the newer head must install without terminating the old fetch by deadline.

- **P2 | docs/design/yin.vm.linker.dht.head.md:490 | Retrying a failed shared load lacks a defined transition.**  
  **Evidence:** unloadable candidates are scheduled again, but `dao.space.dht/load` preserves failed records (`src/cljc/dao/space/dht.cljc:1157–1169`). Releasing/reacquiring the candidate holder cannot clear that record when another holder retains it. The revision specifies adding holders and removing records, but no retry operation that refreshes a failed shared record.  
  **Concrete fix:** define an explicit failed-load restart transition preserving all holders, including its event semantics. Test failure, continued ownership by another consumer, subsequent blob availability, and successful follower retry.

- **P2 | docs/design/yin.vm.linker.dht.head.md:1112 | H1 cannot satisfy its unchanged-test requirement.**  
  **Evidence:** `forget` becomes release, including during loading (512–515). H1 requires every existing load test to pass unchanged (1112–1113), but `test/dao/space/dht_test.cljc:721–727` explicitly requires `forget` during loading to throw `::dht/loading`.  
  **Concrete fix:** either preserve `forget`’s loading refusal while allowing `release`, or explicitly migrate that test and document the changed contract in H1.

- **P2 | docs/design/yin.vm.linker.dht.head.md:738 | H5’s cookie placement does not cover fragmented requests.**  
  **Evidence:** the proposed cookie is an open key on the remote request, checked before channel allocation. UDP fragmentation replaces that request with envelopes containing only id, part, parts, direction, and bytes (`src/cljc/dao/stream/udp.cljc:135–149`); reassembly occurs before the whole request is deposited (`312–325`). The gate cannot inspect an inner request cookie before reassembly. H5 nevertheless requires gating fragments (1268–1271), while its expected files omit the UDP framing implementation and remote transport contract.  
  **Concrete fix:** require the H5 design note to specify cookie-bearing outer fragment framing, pre-reassembly validation, and revised byte budgets. Add `udp.cljc` and `dao.stream.remote.md` to its scope. Keep public serving disabled until that review passes.

N1’s volatile-follower relay prohibition is sound (618–622). N2’s loopback staging and later preallocation cookie gate are sound directions, subject to the H5 framing finding. N3 suppresses tokens on non-serving binds (693–697; test 1222–1223). N4 adds reset coverage (1046–1052), consistent with history carry-over in `src/cljc/yin/repl/index.cljc:222–233`.

The new lookup and holder mechanisms introduce no inherent violation of apply/rpc independence, clock-free linking, principal authority, or separate db-values. Persisting signed observations supplies evidence unavailable from the reader’s own index. H5 may amend the frozen DHT contract through the separately reviewed process it explicitly requires; “frozen” is accurately cited from `docs/design/dao.jing.dht.md:3`. The amendment must keep head semantics outside the DHT.

The extra detail mostly repairs necessary correctness gaps. Automatic relaying, multiple sources, and public transport hardening expand the initial delivery scope. A simpler sound first milestone is one explicitly configured publisher source, durable installed-head persistence, isolated candidate loads, and loopback REPL following; defer automatic relaying and multi-source scheduling. Cross-machine use still requires reviewed transport hardening.

Blocked from adoption by candidate starvation and unfinished-load blocking; also resolve shared-load retry semantics, H1’s contradictory test criterion, and H5’s fragment-gating scope.
