(ns dao.data-test
  "Contract tests for dao.data's `tag` and `summarize`
   (docs/design/dao.data.md)."
  (:require [clojure.test :refer [are deftest is testing]]
            [dao.data :as data]
            [dao.stream :as stream]
            [dao.stream.ringbuffer :as ringbuffer]))


(def bounds
  "A generous default budget; every test that exercises a specific bound
   states its own instead."
  {:depth 3 :items 4 :chars 40})


(defrecord FakeHandle
  [id]

  stream/IDaoStreamDescriptor

  (descriptor
    [_]
    {:dao.stream/outcome :dao.stream/ok
     :dao.stream/descriptor {:dao.stream/type :dao.stream/fake
                             :dao.stream/identity id}
     :dao.stream/identity id}))


(defn- counting-seq
  "A deliberately non-chunked lazy sequence of n elements; :produced counts
   every element actually realized, so tests can assert how many elements
   summarize asked for (a chunked source would realize whole chunks and
   blur the count)."
  [n]
  (let [produced (atom 0)]
    (letfn [(go
              [i]
              (lazy-seq
                (when (< i n)
                  (swap! produced inc)
                  (cons i (go (inc i))))))]
      {:produced produced :s (go 0)})))


(deftest tag-classifies-every-branch
  (are [x expected] (= expected (data/tag x))
    nil :nil
    true :boolean
    false :boolean
    42 :number
    ;; No ratio type on CLJS; the row splices away there (see the gated
    ;; ratio case in number-portability below).
    #?@(:cljs [] :default [1/3 :number])
    "s" :string
    :kw :keyword
    'sym :symbol
    inc :fn
    [] :vector
    #{} :set
    {} :map
    (lazy-seq [1]) :sequence
    (random-uuid) :opaque))


(deftest tag-orders-stream-before-map
  (let [h (->FakeHandle "x")]
    ;; A handle may be a record, so it is map-like — but still a :stream.
    (is (map? h))
    (is (= :stream (data/tag h)))
    (is (= :map (data/tag {:not :a-handle})))))


(deftest leaf-nodes-carry-nothing-beyond-value
  (is (= {:dao.data/type :nil} (data/summarize nil bounds)))
  (is (= {:dao.data/type :boolean :dao.data/value true} (data/summarize true bounds)))
  (is (= {:dao.data/type :fn} (data/summarize inc bounds)))
  (is (= {:dao.data/type :opaque} (data/summarize (random-uuid) bounds))))


(deftest depth-bound
  (testing "depth 0: no items, truncated?, count still true for a counted input"
    (is (= {:dao.data/type :vector :dao.data/truncated? true :dao.data/count 1}
           (data/summarize [3] {:depth 0 :items 4 :chars 40}))))
  (testing "each nesting level consumes one depth"
    (let [node (data/summarize [1 [2 [3]]] {:depth 2 :items 4 :chars 40})
          inner (get-in node [:dao.data/items 1])
          innermost (get-in inner [:dao.data/items 1])]
      (is (= :vector (:dao.data/type inner)))
      (is (= 2 (count (:dao.data/items inner))))
      (is (= {:dao.data/type :vector :dao.data/truncated? true :dao.data/count 1}
             innermost)
          "depth runs out at the innermost vector: truncated?, no :items"))))


(deftest items-bound
  (let [node (data/summarize [1 2 3 4 5] {:depth 1 :items 2 :chars 40})]
    (is (= 2 (count (:dao.data/items node))))
    (is (true? (:dao.data/truncated? node)))
    (is (= 5 (:dao.data/count node))
        "counted input: :count is the true count, independent of :items")))


