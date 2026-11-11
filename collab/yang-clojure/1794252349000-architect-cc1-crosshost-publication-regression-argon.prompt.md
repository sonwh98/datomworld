Created-GMT: 2026-11-10 (real header below)
Coding-Agent: agy
Session-ID: pending (provider-generated)

# Task: ARCHITECT RULING — cc1 made the hashed module manifest host-dependent (measured regression)

Role: Lead System Architect — READ-ONLY architecture review
(Model note: argon is the owner-reserved architecture-review lane. codex/astra began
this ruling and hit a usage limit after establishing the key fact below; this
round completes it.)

## Measured facts (bisect, node lane, same host, freshly generated both sides)

  f4df9893 (master before cc1): yang.python.antlr.dht-import-test exit=0, 158
      assertions, 0 failures.
  b1b9abae (master with cc1):   exit=1, 155 assertions, 84 failure lines.
      (three assertions vanish: some rows never reach comparison)

Failing invariant (verbatim): "this host's own publication of the committed
packets is the JVM's artifact: same manifests, blobs, envelopes", with
:blobs-differ listing divergent :segment/blake3-... addresses. Sibling failure:
dao.stream corpus-jvm-serves-node-decodes-over-cbor.

Prior ruling round's established fact (codex, before its quota cut): "cc1 adds
call-site tables to THE HASHED MANIFEST and transfers them with the module
closure."

## The question

cc1 = commits fa70516f, f440a2fb, ba2deb73 on origin/callsite-contract-clojure
(merged at b1b9abae). Its occurrence-identity work attaches per-occurrence
origins and a call-site table to the module record.

RULE: are the call-site tables host-independent? Read how they are constructed
and ordered (yin/vm/macro.cljc lineage/origin code, yin/vm/linker/publish.cljc,
the module record construction, the CBOR canonical encoder). Identify the exact
source of per-host variation if present: hash-map/set iteration order, lazy seq
realization, object identity, host-specific types, unsorted collections,
timestamps/uuids, or environment.

Then rule ONE of:
  (a) canonicalize: the table must be produced in a canonical (sorted, stable)
      order so every host hashes identical bytes — say exactly where;
  (b) exclude: the table (or specific keys) must not participate in the hashed
      manifest — say exactly where and what the design loses by excluding it;
  (c) both, for different parts.
Give reasons from the design (yin.repl.dao.space-index.md, dao.jing.md,
yin.vm.linker*), and state the acceptance test: which test must go green
(yang.python.antlr.dht-import-test, and the dao.stream corpus test if the same
cause), and what must NOT change.

Constraints: read-only; do not edit files; do not run suites (a single scoped
test run is permitted only if reading cannot settle it — say so if you need it).
Begin the final response exactly with Completed-GMT / Completed-Local.
