(ns yang.php-literal-test
  "PHP number literals over the primitive profile, on every lane
   (docs/design/dao.stream.host-boundary.md §8 H2, table D row (4)):
   integers exact in their canonical carrier, floats the nearest double."
  (:require
    [clojure.string :as str]
    [clojure.test :refer [deftest is testing]]
    [dao.host.integer :as host-integer]
    [yang.php :as php]))


(defn- decimal-text
  [v]
  (host-integer/to-radix-string (host-integer/promote v) 10))


(deftest integer-literals-are-exact-in-their-canonical-carrier
  (testing "a small literal is a native carrier"
    (let [v (php/php-number "12")]
      (is (host-integer/safe-native? v))
      (is (= 12 v))))
  (testing "2^53 + 1 parses to that exact integer (a big carrier on JS)"
    (let [v (php/php-number "9007199254740993")]
      (is (host-integer/exact-integer? v))
      (is (= "9007199254740993" (decimal-text v)))
      (is (not (host-integer/safe-native? v)))
      #?(:cljs (is (host-integer/big-carrier? v)))))
  (testing "a 400-digit literal is an exact big"
    (let [text (str "1" (apply str (repeat 399 "7")))
          v (php/php-number text)]
      (is (host-integer/big-carrier? v))
      (is (= text (decimal-text v)))))
  (testing "through the literal parser"
    (is (= {:php-type :literal :value 12} (php/parse-php-literal [:number "12"])))))


(deftest float-literals-are-the-nearest-double
  (is (= 1.5 (php/php-number "1.5")))
  (is (= 0.1 (php/php-number "0.1")))
  (is (= 1.0 (php/php-number "1.")) "the lexer's digits-dot form")
  (is (= ##Inf (php/php-number (str (str/join (repeat 400 "9")) ".0"))))
  (is (host-integer/host-float? (php/php-number "2.0"))))
