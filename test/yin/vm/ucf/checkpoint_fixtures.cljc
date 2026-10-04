(ns yin.vm.ucf.checkpoint-fixtures
  "The shared canonical fixtures of M-next C slice C4: hand-built
   version-1 handoff bodies (UCF 7.2.1, 7.4.3, 7.7.8), pinned by their
   segment addresses, loaded on the JVM, Node and Dart.

   The pins live in test/resources/yin/vm/ucf/checkpoint-v1.txt, one
   block per fixture separated by a blank line: the name and the
   segment address of its bytes.  Three anchors (`anchors`) also keep
   their full bytes as lowercase hex in lines of 64 digits, so the
   inspector reads bytes this build did not produce.  Every other
   fixture's bytes are built from its body and checked against its
   pinned address; `non-canonical` patches the `successor` anchor's
   bytes.  Stage D's version-1 `validate-body` must answer the same
   outcome on the same bytes.  Regenerate the file from a JVM REPL with
   the string `render` returns, saved to `path`; the test proves on
   every host that the file equals `render`.

   Every fixture but the accepted bases is a mutation of a named base,
   so when D regenerates a base through a real export, the fixtures
   derived from it follow without being rewritten.

   The bodies are custody fixtures, not restorable machines: code,
   registers and cells are shaped like the 7.2.1 grammar but carry a
   placeholder segment, so only the inspector's rules are meaningful
   over them."
  (:require [clojure.string :as str]
            [dao.jing :as jing]
            [dao.jing.cbor :as cbor]
            [dao.jing.cbor-fixtures :as fx]))


(def path
  "The fixture file, relative to the repository root."
  "test/resources/yin/vm/ucf/checkpoint-v1.txt")


(def max-exact 4503599627370495)


;; =============================================================================
;; Parts
;; =============================================================================

(def ^:private segment "segment-placeholder")


(defn- marker
  [identity]
  {:yin.k/tag :yin.k/stream
   :dao.stream/identity identity
   :dao.stream/descriptor {:dao.stream/type :remote :peer "peer-a"}})


(defn- registers
  [pc]
  {:yin.k/segment segment :yin.k/pc pc :yin.k/env {} :yin.k/stack []
   :yin.k/k []})


(defn- frame
  [pending]
  {:yin.k/registers (registers 0) :yin.k/pending pending})


(defn- literal
  [& entries]
  {:yin.k/tag :yin.k/literal :yin.k/entries (vec entries)})


(def occurrence "8f0c6a52-6a1e-4e43-9d55-3f0a4c1b2e01")
(def predecessor "1d3e5b7a-0c2f-4a69-8b11-7e6d5c4b3a02")


(def origin
  {:yin.k/occurrence predecessor
   :dao.lease/lease "lease-7"
   :yin.k/emitter "peer-a"})


(def arbitration
  {:dao.stream/identity "arbitration-1"
   :dao.stream/descriptor {:dao.stream/type :remote :peer "peer-b"}})


(defn op-id
  ([n] (op-id predecessor n))
  ([occ n] {:yin.k/occurrence occ :yin.k/seq n}))


(defn- put
  ([identity value] {:yin.k/reason :put :yin.k/stream (marker identity)
                     :yin.k/value value})
  ([identity value id] (assoc (put identity value) :yin.k/op-id id)))


(def ^:private next-pending {:yin.k/reason :next :yin.k/cell :yin.k/c-0})


(def ^:private ffi-request
  {:yin.k/reason :ffi-request
   :yin.k/call-id :call-7
   :yin.k/request-envelope (literal :dao.stream.apply/id :call-7
                                    :dao.stream.apply/op :op/add)
   :yin.k/request-op :op/add
   :yin.k/request-args [1 2]
   :yin.k/request (marker "call-out")
   :yin.k/response (marker "call-in")
   :yin.k/response-cell :yin.k/c-1})


