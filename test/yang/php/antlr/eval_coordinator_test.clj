(ns yang.php.antlr.eval-coordinator-test
  (:require [clojure.test :refer [deftest is]]
            [dao.host.text :as text]
            [yang.antlr.packet :as packet]
            [yang.php.antlr.eval-admission :as admission]
            [yang.php.antlr.eval-coordinator :as coordinator]))


(defn- carrier
  [s]
  (text/units->string (map #(bit-and % 255) (text/utf8-encode s))))


(deftest admitted-source-and-identity
  (let [source "function f() { eval('return 1;'); } ?>é<?php return 2;"
        request {:yang.compile/request :r :yang.compile/unit :u
                 :yang.compile/provenance :p :yang.php/source (carrier source)}
        result (coordinator/compile-request (coordinator/make-worker) request)]
    (is (= :yang.cst/ok (get-in result [:cst :yang.cst/outcome])))
    (is (empty? (packet/conform (:cst result) source)))
    (is (true? (:dynamic? (first (vals (get-in result [:scope :scopes]))))))
    (is (= (carrier source) (get-in result [:identity :yang.php/source])))
    (is (= admission/profile-revision (get-in result [:identity :yang.php.eval/profile-revision])))
    (is (not= (:identity result)
              (:identity (coordinator/compile-request (coordinator/make-worker)
                                                      (assoc request :yang.php/source (carrier (str source " ")))))))))


(deftest malformed-source-is-refused
  (let [result (coordinator/compile-request (coordinator/make-worker)
                                            {:yang.compile/request :r :yang.compile/unit :u
                                             :yang.compile/provenance :p :yang.php/source (text/units->string [255])})]
    (is (= :yang.compile/rejected (:yang.compile/outcome result)))
    (is (= :source-encoding (get-in result [:yang.compile/diagnostic :reason])))
    (is (= :p (:yang.compile/provenance result)))))


(deftest original-byte-locations-and-goals
  (doseq [source ["" "return 'é中😀';\r\n?>html<?php echo 1;" "// é\nreturn 1;"]]
    (let [request {:yang.compile/unit :u :yang.php/source (carrier source)}
          result (coordinator/compile-request (coordinator/make-worker) request)
          pk (:cst result)
          byte-count (count (text/utf8-encode source))
          eof (last (filter #(and (= :token (:kind %)) (= "EOF" (:type %)))
                            (:yang.cst/nodes pk)))]
      (is (= :yang.cst/ok (:yang.cst/outcome pk)))
      (is (empty? (packet/conform pk source)))
      (is (= [byte-count byte-count] (:span eof)))
      (is (= (into [0] (keep-indexed (fn [i b] (when (= b 10) (inc i)))
                                     (text/utf8-encode source)))
             (:yang.php/line-starts pk)))))
  (let [request {:yang.compile/unit :u :yang.php/source (carrier "return 1;")}
        worker (coordinator/make-worker)]
    (is (not= (:identity (coordinator/compile-request worker request))
              (:identity (coordinator/compile-request worker (assoc request :revision 2)))))))
