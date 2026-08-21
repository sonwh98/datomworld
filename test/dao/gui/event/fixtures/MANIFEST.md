# dao.gui.event conformance obligation manifest

Maps every in-scope Conformance Scenarios bullet, diagnostic kind, and
invalid-machine validation rule to its committed fixture or boundary test.
One minimal fixture may cover several explicitly listed obligations; no
obligation is implicit. Deferred obligations belong to the compiler and
terminal boundaries excluded by Implementation Scope in
docs/design/dao.gui.event.md and are required only when those boundaries
are implemented.

Fixtures live in this directory and replay through the total reducer on
CLJ, CLJS, and CLJD via the embedded `dao.gui.event.fixtures-corpus`
namespace. Boundary tests live in `test/dao/gui/event/`.

## Conformance Scenarios

| Obligation | Fixture or boundary test |
| --- | --- |
| one-, two-, three-contact taps | tap_single, tap_two-contact, tap_three-contact |
| single, double, repeated taps | tap_single, tap_double, tap_repeated |
| single tap delayed by competing double tap | tap_deferred-revival |
| long press acceptance, early up, movement rejection, cancellation | longpress_accept, longpress_early-up, longpress_move-reject, longpress_cancel |
| child tap competing with ancestor vertical scroll pan | arena_child-tap-ancestor-pan |
| axis-constrained and free pan, pointer leaving hit rectangle | pan_axis-y, pan_free-leaving-rect |
| pan end with and without fling | pan_end-with-fling, pan_end-without-fling |
| swipe direction, distance, duration, velocity boundaries | swipe_boundaries |
| one-finger transform growing into two-finger scale and rotation | transform_grow |
| separate cooperative scale and rotation recognizers | transform_cooperative-scale-rotation |
| one-arena contact join and contacts/changed ordering | join_contacts-changed-order |
| a new down bridging several unresolved arenas into the oldest | join_bridge-merge |
| accepted-arena late join, join-after-accept on and off | join_after-accept-on, join_after-accept-off |
| path-disjoint controls operating concurrently | arena_path-disjoint-concurrent |
| contact lift under :end, :degrade, :hold | pan_lift-end, transform_lift-degrade, pan_lift-hold |
| contact-count overflow and owned-candidate omission on merge | arena_contact-overflow, arena_owned-candidate-omitted |
| captured movement across another region without retargeting | capture_across-region |
| edge pan delivered, cancelled, never qualifying | edge-pan_delivered, edge-pan_cancelled, edge-pan_never-qualifies |
| pressure recognition with and without capability | pressure_with, pressure_without |
| custom machine validation, transition order, timers, fault isolation | machine_invalid-* corpus, machine_transition-order, machine_timers, machine_fault-isolation |
| capture across frame presentation and target disappearance | capture_across-frames |
| rejected and skipped frames while a gesture is active | terminal_frames-rejected-skipped |
| terminal reset | terminal_reset |
| coordinate-space mismatch | diagnostic_coordinate-space-mismatch |
| profile mismatch | diagnostic_profile-mismatch |
| duplicate pointer ids | diagnostic_duplicate-pointer-down |
| orphan packets | diagnostic_orphan-pointer-packet |
| input-sequence gaps | diagnostic_input-sequence-gap |
| local scrolling and animation without coordinate-space remint | geometry_no-remint |
| pointer-before-timer deadline ties, stale timer sequences | timer_deadline-tie, timer_stale-sequence |
| late runtime input | diagnostic_late-runtime-input |
| predicted-sample miscorrection without semantic state change | predicted_miscorrection |
| one retention gap cancelling several affected arenas | input-loss_multi-arena |
| stationary long press without heartbeat packets | longpress_stationary |
| web touch policies on overlapping regions | boundary: policy_test.cljc (pure policy functions) |
| atomic overlay publication, stable-root capture, accessibility exclusion, precedence mapping, policy-mismatch fallback | deferred: terminal boundary under Implementation Scope |
| bounded input and dispatch streams without silent lifecycle loss | boundary: bind_test.cljc (real DaoStreams) |
| cross-runtime trace replay with exact structural equality | the corpus suite itself: fixture_test embedded-corpus-replays-on-every-runtime |

## Diagnostic kinds

| Diagnostic | Fixture or boundary test |
| --- | --- |
| :dao.gui.event/unsupported-region | diagnostic_unsupported-region |
| :dao.gui.event/invalid-recognizer | machine_invalid-* corpus; terminal_test cooperative/duplicate tests |
| :dao.gui.event/recognizer-fault | machine_fault-isolation |
| :dao.gui.event/unsupported-capability | pressure_without |
| :dao.gui.event/unrecognized-event-kind | diagnostic_unrecognized-event-kind; core_test source/kind tests |
| :dao.gui.event/no-active-frame | diagnostic_no-active-frame |
| :dao.gui.event/future-frame-input | diagnostic_future-frame |
| :dao.gui.event/stale-frame-input | diagnostic_stale-frame |
| :dao.gui.event/stale-generation-input | diagnostic_stale-generation |
| :dao.gui.event/coordinate-space-mismatch | diagnostic_coordinate-space-mismatch |
| :dao.gui.event/profile-mismatch | diagnostic_profile-mismatch; terminal_test monotonic-id tests |
| :dao.gui.event/input-sequence-gap | diagnostic_input-sequence-gap |
| :dao.gui.event/duplicate-pointer-down | diagnostic_duplicate-pointer-down |
| :dao.gui.event/orphan-pointer-packet | diagnostic_orphan-pointer-packet |
| :dao.gui.event/capture-lost | deferred: terminal boundary under Implementation Scope |
| :dao.gui.event/late-timer | timer_stale-sequence; timer_deadline-tie |
| :dao.gui.event/late-runtime-input | diagnostic_late-runtime-input |
| :dao.gui.event/browser-policy-conflict | deferred: terminal boundary under Implementation Scope |
| :dao.gui.event/browser-policy-mismatch | deferred: terminal boundary under Implementation Scope |
| :dao.gui.event/dispatch-backpressure | boundary: bind_test.cljc (stream transport, not reducer replay) |

## Invalid-machine validation rules

| Rule | Fixture |
| --- | --- |
| machine/version must be 1 | machine_invalid-invalid-version |
| initial state must be declared | machine_invalid-unknown-initial-state |
| windows must declare positive capacity | machine_invalid-invalid-window |
| state ids must be keywords | machine_invalid-non-keyword-state |
| transitions must be sequential | machine_invalid-invalid-transitions |
| non-terminal states need a cancel transition | machine_invalid-missing-cancel-transition |
| transition order must be unique | machine_invalid-duplicate-transition |
| selectors must be legal | machine_invalid-unknown-selector |
| operators must be legal | machine_invalid-unknown-operator |
| actions must be legal | machine_invalid-unknown-action |
| operator and action arities must match | machine_invalid-invalid-arity |
| :setting lookups must name declared settings | machine_invalid-undeclared-setting |
| literals must be finite EDN | machine_invalid-non-finite-literal |
| :goto must target declared states | machine_invalid-unknown-state |
| window pushes must name declared windows | machine_invalid-undeclared-window |
| emission phases must be legal | machine_invalid-illegal-emission-phase |
| accept and reject at most once per transition | machine_invalid-duplicate-decision |
| :emit must follow :arena/accept in a transition | machine_invalid-emit-before-accept |
| transitions must be maps | machine_invalid-malformed-transition |

## Generator

`test/dao/gui/event/fixtures_gen.cljc` regenerates the committed .edn
files and the embedded corpus namespace. The committed artifacts, not the
generator's current output, are the conformance surface.
