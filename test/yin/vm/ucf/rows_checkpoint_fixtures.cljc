(ns yin.vm.ucf.rows-checkpoint-fixtures
  "The shared canonical fixtures of the rows inspector (phase 7a, plan
   §3.3, §4 7a), pinned by their pack addresses, loaded on the JVM, Node
   and Dart.

   The five accepted bases are packs rows/lift-task itself emitted under
   a custody header from real semantic-register machines
   (yin.vm.ucf.rows-support), the old checkpoint fixtures' five bases
   re-lifted. Nothing about a base is hand-built but its operation ids
   (`rows-support/with-op-id`). Every other fixture is a mutation of a
   named base: a task edit (decode, change the abstract task, `rows/emit`,
   so every row it touches is rehashed and the pack stays canonical), or a
   raw edit of the pack where the defect is the carrier's own (version,
   tag, row order, a row changed without rehash, a frame root). A
   mutation names the refusal it must draw, as a map the refusal must
   contain, computed from its base where a path depends on it.

   The pins live in test/resources/yin/vm/ucf/rows-checkpoint-v3.txt, one
   block per fixture separated by a blank line: the name and the pack
   address of its bytes. Three anchors (`anchors`) also keep their full
   bytes as lowercase hex in lines of 64 digits, so the inspector reads
   bytes this build did not produce; every other fixture's bytes are built
   from its pack and checked against its pinned address. Regenerate the
   file from a JVM REPL with the string `render` returns, saved to `path`;
   the test proves on every host that the file equals `render`.

   The test helpers below (`base`, `edit-task`, `edit-custody`, `enc`,
   the wait and child accessors) are what the authority's tests use to
   make their own packs from these bases."
  (:require [clojure.string :as str]
            [dao.jing :as jing]
            [dao.jing.cbor :as cbor]
            [dao.jing.cbor-fixtures :as fx]
            [yin.vm.ucf.rows :as rows]
            [yin.vm.ucf.rows-support :as s]))


(def path
  "The fixture file, relative to the repository root."
  "test/resources/yin/vm/ucf/rows-checkpoint-v3.txt")


(def max-exact rows/max-exact)

(def occurrence s/occurrence)
(def predecessor s/predecessor)
(def origin s/origin)
(def arbitration s/arbitration)
(def op-id s/op-id)


;; =============================================================================
;; Accepted bases: real lifts
;; =============================================================================

(defn- lifted
  [machine header]
  (let [w (s/lift machine header)]
    (when (contains? w :yin.k/status)
      (throw (ex-info "A fixture base failed to lift" w)))
    w))


