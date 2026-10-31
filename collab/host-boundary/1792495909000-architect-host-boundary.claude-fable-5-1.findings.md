Created-GMT: 2026-10-20 12:40:00 GMT
Created-Local: 2026-10-20 19:40:00 +07
Coding-Agent: claude
Model: claude-fable-5-1
Session-ID: 36134bec-9cbd-4852-b78c-4901af66fd02
Task: design dao.stream as the only host-dependency boundary
Role: Architect
Deliverable: docs/design/dao.stream.host-boundary.md (new; standalone; "design only; nothing commissioned")
Labels: V verified by reading, R read-not-run, P proposed. Nothing executed. No src edits, nothing staged.

# Findings

**1. The owner's sentence holds under one reading (V, doctrine).** `datom.world.abi.md`
§5 puts the floor at "world facts that shipped code cannot compute"; §4 keeps
machine-internal work inside the instance. So two kinds of host contact exist and only one
is a stream: world facts and effects (sockets, files, locks, clock, entropy, a parser
running elsewhere) cross as `dao.stream` implementations with a host seam value; carriers
and pure functions over them (integers, floats, bytes, text, hashing, signing, canonical
encoding) form one enumerable **primitive profile** with a byte-level oracle per entry.
Hot-path line (doc §2): per step, value, byte or address is a primitive; per I/O event is
a stream operation. A stream round trip per `integer/add` would be a 10^3-10^5 slowdown
and is excluded; so is a hand-rolled BLAKE3 (owner guidance: libraries for crypto).

**2. The census confirms the evidence and adds one defect (V).** `rg -c '#\?'` over 183
portable files: the heavy rows are the spike's failures (`dao.jing.cbor` 52,
`dao.data.btree` 35, `yin.vm.integer.host` 30, `dao.jing.file` 19, `store.fs` 17, `sign`
16, `dao.jing` 12); VM, linker and REPL counts are mostly `catch` arms. The defect: two
big-integer shims, `yin.vm.integer.host` and a private copy at `cbor.cljc:106-254`, and the
former's docstring says it avoids the latter on purpose. Consolidated in H1/H2.

**3. Several categories are already stream-shaped and need naming, not redesign (V).**
Sockets have portable cores plus seam maps. The durable stack already has the stream on
top: `dao.stream.journal` runs over `dao.jing.file` under the `store.fs` lock. The Python
parser is a stage between two streams; no ANTLR object leaves the host walk. So storage
gets a declared seam **beneath** the journal (doc §4.2), not a second stream shape, and the
parser stage becomes relocatable over any stream implementation with one test.

**4. The one real (a) candidate is the Jing CBOR writer (V+R).** Decode is already Jing's
own portable reader; only the writer binds Boring (JVM, JS) and the Dart `cbor` package. A
portable writer over the bytes seam removes two libraries from the content path; the frozen
`cbor-v1.json` corpus is a byte oracle. Boring is itself Clojure (R), so no regression
argument; measured in H4. Owner decision OQ4.

**5. Contract and conformance (P).** A host implements the primitive profile (`dao.host.*`,
about 55 functions, oracle named per entry), the seam values its composition selects, and a
`main` supplying clock, entropy and schedule. Two suites `yin.vm.engine.md` §E.5
presupposes are added: `dao.host.conformance-test` (vectors) and
`dao.stream.conformance-test` (outcome tables through a scripted seam). E.5.3's W0 adapter
probes are the second suite instantiated for wasm; the wasm surface is unchanged.

**6. Migration (P).** H0 freeze (grep allowlist plus negative test); H1-H5 code motion
behind byte-identical corpora; slow tier once; H6 storage seam (largest; medium risk: Node
claim-file liveness and HEAD rename reproduced exactly); H7 parser stage; H8 suites.
File-disjoint from the codec track's C2-C4; one `require` in `dao.stream.codec` deferred
past C4. Cost: ~10^1 files per phase, ~10^3 tests rerun per lane, ~10^1 rewritten.

**7. In-flight host-lane slices (R, jolt plan and reports).** J0a is boundary-agnostic and
its inventory **is** H0's input. J0b sits under the datagram seam: unaffected. J0c is the
parser stage's host adapter: unaffected. J1 is the one tension: it adds `:jolt` arms to
`integer/host.cljc` and `cbor.cljc` (jolt doc §5, §5.1), the files H1-H3 consolidate.
Recommendation (OQ3): pause J1's arm-adding until phase A lands, or accept a one-`require`
rebase. J6's dependency on the codec track is unchanged.

**8. Not verified.** No lane was run; quoted test counts come from the orchestrator log
(R). Boring versus a portable writer is plausible, not measured. The storage namespaces
are the least tested across hosts (the spike never loaded them).

**9. Owner decisions (doc §10):** OQ1 scope (recommend the `yin.repl` closure), OQ2 the
`dao.host.*` name, OQ3 pause J1-style arm work, OQ4 drop Boring from the Jing path, OQ5
storage seam versus streams all the way down, OQ6 a separate orchestrator seat.

**10. Sign-off.** Claude-authored; a non-Claude architect must sign the document off before
anything is commissioned from it (stated in the document header and §12).
