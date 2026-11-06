(ns yang.php.antlr.programs-p5b2
  "P5b2 request-lifecycle completions: hand-derived PHP 8.3 expectations for
  the request merge, header/cookie/status and error-triggering fixes. Every
  fixture stays `:oracle :pending` for the shiva php:8.3 oracle pass.")


(defn request-array
  [entries]
  {:php/type :array, :index (into {} entries), :order (mapv first entries)})


(def base-request
  {:php.request/method "POST"
   :php.request/uri "/b2"
   :php.request/query (request-array [])
   :php.request/headers (request-array [])
   :php.request/cookies (request-array [])
   :php.request/body (request-array [])
   :php.request/server (request-array [])
   :php.request/env (request-array [])
   :php.request/files (request-array [])})


(defn- fx
  [id source request out & {:as more}]
  (merge {:id id, :group :p5b2-lifecycle, :source source, :request request,
          :out out, :oracle :pending}
         more))


(def numeric-merge-request
  (assoc base-request
         :php.request/query (request-array
                             [["ids" (request-array [[0 "10"] [1 "20"]])]
                               ["shared" "get"]])
         :php.request/body (request-array
                            [["ids" (request-array [[0 "30"]])]
                              ["shared" "post"]])))


(def nested-merge-request
  (assoc base-request
         :php.request/query (request-array
                             [["cfg" (request-array [["a" "1"] ["b" "2"]])]])
         :php.request/body (request-array
                            [["cfg" (request-array [["b" "3"] ["c" "4"]])]])))


(def fixtures
  [(fx :request-merge-numeric
       "<?php echo $_REQUEST['ids'][0], $_REQUEST['ids'][1], $_REQUEST['ids'][2], $_REQUEST['shared'];"
       numeric-merge-request "102030post"
       :diag [] :oracle :pending)
   (fx :request-merge-nested
       "<?php echo $_REQUEST['cfg']['a'], $_REQUEST['cfg']['b'], $_REQUEST['cfg']['c'];"
       nested-merge-request "134"
       :diag [] :oracle :pending)
   (fx :status-answers-previous
       "<?php $a = http_response_code(201); $b = http_response_code(202); var_dump($a); var_dump($b); var_dump(http_response_code());"
       base-request "int(200)\nint(201)\nint(202)\n"
       :diag [] :oracle :pending
       :response [{:php.response/status 201}
                  {:php.response/status 202}
                  {:php.response/body "int(200)\nint(201)\nint(202)\n"}])
   (fx :header-replace-and-status-arg
       "<?php header('X-A: 1', false); header('X-B: 2', true, 202); echo http_response_code();"
       base-request "202"
       :diag [] :oracle :pending
       :response [{:php.response/header "X-A: 1", :replace false}
                  {:php.response/header "X-B: 2", :replace true}
                  {:php.response/status 202}
                  {:php.response/body "202"}])
   (fx :setcookie-positional-options
       "<?php var_dump(setcookie('s', 'v', 1700003600, '/app', 'example.com', true, true));"
       base-request "bool(true)\n"
       :diag [] :oracle :pending
       :response [{:php.response/cookie
                    {:name "s", :value "v",
                     :options {:expires 1700003600, :path "/app",
                               :domain "example.com",
                               :secure true, :httponly true}}}
                  {:php.response/body "bool(true)\n"}])
   (fx :setcookie-options-array
       "<?php var_dump(setcookie('o', 'w', ['expires' => 1700003600, 'path' => '/', 'samesite' => 'Lax']));"
       base-request "bool(true)\n"
       :diag [] :oracle :pending
       :response [{:php.response/cookie
                    {:name "o", :value "w",
                     :options {:expires 1700003600, :path "/",
                               :samesite "Lax"}}}
                  {:php.response/body "bool(true)\n"}])
   (fx :trigger-user-error-is-fatal
       "<?php echo 'A'; trigger_error('boom', E_USER_ERROR); echo 'C';"
       base-request "A"
       :diag [[256 "boom"]] :oracle :pending
       :fatal "boom")
   (fx :trigger-invalid-level-rejected
       "<?php var_dump(trigger_error('x', 9999));"
       base-request "bool(false)\n"
       :diag [[2 "trigger_error(): Invalid error level"]] :oracle :pending
       :response [{:php.response/body "bool(false)\n"}])
   (fx :handler-mask-saved-and-restored
       (str "<?php set_error_handler(function($n, $s) { echo 'H', $n; return true; }, E_USER_WARNING);"
            " trigger_error('w1', E_USER_WARNING);"
            " trigger_error('n1', E_USER_NOTICE);"
            " restore_error_handler();"
            " trigger_error('w2', E_USER_WARNING);")
       base-request "H512"
       :diag [[1024 "n1"] [512 "w2"]] :oracle :pending
       :response [{:php.response/body "H512"}])])
