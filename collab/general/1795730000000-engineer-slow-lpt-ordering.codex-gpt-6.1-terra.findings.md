# Nightly slow LPT ordering findings

Code commit: `6eca6b48191599e972046ad5b0914fa758f06b5b` (based on `afad6081`; includes `10fccf2e` and `dc93a9ac`). The findings commit is its child; use `git rev-parse HEAD` for the branch head. No nightly was run.

The driver now orders jobs by descending estimated seconds, namespace then shard number as tie breakers, in the shared schedule used by both `lane-jvm` and `--list-slow`. Shard weight is namespace weight × group var count / namespace slow var count; existing var membership and order, exception-contract exclusion, commands, reconciliation and exit semantics are unchanged. Unknown namespaces receive 500 seconds. This is an ordering estimate, not a per-var measurement.

## Receipts and weights

The complete table is in `src/dev/nightly.clj`. All measured entries come from Jupiter `/home/sto/ci/nightly-results/20261123-0018-slow-runs.txt`, reproduced below, seconds per namespace. Red runs may have exited early. The two metadata-style namespaces `yang.call.exception-contract-test` and `yang.python.antlr.exception-call-test` were absent from that receipt; each gets the explicit 500s provisional default. `yin.repl.main-test` uses the directly observed 104s slow row rather than scaling the separate 116.94s quick measurement. PHP e2e is seeded at the requested **29,887s**, the audit's observed lower bound (see `collab/general/1795470000000-architect-test-runtime-audit.gemini-4-argon-high.findings.md` § What the clocks measured); the subsequently completed receipt below records 58,417s. The older weights and equal per-var shares do not account for uneven var durations or extra shard setup. Refresh from NDJSON when `10fccf2e`-era nightly data exists.

```text
dao.test-slow rc=0 secs=8
dao.star.driver-test rc=0 secs=15
yang.clojure.stream-eval-test rc=0 secs=33
dao.space.dht-test rc=0 secs=48
yang.python.antlr.dht-import-test rc=1 secs=134
yang.python.antlr.float-address-test rc=0 secs=40
yang.python.antlr.float-text-test rc=0 secs=8
yang.php.antlr.generators-test rc=0 secs=358
yang.python.antlr.int-contract-test rc=0 secs=36
yang.call.exception-source-test rc=0 secs=746
yang.python.antlr.e2e-c1-test rc=0 secs=768
yang.python.antlr.int-heap-test rc=0 secs=60
yang.python.antlr.int-literal-test rc=0 secs=27
yang.call.python-exception-test rc=1 secs=931
yang.python.antlr.c3-gate-test rc=0 secs=995
yang.python.antlr.c3-gate-parser-test rc=0 secs=1005
yang.python.antlr.prelude-parity-test rc=0 secs=136
yang.python.antlr.linked-prelude-test rc=1 secs=316
yang.python.antlr.int-conv-test rc=0 secs=908
yang.python.antlr.publication-test rc=0 secs=176
yang.python.antlr.import-test rc=0 secs=1187
yang.python.antlr.linked-safepoint-test rc=1 secs=412
yang.typescript.antlr.class-prelude-test rc=0 secs=112
yang.python.antlr.int-ops-test rc=0 secs=703
yang.python.antlr.int-ops-parser-test rc=0 secs=717
yang.python.antlr.siteset-test rc=0 secs=349
yang.typescript.antlr.linked-prelude-test rc=0 secs=298
yang.typescript.antlr.prelude-parity-test rc=0 secs=85
yang.typescript.antlr.prelude-test rc=0 secs=52
yang.typescript.antlr.safepoint-test rc=0 secs=270
yang.python.antlr.e2e-c2-test rc=0 secs=1995
yang.typescript.antlr.string-forms-test rc=0 secs=49
yin.repl.ast-query-e2e-test rc=0 secs=38
yang.typescript.antlr.lower-test rc=0 secs=575
yin.repl.dht-head-test rc=0 secs=56
yang.python.antlr.safepoint-test rc=0 secs=904
yin.repl.dht-test rc=0 secs=101
yin.repl.main-test rc=0 secs=104
yin.repl.query-test rc=0 secs=84
yin.vm.linker.cross-host-transfer-test rc=0 secs=69
yin.vm.linker.dht-end-to-end-test rc=0 secs=30
yin.vm.linker-test rc=0 secs=83
yang.call.php-exception-test rc=0 secs=2501
yang.typescript.antlr.source-call-test rc=0 secs=761
yin.vm.ucf.compose-rows-test rc=0 secs=13
yin.vm.ucf.holder.driver-test rc=0 secs=20
yin.vm.ucf.rows-test rc=0 secs=91
yin.vm.semantic.derive-test rc=0 secs=362
yin.vm.semantic.scan-test rc=0 secs=183
yin.vm.ucf.stage-e-rows-test rc=0 secs=51
yin.repl.dht-process-test rc=1 secs=738
yang.python.antlr.e2e-test rc=1 secs=3109
yin.vm.ucf.stage-e-process-test rc=0 secs=698
yin.repl.python-antlr-test rc=0 secs=1457
yang.typescript.antlr.object-e2e-test rc=0 secs=2271
yang.typescript.antlr.e2e-test rc=0 secs=2754
yin.vm.semantic.safepoint-harness-test rc=0 secs=5078
yang.php.antlr.include-test rc=0 secs=9130
yang.typescript.antlr.corpus-test rc=0 secs=8959
yang.php.antlr.e2e-test rc=0 secs=58417
```

