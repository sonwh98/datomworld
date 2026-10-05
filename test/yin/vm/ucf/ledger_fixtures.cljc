(ns yin.vm.ucf.ledger-fixtures
  "The cross-host ledger fixture of M-next C slice C12: one arbitration
   ledger holding every fact kind of yin.vm.ucf.ledger, built by a
   portable script (`build`) and pinned as its journal frames.

   The pins live in test/resources/yin/vm/ucf/ledger-v1.txt: a first
   line `ledger-v1`, a second `projection <digest>`, the BLAKE3 hex of
   the canonical bytes of the projection the frames fold to, then one
   journal frame per line as lowercase hex, header first.  Regenerate
   the file from a one-off JVM script with the string `render` returns,
   saved to `path`; the test proves on every host that the file equals
   `render`, so every host's rebuild has the pinned bytes.

   Nothing here writes a file."
  (:require [clojure.string :as str]
            [dao.jing :as jing]
            [dao.jing.cbor :as cbor]
            [dao.jing.cbor-fixtures :as cfx]
            [dao.jing.mem :as mem]
            [dao.lease :as lease]
            [dao.stream :as stream]
            [dao.stream.journal :as journal]
            [dao.stream.ringbuffer :as ringbuffer]
            [yin.vm.ucf.authority :as authority]
            [yin.vm.ucf.authority.admission :as admission]
            [yin.vm.ucf.authority.completion :as completion]
            [yin.vm.ucf.authority.grant :as grant]
            [yin.vm.ucf.authority.input :as input]
            [yin.vm.ucf.checkpoint-fixtures :as fx]
            [yin.vm.ucf.custody :as custody]))


(def path
  "The fixture file, relative to the repository root."
  "test/resources/yin/vm/ucf/ledger-v1.txt")


(def arb "arb-c12")

(def duration {:s 30})


;; The root, its continuation successor and a stranger occurrence.
(def r "0c000000-0000-4000-8000-000000000001")

(def s1 "0c000000-0000-4000-8000-000000000002")

(def x "0c000000-0000-4000-8000-000000000004")


;; =============================================================================
;; World: the requests a holder and the composition make
;; =============================================================================

(defn header-frame
  "The journal header frame naming identity `i`."
  ([] (header-frame arb))
  ([i]
   (cbor/encode {:dao.stream.journal/header {:version 1 :identity i}})))


(defn origin
  [o l]
  {:yin.k/occurrence o :dao.lease/lease l :yin.k/emitter "holder-a"})


(defn park
  "A blocked root of occurrence `o` with counter `n` and origin `org`,
   nil for a first park."
  ([o n] (park o n nil))
  ([o n org]
   (cond-> (assoc (get fx/fixtures "first-park")
                  :yin.k/occurrence o
                  :yin.k/next-op-seq n
                  :yin.k/arbitration {:dao.stream/identity arb
                                      :dao.stream/descriptor
                                      {:dao.stream/type :dao.stream/journal}})
     (some? org) (assoc :yin.k/origin org))))


(defn halted
  "A halted result of occurrence `o` under lease `l`."
  [o l]
  (assoc (get fx/fixtures "halted-root") :yin.k/origin (origin o l)))


(defn world
  "A fresh world: the authority's frames (an atom), its content store and
   a diagnostic stream."
  []
  {:frames (atom [(header-frame)])
   :store (mem/create-content-mem)
   :diag (:dao.stream/handle
           (ringbuffer/create! {:dao.stream/type ringbuffer/transport-type
                                ringbuffer/capacity-key 64}))})


(defn offer!
  [{:keys [a store]} b]
  (let [bs (cbor/encode b)]
    (grant/offer! a store (fx/segment-address bs) bs "carrier")))


(defn grant!
  [{:keys [a]} o l h]
  (stream/append! (grant/writer a)
                  (lease/grant l (custody/subject o) h duration
                               {:dao.lease/proposal (str "p-" l)})))


(defn lapse!
  [{:keys [a]} l cause]
  (stream/append! (grant/writer a) (lease/lapsed l cause)))


(defn refuse!
  "The hook's refusal of `proposer`'s proposal pid, through the writer."
  [{:keys [a]} proposer pid]
  (stream/append! (grant/writer a)
                  (assoc (lease/refusal pid) :yin.k/proposer proposer)))


(defn report!
  "holder-a reports body `b` as o's successor under l."
  [{:keys [a]} o l b]
  (let [bs (cbor/encode b)]
    (completion/report! a "holder-a"
                        (completion/resumed o l (fx/segment-address bs))
                        bs)))


