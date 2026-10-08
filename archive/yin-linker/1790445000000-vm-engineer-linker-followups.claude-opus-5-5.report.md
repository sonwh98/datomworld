Created-GMT: 2026-09-26
Coding-Agent: claude (claude-opus-5-5)
Worktree: /Users/sto/workspace/datomworld-followups (branch followups,
from master 768e62c2). Uncommitted, as asked. No docs/ file touched.

# Report: yin.vm.linker small code follow-ups

## Verification

- Touched namespaces, JVM: attach-image, linker-manifest,
  linker-require, and repl.require tests. 76 tests, 804 assertions,
  0 failures.
- `dao.stream.ws.jvm-test` alone: 20/20 green (10 runs on the first
  version of the fix, then 10 on the final version, which ran while a full
  suite was running on the same machine).
- Full JVM suite (`clojure -M:test`): 2206 tests, 182800 assertions,
  0 failures, 0 errors. That run started before a last cosmetic tidy of
  the ws helper. A second full run on the final tree was still in
  progress when this report was written; the orchestrator's lanes cover
  it.
- cljstyle check: clean on all 7 touched files.
- kondo: no new findings. The two it reports were already there:
  `stack.cljc:52` unused excluded `eval` (info), and `jvm_test.clj:453`
  unresolved `org.httpkit.server` (warning; this line was unchanged, it
  only moved).
- I did not run the Node or Dart lanes; the orchestrator runs those.

## 1. declared-discharge nil-equality (Fable S4 P3)

- `src/cljc/yin/vm/linker.cljc:1014` (`:primitive` arm) and `:1021`
  (`:module` arm): added `(some? (:profile obligation))` /
  `(some? (:manifest obligation))` before the equality. An obligation
  with no address is now refused `:unresolved-free`, even when the
  receiver entry has no address either. Docstring (~1003) updated to
  say so.
- Test: `yin.vm.linker-manifest-test/
  a-declared-obligation-without-an-address-resolves-nothing` (line 565).
  It covers both arms: a nil profile against an unprofiled primitive,
  and a nil manifest against an unmanifested module.

## 2. Host-only require convention

- `test/yin/vm/linker_manifest_test.cljc:15`:
  `#?@(:clj [...])` changed to `#?@(:cljd [] :clj [...])`.
- I checked every .clj/.cljc/.cljs file in
  `git diff 9428c3d2 HEAD --name-only` for reader conditionals that have
  a `:clj` branch and no `:cljd` branch. This was the only real case.
  The others were not changed:
  - Three-way forms that have a `:cljd` branch (`catch` classes,
    `vm.cljc:380` bytes->str, `linker.cljc:1231` byte-count,
    `debruijn_register_effects_test.cljc:5` edn): Dart mode picks
    `:cljd`, so there is no trap.
  - `dao/await.cljc:148` and `vm.cljc:584`: host-only `defmacro`s,
    which is intentional. Both date from before M4 anyway.
  - `debruijn_register_benchmark_test.cljc:332/358`: JFR helpers from
    commit 7cfd1b39, which predates 9428c3d2, so out of scope. They are
    the same pattern, though; a candidate for a later sweep.
  - `store_write_audit_test.clj:376-382`: these are string fixtures,
    not code.

## 3. DeepSeek S1 P3s

(a) Empty-image attach is now a true no-op:
- `src/cljc/yin/vm/debruijn/stack.cljc:164` `attach-image`: `(or (empty?
  image) <already-a-row>)` returns `vm` unchanged. Docstring updated.
- `src/cljc/yin/vm/debruijn/register.cljc:191` `attach-image`: the
  identity is computed only for a non-empty image, and a nil identity
  returns `vm` unchanged. The line that normalized an empty image to
  `empty-image` is removed because nothing uses it now. `empty-image`
  itself is still used by `install-image`.
- The result: no row is added, and neither `:hash` nor `:segment`
  changes. Attaching an empty image to an empty VM was already a no-op.

(b) `absolute-pc` bounds:
- `stack.cljc:208` and `register.cljc:236`: `absolute-pc` now returns a
  pc only if `rel-pc` is a `nat-int?` and the pc it produces lies in the
  row being lowered. The check reuses the same row lookup `image-pc` uses
  (`row-at` in stack, `effects/image-row` in register), so `absolute-pc`
  is exactly the inverse of `image-pc`. Any other input returns nil,
  the same way an unknown identity already does. Its callers already
  fail closed on nil: `lower-closure` (stack.cljc:817, register.cljc:798)
  throws `:origin-not-attached`.
- Decision: one past the end of the LAST row is still accepted. This is
  needed: `image-pc` lifts that pc, and the continuation round-trip in
  `stack-continuation-lifted-after-attach-rebases-test` lowers
  `:return-pc`s through `absolute-pc`. One past the end of any other row
  is refused, because that pc belongs to the next image.
