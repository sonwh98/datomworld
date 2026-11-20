(ns yin.vm.code-entry-test
  (:require [clojure.test :refer [deftest is testing]]
            [dao.jing :as jing]
            [yin.vm.effect :as effect]
            [yin.vm.module :as module]))


(deftest installed-export-is-address-pinned
  (let [manifest {:yin.module/name 'eval.unit :yin.module/exports #{'entry}}
        address (jing/segment-key manifest)
        registry (module/assoc-module (module/default-registry) 'eval.unit
                                      {:manifest manifest :address address
                                       :bindings {'entry :callable}})
        state {:modules registry}
        effect {:module 'eval.unit :address address :export 'entry}
        run #(module/code-entry-handler state % {})]
    (is (= :callable (:value (run effect))))
    (is (= state (:state (run effect))))
    (is (false? (:blocked? (run effect))))
    (doseq [[bad reason] [[(assoc effect :address :wrong) :manifest-mismatch]
                          [(assoc effect :export 'missing) :export-undeclared]
                          [(assoc effect :module 'absent) :module-missing]]]
      (is (= reason (get-in (run bad) [:value :reason]))))
    (doseq [bad [(assoc effect :address :wrong)
                 (assoc effect :export 'missing)
                 (assoc effect :module 'absent)]]
      (is (= :refused (get-in (run bad) [:value :status]))))
    (is (= module/code-entry-handler
           (module/get-effect-handler registry :code/entry)))
    (is (= {:effect :code/entry :module 'eval.unit :address address :export 'entry}
           (effect/descriptor (module/code-entry 'eval.unit address 'entry))))
    (is (fn? (module/resolve-module (module/register-code-module registry) 'code.entry)))
    (doseq [modified [(assoc-in registry [:modules 'eval 'unit :manifest :extra] true)
                      (update-in registry [:modules 'eval 'unit] dissoc :bindings)]]
      (is (= :refused (get-in (module/code-entry-handler (assoc state :modules modified) effect {})
                              [:value :status]))))))


(deftest installed-export-is-verified-with-the-address-algorithm
  (let [manifest {:yin.module/name 'eval.unit :yin.module/exports #{'entry}}
        address (jing/segment-key manifest {:algorithm :sha256})
        malformed :segment/sha256-xyz
        unknown (keyword "segment" (str "sha512-" (jing/segment-digest address)))
        lookup (fn [stored-manifest stored-address requested-address]
                 (let [registry (module/assoc-module (module/default-registry) 'eval.unit
                                                     {:manifest stored-manifest
                                                      :address stored-address
                                                      :bindings {'entry :callable}})]
                   (:value (module/code-entry-handler
                             {:modules registry}
                             {:module 'eval.unit :address requested-address :export 'entry}
                             {}))))]
    (testing "an installed SHA-256 manifest serves its export"
      (is (= :callable (lookup manifest address address))))
    (doseq [[label stored-manifest stored-address requested-address]
            [["tampered manifest" (assoc manifest :extra true) address address]
             ["nil requested address" manifest address nil]
             ["nil stored and requested addresses" manifest nil nil]
             ["malformed address" manifest malformed malformed]
             ["unknown algorithm" manifest unknown unknown]]]
      (testing label
        (is (= {:status :refused :reason :manifest-mismatch
                :module 'eval.unit :export 'entry}
               (lookup stored-manifest stored-address requested-address)))))))
