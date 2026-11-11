Created-GMT: 2026-11-10 (real header below)
Coding-Agent: codex
Session-ID: pending (provider-generated)

# Task: ARCHITECT RULING — cc1 broke the cross-host publication-identity invariant (nightly bisect, measured)

Role: Lead System Architect (read-only; a measured regression with a decision to make)

## The measurement (bisect, node lane, same host, both revisions freshly generated)

  f4df9893 (master before cc1): yang.python.antlr.dht-import-test exit=0, 5 tests /
      158 assertions, 0 failures.
  b1b9abae (master with cc1):   exit=1, 155 assertions, 84 failure lines.

Three assertions vanish as well (158 -> 155): some rows never reach comparison.

## The failing invariant (verbatim from the test)

  "this host's own publication of the committed packets is the JVM's artifact:
   same manifests, blobs, envelopes"
  expected: (nil? (first-difference (fixture/parse artifact-lines) (fixture/parse lines)))
  actual:   (not (nil? {:keys [":blobs" ":indexes" ":py" ":republished" ":units"],
                        :blobs-differ [":segment/blake3-00f50692..." ":segment/blake3-13ba..." ...]}))

Same failure family in dao.stream: corpus-jvm-serves-node-decodes-over-cbor
(node shard rc=1), i.e. a JVM-produced corpus does not decode identically on node.

## Why cc1 is the suspect (from your own re-gate, recorded)

In the cc1 re-gate you wrote: "Manifests and addresses can change because
successful Clojure-donor publication now includes :yin.module/call-sites".
cc1 (fa70516f..ba2deb73) changed what the macro/publication path places in the
module record. If that field is host-dependent (map/set iteration order, host
identity, lazy seq realization, or an object identity leak), the published
blobs diverge per host and the cross-host artifact identity breaks.

## Your ruling (one adjudication; this seat is not deciding it)

1. Confirm or refute the causal link by reading the cc1 delta against the
   publication path (yin/vm/linker/publish.cljc, core-module, the datom encoder).
2. Rule which of these is correct, with reasons:
   (a) the new :yin.module/call-sites content must be canonically ordered /
       host-independent so all hosts publish identical bytes;
   (b) it must be excluded from the published artifact identity (and where);
   (c) something else the design intends.
3. State the acceptance test: which single namespace/test the fix must turn
   green, and what must NOT change (the invariant's other clauses).

Constraints: read-only; no test runs. Begin with Completed-GMT / Completed-Local.