(def ^:private link-request
  {:yin.k/reason :link-request
   :yin.k/link-id [:t0 7]
   :yin.k/name 'foo
   :yin.k/envelope (literal :yin.link/id [:t0 7] :yin.link/name 'foo)
   :yin.k/request (marker "link-out")
   :yin.k/response (marker "link-in")
   :yin.k/cell :yin.k/c-2})


(defn- install-pending
  [m]
  {:yin.k/reason :install :yin.k/name m})


(defn- cells
  [& ids]
  (into {}
        (map (fn [cid]
               [cid {:yin.k/stream (marker (str "cell-" (name cid)))
                     :yin.k/position 0}]))
        ids))


(defn- body
  "A version-1 body of `kind` with the 7.2.1 base keys and no header."
  [kind]
  {:yin.k/handoff true
   :yin.k/version 1
   :yin.k/kind kind
   :yin.k/contract {:yin.code/contract "v3" :yin.k/version 0}
   :yin.k/id-counter 0
   :yin.k/parked {}
   :yin.k/store {}
   :yin.k/module-stores {}
   :yin.k/cells {}
   :yin.k/requires {:yin.k/cursor-profiles #{:dao.stream.remote/v1}
                    :yin.k/segments #{segment}}
   :yin.k/code {segment []}})


(defn- header
  ([b n] (header b n origin))
  ([b n o]
   (cond-> (assoc b
                  :yin.k/policy :yin.k/exclusive
                  :yin.k/occurrence occurrence
                  :yin.k/arbitration arbitration
                  :yin.k/next-op-seq n)
     (some? o) (assoc :yin.k/origin o))))


(defn- install
  [child]
  {:yin.k/phase :running
   :yin.k/parent [:t0 7]
   :yin.k/response {:manifest {:yin.module/name 'foo}}
   :yin.k/child child})


;; =============================================================================
;; Accepted bases
;; =============================================================================

(def ^:private child
  "A blocked install child drawing id 4 from its root, holding a
   grandchild that draws id 5."
  (-> (body :blocked)
      (assoc :yin.k/frames [(frame (install-pending 'bar))
                            (frame (put "child-out" :x (op-id 4)))]
             :yin.k/installs
             {'bar (install (assoc (body :blocked)
                                   :yin.k/frames
                                   [(frame (put "grandchild-out" :y
                                                (op-id 5)))]))})))


(def ^:private base-bodies
  {"first-park"
   (-> (body :blocked)
       (header 0 nil)
       (assoc :yin.k/cells (cells :yin.k/c-0)
              :yin.k/frames [(frame (put "out" 1)) (frame next-pending)]))

   "successor"
   (-> (body :blocked)
       (header 3)
       (assoc :yin.k/cells (cells :yin.k/c-1 :yin.k/c-2)
              :yin.k/frames [(frame (put "out" (literal :a 1) (op-id 0)))
                             (frame (assoc ffi-request
                                           :yin.k/op-id (op-id 1)))
                             (frame (assoc link-request
                                           :yin.k/op-id (op-id 2)))]))

   ;; a blocked child with a grandchild under 'foo, a halted child with
   ;; no header under 'baz
   "installs"
   (-> (body :blocked)
       (header 6)
       (assoc :yin.k/frames [(frame (install-pending 'foo))
                             (frame (install-pending 'baz))
                             (frame (put "out" 1 (op-id 0)))]
              :yin.k/installs {'foo (install child)
                               'baz (install (assoc (body :halted)
                                                    :yin.k/result 42))}))

   "parked"
   (-> (body :parked)
       (header 1)
       (assoc :yin.k/parked {:p-1 (registers 3)}
              :yin.k/parked-id :p-1))

   "halted-root"
   (assoc (body :halted) :yin.k/origin origin :yin.k/result 42)})


;; =============================================================================
;; Mutations: [name base f], the fixture is (f base)
;; =============================================================================

(def ^:private foo-child [:yin.k/installs 'foo :yin.k/child])
(def ^:private baz-child [:yin.k/installs 'baz :yin.k/child])
(def ^:private put-id [:yin.k/frames 0 :yin.k/pending :yin.k/op-id])


(defn- set-in
  [p v]
  #(assoc-in % p v))


(def ^:private mutations
  [;; accepted
   ["variant-equal" "successor"
    #(assoc % :yin.k/id-counter 9 :yin.k/store {:k (literal :a 2)})]
   ["variant-different-intent" "successor"
    (set-in [:yin.k/frames 0 :yin.k/pending :yin.k/value] (literal :a 2))]
   ["variant-counter" "successor" (set-in [:yin.k/next-op-seq] 4)]
   ["counter-at-bound" "successor"
    #(-> % (assoc :yin.k/next-op-seq max-exact)
         (assoc-in put-id (op-id (dec max-exact))))]
   ;; bytes and gate; hash-mismatch and non-canonical change the bytes
   ["hash-mismatch" "successor" identity]
   ["non-canonical" "successor" identity]
   ["version-float" "successor" (set-in [:yin.k/version] (cbor/float64 1))]
   ["version-2" "successor" (set-in [:yin.k/version] 2)]
   ["version-absent" "successor" #(dissoc % :yin.k/version)]
   ["version-0" "successor" (set-in [:yin.k/version] 0)]
   ["no-tag" "successor" #(dissoc % :yin.k/handoff)]
   ["bad-kind" "successor" (set-in [:yin.k/kind] :running)]
   ["mixed-child" "installs" (set-in (conj foo-child :yin.k/version) 0)]
   ;; root header
   ["missing-policy" "successor" #(dissoc % :yin.k/policy)]
   ["missing-occurrence" "successor" #(dissoc % :yin.k/occurrence)]
   ["missing-arbitration" "successor" #(dissoc % :yin.k/arbitration)]
   ["missing-next-op-seq" "successor" #(dissoc % :yin.k/next-op-seq)]
   ["fork-policy" "successor" (set-in [:yin.k/policy] :yin.k/fork)]
   ["nil-occurrence" "successor" (set-in [:yin.k/occurrence] nil)]
   ["occurrence-not-uuid" "successor" (set-in [:yin.k/occurrence] "O")]
   ["origin-occurrence-not-uuid" "successor"
    (set-in [:yin.k/origin :yin.k/occurrence] "O")]
   ["malformed-arbitration" "successor"
    #(update % :yin.k/arbitration dissoc :dao.stream/descriptor)]
   ["origin-is-self" "successor"
    (set-in [:yin.k/origin :yin.k/occurrence] occurrence)]
   ["malformed-origin" "successor"
    #(update % :yin.k/origin dissoc :dao.lease/lease)]
   ["origin-nil-emitter" "successor"
    (set-in [:yin.k/origin :yin.k/emitter] nil)]
   ["counter-float" "successor"
    (set-in [:yin.k/next-op-seq] (cbor/float64 3))]
   ["counter-over-bound" "successor"
    (set-in [:yin.k/next-op-seq] (inc max-exact))]
   ["counter-negative" "successor" (set-in [:yin.k/next-op-seq] -1)]
   ["first-export-counter" "first-park" (set-in [:yin.k/next-op-seq] 1)]
   ["halted-root-header" "halted-root"
    (set-in [:yin.k/occurrence] occurrence)]
   ["halted-root-without-origin" "halted-root" #(dissoc % :yin.k/origin)]
   ;; child role and install completeness
   ["child-header" "installs"
    (set-in (conj foo-child :yin.k/occurrence) occurrence)]
   ["halted-child-origin" "installs"
    (set-in (conj baz-child :yin.k/origin) origin)]
   ["install-without-entry" "installs"
    #(update % :yin.k/installs dissoc 'baz)]
   ;; operation ids
   ["op-id-on-next" "successor"
    (set-in [:yin.k/frames 1 :yin.k/pending]
            (assoc next-pending :yin.k/op-id (op-id 1)))]
   ["op-id-at-counter" "successor" (set-in put-id (op-id 3))]
   ["child-op-id-out-of-range" "installs"
    (set-in (conj foo-child :yin.k/frames 1 :yin.k/pending :yin.k/op-id)
            (op-id 6))]
   ["duplicate-op-id" "installs"
    (set-in (conj foo-child :yin.k/frames 1 :yin.k/pending :yin.k/op-id)
            (op-id 0))]
   ["op-id-without-origin" "first-park" (set-in put-id (op-id 0))]
   ["op-id-own-occurrence" "successor" (set-in put-id (op-id occurrence 0))]
   ["op-id-occurrence-not-uuid" "successor" (set-in put-id (op-id "O" 0))]
   ["op-id-malformed" "successor"
    (set-in put-id (assoc (op-id 0) :yin.k/epoch 0))]
   ["op-id-seq-float" "successor" (set-in put-id (op-id (cbor/float64 0)))]
   ["op-id-seq-negative" "successor" (set-in put-id (op-id -1))]
   ["park-reason" "successor"
    (set-in [:yin.k/frames 1 :yin.k/pending] {:yin.k/reason :park})]])


(def fixtures
  "Fixture name to its body; for hash-mismatch and non-canonical, the
   body whose bytes they change."
  (into base-bodies
        (map (fn [[n base f]] [n (f (get base-bodies base))]))
        mutations))


(def fixture-names
  (into ["first-park" "successor" "installs" "parked" "halted-root"]
        (map first)
        mutations))


(def anchors
  "The fixtures whose full bytes the file keeps."
  #{"first-park" "successor" "installs"})


;; =============================================================================
;; Bytes and pins
;; =============================================================================

(defn segment-address
  "The BLAKE3 segment address of host bytes."
  [bs]
  (keyword "segment" (str "blake3-" (jing/digest-bytes :blake3 bs))))


(defn patch-non-canonical
  "`bs` with its body version 1 written in the two-byte form 0x1801, a
   well-formed CBOR item that does not re-encode to itself."
  [bs]
  (let [k (fx/bytes->hex (cbor/encode :yin.k/version))]
    (fx/hex->bytes (str/replace-first (fx/bytes->hex bs) (str k "01")
                                      (str k "1801")))))


(defn- built-bytes
  "Fixture `n`'s bytes built from its body on this host."
  [n]
  (let [bs (cbor/encode (get fixtures n))]
    (if (= "non-canonical" n) (patch-non-canonical bs) bs)))


(defn- built-address
  "The address pinned for `n`: its bytes' address, except hash-mismatch,
   which pins first-park's."
  [n]
  (segment-address (built-bytes (if (= "hash-mismatch" n) "first-park" n))))


(defn render
  "The fixture file's text, computed from `fixtures`."
  []
  (str/join "\n"
            (map (fn [n]
                   (let [h (when (contains? anchors n)
                             (fx/bytes->hex (built-bytes n)))]
                     (str n "\n" (subs (str (built-address n)) 1) "\n"
                          (apply str
                                 (map #(str (subs h % (min (count h)
                                                           (+ % 64)))
                                            "\n")
                                      (range 0 (count h) 64))))))
                 fixture-names)))


(defn read-file
  "The fixture file as {name {:address a :hex h}}, `h` nil but for an
   anchor."
  []
  (let [blocks (remove #(every? str/blank? %)
                       (partition-by str/blank?
                                     (str/split-lines (fx/read-path path))))]
    (into {}
          (map (fn [[n a & hex]]
                 [n {:address (keyword "segment"
                                       (subs a (count "segment/")))
                     :hex (when (seq hex) (apply str hex))}]))
          blocks)))


(def ^:private file* (delay (read-file)))


(defn fixture
  "Fixture `n`'s pinned address and its bytes: an anchor's from the
   file, non-canonical's patched from the successor anchor's, every
   other built from its body."
  [n]
  (let [{:keys [address hex]} (get @file* n)
        anchor (fn [m] (fx/hex->bytes (:hex (get @file* m))))]
    {:address address
     :bytes (cond
              (some? hex) (fx/hex->bytes hex)
              (= "non-canonical" n) (patch-non-canonical (anchor "successor"))
              :else (cbor/encode (get fixtures n)))}))
