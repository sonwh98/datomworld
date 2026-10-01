(ns yin.vm.linker.sign-test
  (:require [clojure.edn :as edn]
            [clojure.test :refer [deftest is]]
            [dao.jing :as jing]
            [yin.vm.linker.authority :as authority]
            [yin.vm.linker.sign :as sign]
            #?@(:cljd [["dart:io" :as dart-io]])))


(defn- vectors
  []
  (edn/read-string
    #?(:cljd (.readAsStringSync
               (dart-io/File. "test/yin/vm/linker/sign_vectors.edn"))
       :clj (slurp "test/yin/vm/linker/sign_vectors.edn")
       :cljs (.readFileSync (js/require "fs")
                            "test/yin/vm/linker/sign_vectors.edn" "utf8"))))


(defn- hex
  [bytes]
  (apply str (mapcat (fn [b]
                       (let [n (bit-and b 255)
                             digits "0123456789abcdef"]
                         [(nth digits (quot n 16))
                          (nth digits (mod n 16))])) bytes)))


(deftest rfc-8032-vectors
  (doseq [{:keys [seed public message signature]} (:rfc (vectors))]
    (is (= public (sign/public-of seed)))
    (is (= signature (sign/sign-message seed message)))))


(deftest canonical-envelope-vectors
  (let [v (vectors)
        seed (get-in v [:rfc 0 :seed])
        public (get-in v [:rfc 0 :public])]
    (doseq [case-key [:assertion :retraction]]
      (let [{:keys [envelope canonical message signature id]}
            (get v case-key)]
        (is (= canonical (hex (jing/canonical-bytes envelope))))
        (is (= message (str "79696e2e6d6f64756c652f656e76656c6f70653a76310a"
                            canonical)))
        (is (= signature
               (:yin.module/signature (sign/sign-envelope seed envelope))))
        (is (= id (jing/segment-key envelope)))
        (is (sign/verify-envelope public (jing/canonical-bytes envelope)
                                  signature))))))


(defn- bad-proof?
  [env proof public]
  (let [result (authority/name-environment
                 {:snapshot :test
                  :principals
                  {(:yin.module/asserted-by env)
                   {:proof :yin.module/signature
                    :key public :verify sign/verify-envelope :seq-floor 0}}}
                 [{:yin.module/envelope env :yin.module/proof proof}])]
    (some #(= :bad-proof (:kind %)) (:diagnostics result))))


(defn- resolution
  [env proof public]
  (authority/name-environment
    {:snapshot :test
     :principals {(:yin.module/asserted-by env)
                  {:proof :yin.module/signature
                   :key public :verify sign/verify-envelope :seq-floor 0}}}
    [{:yin.module/envelope env :yin.module/proof proof}]))


(deftest tampering-is-bad-proof
  (let [v (vectors)
        env (get-in v [:assertion :envelope])
        proof {:yin.module/signature
               (get-in v [:assertion :signature])}
        public (get-in v [:rfc 0 :public])
        t (:tamper v)]
    (is (= :ok (get-in (resolution env proof public)
                       [:names 'my.lib :status])))
    (doseq [tampered [(assoc env :yin.module/name (:name t))
                      (assoc env :yin.module/manifest (:manifest t))
                      (assoc env :yin.module/seq (:seq t))
                      {:yin.module/op :retract
                       :yin.module/of (jing/segment-key env)
                       :yin.module/asserted-by (:yin.module/asserted-by env)
                       :yin.module/seq (:yin.module/seq env)}
                      (assoc env :yin.module/asserted-by
                             (sign/principal (:wrong-public t)))]]
      (is (bad-proof? tampered proof public)))
    (is (bad-proof? env
                    {:yin.module/signature
                     (:bad-signature t)}
                    public))
    (is (bad-proof? env proof (:wrong-public t)))
    (is (bad-proof? (assoc env :yin.module/asserted-by
                           (sign/principal (:wrong-public t)))
                    proof (:wrong-public t)))
    (is (bad-proof? env
                    {:yin.module/signature
                     (:no-prefix-signature t)}
                    public))))


(deftest key-file-validation
  (let [key (select-keys (first (:rfc (vectors))) [:seed :public])]
    (is (= key (sign/key-from-text (sign/key-text key))))
    (is (= key (sign/key-from-text (str (sign/key-text key) "\n"))))
    (doseq [[s reason]
            [["" :yin.link.sign/malformed-key]
             [(str (sign/key-text key) " 42")
              :yin.link.sign/malformed-key]
             [(str (sign/key-text key) "] [42")
              :yin.link.sign/malformed-key]
             [(str (sign/key-text key) "}} {:x {")
              :yin.link.sign/malformed-key]
             ["{}" :yin.link.sign/key-shape]
             [(pr-str (assoc key :version 2 :algorithm :ed25519))
              :yin.link.sign/version]
             [(pr-str (assoc key :version 1 :algorithm :rsa))
              :yin.link.sign/algorithm]
             [(pr-str (assoc key :version 1 :algorithm :ed25519 :extra 1))
              :yin.link.sign/key-shape]
             [(pr-str (assoc key :version 1 :algorithm :ed25519
                             :seed (str "A" (subs (:seed key) 1))))
              :yin.link.sign/seed]
             [(pr-str (assoc key :version 1 :algorithm :ed25519
                             :public (str "A" (subs (:public key) 1))))
              :yin.link.sign/public]
             [(sign/key-text (assoc key :public
                                    (get-in (vectors) [:rfc 1 :public])))
              :yin.link.sign/public-mismatch]]]
      (is (= {:status :refused :reason reason}
             (sign/key-from-text s))))))