- Decision: an out-of-range entry reuses the `:origin-not-attached`
  refusal instead of getting a new reason keyword. My reading is that a
  `[segment entry]` pair outside the row names no attached code. If the
  owner wants a separate reason such as `:entry-out-of-range`, that is a
  small change, but it would touch the spec's refusal list.

Semantic and walker kernels:
- Semantic, gap (a): not present in the same form. There is no combined
  hash and no offset table. An empty vector attached under its own
  address is an addressable image that the caller reads back from the
  alias column. Left unchanged.
- Semantic, gap (b): present. `lower-closure` accepted any
  `:yin.k/entry`, so an out-of-range entry would fail late or run from
  the wrong place. Fixed at `src/cljc/yin/vm/semantic.cljc:976`: the
  entry must be a `nat-int?` below the attached image's instruction
  count, or the existing `:origin-not-attached` refusal is thrown.
- Walker: neither gap exists. `attach-image` of an empty row set adds
  nothing, and the walker has no pcs.

Tests, all in `test/yin/vm/attach_image_test.cljc`:
- `stack-empty-attach-and-out-of-range-pcs-test` (186)
- `register-empty-attach-and-out-of-range-pcs-test` (364)
- `semantic-closure-entry-outside-its-image-is-refused-test` (660); adds
  a require of `yin.vm.module`

The stack and register tests cover: the empty attach leaves the VM
equal; in-range and end-of-last-row pcs are accepted; rel-pc n+1 and -1
are refused; and one past a row that is not the last is refused.

## 4. Flaky ws JVM test

- Cause (a race in the test, not a production bug): `connect-raw`
  `.join`s the client handshake, which finishes when the client reads the
  101 response. The http-kit listener calls `accept!`
  (`ws/accept-connection!`, which marks the slot `:pending` and writes the
  offer) in `on-open` (`src/clj/dao/stream/ws/jvm.clj:310`). That runs on
  the server thread after the 101 is sent. Under load, the test's
  `accept-and-ack-slot!` read the endpoint state before `on-open` had
  run, so `index` was nil, and `(nth slots nil)` threw the NPE
  (`RT.intCast` on nil).
- Fix, test side only (`test/dao/stream/ws/jvm_test.clj:408`): the helper
  now waits, using the file's existing `eventually` with a 5s bound, until
  a slot is `:pending` AND its offer is present. It then takes the entry
  and the offer together. If 5s pass, it throws
  `"no slot offer within 5s"` with the slot statuses, so a real accept
  failure still shows up as a failure instead of being hidden.
- Production code is unchanged. `on-open` running after the 101 is
  normal WebSocket server behaviour. `on-message` already ignores
  messages until the adapter is set, and the test only sends after it
  receives the accept frame.

## 5. dao.pretty quote rendering (report only)

- Where it comes from: `yin.repl/format-value` (`src/cljc/yin/repl.cljc:
  238`) turns symbols into `(quote sym)` using `quote-symbols` and calls
  `dao.pretty/pp-str`. On JVM and cljs, `pp-str` is `clojure.pprint` /
  `cljs.pprint`. Their simple dispatch prints reader-macro lists
  (`quote`, `deref`, `var`, `unquote`) in reader form, so you see `'mod`.
  On ClojureDart, `pp-str` is the hand-rolled printer in the
  `#?(:cljd ...)` branch of `src/cljc/dao/pretty.cljc:26-128`. Its
  `list?`/`seq?` arms (~116-120) print every list as `(...)`, with no
  special case for reader macros, so you see `(quote mod)`.
- Is it a small fix? Yes. In the cljd `pp-str`, before the `list?` arm,
  add one branch: a 2-element seq whose first element is `'quote` is
  printed as `(str "'" (pp-str (second value) depth))`. To match pprint
  fully, `deref` (`@`), `var` (`#'`), and `clojure.core/unquote` (`~`)
  would be handled the same way. Only `quote` shows up in REPL output
  today, because `quote-symbols` produces it.
- What it touches: only the cljd branch of `dao/pretty.cljc`. The only
  consumer is `yin.repl/format-value` (REPL value, print, error, and
  debug rendering). No test pins the Dart rendering: `require_test`
  reads `:last-value` specifically to avoid it. Once the fix lands, the
  docstring note in `test/yin/repl/require_test.cljc:256` could be
  dropped, and a Dart-lane assertion on the rendered text could be added.

## Doc changes implied (not made; for the orchestrator)

- yin.vm.linker.md section 7.3, the `absolute-pc` contract: it returns
  nil for a rel-pc that `image-pc` would not produce (the exact inverse),
  and an empty image attach adds no row.
- Section 7.3 / 8.1 (step 5b): a declared obligation with no
  profile/manifest address discharges nothing.
- Semantic `lower-closure`: an entry outside the attached image is
  refused `:origin-not-attached`. If the owner prefers a separate reason,
  see the item 3 decision.
