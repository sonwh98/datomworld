(ns yin.repl.trust-test
  "Session trust configuration, validation, display and reset."
  (:require [clojure.string :as str]
            [clojure.test :refer [deftest is]]
            [yin.repl :as repl]
            [yin.repl.main :as main]
            [yin.repl.state :as config]
            [yin.vm.linker.sign :as sign]))


(deftest trusted-display-validation-and-reset-test
  (let [a (sign/generate)
        b (sign/generate)
        pa (sign/principal (:public a))
        pb (sign/principal (:public b))
        initial (repl/create-state {:dht-key a})
        [state _] (repl/eval-input initial (str "(trust " (pr-str pb) ")"))
        [state text] (repl/eval-input state "(trusted)")]
    (is (str/includes? text pa))
    (is (str/includes? text pb))
    (doseq [form ["(trust)" "(trust 1)" "(trust \"ed25519:no\")"
                  (str "(trust " (pr-str (:public b)) ")")
                  (str "(untrust " (pr-str pb) " " (pr-str pa) ")")]]
      (let [[same text] (repl/eval-input state form)]
        (is (str/includes? text "Error:") text)
        (is (= (:admission state) (:admission same)))
        (is (= (:linker state) (:linker same)))))
    (let [[reset _] (repl/eval-input state "(reset)")]
      (is (= {} (:admission reset))))
    (let [state (repl/create-state {:dht-key a :admission {:trusted #{pb}}})
          [state _] (repl/eval-input state (str "(untrust " (pr-str pb) ")"))
          [state _] (repl/eval-input state (str "(trust " (pr-str pa) ")"))
          [reset _] (repl/eval-input state "(reset)")]
      (is (= #{pb} (get-in reset [:admission :trusted]))))
    (is (str/includes? repl/help-text "(trusted)"))
    (is (str/includes? (str/join "\n" main/help-lines) "(trusted)"))))


(deftest cli-and-session-share-the-principal-parser-test
  (let [hex (:public (sign/generate))
        p (sign/principal hex)]
    (is (= p (config/parse-trust hex) (main/parse-trust hex)))
    (is (= p (config/parse-trust p) (main/parse-trust p)))))


(deftest external-trust-records-configuration-test
  (let [p (sign/principal (:public (sign/generate)))
        initial (repl/create-state {:admission :external})
        [state text] (repl/eval-input initial
                                      (str "(trust " (pr-str p) ")"))]
    (is (= (str "Recorded trust for " p
                "; unused while admission is external") text))
    (is (= #{p} (get-in state [:admission :trusted])))
    (is (= (:linker initial) (:linker state)))
    (let [[_ text] (repl/eval-input state
                                    (str "(trust " (pr-str p) ")"))]
      (is (str/includes? text "unused while admission is external")))))


(deftest own-principal-is-always-trusted-test
  (let [key (sign/generate)
        p (sign/principal (:public key))
        initial (repl/create-state {:dht-key key :admission {:trusted #{p}}})
        [state text] (repl/eval-input initial
                                      (str "(untrust " (pr-str p) ")"))]
    (is (= (str "Own principal " p " is always trusted") text))
    (is (= initial state))))


(deftest invalid-principal-error-is-source-neutral-test
  (let [state (repl/create-state {})
        [_ text] (repl/eval-input state "(trust \"ed25519:no\")")]
    (is (str/includes? text "invalid principal id"))
    (is (not (str/includes? text "--dht-trust")))))
