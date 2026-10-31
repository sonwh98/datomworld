Verdict: SIGNED OFF.

P2 is resolved. At `src/cljc/dao/stream/remote_channel.cljc:354`, `(contains? config :codecs)` preserves explicitly supplied selections, including `[]`. `ws/make-endpoint` rejects the empty vector before execution reaches `:bind!`; omission retains the Transit default. The validated endpoint selection also reaches bind.

The regression `an-explicit-empty-codecs-is-refused-and-omitted-defaults-to-transit` is present at `test/dao/stream/remote_channel_test.cljc:225` and passes on all three hosts. It checks empty-selection rejection and the omitted-selection default; the before-bind ordering is additionally verified from the implementation.

Fresh verification: `bb test:sub dao.stream dao.jing` passed with zero failures/errors: CLJ 689 tests / 13,664 assertions; CLJS 680 tests / 8,902 assertions; CLJD 657 tests. Each host has one additional test versus the previous review's combined counts. Dart freshly compiled the regression namespace; its log also confirms codec-fixture execution.

The C1 accessor grep gate remains satisfied; defaults remain intact. Jing validates profiles before branching and dispatches by `bytes?`, including unfamiliar-name coverage. Explicit codec selection works end to end. No code files were edited by this review.

Sign-off statement: Slice C1 is SIGNED OFF against §§3.2, 3.3 and §7.1 C1. The previous P2 acceptance hold is released. This is slice review sign-off; normal landing/merge gates remain required, including `bb test:changed`, which was not run here. Bidirectional old/new peer verification belongs to C2/C4 and is not claimed by this review.
