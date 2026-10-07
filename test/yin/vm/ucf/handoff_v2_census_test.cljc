(ns yin.vm.ucf.handoff-v2-census-test
  "UCF version 2, section 11 rows 3, 4 and 10: the census.  Aliased and
   independent cells keep their identities across ordered waits; a halted
   result carries a linked module's closure with its store and image and
   no initialization reruns on lower; numeric carrier classes (float64
   integral content, negative zero) survive in code and value positions
   without recomputing a code hash after coercion."
  (:require [clojure.test :refer [deftest is testing]]
            [dao.jing.cbor :as jing.cbor]
            [dao.stream :as stream]
            [yin.vm :as vm]
            [yin.vm.ucf.v2-support :as s]
            [yin.vm.values :as values]))


(defn- ordered-waits
  "`parked`, a reader, with four ordered waits: the original, a second
   waiter on the same cell, and two independent cells at one position."
  [parked]
  (let [base (first (:wait-set parked))
        cid (get-in base [:cursor-ref :id])
        sid (:stream-id base)
        kept (get-in parked [:resources cid :cursor])
        indep (fn [id] (assoc base :cursor-ref {:type :cursor-ref :id id}))]
    (-> parked
        (update :resources assoc
                :yin/i1 {:stream-id sid :cursor kept}
                :yin/i2 {:stream-id sid :cursor kept})
        (assoc :wait-set [base base (indep :yin/i1) (indep :yin/i2)]))))


(deftest aliased-and-independent-cells-survive-ordered-waits
  (doseq [engine s/engines]
    (testing (name engine)
      (let [[parked _] (s/parked-reader engine)
            four (ordered-waits parked)
            t (s/toy)
            peer (s/served-peer t)
            export (s/lift four t peer)
            body (:body export)
            cells-of (fn [m]
                       (mapv #(get-in % [:cursor-ref :id]) (:wait-set m)))
            [r _] (s/read! engine t (:bytes export)
                           {:address (:address export)})
            ids (cells-of (:vm r))
            pendings (mapv :yin.k/pending (:yin.k/frames body))]
        (is (= :ok (:status export)) (pr-str export))
        (is (= 4 (count (:yin.k/frames body))) "the wait order is kept")
        (is (= 3 (count (:yin.k/cells body)))
            "one cell for the aliases, one each for the independents")
        (is (= (:yin.k/cell (first pendings)) (:yin.k/cell (second pendings))))
        (is (not= (:yin.k/cell (second pendings))
                  (:yin.k/cell (nth pendings 2))))
        (is (not= (:yin.k/cell (nth pendings 2))
                  (:yin.k/cell (nth pendings 3))))
        (is (= :ok (:status r)) (pr-str r))
        (is (= (first ids) (second ids)))
        (is (= 3 (count (set ids))) "three fresh cursor entries")))))


(defn- link-through
  "Run `m`, answering each link wait from `table` until none is left."
  [m response table]
  (loop [m (vm/run m) n 8]
    (let [e (first (filter #(contains? #{:link-request :link-response}
                                       (:reason %))
                           (:wait-set m)))]
      (if (and e (pos? n))
        (do (stream/append! response
                            (assoc (get table (:name e))
                                   :yin.link/id (:link-id e)))
            (recur (vm/run m) (dec n)))
        m))))


(deftest a-linked-modules-closure-alone-in-the-result-carries-its-census
  (doseq [engine s/engines]
    (testing (name engine)
      (let [request (s/ring 64)
            response (s/ring 64)
            module {:status :ok
                    :image {:value (s/module-image
                                     engine
                                     (s/def! 'f (s/lam [] (s/lit 42))))}
                    :manifest {:yin.module/name 'host.mod
                               :yin.module/exports #{'f}}
                    :obligations []}
            m (link-through
                (s/load-ast engine (s/linking-machine engine request response)
                            (s/then (s/require-program 'host.mod)
                                    (s/v 'host.mod/f)))
                response {'host.mod module})
            t (s/toy)
            peer (s/served-peer t)
            export (s/lift m t peer)
            body (:body export)
            [r _] (s/read! engine t (:bytes export)
                           {:address (:address export)})]
        (is (vm/halted? m))
        (is (values/closure? (vm/value m)))
        (is (= :ok (:status export)) (pr-str export))
        (is (= :halted (:kind export)))
        (is (seq (:yin.k/module-stores body))
            "the module's store crosses with the closure")
        (is (some #(contains? (:yin.k/module-stores body) %)
                  (keep :yin.k/store-of
                        (tree-seq coll? seq (:yin.k/result body)))))
        (is (= :ok (:status r)) (pr-str r))
        (is (values/closure? (vm/value (:vm r))))
        (is (values/owned-by? (vm/value (:vm r)) (:owner (:vm r))))
        (is (empty? (:installs (:vm r))) "no module initialization reruns")))))


(deftest numeric-carrier-classes-survive-code-and-value-positions
  (doseq [engine s/engines]
    (testing (name engine)
      (let [one (jing.cbor/float64 1)
            neg-zero (jing.cbor/float64 -0.0)
            parked (s/halted-with engine (s/lit [one neg-zero 1]))
            t (s/toy)
            peer (s/served-peer t)
            export (s/lift parked t peer)
            decoded (jing.cbor/decode (:bytes export))
            result (:yin.k/result decoded)
            [r n] (s/read! engine t (:bytes export)
                           {:address (:address export)})
            value (when (:vm r) (vm/value (:vm r)))]
        (is (= :ok (:status export)) (pr-str export))
        (is (jing.cbor/float64? (nth result 0)) "integral float stays float64")
        (is (jing.cbor/float64? (nth result 1)) "negative zero stays float64")
        (is (not (jing.cbor/float64? (nth result 2))) "the integer stays one")
        (is (= :ok (:status r)) (pr-str r))
        (is (some? n))
        (is (jing.cbor/float64? (nth value 0)))
        (is (jing.cbor/float64? (nth value 1)))
        (is (not (jing.cbor/float64? (nth value 2))))))))
