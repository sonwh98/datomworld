(ns yang.python.antlr.dht-import-fixture
  "The JVM publication artifact of C4 slice I3's DHT import
   (docs/design/yang.antlr.md 8.5.6): test/resources/yang/python/
   dht-import-v1.edn, written on the JVM by
   test/resources/yang/python/dht-import-v1.generate.clj.

   It holds what a publisher `A` publishes and signs: the guest blobs of
   the units m, k, t and e and of k's republication k2 (every blob of
   their closures but `py`'s, which each reader has installed, and each
   module's site set blob and publication record blob, I7), each
   unit's manifest address, the signed name envelopes of three index
   transactions -- `A` naming py, m, k and t; `A` retracting k and
   naming k2; a foreign principal `B` naming e -- and the importer
   packets the reader lowers, and a fourth index transaction of `A`'s signed
   site-set publication entries (I7). The keys are fixed test seeds, and Ed25519
   signatures are deterministic, so one host's `generate` is the file
   byte for byte.

   `yang.python.antlr.dht-import-test` consumes the file on every host
   without republishing anything, and checks that this host's own
   `generate` equals it: a host whose publication of the same packets
   differs from the JVM's fails there.

   One EDN form per line: the magic, the header maps, then one
   `[address hex]` line per blob in address order."
  (:require
    [clojure.edn :as edn]
    [clojure.string :as str]
    [dao.jing :as jing]
    [dao.jing.cbor-fixtures :as cfx]
    [dao.jing.dht :as jing.dht]
    [dao.jing.mem :as mem]
    [yang.python.antlr.import-programs :as programs]
    [yang.python.antlr.linked-harness :as h]
    [yang.python.antlr.publication :as publication]
    [yang.python.antlr.siteset :as siteset]
    [yin.vm.linker.closure :as closure]
    [yin.vm.linker.publish :as publish]
    [yin.vm.linker.sign :as sign]))


(def path
  "The artifact, relative to the repository root."
  "test/resources/yang/python/dht-import-v1.edn")


(def magic "dht-import-v1")


(def key-a
  "The publisher `A`: a fixed test seed."
  (let [seed (apply str (repeat 32 "a1"))]
    {:seed seed, :public (sign/public-of seed)}))


(def key-b
  "The foreign principal `B`: a fixed test seed."
  (let [seed (apply str (repeat 32 "b2"))]
    {:seed seed, :public (sign/public-of seed)}))