(def ^:private base-packs
  (delay
    {"first-park" (lifted (s/parked-reader) (s/header 0 nil #{}))
     "successor" (lifted (s/with-op-id (s/parked-writer) :put (op-id 0)) (s/header 3 origin #{"s0"}))
     ;; a root waiting on an install whose child is blocked on a write
     ;; drawing id 4 from the root's sequence
     "installs" (lifted (assoc-in (s/parked-installer) [:installs 'host.mod :vm]
                                  (s/with-op-id (s/parked-writer) :put (op-id 4)))
                        (s/header 6 origin #{"s0"}))
     "parked" (lifted (s/parked-explicit) (s/header 1))
     "halted-root" (lifted (s/halted-machine) (s/header 0 origin #{}))}))


(def base-names ["first-park" "successor" "installs" "parked" "halted-root"])


(defn base
  "The pack of accepted base n."
  [n]
  (get @base-packs n))


;; =============================================================================
;; Editing packs
;; =============================================================================

(defn edit-task
  "wire's root task through f, re-emitted: every changed row rehashed."
  [wire f]
  (rows/emit (f (rows/decode wire))))


(defn edit-custody
  "wire with its root's custody slot through f."
  [wire f]
  (edit-task wire #(update-in % [:slots 16] f)))


(defn custody
  "The custody slot of wire's root."
  [wire]
  (nth (:slots (rows/decode wire)) 16))


(defn waits
  [task]
  (nth (:slots task) 13))


(defn with-waits
  [task ws]
  (assoc-in task [:slots 13] (vec ws)))


(defn pending-of
  [wait]
  (second (:slots wait)))


(defn reason-of
  [wait]
  (first (:slots (pending-of wait))))


(defn wait-index
  "The index of task's first wait whose pending reason is reason."
  [task reason]
  (or (first (keep-indexed #(when (= reason (reason-of %2)) %1) (waits task)))
      (throw (ex-info "No wait of that reason" {:reason reason}))))


(defn set-pending-slot
  "wait with slot i of its pending row set to x."
  [wait i x]
  (assoc-in wait [:slots 1 :slots i] x))


(defn set-op-id
  [wait id]
  (set-pending-slot wait 3 id))


(defn update-put
  "task with its first :put wait through f."
  [task f]
  (update-in task [:slots 13 (wait-index task :put)] f))


(defn install-index
  [task mn]
  (or (first (keep-indexed #(when (= mn (first (:slots %2))) %1) (nth (:slots task) 12)))
      (throw (ex-info "No install of that name" {:name mn}))))


(defn child
  [task mn]
  (nth (:slots (nth (nth (:slots task) 12) (install-index task mn))) 4))


(defn update-child
  [task mn f]
  (update-in task [:slots 12 (install-index task mn) :slots 4] f))


(defn segment-address
  "The BLAKE3 segment address of host bytes."
  [bs]
  (keyword "segment" (str "blake3-" (jing/digest-bytes :blake3 bs))))


(defn enc
  "A pack's canonical bytes and its pack address."
  [wire]
  (let [bs (cbor/encode wire)]
    {:address (segment-address bs) :bytes bs}))


;; =============================================================================
;; Mutations: the fixture is (f base)
;; =============================================================================
;;
;; :refusal is a function of the base to the map the inspector's refusal
;; must contain; an accepted variant names none.

(defn- m
  ([n b f] {:name n :base b :f f})
  ([n b f refusal] {:name n :base b :f f :refusal refusal}))


(defn- custody-set
  [k x]
  #(edit-custody % (fn [c] (assoc c k x))))


(defn- custody-drop
  [k]
  #(edit-custody % (fn [c] (dissoc c k))))


(defn- undecodable
  [kind p]
  (constantly {:yin.k/status :yin.k/undecodable :yin.k/kind kind :yin.k/path p}))


(defn- put-op
  "The path of base wire's first :put wait's id, with tail appended."
  [wire & tail]
  (into [:frames (wait-index (rows/decode wire) :put) :op-id] tail))


(defn- child-put-op
  [wire & tail]
  (into [:installs 'host.mod :frames (wait-index (child (rows/decode wire) 'host.mod) :put) :op-id] tail))


(defn- edit-op-id
  [f]
  #(edit-task % (fn [t] (update-put t (fn [w] (set-op-id w (f (nth (:slots (pending-of w)) 3))))))))


(def ^:private origin-path [:custody :yin.k/origin])


(def ^:private mutations
  [;; accepted: variants of the successor
   ;; the same rows in another order: one root, another pack address
   (m "variant-equal" "successor" #(update % :yin.k/rows (comp vec reverse)))
   ;; another id counter: another root, the same custody and ids
   (m "variant-other-root" "successor" #(edit-task % (fn [t] (assoc-in t [:slots 4] 9))))
   (m "variant-different-intent" "successor"
      #(edit-task % (fn [t] (update-put t (fn [w] (set-pending-slot w 2 [:lit "w"]))))))
   (m "variant-counter" "successor" (custody-set :yin.k/next-op-seq 4))
   (m "counter-at-bound" "successor"
      #(-> %
           (edit-custody (fn [c] (assoc c :yin.k/next-op-seq max-exact)))
           (edit-task (fn [t] (update-put t (fn [w] (set-op-id w (op-id (dec max-exact)))))))))
   ;; bytes, gate and carrier
   (m "hash-mismatch" "successor" identity)
   (m "non-canonical" "successor" identity)
   (m "version-float" "successor" #(assoc % :yin.k/version (cbor/float64 3))
      (constantly {:yin.k/status :yin.k/profile-mismatch :yin.k/kind :version}))
   (m "version-2" "successor" #(assoc % :yin.k/version 2)
      (constantly {:yin.k/status :yin.k/profile-mismatch :yin.k/kind :version}))
   (m "version-absent" "successor" #(dissoc % :yin.k/version)
      (constantly {:yin.k/status :yin.k/profile-mismatch :yin.k/kind :version}))
   (m "no-tag" "successor" #(dissoc % :yin.k/handoff)
      (constantly {:yin.k/status :yin.k/profile-mismatch :yin.k/kind :version}))
   ;; a pack rooted at the successor's wait, not at a task
   (m "frame-root" "successor"
      #(rows/emit-numbered (nth (waits (rows/decode %)) 0) {})
      (undecodable :root [:yin.k/root]))
   ;; the store row's value changed, its id kept
   (m "row-tampered" "successor"
      (fn [w]
        (update w :yin.k/rows
                (fn [rs] (mapv #(if (= :store (second %)) (conj % 'tampered [:lit 1]) %) rs))))
      (constantly {:yin.k/status :yin.k/hash-mismatch :yin.k/kind :row}))
   (m "bad-kind" "successor" #(edit-task % (fn [t] (assoc-in t [:slots 2] :running)))
      (undecodable :kind [:kind]))
   (m "root-role" "successor" #(edit-task % (fn [t] (assoc-in t [:slots 3] :child)))
      (undecodable :role [:role]))
   ;; root custody
   (m "missing-policy" "successor" (custody-drop :yin.k/policy)
      (undecodable :missing-header [:custody :yin.k/policy]))
   (m "missing-occurrence" "successor" (custody-drop :yin.k/occurrence)
      (undecodable :missing-header [:custody :yin.k/occurrence]))
   (m "missing-arbitration" "successor" (custody-drop :yin.k/arbitration)
      (undecodable :missing-header [:custody :yin.k/arbitration]))
   (m "missing-next-op-seq" "successor" (custody-drop :yin.k/next-op-seq)
      (undecodable :missing-header [:custody :yin.k/next-op-seq]))
   ;; the draft's explicit fork spelling, not the ruled nil
   (m "fork-policy" "successor" (custody-set :yin.k/policy :yin.k/fork)
      (undecodable :policy [:custody :yin.k/policy]))
   (m "nil-occurrence" "successor" (custody-set :yin.k/occurrence nil)
      (undecodable :nil-occurrence [:custody :yin.k/occurrence]))
   (m "occurrence-not-uuid" "successor" (custody-set :yin.k/occurrence "O")
      (undecodable :malformed-occurrence [:custody :yin.k/occurrence]))
   (m "origin-occurrence-not-uuid" "successor" (custody-set :yin.k/origin (assoc origin :yin.k/occurrence "O"))
      (undecodable :malformed-origin origin-path))
   (m "malformed-arbitration" "successor" (custody-set :yin.k/arbitration (dissoc arbitration :dao.stream/descriptor))
      (undecodable :malformed-arbitration [:custody :yin.k/arbitration]))
   (m "origin-is-self" "successor" (custody-set :yin.k/origin (assoc origin :yin.k/occurrence occurrence))
      (undecodable :origin-is-self (conj origin-path :yin.k/occurrence)))
   (m "malformed-origin" "successor" (custody-set :yin.k/origin (dissoc origin :dao.lease/lease))
      (undecodable :malformed-origin origin-path))
   (m "origin-nil-emitter" "successor" (custody-set :yin.k/origin (assoc origin :yin.k/emitter nil))
      (undecodable :malformed-origin origin-path))
   (m "counter-float" "successor" (custody-set :yin.k/next-op-seq (cbor/float64 3))
      (undecodable :inexact [:custody :yin.k/next-op-seq]))
   (m "counter-over-bound" "successor" (custody-set :yin.k/next-op-seq (inc max-exact))
      (undecodable :inexact [:custody :yin.k/next-op-seq]))
   (m "counter-negative" "successor" (custody-set :yin.k/next-op-seq -1)
      (undecodable :inexact [:custody :yin.k/next-op-seq]))
   (m "first-export-counter" "first-park" (custody-set :yin.k/next-op-seq 1)
      (undecodable :first-export-counter [:custody :yin.k/next-op-seq]))
   (m "halted-root-header" "halted-root" (custody-set :yin.k/occurrence occurrence)
      (undecodable :halted-header [:custody :yin.k/occurrence]))
   (m "halted-root-without-origin" "halted-root" (custody-drop :yin.k/origin)
      (undecodable :missing-header origin-path))
   ;; lease, epoch and holder never enter a body (UCF 7.2.1)
   (m "custody-epoch" "successor" (custody-set :yin.k/epoch 0)
      (undecodable :custody-key [:custody :yin.k/epoch]))
   (m "custody-lease" "successor" (custody-set :dao.lease/lease "lease-7")
      (undecodable :custody-key [:custody :dao.lease/lease]))
   (m "custody-not-map" "successor" #(edit-custody % (constantly :yin.k/exclusive))
      (undecodable :malformed-custody [:custody]))
   ;; the child role and install completeness
   (m "child-header" "installs"
      #(edit-task % (fn [t] (update-child t 'host.mod (fn [c] (assoc-in c [:slots 16] {:yin.k/origin origin})))))
      (undecodable :child-header [:installs 'host.mod :custody]))
   (m "install-without-entry" "installs" #(edit-task % (fn [t] (assoc-in t [:slots 12] [])))
      (fn [w]
        {:yin.k/status :yin.k/undecodable :yin.k/kind :incomplete-install
         :yin.k/path [:frames (wait-index (rows/decode w) :install)]}))
   ;; operation ids
   (m "op-id-at-counter" "successor" (edit-op-id #(assoc % :yin.k/seq 3))
      (fn [w] {:yin.k/status :yin.k/undecodable :yin.k/kind :op-seq-range :yin.k/path (put-op w :yin.k/seq)}))
   (m "child-op-id-out-of-range" "installs"
      #(edit-task % (fn [t]
                      (update-child t 'host.mod
                                    (fn [c] (update-put c (fn [w] (set-op-id w (op-id 6))))))))
      (fn [w]
        {:yin.k/status :yin.k/undecodable :yin.k/kind :op-seq-range
         :yin.k/path (child-put-op w :yin.k/seq)}))
   ;; the successor's write carried twice: the same id twice
   (m "duplicate-op-id" "successor"
      #(edit-task % (fn [t] (with-waits t (into (waits t) (waits t)))))
      (fn [w]
        {:yin.k/status :yin.k/undecodable :yin.k/kind :duplicate-op-id
         :yin.k/path [:frames (+ (count (waits (rows/decode w))) (wait-index (rows/decode w) :put)) :op-id]}))
   (m "op-id-without-origin" "successor"
      #(edit-custody % (fn [c] (-> c (dissoc :yin.k/origin) (assoc :yin.k/next-op-seq 0))))
      (fn [w] {:yin.k/status :yin.k/undecodable :yin.k/kind :op-id-without-origin :yin.k/path (put-op w)}))
   (m "op-id-own-occurrence" "successor" (edit-op-id #(assoc % :yin.k/occurrence occurrence))
      (fn [w] {:yin.k/status :yin.k/undecodable :yin.k/kind :op-id-own-occurrence :yin.k/path (put-op w)}))
   (m "op-id-malformed" "successor" (edit-op-id #(assoc % :yin.k/epoch 0))
      (fn [w] {:yin.k/status :yin.k/undecodable :yin.k/kind :malformed-op-id :yin.k/path (put-op w)}))
   (m "op-id-occurrence-not-uuid" "successor" (edit-op-id #(assoc % :yin.k/occurrence "O"))
      (fn [w] {:yin.k/status :yin.k/undecodable :yin.k/kind :malformed-op-id :yin.k/path (put-op w)}))
   (m "op-id-seq-float" "successor" (edit-op-id #(assoc % :yin.k/seq (cbor/float64 0)))
      (fn [w] {:yin.k/status :yin.k/undecodable :yin.k/kind :inexact :yin.k/path (put-op w :yin.k/seq)}))
   (m "op-id-seq-negative" "successor" (edit-op-id #(assoc % :yin.k/seq -1))
      (fn [w] {:yin.k/status :yin.k/undecodable :yin.k/kind :inexact :yin.k/path (put-op w :yin.k/seq)}))
   ;; a fork (nil slot) carrying an id
   (m "fork-op-id" "successor" #(edit-custody % (constantly nil))
      (fn [w] {:yin.k/status :yin.k/undecodable :yin.k/kind :fork-op-id :yin.k/path (put-op w)}))])


(def ^:private fixtures*
  (delay
    (into @base-packs
          (map (fn [{:keys [name base f]}] [name (f (get @base-packs base))]))
          mutations)))


(defn pack-of
  "Fixture n's pack (for hash-mismatch and non-canonical, the pack whose
   bytes they change)."
  [n]
  (get @fixtures* n))


(def fixture-names
  (into base-names (map :name) mutations))


(def anchors
  "The fixtures whose full bytes the file keeps."
  #{"first-park" "successor" "installs"})


(def accepted
  #{"first-park" "successor" "installs" "parked" "halted-root" "variant-equal" "variant-other-root"
    "variant-different-intent" "variant-counter" "counter-at-bound"})


(defn base-of
  "The base fixture n mutates, or nil for a base."
  [n]
  (:base (first (filter #(= n (:name %)) mutations))))


(defn refusal
  "The map fixture n's refusal must contain, or nil."
  [n]
  (when-some [{b :base r :refusal} (first (filter #(= n (:name %)) mutations))]
    (when r (r (base b)))))


;; =============================================================================
;; Bytes and pins
;; =============================================================================

(defn patch-non-canonical
  "bs with its pack version 3 written in the two-byte form 0x1803, a
   well-formed CBOR item that does not re-encode to itself."
  [bs]
  (let [k (fx/bytes->hex (cbor/encode :yin.k/version))]
    (fx/hex->bytes (str/replace-first (fx/bytes->hex bs) (str k "03") (str k "1803")))))


(defn- built-bytes
  [n]
  (let [bs (cbor/encode (pack-of n))]
    (if (= "non-canonical" n) (patch-non-canonical bs) bs)))


(defn- built-address
  "The address pinned for n: its bytes' address, except hash-mismatch,
   which pins first-park's."
  [n]
  (segment-address (built-bytes (if (= "hash-mismatch" n) "first-park" n))))


(defn render
  "The fixture file's text, computed from the fixtures on this host."
  []
  (str/join "\n"
            (map (fn [n]
                   (let [h (when (contains? anchors n) (fx/bytes->hex (built-bytes n)))]
                     (str n "\n" (subs (str (built-address n)) 1) "\n"
                          (apply str (map #(str (subs h % (min (count h) (+ % 64))) "\n")
                                          (range 0 (count h) 64))))))
                 fixture-names)))


(defn read-file
  "The fixture file as {name {:address a :hex h}}, h nil but for an anchor."
  []
  (let [blocks (remove #(every? str/blank? %)
                       (partition-by str/blank? (str/split-lines (fx/read-path path))))]
    (into {}
          (map (fn [[n a & hex]]
                 [n {:address (keyword "segment" (subs a (count "segment/")))
                     :hex (when (seq hex) (apply str hex))}]))
          blocks)))


(def ^:private file* (delay (read-file)))


(defn fixture
  "Fixture n's pinned address and its bytes: an anchor's from the file,
   non-canonical's patched from the successor anchor's, every other built
   from its pack."
  [n]
  (let [{:keys [address hex]} (get @file* n)
        anchor (fn [a] (fx/hex->bytes (:hex (get @file* a))))]
    {:address address
     :bytes (cond
              (some? hex) (fx/hex->bytes hex)
              (= "non-canonical" n) (patch-non-canonical (anchor "successor"))
              :else (cbor/encode (pack-of n)))}))
