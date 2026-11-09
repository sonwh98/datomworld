(ns yang.php.antlr.p5b2-lifecycle-test
  "P5b2 regressions: the request lifecycle, merge, header/cookie/status and
  error-triggering completions. Expectations are hand-derived from PHP 8.3;
  every fixture stays available to the oracle recording pass."
  (:require
    [clojure.test :refer [deftest is testing]]
    [clojure.walk :as walk]
    [yang.php.antlr.cst :as cst]
    [yang.php.antlr.e2e-harness :as h]
    [yang.php.antlr.linked-harness :as lh]
    [yang.php.antlr.portable-harness :as ph]
    [yang.php.antlr.prelude :as prelude]
    [yang.php.antlr.programs-p5b2 :as p5b2]))


(defn- diagnostics
  [r]
  (mapv (juxt :errno :message) (:php/diag r)))


(deftest p5b2-lifecycle-fixtures-test
  (doseq [{:keys [id source request out diag response status fatal uncaught]} p5b2/fixtures]
    (testing (name id)
      (let [r (h/run-raw source request)]
        (is (= out (:php/out r)))
        (is (= (vec diag) (diagnostics r)))
        (when response (is (= response (:php/response r))))
        (when status (is (= status (:php/status r))))
        (when fatal (is (= fatal (:php/fatal r))))
        (when-not fatal (is (nil? (:php/fatal r))))
        (if uncaught
          (is (= uncaught (select-keys (:php/uncaught r) [:class :message])))
          (is (nil? (:php/uncaught r))))))))


(deftest run-main-consumes-request-without-rewrite-test
  (testing "production initialization seeds superglobals from the operand"
    (let [r (h/run-raw "<?php echo $_GET['v'];" (assoc p5b2/base-request
                                                       :php.request/query
                                                       (p5b2/request-array [["v" "Q"]])))]
      (is (= "Q" (:php/out r)))
      (is (= [] (:php/diag r))))))


(deftest stream-lifecycle-consumes-and-emits-over-streams-test
  (testing "the request consumed from the request stream seeds the superglobals"
    (let [request (assoc p5b2/base-request
                         :php.request/query (p5b2/request-array [["v" "Q"]])
                         :php.request/body (p5b2/request-array [["w" "W"]]))
          source "<?php echo $_GET['v'], $_POST['w'], $_REQUEST['v'], $_REQUEST['w'];"
          {:keys [value response diag] :as lifecycle}
          (ph/run-lifecycle-raw (cst/parse-source source) request)]
      (is (= request (:request lifecycle)))
      (is (= [{:php.response/body "QWQW"}] response))
      (is (= [] diag))
      (is (= response (:php/response value)))
      (is (= diag (:php/diag value)))
      (is (= "QWQW" (:php/out value)))
      (is (= "QWQW" (:php/out (h/run-raw source request))))))
  (testing "diagnostic records are emitted on the diagnostics stream"
    (let [source "<?php trigger_error('boom', E_USER_NOTICE); echo 'done';"
          {:keys [value response diag]}
          (ph/run-lifecycle-raw (cst/parse-source source) p5b2/base-request)]
      (is (= [[1024 "boom"]] (mapv (juxt :errno :message) diag)))
      (is (= diag (:php/diag value)))
      (is (= [{:php.response/body "done"}] response))))
  (testing "response records keep header-then-body emission order on the stream"
    (let [source "<?php header('X-A: 1'); echo 'hi';"
          {:keys [value response diag]}
          (ph/run-lifecycle-raw (cst/parse-source source) p5b2/base-request)]
      (is (= [] diag))
      (is (= response (:php/response value)))
      (is (= "X-A: 1" (:php.response/header (first response))))
      (is (= {:php.response/body "hi"} (last response)))))
  (testing "a nil request keeps the empty defaults through the streams"
    (let [{:keys [value response diag] :as lifecycle}
          (ph/run-lifecycle-raw (cst/parse-source "<?php echo 'none';") nil)]
      (is (nil? (:request lifecycle)))
      (is (= [{:php.response/body "none"}] response))
      (is (= [] diag))
      (is (= "none" (:php/out value))))))


