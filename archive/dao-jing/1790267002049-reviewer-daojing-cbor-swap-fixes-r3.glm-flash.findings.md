# Round-3 consensus verification — DaoJing CBOR swap (r2 delta)

Reviewer: ZCode subagent, GLM-5.3-Flash (independent of claude-sonnet-5 fix author).
Artifacts: collab/1790267002049-reviewer-daojing-cbor-swap-fixes-r3.glm-flash.{prompt.md,findings.md}
Completed: 2026-09-25 ~00:15 +07. All work read-only; every claim verified against the tree.

## Rulings on the six r2 findings

1. **VM carrier awareness — CLOSED.** `numeric-class` tests the carrier first on every host
   (`src/cljc/yin/vm/debruijn.cljc:227` `(cbor/float64? v) :double`, host dispatch at 230-235),
   and `canonical-class` routes carriers at `debruijn.cljc:299`. Host parity holds: `float64?`
   (`src/cljc/dao/jing/cbor.cljc:460-468`) matches each host's decode output (JVM `Double` at
   cbor.cljc:525, CLJS `Float64` deftype decoded at cbor.cljc:1436-1440, Dart double);
   decimal/rational carriers classify nil intentionally (outside the VM's §5 numeric domain);
   forged native floats and tag 39 are refused at decode (cbor.cljc:1466, 1515), and a
   non-carrier deftype/record falls through `canonical-class` to nil, so nothing smuggles
   through as a plain number. `slot-kind-ok?`/`slot-shape-ok?` (`debruijn.cljc:537,1190`)
   correctly reject carriers from `:int64` slots via the `number?` guard. pipeline_test now
   compares portable content via `cbor/content=` at the three sites
   (`test/yin/vm/pipeline_test.cljc:512-518, 559-562, 591-601`); these fail on pre-fix code.

2. **Copy-before-validate — CLOSED.** `src/cljc/dao/jing/mem.cljc:53-54` copies first,
   validates the snapshot, and the CAS duplicate comparison uses only the snapshot
   (mem.cljc:62); `src/cljc/dao/jing/file.cljc:348` copies first, validates (349-353), frames
   (354) and compares (362) only the snapshot. No read of caller-owned `bs` remains after the
   copy in either backend; TOCTOU is closed.

3. **Trusted reads must not re-encode — PARTIALLY CLOSED.** The split is correct:
   `decode-snapshot` (`cbor.cljc:1524-1538`) drops only the re-encode check; `decode`
   (1541-1550) retains full strictness; `segment-value` hash-verifies then snapshot-decodes
   (`src/cljc/dao/jing.cljc:463-471`), and all its consumers are trusted reads
   (`jing.cljc:563`, `mem.cljc:147`, `file.cljc:449`, `dao/space/index.cljc:629,1181`). Strict
   decode is retained at `remote.cljc:50`, `dht.cljc:174`, `dht/node.cljc:120`,
   `file.cljc:124,145`, `debruijn_linker.cljc:199` — but the fix report's "all ingress sites
   still call strict decode" is false: the remote client receipt silently regressed (P1 below).

4. **Hash-registry doc — CLOSED.** `docs/design/dao.jing.hash-registry.md:129-131` states
   `canonical-bytes` delegates to `dao.jing.cbor/encode` and the print encoder "is gone (clean
   break, 2026-09-24)"; :509-512 records the clean break as complete. `dao.jing.md:512-513`
   marks the transitional printer retired.

5. **File-handle isolation tests — CLOSED.** `test/dao/jing/file_test.cljc:228` mutates the
   input array after put and checks the in-memory snapshot, `get-bytes`, a reopen, and
   `records`; `:254` mutates the output array after get and checks later reads plus a reopen.
   They exercise exactly the claimed paths.

6. **Added-line hygiene — CLOSED for the five flagged lines**, with one new violation found
   (P3 below). Every remaining violation in the touched files matches HEAD text exactly,
   verified line-by-line.

## Actionable findings

P1 | src/cljc/dao/jing/remote/step.cljc:304 | The remote get-content client receipt decodes
untrusted reply bytes with `jing/segment-value` (`(jing/segment-value address bs)`), which
since the r2 delta is hash-verify + `cbor/decode-snapshot` (`src/cljc/dao/jing.cljc:471`) —
no canonicality check. The site's own comment (step.cljc:295-297: "decode as one canonical
payload before a value is published") and the contract (`docs/design/dao.jing.cbor.md:125-128`
— canonicality verification at each ingress of bytes Jing did not encode itself,
"remote/DHT receipt ... before storage, caching, or exposure"; :458-459; :638-640 "Hash-valid
noncanonical payloads fail at ingress") require strict decode here, as the storage-side
receipts still do (`remote.cljc:50`, `dht.cljc:174`, `dht/node.cljc:120`). A malicious
address+server pair can serve a hash-valid, profile-valid but noncanonical payload whose
canonical bytes hash to a different address; it is published as `:found? true :value decoded`
(step.cljc:316) and silently breaks content-address convergence when the consumer
re-materializes. No test covers this refusal (step_test only pins the hash-mismatch arm at
test/dao/jing/remote/step_test.cljc:267-285). The fix report's claim "All ingress sites
(remote, ...) still call strict decode" is false for this site. | After `accepted-bytes` hash
verification, decode strictly at step.cljc:304 — replace `jing/segment-value` with
`cbor/decode` (mirroring `dao.jing.remote/accept-bytes!` at remote.cljc:35-51) or add a
strict `jing` wrapper, mapping refusal onto the existing `::refused`/integrity-failure arm;
add a step_test case feeding a hash-valid noncanonical payload and asserting the
integrity-failure completion.

P3 | src/cljc/dao/jing/file.cljc:365 | The r2 copy-first restructure re-indented make-put's
collision arm; the message line is 81 columns (verified `length=81`). | Split the literal like
the mem backend does (mem.cljc:66-67), reflowed under the throw.

P3 | (process) Dart host | The delta's ClojureDart branches (`debruijn.cljc:235` :cljd arm of
the changed classifier; carrier code in cbor.cljc) have no green-run evidence: the r2 fix
report discloses the Dart suite was still building, and the round-3 orchestrator evidence
covers JVM and Node only, while acceptance requires all three hosts
(docs/design/dao.jing.cbor.md:568-569, 656). | Run the Dart suite on the final tree before
merge; static inspection of the :cljd branches shows parity, but it is the only unexercised
host for the changed code.

Rulings summary: r2 findings 1, 2, 4, 5, 6 CLOSED; finding 3 PARTIALLY CLOSED (trusted-read
split correct, one ingress site regressed). The P1 is a regression the fix delta introduced in
exactly the attack surface the brief flagged, so the delta cannot ship as-is.

Verdict: REQUEST CHANGES
