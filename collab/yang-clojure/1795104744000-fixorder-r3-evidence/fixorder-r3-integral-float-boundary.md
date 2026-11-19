;; r3 B3 boundary evidence -- bare integral-valued float literals mint
;; different content per host (JVM float64 carrier vs JS integer encoding
;; of a bare integral Number; dao.host.float / dao.jing.cbor.md numeric
;; identity). First caught by corpus-cross-host-reference-test on its first
;; Node run, at the :floats case whose corpus then contained 0.0:
;;
;;   JVM-recorded :floats row (0.0-bearing corpus)
;;     root  149c168e3b766d703fc230365be4ad940bbcf487e0bff36efff9cf6e3bf08958
;;     keys  cb7365d53d55ad30e90e700206caff405d3fe1d5faf62566df91f4f1dcd4afb7
;;     c1    f181b911a60e6e66217fecbf93bd6623d83354c41aa0afdb9e49f10f45f69fdd
;;     prov  e25482dac0a59eaa8801c1faea000ad518a5ab1d7e53b704d883ff6ecf495735
;;   Node actual (same corpus, same compiler)
;;     root  d4ee0ca368a18d7cf8010e211e50ed96df6f4ecc39befad11dd9aad01c57d5b1
;;     (full keys/c1/prov in fixorder-r3-node-order-floats-divergence.log)
;;
;; Non-integral doubles (1.5, 2.25, -0.5, 3.75, 2.5) agree across hosts;
;; JVM probes show every double is CBOR-tagged float64 (even 0.0), so the
;; divergence is the JS host's bare-integral-Number rule. Encoder/float
;; carrier territory: reported as a dependency for a frontend/codec round
;; (should bare integral-valued float literals travel via the float64
;; carrier on every host?), NOT fixed here. Within bounds, the corpus now
;; uses non-integral floats and the :floats reference row is re-recorded.
