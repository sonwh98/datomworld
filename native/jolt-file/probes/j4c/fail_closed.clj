;; Run under jolt -Srepro -M:jolt by probes/run.clj: the J4c fail-closed
;; handle state (review F3) of dao.posix.file under dao.jing.file and the
;; file journal. Faults are injected through the facade's single-call vars
;; (pwrite-once, fsync-once, close-once); jolt.ffi/alloc and free are
;; accounted around everything.
;;
;; Prints PASS/FAIL per gate and RECORD per observation; exits nonzero on
;; any FAIL. Scratch under build/j4c-probe/run-<millis>/, fresh each run.
(ns fail-closed
  (:require [jolt.ffi :as ffi]))


(require '[dao.jing :as jing] '[dao.jing.file :as jf]
         '[dao.stream :as stream] '[dao.stream.journal :as journal]
         '[dao.stream.journal.file :as jfile])


(def failures (atom 0))


(defn- check
  [label ok? detail]
  (when-not ok? (swap! failures inc))
  (println (if ok? "PASS" "FAIL") label (pr-str detail)))


;; a fresh root per run: a reused scratch file would hold earlier frames
(def root
  (str (System/getProperty "user.dir") "/build/j4c-probe/run-"
       (System/currentTimeMillis)))


(.mkdirs (java.io.File. ^String root))
(def counter (atom 0))


(defn- scratch
  [nm]
  (str root "/" nm "-" (swap! counter inc)))


(defn- size
  [path]
  (.length (java.io.File. ^String path)))


(defn- thrown
  [f]
  (try (f) nil (catch Throwable e e)))


(def EIO 5)
(def ENOSPC 28)
(def pwrite-var (ns-resolve 'dao.posix.file 'pwrite-once))
(def fsync-var (ns-resolve 'dao.posix.file 'fsync-once))
(def close-var (ns-resolve 'dao.posix.file 'close-once))
(def orig-pwrite @pwrite-var)
(def orig-close @close-var)

(def ledger (atom {:allocs 0, :frees 0, :live #{}}))
(def orig-alloc ffi/alloc)
(def orig-free ffi/free)


(defn- torn-pwrite
  "pwrite-once that writes `first-bytes` of its first call, then answers
   -errno for every later call."
  [first-bytes errno]
  (let [n (atom 0)]
    (fn [fd off buf at len]
      (if (= 1 (swap! n inc))
        (orig-pwrite fd off buf at (min len first-bytes))
        (- errno)))))


(with-redefs-fn
  {#'ffi/alloc (fn [& args]
                 (let [p (apply orig-alloc args)]
                   (swap! ledger #(-> % (update :allocs inc) (update :live conj (ffi/address p))))
                   p))
   #'ffi/free (fn [p]
                (swap! ledger #(-> % (update :frees inc) (update :live disj (ffi/address p))))
                (orig-free p))}
  (fn []

    ;; -------------------------------------------------------------------------
    ;; content log: a torn append makes the handle fail-closed
    ;; -------------------------------------------------------------------------
    (let [path (str (scratch "torn") ".log")
          h (jf/create-content-file path)
          a1 (jing/materialize! h "first")
          closes (atom 0)
          e1 (with-redefs-fn {pwrite-var (torn-pwrite 5 ENOSPC)}
               #(thrown (fn [] (jing/materialize! h {:torn true}))))
          len-after-tear (size path)
          e2 (thrown #(jing/materialize! h "a later valid frame"))
          e3 (thrown #((:put-bytes-fn h) (jing/segment-key "raw") (jing/canonical-bytes "raw")))
          served (jing/get h a1 ::absent)
          unacked (jing/get h (jing/segment-key {:torn true}) ::absent)
          len-after-refusals (size path)]
      (with-redefs-fn {close-var (fn [fd] (swap! closes inc) (orig-close fd))}
        #(do (jing/close! h) (jing/close! h)))
      (println "RECORD torn append" (pr-str {:first (ex-message e1), :later (ex-data e2)}))
      (check "a torn append (5 bytes, then ENOSPC) throws; every later put through the handle throws naming the path and the failed operation"
             (and e1 e2 e3 (= "pwrite" (:operation (ex-data e2))) (= path (:path (ex-data e2)))
                  (:dao.posix.file/fail-closed (ex-data e2)) (:dao.posix.file/fail-closed (ex-data e3)))
             {:later (ex-message e2)})
      (check "a fail-closed handle appends nothing more: the file length is unchanged by refused puts"
             (= len-after-tear len-after-refusals) {:after-tear len-after-tear, :after-refusals len-after-refusals})
      (check "get keeps serving the in-memory map; the unacknowledged frame is not there"
             (and (= "first" served) (= ::absent unacked)) {:served served})
      (check "close still closes once (two close! calls, one close)" (= 1 @closes) {:closes @closes})
      (let [h2 (jf/create-content-file path)
            a3 (jing/materialize! h2 "after reopen")]
        (check "reopen drops the torn tail through the shared recovery, keeps the acknowledged prefix, and accepts new puts"
               (and (= "first" (jing/get h2 a1 ::absent))
                    (= ::absent (jing/get h2 (jing/segment-key {:torn true}) ::absent))
                    (= "after reopen" (jing/get h2 a3 ::absent))
                    (= 2 (count (jf/records path))))
               {:records (count (jf/records path))})
        (jing/close! h2)))

    ;; -------------------------------------------------------------------------
    ;; content log: a failed fsync makes the handle fail-closed (never retried)
    ;; -------------------------------------------------------------------------
    (let [path (str (scratch "fsync") ".log")
          h (jf/create-content-file path)
          a1 (jing/materialize! h "first")
          syncs (atom 0)
          e1 (with-redefs-fn {fsync-var (fn [_fd] (swap! syncs inc) (- EIO))}
               #(thrown (fn [] (jing/materialize! h "unsynced"))))
          e2 (with-redefs-fn {fsync-var (fn [_fd] (swap! syncs inc) 0)}
               #(thrown (fn [] (jing/materialize! h "next"))))]
      (println "RECORD failed fsync" (pr-str {:first (ex-data e1), :later (ex-data e2), :fsync-calls @syncs}))
      (check "a failed fsync (EIO) throws, is not retried (one fsync call), and the handle then refuses puts naming fsync, before any further fsync"
             (and e1 (= "fsync" (:operation (ex-data e1))) (= 1 @syncs)
                  e2 (= "fsync" (:operation (ex-data e2))) (:dao.posix.file/fail-closed (ex-data e2)))
             {:fsync-calls @syncs, :later (ex-message e2)})
      (check "get serves the acknowledged value and not the unacknowledged one"
             (and (= "first" (jing/get h a1 ::absent))
                  (= ::absent (jing/get h (jing/segment-key "unsynced") ::absent)))
             nil)
      (jing/close! h)
      ;; the unsynced frame was written whole: recovery may retain it (it is
      ;; complete and valid), never truncating the acknowledged prefix
      (let [recs (jf/records path)]
        (check "reopen after a failed fsync keeps the acknowledged prefix; the complete unsynced frame may stand, never a torn one"
               (and (= [(jing/segment-key "first") "first"] (first recs)) (<= 1 (count recs) 2))
               {:records (mapv second recs)})))

    ;; -------------------------------------------------------------------------
    ;; the file journal over a fail-closed handle: poison, then reopen and
    ;; continue without identity or order drift
    ;; -------------------------------------------------------------------------
    (let [dir (scratch "journal")
          open! (fn []
                  (let [b (::jfile/backend (jfile/backend! dir))]
                    [b (:dao.stream/handle (journal/open! b))]))
          values (fn [hd]
                   (loop [c (:dao.stream/cursor (stream/cursor hd :dao.stream/oldest)) vs []]
                     (let [r (stream/next hd c)]
                       (if (= :dao.stream/ok (:dao.stream/outcome r))
                         (recur (:dao.stream/cursor r) (conj vs (:dao.stream/value r)))
                         vs))))
          [b hd] (open!)
          id1 (:dao.stream/identity (stream/descriptor hd))
          acked (mapv #(:dao.stream/outcome (stream/append! hd %)) [0 1 2])
          torn (with-redefs-fn {pwrite-var (torn-pwrite 7 EIO)}
                 #(stream/append! hd 3))
          later (stream/append! hd 4)]
      (jfile/close! b)
      (let [[b2 hd2] (open!)
            id2 (:dao.stream/identity (stream/descriptor hd2))
            before (values hd2)
            more (mapv #(:dao.stream/outcome (stream/append! hd2 %)) [3 4])
            after (values hd2)]
        (jfile/close! b2)
        (let [[b3 hd3] (open!)
              id3 (:dao.stream/identity (stream/descriptor hd3))
              final (values hd3)]
          (jfile/close! b3)
          (println "RECORD journal" (pr-str {:acked acked, :torn (dissoc torn :dao.stream/handle),
                                             :later (dissoc later :dao.stream/handle),
                                             :reopened before, :continued after, :final final}))
          (check "the journal: a torn write answers a transport error (poisoned), later appends refuse; reopen yields the dense acknowledged prefix, appends continue, and a further reopen keeps identity and order"
                 (and (= [:dao.stream/ok :dao.stream/ok :dao.stream/ok] acked)
                      (not= :dao.stream/ok (:dao.stream/outcome torn))
                      (not= :dao.stream/ok (:dao.stream/outcome later))
                      (= [0 1 2] before) (= [:dao.stream/ok :dao.stream/ok] more)
                      (= [0 1 2 3 4] after final) (= id1 id2 id3))
                 {:reopened before, :final final, :identities [id1 id2 id3]}))))))


(let [{:keys [allocs frees live]} @ledger]
  (check "no native-memory leak across the fail-closed gates" (and (pos? allocs) (= allocs frees) (empty? live))
         {:allocs allocs, :frees frees}))


(System/exit (if (zero? @failures) 0 1))
