;; Post-port oracle-binding re-verification (round 1794519755000), item 4/5
;; evidence harness. NOT part of the test suite; run read-only via
;;   clojure -M -e '(load-file "verify/digest_cohort.clj")'
;; from the tree root being measured (post-port tip or the extracted
;; pre-port tree). Prints one EDN map per line; identical inputs at two
;; tree shapes must print identical digests unless the port moved the
;; canonical-row contract for ordinary (non-exception) sources.
(ns digest-cohort
  (:require [yang.clojure :as yang]
            [yin.vm :as vm]
            [yin.vm.encoder :as encoder]
            [yin.vm.semantic.linearize :as linearize]
            [dao.jing.cbor :as cbor]))


(def ^:private token "pp-verify-batch-1")


(def ^:private cohort
  "Ordinary (exception-free) programs, fixed. :arith* is the in-run
   sensitivity probe: one changed literal must change every digest that
   depends on it, proving the digests are not vacuously constant."
  [[:arith [(quote (defn f
                     [x]
                     (+ (* x 2) 1))) (quote (f 21))]]
   [:arith* [(quote (defn f
                      [x]
                      (+ (* x 2) 1))) (quote (f 22))]]
   [:higher-order [(quote (defn inc1
                            [x]
                            (+ x 1)))
                   (quote (defn twice
                            [g v]
                            (g (g v))))
                   (quote (twice inc1 5))]]
   [:loop [(quote (defn sum
                    [xs]
                    (loop [xs xs acc 0]
                      (if (seq xs) (recur (rest xs) (+ acc (first xs))) acc))))
           (quote (sum (quote (1 2 3 4))))]]
   [:case [(quote (defn dispatch
                    [e]
                    (case e a 1 b 2 3)))
           (quote (dispatch (quote a)))]]
   [:destructure [(quote (defn area
                           [r]
                           (let [w (:w r) h (:h r)] (* w h))))
                  (quote (area (quote {:w 3 :h 4})))]]])


(defn- sha256
  ^String [^bytes bs]
  (let [md (java.security.MessageDigest/getInstance "SHA-256")]
    (apply str (map #(format "%02x" %) (.digest md bs)))))


(defn- digest
  [id program]
  (let [ast (yang/compile-program program)
        [root datoms] (vm/ast->datoms-with-root ast)
        canonical-sha (sha256 (cbor/encode datoms))
        projected (encoder/project
                    (encoder/source-envelope :program token [ast]))
        tree (first (:yin/batch projected))
        metadata (:yin/frontend-metadata projected)
        sem (linearize/project-rows tree nil metadata)
        marked (into #{}
                     (comp (filter #(= :yang/call-site (nth % 3)))
                           (map (fn [[o r path _ _]] [o r path])))
                     metadata)
        eligible (into #{}
                       (comp (filter (fn [[_ _ id]]
                                       (= :application (nth (get (:rows tree) id) 1))))
                             (map (fn [[_ path _]]
                                    [[:source :program token 0]
                                     (:root tree) path])))
                       (vm/occurrences tree))]
    {:cohort id
     :root root
     :canonical-sha canonical-sha
     :c1-metadata-sha (sha256 (.getBytes (pr-str metadata) "UTF-8"))
     :provenance-sha (sha256 (.getBytes (pr-str (:provenance sem)) "UTF-8"))
     :call-provenance-sha (sha256 (.getBytes (pr-str (:call-provenance sem)) "UTF-8"))
     :vector-sha (sha256 (.getBytes (pr-str (:vector sem)) "UTF-8"))
     :apps (count eligible)
     :marked (count marked)
     :c1-equal (= eligible marked)}))


(defn -main
  []
  (doseq [[id program] cohort]
    (prn (digest id program))))
