Completed-GMT / Completed-Local: 2026-11-09 07:01 GMT / 2026-11-09 14:01 +0700

**Verdict: REQUEST CHANGES — fa70516f is not merge-ready. Two P1 implementation defects remain.** This accepts signed U1–U3; it does not reopen the resolved content-address or default-admission objections.

All code references below are to fa70516f. The linked cc1 files match that revision for these paths. Review was read-only; no tests were run. Reproductions below are statically traced, not executed.

1. **P1 — Rebuilding a packet container loses retained origins and fabricates construction provenance.**

   Reproducible source forms, compiled and expanded through the existing `expand-like-the-repl` helper:

   ```clojure
   (defmacro repack [x]
     (conj [] (first x) (first (rest x))))
   (repack (+ 1 2))
   ```

   `expand-call` attaches `::origin-of` to the **outer operand packet’s metadata** at [macro.cljc:1208](/Users/sto/workspace/datomworld-cc1/src/cljc/yin/vm/macro.cljc:1208). The prelude exposes ordinary `first`, `rest`, and `conj` through [macro.cljc:739](/Users/sto/workspace/datomworld-cc1/src/cljc/yin/vm/macro.cljc:739) and [vm.cljc:398](/Users/sto/workspace/datomworld-cc1/src/cljc/yin/vm.cljc:398).

   The macro returns a fresh `[root rows]` container holding the original root and rows. It constructs no application syntax, but the outer metadata disappears. Validation accepts those unchanged rows. Then [macro.cljc:1232](/Users/sto/workspace/datomworld-cc1/src/cljc/yin/vm/macro.cljc:1232) applies:

   ```clojure
   (or (origin-of-fn tree)
       (fn [_] pending-expansion-origin))
   ```

   Consequently, the output correspondence records `{[] :constructed}` instead of `{[] [:retained incoming-origin]}`. The walk translates that into `[:expansion record-address]` at [macro.cljc:1259](/Users/sto/workspace/datomworld-cc1/src/cljc/yin/vm/macro.cljc:1259), and the final table consumes it at [macro.cljc:1387](/Users/sto/workspace/datomworld-cc1/src/cljc/yin/vm/macro.cljc:1387).

   **U1 violation:** the record asserts construction history that did not produce the retained application. Reading that incorrect record faithfully does not repair provenance. The same loss can occur beneath a constructor: [macro.cljc:638](/Users/sto/workspace/datomworld-cc1/src/cljc/yin/vm/macro.cljc:638) also treats missing child provenance as constructed.

   **Minimum correction:** preserve occurrence provenance through permitted packet decomposition/reassembly, including child insertion. Remove the assumption that missing provenance proves construction. Explicit refusal is an interim containment measure; restoring supported packet operations requires provenance propagation through those operations. Address-equality inference is not a valid replacement.

2. **P1 — Internal provenance metadata makes an otherwise valid packet literal unencodable.**

   Reproducible forms:

   ```clojure
   (defmacro packet-data [x] (yin/literal x))
   (packet-data (+ 1 2))
   ```

   [macro.cljc:618](/Users/sto/workspace/datomworld-cc1/src/cljc/yin/vm/macro.cljc:618) attaches a host function as `::origin-of`. `yin/literal` passes the operand directly into `[:literal v]` at [macro.cljc:667](/Users/sto/workspace/datomworld-cc1/src/cljc/yin/vm/macro.cljc:667). `construct` interns that body at [macro.cljc:655](/Users/sto/workspace/datomworld-cc1/src/cljc/yin/vm/macro.cljc:655), before output validation.

   Jing encodes vector metadata: [cbor.cljc:904](/Users/sto/workspace/datomworld-cc1/src/cljc/dao/jing/cbor.cljc:904) calls `meta-bytes`; [cbor.cljc:834](/Users/sto/workspace/datomworld-cc1/src/cljc/dao/jing/cbor.cljc:834) removes only reader-position keys, leaving `::origin-of`. Encoding its function value reaches the unsupported-value refusal. The bounded runner converts the exception into `:body-error` at [macro.cljc:829](/Users/sto/workspace/datomworld-cc1/src/cljc/yin/vm/macro.cljc:829).

   Thus internal tracking changes valid macro behavior. The claim that this metadata is “content-addressing invisible” is false when a packet becomes literal data.

   **Minimum correction:** remove only private tracking metadata, recursively, when values enter literal/data slots, before hashing and validation; preserve legitimate user metadata. Keeping tracking entirely outside macro-visible values would also resolve this boundary problem. Do not change Jing’s metadata semantics to accommodate it.

**These are current round-5 defects, not stale pre-fix findings.** The relevant implementation is unchanged between round-5 commit `847aa885` and fa70516f. Round 5 correctly handles direct pass-through and constructor-mediated composition, including the tested identical-content twins; it does not cover the two paths above. The reported Node and JVM results—27 tests/242 assertions each—do not contradict these uncovered cases.

**Corpus ruling:** the signature difference is compatible with added provenance work, but does not establish its cause. Response `14` is zero-based and names `<?php echo 1 + 2;`: see [runner:94](/Users/sto/workspace/datomworld/src/dev/jolt_shell_session.clj:94) and [fixture:8](/Users/sto/workspace/datomworld/test/resources/yin/repl/transcripts/local.edn:8). The runner gives each response 180 seconds.

The recorded branch timeout also occurred **in isolation**, so worker starvation is not an adequate explanation. Cc1 adds occurrence enumeration, correspondence sorting, and origin bookkeeping; these add compilation cost without inherently changing executable tree structure. The PHP prelude-depth defect explains master’s overflow, and `f4df9893` fixes that structure. **That fix is not contained in fa70516f.**

Accordingly, I find no demonstrated third cc1 defect from the timeout alone, but “master also fails” does not fully exonerate the branch’s timing behavior. A comparison on the same PHP-fixed base would resolve that residual attribution question.

**Final disposition: do not merge fa70516f. The two P1 metadata defects block approval independently of the corpus question. This closes the cc1 re-check with a request-changes verdict.**