## Verification

`NIGHTLY_SLOW_VAR_SHARDS=1 bb src/dev/nightly*.clj --list-slow /tmp/slow-lpt`: before and after each produced 76 commands; multiset equality holds and order differs. The before list below is the actual hash-map listing; execution previously sorted namespaces alphabetically. Shard labels below identify each namespace's original round-robin group, reconstructed from its ordered command vectors. All command vectors, including every `-v`, were compared. `bb gen:antlr` supplied missing generated classes before discovery. `clj -M:kondo --lint src/dev/nightly.clj`: 0 errors, 0 warnings; `git diff --check` clean.

Simple list scheduling on 12 identical workers, sorting the old queue alphabetically by namespace and retaining group order, gives **7471.75s alphabetical** versus **7471.75s LPT**. Both are bounded by a first-wave PHP shard at 7,471.75s under the equal-share assumption. This is not a prediction of measured speedup: actual shard costs are unequal and the old PHP wall is a lower bound; queue ordering changes start times even when this model's makespan is identical.

## Before: actual `--list-slow` hash-map order

1. yang.clojure.stream-eval-test (33.00s)
2. yang.php.antlr.generators-test (358.00s)
3. yin.vm.semantic.derive-test (362.00s)
4. yang.python.antlr.siteset-test (349.00s)
5. yin.repl.dht-test (101.00s)
6. yang.python.antlr.int-conv-test (908.00s)
7. yin.vm.ucf.stage-e-process-test (698.00s)
8. yang.python.antlr.linked-prelude-test (316.00s)
9. yang.typescript.antlr.prelude-parity-test (85.00s)
10. yang.typescript.antlr.e2e-test (2754.00s)
11. yang.python.antlr.publication-test (176.00s)
12. yin.vm.ucf.holder.driver-test (20.00s)
13. yang.python.antlr.float-address-test (40.00s)
14. yang.typescript.antlr.lower-test (575.00s)
15. yin.repl.dht-head-test (56.00s)
16. yang.typescript.antlr.corpus-test shard 1 (2986.33s)
17. yang.typescript.antlr.corpus-test shard 2 (2986.33s)
18. yang.typescript.antlr.corpus-test shard 3 (1493.17s)
19. yang.typescript.antlr.corpus-test shard 4 (1493.17s)
20. yang.python.antlr.float-text-test (8.00s)
21. yang.typescript.antlr.string-forms-test (49.00s)
22. yang.python.antlr.c3-gate-parser-test (1005.00s)
23. yang.python.antlr.exception-call-test (500.00s)
24. yang.python.antlr.import-test (1187.00s)
25. yin.repl.ast-query-e2e-test (38.00s)
26. yang.python.antlr.e2e-c2-test (1995.00s)
27. yang.call.exception-contract-test (500.00s)
28. dao.space.dht-test (48.00s)
29. yang.typescript.antlr.source-call-test (761.00s)
30. yang.php.antlr.e2e-test shard 1 (7471.75s)
31. yang.php.antlr.e2e-test shard 2 (7471.75s)
32. yang.php.antlr.e2e-test shard 3 (7471.75s)
33. yang.php.antlr.e2e-test shard 4 (7471.75s)
34. dao.star.driver-test (15.00s)
35. yang.python.antlr.e2e-test shard 1 (777.25s)
36. yang.python.antlr.e2e-test shard 2 (777.25s)
37. yang.python.antlr.e2e-test shard 3 (777.25s)
38. yang.python.antlr.e2e-test shard 4 (777.25s)
39. yin.vm.semantic.scan-test (183.00s)
40. yang.python.antlr.c3-gate-test (995.00s)
41. yang.python.antlr.dht-import-test (134.00s)
42. yang.typescript.antlr.object-e2e-test (2271.00s)
43. yin.vm.ucf.stage-e-rows-test (51.00s)
44. yang.call.php-exception-test (2501.00s)
45. yang.php.antlr.include-test shard 1 (2608.57s)
46. yang.php.antlr.include-test shard 2 (2608.57s)
47. yang.php.antlr.include-test shard 3 (2608.57s)
48. yang.php.antlr.include-test shard 4 (1304.29s)
49. yin.vm.linker-test (83.00s)
50. yang.python.antlr.int-ops-test (703.00s)
51. yin.repl.main-test (104.00s)
52. yin.vm.semantic.safepoint-harness-test shard 1 (2031.20s)
53. yin.vm.semantic.safepoint-harness-test shard 2 (1015.60s)
54. yin.vm.semantic.safepoint-harness-test shard 3 (1015.60s)
55. yin.vm.semantic.safepoint-harness-test shard 4 (1015.60s)
56. yang.python.antlr.safepoint-test (904.00s)
57. yang.typescript.antlr.prelude-test (52.00s)
58. yang.python.antlr.linked-safepoint-test (412.00s)
59. yang.python.antlr.int-contract-test (36.00s)
60. yang.python.antlr.prelude-parity-test (136.00s)
61. yin.repl.dht-process-test (738.00s)
62. yang.call.exception-source-test (746.00s)
63. yang.python.antlr.int-literal-test (27.00s)
64. yang.python.antlr.int-heap-test (60.00s)
65. yang.python.antlr.int-ops-parser-test (717.00s)
66. yin.vm.linker.cross-host-transfer-test (69.00s)
67. yang.call.python-exception-test (931.00s)
68. yin.vm.ucf.rows-test (91.00s)
69. yang.typescript.antlr.safepoint-test (270.00s)
70. yang.typescript.antlr.linked-prelude-test (298.00s)
71. yin.repl.python-antlr-test (1457.00s)
72. yang.typescript.antlr.class-prelude-test (112.00s)
73. yin.vm.ucf.compose-rows-test (13.00s)
74. yin.repl.query-test (84.00s)
75. yang.python.antlr.e2e-c1-test (768.00s)
76. yin.vm.linker.dht-end-to-end-test (30.00s)

