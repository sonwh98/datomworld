;; constructed-collections-v1 re-pin ledger (ruling 1795017757000, RULING 3)
;; ============================================================================
;; One-time canonical shift of the cc4 replay cohort under the c1-2 frontend
;; revision. Recorded on the JVM (OpenJDK 21.0.2+13-58, Clojure CLI 1.12.4.1602)
;; in worktree datomworld-fixorder at dispatch base 706d44f9 with this round's
;; emitter change applied and uncommitted.
;;
;; input pins
;;   cohort source/options/coordinates : yang.clojure-callsite-test/replay-artifacts
;;   compiler source (pre-fix)         : src/cljc/yang/clojure.cljc
;;                                       sha256 fc15a56c10ed2d59a842e0f677b3bcaa23ae1331112fdd3daff13895b3d0a12b
;;   frontend manifest (pre-fix)       : revision c1-1, lowering :segment/legacy
;;   frontend manifest (post-fix)      : revision c1-2,
;;                                       lowering :yang.clojure/constructed-collections-v1
;;
;; pre-fix bundle  : test/resources/yang/clojure/cc4-replay-snapshots-prefix-c1-1.edn
;; post-fix bundle : test/resources/yang/clojure/cc4-replay-snapshots.edn
;; r2 defect evidence (immutable): cc4-replay-dart-divergences.edn,
;;                   cc4-replay-jvm.cbor, cc4-replay-jvm-affected.edn,
;;                   collab/yang-clojure/1795017275897-cc4-r2-evidence/
;;
;; member digests (root / keys / c1 / instruction-provenance), pre -> post
;;
;; :native         unchanged (no constructed collection in the tree)
;; :prelude        unchanged
;; :macro          unchanged
;; :negative       unchanged (same content as :native, second token)
;; :yang.call      unchanged (publication; no constructed collection)
;; :clj            unchanged
;;
;; :argument-vector (263 applications, both revisions)
;;   root   a0f9c217122ce675d1b3cd5f899740d604ec35f801eac9a4960153cbcd91d699
;;       -> 4a9a1b91e8a113009af0dc68f8e5a5afbe1910a61e07741894f5922c20499b26
;;   keys   c566619c48987d2d8968f709b2a9fe878fa13d090d03e09a23ed476af4f3071f
;;       -> aeb62bc089bd7b30f200d9b0645e7933e54286af39b9675c73823c4be8d0175d
;;   c1     82385d11b83bd1a6bd540cc80011ec88f23cf2e9a959fecfd604eff029a1ba2a
;;       -> 41b8f25ba2592d4f5d77364f97e7c8d058cbe4e2a428587cc2b43a5e4193387d
;;   prov   46aaeddb12f05415c9291f48431f1f1d300685aefdb31ee7d1517bcb6e90cb22
;;       -> d0f4d14d0cef560ad313574f206a391b1eed7938b38b81f614549bc26cb8b3cb
;;
;; :lower-unit (256 applications, both revisions)
;;   root   44c06492b31348905205b1e1130c27aa1c769790010291ca223d3ab61bfa2775
;;       -> f4603726a6b554bf102dd63f752851e735c068d3804158b2435004161c6655b4
;;   keys   3d26c4d7ace79574c00ec69f676027cc95025ba8e108fc3958606471308c6618
;;       -> 18e26285f07ae96609caeea87f96e4b9fe8cea6b5223c87417d63edeac054670
;;   c1     654c6a31726250f58550db88ce905fda869f3040b350e298da0aa73995b581ed
;;       -> 1ab2dbf99e3fa7bbe4d53281c7d961f03b837cef6a6df5eb10f82d1ceff5849d
;;   prov   3c755a27a4432a44786960a0bbf8771c07068707a23d260c2733c76078870100
;;       -> 711d0c50ea119b0f6577868383a5e82462b17a77da52b0b09e049fddc68d4aa5
;;
;; The reproduced arity-map constructor's operand vector (the defect site),
;; pre-fix JVM host order -> post-fix canonical order (key roots
;; 1e03667e/81b62c1e/8930bf5e are the same child rows in both revisions):
;;
;;   pre (JVM/Node): {} :yin.vm.data/reason :arity :yin.k/argc (data/count dsg__av)
;;                        :yin.k/fn (quote replay)
;;   pre (Dart)    : {} :yin.vm.data/reason :arity :yin.k/fn (quote replay)
;;                        :yin.k/argc (data/count dsg__av)
;;   post (all)    : {} :yin.k/fn (quote replay)
;;                        :yin.vm.data/reason :arity
;;                        :yin.k/argc (data/count dsg__av)
;;
;; retained legacy artifact (family 7): the c1-1 compiled row set of
;; ((fn [x] {(- x 1) :a (- 1 x) :b}) 1) with its old root
;; 49d9ef3191b305b26e67a803c9e74104dc57d1c5f03b147b3fd2352d256f5e64 and old
;; executed value {0 :b};
;; test/resources/yang/clojure/constructed-collections-legacy-c1-1.cbor
;; (base64 copy embedded in yang.clojure-collection-order-test).
