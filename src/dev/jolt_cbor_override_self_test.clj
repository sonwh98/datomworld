;; All copies live inside the worktree and are deleted even on test failure.
(require '[babashka.fs :as fs]
         '[babashka.process :as p]
         '[clojure.edn :as edn]
         '[clojure.string :as str]
         '[clojure.test :refer [deftest is run-tests testing]])


(load-file "src/dev/jolt_cbor_override_check.clj")
(require '[jolt-cbor-override-check :as guard])
(load-file "src/dev/jolt_j2_lane.clj")
(require '[jolt-j2-lane :as lane])


(deftest override-check
  (let [scratch (fs/create-temp-dir {:dir "." :prefix ".cbor-override-"})
        paths (into {} (map (fn [[k path]] [k (str (fs/path scratch path))])
                            guard/paths))
        shared (slurp (:shared guard/paths))
        jolt (slurp (:jolt guard/paths))
        record (edn/read-string (slurp (:reviewed guard/paths)))
        check #(guard/check! paths %)
        driver (str (fs/absolutize "src/dev/jolt_cbor_override_check.clj"))
        cli (fn [& args]
              (apply p/shell {:dir (str scratch) :continue true :shutdown nil :out :string :err :string}
                     "bb" driver args))]
    (try
      (doseq [[k dest] paths]
        (fs/create-dirs (fs/parent dest))
        (fs/copy (get guard/paths k) dest))
      (is (= :reviewed (:status (check false))))
      (testing "exact bytes drift, CLI refusal and update path"
        (spit (:shared paths) (str shared "\n;; shared change\n"))
        (is (thrown-with-msg? clojure.lang.ExceptionInfo #"non-blank reviewer note"
              (check true)))
        (is (thrown-with-msg? clojure.lang.ExceptionInfo
                              #"the shared dao.stream.cbor changed" (check false)))
        (let [{:keys [exit err]} (cli)]
          (is (= 1 exit))
          (is (str/includes? err guard/drift-message)))
        ;; A complete synthetic manifest lets every lane reach the digest gate.
        (doseq [[i ns-name] (map-indexed vector
                                         (concat lane/included (map :ns lane/excluded)))]
          (let [file (fs/path scratch "test/yin/vm" (str "stub_" i "_test.clj"))]
            (fs/create-dirs (fs/parent file))
            (spit (str file) (str "(ns " ns-name ")"))))
        (doseq [mode ["list" "core" "probe" "jvm"]]
          (let [{:keys [exit err]}
                (p/shell {:dir (str scratch) :continue true :shutdown nil
                          :out :string :err :string}
                         "bb" (str (fs/absolutize "src/dev/jolt_j2_lane.clj")) mode)]
            (is (= 1 exit))
            (is (str/includes? err guard/drift-message))))
        (is (not (fs/exists? (fs/path scratch "build"))))
        (doseq [args [["--update"] ["--update" "  "]]]
          (let [{:keys [exit err]} (apply cli args)]
            (is (= 1 exit))
            (is (str/includes? err "non-blank reviewer note"))))
        (is (= record (edn/read-string (slurp (:reviewed paths)))))
        (is (zero? (:exit (cli "--update" "Self-test reviewer, 2026-10-22"))))
        (is (= "Self-test reviewer, 2026-10-22"
               (:reviewer-note (edn/read-string (slurp (:reviewed paths))))))
        (is (= (guard/digest (:shared paths))
               (:sha256 (edn/read-string (slurp (:reviewed paths))))))
        (is (= :reviewed (:status (check false))))
        (is (= (dissoc record :sha256 :reviewer-note)
               (dissoc (edn/read-string (slurp (:reviewed paths))) :sha256 :reviewer-note))))
      ;; Reset digest and source to the reviewed baseline for API cases.
      (spit (:shared paths) shared)
      (spit (:reviewed paths) (pr-str record))
      (testing "missing public var"
        (spit (:jolt paths) (str/replace jolt "(defn decode" "(defn- decode"))
        (is (thrown-with-msg? clojure.lang.ExceptionInfo #"mismatch: decode" (check false))))
      (testing "arglist drift cannot be blessed by --update"
        (spit (:jolt paths) (str/replace jolt "[bytes]" "[bytes options]"))
        (is (thrown-with-msg? clojure.lang.ExceptionInfo #"mismatch: decode" (check false)))
        (is (= 1 (:exit (cli "--update" "Self-test reviewer, 2026-10-22"))))
        (is (= record (edn/read-string (slurp (:reviewed paths)))))
        (let [a (guard/public-api shared :clj)
              b (guard/public-api (slurp (:jolt paths)) :jolt)]
          (spit (:reviewed paths)
                (pr-str (assoc record :api-allowlist
                               {'decode {:shared (get a 'decode) :jolt (get b 'decode)
                                         :reason "Synthetic extra options argument for self-test"}})))
          (is (= :reviewed (:status (check false))))
          (spit (:jolt paths) jolt)
          (is (thrown-with-msg? clojure.lang.ExceptionInfo #"mismatch: decode" (check false)))))
      (testing "profile comparison"
        (spit (:reviewed paths) (pr-str record))
        (spit (:jolt paths) (str/replace jolt ":dao.stream.codec/frame-kind :binary" ":dao.stream.codec/frame-kind :text"))
        (is (thrown-with-msg? clojure.lang.ExceptionInfo #"mismatch: profile" (check false))))
      (testing "every public def initializer, including aliases, is compared exactly"
        (spit (:jolt paths) (str/replace jolt "\n  codec/host-bytes?)" "\n  identity)"))
        (is (thrown-with-msg? clojure.lang.ExceptionInfo #"mismatch: byte-payload\?" (check false)))
        (is (= 1 (:exit (cli "--update" "Self-test reviewer, 2026-10-22"))))
        (let [a (guard/public-api shared :clj)
              b (guard/public-api (slurp (:jolt paths)) :jolt)]
          (spit (:reviewed paths)
                (pr-str (assoc record :api-allowlist
                               {'byte-payload? {:shared (get a 'byte-payload?) :jolt (get b 'byte-payload?)
                                                :reason "Synthetic alias retarget for self-test"}})))
          (is (= :reviewed (:status (check false))))
          (spit (:jolt paths) (str/replace jolt "\n  codec/host-bytes?)" "\n  boolean)"))
          (is (thrown-with-msg? clojure.lang.ExceptionInfo #"mismatch: byte-payload\?" (check false)))))
      (testing "parity exceptions are required, including on update"
        (spit (:jolt paths) jolt)
        (doseq [invalid [(dissoc record :parity-exceptions)
                         (assoc record :parity-exceptions [])
                         (assoc record :parity-exceptions {})]]
          (spit (:reviewed paths) (pr-str invalid))
          (is (thrown-with-msg? clojure.lang.ExceptionInfo #"non-empty :parity-exceptions vector" (check false)))
          (is (= 1 (:exit (cli "--update" "Self-test reviewer, 2026-10-22"))))
          (is (= invalid (edn/read-string (slurp (:reviewed paths)))))))
      (testing "missing review record is a one-line preflight failure in every mode"
        (fs/delete (:reviewed paths))
        (doseq [mode [nil "list" "core" "probe" "jvm"]]
          (let [{:keys [exit err]} (if mode
                                     (p/shell {:dir (str scratch) :continue true :shutdown nil
                                               :out :string :err :string}
                                              "bb" (str (fs/absolutize "src/dev/jolt_j2_lane.clj")) mode)
                                     (cli))]
            (is (= 1 exit))
            (is (= ["Missing CBOR review record: src/jolt/dao/stream/cbor.reviewed.edn"]
                   (str/split-lines err))))))
      (finally (fs/delete-tree scratch)))))


(deftest extraction
  (let [source "(ns sample) (defn ^:private hidden [x] x)
                (defn attrs-private {:private true} [x] x)
                (defn- also-hidden [x] x)
                #?(:clj (defn f ([x] x) ([x y] y))
                   :default (defn f [x & xs] xs))
                (comment (def generated 1)) (def alias f)"]
    (is (= {'f {:kind 'defn :arglists '[[x] [x y]]}
            'alias {:kind 'def :arglists nil :value 'f}}
           (guard/public-api source :clj)))
    (is (= '[[x & xs]]
           (get-in (guard/public-api source :jolt) ['f :arglists])))))


(let [{:keys [fail error]} (run-tests)]
  (System/exit (if (zero? (+ fail error)) 0 1)))