(defn envelope
  [l e o n v]
  {:yin.k/envelope :yin.k/fenced-v1
   :yin.k/incarnation l
   :yin.k/epoch e
   :yin.k/op-id {:yin.k/occurrence o :yin.k/seq n}
   :yin.k/value v})


(defn admit!
  [{:keys [a store i diag]} l e o n v]
  (admission/admit! a store i "holder-a" (envelope l e o n v) diag))


(defn record!
  [{:keys [a]} o l e k observed]
  (input/record-input! a "holder-a"
                       (input/request o l e k
                                      {:yin.k/kind :yin.k/read :yin.k/name "s"}
                                      observed)))


(defn read-ok
  [v pos]
  {:dao.stream/outcome :dao.stream/ok
   :dao.stream/value v
   :dao.stream/cursor {:position pos}})


;; =============================================================================
;; The script
;; =============================================================================

(defn answer
  "One request's answer as a status keyword."
  [rep]
  (or (:yin.k/status rep) (:yin.k/admission rep) (:dao.stream/outcome rep)))


(def script-answers
  "The answer each step of `build` gives, in order."
  [:committed                           ; enroll target i
   :committed                           ; offer r
   :dao.stream/ok                       ; grant lease-1 on r
   :recorded                            ; input 0 of r
   :committed                           ; admit (r 0)
   :dao.stream/ok                       ; refuse holder-b's p-b
   :dao.stream/ok                       ; reclaim lease-1, epoch 1
   :dao.stream/ok                       ; grant lease-2 on r
   :committed                           ; report s1
   :dao.stream/ok                       ; release lease-2: r completes
   :committed                           ; offer s1
   :dao.stream/ok                       ; grant lease-3 on s1
   :committed                           ; report a halted result
   :dao.stream/ok                       ; release lease-3: s1 terminates
   :committed                           ; offer x
   :dao.stream/ok                       ; grant lease-4 on x
   :committed                           ; admit (x 0) :v
   :intent-conflict                     ; admit (x 0) :w: x quarantined
   :committed])                         ; close target i


(defn build
  "Run the script on a fresh memory-backed authority.  Answers the world
   with `:answers`, one per step, and the authority, left open."
  []
  (let [w0 (world)
        a (::authority/authority
            (authority/open! (journal/memory-backend (:frames w0) nil)))
        enrolled (authority/enroll! a)
        w (assoc w0 :a a :i (:yin.k/target enrolled))
        steps [#(offer! w (park r 0))
               #(grant! w r "lease-1" "holder-a")
               #(record! w r "lease-1" 0 0 (read-ok 7 1))
               #(admit! w "lease-1" 0 r 0 :v)
               #(refuse! w "holder-b" "p-b")
               #(lapse! w "lease-1" :policy)
               #(grant! w r "lease-2" "holder-a")
               #(report! w r "lease-2" (park s1 3 (origin r "lease-2")))
               #(lapse! w "lease-2" :release)
               #(offer! w (park s1 3 (origin r "lease-2")))
               #(grant! w s1 "lease-3" "holder-a")
               #(report! w s1 "lease-3" (halted s1 "lease-3"))
               #(lapse! w "lease-3" :release)
               #(offer! w (park x 0))
               #(grant! w x "lease-4" "holder-a")
               #(admit! w "lease-4" 0 x 0 :v)
               #(admit! w "lease-4" 0 x 0 :w)
               #(authority/close-target! a (:i w))]]
    (assoc w :answers (into [(answer enrolled)] (map #(answer (%))) steps))))


;; =============================================================================
;; Rendering and reading
;; =============================================================================

(defn digest
  "The BLAKE3 hex of the canonical bytes of projection `p`."
  [p]
  (jing/digest-bytes :blake3 (cbor/encode p)))


(defn hex
  [bs]
  (cfx/bytes->hex bs))


(defn render
  "The fixture file's text, computed from `build` on this host."
  []
  (let [{:keys [a frames]} (build)]
    (str "ledger-v1\n"
         "projection " (digest (authority/projection a)) "\n"
         (str/join (map #(str (hex %) "\n") @frames)))))


(defn read-file
  "The fixture file as `{:digest d :frames [bytes ...]}`."
  []
  (let [[magic pin & frames] (remove str/blank?
                                     (str/split-lines (cfx/read-path path)))]
    (when-not (= "ledger-v1" magic)
      (throw (ex-info "not a ledger-v1 fixture" {:path path})))
    {:digest (subs pin (count "projection "))
     :frames (mapv cfx/hex->bytes frames)}))


(defn read-text
  "The fixture file's text."
  []
  (cfx/read-path path))
