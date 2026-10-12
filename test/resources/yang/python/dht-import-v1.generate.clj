;; Generator of test/resources/yang/python/dht-import-v1.edn.
;;
;; Provenance: the JVM publication of C4 slice I3's DHT import
;; (yang.python.antlr.dht-import-fixture). Run from the repository root
;; on the JVM, with the test paths on the classpath, to rewrite the
;; artifact:
;;
;;   clojure -A:test -M test/resources/yang/python/dht-import-v1.generate.clj
;;
;; It publishes the committed CST packets of the guest units through
;; `yang.python.antlr.publication` against `py` as the JVM publishes it,
;; signs the name envelopes with the fixture's fixed test seeds, and
;; writes the guest blobs, the manifest addresses, the envelopes and the
;; importer packets. Every input is checked in, and Ed25519 signatures are
;; deterministic, so a rerun on an unchanged tree rewrites the same data.
;; Rerun it whenever the prelude, the lowering or the linker's
;; publication moves an address; `dht-import-test` fails until it is.

(require '[yang.python.antlr.dht-import-fixture :as fixture])

(fixture/write!)
(println "wrote" fixture/path)
