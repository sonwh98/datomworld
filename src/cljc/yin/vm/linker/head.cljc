(ns yin.vm.linker.head
  "The published head trace and its rule (docs/design/
   yin.vm.linker.dht.head.md, slice H0): a publisher's index HEAD as a
   signed trace, and the pure judgement a reader makes of one.

   A trace is exactly the closed shape of 5.2:

     {:yin.head/envelope {:yin.head/principal \"ed25519:<64 hex>\"
                          :yin.head/manifest  :segment/...
                          :yin.head/seq       n}
      :yin.head/proof    {:yin.head/signature \"<128 hex>\"}}

   signed over `yin.head/trace:v1\\n` and the envelope's canonical bytes
   (`yin.vm.linker.sign/sign-trace`).  The sequence is derived, never
   stored: the greatest transaction `t` among the datoms of the index the
   manifest names (`seq-of`).

   Return shapes this namespace chose where the design leaves them open:
   - `judge` answers one keyword, the outcome of the 5.5 table:
     `:yin.head/malformed`, `:yin.head/wrong-principal`,
     `:yin.head/bad-proof`, `:yin.head/stale`, `:yin.head/equivocation`,
     `:duplicate` or `:candidate`.  Its `principal` is the followed
     principal id, `\"ed25519:\"` and 64 lowercase hex; `floor` is a
     sequence or nil; `installed` is the installed head's manifest
     address, or nil.
   - A sequence is a nonnegative integer no greater than 2^53 - 1, the
     greatest integer every host holds exactly (5.10: the sequence stays
     within 2^53); beyond it a trace is `:yin.head/malformed`.
   - `trace` throws `ex-info` when the key or the trace it would build is
     not well formed; it never answers a malformed trace."
  (:require [clojure.string :as str]
            [dao.jing :as jing]
            [dao.space.index :as index]
            [yin.vm.linker.sign :as sign]))


(def ^:private max-seq
  "2^53 - 1: the greatest integer exact on every host."
  9007199254740991)


(def ^:private hex-digits "0123456789abcdef")


(defn- hex?
  [n s]
  (and (string? s)
       (= n (count s))
       (every? #(some? (str/index-of hex-digits (str %))) s)))


(defn- valid-seq?
  [n]
  (and (integer? n) (<= 0 n max-seq)))


(defn- public-of-principal
  "The 64 hex digits of an `ed25519:` principal id, or nil."
  [principal]
  (when (and (string? principal) (str/starts-with? principal "ed25519:"))
    (let [public (subs principal (count "ed25519:"))]
      (when (hex? 64 public) public))))


(defn- well-formed?
  "The closed shape of 5.2, with a sequence that is a nonnegative
   integer."
  [trace]
  ;; Each value is checked `map?` with its closed key set before anything
  ;; is read from it: a trace comes off a channel and may decode to any
  ;; shape.
  (try
    (boolean
      (and (map? trace)
           (= #{:yin.head/envelope :yin.head/proof} (set (keys trace)))
           (let [envelope (get trace :yin.head/envelope)
                 proof (get trace :yin.head/proof)]
             (and (map? envelope)
                  (= #{:yin.head/principal :yin.head/manifest :yin.head/seq}
                     (set (keys envelope)))
                  (map? proof)
                  (= #{:yin.head/signature} (set (keys proof)))
                  (public-of-principal (get envelope :yin.head/principal))
                  (jing/segment-address? (get envelope :yin.head/manifest))
                  (valid-seq? (get envelope :yin.head/seq))
                  (hex? 128 (get proof :yin.head/signature))))))
    (catch #?(:cljd Object :clj Exception :cljs :default) _ false)))


(defn trace
  "Build and sign the trace of `manifest` at sequence `n` under `key`,
   `{:seed hex :public hex}`.  The principal is the key's own."
  [key manifest n]
  (let [principal (sign/principal (sign/public-of (:seed key)))
        envelope {:yin.head/principal principal
                  :yin.head/manifest manifest
                  :yin.head/seq n}
        built {:yin.head/envelope envelope
               :yin.head/proof (sign/sign-trace (:seed key) envelope)}]
    (when-not (well-formed? built)
      (throw (ex-info "Malformed head trace"
                      {:reason :yin.head/malformed :envelope envelope})))
    built))


(defn- proven?
  [trace]
  (sign/verify-trace (public-of-principal
                       (get-in trace [:yin.head/envelope :yin.head/principal]))
                     (jing/canonical-bytes (:yin.head/envelope trace))
                     (get-in trace [:yin.head/proof :yin.head/signature])))


(defn verify
  "True when `trace` has the closed shape of 5.2 and its signature is its
   principal's head proof over its envelope; false otherwise.  Never
   throws."
  [trace]
  (try
    (boolean (and (well-formed? trace) (proven? trace)))
    (catch #?(:cljd Object :clj Exception :cljs :default) _ false)))


(defn seq-of
  "The greatest transaction `t` among `datoms`; nil for no datoms."
  [datoms]
  (when (seq datoms)
    (reduce max (map index/datom-t datoms))))


(defn judge
  "The rule of 5.5 for one observed trace: a pure function of the
   followed `principal`, the `floor` (the installed head's sequence, or
   nil), the `installed` manifest and the `trace`.  It takes no source
   and no candidate: its answer is the same whoever carried the trace,
   in whatever order, and whatever is loading."
  [principal floor installed trace]
  ;; The shape is checked before any field is read: `judge` never throws,
  ;; whatever a channel decoded the trace to.
  (if-not (well-formed? trace)
    :yin.head/malformed
    (let [{:yin.head/keys [manifest] n :yin.head/seq}
          (:yin.head/envelope trace)]
      (cond
        (not= principal (:yin.head/principal (:yin.head/envelope trace)))
        :yin.head/wrong-principal
        (not (verify trace)) :yin.head/bad-proof
        (nil? floor) :candidate
        (< n floor) :yin.head/stale
        (and (= n floor) (= manifest installed)) :duplicate
        (= n floor) :yin.head/equivocation
        :else :candidate))))
