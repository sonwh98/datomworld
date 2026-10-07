p = 'src/cljc/yin/vm/ucf/holder/export.cljc'
s = open(p).read()
s = s.replace("            [yin.vm.completion :as completion]\n", "", 1)
s = s.replace("            [yin.vm.module :as module]\n", "", 1)
open(p, 'w').write(s)

p = 'src/cljc/yin/vm/ucf/holder/driver.cljc'
s = open(p).read()
n = s.count("(export/enter (:machine cell))") + s.count("(export/enter (:machine state))")
s = s.replace("(export/enter (:machine cell))",
              "(export/enter (:machine cell) {:version (:export-version state)})")
s = s.replace("(export/enter (:machine state))",
              "(export/enter (:machine state) {:version (:export-version state)})")
s = s.replace('''   :seams (-> (select-keys config (into seams optional-seams))''',
              '''   :export-version (:export-version config)
   :seams (-> (select-keys config (into seams optional-seams))''', 1)
print("enter calls", n)
open(p, 'w').write(s)
