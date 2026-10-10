(ns yin.vm.semantic-register.linearize-test
  (:require [clojure.test :refer [deftest is testing]]
            [yin.vm :as vm]
            [yin.vm.malformed-rows :as malformed]
            [yin.vm.semantic-register.code :as code]
            [yin.vm.semantic-register.corpus :as corpus
             :refer [app lam lit tail v]]
            [yin.vm.semantic-register.linearize :as linearize]
            [yin.vm.ucf :as ucf]))


(defn- throws-ex-data
  [thunk]
  (try (thunk) nil
       (catch #?(:clj Exception :cljs js/Error :cljd Object) e
         (or (ex-data e) {}))))


(defn- datom-lane
  [ast]
  (linearize/project-datoms (vm/ast->datoms ast)))


(deftest corpus-is-large-enough
  (is (<= 25 (count corpus/programs)))
  (is (= (set (map first corpus/programs)) (set (keys corpus/goldens)))))


(deftest row-lane-matches-the-goldens
  (doseq [[n ast] corpus/programs]
    (testing n
      (let [{:keys [vector address]} (linearize/project ast)
            golden (get corpus/goldens n)]
        (is (= (:vector golden) vector))
        (is (= (:address golden) address))))))


(deftest datom-lane-matches-the-goldens
  (doseq [[n ast] corpus/programs]
    (testing n
      (let [{:keys [vector address]} (datom-lane ast)
            golden (get corpus/goldens n)]
        (is (= (:vector golden) vector))
        (is (= (:address golden) address))))))


(deftest golden-addresses-are-the-code-address-on-this-host
  (doseq [[n {:keys [vector address]}] corpus/goldens]
    (testing n
      (is (= address (ucf/code-address vector))
          "the canonical bytes hash identically on every host"))))


(deftest worked-example-is-byte-for-byte-the-design
  ;; §3.3 item 2: rd(f-call)=0, rd(f)=1, rd(g-call)=2, rd(g)=3, rd(x)=4,
  ;; rd(y)=5, emitted in evaluation order.
  (let [expected '[[:var 1 f] [:var 3 g] [:var 4 x] [:call 2 3 [4] false]
                   [:var 5 y] [:call 0 1 [2 5] false] [:halt 0]]
        {:keys [vector address]} (linearize/project
                                   (app (v 'f) (app (v 'g) (v 'x)) (v 'y)))]
    (is (= expected vector))
    (is (= (ucf/code-address expected) address))
    (is (= '[[:var 1 f] [:var 3 g] [:var 4 x] [:call 2 3 [4] false]
             [:var 5 y] [:call 0 1 [2 5] true] [:halt 0]]
           (:vector (linearize/project
                      (tail (app (v 'f) (app (v 'g) (v 'x)) (v 'y))))))
        "the tail flag is copied from the AST, nothing else moves")))


(deftest every-production-is-covered
  (let [ops (set (mapcat (fn [[_ {:keys [vector]}]] (map first vector))
                         corpus/goldens))]
    (is (= code/mnemonics ops)
        "every §2.3 opcode, hence every §3.1 production, is in the corpus")))


(deftest goldens-are-well-formed
  (doseq [[n {:keys [vector]}] corpus/goldens]
    (testing n
      (is (nil? (code/well-formed? vector))))))


(deftest projection-is-deterministic
  (doseq [[n ast] corpus/programs]
    (testing n
      (is (= (linearize/project ast) (linearize/project ast)))
      (is (= (:vector (linearize/project ast))
             (:vector (linearize/project
                        (vm/semantic-bytecode->ast
                          (vm/ast->semantic-bytecode ast)))))
          "rows -> map -> rows projects identically"))))


(deftest saturation-materializes-defaults
  (is (= (linearize/project {:type :vm/gensym})
         (linearize/project {:type :vm/gensym, :prefix "id"})))
  (is (= (linearize/project {:type :stream/make})
         (linearize/project {:type :stream/make, :buffer vm/default-stream-capacity})))
  (is (= (linearize/project (app (v 'f)))
         (linearize/project (assoc (app (v 'f)) :tail? false))))
  (is (= '[[:gensym 0 "id"] [:halt 0]]
         (:vector (linearize/project-datoms
                    [[-1 :yin/type :vm/gensym 0 0] [-1 :yin/root true 0 0]])))
      "the datom lane saturates an omitted prefix"))


(deftest shared-occurrences-expand-positionally
  (let [ast (get (into {} corpus/programs) :shared-occurrence)
        bc (vm/ast->semantic-bytecode ast)
        {:keys [vector provenance]} (linearize/project-rows bc)]
    (is (= 5 (count (:rows bc)))
        "+, g, 1, the root, and one (g 1) row: the shared node is one row")
    (is (= [[:call 2 3 [4] false] [:call 5 6 [7] false]]
           (mapv #(nth vector %) [3 6]))
        "the two occurrences of (g 1) lower twice, at two places")
    (is (= [[[3 0]] [[3 1]]]
           (mapv #(last (nth provenance %)) [3 6]))
        "the two occurrences carry distinct structural paths")))


(deftest provenance-is-one-row-per-pc
  (doseq [[n ast] corpus/programs]
    (testing n
      (let [bc (vm/ast->semantic-bytecode ast)
            origin [:source :medium :token 0]
            {:keys [vector provenance]} (linearize/project-rows bc origin)
            datoms (vm/ast->datoms ast)
            entities (set (map first datoms))
            d (linearize/project-datoms datoms)]
        (is (= (count vector) (count provenance) (count (:provenance d))))
        (is (= (range (count vector)) (map first provenance)))
        (is (every? #(= [origin (:root bc)] (subvec % 1 3)) provenance))
        (is (every? #(contains? entities (second %)) (:provenance d))
            "every datom-lane source names an AST entity")))))


(deftest bodies-are-out-of-line-in-fifo-order
  (let [{:keys [vector]} (get corpus/goldens :body-queue-order)]
    (is (= [4 8 10]
           (keep (fn [t] (when (= :closure (first t)) (nth t 3))) vector))
        "main's two lambdas first, then the one discovered inside a body")
    (is (= [:halt 0] (nth vector 3)))
    (is (every? #(= [:return 0] (nth vector %)) [7 9 11]))))


(deftest resume-mints-a-definition-less-id
  (let [{:keys [vector]} (get corpus/goldens :resume-operand)]
    (is (= [:call 0 1 [2 4] false] (nth vector 4)))
    (is (not-any? #(= 2 (code/rd %)) vector)
        "id 2 is minted by the resume and named only by its consumer")))


(deftest unsupported-and-reserved-nodes-are-refused
  (is (= :reserved-name
         (:rule (throws-ex-data
                  #(linearize/project-datoms
                     [[-1 :yin/type :variable 0 0]
                      [-1 :yin/name 'yin/def 0 0]
                      [-1 :yin/root true 0 0]])))))
  (is (= :reserved-name
         (:rule (throws-ex-data
                  #(linearize/project-datoms
                     (vm/ast->datoms (lam '[yin/def] (lit 1))))))))
  (is (some? (throws-ex-data
               #(linearize/project-datoms
                  [[-1 :yin/type :vm/store-update 0 0]
                   [-1 :yin/root true 0 0]]))))
  (is (some? (throws-ex-data #(linearize/project-datoms [])))))


(deftest malformed-row-sets-are-refused
  (doseq [[n [_expected bc]] malformed/malformed-row-sets]
    (testing n
      (is (some? (throws-ex-data #(linearize/project-rows bc)))))))
