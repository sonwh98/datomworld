Coding-Agent: agy
Session-ID: 3aae8838-e357-4885-b723-70b3d7483d80 (agy conversation_id; two turns — plan pause, then resumed with explicit no-edit approval)

Completed-GMT: 2026-11-03 01:23:59 GMT
Completed-Local: 2026-11-03 08:23:59 +07

# BLE Track Review r6 — Confirmation of r5 Fixes

Target Worktree: `/Users/sto/workspace/datomworld-ble2` (`ble-track2`)  
Review Role: Adversarial Code Reviewer and Security Auditor  
Review Mode: Read-Only Audit (no repository modifications)

---

### Disposition 1: P1 Startup Sequencing (`dart.cljd start-radio!`)
* **Requirement**: The async `start-peripheral!` is awaited before its value is stored as the peripheral and before `[:radio-up identity]` fires. Its internal catch rethrows rather than swallows refusals, ensuring `start-radio!`'s catch deposits `bind-failed` exactly once, and no `bound` is ever deposited when either role fails (design §3 "no writer").
* **Evidence**:
  - In [`src/cljd/dao/stream/ble/dart.cljd:229-233`](file:///Users/sto/workspace/datomworld-ble2/src/cljd/dao/stream/ble/dart.cljd#L229-L233), `start-peripheral!` catches `Object e` and rethrows with `(throw e)`.
  - In [`src/cljd/dao/stream/ble/dart.cljd:314-328`](file:///Users/sto/workspace/datomworld-ble2/src/cljd/dao/stream/ble/dart.cljd#L314-L328), `start-radio!` awaits `fbp/FlutterBluePlus.startScan` and then awaits `(start-peripheral! ...)`. Only upon resolution of `p` is `peripheral-peer` associated in `!links` and `[:radio-up identity]` fired. Any refusal inside `startScan` or `start-peripheral!` routes directly to `(catch Object e (radio! [:bind-failed (str e)]))`.
  - In compiled Dart [`lib/cljd-out/dao/stream/ble/dart.dart:920-922`](file:///Users/sto/workspace/datomworld-ble2/lib/cljd-out/dao/stream/ble/dart.dart#L920-L922), `start_peripheral$BANG_` compiles to `} on dc.Object catch (e$2) { rethrow; }`.
  - In compiled Dart [`lib/cljd-out/dao/stream/ble/dart.dart:939-966`](file:///Users/sto/workspace/datomworld-ble2/lib/cljd-out/dao/stream/ble/dart.dart#L939-L966), `start_radio$BANG_` executes `((await $1)); final dc.dynamic p$1 = (await lcodsb_dart.start_peripheral$BANG_(...));`, assigns `p$1` to `peripheral_peer`, and invokes `radio-up`. On error, it enters `on dc.Object catch (e$1)` and deposits `bind-failed`.
* **Status**: **CONFIRMED**

---

### Disposition 2: P1 TX-Only Peers (`dart.cljd perform-send-frame!`)
* **Requirement**: A link with no RX characteristic feeds `[:send-fail peer "no rx characteristic"]` instead of silently answering ok.
* **Evidence**:
  - In [`src/cljd/dao/stream/ble/dart.cljd:265-274`](file:///Users/sto/workspace/datomworld-ble2/src/cljd/dao/stream/ble/dart.cljd#L265-L274):
    ```clojure
    (when-some [{:keys [rx]} (get @!links peer)]
      (if rx
        (-> (.write ^fbp/BluetoothCharacteristic rx (vec bs) .withoutResponse false)
            (.catchError
              (fn [e] (radio! [:send-fail peer (str "write: " e)]))))
        (radio! [:send-fail peer "no rx characteristic"])))
    ```
  - In compiled Dart [`lib/cljd-out/dao/stream/ble/dart.dart:628-662`](file:///Users/sto/workspace/datomworld-ble2/lib/cljd-out/dao/stream/ble/dart.dart#L628-L662), the `else` branch of `(null != rx$1)` allocates vector `[:send-fail peer "no rx characteristic"]` and passes it directly to `radio!`.
* **Status**: **CONFIRMED**

---

### Disposition 3: P2 Subscriptions at Bind (`dart.cljd bind!`)
* **Requirement**: `adapter-events!` and `scan-events!` return their subscriptions and are registered in `!subs`; `stop-radio` cancels them. Cancellation futures get `.catchError` while synchronous throws stay under `try`.
* **Evidence**:
  - In [`src/cljd/dao/stream/ble/dart.cljd:80-90`](file:///Users/sto/workspace/datomworld-ble2/src/cljd/dao/stream/ble/dart.cljd#L80-L90) and [`98-111`](file:///Users/sto/workspace/datomworld-ble2/src/cljd/dao/stream/ble/dart.cljd#L98-L111), `scan-events!` and `adapter-events!` evaluate to the `listen!` subscription result.
  - In [`src/cljd/dao/stream/ble/dart.cljd:375-376`](file:///Users/sto/workspace/datomworld-ble2/src/cljd/dao/stream/ble/dart.cljd#L375-L376), `bind!` swaps both into `!subs`:
    ```clojure
    (swap! !subs conj (adapter-events! radio!))
    (swap! !subs conj (scan-events! radio!))
    ```
  - In [`src/cljd/dao/stream/ble/dart.cljd:294-300`](file:///Users/sto/workspace/datomworld-ble2/src/cljd/dao/stream/ble/dart.cljd#L294-L300), `perform-stop-radio!` cancels every subscription:
    ```clojure
    (doseq [s @!subs]
      (try (-> (.cancel ^async/StreamSubscription s)
               (.catchError (fn [_] nil)))
           (catch Object _ nil)))
    ```
  - In compiled Dart [`lib/cljd-out/dao/stream/ble/dart.dart:239-240`](file:///Users/sto/workspace/datomworld-ble2/lib/cljd-out/dao/stream/ble/dart.dart#L239-L240) and [`765-779`](file:///Users/sto/workspace/datomworld-ble2/lib/cljd-out/dao/stream/ble/dart.dart#L765-L779), `bind!` pushes both listener streams into `$BANG_subs$1`, and `perform_stop_radio$BANG_` loops through each subscription with `.cancel().catchError(...)` nested under `try ... on dc.Object catch`.
* **Status**: **CONFIRMED**

---

### Disposition 4: Documentation Contradictions
* **Requirement**: `links.cljc close!` docstring states "one closed event followed by a link-down for every live link" (matching code deposit order); `mesh.cljc step-event!`'s `closed` comment explains the closed/gap distinction without claiming "the same rule a gap gets below".
* **Evidence**:
  - In [`src/cljc/dao/stream/ble/links.cljc:277-283`](file:///Users/sto/workspace/datomworld-ble2/src/cljc/dao/stream/ble/links.cljc#L277-L283), the docstring reads:
    > "The close path: the node stops advertising, scanning and serving, and is observably gone. Deposits are one closed event followed by a link-down for every live link -- the mesh above learns its neighbors from those events, and a link fact the reader held must not outlive the reader's evidence for it (design 5). Idempotent: a closed node's close answers without deposits or actions."
    This accurately mirrors the code's `(into [(ble/closed-event ...)] (map (fn [p] (ble/link-down-event ...)) (keys (:links state))))`.
  - In [`src/cljc/dao/stream/ble/mesh.cljc:301-306`](file:///Users/sto/workspace/datomworld-ble2/src/cljc/dao/stream/ble/mesh.cljc#L301-L306), the comment for `:dao.stream.ble/closed` states:
    > "The raw layer says the node itself is gone: no link fact this mesh held survives it, and unlike a gap there is nothing stale-but-usable here -- the neighbors are gone."
    The prior errant reference equating closed handling to gap handling is completely removed.
* **Status**: **CONFIRMED**

---

### Disposition 5: Assertion-Count Delta Reconciled
* **Requirement**: 51 tests / 203 assertions on JVM (vs lost tree's 50/198) verified as reconstruction variance.
* **Evidence**:
  - Independent execution of JVM test suite across `dao.stream.ble-test`, `dao.stream.ble.links-test`, and `dao.stream.ble.mesh-test`:
    `Ran 51 tests containing 203 assertions. 0 failures, 0 errors.`
  - Isolating [`test/dao/stream/ble/links_test.cljc:136`](file:///Users/sto/workspace/datomworld-ble2/test/dao/stream/ble/links_test.cljc#L136) (`an-inbound-link-always-deposits-whatever-the-ceiling`) accounts for `{:test 1, :pass 3, :fail 0, :error 0}`.
  - Dart lane spans 52 tests across 4 namespaces (51 shared `.cljc` tests + 1 test in [`test/dao/stream/ble/dart_test.cljd:38`](file:///Users/sto/workspace/datomworld-ble2/test/dao/stream/ble/dart_test.cljd#L38)).
  - Kondo linting across `src/cljc/dao/stream/ble*`, `src/cljd/dao/stream/ble*`, and `test/dao/stream/ble*` confirms clean status aside from the 2 documented false positives on `Uint8List.fromList` ([`dart.cljd:59`](file:///Users/sto/workspace/datomworld-ble2/src/cljd/dao/stream/ble/dart.cljd#L59) and [`dart_test.cljd:52`](file:///Users/sto/workspace/datomworld-ble2/test/dao/stream/ble/dart_test.cljd#L52)).
* **Status**: **CONFIRMED**

---

### Side-Effect / Regression Audit
- No regressions introduced: all 51 JVM tests pass cleanly with 0 failures; compiled Dart output is intact and fully generated.
- Exception handling adheres strictly to design §3 ("no writer") and design §4 ("send-failed channel").
- Teardown idempotency and resource reclamation are verified across central and peripheral streams.

CONFIRMED

