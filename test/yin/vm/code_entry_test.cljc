(ns yin.vm.code-entry-test
  (:require [clojure.test :refer [deftest is]]
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
