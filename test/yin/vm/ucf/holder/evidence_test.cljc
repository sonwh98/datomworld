(ns yin.vm.ucf.holder.evidence-test
  (:require [clojure.test :refer [deftest is]]
            [dao.jing.cbor :as cbor]
            [dao.jing.mem :as mem]
            [dao.lease :as lease]
            [dao.stream :as stream]
            [dao.stream.journal :as journal]
            [dao.stream.remote :as remote]
            [dao.stream.ringbuffer :as ringbuffer]
            [yin.vm.ucf.authority :as authority]
            [yin.vm.ucf.authority.grant :as grant]
            [yin.vm.ucf.authority.input :as input]
            [yin.vm.ucf.checkpoint-fixtures :as fx]
            [yin.vm.ucf.custody :as custody]
            [yin.vm.ucf.holder.evidence :as evidence]))


(def ^:private arb "arb-d3")

(def ^:private occ fx/occurrence)

(def ^:private duration {:s 30})

(def ^:private s-read {:yin.k/kind :yin.k/read :yin.k/name "s"})


(defn- fresh-frames
  []
  (atom [(cbor/encode {:dao.stream.journal/header
                       {:version 1 :identity arb}})]))


(defn- body
  []
  (assoc (get fx/fixtures "first-park")
         :yin.k/arbitration {:dao.stream/identity arb
                             :dao.stream/descriptor
                             {:dao.stream/type :dao.stream/journal}}))


