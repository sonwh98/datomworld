(ns yang.python.antlr.dht-import-test
  "C4 slices I3/I5 (docs/design/yang.antlr.md 8.5.6): Python modules published
   by name and imported over the DHT.

   The fast tests pin the publication: a unit set publishes bottom-up, a
   cyclic pair publishes as one SCC bundle step, an import
   no unit or publication supplies is refused.

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
   Then `B` vouches for a module, and the reader, declaring `B` too, is
   refused it by the default admission interpreter while it trusts only `A`
   (yin.vm.linker.dht.md 7.5), raising ImportError in Python with no install
   child and no output, and allowed it once it trusts `B`. Next, `B`'s head
   moves the name to another module between the admission question and its
   answer: the allow binds the module the question was asked about. Last,
   `A`'s republication of `k` is indexed, and the reader's import of `t`,
   which pinned the old `k`, encounters a dependency pin mismatch at
   statement-time receive when `k` is delivered, raising ImportError before `k`
   is installed or runs."
  (:require
    [clojure.test :refer [deftest is testing]]
    [dao.datom :as datom]
    [dao.jing :as jing]
    [dao.jing.dht :as jing.dht]
    [dao.jing.dht.mesh :as mesh]
    [dao.jing.mem :as mem]
    [dao.space.dht :as dht]
    [dao.space.dht-test :as dht-test]
    [dao.space.index :as index]
    [dao.stream :as stream]
    [dao.test-slow :as slow]
    [yang.python.antlr.dht-import-fixture :as fixture]
    [yang.python.antlr.import-programs :as programs]
    [yang.python.antlr.linked-harness :as h]
    [yang.python.antlr.lower :as lower]
    [yang.python.antlr.publication :as publication]
    [yang.python.antlr.render :as render]
    [yang.python.antlr.safepoint :as hooks]
    [yang.python.antlr.siteset :as siteset]
    [yin.vm :as vm]
    [yin.vm.engine :as engine]
    [yin.vm.linker.admission :as admission]
    [yin.vm.linker.dht :as ld]
    [yin.vm.linker.head :as head]
    [yin.vm.linker.interpreter :as link]
    [yin.vm.linker.publish :as publish]
    [yin.vm.linker.sign :as sign]
    [yin.vm.module :as module]
    [yin.vm.test-utils :as tu]))


;; =============================================================================
;; The fast tests: publication order, cycles, admission
;; =============================================================================

(def ^:private stand-in-manifest
  {:yin.module/requires {'yang.call :test/call-address}})


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


(deftest cyclic-components-are-ordered-as-one-publication-step-test
  (doseq [units [[(unit "ca" programs/ca-packet) (unit "cb" programs/cb-packet)]
                 [(unit "cb" programs/cb-packet) (unit "ca" programs/ca-packet)]]]
    (is (= {:status :ok, :order [["ca" "cb"]]}
           (publication/publication-order units {})))))


(deftest an-unsupplied-import-is-refused-at-publication-test
  (let [store (mem/create-content-mem)
        py-address (jing/materialize! store stand-in-manifest)
        before-addresses (set (keys ((:entries-fn store))))]
    (is (= {:status :refused,
            :reason :yang.python.antlr/unpublished-import,
            :module "k",
            :imported-by "t"}
           (publication/publish-units! store (h/registry) py-address
                                       [(unit "t" programs/t-packet)])))
    (is (= before-addresses
           (set (keys ((:entries-fn store))))))))


;; the artifact's modules as [name manifest]: the units, then k's republication
(defn- artifact-modules
  [artifact]
  (into (vec (sort-by key (:units artifact)))
        (map (fn [[n m]] [(str n " (republished)") m]))
        (:republished artifact)))


(deftest published-site-sets-verify-test
  (testing "every module of the artifact has a site-set record that A's
            signed index entry publishes, discovered from the published
            code under A alone, fetched by address, and verifying against
            the manifest in the artifact's own blobs: root, frontend
            revision, transformation profile and the set's address (C4
            slice I7)"
    (let [artifact (fixture/read-file)
          store (mem/create-content-mem (:blobs artifact))
          datoms (siteset/entry-datoms (get-in artifact [:indexes :sites]))
          {:keys [a b]} (:principals artifact)
          hooks {:profile :py/hooks, :hooks true}]
      (doseq [[n manifest] (artifact-modules artifact)]
        (let [found (siteset/discover datoms [a] manifest)]
          (is (= 1 (count found)) n)
          (is (nil? (siteset/verify store manifest (first found))) n)
          (is (= :ok (:status (siteset/admit store manifest found hooks))) n)
          (is (= [] (siteset/discover datoms [b] manifest))
              (str n " is not discovered under a principal that did not sign it"))
          (is (= :yang.python.antlr/missing-site-set
                 (:reason (siteset/admit store manifest [] hooks)))
              n))))))


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
                        (let [site? (contains? (:envelope s) :yang.site/op)]
                          {:db/id (+ datom/first-user-id i),
                           (if site? :yang.site/envelope :yin.module/envelope)
                           (:envelope s),
                           (if site? :yang.site/proof :yin.module/proof)
                           (:proof s)}))
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
   :children (or (:origins task) 0),
   :admissions (into {}
                     (keep (fn [[n entry]]
                             (when (contains? (guest-installs task) (str n))
                               [(str n) (:admission entry)])))
                     (module/module-entries (:modules task)))})


