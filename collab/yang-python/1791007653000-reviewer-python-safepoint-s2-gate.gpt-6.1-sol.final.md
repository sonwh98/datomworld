Completed-GMT: 2026-10-03 06:09:56 GMT
Completed-Local: 2026-10-03 13:09:56 Asia/Ho_Chi_Minh

P1 | src/cljc/yang/python/antlr/prelude.cljc:302 | Generator crossings mutate the generator to `:running`, then install the rebased context at lines 307–309 or 320 without checking the recursion limit. The only check is function entry (`safepoint.cljc:83–90`); the generator body lambda is unmarked (`lower.cljc:1059`). Consequently, a generator immediately yielding a literal can start or resume above the limit. This violates the ruling’s explicit admission requirement. | Check the prospective effective depth before mutating either context or generator state, preserving naive/no-op behavior. Add a rejected deep resume followed by a successful shallow resume, proving the generator remains suspended and the caller unchanged.

P2 | test/yang/python/antlr/safepoint_test.cljc:558 | The generator regression covers ordinary yields and an internally raised exception after rebasing, but omits the ruled additive nested-generator chain and `throw`/`close` with finally execution under a changed resumer base. The ordinary-function unwind test at line 545 cannot verify these generator crossings. | Add portable regressions counting every active nested generator once, and exercising `throw`/`close` plus finally at the current base; assert caller depth after each crossing.

P3 | test/yang/python/antlr/safepoint_programs.cljc:42 | Added CST forms exceed 80 columns despite straightforward wrapping: line 42 is 96 columns, line 63 is 101. The added expected-output vector at `safepoint_test.cljc:568` also exceeds the limit. | Wrap CST children and expected vectors onto separate lines; retain long literal strings only where practical wrapping would change their contents.

The setter’s effective-depth check, 79/99 expectations for `down(19,g)`, exception hierarchy, and activation-relative restoration otherwise match the rulings. No additional host-portability defect identified statically.

Read-only review completed; no files edited or test suites run. Engineer-reported results remain unverified.