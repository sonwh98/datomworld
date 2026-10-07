(ns yin.vm.ucf.handoff-v2-layout-test
  "UCF version 2, section 11 row 5: image growth.  A stack or register
   task attaches module image B, captures a continuation inside it with a
   return frame into the root image A, attaches module image C, and
   blocks.  The body carries the whole current layout in order, the
   captured continuation its own prefix layout, every image once; the
   lower rebuilds both into a receiver whose old layout was something
   else, and the captured continuation returns across the images exactly
   as the source's does."
  (:require [clojure.test :refer [deftest is testing]]
            [clojure.set :as set]
            [clojure.walk :as walk]
            [dao.jing.cbor :as jing.cbor]
            [dao.stream :as stream]
            [yin.vm :as vm]
            [yin.vm.debruijn-code :as dcode]
            [yin.vm.debruijn-linearize :as dl]
            [yin.vm.debruijn-register-code :as rcode]
            [yin.vm.debruijn-register-compile :as rc]
            [yin.vm.debruijn.register :as register]
            [yin.vm.debruijn.stack :as stack]
            [yin.vm.ucf.v2-support :as s]
            [yin.vm.values :as values]))


(def ^:private profiles [:stack :register])


(defn- module-resp
  [engine module-name exports ast]
  {:status :ok
   :image {:value (s/module-image engine ast)}
   :manifest {:yin.module/name module-name
              :yin.module/exports (set exports)}
   :obligations []})


