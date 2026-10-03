(ns yin.vm.linker.cross-host-transfer-test
  "Criterion 5a: a JVM publisher and a separate Dart receiver.
   The receiver starts with identity and index, never an image. Remote
   stream envelopes cross Transit pipes between two ring compositions.
   Build the test-only peer on every run so this gate cannot skip."
  (:require [clojure.java.io :as io]
            [clojure.java.shell :as shell]
            [clojure.test :refer [deftest is testing]]
            [dao.jing :as jing]
            [dao.jing.content :as content]
            [dao.jing.mem :as mem]
            [dao.stream :as stream]
            [dao.stream.remote :as remote]
            [dao.stream.ringbuffer :as ring]
            [dao.stream.transit :as transit]
            [yin.vm :as vm]
            [yin.vm.debruijn-linearize :as dl]
            [yin.vm.debruijn-register-compile :as rc]
            [yin.vm.debruijn-vm-contract-test :as b0]
            [yin.vm.linker :as linker]
            [yin.vm.linker.publish :as publish]
            [yin.vm.linker.remote-transfer-test :as fixtures]
            [yin.vm.test-utils :as tu]))


(defn- build-peer!
  []
  (doseq [command
          [["mise" "exec" "--" "clojure" "-M:clojuredart:cljd" "compile"
            "yin.vm.linker.transfer-peer"]
           ["mise" "exec" "--" "dart" "compile" "exe"
            "lib/cljd-out/yin/vm/linker/transfer-peer.dart"
            "-o" "build/linker-transfer-peer"]]]
    (let [{:keys [exit out err]} (apply shell/sh command)]
      (when-not (zero? exit)
        (throw (ex-info "Dart transfer peer build failed"
                        {:command command :exit exit :out out :err err}))))))


(defn- medium
  []
  (:dao.stream/handle
    (ring/create! {:dao.stream/type ring/transport-type
                   ring/capacity-key 256})))


(defn- oldest
  [h]
  (:dao.stream/cursor (stream/cursor h :dao.stream/oldest)))


(defn- drain!
  [h cursor]
  (loop [values []]
    (let [r (stream/next h @cursor)]
      (if (= :dao.stream/ok (:dao.stream/outcome r))
        (do (reset! cursor (:dao.stream/cursor r))
            (recur (conj values (:dao.stream/value r))))
        values))))


(defn- receive!
  [store initial]
  (let [process (.start (ProcessBuilder.
                          ^java.util.List ["build/linker-transfer-peer"]))
        stderr (future (slurp (.getErrorStream process)))
        reader (io/reader (.getInputStream process))
        writer (io/writer (.getOutputStream process))
        incoming (medium)
        outgoing (medium)
        requests (medium)
        answers (medium)
        mirror-cursor (atom (oldest incoming))
        serve-cursor (atom (oldest requests))
        out-cursor (atom (oldest outgoing))
        table {"requests" {:handle requests :surface #{:reader :writer}}
               "answers" {:handle answers :surface #{:reader :writer}}}
        send! (fn [value]
                (.write writer (str (transit/encode value) "\n"))
                (.flush writer))
        task (future
               (send! initial)
               (loop [rounds 0]
                 (when (> rounds 10000)
                   (throw (ex-info "Transfer exceeded round budget" {})))
                 (if-let [line (.readLine reader)]
                   (let [reply (transit/decode line)]
                     (if (contains? reply :result)
                       reply
                       (do
                         (doseq [v (:wire reply)]
                           (stream/append! incoming v))
                         (swap! mirror-cursor
                                #(remote/mirror-step table incoming %
                                                     outgoing))
                         (swap! serve-cursor
                                #(content/serve-step store requests %
                                                     answers 64))
                         (send! {:wire (drain! outgoing out-cursor)})
                         (recur (inc rounds)))))
                   (throw (ex-info "Dart peer ended before result"
                                   {:stderr @stderr})))))]
    (try
      (let [reply (deref task 60000 ::timeout)]
        (when (= ::timeout reply)
          (throw (ex-info "Dart transfer timed out" {})))
        reply)
      (finally
        (.destroyForcibly process)
        (.waitFor process)
        (future-cancel task)
        (.close writer)
        (.close reader)))))


(defn- image
  [lower ast]
  (:image (lower (second (vm/ast->datoms-with-root ast)))))


(deftest ^:slow jvm-images-transfer-to-an-identity-only-dart-receiver
  (build-peer!)
  (doseq [[kind format lower]
          [[:H linker/stack-format dl/adapt]
           [:R linker/register-format rc/adapt]]]
    (testing (name kind)
      (let [store (mem/create-content-mem)]
        (try
          (let [published (publish/publish-module!
                            store {:name 'transfer.example
                                   :ast fixtures/worked-example
                                   :exports #{} :requires {}
                                   :primitives
                                   {'+ {:yin.k/profile :test/add
                                        :yin.k/effects #{}}}})
                identity (get-in published [:identities (:format format)])
                index (select-keys
                        (get-in published [:manifest :yin.module/index])
                        [identity])
                initial {:kind kind :identity identity :index index}
                received (receive! store initial)
                local (b0/normalize
                        (vm/value (vm/eval (tu/create-vm)
                                           fixtures/worked-example)))]
            (is (jing/segment-address? (:address published)))
            (is (every? #(= :ok (:status %)) (vals (:links published))))
            (is (= #{:kind :identity :index} (set (keys initial))))
            (is (= :ok (get-in received [:result :status]))
                (pr-str received))
            (is (true? (:identity-matches received)))
            (is (= identity (get-in received [:result :identity])))
            (is (= local (b0/normalize (:value received))))
            (testing "a foreign image at the claimed index address"
              (let [[foreign _ address]
                    (linker/publish! store format
                                     (image lower fixtures/other-program))
                    reply (receive! store
                                    (assoc initial :index {identity address}))]
                (is (= {:status :refused :reason :hash-mismatch
                        :expected identity :actual foreign}
                       (:result reply)))
                (is (not (contains? reply :value)))))
            (testing "corrupt bytes served under the published address"
              (let [bad-store (assoc store :get-bytes-fn
                                     (fn [_ _]
                                       (jing/canonical-bytes [:corrupt])))
                    reply (receive! bad-store initial)]
                (is (= :refused (get-in reply [:result :status])))
                (is (= :address-mismatch
                       (get-in reply [:result :reason])))
                (is (not (contains? reply :value))))))
          (finally (jing/close! store)))))))
