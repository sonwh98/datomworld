(ns yin.vm.linker.dht-end-to-end-test
  "Linker over DHT slice L5, in process (docs/design/yin.vm.linker.dht.md
   section 12, L5): the end-to-end scenario of yin.repl.dht-process-test
   over the dao.jing.dht test mesh seam, so that it runs on the JVM, Node
   and Dart alike.

   A publishing shell holding an Ed25519 key defines a function and a
   module-level value, and publishes `my.lib` (the function) and
   `my.store` (an export reading the value) by name.  A reader shell
   handed the publisher's index manifest (`--dht-manifest`) and its
   principal (`--dht-principal`) requires each module by name, waits
   through pending, and evaluates the export, on each of the four VMs.
   The name resolves only through a verified signature: a reader that
   does not declare the principal, and one handed an index whose proof
   another key made, are each refused `:absent` with the fold's
   diagnostic.  Time advances only by the readings each step is handed."
  (:require #?@(:cljd [["dart:io" :as dart-io]])
            [clojure.string :as str]
            [clojure.test :refer [deftest is testing]]
            [dao.datom :as datom]
            [dao.jing :as jing]
            [dao.jing.dht :as jing.dht]
            [dao.jing.dht.mesh :as mesh]
            [dao.space.dht :as dht]
            [dao.space.dht-test :as dht-test]
            [dao.space.index :as index]
            [yin.repl :as repl]
            [yin.repl.dht :as repl.dht]
            [yin.repl.store :as store]
            [yin.vm.linker.dht :as ld]
            [yin.vm.linker.publish :as publish]
            [yin.vm.linker.sign :as sign]))


;; =============================================================================
;; Host helpers
;; =============================================================================

(defn- temp-dir
  []
  (str "target/test-linker-e2e-" (random-uuid)))


(defn- cleanup-dir!
  [dir]
  #?(:cljd (try (.deleteSync (dart-io/Directory. dir) .recursive true)
                (catch Object _ nil))
     :clj (let [f (java.io.File. ^String dir)]
            (when (.isDirectory f)
              (doseq [child (.listFiles f)]
                (.delete ^java.io.File child))
              (.delete f)))
     :cljs (try (.rmSync (js/require "fs") dir #js {:recursive true :force
                                                    true})
                (catch :default _ nil))))


;; =============================================================================
;; A mesh world of shells and plain publishing peers
;; =============================================================================

(defn- peer-node
  [net port]
  (let [c (mesh/join! net port {::jing.dht/publish? true
                                ::jing.dht/max-inbound-bytes (* 64 1024 1024)})]
    {:composition c :state (jing.dht/state c)}))


(defn- step-peers
  [peers now]
  (into {}
        (map (fn [[port {:keys [composition state]}]]
               (mesh/tick! composition now)
               [port {:composition composition
                      :state (jing.dht/step state 256)}]))
        peers))


(defn- shell-at
  "A shell over a dht store at `dir` on mesh `port` with contacts
   `peer-ports`: publishing when `key` is given (`--dht-publish
   --dht-key`), hydrating `manifest` first when given (`--dht-manifest`),
   declaring `principals` (`--dht-principal`)."
  [net dir port peer-ports {:keys [key manifest principals]}]
  (repl/create-state
    {:index-store-spec {:type :dht
                        :dir dir
                        :peers (mapv (fn [p] {:host "127.0.0.1" :port p})
                                     peer-ports)
                        :publish? (some? key)
                        :bind-host "127.0.0.1"
                        :bind-port 0
                        :bind! (mesh/seam net port)
                        :max-inbound-bytes repl.dht/default-max-inbound-bytes
                        :manifest manifest}
     :dht-key key
     :principals (vec principals)}))


(defn- world
  [net peer-ports]
  {:net net
   :peers (into {} (map (fn [p] [p (peer-node net p)])) peer-ports)
   :shells {}
   :now 0
   :lines []
   :events []})


