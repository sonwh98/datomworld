Completed-GMT: 2026-11-17 00:35:14 GMT  
Completed-Local: 2026-11-17 07:35:14 Asia/Ho_Chi_Minh

The retained-input fixture gate is integrated and tested, but **the declaration and bounded corpus verification are not complete**. No companion or verification result was published.

A pre-declaration pilot passed two retained fixtures through replay, structural checks, both guarded lanes and scoped attribution queries. The larger fixture took **69,936 ms** including fetch and replay, exceeding the draft’s non-binding 60,000 ms candidate. That candidate was never frozen; a complete declaration still needs the full claim set, phase and baseline measurements, and remaining enforcement work.

The gate now refuses incomplete bounds, checks tree limits before lane execution, monitors lane limits, and preserves measured exceedances with the quantity, value and ceiling. It explicitly does **not** claim complete D1 recipe, image-derivation or instruction-provenance verification.

The [r11 findings](/Users/sto/workspace/datomworld-d1/collab/yang-clojure/1794731000000-engineer-d1-r11-declaration-verifier-integration.gpt-6.1-terra.findings.md) and [retained-path pilot outcomes](/Users/sto/workspace/datomworld-d1/collab/yang-clojure/1794731000000-engineer-d1-r11-retained-pilot.edn) are in the worktree mirror. Focused tests passed **33 tests / 114 assertions**; kondo reported zero errors and warnings. The frozen v1 evidence is unchanged, the store remains uncommitted, and no commit was made.