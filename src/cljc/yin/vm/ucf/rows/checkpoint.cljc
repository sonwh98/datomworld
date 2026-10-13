(ns yin.vm.ucf.rows.checkpoint
  "The rows inspector of phase 7a (phase-7 plan §3.3): the custody grammar
   of a version-3 row pack, checked on its canonical bytes, and the
   operation baseline it carries. It replaces yin.vm.ucf.checkpoint at the
   authority's three call sites (grant/offer!, completion/report!,
   admission's accepted-baseline); the version-1/2 inspector stays for the
   old codec's tests until phase 8.

   A pack has two identities. The **pack address** is the BLAKE3 segment
   address of the pack's canonical bytes: the storage key, the one a
   dao.jing content store verifies on put, and the ledger's :yin.k/id. The
   **root id** is the :task row's own content address inside the pack: the
   semantic identity of the transported state, which `emit` makes one pack
   per root on every host by sorting the rows. The baseline names the root
   id as :yin.k/root; the address is the caller's.

   `inspect` checks, in this order, and the first to fail decides: the
   address against the bytes (:yin.k/hash-mismatch carrying the claimed
   :yin.k/address and the :yin.k/computed one), one canonical CBOR item
   (:yin.k/undecodable :bytes), the carrier itself through rows/decode
   (gate, structure, graph, every row hash, canonical form and cursor-cell
   numbering, frames: the codec's own refusal), a :task root, and then
   rows/custody-baseline: the root's custody slot by kind, every task's
   kind and role, every :install pending's row, a nil slot on every child,
   and every operation id on a :put, :ffi-request or :link-request pending
   of the root and its children against the root's occurrence, origin and
   counter, duplicates refused. A nil slot is a fork: no id may be carried
   and the baseline says :yin.k/fork true.

   Answers the operation baseline

     {:yin.k/kind        k
      :yin.k/root        B          ; the root id
      :yin.k/occurrence  O          ; absent on a halted root and a fork
      :yin.k/origin      {...}      ; absent on a first park and a fork
      :yin.k/arbitration {...}      ; absent on a halted root and a fork
      :yin.k/next-op-seq n          ; absent on a halted root and a fork
      :yin.k/ops         {op-id digest}
      :yin.k/fork        true}      ; a nil slot only

   digest being the BLAKE3 hex of the canonical bytes of [:yin.k/append
   target-identity payload], the payload as the wire slot holds it.
   Every refusal is a data outcome carrying :yin.k/status; nothing here
   throws on any input but a codec defect."
  (:require [dao.jing :as jing]
            [dao.jing.cbor :as cbor]
            [yin.vm.ucf.rows :as rows]))


(defn- answer
  "Run f, answering its value or the refusal it threw. A throw without
   the refusal vocabulary is a defect and propagates."
  [f]
  (try (f)
       (catch #?(:cljd Object :clj Throwable :cljs :default) e
         (let [d (ex-data e)]
           (if (and (map? d) (contains? d :yin.k/status)) d (throw e))))))


(defn- computed-address
  "The segment address of bytes under the algorithm address names, or
   BLAKE3 when it names none; nil when bytes are not bytes."
  [address bytes]
  (when (cbor/byte-payload? bytes)
    (let [algo (or (:algorithm (jing/parse-segment-address address)) :blake3)]
      (keyword "segment" (str (get-in jing/registry [algo :address-id]) "-"
                              (jing/digest-bytes algo bytes))))))


(defn inspect-pack
  "The operation baseline of the decoded pack wire (a map
   dao.jing.cbor/decode answered), or the first refusal as data."
  [wire]
  (answer
    (fn []
      (let [task (rows/decode wire)]
        (when-not (= :task (:kind task))
          (throw (ex-info "Checkpoint refused: undecodable"
                          {:yin.k/status :yin.k/undecodable :yin.k/kind :root :yin.k/path [:yin.k/root]})))
        (assoc (rows/custody-baseline task) :yin.k/root (:yin.k/root wire))))))


(defn inspect
  "The operation baseline of the pack whose canonical bytes are bytes
   under the segment address address (verified under the algorithm it
   names), or the first refusal as data."
  [address bytes]
  (answer
    (fn []
      (when-not (and (cbor/byte-payload? bytes) (jing/segment-bytes-match? address bytes))
        (throw (ex-info "Checkpoint refused: hash-mismatch"
                        {:yin.k/status :yin.k/hash-mismatch
                         :yin.k/address address
                         :yin.k/computed (computed-address address bytes)})))
      (let [wire (try (cbor/decode bytes)
                      (catch #?(:cljd Object :clj Throwable :cljs :default) e
                        (throw (ex-info "Checkpoint refused: undecodable"
                                        {:yin.k/status :yin.k/undecodable :yin.k/path []
                                         :yin.k/kind :bytes :yin.k/refusal (cbor/refusal e)}))))]
        (if (map? wire)
          (inspect-pack wire)
          {:yin.k/status :yin.k/undecodable :yin.k/path [] :yin.k/kind :body})))))
