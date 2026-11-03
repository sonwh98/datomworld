(require '[clojure.java.io :as io]
         '[yang.php.antlr.cst :as cst]
         '[yang.php.antlr.e2e-harness :as h])


(def samples
  {:basic-math "10 + 20;"
   :closure-power "$makePower = function (int $exponent) {\n  return function (int $base) use ($exponent): int {\n    $result = 1;\n    for ($i = 0; $i < $exponent; $i++) {\n      $result *= $base;\n    }\n    return $result;\n  };\n};\n$cube = $makePower(3);\n$cube(2);"
   :factorial "function fact($n) {\n  if ($n == 0) {\n    return 1;\n  } else {\n    return $n * fact($n - 1);\n  }\n}\nfact(5);"
   :fibonacci "function fib($n) {\n  if ($n < 2) {\n    return $n;\n  } else {\n    return fib($n - 1) + fib($n - 2);\n  }\n}\nfib(7);"})


(let [target (io/file "src/cljc/yang/php/antlr/demo_packets.cljc")
      forms (into (sorted-map)
                  (map (fn [[id source]]
                         [id (h/packet->form (cst/parse-source (str "<?php " source)))])
                       samples))]
  (io/make-parents target)
  (spit target
        (str "(ns yang.php.antlr.demo-packets\n"
             "  \"Generated CST forms for the browser demo; see test/resources/yang/php/demo_packets.generate.clj.\")\n\n\n"
             "(def forms\n  '"
             (pr-str forms)
             ")\n")))