(deftest chars-bound
  (testing "a string over :chars is cut; at or under it is not"
    (is (= {:dao.data/type :string :dao.data/value "abcd" :dao.data/truncated? true}
           (data/summarize "abcdefghij" {:depth 1 :items 4 :chars 4})))
    (is (= {:dao.data/type :string :dao.data/value "abcd" :dao.data/truncated? false}
           (data/summarize "abcd" {:depth 1 :items 4 :chars 4}))))
  (testing "leaf truncation is unaffected by :depth"
    (is (= {:dao.data/type :string :dao.data/value "abcd" :dao.data/truncated? true}
           (data/summarize "abcdefghij" {:depth 0 :items 0 :chars 4}))))
  (testing "keywords are measured on their printed form"
    (is (= {:dao.data/type :keyword :dao.data/value :a/b :dao.data/truncated? false}
           (data/summarize :a/b {:depth 1 :items 4 :chars 4}))
        ":a/b prints as exactly 4 characters")
    (is (= {:dao.data/type :keyword :dao.data/value ":ns/lim" :dao.data/truncated? true}
           (data/summarize :ns/limited {:depth 1 :items 4 :chars 7}))
        "a cut keyword becomes a plain string"))
  (testing "symbols follow the same rule"
    (is (= {:dao.data/type :symbol :dao.data/value 'abcdef :dao.data/truncated? false}
           (data/summarize 'abcdef {:depth 1 :items 4 :chars 40})))
    (is (= {:dao.data/type :symbol :dao.data/value "abcd" :dao.data/truncated? true}
           (data/summarize 'abcdefghij {:depth 1 :items 4 :chars 4})))))


(deftest number-portability
  (is (= {:dao.data/type :number :dao.data/value 42 :dao.data/truncated? false}
         (data/summarize 42 bounds)))
  (is (= {:dao.data/type :number :dao.data/value -0.5 :dao.data/truncated? false}
         (data/summarize -0.5 bounds)))
  (testing "an integer one past the safe range is stringified"
    (is (= {:dao.data/type :number :dao.data/value "9007199254740992"
            :dao.data/truncated? true}
           (data/summarize 9007199254740992 bounds))))
  (testing "non-finite numbers are stringified"
    (let [node (data/summarize ##Inf bounds)]
      (is (true? (:dao.data/truncated? node)))
      (is (string? (:dao.data/value node)))))
  #?(:clj
     (testing "a ratio is outside the portable domain on the JVM"
       (is (= {:dao.data/type :number :dao.data/value "1/3" :dao.data/truncated? true}
              (data/summarize 1/3 bounds))))))


(deftest count-presence-is-decided-by-counted?
  (testing "counted inputs carry :count"
    (doseq [x [[1 2 3] #{1 2} {:a 1} (list 1 2)]]
      (is (contains? (data/summarize x bounds) :dao.data/count)
          (pr-str x))))
  (testing "a non-counted sequence cut by the item bound: no :count"
    (let [node (data/summarize (lazy-seq [1 2 3 4 5]) {:depth 1 :items 2 :chars 40})]
      (is (true? (:dao.data/truncated? node)))
      (is (not (contains? node :dao.data/count)))))
  (testing "a non-counted sequence exhausted during the probe: still no :count"
    (let [node (data/summarize (lazy-seq [1 2]) {:depth 1 :items 2 :chars 40})]
      (is (false? (:dao.data/truncated? node)))
      (is (= 2 (count (:dao.data/items node))))
      (is (not (contains? node :dao.data/count))
          ":count is a property of the input's type, never of what the probe found")))
  (testing "a fully realized lazy seq still answers counted? false"
    (let [s (doall (lazy-seq [1 2]))]
      (is (false? (counted? s)))
      (is (not (contains? (data/summarize s bounds) :dao.data/count))))))


(deftest non-counted-probe-discipline
  (testing "at most items+1 elements requested at depth > 0"
    (let [{:keys [produced s]} (counting-seq 100)]
      (data/summarize s {:depth 1 :items 3 :chars 40})
      (is (= 4 @produced)
          "three to fill :items plus one to see whether a next exists")))
  (testing "an exhausted sequence requests only what it has"
    (let [{:keys [produced s]} (counting-seq 2)]
      (data/summarize s {:depth 1 :items 3 :chars 40})
      (is (= 2 @produced))))
  (testing "no probe at all at depth 0"
    (let [{:keys [produced s]} (counting-seq 3)]
      (data/summarize s {:depth 0 :items 2 :chars 40})
      (is (zero? @produced)
          "nothing in a depth-0 result depends on the answer"))))


(deftest items-and-entries-are-always-vectors
  (is (vector? (:dao.data/items (data/summarize #{1 2} bounds))))
  (is (vector? (:dao.data/items (data/summarize (list 1 2) bounds))))
  (testing "equal summaries are not collapsed the way a set would"
    (let [node (data/summarize (lazy-seq [1 1 1]) {:depth 1 :items 4 :chars 40})]
      (is (= 3 (count (:dao.data/items node)))))))


(deftest map-entries
  (let [node (data/summarize {:a 1} {:depth 1 :items 4 :chars 40})
        [[k v]] (:dao.data/entries node)]
    (is (= 1 (count (:dao.data/entries node))))
    (is (vector? (first (:dao.data/entries node))))
    (is (= {:dao.data/type :keyword :dao.data/value :a :dao.data/truncated? false} k)
        "keys are summarized like values")
    (is (= {:dao.data/type :number :dao.data/value 1 :dao.data/truncated? false} v)))
  (testing "the item bound counts entries, not key/value pairs"
    (let [node (data/summarize {:a 1 :b 2 :c 3} {:depth 1 :items 2 :chars 40})]
      (is (= 2 (count (:dao.data/entries node))))
      (is (= 3 (:dao.data/count node)))
      (is (true? (:dao.data/truncated? node)))))
  (testing "depth 0: no entries, count still present"
    (let [node (data/summarize {:a 1} {:depth 0 :items 4 :chars 40})]
      (is (not (contains? node :dao.data/entries)))
      (is (= 1 (:dao.data/count node))))))


(deftest stream-identity-is-a-summarized-node
  (testing "a map-like fake handle, identity bounded by :chars like any value"
    (let [b {:depth 3 :items 4 :chars 4}
          node (data/summarize (->FakeHandle "an-identity-string") b)]
      (is (= :stream (:dao.data/type node)))
      (is (= (data/summarize "an-identity-string" b)
             (:dao.data/identity node)))
      (is (= {:dao.data/type :string :dao.data/value "an-i" :dao.data/truncated? true}
             (:dao.data/identity node)))))
  (testing "a real ringbuffer handle, identity from the descriptor outcome"
    (let [{:dao.stream/keys [handle]}
          (ringbuffer/create! {:dao.stream/type :dao.stream/ringbuffer
                               :dao.stream.ringbuffer/capacity 8})
          b {:depth 2 :items 4 :chars 100}]
      (is (= :stream (data/tag handle)))
      (is (= (data/summarize (:dao.stream/identity (stream/descriptor handle)) b)
             (:dao.data/identity (data/summarize handle b))))))
  (testing "a non-conforming descriptor falls back to :opaque"
    (let [h (reify stream/IDaoStreamDescriptor
              (descriptor [_] {:dao.stream/outcome :dao.stream/error}))]
      (is (= :stream (data/tag h)))
      (is (= {:dao.data/type :opaque} (data/summarize h bounds))))))
