(ns yang.correspondence-static
  "Static C1 production, storage and query measurement; not D1 verification."
  (:require [dao.jing :as jing]
            [dao.jing.cbor :as cbor]
            [dao.space.query :as query]
            [yang.correspondence-verifier :as verifier]
            [yin.vm :as vm]))


(defn measure-static!
  "For a retained tree and recorded origin, rederive C1 rows, compare them to
   supplied metadata, materialize and fetch both, then query C1 markers. The
   handle is caller-supplied so experiments can use fresh stores. No guest runs."
  [tree origin metadata handle clock]
  (when-not (vm/occurrence-origin? origin)
    (throw (ex-info "Invalid occurrence origin" {:stage :admission})))
  (let [elapsed (fn [t] (quot (+ (- (clock) t) 999999) 1000000))
        t (clock)
        baseline (vm/ast->semantic-bytecode (vm/semantic-bytecode->ast tree))
        baseline-ms (elapsed t)
        _ (when-not (= tree baseline)
            (throw (ex-info "Baseline row projection differs" {:stage :root})))
        t (clock)
        applications (vm/application-occurrences tree)
        markers (mapv (fn [[root path]] [origin root path :yang/call-site 1])
                      (sort-by pr-str applications))
        supplied (filterv #(and (vector? %) (= 5 (count %))
                                (= :yang/call-site (nth % 3))) metadata)
        _ (when-not (and (= (count supplied) (count (set supplied)))
                         (= (set markers) (set supplied)))
            (throw (ex-info "C1 markers differ" {:stage :metadata})))
        marker-ms (elapsed t)
        t (clock)
        root (vm/materialize-tree! handle tree)
        marker-address (jing/materialize! handle markers)
        publish-ms (elapsed t)
        t (clock)
        loaded-tree (verifier/fetch-tree! handle root (inc (count (:rows tree))))
        loaded-markers (jing/get handle marker-address ::missing)
        _ (when-not (and (= tree loaded-tree) (= markers loaded-markers))
            (throw (ex-info "C1 content differs after fetch" {:stage :load})))
        load-ms (elapsed t)
        t (clock)
        relation (query/relation (mapv (fn [[o r p a value]] [[o r p] a value 0 nil]) loaded-markers))
        relation-ms (elapsed t)
        t (clock)
        found (set (query/collect (query/q '[:find ?e :where [?e :yang/call-site 1 _ _]] relation)))
        expected (set (map (fn [[o r p]] [[o r p]]) loaded-markers))
        _ (when-not (= expected found)
            (throw (ex-info "C1 marker query differs" {:stage :query})))
        query-ms (elapsed t)]
    {:status :pass :root root :marker-address marker-address :marker-count (count markers)
     :marker-bytes (alength (cbor/encode markers))
     :phases {:baseline-project-ms baseline-ms :marker-derive-ms marker-ms
              :publish-ms publish-ms :load-ms load-ms :relation-build-ms relation-ms :query-ms query-ms}}))


(defn compare-static!
  "Measure baseline tree publication/retrieval in one fresh store and C1
   rederivation/publication/retrieval/query in another. Both use the exact
   same retained root; no guest executes and no new source origin is minted."
  [tree origin metadata baseline-store c1-store clock]
  (when (identical? baseline-store c1-store)
    (throw (ex-info "Baseline and C1 stores must be distinct" {:stage :configuration})))
  (let [elapsed (fn [t] (quot (+ (- (clock) t) 999999) 1000000))
        t (clock)
        root (vm/materialize-tree! baseline-store tree)
        publish-ms (elapsed t)
        t (clock)
        loaded (verifier/fetch-tree! baseline-store root (inc (count (:rows tree))))
        _ (when-not (= tree loaded)
            (throw (ex-info "Baseline tree differs after reload" {:stage :load})))
        load-ms (elapsed t)
        result (measure-static! tree origin metadata c1-store clock)]
    (when-not (= root (:root result))
      (throw (ex-info "Static C1 changed code root" {:stage :root})))
    (assoc result :baseline-phases {:publish-ms publish-ms :load-ms load-ms})))