(defn- records
  [frames]
  (mapv #(:dao.stream.journal/value (cbor/decode %)) (rest @frames)))


(defn- attributed
  ([frames] (attributed frames arb))
  ([frames author] (mapv (fn [r] [author r]) (records frames))))


(defn- read-ok
  [v pos]
  {:dao.stream/outcome :dao.stream/ok
   :dao.stream/value v
   :dao.stream/cursor {:position pos}})


(defn- history
  "An authority with the first-park fixture offered, one target
   enrolled, lease-1 granted to holder-a, two inputs recorded, lease-1
   reclaimed, and lease-2 granted to holder-b: the frames and the
   authority.  lease-2's frontier is 2."
  []
  (let [frames (fresh-frames)
        a (::authority/authority
            (authority/open! (journal/memory-backend frames nil)))
        bs (cbor/encode (body))
        w (grant/writer a)
        g (fn [l h pid]
            (stream/append!
              w (lease/grant l (custody/subject occ) h duration
                             {:dao.lease/proposal pid})))]
    (grant/offer! a (mem/create-content-mem) (fx/segment-address bs) bs
                  "carrier")
    (authority/enroll! a)
    (g "lease-1" "holder-a" "p-1")
    (input/record-input! a "holder-a"
                         (input/request occ "lease-1" 0 0 s-read
                                        (read-ok 7 1)))
    (input/record-input! a "holder-a"
                         (input/request occ "lease-1" 0 1 s-read
                                        (read-ok 8 2)))
    (stream/append! w (lease/lapsed "lease-1" :policy))
    (g "lease-2" "holder-b" "p-2")
    [frames a]))


(defn- unavailable?
  [r]
  (contains? #{:yin.k/unsatisfied :yin.k/awaiting-grant}
             (:yin.k/status r)))


(defn- no-prefix?
  [r]
  (and (unavailable? r)
       (not (contains? r :yin.k/prefix))
       (not (contains? r :yin.k/enrolled))))


;; =============================================================================
;; Equal to the authority's own projection
;; =============================================================================

(deftest a-fold-from-the-origin-equals-the-authoritys-projection
  (let [[frames a] (history)
        p (authority/projection a)
        r (evidence/read-evidence arb (attributed frames) "lease-2")]
    (is (= :yin.k/ready (:yin.k/status r)))
    (is (= (custody/binding-evidence arb (attributed frames) "lease-2")
           (:yin.k/binding r)))
    (is (= 1 (:yin.k/epoch (:yin.k/binding r))))
    (is (= (set (keys (:targets p))) (:yin.k/enrolled r)))
    (is (= 1 (count (:yin.k/enrolled r))))
    (is (= (input/inputs p "lease-2") (:yin.k/prefix r)))
    (is (= 2 (:yin.k/frontier (:yin.k/prefix r))))
    (is (= 0 (:yin.k/frontier
               (:yin.k/prefix
                 (evidence/read-evidence arb (attributed frames)
                                         "lease-1"))))
        "an empty prefix is a real answer once the history is whole")))


(deftest a-lease-the-history-does-not-yet-grant-awaits-its-grant
  (let [[frames _] (history)
        r (evidence/read-evidence arb (attributed frames) "lease-9")]
    (is (= :yin.k/awaiting-grant (:yin.k/status r)))
    (is (no-prefix? r))
    (is (= :yin.k/awaiting-grant
           (:yin.k/status (evidence/read-evidence arb [] "lease-1"))))))


;; =============================================================================
;; Incomplete history is unavailable, never empty
;; =============================================================================

(deftest a-gap-answers-unavailable
  (let [[frames _] (history)
        rs (attributed frames)
        r (evidence/read-evidence arb (into [(first rs)] (drop 2) rs)
                                  "lease-2")]
    (is (= :yin.k/unsatisfied (:yin.k/status r)))
    (is (= :gap (:yin.k/reason r)))
    (is (no-prefix? r))))


(deftest a-start-past-the-origin-answers-unavailable
  (let [[frames _] (history)
        r (evidence/read-evidence arb (rest (attributed frames)) "lease-2")]
    (is (= :yin.k/unsatisfied (:yin.k/status r)))
    (is (= :start-past-origin (:yin.k/reason r)))
    (is (no-prefix? r))))


(deftest a-transport-error-answers-unavailable
  (let [[frames _] (history)
        rs (attributed frames)
        err [arb {:dao.stream/outcome :dao.stream/transport-error}]
        r (evidence/read-evidence arb (conj (vec (butlast rs)) err)
                                  "lease-2")]
    (is (= :yin.k/unsatisfied (:yin.k/status r)))
    (is (= :transport-error (:yin.k/reason r)))
    (is (no-prefix? r))
    (is (= :transport-error
           (:yin.k/reason (evidence/read-evidence arb [err] "lease-1")))
        "even when nothing else was read")))


(deftest a-bare-outcome-item-answers-unavailable
  (let [[frames _] (history)
        rs (attributed frames)
        bare {:dao.stream/outcome :dao.stream/transport-error}
        r (evidence/read-evidence arb (conj (vec rs) bare) "lease-2")]
    (is (= :yin.k/ready
           (:yin.k/status (evidence/read-evidence arb rs "lease-2")))
        "the dense history alone is whole")
    (is (= :yin.k/unsatisfied (:yin.k/status r)))
    (is (= :fold-defect (:yin.k/reason r)))
    (is (no-prefix? r))
    "a bare outcome the driver failed to pair must never read as a
     whole history"))


(deftest a-fold-defect-answers-unavailable
  (let [[frames _] (history)
        rs (attributed frames)
        bad (assoc rs 1 [arb {}])
        r (evidence/read-evidence arb bad "lease-2")]
    (is (= :yin.k/unsatisfied (:yin.k/status r)))
    (is (= :fold-defect (:yin.k/reason r)))
    (is (no-prefix? r))
    (is (= :fold-defect
           (:yin.k/reason
             (evidence/read-evidence arb (assoc rs 1 (rs 0)) "lease-2")))
        "a repeated record is a defect, never skipped")))


;; =============================================================================
;; Authorship
;; =============================================================================

(deftest records-from-another-author-establish-nothing
  (let [[frames _] (history)
        r (evidence/read-evidence arb (attributed frames "mallory") "lease-2")]
    (is (= :yin.k/awaiting-grant (:yin.k/status r)))
    (is (no-prefix? r)))
  (let [[frames _] (history)
        rs (attributed frames)
        mixed (into [] (mapcat (fn [[_ r :as x]] [x ["mallory" r]])) rs)
        r (evidence/read-evidence arb mixed "lease-2")]
    (is (= :yin.k/ready (:yin.k/status r))
        "another author's copies are ignored, not folded into a gap")
    (is (= (:yin.k/prefix
             (evidence/read-evidence arb (attributed frames) "lease-2"))
           (:yin.k/prefix r)))))


;; =============================================================================
;; Through a dao.stream.remote reflection
;; =============================================================================

(defn- ring
  []
  (:dao.stream/handle
    (ringbuffer/create! {:dao.stream/type :dao.stream/ringbuffer
                         :dao.stream.ringbuffer/capacity 64})))


(defn- oldest
  [h]
  (:dao.stream/cursor (stream/cursor h :dao.stream/oldest)))


(defn- outcome
  [r]
  (:dao.stream/outcome r))


(defn- reflect
  [h]
  (let [ab (ring)
        ba (ring)
        cd {:dao.stream/type :dao.stream.test/channel
            :dao.stream/identity "toy-channel"}
        table {arb {:handle h :surface #{:reader}}}
        mirror (atom (oldest ab))
        attach! (remote/attacher
                  {:dao.stream.remote/channels {cd {:reader ba :writer ab}}})]
    {:reflection (:dao.stream/handle
                   (attach! {:dao.stream/type :dao.stream/remote
                             :dao.stream/identity arb
                             :dao.stream/channel cd}))
     :serve! #(swap! mirror (fn [c] (remote/mirror-step table ab c ba)))}))


(defn- ask
  [serve! f]
  (loop [n 0]
    (let [r (f)]
      (if (and (< n 16)
               (or (= :dao.stream/blocked (outcome r))
                   (:dao.stream/retry? r)))
        (do (serve!) (recur (inc n)))
        r))))


(deftest evidence-survives-a-remote-reflection
  (let [[frames a] (history)
        {:keys [reflection serve!]} (reflect (:journal a))
        author (:dao.stream/identity (stream/descriptor reflection))
        read (loop [c (:dao.stream/cursor
                        (ask serve! #(stream/cursor reflection
                                                    :dao.stream/oldest)))
                    acc []]
               (let [r (ask serve! #(stream/next reflection c))]
                 (if (= :dao.stream/ok (outcome r))
                   (recur (:dao.stream/cursor r)
                          (conj acc [author (:dao.stream/value r)]))
                   acc)))
        direct (evidence/read-evidence arb (attributed frames) "lease-2")]
    (is (= arb author))
    (is (= :yin.k/ready (:yin.k/status direct)))
    (is (= direct (evidence/read-evidence arb read "lease-2")))
    (is (= direct (evidence/read-evidence
                    arb (cbor/decode (cbor/encode read)) "lease-2"))
        "and the canonical codec after it")
    (is (= :yin.k/awaiting-grant
           (:yin.k/status
             (evidence/read-evidence
               arb (mapv (fn [[_ r]] ["mallory" r]) read) "lease-2")))
        "the same records attributed to another author prove nothing")))
