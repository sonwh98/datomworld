(ns yang.python.antlr.dht-import-test
  "C4 slice I3 (docs/design/yang.antlr.md 8.5.6): Python modules published
   by name and imported over the DHT.

   The fast tests pin the publication: a unit set publishes bottom-up, a
   cyclic pair is refused by name before anything is written, an import
   no unit or publication supplies is refused, and the foreign-principal
   admission refuses a name only another principal vouches for.

   The slow test is the acceptance over the `dao.jing.dht` mesh seam, the
   in-process form `yin.vm.linker.dht-end-to-end-test` uses, so it runs on
   the JVM, Node and Dart alike. What travels is the JVM's publication:
   the artifact `yang.python.antlr.dht-import-fixture` reads, written on
   the JVM by its generator script -- the guest blobs, manifest addresses
   and signed envelopes of `A`'s units and `B`'s name, and the importer
   packets. No host republishes it: the publisher node serves the
   artifact's bytes and envelopes as they are. Each host also publishes
   the same packets itself, into a store of its own, and must arrive at
   the artifact: a host-specific publication difference fails there.
   `py` is the runtime every node has installed, one manifest address on
   every host (`linked-prelude-test`'s golden, and the artifact's), since
   its code images exceed the DHT's message bound.

   One reader, handed `A`'s index manifest and declaring `A`, imports
   through the DHT link source on the semantic, stack and register VMs.
   Then `A`'s republication of `k` is indexed, and the reader's import of
   `t`, which pinned the old `k`, is refused as a dependency binding with
   no install child and no output; then `B` vouches for a module, and the
   reader, declaring `B` too, is refused it by the Python admission until
   L-e, again with no install child and no output."
  (:require
    [clojure.test :refer [deftest is testing]]
    [dao.datom :as datom]
    [dao.jing.dht :as jing.dht]
    [dao.jing.dht.mesh :as mesh]
    [dao.jing.mem :as mem]
    [dao.space.dht :as dht]
    [dao.space.dht-test :as dht-test]
    [dao.test-slow :as slow]
    [yang.python.antlr.dht-import-fixture :as fixture]
    [yang.python.antlr.import-programs :as programs]
    [yang.python.antlr.linked-harness :as h]
    [yang.python.antlr.lower :as lower]
    [yang.python.antlr.publication :as publication]
    [yang.python.antlr.render :as render]
    [yin.repl.link :as link]
    [yin.vm :as vm]
    [yin.vm.linker.sign :as sign]
    [yin.vm.module :as module]))


;; =============================================================================
;; The fast tests: publication order, cycles, admission
;; =============================================================================

(def ^:private stand-in-address
  "A stand-in `py` address: a refused publication never reads it."
  :test/address)


(defn- unit
  [nm pk]
  {:name nm, :packet pk, :package? false})


(deftest units-publish-after-what-they-import-test
  (testing "t imports k: k is ordered first whatever the input order"
    (is (= {:status :ok, :order ["k" "t" "m"]}
           (publication/publication-order
             [(unit "t" programs/t-packet) (unit "k" programs/k-packet)
              (unit "m" programs/m-packet)]
             {}))))
  (testing "an import a publication already supplies needs no unit"
    (is (= {:status :ok, :order ["t"]}
           (publication/publication-order [(unit "t" programs/t-packet)]
                                          {"k" :test/k})))))


(deftest a-cyclic-pair-is-refused-at-publication-by-name-test
  (let [store (mem/create-content-mem)
        refusal {:status :refused,
                 :reason :yang.python.antlr/import-cycle,
                 :cycle ["ca" "cb"]}]
    (testing "ca imports cb imports ca: refused naming both, least first,
              whichever unit the set lists first"
      (is (= refusal
             (publication/publish-units!
               store (h/registry) stand-in-address
               [(unit "ca" programs/ca-packet)
                (unit "cb" programs/cb-packet)])))
      (is (= refusal
             (publication/publish-units!
               store (h/registry) stand-in-address
               [(unit "cb" programs/cb-packet)
                (unit "ca" programs/ca-packet)]))))
    (testing "nothing was written"
      (is (empty? ((:entries-fn store)))))))


(deftest an-unsupplied-import-is-refused-at-publication-test
  (let [store (mem/create-content-mem)]
    (is (= {:status :refused,
            :reason :yang.python.antlr/unpublished-import,
            :module "k",
            :imported-by "t"}
           (publication/publish-units! store (h/registry) stand-in-address
                                       [(unit "t" programs/t-packet)])))
    (is (empty? ((:entries-fn store))))))


