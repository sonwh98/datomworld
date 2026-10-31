Created-Local: 2026-10-12 15:00 +07
To: the yang.python (C4) orchestrator, relayed by the owner

The quick tier on master (e0b0006f, includes your I3 slice 544e99e1) fails one test in the dao.jing shard:

  dao.jing.cbor-conformance-test/no-source-file-that-names-the-resource-writes-files (cbor_conformance_test.cljc:1028)
  offender: test/yang/python/antlr/dht_import_fixture.cljc, calls `spit` and requires dao.jing.cbor-fixtures

The conformance test forbids any source file that names the CBOR resource and writes files. It passed on e5df547a. Everything else passes (12 of 13 shards, the failing one only for this test). Please fix in your track (move the spit away from files that name the resource, or adjust the helper). Heads-up: our slice `blake3-rest` (branch blake3-rest, in review) regenerates test/resources/dao/jing/cbor-v1.json (field sha256 becomes blake3) and edits cbor_conformance_test.cljc; if your fixtures read the `sha256` field of that JSON, they will need `blake3`.
