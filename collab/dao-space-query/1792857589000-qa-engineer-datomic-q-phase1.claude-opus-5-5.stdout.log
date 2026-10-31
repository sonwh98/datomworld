Completed-GMT: 2026-10-24 16:05:08 GMT
Completed-Local: 2026-10-24 23:05:08 +07
Coding-Agent: claude
Session-ID: 6e84b906-f5ab-4551-bd47-73efc3d4a7c4

The Phase 1 adapter is in place and every check passes on the JVM. CLJS and CLJD have not been run.

## Files (the only two I touched)
- `src/cljc/dao/space/query/datomic.cljc` (new)
- `test/dao/space/query/datomic_test.cljc` (new)

`query.cljc`, the corpus and `deps.edn` are untouched.

## Verification
| Command | Outcome |
|---|---|
| `clj -M:test -n dao.space.query.datomic-test` | 29 tests, 226 assertions, 0 failures, 0 errors |
| `clj -M:test -n dao.space.datomic-query-conformance-test` | 17 tests, 41 assertions, 0 failures (unchanged) |
| `clj -M:test -n dao.space.query-test` | 72 tests, 282 assertions, 0 failures (unchanged) |
| `clj -M:kondo --lint` on both new files | 0 errors, 0 warnings |
| `mise exec -- bb check:node-discovery` | `Node discovery OK: 216 source files outside test/` |
| `git status --porcelain` (scoped to `src`, `test`, `deps.edn`, ble files filtered out) | only the 2 new files; I filtered out the ble files and did not touch them |

## What the adapter does
- **Call shapes:** `(q arg-map)` and `(q query & args)`. Both the `(q db arg-map)` and `(q db query)` forms throw `:unsupported`.
- **Queries:** a map, a vector or list, or an EDN string. Strings use the host's EDN reader with no tag handlers and are never passed to core `normalize-query`. Before any reader runs, a scanner refuses `#tag`, `#=` and `#?` forms, text over 100,000 characters, and nesting deeper than 64. A `#` inside a string or comment is allowed.
- **Validation before evaluation:**
  - **`:args`:** must be a sequential vector whose count matches `:in`.
  - **Source positions:** taken from `:in` only. Only `query/value?` values are accepted there; anything else gets `:invalid-request` pointing at the wrapping constructors. Vectors and maps at other positions stay data.
  - **`:offset` and `:limit`:** absent offset is 0 and absent limit is -1. Offset must be a nonnegative integer. Limit must be a nonnegative integer or -1.
  - **Refused options:** `:timeout`, `:ret`, `:io-context`, `:query-stats` and unknown options throw `:unsupported` with the option named.
- **Pattern padding:** 1- and 2-position patterns, including source-qualified ones, are padded with `_` to three fixed positions. This reaches `not`, `not-join`, `or`, `or-join`, `and` and rule bodies passed at `%`. Patterns of 4 or more positions and `&` rest patterns throw `:unsupported`.
- **Find validation:**
  - **Aggregates:** parameterized aggregates such as `(min 2 ?a)` and the later-phase ones (`distinct`, `median`, `variance`, `stddev`, `rand`, `sample`) throw `:unsupported`. A truly unknown aggregate throws `:invalid-request`.
  - **Pull:** the admitted profile is flat attributes, `*`, finite nesting, and the vector-form options `:default`, `:as` and `:limit`. These throw `:unsupported`: recursion markers, reverse attributes, list-form options, unknown options, dynamic patterns, source-qualified pull, and multi-entry nested maps.
- **Execution:** core `q`, then `collect`, then offset/limit on the realized rows. A relation comes back as a vector (core returns a set), and `:limit 0` still evaluates the query. Scalar and tuple finds with `:offset` or `:limit` throw `:unsupported`.
- **Errors:** every error carries `:dao.space.query.datomic/error`, the anomaly category, and minimal context (option, variable, or a clause fragment capped at 120 characters).
  - **Core refusals:** these become `:invalid-request`, or `:unsupported` when the message says "not implemented". They keep only the message and no `ex-data`, so bindings cannot leak.
  - **Source failures:** an exception whose data carries an anomaly category becomes `:source-failed` with that category and the original exception as `ex-cause`.
  - **Evaluator faults:** non-`ex-info` throwables become `:evaluation-failed` with their cause.
- **State:** no atoms, dynamic vars, globals or clocks.

## Concerns
- **Pattern padding applies to every source.** Because the adapter pads every data pattern, it cannot query a mixed-arity relation value that depends on exact arity, such as a 2-tuple relation. The contract targets fact sources, so I took that as intended.
- **Scalar/tuple/collection finds are unverified.** I let them through unchanged, and they are paginated only for relation, collection and return-map results.
- **List-form pull options are refused.** Datomic's real syntax is `(:attr :default 0)`, but the core honors only the vector form, so I refuse the list form rather than translate it. Translating would be a small addition if wanted.
- **The `:evaluation-failed` test is JVM-only.** It relies on a host `ClassCastException`, and it accepts either `:evaluation-failed` or `:invalid-request`. The `:source-failed` path is tested on all hosts via a throwing `delay` relation.
- **The EDN read path is untested on CLJS and CLJD.** On CLJD the reader is called without the options map, since I'm not sure that arity exists; the scanner is the only guard there. I did not run CLJS or CLJD tests, so portability beyond the Node discovery check is unproven.
- **Tests do not exercise the indexed path directly.** The padding tests assert behaviour (selective constant-entity queries over a plain fact relation and over a `current` view), not that the index is used.
