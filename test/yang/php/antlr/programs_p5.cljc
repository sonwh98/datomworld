(ns yang.php.antlr.programs-p5
  "P5 request-context fixtures. Expectations are hand-derived from PHP 8.3;
  every fixture remains available to the oracle recording pass.")


(defn request-array
  [entries]
  {:php/type :array, :index (into {} entries), :order (mapv first entries)})


(def request
  {:php.request/method "POST"
   :php.request/uri "/hello?a=1"
   :php.request/query (request-array [["a" "1"] ["shared" "get"]])
   :php.request/headers (request-array [["X-Test" "yes"]])
   :php.request/cookies (request-array [["session" "abc"]])
   :php.request/body (request-array [["b" "2"] ["shared" "post"]])
   :php.request/server (request-array [["SERVER_NAME" "fixture.test"]])
   :php.request/env (request-array [["APP_ENV" "test"]])
   :php.request/files (request-array [["upload" "fixture"]])})


(defn- fx
  [id source out & {:as more}]
  (merge {:id id, :group :request-context, :source source, :out out,
          :request request, :oracle :pending}
         more))


(def fixtures
  [(fx :superglobals-from-request
       (str "<?php echo $_GET['a'], $_POST['b'], $_COOKIE['session'],"
            " $_SERVER['REQUEST_METHOD'], $_SERVER['REQUEST_URI'],"
            " $_SERVER['SERVER_NAME'], $_ENV['APP_ENV'], $_FILES['upload'],"
            " $_REQUEST['a'], $_REQUEST['b'], $_REQUEST['shared'];")
       "12abcPOST/hello?a=1fixture.testtestfixture12post" :diag [] :oracle "php-8.3.35 shiva 2026-10-25")
   (fx :output-buffering
       "<?php ob_start(); echo 'a'; echo ob_get_contents(); ob_start(); echo 'b'; echo ob_get_clean(); echo ob_get_clean(); echo 'c';"
       "aabc" :diag [] :oracle "php-8.3.35 shiva 2026-10-25")
   (fx :output-buffer-flush
       "<?php ob_start(); echo 'a'; ob_end_flush(); echo 'b'; var_dump(ob_end_clean());"
       "abbool(false)\n" :diag [[8 "ob_end_clean(): Failed to delete buffer. No buffer to delete"]] :oracle "php-8.3.35 shiva 2026-10-25")
   (fx :headers-cookies-status
       "<?php header('X-One: 1'); setcookie('sid', 'abc'); http_response_code(201); echo http_response_code();"
       "201" :diag [] :oracle "php-8.3.35 shiva 2026-10-25"
       :response [{:php.response/header "X-One: 1", :replace true}
                  {:php.response/cookie {:name "sid", :value "abc"}}
                  {:php.response/status 201}
                  {:php.response/body "201"}])
   (fx :headers-after-output
       "<?php echo 'x'; header('X-Late: 1'); var_dump(headers_sent());"
       "xbool(true)\n" :diag [[2 "Cannot modify header information - headers already sent by (output started at /w/headers-after-output.php:1)"]] :oracle "php-8.3.35 shiva 2026-10-25"
       :response [{:php.response/body "xbool(true)\n"}])
   (fx :error-handler-and-warnings
       (str "<?php set_error_handler(function($n, $s) { echo 'H', $n, ':', $s; });"
            " trigger_error('first', E_USER_WARNING); restore_error_handler();"
            " trigger_error('second', E_USER_NOTICE);")
       "H512:first" :diag [[8 "second"]] :oracle "php-8.3.35 shiva 2026-10-25")
   (fx :exit-runs-shutdown
       "<?php register_shutdown_function(function() { echo 'S'; }); echo 'A'; exit(7); echo 'B';"
       "AS" :diag [] :oracle "php-8.3.35 shiva 2026-10-25"
       :status 7)
   (fx :exit-string
       "<?php echo 'A'; die('B'); echo 'C';"
       "AB" :diag [] :oracle "php-8.3.35 shiva 2026-10-25"
       :status 0)])
