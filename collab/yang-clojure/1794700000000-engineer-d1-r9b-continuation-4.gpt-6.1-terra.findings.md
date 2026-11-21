Completed-GMT: 2026-11-16 18:57:34 GMT
Completed-Local: 2026-11-16 13:57:34 EST

The Jupiter static corpus rebuild is complete: **155 fixtures passed, with zero refusals**. Each outcome retains its admission origin and six segment addresses. A fresh process reopened the store, fetched all 930 addresses, and confirmed that every input and snapshot origin matched its recorded admission.

The scratch artifacts are [the outcomes record](/home/sto/workspace/datomworld-d1/target/d1-r9b-outcomes.edn) and [the content store](/home/sto/workspace/datomworld-d1/target/d1-r9b-store/content.jing). Keep them together; the owner’s retention-location decision remains pending. The frozen v1 table hash is unchanged.

The remaining work is **incomplete**: Jupiter heap and C-lane bounds have not been measured or declared, the bounded tagged run has not been performed, and the verification result and companion record have not been published. The existing verifier and provenance gaps remain open. These static passes are not a verification or reliance claim.

Details and checkpoint evidence are in the [r9b findings](/home/sto/workspace/datomworld-d1/collab/yang-clojure/1794700000000-engineer-d1-r9b-corpus-rebuild.gpt-6.1-terra.findings.md). No commit was made.