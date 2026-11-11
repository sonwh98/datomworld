Created-GMT: 2026-11-10 (real header below)
Coding-Agent: muse
Session-ID: d69b15f6-fc04-4fdc-8f3b-f632755642b8

# Task: exclude :yin.module/call-sites from the hashed manifest + canonicalize the side table (architect ruling)

Role: Compiler/VM Engineer (implement an architect ruling precisely)

Implementers:
- Model: muse-wonder-1.0 | Assigned: 2026-11-10 | Status: active

## Governing ruling (READ FULLY — it is exact)

collab/yang-clojure/1794252349000-architect-cc1-crosshost-publication-regression-argon.gemini-4-argon-high.stdout.log
(the argon architect ruling: (c) both parts — (b) exclude from the hashed manifest,
primary; (a) canonicalize the side-table row ordering, secondary)

## Root cause (from the ruling, confirmed by bisect)

cc1 injected :yin.module/call-sites into the schema-1 hashed module manifest
(yin/vm/linker/publish.cljc publish-closure! ~:351-363). Because py requires
yang.call, the change propagated to every guest module, so a LIVE publication
no longer matched the committed pre-cc1 golden corpus
(test/resources/yang/python/dht-import-v1.edn), failing
yang.python.antlr.dht-import-test (84 failure lines) and manifest-address-test.

## Part B (primary) — exclude from the hashed manifest

1. publish-closure! (publish.cljc ~:327-370): do NOT assoc :yin.module/call-sites
   into the manifest.
2. linker.cljc (manifest-keys, manifest-defect ~:739-845): revert the
   :yin.module/call-sites entry — the schema-1 key set is CLOSED.
3. linker/closure.cljc (walk-call-sites, walk-manifest ~:130-157): revert
   walk-call-sites from walk-manifest.
4. publish.cljc (call-site-record, publish-call-sites!, published-call-site-table
   ~:218-263): INVERT THE POINTER — materialize the manifest FIRST to get
   manifest-address, then build the record naming the manifest:
     {:yang.call-site/schema 1 :yang.call-site/manifest manifest-address
      :yang.call-site/root (:root tree) :yang.call-site/origin origin
      :yang.call-site/table table-address}
   and return :call-sites {:record .. :table .. :origin .. :root .. :manifest ..}
   from publish-module!. published-call-site-table must accept the :record
   address (or the publish-module! result) and validate
   (:yang.call-site/manifest rec) matches the manifest it is asked about.

## Part A (secondary) — canonicalize the row ordering

Replace (sort-by pr-str ...) with a deterministic structural comparator over
[origin root-address path :yang/call-site version] (or sort-by
jing/canonical-bytes with byte-wise compare) at:
  publish.cljc:213 call-site-claim; yang/clojure.cljc:112 call-site-metadata;
  macro.cljc:1387 dedupe-call-site-rows and :1622 final-call-site-metadata.

## MUST NOT change

- test/resources/yang/python/dht-import-v1.edn and the manifest-golden
  (linked_prelude_test.cljc:229-238) — do NOT regenerate or rewrite them.
- yang.core.call/module-uast KEEPS (u/annotate-call-sites ...).
- Do not touch test/yang/call/conformance.clj (another seat owns it).

## Verify (scoped — the failing tests only; report commands, orchestrator runs them remotely)

- node: yang.python.antlr.dht-import-test -> 0 failures (was 84)
- JVM: yang.python.antlr.linked-prelude-test manifest-address-test (the golden)
- cc1 battery: yang.clojure-callsite-test (update ONLY published-module-test per
  the ruling: same address via different scopes; distinct call-sites)
Work in a scratch worktree from origin/master; uncommitted; report git diff --stat.
Begin with Completed-GMT / Completed-Local.
