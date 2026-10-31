;; Run from the repository root: clojure -M collab/1792620000000-8ab-regen.clj
;; The adjacent EDN file prints every intermediate; no lowerer or private var
;; is needed. Projection fingerprints use printed map ASTs.
(require '[clojure.edn :as edn]
         '[clojure.string :as str]
         '[dao.jing :as jing]
         '[dao.jing.cbor :as cbor]
         '[yin.vm :as vm]
         '[yin.vm.debruijn :as db]
         '[yin.vm.debruijn-code :as dc]
         '[yin.vm.debruijn-register-code :as rcode])


(def intermediates
  (edn/read-string (slurp "collab/1792620000000-8ab-intermediates.edn")))


(defn recompute
  [{:keys [kind input]}]
  (case kind
    :h (dc/image-hash input)
    :r (rcode/register-hash input)
    :descriptor (jing/blake3 (dc/encode-scalar input))
    :dimension (jing/blake3 (db/encode-value input))
    :projection (:fingerprint (db/project-datoms (vm/ast->datoms input)))
    :segment (jing/segment-key input)))


(assert (= dc/descriptor (get-in intermediates ["44c761ee3ba9b5669385841b361b76f86f0b1db7b3b42f1c7966458669f6361e" :input])))


(assert (= rcode/descriptor
           (get-in intermediates
                   ["7eacba41f9f1548d18b724cbd2db87945f09ec7b4ab2f303399e9b6497007632" :input])))


(assert (= db/descriptor
           (get-in intermediates
                   ["1f4ec95cdfd666cf5da2be5f47fd3a7e3c942dfa2ea87e61e0062270f61a0803" :input])))


(doseq [[old entry] (sort-by key intermediates)]
  (when (str/starts-with? old "handoff-v2:")
    (let [[_ address & hex] (str/split-lines
                              (slurp "test/resources/yin/vm/ucf/handoff-v2.txt"))
          encoded (apply str (map #(format "%02x" (bit-and 255 %))
                                  (cbor/encode (:input entry))))]
      (assert (= address (subs (str (recompute entry)) 1)))
      (assert (= (apply str hex) encoded))))
  (prn [old (recompute entry)]))