## Before: lane execution alphabetical order

1. dao.space.dht-test (48.00s)
2. dao.star.driver-test (15.00s)
3. yang.call.exception-contract-test (500.00s)
4. yang.call.exception-source-test (746.00s)
5. yang.call.php-exception-test (2501.00s)
6. yang.call.python-exception-test (931.00s)
7. yang.clojure.stream-eval-test (33.00s)
8. yang.php.antlr.e2e-test shard 1 (7471.75s)
9. yang.php.antlr.e2e-test shard 2 (7471.75s)
10. yang.php.antlr.e2e-test shard 3 (7471.75s)
11. yang.php.antlr.e2e-test shard 4 (7471.75s)
12. yang.php.antlr.generators-test (358.00s)
13. yang.php.antlr.include-test shard 1 (2608.57s)
14. yang.php.antlr.include-test shard 2 (2608.57s)
15. yang.php.antlr.include-test shard 3 (2608.57s)
16. yang.php.antlr.include-test shard 4 (1304.29s)
17. yang.python.antlr.c3-gate-parser-test (1005.00s)
18. yang.python.antlr.c3-gate-test (995.00s)
19. yang.python.antlr.dht-import-test (134.00s)
20. yang.python.antlr.e2e-c1-test (768.00s)
21. yang.python.antlr.e2e-c2-test (1995.00s)
22. yang.python.antlr.e2e-test shard 1 (777.25s)
23. yang.python.antlr.e2e-test shard 2 (777.25s)
24. yang.python.antlr.e2e-test shard 3 (777.25s)
25. yang.python.antlr.e2e-test shard 4 (777.25s)
26. yang.python.antlr.exception-call-test (500.00s)
27. yang.python.antlr.float-address-test (40.00s)
28. yang.python.antlr.float-text-test (8.00s)
29. yang.python.antlr.import-test (1187.00s)
30. yang.python.antlr.int-contract-test (36.00s)
31. yang.python.antlr.int-conv-test (908.00s)
32. yang.python.antlr.int-heap-test (60.00s)
33. yang.python.antlr.int-literal-test (27.00s)
34. yang.python.antlr.int-ops-parser-test (717.00s)
35. yang.python.antlr.int-ops-test (703.00s)
36. yang.python.antlr.linked-prelude-test (316.00s)
37. yang.python.antlr.linked-safepoint-test (412.00s)
38. yang.python.antlr.prelude-parity-test (136.00s)
39. yang.python.antlr.publication-test (176.00s)
40. yang.python.antlr.safepoint-test (904.00s)
41. yang.python.antlr.siteset-test (349.00s)
42. yang.typescript.antlr.class-prelude-test (112.00s)
43. yang.typescript.antlr.corpus-test shard 1 (2986.33s)
44. yang.typescript.antlr.corpus-test shard 2 (2986.33s)
45. yang.typescript.antlr.corpus-test shard 3 (1493.17s)
46. yang.typescript.antlr.corpus-test shard 4 (1493.17s)
47. yang.typescript.antlr.e2e-test (2754.00s)
48. yang.typescript.antlr.linked-prelude-test (298.00s)
49. yang.typescript.antlr.lower-test (575.00s)
50. yang.typescript.antlr.object-e2e-test (2271.00s)
51. yang.typescript.antlr.prelude-parity-test (85.00s)
52. yang.typescript.antlr.prelude-test (52.00s)
53. yang.typescript.antlr.safepoint-test (270.00s)
54. yang.typescript.antlr.source-call-test (761.00s)
55. yang.typescript.antlr.string-forms-test (49.00s)
56. yin.repl.ast-query-e2e-test (38.00s)
57. yin.repl.dht-head-test (56.00s)
58. yin.repl.dht-process-test (738.00s)
59. yin.repl.dht-test (101.00s)
60. yin.repl.main-test (104.00s)
61. yin.repl.python-antlr-test (1457.00s)
62. yin.repl.query-test (84.00s)
63. yin.vm.linker-test (83.00s)
64. yin.vm.linker.cross-host-transfer-test (69.00s)
65. yin.vm.linker.dht-end-to-end-test (30.00s)
66. yin.vm.semantic.derive-test (362.00s)
67. yin.vm.semantic.safepoint-harness-test shard 1 (2031.20s)
68. yin.vm.semantic.safepoint-harness-test shard 2 (1015.60s)
69. yin.vm.semantic.safepoint-harness-test shard 3 (1015.60s)
70. yin.vm.semantic.safepoint-harness-test shard 4 (1015.60s)
71. yin.vm.semantic.scan-test (183.00s)
72. yin.vm.ucf.compose-rows-test (13.00s)
73. yin.vm.ucf.holder.driver-test (20.00s)
74. yin.vm.ucf.rows-test (91.00s)
75. yin.vm.ucf.stage-e-process-test (698.00s)
76. yin.vm.ucf.stage-e-rows-test (51.00s)

