(ns yang.php.antlr.generators-test
  "P4 S8 generators, live iteration and protocol integration on the JVM."
  (:require
    [clojure.test :refer [deftest is testing]]
    [yang.php.antlr.e2e-harness :as h]
    [yang.php.antlr.programs-p4 :as p4]
    [yin.vm :as vm]
    [yin.vm.debruijn.register :as rvm]
    [yin.vm.debruijn.stack :as dvm]
    [yin.vm.engine :as engine]
    [yin.vm.semantic :as semantic]
    [yin.vm.semantic.derive :as derive]
    [yin.vm.semantic.linearize :as sr-linearize]
    [yin.vm.test-utils :as tu]
    [yin.vm.values :as values]))


(def ^:private vms [:ast-walker :semantic :stack :register])


(deftest generator-fixtures-on-every-vm-test
  (doseq [{:keys [id source out]} p4/generators-v1]
    (testing (name id)
      (let [results (h/run-php source)]
        (is (not (contains? results :diagnostics)) (pr-str results))
        (doseq [k vms]
          (is (= out (get results k)) (str (name id) " on " k)))))))


(deftest iteration-protocols-test
  (testing "Iterator drives foreach directly"
    (is (= "01" (:php/out (h/run-raw
                            "<?php class I implements Iterator { private $i=0; function rewind(): void {$this->i=0;} function valid(): bool {return $this->i<2;} function current(): mixed {return $this->i;} function key(): mixed {return $this->i;} function next(): void {$this->i++;} } foreach(new I as $v) echo $v;")))))
  (testing "IteratorAggregate supplies the live iterator"
    (is (= "23" (:php/out (h/run-raw
                            "<?php class I implements Iterator {private $i=2;function rewind():void{}function valid():bool{return $this->i<4;}function current(){return $this->i;}function key(){return $this->i;}function next():void{$this->i++;}} class A implements IteratorAggregate {function getIterator():Iterator{return new I;}} foreach(new A as $v)echo $v;")))))
  (testing "property iteration filters visibility from the calling scope"
    (is (= "aabc" (:php/out (h/run-raw
                              "<?php class P {public $a='a'; protected $b='b'; private $c='c'; function f(){foreach($this as $v)echo $v;}} $p=new P;foreach($p as $v)echo $v;$p->f();")))))
  (testing "ArrayAccess reads by value; Countable and Stringable dispatch"
    (is (= "43s" (:php/out (h/run-raw
                             "<?php class A implements ArrayAccess, Countable, Stringable {function offsetExists($x):bool{return true;} function offsetGet($x){return 4;} function offsetSet($x,$v):void{} function offsetUnset($x):void{} function count():int{return 3;} function __toString():string{return 's';}} $a=new A;echo $a[0],count($a),(string)$a;"))))))


(def ^:private opts
  {:make-stream tu/make-stream
   :capability-secret tu/secret
   :primitives vm/primitives
   :modules (h/host-registry)})


(def ^:private load-semantic
  (vm/fresh-code-loader semantic/load-ast vm/ast-contract))


(def ^:private final-vms
  {:ast-walker (fn [ast _] (vm/eval (tu/create-vm opts) ast))
   :semantic (fn [_ datoms] (vm/run (load-semantic (semantic/create-vm opts) datoms)))
   :stack (fn [ast _]
            (vm/run (dvm/create-vm
                      (:image (derive/stack-image (:vector (sr-linearize/project ast))))
                      (assoc opts :contract vm/stack-contract))))
   :register (fn [ast _]
               (vm/run (rvm/create-vm
                         (derive/register-image (:vector (sr-linearize/project ast)))
                         (assoc opts :contract vm/register-contract))))})


(defn- deep-size
  [x]
  (cond
    (values/host-typed? x) (+ 1 (deep-size (values/payload x)))
    (map? x) (reduce + 1 (map deep-size (concat (keys x) (vals x))))
    (coll? x) (reduce + 1 (map deep-size x))
    :else 1))


(defn- live-after-collection
  [k source]
  (let [lowered (:ast (h/lower-program source))
        main (get-in lowered [:operator :body :operands 0])
        ast (assoc-in lowered [:operator :body]
                      {:type :application :operator main :operands []})
        final ((get final-vms k) ast (vm/ast->datoms ast))
        roots (into []
                    (keep (fn [[id {:keys [value seal]}]]
                            (when (and (= "h" (get-in value [:gen :name]))
                                       (= :suspended (get-in value [:gen :state])))
                              {:type :cell-ref :id id :seal seal})))
                    (:heap final))
        heap (:heap (engine/collect final roots))]
    (is (= 1 (count roots)) (str k " one suspended delegating generator"))
    [(count heap) (reduce + (map (fn [[_ {:keys [value]}]] (deep-size value)) heap))]))


(defn- changing-caller-source
  [n]
  (str "<?php function g() { $i=0; while (true) { yield $i; $i++; } }"
       " function h() { yield from g(); } $it=h(); $it->self=$it;"
       " function byFunction($x) { $x->next(); }"
       " function byGenerator($x) { yield $x->current(); $x->next(); }"
       " $k=0; while ($k < " n ") {"
       " if ($k % 2 == 0) { byFunction($it); }"
       " else { $j=byGenerator($it); $j->current(); $j->next(); } $k++; }"))


(deftest ^:slow reachable-heap-is-stable-under-delegation-test
  (doseq [k vms]
    (testing (str k)
      (is (= (live-after-collection k (changing-caller-source 10))
             (live-after-collection k (changing-caller-source 1000)))))))