(def ^:private module-b
  (s/def! 'g (s/lam [] {:type :vm/current-continuation})))


(def ^:private module-c
  (s/def! 'h (s/lam [] (s/lit 1))))


(def ^:private program
  "Link B, capture k inside B's g with a return frame into this image,
   link C, block on a read; once the read answers, invoke the captured
   continuation if k is still one, else answer k."
  (s/let1 's {:type :stream/make, :buffer 4}
          (s/let1 'c (s/cursor-of (s/v 's))
                  (s/then (s/require-program 'host.b)
                          (s/then (s/def! 'k (s/app (s/v 'host.b/g)))
                                  (s/then (s/require-program 'host.c)
                                          (s/then (s/next-of (s/v 'c))
                                                  (s/lit :done))))))))


(defn- link-through
  "Run `m`, answering each link wait from `table` until none is left."
  [m response table]
  (loop [m (vm/run m) n 8]
    (let [e (first (filter #(contains? #{:link-request :link-response}
                                       (:reason %))
                           (:wait-set m)))]
      (if (and e (pos? n))
        (do (stream/append! response
                            (assoc (get table (:name e))
                                   :yin.link/id (:link-id e)))
            (recur (vm/run m) (dec n)))
        m))))


(defn- grown-machine
  "The task after both links: layout [A B C] under `engine`, blocked on
   its read."
  [engine]
  (let [request (s/ring 64)
        response (s/ring 64)
        m (s/load-ast engine (s/linking-machine engine request response)
                      program)]
    (link-through m response
                  {'host.b (module-resp engine 'host.b ['g] module-b)
                   'host.c (module-resp engine 'host.c ['h] module-c)})))


(defn- layout-of
  [engine m]
  (case engine
    :stack (stack/layout m)
    :register (register/layout m)))


(defn- frame-registers
  "Every reified continuation's register map inside `x`."
  [x]
  (into []
        (comp (filter #(and (map? %) (= :yin.k/frame (:yin.k/tag %))))
              (keep :yin.k/registers))
        (tree-seq coll? (fn [n]
                          (if (map? n) (concat (keys n) (vals n)) (seq n)))
                  x)))


(deftest a-grown-layout-travels-in-order-and-rebuilds-in-a-fresh-receiver
  (doseq [engine profiles]
    (testing (name engine)
      (let [m (grown-machine engine)
            source-layout (layout-of engine m)
            t (s/toy)
            peer (s/served-peer t)
            export (s/lift m t peer)
            body (:body export)
            poisoned (let [base (s/load-ast engine (s/new-machine engine)
                                            (s/lit 99))]
                       base)
            [r _] (s/read! engine t (:bytes export)
                           {:address (:address export)})]
        (is (= :ok (:status export)) (pr-str export))
        (is (= 3 (count source-layout)) (pr-str source-layout))
        (is (= source-layout (:yin.k/layout body))
            "the exact ordered layout, never sorted")
        (is (= (set source-layout) (set (keys (:yin.k/code body))))
            "every image carried once")
        (is (not (contains? (set (keys (:yin.k/code body))) (:hash m)))
            "no aggregate hash is an image identity")
        (testing "the captured continuation carries its own prefix layout"
          (let [captured (frame-registers (:yin.k/store body))
                prefixes (map :yin.k/layout captured)]
            (is (seq captured))
            (is (every? #(= % (subvec source-layout 0 (count %))) prefixes))
            (is (some #(< (count %) 3) prefixes)
                "captured before C was attached")))
        (is (= :ok (:status r)) (pr-str r))
        (is (= source-layout (layout-of engine (:vm r)))
            "the receiver's old layout is replaced by the source's")
        (is (some? poisoned))))))


(defn- restored
  "The registers continuation value `k` restores to under `engine`'s
   own restore, delivering `val`, over machine `m`."
  [engine m k val]
  (let [payload (values/payload k)
        r (case engine
            :stack (stack/stack-restore m payload val)
            :register (register/register-restore m payload val))]
    (walk/postwalk (fn [x]
                     (cond (values/host-typed? x) (values/kind-of x)
                           (and (map? x) (contains? #{:stream-ref :cursor-ref}
                                                    (:type x)))
                           (:type x)
                           :else x))
                   (select-keys r [:pc :frames :stack :continuation
                                   :registers :store-of]))))


(deftest a-captured-continuation-returns-across-images-as-the-source-does
  (doseq [engine profiles]
    (testing (name engine)
      (let [m (grown-machine engine)
            t (s/toy)
            peer (s/served-peer t)
            export (s/lift m t peer)
            src (s/stream-of m)
            k-src (get (:store m) 'k)
            [r _] (s/read! engine t (:bytes export)
                           {:address (:address export)})
            recv (:vm r)
            k-recv (get (:store recv) 'k)
            reference (do (stream/append! src "B")
                          (s/drive-local m))
            done (s/drive recv peer)]
        (is (= :ok (:status r)) (pr-str r))
        (is (values/continuation? k-src))
        (is (values/continuation? k-recv))
        (is (= (restored engine m k-src 7) (restored engine recv k-recv 7))
            "the cross-image return frames restore to the same registers")
        (is (= (vm/blocked? reference) (vm/blocked? done)))
        (is (= (vm/value reference) (vm/value done)) "the task runs on")))))


(defn- tamper-image
  "`image` with the value of its first `:const` changed: still a valid
   image, no longer the one its key names."
  [image]
  (let [insts (if (vector? image) image (:instructions image))
        i (first (keep-indexed #(when (= :const (first %2)) %1) insts))
        insts' (update insts i
                       (fn [inst] (assoc inst (dec (count inst)) "tampered")))]
    (if (vector? image)
      insts'
      (assoc image :instructions insts'))))


(deftest a-corrupted-image-or-image-identity-refuses-before-attachment
  (doseq [engine profiles]
    (testing (name engine)
      (let [m (grown-machine engine)
            t (s/toy)
            peer (s/served-peer t)
            export (s/lift m t peer)
            body (:body export)
            [a b c] (:yin.k/layout body)
            enc jing.cbor/encode
            refused (fn [bytes status]
                      (let [[r n] (s/read! engine t bytes nil)]
                        (is (= status (:yin.k/status r)) (pr-str r))
                        (is (zero? n))
                        (is (not (contains? r :vm)))))]
        (testing "an image whose bytes no longer hash to its key"
          (refused (enc (update-in body [:yin.k/code a] tamper-image))
                   :yin.k/hash-mismatch))
        (testing "a layout that repeats an identity"
          (refused (enc (assoc body :yin.k/layout [a b a])) :yin.k/undecodable))
        (testing "a layout naming an uncarried image"
          (refused (enc (assoc body :yin.k/layout [a b :segment/missing]))
                   :yin.k/undecodable))
        (testing "a layout dropping an image the code carries"
          (refused (enc (assoc body :yin.k/layout [a b])) :yin.k/undecodable))
        (testing "an image field naming another row"
          (refused (enc (assoc-in body
                                  [:yin.k/frames 0 :yin.k/registers
                                   :yin.k/image]
                                  (if (= c (get-in body [:yin.k/frames 0
                                                         :yin.k/registers
                                                         :yin.k/image]))
                                    a
                                    c)))
                   :yin.k/undecodable))
        (is (set/subset? #{a b c} (set (keys (:yin.k/code body)))))
        (is (every? values/closure? [])
            "no closure was needed for the layout rows")))))


(defn- empty-based-machine
  "A machine whose base is the empty image with the program image
   attached after it, as `yin.repl` continues a halted task: layout
   [empty A]."
  [engine ast]
  (let [m (s/new-machine engine)]
    (case engine
      :stack
      (let [img (:image (dl/adapt (vm/ast->datoms ast)))
            attached (stack/attach-image m img vm/stack-contract)]
        (assoc attached
               :pc (stack/absolute-pc attached [(dcode/image-hash img) 0])
               :frames [] :stack [] :continuation []
               :halted? false :blocked? false :value nil))
      :register
      (let [img (:image (rc/adapt (vm/ast->datoms ast)))
            attached (register/attach-image m img vm/register-contract)
            pc (register/absolute-pc attached [(rcode/register-hash img) 0])
            body (some #(when (= pc (:start %)) %)
                       (:bodies (:segment attached)))]
        (assoc attached
               :pc pc :frames [] :continuation []
               :registers (vec (repeat (:registers body) nil))
               :halted? false :blocked? false :value nil)))))


(deftest an-empty-base-is-the-first-layout-entry
  (doseq [engine profiles]
    (testing (name engine)
      (let [m (vm/run (empty-based-machine
                        engine
                        (s/let1 's {:type :stream/make, :buffer 4}
                                (s/next-of (s/cursor-of (s/v 's))))))
            layout (layout-of engine m)
            t (s/toy)
            peer (s/served-peer t)
            export (s/lift m t peer)
            src (s/stream-of m)
            reference (do (stream/append! src "B")
                          (vm/value (s/drive-local m)))
            [r _] (s/read! engine t (:bytes export)
                           {:address (:address export)})]
        (is (= :ok (:status export)) (pr-str export))
        (is (= 2 (count layout)))
        (is (= layout (get-in export [:body :yin.k/layout]))
            "the empty base is index zero, in the empty image's own hash")
        (is (= {} (select-keys (get-in export [:body :yin.k/code] {}) [])))
        (is (= :ok (:status r)) (pr-str r))
        (is (= layout (layout-of engine (:vm r))))
        (is (= reference (vm/value (s/drive (:vm r) peer))))))))
