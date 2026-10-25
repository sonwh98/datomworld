;; Run under jolt -Srepro -M:jolt by probes/run.clj: the in-process J4a gate
;; rows over dao.posix.file and dao.jing.file's :jolt arms
;; (collab/1792769713135-architect-j4-file-seams-design §5, J4a row; review
;; F2). The test-only indirection is dao.posix.file's single-transfer vars
;; (pread-once, pwrite-once, fsync-once, close-once), redefined here; the
;; library has no fault mode. jolt.ffi/alloc and free are wrapped to account
;; for every native buffer.
;;
;; Prints PASS/FAIL per gate and RECORD per observation; exits nonzero on any
;; FAIL. Scratch files live under build/j4a-probe/gates/.
(ns gates
  (:require [jolt.ffi :as ffi]))


(require '[dao.posix.file :as hf] '[dao.jing :as jing] '[dao.jing.file :as jf])

(def failures (atom 0))


(defn- check
  [label ok? detail]
  (when-not ok? (swap! failures inc))
  (println (if ok? "PASS" "FAIL") label (pr-str detail)))


(def dir "build/j4a-probe/gates")
(.mkdirs (java.io.File. dir))
(def counter (atom 0))


(defn- scratch
  [nm]
  (str dir "/" nm "-" (swap! counter inc) ".log"))


(defn- delete!
  [path]
  (.delete (java.io.File. ^String path)))


(defn- file-bytes
  [path]
  (with-open [r (java.io.RandomAccessFile. (java.io.File. ^String path) "r")]
    (let [b (byte-array (.length r))]
      (.readFully r b)
      (vec (map #(bit-and % 0xff) b)))))


(defn- write-bytes!
  "Replace the file at path with the octets `ints`."
  [path ints]
  (delete! path)
  (with-open [r (java.io.RandomAccessFile. (java.io.File. ^String path) "rw")]
    (.write r (byte-array (map unchecked-byte ints)))))


(defn- thrown
  "The throwable (f) raised, or nil."
  [f]
  (try (f) nil (catch Throwable e e)))


(def eintr @(ns-resolve 'dao.posix.file 'eintr))
(def pread-var (ns-resolve 'dao.posix.file 'pread-once))
(def pwrite-var (ns-resolve 'dao.posix.file 'pwrite-once))
(def fsync-var (ns-resolve 'dao.posix.file 'fsync-once))
(def close-var (ns-resolve 'dao.posix.file 'close-once))
(def orig-pread @pread-var)
(def orig-pwrite @pwrite-var)
(def orig-fsync @fsync-var)
(def orig-close @close-var)
(def c-s32be-put @(ns-resolve 'dao.posix.file 'c-s32be-put))
(def c-s32be-get @(ns-resolve 'dao.posix.file 'c-s32be-get))


;; EIO and ENOSPC are 5 and 28 on both qualified platforms (sys/errno.h)
(def EIO 5)
(def ENOSPC 28)


;; =============================================================================
;; Native memory accounting, around every gate
;; =============================================================================

(def ledger (atom {:allocs 0, :frees 0, :live #{}, :double-free 0}))
(def orig-alloc ffi/alloc)
(def orig-free ffi/free)


(defn- counting-alloc
  [& args]
  (let [p (apply orig-alloc args)]
    (swap! ledger #(-> % (update :allocs inc) (update :live conj (ffi/address p))))
    p))


(defn- counting-free
  [p]
  (let [a (ffi/address p)]
    (swap! ledger #(if (contains? (:live %) a)
                     (-> % (update :frees inc) (update :live disj a))
                     (update % :double-free inc))))
  (orig-free p))


(defn- capped
  "A transfer var's replacement: at most `cap` bytes per call, and EINTR on
   the first call and every `eintr-every`th after it, counting calls into
   `calls`."
  [orig cap eintr-every calls]
  (fn [fd off buf at n]
    (let [i (swap! calls inc)]
      (if (and eintr-every (zero? (mod (dec i) eintr-every)))
        (- eintr)
        (orig fd off buf at (min n cap))))))


(with-redefs-fn
  {#'ffi/alloc counting-alloc, #'ffi/free counting-free}
  (fn []

    ;; -------------------------------------------------------------------------
    ;; F2: the s32BE prefix is converted by the wrapper; top-bit prefixes are
    ;; negative
    ;; -------------------------------------------------------------------------
    (let [path (scratch "f2")]
      (write-bytes! path [0x7f 0xff 0xff 0xff, 0x80 0 0 0, 0xff 0xff 0xff 0xff, 0 0 1 0])
      (let [h (hf/open path)
            got (try (mapv #(hf/read-s32be h %) [0 4 8 12]) (finally (hf/close! h)))]
        (println "RECORD f2 prefixes 7fffffff 80000000 ffffffff 00000100 read as" got)
        (check "F2: 0x7fffffff, 0x80000000, 0xffffffff read back as 2147483647, -2147483648, -1"
               (= got [2147483647 -2147483648 -1 256]) got))
      (let [vals [2147483647 -2147483648 -1 0 1 256 -256]
            packed (mapv (fn [v]
                           (let [p (ffi/alloc 4)]
                             (try (c-s32be-put p 0 v)
                                  [(vec (map #(bit-and % 0xff) (ffi/read-array p 4)))
                                   (c-s32be-get p 0)]
                                  (finally (ffi/free p)))))
                         vals)]
        (println "RECORD f2 s32be_put/get" (pr-str (zipmap vals packed)))
        (check "F2: s32be_put packs big-endian two's complement and s32be_get inverts it"
               (= packed [[[0x7f 0xff 0xff 0xff] 2147483647] [[0x80 0 0 0] -2147483648]
                          [[0xff 0xff 0xff 0xff] -1] [[0 0 0 0] 0] [[0 0 0 1] 1]
                          [[0 0 1 0] 256] [[0xff 0xff 0xff 0] -256]])
               packed))
      (delete! path))

    ;; a valid record, then a 0x80000000 prefix: the negative length is torn
    (let [path (scratch "f2-torn")
          h (jf/create-content-file path)
          a (jing/materialize! h "survivor")
          _ (jing/close! h)
          good (file-bytes path)]
      (write-bytes! path (into good [0x80 0 0 0 1 2]))
      (let [h2 (jf/create-content-file path)]
        (try
          (check "F2: a 0x80000000 prefix is a torn tail, truncated at the validated cut"
                 (and (= good (file-bytes path)) (= "survivor" (jing/get h2 a ::absent)))
                 {:length (count (file-bytes path)), :expected (count good)})
          (finally (jing/close! h2))))
      (delete! path))

    ;; -------------------------------------------------------------------------
    ;; strict reads: end of file before a complete read is an error, never
    ;; zero padding
    ;; -------------------------------------------------------------------------
    (let [path (scratch "strict")]
      (write-bytes! path (range 10))
      (let [h (hf/open path)]
        (try
          (let [e1 (thrown #(hf/read-bytes h 8 4))
                e2 (thrown #(hf/read-s32be h 8))
                e3 (thrown #(hf/read-bytes h 20 1))
                ok (vec (map #(bit-and % 0xff) (hf/read-bytes h 6 4)))
                empty (count (hf/read-bytes h 10 0))]
            (println "RECORD strict read past end" (pr-str (ex-data e1)))
            (check "strict reads: a read crossing end of file throws (read 2 of 4), never pads"
                   (and e1 e2 e3 (= 2 (:read (ex-data e1))) (= 0 (:read (ex-data e3)))
                        (= [6 7 8 9] ok) (zero? empty))
                   {:e1 (ex-data e1), :e2 (some-> e2 ex-message), :ok ok}))
          (finally (hf/close! h))))
      (delete! path))

    ;; -------------------------------------------------------------------------
    ;; injected transfers
    ;; -------------------------------------------------------------------------
    (let [path (scratch "reads")
          content (vec (map #(mod (* 7 %) 256) (range 1000)))]
      (write-bytes! path content)
      (let [h (hf/open path)]
        (try
          (doseq [[label cap every] [["1-byte" 1 nil] ["3-byte" 3 nil] ["EINTR every 2nd" 1000 2]
                                     ["3-byte + EINTR every 3rd" 3 3]]]
            (let [calls (atom 0)
                  got (with-redefs-fn {pread-var (capped orig-pread cap every calls)}
                        #(vec (map (fn [b] (bit-and b 0xff)) (hf/read-bytes h 0 1000))))]
              (check (str "injected reads (" label "): the loop continues to the exact bytes")
                     (and (= content got) (> @calls 1)) {:calls @calls})))
          (let [calls (atom 0)
                e (with-redefs-fn {pread-var (fn [fd off buf at n]
                                               (if (= 2 (swap! calls inc)) 0 (orig-pread fd off buf at (min n 10))))}
                    #(thrown (fn [] (hf/read-bytes h 0 100))))]
            (check "injected read zero progress (premature end of file): throws, read 10 of 100"
                   (and e (= 10 (:read (ex-data e)))) (ex-data e)))
          (let [e (with-redefs-fn {pread-var (fn [& _] (- EIO))}
                    #(thrown (fn [] (hf/read-s32be h 0))))]
            (check "injected read error (EIO): throws with the errno and the path"
                   (and e (= EIO (:errno (ex-data e))) (= path (:path (ex-data e))))
                   (ex-data e)))
          (finally (hf/close! h))))
      (delete! path))

    ;; writes, through dao.jing.file's put: the injected file is byte-identical
    ;; to an uninjected one
    (let [payloads ["one" {:k [1 2 3]} (apply str (repeat 300 "x"))]
          reference (scratch "ref")
          h (jf/create-content-file reference)]
      (doseq [p payloads] (jing/materialize! h p))
      (jing/materialize! h {:sha true} {:algorithm :sha256})
      (jing/close! h)
      (doseq [[label cap every] [["1-byte" 1 nil] ["3-byte" 3 nil] ["EINTR every 2nd" 100000 2]
                                 ["3-byte + EINTR every 4th" 3 4]]]
        (let [path (scratch "writes")
              calls (atom 0)]
          (with-redefs-fn {pwrite-var (capped orig-pwrite cap every calls)}
            #(let [h (jf/create-content-file path)]
               (doseq [p payloads] (jing/materialize! h p))
               (jing/materialize! h {:sha true} {:algorithm :sha256})
               (jing/close! h)))
          (check (str "injected writes (" label "): every put completes, file byte-identical")
                 (and (= (file-bytes reference) (file-bytes path)) (> @calls 4))
                 {:calls @calls})
          (delete! path)))
      (delete! reference))

    (doseq [[label inject expect]
            [["zero progress" (fn [calls orig]
                                (fn [fd off buf at n]
                                  (if (= 2 (swap! calls inc)) 0 (orig fd off buf at (min n 5)))))
              #(re-find #"no progress" (ex-message %))]
             ["ENOSPC" (fn [calls orig]
                         (fn [fd off buf at n]
                           (if (= 2 (swap! calls inc)) (- ENOSPC) (orig fd off buf at (min n 5)))))
              #(= ENOSPC (:errno (ex-data %)))]]]
      (let [path (scratch "write-fail")
            h (jf/create-content-file path)
            a1 (jing/materialize! h "first")
            calls (atom 0)
            e (with-redefs-fn {pwrite-var (inject calls orig-pwrite)}
                #(thrown (fn [] (jing/materialize! h {:torn true}))))
            a2 (jing/segment-key {:torn true})
            unacknowledged (= ::absent (jing/get h a2 ::absent))]
        (jing/close! h)
        (let [h2 (jf/create-content-file path)
              recovered [(jing/get h2 a1 ::absent) (jing/get h2 a2 ::absent)]]
          (jing/close! h2)
          (println "RECORD injected write" label (pr-str (ex-data e)))
          (check (str "injected write " label ": the put throws, is not acknowledged, and reopen recovers the prefix")
                 (and e (expect e) unacknowledged (= ["first" ::absent] recovered))
                 {:error (some-> e ex-message), :recovered recovered}))
        (delete! path)))

    (let [path (scratch "fsync-fail")
          h (jf/create-content-file path)
          e (with-redefs-fn {fsync-var (fn [_] (- EIO))}
              #(thrown (fn [] (jing/materialize! h "unsynced"))))
          a (jing/segment-key "unsynced")]
      (check "injected fsync error (EIO): the put throws and is not acknowledged (never retried)"
             (and e (= EIO (:errno (ex-data e))) (= "fsync" (:operation (ex-data e)))
                  (= ::absent (jing/get h a ::absent)))
             (ex-data e))
      (jing/close! h)
      (delete! path))

    ;; -------------------------------------------------------------------------
    ;; a corrupt complete frame fails the open with the file byte-identical
    ;; (the torn tail behind it is NOT truncated: validation precedes mutation)
    ;; -------------------------------------------------------------------------
    (let [path (scratch "corrupt")
          h (jf/create-content-file path)]
      (jing/materialize! h {:a 1})
      (jing/materialize! h {:b 2} {:algorithm :sha256})
      (jing/close! h)
      (let [bs (file-bytes path)
            corrupt (conj (update bs (- (count bs) 2) #(bit-xor % 1)) 0 0)]
        (write-bytes! path corrupt)
        (let [e (thrown #(jf/create-content-file path))
              e2 (thrown #(jf/records path))]
          (check "corrupt complete frame: the open throws and the file is byte-identical (tail kept)"
                 (and e e2 (= corrupt (file-bytes path)))
                 {:error (some-> e ex-message), :length (count (file-bytes path))})))
      (delete! path))

    ;; -------------------------------------------------------------------------
    ;; idempotent close: one close attempt per descriptor, EINTR not retried
    ;; -------------------------------------------------------------------------
    (let [path (scratch "close")
          calls (atom [])]
      (with-redefs-fn {close-var (fn [fd] (swap! calls conj fd) (orig-close fd))}
        #(let [h (jf/create-content-file path)]
           (jing/materialize! h "x")
           (jing/close! h)
           (jing/close! h)
           (let [raw (hf/open path)]
             (hf/close! raw)
             (hf/close! raw))))
      (let [eintr-calls (atom 0)
            raw (hf/open path)
            ok (with-redefs-fn {close-var (fn [fd] (swap! eintr-calls inc) (orig-close fd) (- eintr))}
                 #(do (hf/close! raw) (hf/close! raw) :no-throw))
            after (thrown #(hf/length raw))]
        (check "idempotent close: one close per descriptor; EINTR is released, not retried"
               (and (= 2 (count @calls)) (= 1 @eintr-calls) (= :no-throw ok) after)
               {:closes @calls, :eintr-closes @eintr-calls}))
      (delete! path))

    ;; -------------------------------------------------------------------------
    ;; open: checked parents, never truncates, NUL refused not truncated
    ;; -------------------------------------------------------------------------
    (let [nested (str dir "/n" (swap! counter inc) "/a/b/c.log")
          h (hf/open nested)]
      (hf/close! h)
      (check "open: missing parent directories are created"
             (.exists (java.io.File. ^String nested)) nested))
    (let [blocker (scratch "blocker")
          _ (write-bytes! blocker [1])
          e (thrown #(hf/open (str blocker "/child.log")))]
      (check "open: a parent that is a regular file fails (checked parent creation)"
             (and e (= "create-parents" (:operation (ex-data e)))) (ex-data e))
      (delete! blocker))
    (let [path (scratch "keep")]
      (write-bytes! path (range 50))
      (let [h (hf/open path)
            n (try (hf/length h) (finally (hf/close! h)))]
        (check "open: an existing file is never truncated" (= 50 n (count (file-bytes path))) n))
      (delete! path))
    (let [base (scratch "nul")
          e (thrown #(hf/open (str base "\u0000tail")))]
      (check "open: a path holding NUL is refused, never truncated at it"
             (and e (not (.exists (java.io.File. ^String base)))) (some-> e ex-data)))

    ;; -------------------------------------------------------------------------
    ;; no descriptor leak: success, failed opens and records, many times
    ;; -------------------------------------------------------------------------
    (let [fd-dir (if (.exists (java.io.File. "/proc/self/fd")) "/proc/self/fd" "/dev/fd")
          fds #(count (.list (java.io.File. ^String fd-dir)))
          corrupt (scratch "leak-corrupt")
          _ (write-bytes! corrupt [0 0 0 2 0xff 0xff])
          before (fds)]
      (dotimes [i 100]
        (let [path (scratch "leak")
              h (jf/create-content-file path)]
          (jing/materialize! h {:i i})
          (jing/close! h)
          (jf/records path)
          (delete! path))
        (thrown #(jf/create-content-file corrupt))
        (thrown #(jf/records corrupt)))
      (let [after (fds)]
        (println "RECORD descriptors" fd-dir "before" before "after" after)
        (check "no descriptor leak over 100 put/close/records cycles and 200 refused opens"
               (= before after) {:before before, :after after}))
      (delete! corrupt))))


;; every gate above ran with accounting on
(let [{:keys [allocs frees live double-free]} @ledger]
  (println "RECORD native buffers" (pr-str {:allocs allocs, :frees frees, :live (count live),
                                            :double-free double-free}))
  (check "no native-memory leak: every native buffer freed exactly once, on every path"
         (and (pos? allocs) (= allocs frees) (empty? live) (zero? double-free))
         {:allocs allocs, :frees frees, :live (count live), :double-free double-free}))


(System/exit (if (zero? @failures) 0 1))
