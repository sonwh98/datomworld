(ns yin.vm.semantic.frame-test
  (:require [clojure.test :refer [deftest is testing]]
            #?(:cljd nil :clj [clojure.java.io :as io])
            [yin.vm :as vm]
            [yin.vm.engine :as engine]
            [yin.vm.module :as module]
            [yin.vm.values :as values]
            [yin.vm.semantic.linearize :as linearize]
            [yin.vm.semantic.analysis :as analysis]
            [yin.vm.semantic.frame :as frame]
            [yin.vm.semantic.corpus :as corpus]
            [yin.vm.semantic.parity-test :as parity]
            [yin.vm.ucf.rows-support :as s]
            [dao.stream :as stream]
            [dao.jing :as jing]
            [dao.jing.cbor :as cbor]))


(def task {:free-env {} :ffi-call-ids #{}})


(defn canonical
  "Reshape evaluator payload; values remain opaque to the pure validator."
  [m f pending?]
  (let [address (fn [seg] (get-in m [:code seg :address]))
        env (fn [e]
              (cond-> {:yin.k/bindings (engine/without-store-of e)}
                (some? (engine/env-store-of e)) (assoc :yin.k/store-of (engine/env-store-of e))))
        act? (contains? f :pc)]
    (cond-> {:yin.k/state (if act? :act :tail) :yin.k/segment (address (:segment f))
             :yin.k/deliver (if act? {:yin.k/deliver :rd :yin.k/rd (get-in f [:deliver :rd])}
                                {:yin.k/deliver :return})
             :yin.k/k (mapv (fn [k]
                              {:yin.k/frame-type :return :yin.k/segment (address (:segment k))
                               :yin.k/pc (:pc k) :yin.k/env (env (:env k))
                               :yin.k/window (:window k) :yin.k/rd (:rd k)}) (or (:k f) []))}
      act? (assoc :yin.k/pc (:pc f) :yin.k/env (env (:env f)) :yin.k/window (:window f))
      (not act?) (assoc :yin.k/site (:site f))
      pending? (assoc :yin.k/pending {:yin.k/reason (:reason f)}))))


(defn machine-code
  [m]
  (into {} (map (fn [[_ image]] [(:address image) (:vector image)])) (:code m)))


(defn- cat-of
  [p q]
  (s/app (s/v 'cat) p q))


(defn- with-cat
  [ast]
  (s/let1 'cat (s/lam ['p 'q] (s/app (s/v 'conj) (s/v 'p) (s/v 'q))) ast))


(defn- nest
  [depth op]
  (if (zero? depth) op
      (cat-of (s/lit [depth]) (s/app (s/lam ['x] (nest (dec depth) op)) (s/lit depth)))))


(def wrappers
  (mapv (fn [[label wrap]] [label (comp with-cat wrap)])
        [[:bare identity]
         [:operand #(cat-of (s/lit [:operand]) %)]
         [:depth-3 #(nest 3 %)]
         [:captured #(s/let1 'k (s/lit :captured)
                             (s/let1 'f (s/lam ['y] (cat-of (cat-of (cat-of (s/lit []) (s/v 'k)) (s/v 'y)) %))
                                     (s/app (s/v 'f) (s/lit :arg))))]]))


(defn- rows-program
  [n]
  (let [read (s/next-of (s/v 'c)) capture {:type :vm/current-continuation}]
    (case n
      1 (s/let1 'a read (cat-of (cat-of (s/lit []) (s/v 'a)) read))
      2 {:type :stream/put :target (s/v 'w) :val (s/lit "v")}
      5 (cat-of (s/lit [:ordinary]) (s/app (s/v 'stream/next!) (s/v 'c)))
      6 (s/app (s/lam ['u] (assoc (s/app (s/v 'stream/next!) (s/v 'c)) :tail? true)) (s/lit 0))
      13 {:type :vm/park}
      14 (s/let1 'k capture (cat-of (cat-of (s/lit []) (s/v 'k)) read))
      15 (s/let1 'k capture {:type :if :test (s/app (s/v '=) (s/v 'k) (s/lit :second))
                             :consequent (s/lit :done) :alternate (s/let1 'r read (s/app (s/v 'k) (s/lit :second)))})
      16 (s/let1 'k1 capture (s/let1 'k2 capture
                                     (cat-of (cat-of (cat-of (s/lit []) (s/v 'k1)) (s/v 'k2)) read)))
      18 (s/app (s/lam ['p] {:type :vm/park}) (s/lit 1))
      19 (s/lit 42)
      21 (s/let1 'f (s/lam [] (s/v 'x)) (s/app (s/lam ['x] (s/app (s/v 'f) read)) (s/lit 1)))
      22 (s/app (s/lam ['p 'q] (cat-of (cat-of (s/lit []) (s/v 'p)) (s/v 'q))) (s/lit nil) read))))


(defn- attach-reader
  [m ring]
  (let [[ref m] (engine/attach-resource m ring)
        [id m] (engine/gensym m "cursor")]
    (engine/handle-cursor m {:stream ref} id)))


(defn reference-machines
  []
  (into
    (mapv (fn [[n [label wrap]]]
            (let [ring (s/ring 4)
                  w (s/one-slot-stream (str "frame-" n))
                  _ (stream/append! w :warmed)
                  env (if (= n 21) {'x 99 'y 7} {})
                  m (parity/register-machine (wrap (rows-program n)) {:env env :modules (module/register-stream-module (module/default-registry))})
                  [cr m] (attach-reader m ring)
                  [wr m] (engine/attach-resource m w)
                  m (assoc m :store {'c cr 'w wr})
                  m (if (= n 18)
                      (let [[second-cursor m] (attach-reader m ring)
                            [seg image] (first (:code m))
                            closure (first (filter #(= :closure (first %)) (:vector image)))
                            clo (values/closure (:owner m) {:type :closure :params (nth closure 2)
                                                            :entry (nth closure 3) :segment seg
                                                            :env {engine/store-of-key 'host.mod}})]
                        (assoc m :store (assoc (:store m) 'mod clo)
                               :module-stores {'host.mod {'c cr 'd second-cursor}})) m)
                  blocked (vm/run m)
                  blocked (if (= n 1) (do (stream/append! ring "A") (vm/run blocked)) blocked)]
              {:row n :wrapper label :machine blocked :task (assoc task :free-env env)}))
          (mapcat (fn [n] (mapv #(vector n %) wrappers)) [1 2 5 6 13 14 15 16 18 19 21 22]))
    (let [ring (s/ring 4)
          m (parity/register-machine (assoc (s/app (s/v 'stream/next!) (s/v 'c)) :tail? true)
                                     {:modules (module/register-stream-module (module/default-registry))})
          [cr m] (attach-reader m ring)]
      [{:row 20 :wrapper :unwrapped :machine (vm/run (assoc m :store {'c cr})) :task task}])))


(defn reference-frames
  "Every wait, parked payload, and capture reachable through their values."
  [{:keys [machine task] :as fixture}]
  (let [out (atom []) code-map (machine-code machine)]
    (letfn [(record
              [f context t]
              (let [cf (canonical machine f (= :wait context))]
                (swap! out conj {:frame cf :code code-map :context context :task t :fixture (select-keys fixture [:row :wrapper])})
                (walk (:env f)) (walk (:window f)) (walk (:k f))))
            (walk
              [x]
              (cond
                (values/continuation? x) (record (values/payload x) :captured task)
                (values/closure? x) (walk (:env (values/payload x)))
                (map? x) (doseq [[k v] x] (walk k) (walk v))
                (coll? x) (doseq [v x] (walk v))
                :else nil))]
      (doseq [f (:wait-set machine)] (record f :wait task))
      (doseq [[p f] (:parked machine)] (record f :parked (assoc task :parked-key p)))
      (walk (:value machine)) (walk (:store machine))
      @out)))


(def worked-vector (:vector (linearize/project (second (first (filter #(= :worked-example (first %)) corpus/programs))))))
(def worked-address (jing/segment-key worked-vector))
(def worked-code {worked-address worked-vector})


(def worked-frame
  {:yin.k/state :act :yin.k/segment worked-address :yin.k/pc 4
   :yin.k/env {:yin.k/bindings {}} :yin.k/window {1 :f}
   :yin.k/deliver {:yin.k/deliver :rd :yin.k/rd 2} :yin.k/k []
   :yin.k/pending {:yin.k/reason :next :yin.k/cursor-cell :test/cursor}})


(deftest contract-is-pure
  (is (nil? (frame/defect worked-frame worked-code :wait task)))
  #?(:cljd nil
     :clj (let [source (slurp (io/file "src/cljc/yin/vm/semantic/frame.cljc"))
                ns-form (first (re-seq #"(?s)^.*?\n\n" source))]
            (is (not (re-find #"yin.vm.engine|yin.vm.semantic\s+:|yin.vm.debruijn.(stack|register)" ns-form))))))


(deftest every-reference-frame-validates
  (let [fixtures (reference-machines)]
    (is (= 49 (count fixtures)))
    (doseq [fixture fixtures]
      (testing (pr-str (select-keys fixture [:row :wrapper]))
        (let [frames (reference-frames fixture)]
          (if (= 19 (:row fixture))
            (is (vm/halted? (:machine fixture)))
            (is (seq frames)))
          (doseq [{:keys [frame code context task]} frames]
            (is (nil? (frame/defect frame code context task)))))))))


(defn kind
  [f c context t]
  (:yin.k/kind (frame/defect f c context t)))


(deftest worked-example-saved
  (is (= #{1} (analysis/saved (analysis/analyze worked-vector) 4 2)))
  (is (nil? (kind worked-frame worked-code :wait task)))
  (is (= :window-self (kind (assoc worked-frame :yin.k/window {1 :f 2 :x}) worked-code :wait task)))
  (is (= :window-missing (kind (assoc worked-frame :yin.k/window {}) worked-code :wait task)))
  (is (= :window-extra (kind (assoc worked-frame :yin.k/window {1 :f 3 :x}) worked-code :wait task))))


(deftest nil-valued-register-is-a-member
  (let [fixture (first (filter #(and (= 22 (:row %)) (= :bare (:wrapper %))) (reference-machines)))
        {:keys [frame code context task]} (first (reference-frames fixture))
        nil-key (first (keep (fn [[k v]] (when (nil? v) k)) (:yin.k/window frame)))]
    (is (some? nil-key))
    (is (nil? (kind frame code context task)))
    (is (= :window-missing (kind (update frame :yin.k/window dissoc nil-key) code context task)))))


(deftest free-env-mismatch
  (let [fixture (first (filter #(and (= 21 (:row %)) (= :bare (:wrapper %))) (reference-machines)))
        {:keys [frame code context task]} (first (reference-frames fixture))]
    (is (= 1 (get-in frame [:yin.k/env :yin.k/bindings 'x])))
    (is (nil? (kind frame code context task)))
    (doseq [env [{'x 99 'y 8} {'x 99}]]
      (is (= :free-env-mismatch (kind frame code context (assoc task :free-env env)))))
    (is (= :free-env-mismatch (kind (update-in frame [:yin.k/env :yin.k/bindings] dissoc 'x) code context task)))
    (is (nil? (kind (assoc-in frame [:yin.k/env :yin.k/bindings 'x] 98) code context task)))
    (is (= :reserved-name (:yin.k/kind (frame/task-defect {:free-env {'yin/def 1}}))))))


(deftest free-env-compares-values-by-content
  (doseq [[context op] [[:wait :stream-next] [:captured :current-continuation] [:parked :park]]]
    (testing (name context)
      (let [v (if (= :wait context)
                [[:closure 0 [] 2] [:halt 0] [:const 0 nil] [op 0 0] [:return 0]]
                [[:closure 0 [] 2] [:halt 0] [op 0] [:return 0]])
            address (jing/segment-key v)
            code {address v}
            f (cond-> {:yin.k/state :act :yin.k/segment address
                       :yin.k/pc (if (= :wait context) 4 3)
                       :yin.k/env {:yin.k/bindings {'x (* 1.0 ##NaN)}} :yin.k/window {}
                       :yin.k/deliver {:yin.k/deliver :rd :yin.k/rd 0} :yin.k/k []}
                (= :wait context) (assoc :yin.k/pending {:yin.k/reason :next}))
            t (assoc task :free-env {'x ##NaN})]
        (is (nil? (frame/defect f code context t)) "fresh and literal NaNs have the same content")
        (doseq [env [{'x 1.0} {'y ##NaN}]]
          (is (= :free-env-mismatch (kind f code context (assoc t :free-env env)))))
        ;; JS has one zero: 0.0 and -0.0 are the same Number there, so the
        ;; signed-zero distinction holds on the JVM and Dart only.
        #?@(:cljs []
            :default [(is (= :free-env-mismatch
                             (kind (assoc-in f [:yin.k/env :yin.k/bindings 'x] 0.0)
                                   code context (assoc t :free-env {'x -0.0}))))
                      (is (not (cbor/content= 0.0 -0.0)))])
        (let [nested {'x {:n [(* 1.0 ##NaN)]}}]
          (is (nil? (frame/defect (assoc-in f [:yin.k/env :yin.k/bindings] nested)
                                  code context (assoc t :free-env {'x {:n [##NaN]}})))))
        (doseq [opaque [(fn [] nil)
                        {:type :stream-ref :id :s :host (fn [] nil)}
                        {:type :cursor-ref :id :c :host (fn [] nil)}
                        {:type :cell-ref :id :v :host (fn [] nil)}]]
          (is (nil? (frame/defect (assoc-in f [:yin.k/env :yin.k/bindings 'x] opaque)
                                  code context (assoc t :free-env {'x opaque})))))))))


(deftest first-defect-is-structural
  (is (= :k-shape (kind (assoc worked-frame :yin.k/k [nil] :yin.k/window nil) worked-code :wait task))))


(deftest each-rule-refuses-its-tamper
  (let [ret {:yin.k/frame-type :return :yin.k/segment worked-address :yin.k/pc 4
             :yin.k/env {:yin.k/bindings {}} :yin.k/window {1 :f} :yin.k/rd 2}
        with-k (assoc worked-frame :yin.k/k [ret])
        tail-v [[:var 1 'f] [:call 0 1 [] true] [:halt 0]]
        tail-a (jing/segment-key tail-v) tail-c {tail-a tail-v}
        tail-f {:yin.k/state :tail :yin.k/segment tail-a :yin.k/site 1
                :yin.k/k [] :yin.k/deliver {:yin.k/deliver :return} :yin.k/pending {:yin.k/reason :next :yin.k/cursor-cell :test/cursor}}
        boundary-v [[:closure 0 [] 2] [:halt 0] [:current-continuation 0] [:return 0]]
        boundary-a (jing/segment-key boundary-v)
        rows [["S1" :frame-shape (assoc worked-frame :extra true) worked-code :wait task]
              ["S2" :k-shape (assoc worked-frame :yin.k/k [nil]) worked-code :wait task]
              ["S3" :window-representation (assoc worked-frame :yin.k/window {-1 nil}) worked-code :wait task]
              ["S4" :env-shape (assoc worked-frame :yin.k/env {}) worked-code :wait task]
              ["C1" :segment worked-frame {} :wait task]
              ["C2-kind" :pc-kind (assoc worked-frame :yin.k/pc 4.5) worked-code :wait task]
              ["C2-bounds" :pc-bounds (assoc worked-frame :yin.k/pc 99) worked-code :wait task]
              ["C3" :pc-body (assoc worked-frame :yin.k/segment boundary-a :yin.k/pc 2) {boundary-a boundary-v} :wait task]
              ["A1" :site (assoc worked-frame :yin.k/pc 3) worked-code :wait task]
              ["A2" :destination (assoc-in worked-frame [:yin.k/deliver :yin.k/rd] 0) worked-code :wait task]
              ["A3" :window-scope (assoc-in worked-frame [:yin.k/window 99] nil) worked-code :wait task]
              ["A4" :window-self (assoc-in worked-frame [:yin.k/window 2] nil) worked-code :wait task]
              ["A5" :window-extra (assoc-in worked-frame [:yin.k/window 3] nil) worked-code :wait task]
              ["A6" :window-missing (assoc worked-frame :yin.k/window {}) worked-code :wait task]
              ["T1" :site (assoc tail-f :yin.k/site 0) tail-c :wait task]
              ["K1" :return-site (assoc-in with-k [:yin.k/k 0 :yin.k/pc] 3) worked-code :wait task]
              ["K2" :return-destination (assoc-in with-k [:yin.k/k 0 :yin.k/rd] 0) worked-code :wait task]
              ["K3" :return-window-missing (assoc-in with-k [:yin.k/k 0 :yin.k/window] {}) worked-code :wait task]
              ["E1" :reserved-name (assoc-in worked-frame [:yin.k/env :yin.k/bindings 'yin/def] nil) worked-code :wait task]
              ["E2" :free-env-mismatch (assoc-in worked-frame [:yin.k/env :yin.k/bindings 'x] nil) worked-code :wait task]
              ["P1" :pending-presence (dissoc worked-frame :yin.k/pending) worked-code :wait task]
              ["P2" :reason-mismatch (assoc-in worked-frame [:yin.k/pending :yin.k/reason] :bogus) worked-code :wait task]
              ["P3" :captured-site (dissoc worked-frame :yin.k/pending) worked-code :captured task]
              ["P4" :parked-site (dissoc tail-f :yin.k/pending) tail-c :parked task]]]
    (is (= 24 (count rows)))
    (doseq [[rule expected f c ctx t] rows]
      (testing rule
        (let [d (frame/defect f c ctx t)]
          (is (= expected (:yin.k/kind d))) (is (vector? (:yin.k/path d))))))
    (is (= :free-env-shape (:yin.k/kind (frame/task-defect {:free-env {1 nil}}))))))


(deftest rule-boundaries-refuse-their-tamper
  ;; Supplements the prescribed table at each rule's other edge.
  (let [resume (fn [op]
                 (let [v [[:closure 0 [] 2] [:halt 0] [op 0] [:return 0]] a (jing/segment-key v)]
                   [(assoc worked-frame :yin.k/segment a :yin.k/pc 3 :yin.k/window {}
                           :yin.k/deliver {:yin.k/deliver :rd :yin.k/rd 0}) {a v}]))
        [park-f park-c] (resume :park)
        [cc-f cc-c] (resume :current-continuation)]
    (is (nil? (kind (dissoc park-f :yin.k/pending) park-c :parked task)))
    (is (nil? (kind (dissoc cc-f :yin.k/pending) cc-c :captured task)))
    (doseq [[rule expected f c] [["S2 map K" :k-shape (assoc worked-frame :yin.k/k {}) worked-code]
                                 ["S2 nil K" :k-shape (assoc worked-frame :yin.k/k nil) worked-code]
                                 ["S4 nil store-of" :env-shape (assoc-in worked-frame [:yin.k/env :yin.k/store-of] nil) worked-code]
                                 ["C2 pc 0" :pc-bounds (assoc worked-frame :yin.k/pc 0) worked-code]
                                 ["A5 before A6" :window-extra (assoc worked-frame :yin.k/window {3 :x}) worked-code]
                                 ["P2 park" :reason-mismatch park-f park-c]
                                 ["P2 current-continuation" :reason-mismatch cc-f cc-c]]]
      (testing rule
        (is (= expected (kind f c :wait task)))))))


(deftest tail-bookkeeping-and-delivery
  (let [fixture (last (reference-machines)) {:keys [frame code]} (first (reference-frames fixture))
        f (dissoc frame :yin.k/pending)]
    (is (= [] (:yin.k/k frame)))
    (is (nil? (kind f code :parked (assoc task :parked-key :call :ffi-call-ids #{:call}))))
    (is (= :parked-site (kind f code :parked (assoc task :parked-key :other :ffi-call-ids #{:call}))))
    (is (= :deliver (kind (assoc frame :yin.k/deliver {:yin.k/deliver :rd :yin.k/rd 0}) code :wait task)))))


(deftest integral-float-is-not-a-pc
  (is (= :pc-kind (kind (assoc worked-frame :yin.k/pc (cbor/float64 4)) worked-code :wait task)))
  (is (= :window-representation
         (kind (assoc worked-frame :yin.k/window {(cbor/float64 1) nil}) worked-code :wait task))))


(deftest link-pendings-require-a-boolean-return-contract
  (doseq [reason [:link-request :link-response :install]]
    (let [f (assoc worked-frame :yin.k/pending {:yin.k/reason reason :yin.k/try? false})]
      (doseq [flag [false true]]
        (is (nil? (frame/defect (assoc-in f [:yin.k/pending :yin.k/try?] flag)
                                worked-code :wait task))))
      (doseq [flag [nil :true 1 "true"]]
        (is (= {:yin.k/kind :pending-try :yin.k/path [:yin.k/pending :yin.k/try?]}
               (frame/defect (assoc-in f [:yin.k/pending :yin.k/try?] flag)
                             worked-code :wait task))))
      (is (= :pending-try
             (:yin.k/kind (frame/defect (update f :yin.k/pending dissoc :yin.k/try?)
                                        worked-code :wait task)))))))