(defn- with-signals
  "`task` with a signal stream holding `signals` bound where `pysp` polls."
  [task signals]
  (let [handle (tu/new-stream 8)]
    (doseq [s signals] (stream/append! handle s))
    (let [[ref task] (engine/attach-resource task handle)]
      (assoc-in task [:store hooks/signals-key] ref))))


(defn- run-over-dht
  "The linked program of packet `pk` on `backend`, its link pair served
   by the DHT source `src` from the world's reader, the world stepped
   between rounds: `[w outcome response observation]`, the outcome
   `[halted? blocked? output]` or `[:thrown message]`. `response` is the
   first refused pair response when any exists, else the latest served
   response. The observation is `observed` of the task the program ended
   as -- for a thrown run, of the task the throwing run resumed, which
   holds every step before the raise: a refused link raises at its resume,
   before anything else runs.

   Both interpreters are driven and retained across rounds
   (yin.vm.linker.dht.md 7.5): the link server, over the admission state
   minted beside the pair, and the source's in-process admission
   interpreter, when the source composes one, which answers each
   question the server asks. `between`, `(between w served)` of each
   serve round's result once the world is stepped, answers the world:
   the place of an interpreter outside the process."
  ([w backend pk src] (run-over-dht w backend pk src (fn [w _] w)))
  ([w backend pk src between] (run-over-dht w backend pk src between nil))
  ([w backend pk src between {:keys [signals]}]
   (let [pair (link/make-pair)
         adm (admission/make-state link/pair-capacity)
         interp (when (:admission src)
                  (admission/interpreter adm (:admission src)))
         linked (lower/lower-packet pk {:prelude :linked})
         task (cond-> ((get h/backends backend)
                       (if signals (hooks/linked-program linked) linked)
                       (h/composition (h/registry) pair))
                signals (with-signals signals))
         limit (+ (:now w) 1200000)
         run (fn [task]
               (try (vm/run task)
                    (catch #?(:cljd Object :clj Exception :cljs :default) e
                      {::thrown (ex-message e), ::resumed task})))]
     (loop [w w
            task (run task)
            pair pair
            adm adm
            interp interp]
       (let [response (or (some #(when (= :refused (:status %))
                                   (dissoc % :yin.link/id :image))
                                (mesh/values (:responses pair)))
                          (dissoc (peek (mesh/values (:responses pair)))
                                  :yin.link/id :image))]
         (cond
           (::thrown task)
           [w [:thrown (::thrown task)] response (observed (::resumed task))]

           (and (vm/blocked? task) (< (:now w) limit))
           (let [{:keys [pair dht admission] :as served}
                 (link/serve {:pair pair,
                              :source src,
                              :dht (:reader w),
                              :admission adm})
                 admitted (when interp
                            (admission/serve
                              {:interpreter interp,
                               :policy (:admission src),
                               :context (admission/context {})}
                              link/serve-budget))
                 w (between (step-world (assoc w :reader dht)) served)]
             (recur w
                    (run task)
                    pair
                    admission
                    (if admitted (:interpreter admitted) interp)))

           :else
           [w
            [(vm/halted? task) (vm/blocked? task)
             (render/output (vm/value task))]
            response
            (observed task)]))))))


