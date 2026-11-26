(ns yang.floatkind-legacy-capture-test
  "ONE-SHOT pre-fix evidence capture (float-kind portability r1, RULING 5
   gate 6): mints the legacy custody fixtures from the CURRENT compiler - the
   JVM float-bearing artifact and the Node integer-bearing artifact of the
   SAME source - and writes them under test/resources/yang/clojure/.

   Run once on each host against the UNREPAIRED tree, then delete this
   namespace. The fixtures it leaves behind are the genuine pre-fix bytes
   the custody test loads and executes without recompilation afterwards;
   an artifact synthesized by the repaired compiler would not be legacy
   evidence."
  (:require [clojure.test :refer [deftest is]]
            [dao.jing :as jing]
            [dao.jing.cbor :as cbor]
            [yang.clojure :as yang]
            [yin.vm :as vm] #?(:clj [clojure.java.io :as io])))


(def ^:private legacy-source
  "The custody witness program: a float literal under a closure call."
  "((fn [] 1.0))")


(defn- host-of
  []
  #?(:clj :jvm-float :cljs :node-integer :default :other))


(defn- write-bytes!
  [path payload]
  (let [bytes (cbor/encode payload)]
    #?(:clj (do (io/make-parents path)
                (with-open [w (io/output-stream path)]
                  (.write w ^bytes bytes)))
       :cljs (.writeFileSync (js/require "fs") path bytes))
    bytes))


(deftest capture-legacy-float-kind-artifact
  (let [ast (yang/compile-program (yang/read-forms legacy-source))
        tree (vm/ast->semantic-bytecode ast)
        host (host-of)
        payload {:yang.clojure.floatkind/legacy true
                 :yang.clojure.floatkind/host host
                 :yang.clojure.floatkind/source legacy-source
                 :yang.clojure.floatkind/pre-fix-ref "7f4898f4 c1-2"
                 :manifest-revision "c1-2"
                 :root (:root tree)
                 :rows (:rows tree)}
        path (str "test/resources/yang/clojure/float-kind-legacy-"
                  (name host) ".cbor")
        bytes (write-bytes! path payload)]
    (println :floatkind-legacy-capture host :bytes (count bytes))
    (is (pos? (count bytes)))))
