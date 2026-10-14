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
      (is (= #{} (get-in reset [:link-source :admission :trusted])))
      (is (= #{pa} (get-in reset [:link-source :admission :decision]))))
    (let [state (repl/create-state {:dht-key a :admission {:trusted #{pb}}})
          [state _] (repl/eval-input state (str "(untrust " (pr-str pb) ")"))
          [state _] (repl/eval-input state (str "(trust " (pr-str pa) ")"))
          [reset _] (repl/eval-input state "(reset)")]
      (is (= #{pb} (get-in reset [:admission :trusted])))
      (is (= #{pa pb} (get-in reset [:link-source :admission :decision]))))
    (is (str/includes? repl/help-text "(trusted)"))
    (is (str/includes? (str/join "\n" main/help-lines) "(trusted)"))))


(deftest cli-and-session-share-the-principal-parser-test
  (let [hex (:public (sign/generate))
        p (sign/principal hex)]
    (is (= p (config/parse-trust hex) (main/parse-trust hex)))
    (is (= p (config/parse-trust p) (main/parse-trust p)))))