(defn- source
  "The reader's DHT link source declaring the public keys `publics`, its
   in-process admission interpreter trusting the principals of the
   public keys `trusted` -- none in process when `trusted` is nil."
  [publics trusted]
  (link/composition
    {:dht? true,
     :principals publics,
     :admission (when trusted
                  {:trusted (set (map sign/principal trusted))})}))


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
           :reader (node-at net 4 [2 3 1] false
                            (merge installed (fixture/installed-pysp))),
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
              names another; the link server delivers k2, but the engine
              refuses receive as a pin mismatch, raising ImportError in t"
      (is (not= k1 k2))
      (let [[w results] (on-every-vm w (get-in artifact [:importers :tt])
                                     src)]
        (doseq [[k [outcome response seen]] results]
          (is (= [true false
                  {:py/out [],
                   :py/exception
                   {:type "ImportError",
                    :args ["Cannot import module 'k': linker refused delivery"]}}]
                 outcome)
              (str k " " (pr-str outcome)))
          (is (= {:status :ok,
                  :yin.admit/address k2}
                 (select-keys response [:status :yin.admit/address]))
              (str k " " (pr-str response)))
          (observes k {:output [], :guests #{"pym.t"}, :children 3} seen))
        w))))


;; -----------------------------------------------------------------------------
;; B's module: refused untrusted, allowed trusted, bound to its question
;; -----------------------------------------------------------------------------

(def ^:private caught-e
  "x's run once e is linked: e.boom raises, x catches it."
  [true false {:py/out ["caught-e"], :py/exception nil}])


(defn- an-untrusted-principal-is-refused-on-every-vm
  [w artifact]
  (let [{:keys [a b]} (:principals artifact)
        [w _] (publish-index w (get-in artifact [:indexes :foreign]))]
    (testing "a module only B vouches for, B declared and A alone trusted:
              the default interpreter refuses it as untrusted, naming B,
              raising ImportError in x before e installs"
      (let [[w results] (on-every-vm w (get-in artifact [:importers :x])
                                     (source [a b] [a]))]
        (doseq [[k [outcome response seen]] results]
          (is (= [true false
                  {:py/out [],
                   :py/exception
                   {:type "ImportError",
                    :args ["Cannot import module 'e': linker refused delivery"]}}]
                 outcome)
              (str k " " (pr-str outcome)))
          (is (= {:status :refused,
                  :reason :yin.admit/untrusted-principal,
                  :name 'pym.e,
                  :yin.admit/decision :refuse,
                  :yin.admit/asserters [(sign/principal b)]}
                 (dissoc response :yin.admit/id))
              (str k " " (pr-str response)))
          (is (vector? (:yin.admit/id response)) (str k))
          (observes k nothing-ran seen))
        w))))


(defn- a-trusted-principal-is-allowed-on-every-vm
  [w artifact]
  (let [{:keys [a b]} (:principals artifact)
        e (get-in artifact [:units "e"])]
    (testing "the reader trusts B explicitly: e imports on every VM, its
              registry entry recording the admission it rode on"
      (let [[w results] (on-every-vm w (get-in artifact [:importers :x])
                                     (source [a b] [a b]))]
        (doseq [[k [outcome response seen]] results]
          (is (= caught-e outcome) (str k " " (pr-str response)))
          (observes k {:output ["caught-e"],
                       :guests #{"pym.e"},
                       :children 2,
                       :admissions {"pym.e"
                                    {:yin.admit/id (:yin.admit/id response),
                                     :yin.admit/decision :allow,
                                     :yin.admit/asserters
                                     [(sign/principal b)],
                                     :yin.admit/address e}}}
                    seen))
        w))))


(defn- install-confirmed
  [follower node events]
  (reduce (fn [[f n] e]
            (if (= :confirmed (:yin.head/event e))
              (let [[f n _] (head/install f n (:principal e) (:trace e))]
                [f n])
              [f n]))
          [follower node]
          events))


