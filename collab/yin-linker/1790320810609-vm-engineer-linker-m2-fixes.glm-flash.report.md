# M2 gate-fix round report (GLM-5.3-Flash subagent, verbatim extract)

Files touched: exactly src/cljc/yin/vm/linker.cljc and
test/yin/vm/linker_test.cljc (content.cljc/content_test.cljc are the
pre-existing uncommitted M2 work, untouched).

P1-1 — section 4.1 scanner contract. All four format records now carry
the three position-bearing scanners (linker.cljc:605-675). Shapes as
documented: :obligations-fn -> {:name sym :at pos :in-body? b},
:definitions-fn -> {:name sym :at pos :conditional? b},
:applications-fn -> {:at pos}; position is [root-address path] for
:yin.ast/code and a pc for the three vector formats. AST scanners are
Datalog over the row and occurrence relations using vm/occurrence-rules
(queries :287-316, scanners :369-415, path order :341-370, branch/body
enclosure :327-338). Stack (:418), semantic (:205), register (:518)
obligation scans by pc; vector definitions are :store-put operands and
applications the call opcodes (:435, :462; register :531, :547), with
conditional ranges from :jump/:branch-false interiors and closure
/:bodies body ranges (:222-256, :499-515). Step 5 consumes the records:
precedes? (:761) and the undischarged join (:785) implement
defined-before-use under dominance, conditional definitions discharge
nothing, a main-sequence occurrence whose every definition is later is
:use-before-definition (added to refusal-reasons :41-44), a body
occurrence is discharged only by an unconditional definition dominating
every application site, the no-application-site rule, and the
nil-position degradation retains everything and discharges nothing
inside bodies. fetch wires the join (:1128-1141) and 5b checks retained
records (free-name-defect :724-743).

P1-2 — dart:typed_data alias added at linker.cljc:23
(#?@(:cljd [["dart:typed_data" :as typed]]), the file_test pattern);
byte-count (:893-899) resolves on Dart; the Dart lane compiles and is
green.

P1-3 — worklist bounds: default-bounds (:968) gives every fetch path,
including nil-opts, finite :max-parts 4096 / :max-depth 256 /
:max-bytes 16777216 via bounded (:976). :max-bytes is checked in
fetch-one (:964-966) before decode; the parts budget is enforced at
enqueue time by enqueue-children (:985-1002) before fetch (verified
with a counting store: zero reads of both children once the budget is
spent).

New tests: ast-scanners-yield-position-bearing-records (:402),
ast-definitions-mark-branch-and-body-bindings-conditional (:417),
vector-scanners-yield-position-bearing-records (:429),
register-scanners-yield-position-bearing-records (:444),
a-definition-discharges-a-use-after-it (:985),
a-use-before-every-definition-is-use-before-definition (:999),
a-conditional-definition-discharges-nothing (:1014),
a-definition-in-a-lambda-body-discharges-nothing (:1028),
a-definition-dominating-every-application-discharges-a-body-occurrence
(:1055), an-application-before-the-definition-retains-the-obligation
(:1069), a-body-occurrence-with-no-application-site-is-discharged
(:1084), an-unreadable-vector-layout-degrades-conservatively (:1094),
obligations-are-checked-by-name-and-record (:1113),
max-bytes-is-checked-before-decode (:920),
the-parts-budget-is-enforced-at-enqueue-time (:941),
a-fetch-without-explicit-bounds-is-finite (:965).

Lane counts (sequential, solo): JVM 2,052 tests / 180,898 assertions /
0 failures (baseline 2,036/180,792/0 plus 16 new tests); Node 1,968
tests / 47,925 assertions / 0 failures; Dart 1,930 passed, "All tests
passed!", exit 0. cljstyle clean; kondo 0 errors/0 warnings; pure
ASCII; every line <= 80 columns; no diagnostics; no commit/stage/
checkout/reset/stash.

Status: COMPLETE
