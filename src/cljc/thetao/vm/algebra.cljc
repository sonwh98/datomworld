(ns thetao.vm.algebra
  (:require
    [thetao.vm.content-store :as store]))


(def continuation-dimension
  {:dim/name :thetao.vm/d_cont_alg
   :dim/arity 6
   :dim/slots [[:vm/form :value :control]
               [:vm/env :hash :environment]
               [:vm/frame :hash :frame]
               [:vm/parent :hash :parent]
               [:cont/blocked-on :value :status]
               [:vm/value :value :return]]
   :dim/encoding :canonical-edn
   :dim/projection-to :d3
   :dim/lift-from :d3})


(defn compile-program
  [ast]
  {:kind :algebra
   :root ast
   :dimension continuation-dimension
   :hash (store/content-hash ast)})
