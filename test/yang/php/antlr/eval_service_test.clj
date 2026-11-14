(ns yang.php.antlr.eval-service-test
  (:require [clojure.test :refer [deftest is]]
            [dao.jing.mem :as mem]
            [dao.stream :as stream]
            [yang.php.antlr.e2e-harness :as harness]
            [yang.php.antlr.eval-coordinator :as coordinator]
            [yang.php.antlr.eval-transport-test :as fixture]
            [yang.php.antlr.linked-harness :as linked]
            [yang.php.antlr.siteset :as siteset]
            [yang.core.module :as core-module]
            [yang.core.uast :as u]
            [yin.vm :as vm]
            [yin.vm.linker.interpreter :as link]
            [yin.vm.linker.publish :as publish]
            [yin.vm.module :as module]
            [yin.vm.values :as values]
            [yin.vm.test-utils :as tu]))


(deftest invalid-request-is-answered-on-stream
  (let [requests (:dao.stream/handle (tu/make-stream 8))
        responses (:dao.stream/handle (tu/make-stream 8))
        start (:dao.stream/cursor (stream/cursor requests stream/anchor-oldest))
        response-start (:dao.stream/cursor (stream/cursor responses stream/anchor-oldest))
        state {:requests requests :responses responses :cursor start
               :worker (coordinator/make-worker)}]
    (stream/append! requests (assoc fixture/request :scope {}))
    (stream/append! requests (assoc fixture/request :yang.php/source (str (char 255))))
    (coordinator/serve state)
    (let [read (stream/next responses response-start)
          answer (:dao.stream/value read)
          malformed (:dao.stream/value (stream/next responses (:dao.stream/cursor read)))]
      (is (= [:caller 1] (:yang.compile/request answer)))
      (is (= :yang.compile/rejected (:yang.compile/outcome answer)))
      (is (= :unknown-field (:yang.compile/detail answer)))
      (is (= :source-encoding (get-in malformed [:yang.compile/diagnostic :reason])))
      (is (= [:caller 1] (:yang.compile/request malformed))))))


(deftest response-refusal-keeps-request-cursor
  (let [requests (:dao.stream/handle (tu/make-stream 8))
        responses (:dao.stream/handle (tu/make-stream 8))
        cursor (:dao.stream/cursor (stream/cursor requests stream/anchor-oldest))
        state {:requests requests :responses responses :cursor cursor
               :worker (coordinator/make-worker)}]
    (stream/append! requests (assoc fixture/request :scope {}))
    (stream/close! responses)
    (let [blocked (coordinator/serve state)
          replacement (:dao.stream/handle (tu/make-stream 8))
          start (:dao.stream/cursor (stream/cursor replacement stream/anchor-oldest))
          resumed (coordinator/serve (assoc blocked :responses replacement))]
      (is (= cursor (:cursor blocked)))
      (is (:pending blocked))
      (is (not= cursor (:cursor resumed)))
      (is (nil? (:pending resumed)))
      (is (= :unknown-field (:yang.compile/detail
                              (:dao.stream/value (stream/next replacement start))))))))


(deftest valid-and-invalid-source-are-answered
  (let [requests (:dao.stream/handle (tu/make-stream 8))
        responses (:dao.stream/handle (tu/make-stream 8))
        start (:dao.stream/cursor (stream/cursor requests stream/anchor-oldest))
        response-start (:dao.stream/cursor (stream/cursor responses stream/anchor-oldest))
        state {:requests requests :responses responses :cursor start
               :worker (coordinator/make-worker)
               :store (mem/create-content-mem) :registry (linked/registry)}]
    (stream/append! requests fixture/request)
    (stream/append! requests (assoc fixture/request :yang.php/source "return (;"))
    (stream/append! requests (assoc fixture/request :yang.php/source "eval('return 1;');"))
    (coordinator/serve state)
    (let [first-read (stream/next responses response-start)
          published (:dao.stream/value first-read)
          second-read (stream/next responses (:dao.stream/cursor first-read))
          refused (:dao.stream/value second-read)
          unsupported (:dao.stream/value (stream/next responses (:dao.stream/cursor second-read)))]
      (is (= :yang.compile/published (:yang.compile/outcome published)) (pr-str published))
      (is (:yang.compile/address published))
      (is (:yang.compile/site-record published))
      (is (= :yang.compile/invalid-source (:yang.compile/reason refused)))
      (is (= [:caller 1] (:yang.compile/request refused)))
      (is (= :yang.compile/unsupported (:yang.compile/reason unsupported)))
      (is (= :eval (get-in unsupported [:yang.compile/diagnostic :construct]))))))


(deftest published-eval-is-admitted-by-site-stage
  (let [store (mem/create-content-mem)
        registry (linked/registry)
        hook (publish/publish-module! store {:name 'php.sp :ast (core-module/module-tree [])
                                             :exports #{} :requires {} :primitives {}})
        service (harness/eval-compiler {:store store :hook-address (:address hook)})
        requests (:requests service)
        responses (:responses service)
        response-cursor (:dao.stream/cursor (stream/cursor responses stream/anchor-oldest))
        requirement (:stage-requirement service)
        _ (stream/append! requests (assoc fixture/request :yang.php/source "while (false) {} return 1;"))
        _ (coordinator/serve service)
        result (:dao.stream/value (stream/next responses response-cursor))
        address (:yang.compile/address result)
        stage (siteset/stage registry requirement
                             {address [(:yang.compile/site-record result)]})
        name (:yang.compile/module result)
        admitted (stage {:store store :datoms [] :principals []} name address)
        source (link/composition {:content-store store
                                  :name-env {name address 'php.sp (:address hook)}
                                  :derivation :trusted :stage stage})
        pair (link/make-pair)
        program (u/seq-nodes [(u/app (u/v 'require) (u/lit 'code))
                              (u/app (u/v 'require) (u/lit name))
                              (u/app (u/v 'code/entry) (u/lit name)
                                     (u/lit (:address admitted)) (u/lit 'entry))])
        task ((:semantic linked/backends) program
                                          (linked/composition (module/register-code-module registry) pair))
        done (try (first (linked/drive task pair source))
                  (catch Exception e {:error (ex-data e)}))]
    (is (= :ok (:status admitted)) (pr-str admitted))
    (is (seq (:set (siteset/verify store address (:yang.compile/site-record result)))))
    (is (:derivation admitted))
    (is (= (:address admitted) (:yang.compile/active-address result)))
    (is (not= address (:address admitted)))
    (is (nil? (:error done)) (pr-str (:error done)))
    (is (not (vm/blocked? done)))
    (is (values/closure? (vm/value done)) (pr-str (vm/value done)))))
