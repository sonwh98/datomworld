(ns yin.vm.macro-build-test
  "U16 Phase 2 (`docs/design/yin.vm.macro.md` §10.3): a build composes the
   same expander observer between file-backed media. Input batches are
   written to a file by the encoder, the expander's program and log packets
   are persisted to files, and every row is stored by its content address
   in a `dao.jing.file` content file, so the evaluator reads code that
   crossed storage by address alone."
  (:require [dao.jing :as jing]
            [clojure.edn :as edn]
            [clojure.java.io :as io]
            [clojure.string :as str]
            [clojure.test :refer [deftest is testing]]
            [dao.jing.file :as jing.file]
            [dao.stream :as stream]
            [dao.stream.observer :as observer]
            [yang.clojure :as yang]
            [yin.vm :as vm]
            [yin.vm.ast-walker :as ast-walker]
            [yin.vm.encoder :as encoder]
            [yin.vm.engine :as engine]
            [yin.vm.macro :as m]
            [yin.vm.test-utils :as tu]))


(def ^:private sources
  "One program per input batch, in order: a macro definition, a use beside a
   frontend-lowered defn, a call the expander rejects, and a later use."
  ['((defmacro unless
       [c a b]
       (yin/if c b a)))
   '((defn sq
       [x]
       (* x x)) (unless false (sq 7) 0))
   '((unless 1 2))
   '((unless false (sq 3) 0))])


(defn- temp-dir
  []
  (let [f (java.io.File/createTempFile "u16-build" "")]
    (.delete f)
    (.mkdirs f)
    f))


(defn- write-values!
  "A file-backed medium: one EDN value per line, in append order."
  [file values]
  (spit file (str/join (map #(str (pr-str %) "\n") values))))


(defn- read-values
  [file]
  (mapv edn/read-string (str/split-lines (slurp file))))


(defn- drain
  "Every value appended to `handle`, in order."
  [handle]
  (loop [cursor (:dao.stream/cursor (stream/cursor handle :dao.stream/oldest))
         acc []]
    (let [r (stream/next handle cursor)]
      (if (= :dao.stream/ok (:dao.stream/outcome r))
        (recur (:dao.stream/cursor r) (conj acc (:dao.stream/value r)))
        acc))))


(defn- build!
  "Expand the batches of `program-in-file` with a fresh expander of
   incarnation `token`, persisting each forwarded tree packet to
   `program-out-file` and each log packet to `log-file`. Returns the
   drained summary."
  [program-in-file program-out-file log-file token]
  (let [{program-in :writer in-observer :observer} (tu/make-attachment 64)
        {program-out :writer} (tu/make-attachment 64)
        {log :writer} (tu/make-attachment 64)
        ctx (m/make-ctx {:token token
                         :source-medium [:file (.getName (io/file program-in-file))]})
        ctx (assoc ctx :store (m/seed-store ctx [m/stdlib-forms]))]
    (doseq [batch (read-values program-in-file)]
      (stream/append! program-in batch))
    (let [session (m/step {:observer in-observer
                           :consumer (m/make-expander ctx program-out log)})
          [_ summary] (m/drain-errors session)]
      (write-values! program-out-file (drain program-out))
      (write-values! log-file (drain log))
      summary)))


(defn- store-rows!
  "Put every row body of `packets` into the content file by its address;
   the content file verifies that the address hashes the body."
  [content packets]
  (doseq [[_ rows] packets
          row rows]
    ((:put-bytes-fn content) (first row)
                             (jing/segment-bytes (first row) (subvec row 1)))))


(defn- fetch-packet
  "Rebuild a packet from its root and row addresses through the content
   file alone."
  [content [root rows]]
  [root (mapv (fn [row]
                (let [a (first row)]
                  (into [a] (jing/get content a nil))))
              rows)])


(defn- evaluate
  "Load and run every packet in order on one ast-walker, as the REPL's
   evaluator observer does."
  [packets]
  (let [{:keys [writer observer]} (tu/make-attachment 64)]
    (doseq [p packets] (stream/append! writer p))
    (:consumer (observer/run-on-stream
                 {:observer observer, :consumer (tu/create-vm)}
                 engine/ready-for-ingress?
                 (fn [v p]
                   (ast-walker/vm-load-rows v (m/packet->row-set p)
                                            vm/ast-contract))
                 vm/run))))


(deftest a-build-expands-between-file-backed-media
  (let [dir (temp-dir)
        program-in (io/file dir "program-in.edn")
        program-out (io/file dir "program-out.edn")
        log-file (io/file dir "macro-log.edn")
        rows-file (io/file dir "rows.content")
        batches (mapv #(encoder/program-batch (yang/compile-program %)) sources)
        _ (write-values! program-in batches)
        summary (build! program-in program-out log-file :build-1)
        out (read-values program-out)
        logs (read-values log-file)]
    (testing "the failed batch forwards nothing and is reported as data"
      (is (= 3 (:forwarded summary)))
      (is (= [:arity] (mapv :kind (:errors summary))))
      (is (= 3 (count out))))
    (testing "the log records every attempt, the failure included"
      (is (= 4 (count logs)))
      (let [events (mapcat second logs)]
        (is (= 3 (count events)) "three recognized calls: two uses and the failure")
        (is (= [nil :arity nil] (mapv #(:kind (peek %)) events))
            "the error slot is an event row's last")))
    (testing "rows cross storage by address and still evaluate"
      (let [content (jing.file/create-content-file (str rows-file))]
        (store-rows! content (map second batches))
        (store-rows! content out)
        ((:close-fn content)))
      (let [content (jing.file/create-content-file (str rows-file))
            fetched (mapv #(fetch-packet content %) out)]
        ((:close-fn content))
        (is (= out fetched) "every address resolves to the row it named")
        (is (every? nil? (map m/valid-tree? fetched)))
        (is (= 9 (vm/value (evaluate fetched))))))
    (testing "the same input and incarnation rebuild byte-identical files"
      (let [out' (io/file dir "program-out-2.edn")
            log' (io/file dir "macro-log-2.edn")]
        (build! program-in out' log' :build-1)
        (is (= (slurp program-out) (slurp out')))
        (is (= (slurp log-file) (slurp log')))))
    (testing "a new incarnation keeps every output address and mints new events"
      (let [out' (io/file dir "program-out-3.edn")
            log' (io/file dir "macro-log-3.edn")]
        (build! program-in out' log' :build-2)
        (is (= out (read-values out')))
        (is (not= (map first (mapcat second logs))
                  (map first (mapcat second (read-values log')))))))))
