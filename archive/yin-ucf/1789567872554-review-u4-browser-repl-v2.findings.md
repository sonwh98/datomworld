# Review Findings: U4 — browser REPL client on the v2 wire

## Findings List

1. **[Accepted Gap] Unverifiable Manual Check:** The manual verification step (`clj -M:clj-yin-repl --port 8080 --headless` and browser interaction) could not be run as no browser or long-running server process is available in this environment. This is flagged explicitly as an unverified gap per the prompt instructions.

2. **[Info] Adapter Implementation:** `src/cljs/dao/stream/ws/browser.cljs` correctly implements a `:connect!`-only adapter. 
    * It synchronously returns `{:send! :close!}` without waiting for the socket to resolve.
    * The DOM event subscriptions are strictly limited to `message`, `close`, and `error` (no `open` event, deferring to the first `:ws/accept` frame).
    * It accurately implements binary vs string classification by checking `(string? data)` and supplying `binary-message-text` (a NUL byte, which the codec will safely reject) for all other frame types.
    * It properly fulfills `yin.repl.host.common/adapter?` but not `binder?`, adhering to the defined API contract.

3. **[Info] REPL Host Composition:** `src/cljs/datomworld/demo/yin_repl.cljs` mirrors the Node host's composition pattern precisely.
    * `driver/create-state` is wired effectively with the `browser-adapter` host map.
    * A single, non-overlapping `setInterval` (started on namespace load) is the sole owner and writer of the driver state, processing it via `driver/repl-step` and appending outputs via `driver/take-outbox` into the `history`.
    * `driver/submit-line!` is appropriately used for pushing new input strings into the driver's input medium without conflicting with the ticking cadence.

4. **[Info] Dependency and Composition Logic:** The decision to circumvent `yin.repl.host` and compose the adapter directly in the browser UI is sound. Requiring `yin.repl.host` in a `.cljs` build resolves to its CLJS shadow namespace, which unconditionally requires `dao.stream.ws.node`, which in turn requires the Node.js `ws` library (and thus `js/require`). Direct composition is the correct way to avoid pulling Node semantics into the browser bundle.

5. **[Info] Test Fidelity:** `test/dao/stream/ws/browser_test.cljs` provides a robust, non-superficial test. The `js/globalThis.WebSocket` shim allows the test to deterministically trace connection instantiation, ensuring that the attach process evaluates synchronously. It correctly intercepts and validates the transport boundary deposits (`:ws/opened`, `:ws/closed`, and `:ws/error`).

6. **[Info] Diff Minimalist Requirements:** The v1 `yin_repl.cljs` file remains strictly untouched. The `demo.cljs` modification is a single line, repointing `[datomworld.demo.yin-repl :as yin-repl-demo]` to `[datomworld.demo.yin-repl :as yin-repl-demo]`.

## Verdict

**Ready for Architect sign-off.**

The implemented components accurately follow the requirements defined in D3/U4. The composition logic is structurally robust, adheres tightly to the DaoStream v2 WebSocket architecture, and satisfies all specified validation checks minus the explicit, acknowledged gap of manual browser verification.