(defn closure-addresses
  "Every blob address of the closure of `manifest` in `store`: the reads
   one closure walk makes."
  [store manifest]
  (let [seen (atom #{})
        get-bytes (:get-bytes-fn store)
        recording (assoc store
                         :get-bytes-fn
                         (fn [address not-found]
                           (swap! seen conj address)
                           (get-bytes address not-found)))]
    (closure/walk recording manifest)
    @seen))


(defn installed-py
  "`py`'s closure as this host published it, `{address bytes}`: the
   Python runtime every reader has installed. It does not travel: three
   of its blobs (the code images, about 1 MB each) exceed the DHT's
   64 KiB message bound, which a `dao.space.dht` node does not let a
   composition raise."
  []
  (let [{:keys [store address]} @h/published]
    (select-keys ((:entries-fn store)) (closure-addresses store address))))


(defn- unit
  [nm pk]
  {:name nm, :packet pk, :package? false})


(defn- assert-names
  "`key`'s assertions binding each `[module address]` of `bound`, at
   consecutive sequences from `seq0`."
  [key seq0 bound]
  (vec (map-indexed (fn [i [n a]]
                      (publish/assertion key {:name n,
                                              :manifest a,
                                              :seq (+ seq0 i)}))
                    bound)))


(defn- signed-only
  [signed]
  (mapv #(select-keys % [:envelope :proof]) signed))


(defn generate
  "This host's artifact: the units published from the committed packets
   into a store holding `py` as this host published it, `A`'s and `B`'s
   envelopes signed over the addresses. Answers `{:lines [...]}`, or the
   first publication refusal as `{:refused r}`."
  []
  (let [py (:address @h/published)
        installed (installed-py)
        store (mem/create-content-mem installed)
        units (publication/publish-units!
                store (h/registry) py
                [(unit "m" programs/m-packet) (unit "k" programs/k-packet)
                 (unit "t" programs/t-packet) (unit "e" programs/e-packet)])
        k2 (publication/publish-units! store (h/registry) py
                                       [(unit "k" programs/k2-packet)])]
    (if-let [refused (some #(when (not= :ok (:status %)) %) [units k2])]
      {:refused refused}
      (let [addresses (:addresses units)
            own (assert-names key-a 1 [['py py]
                                       ['pym.m (get addresses "m")]
                                       ['pym.k (get addresses "k")]
                                       ['pym.t (get addresses "t")]])
            republish [(publish/retraction
                         key-a
                         {:of (jing/segment-key (:envelope (nth own 2))),
                          :seq 5})
                       (publish/assertion
                         key-a
                         {:name 'pym.k,
                          :manifest (get-in k2 [:addresses "k"]),
                          :seq 6})]
            foreign (assert-names key-b 1 [['pym.e (get addresses "e")]])
            ;; I7: A's signed publication entry for each module's site-set
            ;; record, the units' then k's republication
            sites (into []
                        (mapcat (fn [[p addrs]]
                                  (map (fn [[n s]]
                                         (siteset/signed-entry
                                           key-a (get addrs n)
                                           (:record-address s)))
                                       (sort-by key (:sites p)))))
                        [[units addresses] [k2 (:addresses k2)]])
            blobs (sort-by (comp str key)
                           (remove (fn [[a _]] (contains? installed a))
                                   ((:entries-fn store))))]
        {:lines
         (into [magic
                (pr-str {:py py})
                (pr-str {:principals {:a (:public key-a),
                                      :b (:public key-b)}})
                (pr-str {:units addresses,
                         :republished {"k" (get-in k2 [:addresses "k"])}})
                (pr-str {:importers {:d programs/d-packet,
                                     :tt programs/tt-packet,
                                     :x programs/x-packet}})
                (pr-str {:index :own, :signed (signed-only own)})
                (pr-str {:index :republish, :signed (signed-only republish)})
                (pr-str {:index :foreign, :signed (signed-only foreign)})
                (pr-str {:index :sites, :signed sites})]
               (map (fn [[a bs]] (pr-str [a (jing.dht/bytes->hex bs)])))
               blobs)}))))


(defn text
  "The artifact text of `lines`: one per line, newline terminated."
  [lines]
  (str (str/join "\n" lines) "\n"))


(defn read-lines
  "The artifact's lines, blank ones dropped (Dart keeps a trailing one)."
  []
  (vec (remove str/blank? (str/split-lines (cfx/read-path path)))))


(defn parse
  "Artifact `lines` as data, `{:py :principals :units :republished
   :importers :indexes {kind [signed ...]} :blobs {address hex}}`: what
   two artifacts are compared by, since a host prints a map's entries in
   its own order."
  [lines]
  (let [[m & forms] lines]
    (when-not (= magic m)
      (throw (ex-info "not a dht-import-v1 artifact" {:path path})))
    (reduce (fn [acc line]
              (let [form (edn/read-string line)]
                (cond
                  (vector? form) (assoc-in acc [:blobs (first form)]
                                           (second form))
                  (:index form) (assoc-in acc [:indexes (:index form)]
                                          (:signed form))
                  :else (merge acc form))))
            {:blobs {}, :indexes {}}
            forms)))


(defn read-file
  "The checked-in artifact, `parse`'s data with each blob as host
   bytes."
  []
  (update (parse (read-lines))
          :blobs
          (fn [blobs]
            (into {} (map (fn [[a hex]] [a (jing.dht/hex->bytes hex)]))
                  blobs))))