(defn- follow-step
  "One reading of the world, then one pass of the reader's head
   follower, installing what it confirms."
  [w]
  (let [w (step-world w)
        [f node events] (head/step (:follower w) (:reader w) (:now w))
        [f node] (install-confirmed f node events)]
    (assoc w :follower f :reader node)))


(defn- move-b-head
  "Publish B's next index through the publisher -- B's assertion of
   pym.e retracted, pym.e now naming `m2` -- deposit its head on B's
   board, and step the world and the reader's follower until the head is
   installed: the world."
  [w artifact m2]
  (let [key-b fixture/key-b
        pb (sign/principal (:public key-b))
        [e1] (get-in artifact [:indexes :foreign])
        signed [(publish/retraction
                  key-b
                  {:of (jing/segment-key (:envelope e1)), :seq 2})
                (publish/assertion key-b
                                   {:name 'pym.e, :manifest m2, :seq 3})]
        m (index! (:pub w) signed)
        w (run-world w 600000 (str "B's head " m " was not reported")
                     (published? m))
        dep (head/deposit! (:board w) key-b m
                           (index/read-datoms (dht/local (:pub w)) m))
        limit (+ (:now w) 600000)
        w (loop [w w]
            (cond
              (= m (get (ld/installed-heads (:reader w)) pb)) w
              (> (:now w) limit) (assoc w :failure "B's head not installed")
              :else (recur (follow-step w))))]
    (is (= :ok (:status dep)) (pr-str dep))
    (is (nil? (:failure w)) (:failure w))
    w))


(defn- allow-after-the-head-moves
  "The `between` of `run-over-dht` for F1, an external interpreter: it
   allows each question once, every name but pym.e at once (py is A's),
   and pym.e only after moving B's head to `m2`. It records under
   `:asked` every admission id a pending pym.e link names."
  [artifact src m2]
  (fn [w served]
    (let [pending (filter :admission (:pending served))
          e? #(= "pym.e" (str (:name %)))
          w (update w :asked into (map :admission (filter e? pending)))
          w (if (or (:moved w) (not-any? e? pending))
              w
              (let [w (move-b-head w artifact m2)]
                (is (= m2 (:address (ld/resolve-name (:reader w)
                                                     (link/authority src)
                                                     'pym.e)))
                    "B's head moved pym.e to M2 while the question was open")
                (assoc w :moved true)))]
      (reduce (fn [w {:keys [admission]}]
                (if (contains? (:answered w) admission)
                  w
                  (do (stream/append! (get-in served
                                              [:admission :pair :answers])
                                      {:yin.admit/id admission,
                                       :yin.admit/decision :allow,
                                       :yin.admit/conditions {}})
                      (update w :answered conj admission))))
              w
              pending))))


(defn- an-allow-binds-the-address-the-question-was-asked-about
  [w artifact]
  (let [{:keys [a b]} (:principals artifact)
        pb (sign/principal b)
        m1 (get-in artifact [:units "e"])
        m2 (get-in artifact [:republished "k"])
        src (source [a b] nil)
        [f reader] (head/follow (:reader w) {:follow [pb],
                                             :heads :volatile,
                                             :poll-ticks 10})
        board (head/board!)
        w (assoc w
                 :reader reader
                 :follower (head/attach f pb board)
                 :board board
                 :asked #{}
                 :answered #{})]
    (testing "M2 is a module the reader has never loaded"
      (is (not= m1 m2))
      (is (nil? (ld/module-status (:reader w) m2))))
    (testing "an allow for the question about M1, answered after B's head
              moved pym.e to M2, links M1 and never fetches M2 (F1)"
      (let [[w outcome response seen]
            (run-over-dht w :semantic (get-in artifact [:importers :x]) src
                          (allow-after-the-head-moves artifact src m2))
            id (:yin.admit/id response)]
        (is (:moved w) "the question was asked and answered")
        (is (= caught-e outcome) (pr-str response))
        (is (= m1 (:yin.admit/address response)))
        (is (= (jing/get (dht/local (:pub w)) m1 nil) (:manifest response)))
        (is (some? (ld/module-status (:reader w) m1)))
        (is (nil? (ld/module-status (:reader w) m2)))
        (is (= {"pym.e" {:yin.admit/id id,
                         :yin.admit/decision :allow,
                         :yin.admit/asserters [pb],
                         :yin.admit/address m1}}
               (:admissions seen)))
        (is (= #{id} (:asked w)) "the pending admission id never changed")
        w))))


(defn- site-sets-over-the-mesh
  "I7's mesh leg: the reader loads `A`'s site-set index and discovers each
   unit's record address from the entries `A` signed. It holds no record
   bytes: the stage load of an import fetches them (`hook-import-with-the-
   index`). Nothing here prefetches."
  [w artifact]
  (let [{:keys [a]} (:principals artifact)
        [w m] (publish-index w (get-in artifact [:indexes :sites]))
        datoms (dht/loaded-datoms (:reader w) m)
        modules (remove (fn [[n _]] (= "e" n)) (sort-by key (:units artifact)))
        records (into {}
                      (map (fn [[n manifest]]
                             [n (siteset/discover datoms [a] manifest)]))
                      modules)]
    (testing "the reader discovers one record per module from the index"
      (is (some? datoms))
      (is (every? #(= 1 (count (val %))) records) (pr-str records)))
    (testing "the reader holds no record before any import"
      (is (every? (fn [[_ rs]]
                    (nil? ((:get-bytes-fn (dht/local (:reader w)))
                           (first rs) nil)))
                  records)))
    w))


(defn- hook-source
  "The reader's DHT source declaring `A`, trusting `A`, `py` named by `A`'s
   assertion and `pysp` by a direct entry, composed with the link-time
   stage of a hook profile (no local records: it discovers them)."
  [artifact]
  (let [{:keys [a]} (:principals artifact)
        pysp (:address @h/published-pysp)]
    (link/composition
      {:dht? true,
       :principals [a],
       :name-env {'pysp pysp},
       :admission {:trusted #{(sign/principal a)}},
       :stage (siteset/stage (h/registry)
                             {:profile :py/hooks,
                              :hooks true,
                              :hooks-profile hooks/linked-profile,
                              :pysp pysp}
                             {})})))


(defn- noting-waits
  "A `between` for `run-over-dht` recording under `:waits` every manifest
   a pending entry waits on."
  [w served]
  (-> w
      (update :waits (fnil into #{}) (keep :manifest (:pending served)))
      (update :responses (fnil into #{})
              (map #(dissoc % :yin.link/id :image)
                   (mesh/values (:responses (:pair served)))))))


(defn- hook-import-refused-without-the-index
  "Without `A`'s site-set index loaded the reader's stage finds no record:
   the import is refused at link time naming the profile and the set, with
   nothing fetched."
  [w artifact]
  (let [m (get-in artifact [:units "m"])
        [w outcome response _]
        (run-over-dht w :semantic (get-in artifact [:importers :d])
                      (hook-source artifact) (fn [w _] w) {:signals []})]
    (testing "no site-set index: refused, the importer ends with ImportError"
      (is (= "ImportError" (get-in (nth outcome 2) [:py/exception :type]))
          (pr-str outcome))
      (is (= :yang.python.antlr/missing-site-set (:reason response))
          (pr-str response))
      (is (= :py/hooks (:profile response)))
      (is (= 'pym.m (:name response)))
      (is (= m (:yin.link/canonical response))))
    w))


(defn- hook-import-with-the-index
  "With `A`'s site-set index loaded the cold reader's stage load fetches
   m's record and set by address, derives and delivers the instrumented
   module; the program runs."
  [w artifact]
  (let [{:keys [a]} (:principals artifact)
        m (get-in artifact [:units "m"])
        record (first (siteset/discover
                        (into []
                              (mapcat #(ld/snapshot-datoms (:reader w) %))
                              (ld/snapshots (:reader w)))
                        [a] m))
        [w outcome response seen]
        (run-over-dht w :semantic (get-in artifact [:importers :d])
                      (hook-source artifact) noting-waits {:signals []})
        local (dht/local (:reader w))]
    (testing "index loaded: delivered instrumented, canonical and derive
              record named"
      (is (= [true false {:py/out ["m-body" "7"], :py/exception nil}] outcome)
          (pr-str response))
      (is (= m (:yin.link/canonical response)) (pr-str response))
      (is (some? (:yin.link/derivation response)) (pr-str response))
      (is (contains? (:guests seen) "pym.m")))
    (testing "the stage load was the pending entry's manifest, and fetched
              the record and its set"
      (is (contains? (:waits w) (ld/stage-key m)))
      (is (some? ((:get-bytes-fn local) record nil)))
      (is (some? ((:get-bytes-fn local)
                  (:yang.site/set (jing/get local record nil)) nil))))
    w))


(defn- a-hook-import-chain
  "The cold reader imports t, which imports k, under a hook profile:
   t's stage derives bottom-up, fetching k's record and set by the same
   staged load, so both are delivered instrumented, t pinning the k it
   links."
  [w artifact]
  (let [t (get-in artifact [:units "t"])
        k (get-in artifact [:units "k"])
        [w outcome _ _]
        (run-over-dht (dissoc w :responses :waits) :semantic
                      (get-in artifact [:importers :tt])
                      (hook-source artifact) noting-waits {:signals []})
        rebound (filter :yin.link/canonical (:responses w))]
    (testing "both modules are delivered instrumented, no pin mismatch"
      (is (= [true false {:py/out ["k-body" "t-sees 3" "t-done"],
                          :py/exception nil}]
             outcome))
      (is (= #{t k} (set (map :yin.link/canonical rebound))))
      (is (every? :yin.link/derivation rebound))
      (is (not-any? #(= :dependency-pin-mismatch (:reason %))
                    (:responses w))))
    w))


(defn- an-absent-record-is-refused-and-retried
  "A signed entry names a record nobody holds: the stage load fails, the
   import is refused `:absent` naming the record address, the failed load
   is forgotten, and a second require starts a fresh load."
  []
  (let [artifact (fixture/read-file)
        m (get-in artifact [:units "m"])
        bogus (jing/segment-key {:nobody :holds-this-record})
        w (publish-world artifact)
        [w _] (publish-index w [(siteset/signed-entry fixture/key-a m bogus)])
        src (hook-source artifact)
        run (fn [w]
              (run-over-dht w :semantic (get-in artifact [:importers :d]) src
                            noting-waits {:signals []}))
        [w outcome response _] (run w)
        _ (testing "the miss is a refusal naming the record address"
            (is (= "ImportError" (get-in (nth outcome 2)
                                         [:py/exception :type])))
            (is (= :absent (:reason response)) (pr-str response))
            (is (= bogus (:address response)) (pr-str response)))
        _ (testing "the failed stage load is forgotten"
            (is (nil? (ld/stage-status (:reader w) m))))
        [w outcome response _] (run w)]
    (testing "a second require starts a fresh stage load and is refused again"
      (is (= :absent (:reason response)) (pr-str response))
      (is (= "ImportError" (get-in (nth outcome 2) [:py/exception :type]))))
    (dht/close! (:pub w))
    (dht/close! (:reader w))))


(defn- dht-import-test
  []
  (let [lines (fixture/read-lines)
        _ (this-host-publishes-the-artifact lines)
        artifact (fixture/read-file)
        a (get-in artifact [:principals :a])
        src (source [a] [a])
        w (publish-world artifact)
        w (hook-import-refused-without-the-index w artifact)
        w (site-sets-over-the-mesh w artifact)
        w (hook-import-with-the-index w artifact)
        w (a-hook-import-chain w artifact)
        w (imports-over-the-dht w artifact src)
        w (an-untrusted-principal-is-refused-on-every-vm w artifact)
        w (a-trusted-principal-is-allowed-on-every-vm w artifact)
        w (an-allow-binds-the-address-the-question-was-asked-about
            w artifact)
        w (republished-dependency-is-refused w artifact src)]
    (dht/close! (:pub w))
    (dht/close! (:reader w))
    (an-absent-record-is-refused-and-retried)))


(deftest ^:slow dht-import-test-test
  (slow/guard "dht-import-test" dht-import-test))
