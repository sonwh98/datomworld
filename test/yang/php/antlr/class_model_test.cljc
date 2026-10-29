(ns yang.php.antlr.class-model-test
  "P4 S1: the class table and descriptors at prelude level
   (docs/design/yang.php.md section 3.7): declaration, inheritance, property
   identity, static sharing, constants, abstracts, trait specialization and
   enum descriptors. Programs here are guest expressions run under
   `php/run-main`; nothing is lowered from PHP source yet (S2)."
  (:require
    [clojure.test :refer [deftest is testing]]
    [yang.core.uast :as u]
    [yang.php.antlr.portable-harness :as h]
    [yang.php.antlr.programs-p4 :as p4]
    [yang.php.antlr.prelude :as prelude]
    [yin.vm :as vm]
    [yin.vm.test-utils :as tu]))


(defn- run-raw
  "The unit value of the prelude expression `form` run under `php/run-main`."
  [form]
  (let [program (u/mark-tails
                  (u/then prelude/uast
                          (u/app (u/v 'php/run-main) (u/lam [] (u/sexp->uast form)))))]
    (vm/value (vm/eval (tu/create-vm {:modules (h/host-registry)}) program))))


(defn- run
  [form]
  (h/render (run-raw form)))


(defn- uncaught-message
  [form]
  (-> (run-raw form) :php/uncaught :message))


(defn- spec-form
  "The guest expression that builds the Clojure data `x`: maps become `assoc`
   chains, vectors `php/vconj` chains; a list is already a guest expression."
  [x]
  (cond
    (map? x) (reduce (fn [acc [k v]] (list 'assoc acc k (spec-form v))) {} x)
    (vector? x) (reduce (fn [acc v] (list 'php/vconj acc (spec-form v))) [] x)
    :else x))


(defn- declare-form
  [spec]
  (list 'php/declare-class! (spec-form spec)))


(defn- seq-forms
  "A form evaluating `forms` in order and answering the last."
  [& forms]
  (cons 'do forms))


(defn- str-of
  "A form echoing the string forms `xs` joined with a comma."
  [& xs]
  (list 'php/echo (cons 'data/str-concat (interpose "," xs))))


(defn- yes
  "A form that is \"true\" or \"false\" for the boolean form `b`."
  [b]
  (list 'if b "true" "false"))


(defn- cls
  [name]
  (list 'php/class-lookup name))


(defn- prop-id-at
  [name i]
  (list 'get (list 'get (list 'get (cls name) :props-decl) i) :id))


(defn- method-field
  [cname m field]
  (list 'get (list 'php/class-method (cls cname) m) field))


(deftest declare-and-inherit-test
  (testing "a declared class is found case-insensitively; instanceof walks parent and interfaces"
    (is (= "A,true,true,true,false"
           (run (seq-forms
                  (declare-form {:name "I", :kind :interface})
                  (declare-form {:name "A", :interfaces ["I"]})
                  (declare-form {:name "B", :parent "A"})
                  (str-of (list 'get (cls "a") :name)
                          (yes '(php/class-is? "B" "a"))
                          (yes '(php/class-is? "B" "i"))
                          (yes '(php/instance-of (php/new-obj "B" (php/empty-array)) "a"))
                          (yes '(php/class-is? "A" "b"))))))))
  (testing "the builtin Throwable hierarchy is a real class table"
    (is (= "true,true,false"
           (run (str-of (yes '(php/class-is? "InvalidArgumentException" "logicexception"))
                        (yes '(php/class-is? "ArgumentCountError" "throwable"))
                        (yes '(php/class-is? "Error" "exception")))))))
  (testing "redeclaration, a missing or final parent, and a non-interface refuse"
    (is (= "Cannot declare class a, because the name is already in use"
           (uncaught-message (seq-forms (declare-form {:name "A"}) (declare-form {:name "a"})))))
    (is (= "Class \"P\" not found"
           (uncaught-message (declare-form {:name "A", :parent "P"}))))
    (is (= "Class B cannot extend final class A"
           (uncaught-message (seq-forms (declare-form {:name "A", :final? true})
                                        (declare-form {:name "B", :parent "A"})))))
    (is (= "B cannot implement A - it is not an interface"
           (uncaught-message (seq-forms (declare-form {:name "A"})
                                        (declare-form {:name "B", :interfaces ["A"]}))))))
  (testing "hoisting: declarable once the parent exists"
    (is (= "false,true"
           (run (str-of (yes '(php/class-declarable? (assoc {} :name "B" :parent "A")))
                        (list 'do
                              (declare-form {:name "A"})
                              (yes '(php/class-declarable? (assoc {} :name "B" :parent "A"))))))))))


(deftest property-identity-test
  (testing "a subclass's property does not collide with the parent's private one"
    (is (= "2,A::p,p"
           (run (seq-forms
                  (declare-form {:name "A", :props [{:name "p", :vis :private}]})
                  (declare-form {:name "B", :parent "A", :props [{:name "p"}]})
                  (str-of '(php/to-string (data/count (get (php/class-lookup "B") :props-decl)))
                          (prop-id-at "B" 0)
                          (prop-id-at "B" 1)))))))
  (testing "redeclaring an inherited public property keeps one slot, in the parent's position"
    (is (= "2,a,b,B"
           (run (seq-forms
                  (declare-form {:name "A", :props [{:name "a"} {:name "b"}]})
                  (declare-form {:name "B", :parent "A", :props [{:name "b"}]})
                  (str-of '(php/to-string (data/count (get (php/class-lookup "B") :props-decl)))
                          (prop-id-at "B" 0)
                          (prop-id-at "B" 1)
                          '(get (get (get (php/class-lookup "B") :props-decl) 1) :declaring))))))))


(deftest static-sharing-test
  (testing "an inherited static is the parent's cell; a redeclared one is a fresh cell"
    (is (= "5,5,1,0"
           (run (seq-forms
                  (declare-form {:name "A",
                                 :props [{:name "n", :static? true, :default 0}
                                         {:name "m", :static? true, :default 0}]})
                  (declare-form {:name "B", :parent "A",
                                 :props [{:name "m", :static? true, :default 0}]})
                  '(cell/set! (php/class-static-cell (php/class-lookup "A") "n") 5)
                  '(cell/set! (php/class-static-cell (php/class-lookup "A") "m") 1)
                  (str-of '(php/to-string (cell/get (php/class-static-cell (php/class-lookup "A") "n")))
                          '(php/to-string (cell/get (php/class-static-cell (php/class-lookup "B") "n")))
                          '(php/to-string (cell/get (php/class-static-cell (php/class-lookup "A") "m")))
                          '(php/to-string (cell/get (php/class-static-cell (php/class-lookup "B") "m")))))))))
  (testing "an undeclared static property refuses"
    (is (= "Access to undeclared static property A::$q"
           (uncaught-message (seq-forms (declare-form {:name "A"})
                                        '(php/class-static-cell (php/class-lookup "A") "q")))))))


(deftest constants-test
  (testing "a constant thunk runs once; interface and inherited constants are visible"
    (is (= "ev,3,3,k"
           (run (seq-forms
                  (declare-form {:name "I", :kind :interface, :consts [{:name "K", :value "k"}]})
                  (declare-form {:name "A", :interfaces ["I"],
                                 :consts [{:name "X", :thunk '(fn [] (do (php/echo "ev,") 3))}]})
                  (declare-form {:name "B", :parent "A"})
                  (str-of '(php/to-string (php/class-const (php/class-lookup "A") "X"))
                          '(php/to-string (php/class-const (php/class-lookup "B") "X"))
                          '(php/to-string (php/class-const (php/class-lookup "B") "K"))))))))
  (testing "an undefined constant refuses"
    (is (= "Undefined constant A::Z"
           (uncaught-message (seq-forms (declare-form {:name "A"})
                                        '(php/class-const (php/class-lookup "A") "Z")))))))


(deftest methods-and-abstracts-test
  (testing "own methods override inherited ones and keep their declaring class"
    (is (= "B,A,2"
           (run (seq-forms
                  (declare-form {:name "A", :methods [{:name "f", :code "a"} {:name "g", :code "ag"}]})
                  (declare-form {:name "B", :parent "A", :methods [{:name "F", :code "b"}]})
                  (str-of (method-field "B" "f" :declaring)
                          (method-field "B" "g" :declaring)
                          '(php/to-string (data/count (get (php/class-lookup "B") :methods)))))))))
  (testing "an unimplemented abstract (or interface) method refuses the concrete class"
    (is (= (str "Class B contains 1 abstract method and must therefore be declared "
                "abstract or implement the remaining methods (A::f)")
           (uncaught-message (seq-forms (declare-form {:name "A", :abstract? true,
                                                       :methods [{:name "f", :abstract? true}]})
                                        (declare-form {:name "B", :parent "A"})))))
    (is (= (str "Class C contains 2 abstract methods and must therefore be declared "
                "abstract or implement the remaining methods (I::a, I::b)")
           (uncaught-message (seq-forms (declare-form {:name "I", :kind :interface,
                                                       :methods [{:name "a"} {:name "b"}]})
                                        (declare-form {:name "C", :interfaces ["I"]}))))))
  (testing "implementing the method, or declaring the class abstract, is accepted"
    (is (= "ok"
           (run (seq-forms
                  (declare-form {:name "I", :kind :interface, :methods [{:name "a"}]})
                  (declare-form {:name "C", :interfaces ["I"], :methods [{:name "a", :code "x"}]})
                  (declare-form {:name "D", :interfaces ["I"], :abstract? true})
                  '(php/echo "ok"))))))
  (testing "a final method cannot be overridden"
    (is (= "Cannot override final method A::f()"
           (uncaught-message (seq-forms (declare-form {:name "A", :methods [{:name "f", :final? true}]})
                                        (declare-form {:name "B", :parent "A",
                                                       :methods [{:name "f"}]})))))))


(deftest trait-specialization-test
  (testing "trait methods are specialized to each using class; trait statics are per user"
    (is (= "A,B,true,false"
           (run (seq-forms
                  (declare-form {:name "T", :kind :trait,
                                 :methods [{:name "who", :make '(fn [c] c)}],
                                 :props [{:name "s", :static? true, :default 0}]})
                  (declare-form {:name "A", :traits ["T"]})
                  (declare-form {:name "B", :traits ["T"]})
                  (str-of (method-field "A" "who" :code)
                          (method-field "B" "who" :code)
                          (yes '(not (php/class-is? "A" "t")))
                          (yes '(= (php/class-static-cell (php/class-lookup "A") "s")
                                   (php/class-static-cell (php/class-lookup "B") "s")))))))))
  (testing "class methods over trait methods over inherited ones"
    (is (= "A:own,T,P"
           (run (seq-forms
                  (declare-form {:name "T", :kind :trait,
                                 :methods [{:name "m", :code "T"} {:name "n", :code "T"}]})
                  (declare-form {:name "P", :methods [{:name "m", :code "P"} {:name "n", :code "P"}
                                                      {:name "o", :code "P"}]})
                  (declare-form {:name "A", :parent "P", :traits ["T"],
                                 :methods [{:name "m", :code "own"}]})
                  (str-of (list 'data/str-concat (method-field "A" "m" :declaring) ":"
                                (method-field "A" "m" :code))
                          (method-field "A" "n" :code)
                          (method-field "A" "o" :code)))))))
  (testing "a trait property is merged under the using class's identity"
    (is (= "A::p"
           (run (seq-forms
                  (declare-form {:name "T", :kind :trait, :props [{:name "p", :vis :private}]})
                  (declare-form {:name "A", :traits ["T"]})
                  (str-of (prop-id-at "A" 0)))))))
  (testing "insteadof and as"
    (is (= "T1,T2,g"
           (run (seq-forms
                  (declare-form {:name "T1", :kind :trait, :methods [{:name "f", :code "T1"}]})
                  (declare-form {:name "T2", :kind :trait, :methods [{:name "f", :code "T2"}]})
                  (declare-form {:name "A", :traits ["T1" "T2"],
                                 :trait-rules {:insteadof [{:trait "T1", :method "f", :excluded ["T2"]}],
                                               :as [{:trait "T2", :method "f", :alias "g"}]}})
                  (str-of (method-field "A" "f" :code)
                          (method-field "A" "g" :code)
                          (method-field "A" "g" :name)))))))
  (testing "an unresolved collision refuses with PHP's text"
    (is (= "Trait method T2::f has not been applied as A::f, because of collision with T1::f"
           (uncaught-message (seq-forms (declare-form {:name "T1", :kind :trait, :methods [{:name "f"}]})
                                        (declare-form {:name "T2", :kind :trait, :methods [{:name "f"}]})
                                        (declare-form {:name "A", :traits ["T1" "T2"]}))))))
  (testing "a trait is not a parent and an unknown trait refuses"
    (is (= "Class B cannot extend trait T"
           (uncaught-message (seq-forms (declare-form {:name "T", :kind :trait})
                                        (declare-form {:name "B", :parent "T"})))))
    (is (= "Trait \"X\" not found"
           (uncaught-message (declare-form {:name "A", :traits ["X"]}))))))


(deftest enum-descriptor-test
  (testing "cases are singleton object constants; a backed enum carries its values"
    (is (= "2,true,true,true"
           (run (seq-forms
                  (declare-form {:name "S", :kind :enum, :backing :string,
                                 :cases [{:name "On", :value "on"} {:name "Off", :value "off"}]})
                  (str-of '(php/to-string (data/count (get (php/class-lookup "S") :cases)))
                          (yes '(= (php/class-const (php/class-lookup "S") "On")
                                   (php/class-const (php/class-lookup "S") "On")))
                          (yes '(php/class-is? "S" "unitenum"))
                          (yes '(php/class-is? "S" "backedenum"))))))))
  (testing "a pure enum is final, a UnitEnum and not a BackedEnum"
    (is (= "true,true,false"
           (run (seq-forms
                  (declare-form {:name "E", :kind :enum, :cases [{:name "A"}]})
                  (str-of (yes '(get (php/class-lookup "E") :final?))
                          (yes '(php/class-is? "E" "unitenum"))
                          (yes '(php/class-is? "E" "backedenum")))))))))


(deftest p4-registry-is-inert-test
  (testing "S0: the P4 fixtures are registered unconditionally, unrecorded, and none runs yet"
    (is (apply distinct? (map :id p4/all-fixtures)))
    (is (every? #(or (= :pending (:oracle %)) (= p4/banner (:oracle %))) p4/all-fixtures))
    (is (= #{:classes-v1 :constructor-v1 :exceptions-v1 :readonly-v1 :f3-v1 :declarations-v1 :closures-v1 :objects-v1
             :typed-ref-candidates}
           (set (map :group p4/all-fixtures))))
    (is (every? #(and (string? (:source %)) (seq (:requires %))) p4/all-fixtures))
    (is (every? #(re-matches #":p4-(s\d+|loc|exit|row\d+)" (str %)) (mapcat :requires p4/all-fixtures)))
    (is (every? #(every? p4/completed-steps (:requires %)) p4/enabled))
    (testing "every recorded oracle result equals the fixture's expectation"
      (is (every? (set (map :id p4/all-fixtures)) (keys p4/recorded)))
      (doseq [f p4/all-fixtures
              :let [r (get p4/recorded (:id f))]
              :when r]
        (is (= p4/banner (:oracle f)) (name (:id f)))
        (is (= (:out r) (:out f)) (name (:id f)))
        (is (= (:diag r) (vec (:diag f))) (name (:id f)))
        (is (= (:uncaught r) (:uncaught f)) (name (:id f)))))
    (testing "only the not-yet-recorded fixtures are still pending once enabled"
      (is (= p4/awaiting-oracle
             (set (map :id (filter #(= :pending (:oracle %)) p4/enabled))))))
    (is (not-any? #(contains? (:requires %) :p4-s7) p4/enabled))
    (is (every? #(= #{:p4-s7} (:requires %)) p4/generators-v1))
    (is (every? #(= :pending (:oracle %)) p4/generators-v1))
    (is (not-any? (set (map :id p4/all-fixtures)) (map :id p4/generators-v1)))
    (is (every? #(contains? p4/completed-steps %) (mapcat :requires p4/enabled)))))