## After: LPT (`--list-slow` and lane execution)

1. yang.php.antlr.e2e-test shard 1 (7471.75s)
2. yang.php.antlr.e2e-test shard 2 (7471.75s)
3. yang.php.antlr.e2e-test shard 3 (7471.75s)
4. yang.php.antlr.e2e-test shard 4 (7471.75s)
5. yang.typescript.antlr.corpus-test shard 1 (2986.33s)
6. yang.typescript.antlr.corpus-test shard 2 (2986.33s)
7. yang.typescript.antlr.e2e-test (2754.00s)
8. yang.php.antlr.include-test shard 1 (2608.57s)
9. yang.php.antlr.include-test shard 2 (2608.57s)
10. yang.php.antlr.include-test shard 3 (2608.57s)
11. yang.call.php-exception-test (2501.00s)
12. yang.typescript.antlr.object-e2e-test (2271.00s)
13. yin.vm.semantic.safepoint-harness-test shard 1 (2031.20s)
14. yang.python.antlr.e2e-c2-test (1995.00s)
15. yang.typescript.antlr.corpus-test shard 3 (1493.17s)
16. yang.typescript.antlr.corpus-test shard 4 (1493.17s)
17. yin.repl.python-antlr-test (1457.00s)
18. yang.php.antlr.include-test shard 4 (1304.29s)
19. yang.python.antlr.import-test (1187.00s)
20. yin.vm.semantic.safepoint-harness-test shard 2 (1015.60s)
21. yin.vm.semantic.safepoint-harness-test shard 3 (1015.60s)
22. yin.vm.semantic.safepoint-harness-test shard 4 (1015.60s)
23. yang.python.antlr.c3-gate-parser-test (1005.00s)
24. yang.python.antlr.c3-gate-test (995.00s)
25. yang.call.python-exception-test (931.00s)
26. yang.python.antlr.int-conv-test (908.00s)
27. yang.python.antlr.safepoint-test (904.00s)
28. yang.python.antlr.e2e-test shard 1 (777.25s)
29. yang.python.antlr.e2e-test shard 2 (777.25s)
30. yang.python.antlr.e2e-test shard 3 (777.25s)
31. yang.python.antlr.e2e-test shard 4 (777.25s)
32. yang.python.antlr.e2e-c1-test (768.00s)
33. yang.typescript.antlr.source-call-test (761.00s)
34. yang.call.exception-source-test (746.00s)
35. yin.repl.dht-process-test (738.00s)
36. yang.python.antlr.int-ops-parser-test (717.00s)
37. yang.python.antlr.int-ops-test (703.00s)
38. yin.vm.ucf.stage-e-process-test (698.00s)
39. yang.typescript.antlr.lower-test (575.00s)
40. yang.call.exception-contract-test (500.00s)
41. yang.python.antlr.exception-call-test (500.00s)
42. yang.python.antlr.linked-safepoint-test (412.00s)
43. yin.vm.semantic.derive-test (362.00s)
44. yang.php.antlr.generators-test (358.00s)
45. yang.python.antlr.siteset-test (349.00s)
46. yang.python.antlr.linked-prelude-test (316.00s)
47. yang.typescript.antlr.linked-prelude-test (298.00s)
48. yang.typescript.antlr.safepoint-test (270.00s)
49. yin.vm.semantic.scan-test (183.00s)
50. yang.python.antlr.publication-test (176.00s)
51. yang.python.antlr.prelude-parity-test (136.00s)
52. yang.python.antlr.dht-import-test (134.00s)
53. yang.typescript.antlr.class-prelude-test (112.00s)
54. yin.repl.main-test (104.00s)
55. yin.repl.dht-test (101.00s)
56. yin.vm.ucf.rows-test (91.00s)
57. yang.typescript.antlr.prelude-parity-test (85.00s)
58. yin.repl.query-test (84.00s)
59. yin.vm.linker-test (83.00s)
60. yin.vm.linker.cross-host-transfer-test (69.00s)
61. yang.python.antlr.int-heap-test (60.00s)
62. yin.repl.dht-head-test (56.00s)
63. yang.typescript.antlr.prelude-test (52.00s)
64. yin.vm.ucf.stage-e-rows-test (51.00s)
65. yang.typescript.antlr.string-forms-test (49.00s)
66. dao.space.dht-test (48.00s)
67. yang.python.antlr.float-address-test (40.00s)
68. yin.repl.ast-query-e2e-test (38.00s)
69. yang.python.antlr.int-contract-test (36.00s)
70. yang.clojure.stream-eval-test (33.00s)
71. yin.vm.linker.dht-end-to-end-test (30.00s)
72. yang.python.antlr.int-literal-test (27.00s)
73. yin.vm.ucf.holder.driver-test (20.00s)
74. dao.star.driver-test (15.00s)
75. yin.vm.ucf.compose-rows-test (13.00s)
76. yang.python.antlr.float-text-test (8.00s)
