Completed-GMT: 2026-11-14 08:21:40 GMT
Completed-Local: 2026-11-14 03:21:40 EST

The corpus rebuild is **incomplete: 8 of 155 fixtures passed, none were refused, and 147 remain not run**. The progress record now retains each pass’s input, snapshot, evidence, profile, and row-root addresses.

After interrupting the first batch, I resumed it in a fresh process and recorded another pass. A separate process reopened the scratch store, fetched all 48 addresses for the eight passes, and checked their admission coordinates. The progress file and store remain under `target/`.

The focused tests pass, clj-kondo reports zero errors or warnings, and the frozen site-table hash is unchanged. No Jupiter bounds declaration, tagged verification run, verification result, or companion record was completed. Nothing was committed.

The partial results and resume instructions are in [the r9b findings](/home/sto/workspace/datomworld-d1/collab/yang-clojure/1794700000000-engineer-d1-r9b-corpus-rebuild.gpt-6.1-terra.findings.md).