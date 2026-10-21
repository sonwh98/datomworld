;; Run under jolt -Srepro -M:jolt by probes/run.clj: fault injection over the
;; native acquisitions of dao.host.hash and dao.host.sign (Architect J1b
;; change 2). jolt.ffi/alloc, write-array and free are redefined to record
;; every pointer, to fail the Nth allocation or copy (a failing copy writes
;; first, then throws, so the secret is resident), or to fail the free of the
;; Nth allocation (it throws WITHOUT freeing: a leak the injection itself
;; causes). jolt_crypto_cleanse is wrapped to see that nothing allocates while
;; it runs, or to throw without zeroing.
;;
;; Each scenario checks, as far as the harness can observe:
;;   * every pointer allocated is freed exactly once (apart from an injected
;;     failing free);
;;   * memory that held seed bytes reads all zero when its free is reached
;;     (apart from an injected failing cleanse);
;;   * nothing is allocated during cleansing;
;;   * the call throws the injected failure, or (no injection) answers what
;;     the uninstrumented call answers.
;; Prints PASS/FAIL per scenario; exits nonzero on any FAIL.
(ns faults
  (:require [jolt.ffi :as ffi]))


(require '[dao.host.hash :as hash] '[dao.host.sign :as sign] '[dao.host.bytes :as bytes])

(def failures (atom 0))

(def orig-alloc ffi/alloc)
(def orig-free ffi/free)
(def orig-write ffi/write-array)
(def cleanse-var (ns-resolve 'dao.host.sign 'c-cleanse))
(def orig-cleanse @cleanse-var)


(defn- pattern
  [n seed]
  (bytes/from-vector (vec (map #(mod (+ seed (* 13 %)) 251) (range n)))))


(def seed (pattern 32 7))
(def seed-vec (bytes/to-vector seed))
(def msg (pattern 100 3))


(defn- run-scenario
  "Run (call) under the instrumentation `inject` describes; answer the trace."
  [{:keys [fail-alloc fail-write fail-free fail-cleanse]} call]
  (let [st (atom {:n-alloc 0, :n-write 0, :allocs [], :sizes {}, :index {},
                  :frees [], :contents {}, :at-free {}, :in-cleanse false,
                  :alloc-in-cleanse 0})
        alloc (fn [& args]
                (let [i (:n-alloc (swap! st update :n-alloc inc))]
                  (when (:in-cleanse @st) (swap! st update :alloc-in-cleanse inc))
                  (when (= i fail-alloc) (throw (ex-info "injected alloc failure" {:injected :alloc})))
                  (let [p (apply orig-alloc args)]
                    (swap! st #(-> % (update :allocs conj p)
                                   (assoc-in [:sizes p] (first args))
                                   (assoc-in [:index p] i)))
                    p)))
        write (fn [p arr & more]
                (let [i (:n-write (swap! st update :n-write inc))
                      r (apply orig-write p arr more)]
                  (swap! st assoc-in [:contents p] (bytes/to-vector arr))
                  (when (= i fail-write) (throw (ex-info "injected copy failure" {:injected :write})))
                  r))
        free (fn [p]
               (let [size (get-in @st [:sizes p])
                     snap (when (and size (pos? size)) (bytes/to-vector (ffi/read-array p (min size 64))))]
                 (swap! st #(-> % (update :frees conj p) (assoc-in [:at-free p] snap)))
                 (if (= fail-free (get-in @st [:index p]))
                   (throw (ex-info "injected free failure" {:injected :free}))
                   (orig-free p))))
        cleanse (fn [p n]
                  (swap! st assoc :in-cleanse true)
                  (try
                    (if fail-cleanse
                      (throw (ex-info "injected cleanse failure" {:injected :cleanse}))
                      (orig-cleanse p n))
                    (finally (swap! st assoc :in-cleanse false))))
        outcome (with-redefs-fn {#'ffi/alloc alloc, #'ffi/write-array write, #'ffi/free free,
                                 cleanse-var cleanse}
                  (fn []
                    (try {:value (call)}
                         (catch Exception e {:threw (or (:injected (ex-data e)) (ex-message e))}))))]
    (assoc @st :outcome outcome)))


(defn- check
  [label inject call expect]
  (let [{:keys [allocs frees contents at-free index alloc-in-cleanse outcome]} (run-scenario inject call)
        freed (frequencies frees)
        leaked-by-injection (when-let [k (:fail-free inject)] (some #(when (= k (index %)) %) allocs))
        each-freed-once (every? #(= 1 (freed %)) allocs)
        seeds (filter #(= seed-vec (contents %)) allocs)
        seeds-zero (every? #(every? zero? (at-free %)) seeds)
        outcome-ok (if (contains? expect :threw)
                     (= (:threw expect) (:threw outcome))
                     (= (:value expect) (:value outcome)))
        ok? (and outcome-ok each-freed-once (zero? alloc-in-cleanse)
                 (or (:fail-cleanse inject) seeds-zero))]
    (when-not ok? (swap! failures inc))
    (println (if ok? "PASS" "FAIL") label
             (pr-str {:allocs (count allocs), :frees (count frees),
                      :each-freed-once each-freed-once,
                      :seed-buffers (count seeds), :seed-zero-at-free seeds-zero,
                      :alloc-during-cleanse alloc-in-cleanse,
                      :leaked-by-injection (some? leaked-by-injection),
                      :outcome (if (contains? outcome :threw) outcome :value)}))))


(defn- v
  [bs]
  (when bs (bytes/to-vector bs)))


(let [sig (sign/sign seed msg)
      pair (sign/keypair-from-seed seed)
      pub (:public pair)
      good-sign {:value (v sig)}]
  ;; sign: seed, then message, then signature buffer
  (check "sign success" {} #(v (sign/sign seed msg)) good-sign)
  (check "sign: seed allocation fails" {:fail-alloc 1} #(sign/sign seed msg) {:threw :alloc})
  (check "sign: seed copy fails (after writing)" {:fail-write 1} #(sign/sign seed msg) {:threw :write})
  (check "sign: message allocation fails after seed acquisition" {:fail-alloc 2} #(sign/sign seed msg) {:threw :alloc})
  (check "sign: message copy fails after message allocation" {:fail-write 2} #(sign/sign seed msg) {:threw :write})
  (check "sign: signature allocation fails after message" {:fail-alloc 3} #(sign/sign seed msg) {:threw :alloc})
  (check "sign: the message free fails; seed and signature still released" {:fail-free 2}
         #(sign/sign seed msg) {:threw :free})
  (check "sign: the signature free fails; message and seed still released" {:fail-free 3}
         #(sign/sign seed msg) {:threw :free})
  (check "sign: the seed free fails; seed cleansed first, others released" {:fail-free 1}
         #(sign/sign seed msg) {:threw :free})
  (check "sign: cleansing throws; seed still freed" {:fail-cleanse true}
         #(sign/sign seed msg) {:threw :cleanse})
  ;; keypair-from-seed: seed, then public-key buffer
  (check "keypair success" {} #(v (:public (sign/keypair-from-seed seed))) {:value (v pub)})
  (check "keypair: seed copy fails (after writing)" {:fail-write 1} #(sign/keypair-from-seed seed) {:threw :write})
  (check "keypair: public allocation fails after seed acquisition" {:fail-alloc 2}
         #(sign/keypair-from-seed seed) {:threw :alloc})
  ;; verify: public key, message, signature
  (check "verify success" {} #(sign/verify pub msg sig) {:value true})
  (check "verify: message allocation fails" {:fail-alloc 2} #(sign/verify pub msg sig) {:threw :alloc})
  (check "verify: signature allocation fails" {:fail-alloc 3} #(sign/verify pub msg sig) {:threw :alloc})
  (check "verify: signature copy fails" {:fail-write 3} #(sign/verify pub msg sig) {:threw :write})
  (check "verify: the message free fails; others still released" {:fail-free 2}
         #(sign/verify pub msg sig) {:threw :free})
  ;; hash: input, then digest buffer
  (check "blake3 success" {} #(v (hash/blake3-bytes msg)) {:value (v (hash/blake3-bytes msg))})
  (check "blake3: digest allocation fails after input" {:fail-alloc 2} #(hash/blake3-bytes msg) {:threw :alloc})
  (check "sha256: input copy fails" {:fail-write 1} #(hash/sha256-bytes msg) {:threw :write})
  (check "sha256: the digest-buffer free fails; input still released" {:fail-free 2}
         #(hash/sha256-bytes msg) {:threw :free}))


(println "SUMMARY" (if (zero? @failures) "ok" (str @failures " failed")))
(System/exit (if (zero? @failures) 0 1))