(defn- run-main-applications
  "Every `php/run-main` application node in lowered `ast`."
  [ast]
  (let [found (volatile! [])]
    (walk/postwalk (fn [node]
                     (when (and (map? node)
                                (= :application (:type node))
                                (= 'php/run-main (get-in node [:operator :name])))
                       (vswap! found conj node))
                     node)
                   ast)
    @found))


(deftest lowering-splices-stream-operands-test
  (testing "stream references ride as run-main operands three and four"
    (let [pk (cst/parse-source "<?php echo 'x';")
          rref {:type :stream-ref :id "s1"}
          dref {:type :stream-ref :id "s2"}
          {:keys [ast]} (ph/lower-through-stage pk nil rref dref)
          apps (run-main-applications ast)]
      (is (= 1 (count apps)))
      (is (= 4 (count (:operands (first apps)))))
      (is (= rref (:value (nth (:operands (first apps)) 2))))
      (is (= dref (:value (nth (:operands (first apps)) 3))))))
  (testing "without streams run-main keeps two operands"
    (let [{:keys [ast]} (ph/lower-through-stage (cst/parse-source "<?php echo 'x';"))]
      (is (= [2] (mapv (comp count :operands) (run-main-applications ast)))))))


(defn- linked-out
  [source request]
  (let [{:keys [values]} (lh/run-fixture {"main.php" (cst/parse-source source)}
                                         "main.php"
                                         {:request request})]
    (into {} (map (fn [[k v]] [k (:php/out v)]) values))))


(defn- request-data-nodes
  "Every compiled-program node embedding request data: map literals carrying
   request-record keys. The composition path must never produce one."
  [ast]
  (let [found (volatile! [])]
    (walk/postwalk (fn [node]
                     (when (and (map? node)
                                (some #(contains? node %) [:php.request/method
                                                           :php.request/uri
                                                           :php.request/query
                                                           :php.request/body]))
                       (vswap! found conj node))
                     node)
                   ast)
    @found))


(deftest request-never-enters-compiled-uast-test
  (testing "the composed second operand is the opaque stream reference"
    (let [pk (cst/parse-source "<?php echo $_GET['v'];")
          ref {:type :stream-ref :id "req-0"}
          {:keys [ast]} (ph/lower-through-stage pk ref)
          apps (run-main-applications ast)]
      (is (= 1 (count apps)))
      (is (= ref (:value (nth (:operands (first apps)) 1))))))
  (testing "no request data is embedded in the compiled program"
    (let [pk (cst/parse-source "<?php echo $_GET['v'];")
          ref {:type :stream-ref :id "req-0"}
          {:keys [ast]} (ph/lower-through-stage pk ref nil nil)]
      (is (empty? (request-data-nodes ast)))))
  (testing "the lifecycle run still seeds superglobals from the stream"
    (let [request (assoc p5b2/base-request
                         :php.request/query (p5b2/request-array [["v" "Q"]]))
          {:keys [value]} (ph/run-lifecycle-raw
                            (cst/parse-source "<?php echo $_GET['v'];")
                            request)]
      (is (= "Q" (:php/out value))))))


(deftest setcookie-validates-before-headers-sent-test
  (testing "excess arguments throw ArgumentCountError even after output"
    (let [r (h/run-raw (str "<?php echo 'x'; var_dump(setcookie('s', 'v', 0, '/', "
                            "'example.com', false, false, 'Lax'));")
                       p5b2/base-request)]
      (is (= "x" (:php/out r)))
      (is (= "ArgumentCountError" (get-in r [:php/uncaught :class])))
      (is (= "setcookie() expects at most 7 arguments, 8 given"
             (get-in r [:php/uncaught :message])))))
  (testing "an unknown option throws ValueError even after output"
    (let [r (h/run-raw (str "<?php echo 'x'; var_dump(setcookie('o', 'w', "
                            "['bogus' => 1]));")
                       p5b2/base-request)]
      (is (= "x" (:php/out r)))
      (is (= "ValueError" (get-in r [:php/uncaught :class])))))
  (testing "a valid setcookie after output still warns and answers false"
    (let [r (h/run-raw "<?php echo 'x'; var_dump(setcookie('s', 'v'));"
                       p5b2/base-request)]
      (is (= "xbool(false)\n" (:php/out r)))
      (is (= [[2 "Cannot modify header information - headers already sent"]]
             (mapv (juxt :errno :message) (:php/diag r)))))))


(deftest linked-profile-consumes-request-and-isolates-test
  (testing "the :linked entry point takes the request operand too"
    (let [r (linked-out "<?php echo $_GET['v'], $_REQUEST['w'];"
                        (assoc p5b2/base-request
                               :php.request/query (p5b2/request-array [["v" "Q"]])
                               :php.request/body (p5b2/request-array [["w" "W"]])))]
      (is (= {:semantic "QW", :stack "QW", :register "QW"} r))))
  (testing "sequential linked runs do not leak request state"
    (let [src "<?php echo $_GET['v'];"
          q (fn [v]
              (assoc p5b2/base-request
                     :php.request/query (p5b2/request-array [["v" v]])))]
      (is (= {:semantic "one", :stack "one", :register "one"} (linked-out src (q "one"))))
      (is (= {:semantic "two", :stack "two", :register "two"} (linked-out src (q "two"))))))
  (testing "no live request cell is a module export"
    (is (not (contains? prelude/module-exports 'rt/request)))
    (is (not (contains? prelude/module-exports 'rt/response)))
    (is (not (contains? prelude/module-exports 'rt/out)))
    (is (not (contains? prelude/module-exports 'rt/diag)))))
