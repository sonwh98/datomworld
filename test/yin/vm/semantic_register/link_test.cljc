(ns yin.vm.semantic-register.link-test
  (:require [clojure.test :refer [deftest is testing]]
            [dao.stream :as stream]
            [yin.vm :as vm]
            [yin.vm.ast-walker :as walker]
            [yin.vm.semantic-register.walker-trace-test :as trace]
            [yin.vm.engine :as engine]
            [yin.vm.module :as module]
            [yin.vm.values :as values]
            [yin.vm.test-utils :as tu]
            [yin.vm.semantic-register :as sr]
            [yin.vm.semantic-register.code :as code]
            [yin.vm.semantic-register.linearize :as linearize]
            [yin.vm.semantic-register.corpus :refer [lit v lam app tail def!]]
            [yin.vm.semantic-register.parity-test :as parity]))


(defn refusal
  [f]
  (try (f) nil (catch #?(:cljd Object :clj Throwable :cljs :default) e (ex-data e))))


(defn pair
  []
  {:request (tu/new-stream 64) :response (tu/new-stream 64)})


(defn root
  [pair ast]
  (parity/register-machine ast {:modules (module/default-registry) :link-request (:request pair) :link-response (:response pair)
                                :secret-source (fn [origin] (str tu/secret "/" origin))}))


(defn body
  [ast exports]
  ;; Install-mechanics fixture; production dependency scanners are phase 5.
  {:status :ok :image {:value (:vector (linearize/project ast))}
   :manifest {:yin.module/name 'foo :yin.module/exports (set exports)} :obligations []})


(defn respond
  [pair response]
  (let [cursor (:dao.stream/cursor (stream/cursor (:request pair) stream/anchor-oldest))
        request (:dao.stream/value (stream/next (:request pair) cursor))]
    (stream/append! (:response pair) (assoc response :yin.link/id (:yin.link/id request)))
    request))


(defn drive
  [machine]
  (loop [machine machine rounds 0]
    (cond
      (not (vm/blocked? machine)) machine
      (= rounds 40) (throw (ex-info "Install did not complete" {:rounds rounds}))
      :else (recur (vm/run machine) (inc rounds)))))


(deftest require-installs-and-preserves-delivery
  (let [pair (pair)
        ast (app (v 'vector) (lit nil)
                 (parity/then (app (v 'require) (lit 'foo)) (app (v 'foo/f))) (lit 2))
        blocked (vm/run (assoc (root pair ast) :env {'vector vector}))
        entry (first (:wait-set blocked))
        payload (select-keys entry [:segment :pc :env :window :k :deliver])
        request (respond pair (body (parity/then (def! 'f (lam [] (lit 42)))
                                                 {:type :stream/next :source {:type :stream/cursor
                                                                              :source {:type :stream/make :buffer 4}}}) ['f]))
        installing (engine/check-wait-set blocked)
        install-entry (first (:wait-set installing))
        child (get-in installing [:installs 'foo :vm])
        child-entry (first (:wait-set child))
        _ (stream/append! (get (:resources child) (:stream-id child-entry)) :installed)
        done (drive (vm/run installing))
        module-entry (module/module-entry (:modules (:modules done)) 'foo)]
    (is (= :link-response (:reason entry)))
    (is (= {:deliver :rd :rd 5} (:deliver entry)))
    (is (= :install (:reason install-entry)))
    (is (= payload (select-keys install-entry (keys payload))))
    (is (= 'foo (:yin.link/name request)))
    (is (= :yin.semantic-register/code (:yin.link/format request)))
    (is (= [nil 42 2] (vm/value done)))
    (is (vm/halted? done))
    (is (empty? (:wait-set done)))
    (is (empty? (:installs done)))
    (is (= :yin.k/closure (get-in module-entry [:slice 'f :yin.k/tag])))))


(deftest tail-require
  (doseq [frame? [false true]]
    (let [pair (pair)
          call (tail (app (v 'require) (lit 'foo)))
          ast (if frame? (app (v 'vector) (lit nil) (app (lam [] call)) (lit 2)) call)
          blocked (vm/run (assoc (root pair ast) :env {'vector vector}))
          entry (first (:wait-set blocked))
          _ (respond pair (body (def! 'f (lam [] (lit 42))) ['f]))
          done (drive (vm/run blocked))]
      (is (= {:deliver :return} (:deliver entry)))
      (is (= (if frame? 1 0) (count (:k entry))))
      (is (not-any? #(contains? entry %) [:window :env :pc]))
      (is (= (if frame? [nil 'foo 2] 'foo) (vm/value done)))
      (is (not (contains? (:env done) engine/store-of-key)))
      (is (vm/halted? done)))))


(deftest install-refusals
  (doseq [[label response expected]
          [[:missing-export (body (lit 1) ['absent]) :export-missing]
           [:malformed (assoc (body (lit 1) []) :image {:value [[:bogus]]}) nil]
           [:v3-vector (assoc (body (lit 1) []) :image {:value [[:const 1] [:halt]]}) nil]
           [:link-refused {:status :refused :reason :absent} :absent]]]
    (testing label
      (let [pair (pair) blocked (vm/run (root pair (app (v 'require) (lit 'foo))))
            _ (respond pair response) data (refusal #(drive (vm/run blocked)))]
        (is (some? data))
        (when expected (is (= expected (:reason data))))
        (when (contains? #{:malformed :v3-vector} label)
          (is (= :loading (:phase data)))
          (is (= :refused (:status data))))))))


(deftest marker-checks
  (let [ast (lam '[x] (v 'x))
        machine (vm/run (parity/register-machine ast))
        closure (values/payload (vm/value machine))
        marker (module/lift-closure machine closure identity)
        lower #(module/lower-closure machine % identity)]
    (is (= {:format :yin.semantic-register/code :contract code/contract} (module/link-format machine)))
    (is (= :yin.semantic-register/code (:yin.k/format marker)))
    (is (= closure (values/payload (lower marker))))
    (is (= :marker-mismatch (:reason (refusal #(lower (assoc marker :yin.k/params '[y]))))))
    (is (= :marker-mismatch (:reason (refusal #(lower (assoc marker :yin.k/entry 0))))))
    (is (= :binding-mismatch (:reason (refusal #(lower (assoc marker :yin.k/format :yin.semantic/code))))))
    (is (= :origin-not-attached (:reason (refusal #(module/lower-closure (sr/create-vm) marker identity)))))
    (is (= :unrooted-body (:yin.k/kind (refusal #(module/lift-closure (sr/create-vm) closure identity)))))))


(deftest image-and-store-context
  (let [image (:vector (linearize/project (lam '[x] (v 'x))))
        machine (sr/attach-image (sr/create-vm) image code/contract)
        address (module/image-identity machine image)
        closure {:type :closure :params '[x] :entry 2 :segment (get (:code-aliases machine) address)
                 :env {'captured nil engine/store-of-key :module-store}}
        marker (module/lift-closure machine closure identity)
        child (module/spawn-module (assoc machine :window {0 :parent-only}) image {:modules (module/default-registry)
                                                                                   :origin :child :ancestry ['foo] :capability-secret tu/secret})]
    (is (module/image-holds? machine image address))
    (is (not (module/image-holds? machine [[:const 0 9] [:halt 0]] address)))
    (is (nil? (:control machine)))
    (is (= {} (:window machine)))
    (is (= :module-store (:yin.k/store-of marker)))
    (is (= {'captured nil} (:yin.k/env marker)))
    (is (= closure (values/payload (module/lower-closure machine marker identity))))
    (is (= {} (:window child)))
    (is (= :child (:origin child)))
    (is (= ['foo] (:ancestry child)))
    (is (= tu/secret (:capability-secret child)))
    (is (= :yin.semantic-register/code (:format (module/link-format child))))))


(deftest walker-link-parity
  (let [run (fn [register?]
              (let [pair (pair)
                    ast (parity/then (app (v 'require) (lit 'foo)) (app (v 'foo/f)))
                    opts {:modules (module/default-registry) :link-request (:request pair)
                          :link-response (:response pair)
                          :secret-source (fn [origin] (str tu/secret "/" origin))}
                    machine (if register? (parity/register-machine ast opts)
                                (walker/vm-load-rows (tu/create-vm opts) (vm/ast->semantic-bytecode ast) vm/ast-contract))
                    blocked (vm/run machine)
                    module-ast (def! 'f (lam [] (lit 42)))
                    response (cond-> (body module-ast ['f])
                               (not register?) (assoc :image {:value (vm/ast->semantic-bytecode module-ast)}))
                    request (respond pair response)
                    done (drive (vm/run blocked))
                    hit (app (v 'require) (lit 'foo))
                    next (if register? (sr/load-ast done (vm/ast->datoms hit) vm/ast-contract)
                             (walker/vm-load-rows done (vm/ast->semantic-bytecode hit) vm/ast-contract))]
                (is (= 42 (vm/value done)))
                (is (= 'foo (:yin.link/name request)))
                (is (vm/halted? done))
                (is (empty? (:wait-set done)))
                (is (empty? (:installs done)))
                [(parity/trace-normalize (vm/value done)) (vm/store done)
                 (trace/trace-machine next register?)]))
        expected-trace [[:value :host-fn] [:value 'foo] [:value 'foo] [:halt 'foo]]
        w (run false) r (run true)]
    (is (= w r))
    (is (= expected-trace (nth w 2)))
    (is (= expected-trace (nth r 2)))))