(deftest foreign-principals-are-refused-until-l-e-test
  (let [a (sign/generate)
        b (sign/generate)
        pa (sign/principal (:public a))
        pb (sign/principal (:public b))
        admit (publication/principal-admission [(:public a)])
        entry (fn [& asserters]
                {:status :ok,
                 :address :test/m,
                 :yin.link/provenance {:yin.module/asserted-by
                                       (vec asserters)}})]
    (testing "the importer's own principal is admitted, alone or beside
              another asserting the same address (R8)"
      (is (nil? (admit 'pym.m (entry pa))))
      (is (nil? (admit 'pym.m (entry pb pa)))))
    (testing "a direct entry is the composition's own"
      (is (nil? (admit 'pym.m {:status :ok,
                               :address :test/m,
                               :yin.link/provenance :composition}))))
    (testing "only another principal: refused, qualified, naming L-e"
      (is (= {:status :refused,
              :reason :yang.python.antlr/foreign-principal,
              :name 'pym.m,
              :asserters [pb],
              :requires :L-e}
             (admit 'pym.m (entry pb)))))))


;; =============================================================================
;; The mesh world: a publisher, two plain peers, a reader
;; =============================================================================

(def ^:private big
  "Every node's inbound bound: py's closure is replicated whole."
  (* 512 1024 1024))


(defn- peer-node
  [net port]
  (let [c (mesh/join! net port {::jing.dht/publish? true
                                ::jing.dht/max-inbound-bytes big})]
    {:composition c, :state (jing.dht/state c)}))


(defn- node-at
  "A node at mesh `port` whose local store already holds `seed`
   ({address bytes}): content it has, not content it publishes."
  [net port peer-ports publish? seed]
  (dht/join {:local (mem/create-content-mem seed),
             :publish? publish?,
             :peers (mapv (fn [p] {:host mesh/host, :port p}) peer-ports),
             :bind! (mesh/seam net port),
             :max-inbound-bytes big}))


(defn- step-world
  "One reading: the publisher, the reader when there is one, then every
   plain peer."
  [w]
  (let [now (:now w)
        [pub pub-events] (dht/step (:pub w) now)
        [reader] (when (:reader w) (dht/step (:reader w) now))]
    (-> w
        (assoc :pub pub
               :reader reader
               :peers (into {}
                            (map (fn [[port {:keys [composition state]}]]
                                   (mesh/tick! composition now)
                                   [port {:composition composition,
                                          :state (jing.dht/step state 256)}]))
                            (:peers w))
               :now (+ now 10))
        (update :events into pub-events))))


(defn- run-world
  "Step `w` until `(done? w)`; `:failure` names `cause` when `span` more
   readings pass first."
  [w span cause done?]
  (let [limit (+ (:now w) span)]
    (loop [w w]
      (cond
        (done? w) w
        (> (:now w) limit) (assoc w :failure cause)
        :else (recur (step-world w))))))


(defn- published?
  [manifest]
  (fn [w]
    (some #(and (= :published (::dht/event %)) (= manifest (:manifest %)))
          (:events w))))


(defn- index!
  "Publish the signed envelopes `signed` (`{:envelope :proof}`) as one
   index transaction through `node` and announce it: its manifest."
  [node signed]
  (dht-test/publish-datoms!
    node
    (vec (map-indexed (fn [i s]
                        {:db/id (+ datom/first-user-id i),
                         :yin.module/envelope (:envelope s),
                         :yin.module/proof (:proof s)})
                      signed))))


(defn- publish-index
  "Publish `signed` as an index through the publisher, run until it is
   reported, and load it onto the reader: `[w manifest]`."
  [w signed]
  (let [m (index! (:pub w) signed)
        w (run-world w 600000 (str "the index " m " was not reported")
                     (published? m))
        _ (is (nil? (:failure w)) (:failure w))
        w (run-world (update w :reader dht/load-index m)
                     600000 (str "the index " m " did not load")
                     #(contains? #{:loaded :failed}
                                 (:status (dht/load-status (:reader %) m))))]
    (is (nil? (:failure w)) (:failure w))
    (is (= :loaded (:status (dht/load-status (:reader w) m))))
    [w m]))


;; =============================================================================
;; A run over the DHT link source
;; =============================================================================

(defn- py-output
  "The lines the Python runtime of `task` has printed, read off its
   `py.rt/out` cell in py's module store: none when py has not run
   `py/init!`, so no Python statement can have printed."
  [task]
  (let [ref (some #(get % 'py.rt/out) (vals (:module-stores task)))]
    (mapv render/line (when ref (get-in task [:heap (:id ref) :value])))))


(defn- guest-installs
  "The guest modules `task` has started an install child for: the
   `pym.*` names it has linked or is installing, as strings (a name
   compares by its text whatever symbol value a kernel's registry
   holds)."
  [task]
  (into (sorted-set)
        (comp (map str)
              (filter #(= "pym." (subs % 0 (min 4 (count %))))))
        (concat (map first (module/module-entries (:modules task)))
                (keys (:installs task)))))


(defn- observed
  "What `task` shows independently of its value: the lines it printed,
   the guest modules it installed, and how many install children it ever
   started (its origin counter, `py`'s included)."
  [task]
  {:output (py-output task),
   :guests (guest-installs task),
   :children (or (:origins task) 0)})


(defn- run-over-dht
  "The linked program of packet `pk` on `backend`, its link pair served
   by the DHT source `src` from the world's reader, the world stepped
   between rounds: `[w outcome last-response observation]`, the outcome
   `[halted? blocked? output]` or `[:thrown message]`. The observation
   is `observed` of the task the program ended as -- for a thrown run,
   of the task the throwing run resumed, which holds every step before
   the raise: a refused link raises at its resume, before anything
   else runs."
  [w backend pk src]
  (let [pair (link/make-pair)
        task ((get h/backends backend)
              (lower/lower-packet pk {:prelude :linked})
              (h/composition (h/registry) pair))
        limit (+ (:now w) 1200000)
        run (fn [task]
              (try (vm/run task)
                   (catch #?(:cljd Object :clj Exception :cljs :default) e
                     {::thrown (ex-message e), ::resumed task})))]
    (loop [w w
           task (run task)
           pair pair]
      (let [response (dissoc (peek (mesh/values (:responses pair)))
                             :yin.link/id :image)]
        (cond
          (::thrown task)
          [w [:thrown (::thrown task)] response (observed (::resumed task))]

          (and (vm/blocked? task) (< (:now w) limit))
          (let [{:keys [pair dht]} (link/serve {:pair pair,
                                                :source src,
                                                :dht (:reader w)})
                w (step-world (assoc w :reader dht))]
            (recur w (run task) pair))

          :else
          [w
           [(vm/halted? task) (vm/blocked? task)
            (render/output (vm/value task))]
           response
           (observed task)])))))


(defn- source
  "The reader's DHT link source declaring the public keys `publics`, its
   Python admission naming `own`."
  [publics own]
  (link/composition {:dht? true,
                     :principals publics,
                     :admit (publication/principal-admission own)}))


(defn- on-every-vm
  "Thread `w` through `run-over-dht` on every vector backend: `[w {backend
   [outcome last-response observation]}]`."
  [w pk src]
  (reduce (fn [[w acc] k]
            (let [[w outcome response seen] (run-over-dht w k pk src)]
              [w (assoc acc k [outcome response seen])]))
          [w {}]
          (keys h/backends)))


;; =============================================================================
;; The acceptance
;; =============================================================================

(defn- first-difference
  "The first key of the artifacts `a` and `b` (`fixture/parse`'s data)
   whose values differ, with the blob addresses only one holds or holds
   other bytes at; nil when they are equal."
  [a b]
  (when (not= a b)
    (let [blobs (fn [x y]
                  (sort (map str (remove #(= (get x %) (get y %))
                                         (keys x)))))]
      {:keys (vec (sort (map str (filter #(not= (get a %) (get b %))
                                         (distinct (concat (keys a)
                                                           (keys b))))))),
       :blobs-differ (vec (take 4 (blobs (:blobs a) (:blobs b)))),
       :blobs-only-here (vec (take 4 (blobs (:blobs b) (:blobs a))))})))


(defn- this-host-publishes-the-artifact
  [artifact-lines]
  (testing "this host's own publication of the committed packets is the
            JVM's artifact: same manifests, blobs and envelopes"
    (let [{:keys [lines refused]} (fixture/generate)]
      (is (nil? refused) (pr-str refused))
      (when lines
        (is (nil? (first-difference (fixture/parse artifact-lines)
                                    (fixture/parse lines))))))))


(defn- publish-world
  "Publisher `A` at mesh port 1, plain peers at 2 and 3, a reader at 4,
   `A` and the reader with `py` installed: `A`'s node serves the
   artifact's guest blobs as they are and its first index (py, m, k and
   t named by `A`), which the reader loads."
  [artifact]
  (let [net (mesh/mesh)
        installed (fixture/installed-py)
        pub (node-at net 1 [2 3] true installed)
        put! (:put-bytes-fn (dht/store pub))
        w {:net net,
           :pub pub,
           :reader (node-at net 4 [2 3 1] false installed),
           :peers (into {} (map (fn [p] [p (peer-node net p)])) [2 3]),
           :now 0,
           :events []}]
    (testing "every node's installed py is the artifact's"
      (is (= (:py artifact) (:address @h/published))))
    (run! (fn [[address bs]] (put! address bs))
          (sort-by (comp str key) (:blobs artifact)))
    (first (publish-index w (get-in artifact [:indexes :own])))))


(def ^:private nothing-ran
  "A refused import's observation: no line printed and no guest install
   child; the one child started is py's."
  {:output [], :guests #{}, :children 1})


(defn- observes
  "Assert each key of the observation `expected` of backend `k` on its
   own, so a failure names the key."
  [k expected seen]
  (doseq [[key v] expected]
    (is (= v (get seen key)) (str k " " key))))


(defn- imports-over-the-dht
  [w artifact src]
  (testing "a JVM-published pym.m imports over the DHT, parserless, with
            the in-store linked run's output and CPython's"
    (let [[w results] (on-every-vm w (get-in artifact [:importers :d]) src)]
      (doseq [[k [outcome response seen]] results]
        (is (= [true false {:py/out ["m-body" "7"], :py/exception nil}]
               outcome)
            (str k " " (pr-str response)))
        (testing "and the observation sees the same run"
          (observes k {:output ["m-body" "7"], :guests #{"pym.m"},
                       :children 2}
                    seen)))
      (testing "and t delivers k, its pinned import, by name"
        (let [[w results] (on-every-vm w (get-in artifact [:importers :tt])
                                       src)]
          (doseq [[k [outcome response seen]] results]
            (is (= [true false
                    {:py/out ["k-body" "t-sees 3" "t-done"],
                     :py/exception nil}]
                   outcome)
                (str k " " (pr-str response)))
            (observes k {:guests #{"pym.k" "pym.t"}} seen))
          w)))))


(defn- republished-dependency-is-refused
  [w artifact src]
  (let [k1 (get-in artifact [:units "k"])
        k2 (get-in artifact [:republished "k"])
        [w _] (publish-index w (get-in artifact [:indexes :republish]))]
    (testing "a republished dependency: t pinned k's first address, k now
              names another, so t is refused as a dependency binding with
              no install child and no output -- nothing of t or k runs"
      (is (not= k1 k2))
      (let [[w results] (on-every-vm w (get-in artifact [:importers :tt])
                                     src)]
        (doseq [[k [outcome response seen]] results]
          (is (= :thrown (first outcome)) (str k " " (pr-str outcome)))
          (is (= {:status :refused,
                  :reason :yin.link.dht/dependency-binding,
                  :name 'pym.k,
                  :pinned k1,
                  :binding :mismatch,
                  :resolved k2}
                 (select-keys response [:status :reason :name :pinned
                                        :binding :resolved]))
              (str k " " (pr-str response)))
          (observes k nothing-ran seen))
        w))))


(defn- foreign-principal-is-refused
  [w artifact]
  (let [{:keys [a b]} (:principals artifact)
        [w _] (publish-index w (get-in artifact [:indexes :foreign]))
        both (source [a b] [a])]
    (testing "a module only B vouches for, B declared: the Python admission
              refuses it by name until L-e, with no install child and no
              output"
      (let [[w results] (on-every-vm w (get-in artifact [:importers :x])
                                     both)]
        (doseq [[k [outcome response seen]] results]
          (is (= :thrown (first outcome)) (str k " " (pr-str outcome)))
          (is (= {:status :refused,
                  :reason :yang.python.antlr/foreign-principal,
                  :name 'pym.e,
                  :asserters [(sign/principal b)],
                  :requires :L-e}
                 response)
              (str k " " (pr-str response)))
          (observes k nothing-ran seen))
        w))))


(defn- dht-import-test
  []
  (let [lines (fixture/read-lines)
        _ (this-host-publishes-the-artifact lines)
        artifact (fixture/read-file)
        a (get-in artifact [:principals :a])
        src (source [a] [a])
        w (publish-world artifact)
        w (imports-over-the-dht w artifact src)
        w (republished-dependency-is-refused w artifact src)
        w (foreign-principal-is-refused w artifact)]
    (dht/close! (:pub w))
    (dht/close! (:reader w))))


(deftest ^:slow dht-import-test-test
  (slow/guard "dht-import-test" dht-import-test))
