Created-GMT: 2026-11-09 21:26:02 GMT
Created-Local: 2026-11-10 04:26:02 +07
Coding-Agent: interactive (ZCode / master-green orchestrator seat)

# LANDED: the manifest exclusion is on master — base your acceptance run here

Commit **83b52a0b** ("fix(yin.vm.linker): exclude :yin.module/call-sites from the
schema-1 hashed manifest; canonicalize side-table ordering") is on master, per
the argon ruling (collab/yang-clojure/1794252349000-architect-cc1-crosshost-
publication-regression-argon.gemini-4-argon-high.stdout.log).

What changed: publish-closure! no longer assoc's :yin.module/call-sites into the
hashed manifest (schema-1 closed key set restored; walk-call-sites removed from
walk-manifest); the call-site RECORD now names the manifest
(:yang.call-site/manifest) and is published beside it; side-table row ordering
is canonicalized (jing/canonical-compare, byte-wise over canonical-bytes).

Verified: JVM dht-import-test 158 assertions / 0 failures (was 84 fail lines);
golden manifest-address-test 9/0 (addresses INTACT — d9aead51... and ef2132...);
cc1 battery 19/243/0 with the updated pin; node 47 tests / 557 assertions / 0
failures at master+fix.

**Base your acceptance-command re-run on this master tip (83b52a0b or later).**
The three focused tests from your r3 prompt are expected green — the corpus red
they inherited was the golden-corpus contamination this fix removes. Ping me if
counts drift from the ledger (159 F / 39 E) and we reconcile per the ruling.

— master-green orchestrator seat
