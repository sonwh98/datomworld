# P7-b oracle adoption

Recorded PHP 8.3.35 shiva folds; source strings retained. Engine final values are from the fixture run; include verification did not finish in this environment.

| Fixture | Engine before | Fold | Final |
|---|---|---|---|
| sequence | ATTBT | ATTBT | ATTBT |
| loop-body | ATAT | ATAT | ATAT |
| function-call | FT | FT | FT |
| generator-resume | ATBT | ATBT | ATBT |
| sequence-n2 | ATB | ATB | ATB |
| nested-threshold | ABCTD | ABCTD | ABCTD |
| cross-function | AFT | AFT | AFT |
| semicolon-directive | ATB | ATB | ATB |
| nested-zero-completion | ATBT | ATBT | ATBT |
| hoisted-threshold | TFTT | TFTT | TFTT |
| unregister-executing | ASB | AS | AS |
| per-entry-reentry | AHXYYBHXYY | AHXYYBHXYY | AHXYYBHXYY |
| do-while-selection | ATBTCT | ATBTTCT | ATBTTCT |
| for-selection | ATBTBTCT | ATBTBTTCT | ATBTBTTCT |
| foreach-selection | ATBTBTCT | ATBTBTTCT | ATBTBTTCT |
| switch-selection | ATBTCT | ATBTCT | ATBTCT |
| adjacent-marked | ATTBT | ATTBT | ATTBT |
| nested-zero-n2 | ABTC | ABTC | ABTC |
| dispatch-add | AHBHN | AHNBHNN | AHNBHNN |
| dispatch-remove | AHVBH | AHBH | AHBH |
| generator-foreach | ATBTTGTCT | ATBTTGTTCT | ATBTTGTTCT |
| include-handoff | not measured | LT | pending |

Self-unregistration fold additionally records uncaught Error, status 255. The runtime now traverses the live handler list and rejects unregistering an executing entry. Loop completion has a tick site for do-while, for, and foreach. The 21 non-include fixtures passed all 84 output, diagnostic, uncaught, and status assertions. The linked include gate was interrupted after several minutes without a result.
