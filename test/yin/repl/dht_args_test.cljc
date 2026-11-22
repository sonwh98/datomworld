(ns yin.repl.dht-args-test
  "Subcommand expansion is host-free, including flags before the command."
  (:require [clojure.test :refer [deftest is]]
            [yin.repl.main :as main]
            [yin.shell.cli :as cli]
            [yin.shell.main :as shell]))


(def principal (apply str (repeat 64 "a")))
(def token (str "yin:127.0.0.1:4001/" principal))

(def ^:private join-paths
  ["--dir" "target/dht-args-default-reader" "--key" "target/dht-args-reader.key"])


(deftest join-after-global-flags-keeps-the-explicit-store
  (doseq [argv [["--index-store" "dht:reader" "--headless" "dht" "join" token]
                ["dht" "join" "--index-store" "dht:reader" "--headless" token]
                ["dht" "join" token "--index-store" "dht:reader" "--headless"]]]
    (let [[args extra] (cli/expand-args (into argv join-paths))
          opts (shell/parse-args args)
          spec (:index-store-spec opts)]
      (is (= "reader" (:dir spec)))
      (is (:headless? opts))
      (is (= [{:host "127.0.0.1" :port 4001}] (:peers spec)))
      (is (= [principal] (:principals opts)))
      (is (= [{:principal principal :host "127.0.0.1" :port 4001}]
             (:follow spec)))
      (is (= #{"--dht-publish" "--dht-key"} (:unset extra))))))


(deftest init-and-serve-after-global-flags-expand-like-command-first
  (doseq [verb ["init" "serve"]]
    (let [tail ["--dir" "node" "--key" "node.key" "--listen" "4002"
                "--peer" "127.0.0.1:4001"]
          prefixed (cli/expand-args (into ["--vm" "cesk" "dht" verb] tail))
          ordinary (cli/expand-args (into ["dht" verb] (into tail ["--vm" "cesk"])))]
      (is (= ordinary prefixed)))))


(deftest a-global-flag-value-is-never-a-subcommand
  (doseq [args [["--index-store" "dht"] ["--index-store" "keygen"]
                ["--name" "dht" "--index-store" "mem"]]]
    (is (= (main/plain-args args) (cli/expand-args args)))))


(deftest startup-expands-the-supplied-reader-command-before-parsing
  (let [seen (atom nil)
        composition (assoc shell/composition
                           :load-key (constantly nil)
                           :open (fn [spec _]
                                   (reset! seen spec)
                                   {:index-store nil}))
        started (main/startup
                  (into ["--reset" "--index-store" "dht:reader" "dht" "join" token]
                        join-paths)
                  {:persist? false :composition composition})]
    (is (nil? (:refusal started)))
    (is (= [{:host "127.0.0.1" :port 4001}] (:peers @seen)))
    (is (= [{:principal principal :host "127.0.0.1" :port 4001}]
           (:follow @seen)))))