(defn- step-world
  "One reading: every shell's node (`yin.repl.dht/step`, then the
   re-check its load events call for, as the host ticker does), then
   every peer."
  [w]
  (let [now (:now w)
        stepped (map (fn [[k s]]
                       (let [[s lines events] (repl.dht/step s now)
                             [s text] (repl/recheck-on-load-events s events)]
                         [k s (cond-> lines text (conj text))
                          (map #(assoc % :who k) events)]))
                     (:shells w))]
    (-> w
        (assoc :shells (into {} (map (fn [[k s]] [k s])) stepped)
               :peers (step-peers (:peers w) now)
               :now (+ now 10))
        (update :lines into (mapcat #(nth % 2)) stepped)
        (update :events into (mapcat #(nth % 3)) stepped))))


(defn- run-world
  "Step `w` until `(done? w)`; `{:failure cause}` under `:failure` when
   `limit` readings pass first."
  [w limit cause done?]
  (loop [w w]
    (cond
      (done? w) w
      (> (:now w) limit) (assoc w :failure (str cause " within " limit
                                                " readings"))
      :else (recur (step-world w)))))


(defn- eval-at
  "Evaluate `line` at shell `k`'s prompt: `[w text]`."
  [w k line]
  (let [[s text] (repl/eval-input (get-in w [:shells k]) line)]
    [(assoc-in w [:shells k] s) text]))


(defn- head-of
  [w k]
  (get-in w [:shells k :indexer :manifest-address]))


(defn- published
  [w k manifest]
  (some #(when (and (= k (:who %)) (= :published (::dht/event %))
                    (= manifest (:manifest %)))
           %)
        (:events w)))


(defn- last-response
  [w k]
  (dissoc (peek (mesh/values (get-in w [:shells k :link-pair :responses])))
          :yin.link/id))


(defn- close-all!
  [w]
  (doseq [[_ s] (:shells w)] (store/close! (:index-store s)))
  nil)


;; =============================================================================
;; The scenario
;; =============================================================================

(def ^:private publisher-lines
  ["(def f (fn [x] (+ x 4200)))"
   "(def base 7)"
   "(def g (fn [] (+ base 1)))"
   "(require (quote yin.link))"
   "(yin.link/publish (quote my.lib) (quote [f]))"
   "(yin.link/publish (quote my.store) (quote [g]))"])


(defn- publish-world
  "Peers at 2 and 3 and the publisher `:a` at 1, which types
   `publisher-lines` and settles: `[w manifest]`, its HEAD index manifest
   reported acknowledged."
  [net dir key]
  (let [w (-> (world net [2 3])
              (assoc-in [:shells :a] (shell-at net dir 1 [2 3] {:key key})))
        w (reduce (fn [w line] (first (eval-at w :a line))) w publisher-lines)
        m (head-of w :a)
        w (run-world w 60000 "the publisher's HEAD was not reported"
                     #(published % :a m))]
    (is (nil? (:failure w)) (:failure w))
    (is (= :acknowledged (:result (published w :a m)))
        (pr-str (dissoc (published w :a m) :failed)))
    [w m]))


(defn- hydrate
  "Add reader `k` at mesh `port`, handed `manifest`, and run until it
   admits evaluation."
  [w k dir port manifest principals]
  (let [w (assoc-in w [:shells k]
                    (shell-at (:net w) dir port [2 3 1]
                              {:manifest manifest :principals principals}))
        w (run-world w (+ (:now w) 60000) (str (name k) " did not hydrate")
                     #(repl.dht/admitting? (get-in % [:shells k])))]
    (is (nil? (:failure w)) (:failure w))
    (is (nil? (repl.dht/refusal (get-in w [:shells k]))))
    w))


(defn- require-at
  "Require `module` at shell `k` and run until the run ends: `[w text]`,
   the require's own text."
  [w k module]
  (let [[w text] (eval-at w k (str "(require (quote " module "))"))
        w (run-world w (+ (:now w) 120000) (str "the require of " module
                                                " never ended")
                     #(nil? (get-in % [:shells k :pending-run])))]
    (is (nil? (:failure w)) (:failure w))
    [w text]))


(def ^:private vm-types
  [:ast-walker :semantic :stack :register])


(deftest a-reader-requires-a-published-module-by-name-on-all-four-vms
  (let [[adir bdir] [(temp-dir) (temp-dir)]
        net (mesh/mesh)
        key (sign/generate)
        principal (sign/principal (:public key))]
    (try
      (let [[w m] (publish-world net adir key)
            w (hydrate w :b bdir 4 m [(:public key)])
            names (ld/names (get-in w [:shells :b :dht])
                            (ld/authority {:principals [(:public key)]}))]
        (testing "the reader resolves both names, each signed by the publisher"
          (doseq [n '[my.lib my.store]]
            (is (= [principal] (get-in names [:names n :yin.link/provenance
                                              :yin.module/asserted-by]))
                (pr-str (get-in names [:names n])))))
        (reduce
          (fn [w vm-type]
            (let [[w _] (eval-at w :b (str "(vm " vm-type ")"))
                  [w text] (require-at w :b 'my.lib)
                  linked (get-in w [:shells :b :last-value])
                  lib-response (last-response w :b)
                  [w answer] (eval-at w :b "(my.lib/f 1)")
                  [w _] (require-at w :b 'my.store)
                  store-response (last-response w :b)
                  [_ value] (eval-at w :b "(my.store/g)")]
              (testing (name vm-type)
                (when (= :ast-walker vm-type)
                  (is (str/includes? text "pending")
                      "the first require waits on the closure load"))
                (is (= 'my.lib linked) (pr-str lib-response))
                (is (= "4201" answer))
                (testing "the store corpus"
                  (when (= :ast-walker vm-type)
                    (is (= {:status :refused :reason :undeclared-free :name
                            'base}
                           (select-keys store-response [:status :reason :name]))
                        (str "the linker's reason, naming the read: "
                             (pr-str store-response))))
                  (when-not (= :ast-walker vm-type)
                    (is (= :ok (:status store-response)) (pr-str
                                                           store-response))
                    (is (= "8" value))))
                nil)
              w))
          w
          vm-types)
        (close-all! w))
      (finally
        (run! cleanup-dir! [adir bdir])))))


(deftest without-the-principal-the-require-is-absent-undeclared
  (let [[adir cdir] [(temp-dir) (temp-dir)]
        net (mesh/mesh)
        key (sign/generate)]
    (try
      (let [[w m] (publish-world net adir key)
            w (hydrate w :c cdir 5 m [])
            [w _] (require-at w :c 'my.lib)
            body (last-response w :c)]
        (is (= {:status :refused :reason :absent :name 'my.lib}
               (select-keys body [:status :reason :name]))
            (pr-str body))
        (is (= [[:undeclared-principal (sign/principal (:public key))]]
               (mapv (juxt :reason :principal) (:diagnostics body))))
        (is (nil? (get-in w [:shells :c :last-value])))
        (close-all! w))
      (finally
        (run! cleanup-dir! [adir cdir])))))


(deftest
  a-dangling-retraction-is-one-global-diagnostic-at-the-prompt
  (let
    [[adir edir] [(temp-dir) (temp-dir)]
     net (mesh/mesh)
     key (sign/generate)
     principal (sign/principal (:public key))
     ;; an assertion signed but never published: no snapshot holds it
     unpublished (:envelope
                   (publish/assertion
                     key {:name 'my.lib
                          :manifest
                          (jing/segment-key "elsewhere")
                          :seq 90}))
     of (jing/segment-key unpublished)]
    (try
      (let [[w _] (publish-world net adir key)
            node (get-in w [:shells :a :dht])
            dangling (let [r (publish/retraction key {:of of :seq 91})]
                       (dht-test/publish-datoms!
                         node
                         [{:db/id datom/first-user-id
                           :yin.module/envelope (:envelope r)
                           :yin.module/proof (:proof r)}]))
            w (run-world w (+ (:now w) 60000)
                         "the retraction index was not reported"
                         #(published % :a dangling))
            w (hydrate w :e edir 7 dangling [(:public key)])
            [w _] (eval-at w :e "(require (quote yin.link))")
            [w _] (eval-at w :e "(yin.link/names)")
            env (get-in w [:shells :e :last-value])]
        (is (nil? (:failure w)) (:failure w))
        (testing (str
                   "(yin.link/names) carries it once, globally, w"
                   "ith principal and id")
          (is (= [[:dangling-retraction principal of]]
                 (mapv (juxt :reason :principal :of) (:global-diagnostics env)))
              (pr-str env)))
        (testing "and in no per-name diagnostic"
          (is (not-any? #(= :dangling-retraction (:reason %)) (:diagnostics
                                                                env))
              (pr-str (:diagnostics env))))
        (testing (str
                   "a require of the name it would have retracted"
                   " is absent, no diagnostic")
          (let [[w _] (require-at w :e 'my.lib)]
            (is (= {:status :refused :reason :absent :name 'my.lib :diagnostics
                    []}
                   (last-response w :e)))
            (close-all! w))))
      (finally
        (run! cleanup-dir! [adir edir])))))


(deftest
  a-retraction-of-a-retraction-is-global-at-the-prompt
  (let
    [[adir edir] [(temp-dir) (temp-dir)]
     net (mesh/mesh)
     key (sign/generate)
     principal (sign/principal (:public key))
     unpublished (:envelope
                   (publish/assertion
                     key {:name 'my.lib
                          :manifest
                          (jing/segment-key "elsewhere")
                          :seq 90}))
     r1 (publish/retraction key {:of (jing/segment-key unpublished) :seq 91})
     r1-id (jing/segment-key (:envelope r1))
     ;; its target is a retraction, which carries no name
     r2 (publish/retraction key {:of r1-id :seq 92})]
    (try
      (let [[w _] (publish-world net adir key)
            index (dht-test/publish-datoms!
                    (get-in w [:shells :a :dht])
                    (vec (map-indexed (fn [i r]
                                        {:db/id (+ datom/first-user-id i)
                                         :yin.module/envelope (:envelope r)
                                         :yin.module/proof (:proof r)})
                                      [r1 r2])))
            w (run-world w (+ (:now w) 60000)
                         "the retraction index was not reported"
                         #(published % :a index))
            w (hydrate w :e edir 8 index [(:public key)])
            [w _] (eval-at w :e "(require (quote yin.link))")
            [w _] (eval-at w :e "(yin.link/names)")
            env (get-in w [:shells :e :last-value])]
        (is (nil? (:failure w)) (:failure w))
        (testing "(yin.link/names): both retractions are global, each once"
          (is (= #{[:dangling-retraction principal (jing/segment-key
                                                     unpublished)]
                   [:dangling-retraction principal r1-id]}
                 (set (mapv (juxt :reason :principal :of) (:global-diagnostics
                                                            env))))
              (pr-str env))
          (is (= 2 (count (:global-diagnostics env)))))
        (testing "and neither is a per-name diagnostic"
          (is (not-any? #(= :dangling-retraction (:reason %)) (:diagnostics
                                                                env))
              (pr-str (:diagnostics env))))
        (close-all! w))
      (finally
        (run! cleanup-dir! [adir edir])))))


(deftest an-empty-export-list-publishes-the-no-op-module-at-the-prompt
  (let [[adir bdir] [(temp-dir) (temp-dir)]
        net (mesh/mesh)
        key (sign/generate)]
    (try
      (let [[w _] (publish-world net adir key)
            [w text] (eval-at w :a
                              "(yin.link/publish (quote empty.lib) (quote []))")
            res (get-in w [:shells :a :last-value])
            m (head-of w :a)
            w (run-world w (+ (:now w) 60000)
                         "the publisher's HEAD was not reported"
                         #(published % :a m))]
        (testing "answered as data, not thrown"
          (is (= 'empty.lib (:module res)) text)
          (is (= {:yin.ast/code :ok :yin.semantic/code :ok
                  :yin.debruijn.code :ok :yin.debruijn.register :ok}
                 (:links res))
              text))
        (testing "a reader requires it by name on every VM"
          (let [w (hydrate w :b bdir 9 m [(:public key)])
                w (reduce (fn [w vm-type]
                            (let [[w _] (eval-at w :b (str "(vm " vm-type ")"))
                                  [w _] (require-at w :b 'empty.lib)]
                              (is (= 'empty.lib (get-in w [:shells :b
                                                           :last-value]))
                                  (str vm-type " " (pr-str (last-response w
                                                                          :b))))
                              w))
                          w
                          vm-types)]
            (close-all! w))))
      (finally
        (run! cleanup-dir! [adir bdir])))))


(defn- forged-index!
  "The publisher's HEAD index `m` with every proof replaced by one `key`
   made over another envelope, published and announced through the
   publisher's own node: its name claims, proofs that do not verify."
  [node m key]
  (dht-test/publish-datoms!
    node
    (into []
          (comp (filter (fn [[_ a]] (= :yin.module/envelope a)))
                (map-indexed (fn [i [_ _ env]]
                               {:db/id (+ datom/first-user-id i)
                                :yin.module/envelope env
                                :yin.module/proof (sign/sign-envelope
                                                    (:seed key)
                                                    (update env :yin.module/seq
                                                            + 1000))})))
          (index/read-datoms (dht/local node) m))))


(deftest a-proof-over-another-envelope-does-not-resolve
  (let [[adir ddir] [(temp-dir) (temp-dir)]
        net (mesh/mesh)
        key (sign/generate)]
    (try
      (let [[w m] (publish-world net adir key)
            forged (forged-index! (get-in w [:shells :a :dht]) m key)
            w (run-world w (+ (:now w) 60000)
                         "the forged index was not reported"
                         #(published % :a forged))
            w (hydrate w :d ddir 6 forged [(:public key)])
            [w _] (require-at w :d 'my.lib)
            body (last-response w :d)]
        (is (nil? (:failure w)) (:failure w))
        (is (= {:status :refused :reason :absent :name 'my.lib}
               (select-keys body [:status :reason :name]))
            (pr-str body))
        (is (= [[:unauthenticated :bad-proof]]
               (mapv (juxt :reason :kind) (:diagnostics body)))
            "the signature was verified, and failed")
        (close-all! w))
      (finally
        (run! cleanup-dir! [adir ddir])))))
