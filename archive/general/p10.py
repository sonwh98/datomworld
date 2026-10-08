import re


def drop(p, line):
    s = open(p).read()
    assert line in s, (p, line)
    s = s.replace(line, "", 1)
    open(p, 'w').write(s)


drop('test/yin/vm/ucf/handoff_v2_census_test.cljc', "            [yin.vm.engine :as engine]\n")
drop('test/yin/vm/ucf/handoff_v2_custody_test.cljc', "            [dao.stream :as stream]\n")
drop('test/yin/vm/ucf/handoff_v2_holder_test.cljc', "            [dao.jing :as jing]\n")
drop('test/yin/vm/ucf/handoff_v2_test.cljc', "            [dao.jing.cbor :as jing.cbor]\n")
drop('test/yin/vm/ucf/handoff_v2_test.cljc', "            [yin.vm.engine :as engine]\n")
drop('test/yin/vm/ucf/handoff_v2_walker_test.cljc', "            [dao.jing.cbor :as jing.cbor]\n")
