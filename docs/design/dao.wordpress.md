# dao.wordpress: WordPress on datom.world

Status: Designed 2026-10-25 from the owner–seat design conversation of the
same day (section 15). Not yet through an adversarial review loop; nothing in
this document is built. The parent language spec (`yang.php.md`) carries the
same not-built caveat; `dao.space.query/sql` (section 5) does not exist today
and is specified here as new surface.

Parent designs: [`yang.php.md`](yang.php.md) (the PHP 8.3 frontend whose
slices P0 to P8 this builds on), [`yang.typescript.md`](yang.typescript.md)
section 2 (the shared rulings: D1 to D7, the linked-profile matrix, ADR 0004),
[`yang.antlr.md`](yang.antlr.md) section 8.6 (the PHP paradigm summary).
Doctrine: [ADR 0004](adr/0004-external-effects-are-interpreted-stream-deposits.md)
(external effects are interpreted stream deposits), Proposed. Store:
`dao.space` (`dao.space.query`, `dao.space.index`, `dao.space.transact`).
Effects substrate: `dao.stream`, `yin.vm.ffi`, `yin.vm.effect`.

## 1. Scope

"WordPress runs" means: a pinned WordPress 6.x core (owner decision 1),
unmodified, serving requests through the WordPress composition (section 3),
with every external effect (database, filesystem, outbound and loopback
HTTP, entropy, clock) an interpreted stream deposit under ADR 0004, and
with the database answered by `dao.space.query/sql` over the shared store
(section 5). One HTTP request is one task; two requests share nothing in
task state (the `yang.php.md` exit criterion 5 discipline holds here too).

The design claims a specified fragment, not WordPress fidelity and not
MySQL fidelity: the SQL fragment is frozen by the W0a corpus (section 11),
the builtin fragment by the W0b catalogue cross, and everything outside
each fragment is refused by name at compile or plan time, exactly as law 12
does for `yang.php.md` builtins. Every claimed behaviour is pinned by
fixtures against two oracles: the pinned PHP CLI (already the `yang.php.md`
oracle, owner decision B8.1) and MySQL in docker on shiva (the same
precedent as the php:8.3-cli fixtures).

Non-goals at the pin: production traffic and performance work (corner C9),
multisite and wp-cli (corner C10), the plugin ecosystem beyond the corpus
(corner C8), Apache rewrite-rule interpretation (corner C1), and a
dao.space-backed object-cache drop-in (corner C9 notes the later shape).

## 2. Prerequisites and the two legs

The work splits into two legs with different dependencies:

- **The SQL leg (W0a, W1, W2, W3)** has no PHP dependency. It needs only
  dao.space and the MySQL oracle, so it may start immediately (owner
  decision 4 recommends yes).
- **The PHP leg (W0b, W4 to W8)** needs `yang.php.md` P1 to P8 landed: the
  dynamic-scope profile (its section 3.3: WordPress's `extract`,
  `compact` and `$$name` are the profile's own examples), the request
  record (its 3.4), the prelude skeleton (its 9), and include over the
  linker (its 11). P9 (eval) is needed only if the corpus reveals core use
  of eval at the pin; core is not expected to (corner C8).

Shared prerequisites: the L1 to L4 lifts and D1 to D7 rulings of
`yang.typescript.md` section 2; ADR 0004 committed (the whole interpreter
side of section 3 is its doctrine); the landed dao.stream codec track (F0,
C0 to C4, H0 to H4) for wire and primitives; the D4 effect value
(`yin.vm.effect`) finished, since deposits are effect descriptors.

## 3. The WordPress composition (the interpreter)

The composition is the ADR 0004 interpreter: the program that answers the
task's deposits. Per request:

1. An HTTP request becomes an explicit request record; `php/init!` builds
   the superglobals from it (`yang.php.md` 3.4).
2. The composition routes every non-static path to the front controller:
   the task runs the pinned index.php with a minimal `$_SERVER` contract
   (REQUEST_METHOD, REQUEST_URI, QUERY_STRING, SCRIPT_NAME, HTTP_*,
   DOCUMENT_ROOT, pinned in fixtures). Static assets (themes, plugins,
   uploads) are served by the composition directly and never enter PHP.
3. WordPress's own rewrite API (pure PHP) does the internal routing; the
   rewrite rules WordPress writes for Apache are emitted as deposits the
   composition does not interpret (corner C1).
4. The task's `:php.response/header` and ordered `:php.response/body`
   records assemble the HTTP response.

The effect vocabulary the interpreter answers (deposit in, reply out; each
record's shape is frozen as a wire fixture, codec-track style):

+-------------+--------------------------------------------------------------+
| Vocabulary  | Reply                                                        |
+=============+==============================================================+
| wp-sql/*    | parsed-plan acknowledgement, row batches, affected counts,   |
|             | error records (number and text subset, corner C5)            |
+-------------+--------------------------------------------------------------+
| wp-fs/*     | read/write/list/stat over a directory namespace plus         |
|             | content-addressed blobs (section 10)                         |
+-------------+--------------------------------------------------------------+
| http-fetch  | status, headers, body: outbound HTTP; loopback requests to   |
|             | the composition's own authority schedule new tasks (section  |
|             | 10)                                                         |
+-------------+--------------------------------------------------------------+
| session     | the P5 session-store bound surface                           |
+-------------+--------------------------------------------------------------+
| entropy     | bytes; the clock and per-request entropy fields live on the  |
|             | request record, not here (section 8)                         |
+-------------+--------------------------------------------------------------+

Park and wake go through `yin.vm.ffi` unchanged: the continuation parks in
the polling wait set, the request lands on the call-in stream, the response
router wakes the task when its caller-scoped call id is answered. The
composition is an ordinary program over dao.stream, not new engine
machinery.

Determinism contract: a response is a pure function of (request record,
reply stream). Replaying both reproduces a byte-identical response, which
is exit criterion 4.

## 4. The stdlib substrate: host-provided builtins

Owner ruling (2026-10-25): PHP builtins are host-provided cljc functions
passed into yin.vm, in two tiers.

- **Pure builtins** (strings, arrays, math, `json`, `serialize`, the
  `preg_*` engine, SPL data structures) are substrate-side functions
  registered in the prelude table with their by-reference positions
  declared (`yang.php.md` corner C6 discipline), pinned by oracle fixtures,
  failing at compile time by name when absent (law 12).
- **Effectful builtins** (`mysqli_*`, `fopen`/`file_*`, HTTP wrappers,
  `random_bytes`) are cljc adapters that route onto the FFI bridge as
  deposits of section 3's vocabulary and park.

The prelude is cljc on the linked-profile matrix (bundled on four
evaluators, linked on three until L-b), so three-host golden agreement is
an acceptance line per wave (P1's precedent).

**PCRE is the hard portable**: `preg_*` must be PCRE-faithful on JVM, Node
and Dart, whose native engines differ. W9 builds a cljc engine for the
corpus's feature subset with honest refusal at its edge (law-12 style).
WordPress core's regex usage is enumerable from the corpus; plugin regex
beyond the subset is corner C8.

## 5. dao.space.query/sql: a parallel query language

Owner ruling (2026-10-25): `sql` is a parallel query language over the
tuple-space, not a lowering through Datalog. Today only `dao.space.query/q`
and `dao.space.query.datomic/q` exist; `sql` is the third surface.

Why peer, not lowered through `q`:

- **Bag, not set, semantics.** SQL preserves duplicates (SELECT without
  DISTINCT, join multiplication) and WordPress relies on MySQL's lax GROUP
  BY (non-aggregated columns chosen permissively); a set-semantics engine
  fights this on every query.
- **Three-valued logic.** SQL NULL is not Datalog's absence of a fact:
  `col = NULL` is UNKNOWN, NOT IN over a NULL-bearing subquery eats rows,
  COUNT(*) differs from COUNT(col), LEFT JOIN null-pads. A SQL-native
  executor carries 3VL from the parser up.
- **Correlated subqueries.** WordPress meta_query compiles to EXISTS and
  IN (SELECT ...) routinely; a SQL planner decorrelates, Datalog has no
  natural correlated shape.

What it shares with the Datalog track (so it is not a second engine): the
index scans (`dao.space.index` EAVT/AEVT/AVET/VAET; a `WHERE col = v`
predicate is the same AVET lookup either language issues), the transactor
for all DML, and the step-budget/spend telemetry machinery, which `sql`
threads like `q` does. What it owns: the MySQL-dialect parser, planner and
bag executor for the corpus fragment.

Every frozen-corpus query must return rows identical to the MySQL oracle
(W1's gate), with error numbers and texts for the subset wpdb inspects
(corner C5). Anything outside the fragment is refused at parse or plan
time with a named diagnostic: no `INTO OUTFILE`, no stacked queries, no
UDFs, no `information_schema` beyond the fragment. Not because they are
blocked, but because WordPress never issues them and law-12 refusal is the
default.

## 6. The table layer over row-entities

WordPress tables map to row-entities: each row is an entity, each column
an attribute (`:wp_posts/post_title` and so on), with a per-table registry
entity recording the DDL-parsed shape. The EAV substrate pays along the
way:

- **ALTER TABLE and dbDelta are nearly free**: a new column is a new
  attribute; absent attribute is NULL; dbDelta becomes a registry diff,
  and the wp_options autoload scan is one AVET lookup.
- **AUTO_INCREMENT** is a per-table counter entity allocated inside the
  same transaction as the insert; `LAST_INSERT_ID` reads the session's
  last allocation (W3).
- **SHOW COLUMNS / SHOW INDEX** read the registry (W2).

## 7. The database surface: two paths

- **Path A (recommended, owner decision 2): emulate the mysqli extension
  over wp-sql deposits.** wpdb runs unmodified; plugins that use mysqli or
  prepared statements directly keep working. Cost: the extension surface
  breadth (connection objects, result objects, fetch modes, error state).
- **Path B: a `wp-content/db.php` drop-in** replacing wpdb with a thin
  class calling a `dao_sql_query` builtin. Cost: reimplementing wpdb's
  public API (prepare, get_results, table helpers), and plugins that call
  mysqli directly break.

With `sql` in place Path A dominates: no WordPress code changes anywhere.

## 8. The no-ambient discipline

Nothing reads ambient process or HTTP state (`yang.php.md` 3.4), and the
WordPress surfaces make it concrete: `time()` reads the request record's
clock field; `random_bytes` and any mid-task entropy come from the entropy
deposit; `php_uname`, `gethostname` and friends read request-record fields
the composition sets. `$_SERVER` is built from the request record only.
The determinism contract of section 3 is the acceptance line: two replays
of (request record, reply stream) are byte-identical.

## 9. Sessions, transactions, prepared statements

- **Autocommit**: each statement-batch is one transaction over the
  dao.space transactor; two tasks' transactions serialize. InnoDB locking,
  isolation levels and deadlock fidelity are deferred (corner C4).
- **START TRANSACTION / COMMIT**: session-scoped deposits; the interpreter
  holds the batch until commit or rolls back on the task's death (a task
  that exits without commit abandons its batch; two requests share
  nothing by construction).
- **Prepared statements**: named plans cached by the interpreter (the SQL
  WordPress issues is constant per call site); bind parameters arrive as
  data. Unbuffered streaming results are refused at the pin (corner C12).

## 10. Filesystem, loopback and wp-cron

Most WordPress file reading is pure PHP over `fopen`/`fread` (translations,
config), so the fs interpreter is thin: a directory namespace plus
content-addressed blobs with attributes. The `dao.fs.md` CAS sketch is the
substrate candidate; its alignment (it predates dao.space, and lacks the
namespace/mtime attributes WordPress observes, notably `filemtime` for
cache busting) is owner decision 6. Writes (uploads, plugin updates) land
as content-addressed blobs with namespace entries.

wp-cron's non-blocking spawn is an `http-fetch` deposit to its own
authority that returns immediately; the composition schedules the cron
task; timing is not real-time (corner C7). A blocking loopback is an
awaited deposit that the composition answers by running the target as a
new task.

## 11. Slice sequencing and acceptance contracts

+-------+----------------------------------+-----------------------------------+
| Slice | Scope                            | Acceptance                        |
+=======+==================================+===================================+
| W0a   | SQL corpus freeze (no PHP        | every query, parameter set and    |
|       | dependency): instrument wpdb on  | result of the install, front and  |
|       | a pinned docker stack (php:8.3   | admin corpus recorded as frozen   |
|       | + MySQL), record the traffic     | fixtures (codec-track F0          |
|       |                                  | precedent)                        |
+-------+----------------------------------+-----------------------------------+
| W0b   | builtin catalogue cross (needs   | the parsed pinned core's builtin  |
|       | P0): parse pinned WP core,       | names intersected with the        |
|       | intersect with the prelude       | prelude table, frozen as the      |
|       | table                            | honest-failure worklist           |
+-------+----------------------------------+-----------------------------------+
| W1    | sql kernel: MySQL-dialect        | every frozen-corpus query returns |
|       | parser for the fragment, bag     | rows identical to the MySQL       |
|       | executor, 3VL, expression        | oracle; refusals are by name      |
|       | evaluator                        | with diagnostics                  |
+-------+----------------------------------+-----------------------------------+
| W2    | table layer + DDL: CREATE TABLE  | the dbDelta corpus upgrades a     |
|       | to registry, row-entities,       | schema version set with every     |
|       | AUTO_INCREMENT counters, SHOW    | diff explained; AUTO_INCREMENT /  |
|       | COLUMNS/INDEX                    | LAST_INSERT_ID fixtures           |
+-------+----------------------------------+-----------------------------------+
| W3    | sessions and DML: statement      | two tasks' transactions serialize |
|       | transactions, START/COMMIT,      | with a fixture; aborted-task      |
|       | prepared statements, cached      | rollback; prepared-plan reuse     |
|       | plans                            | fixture                           |
+-------+----------------------------------+-----------------------------------+
| W4    | wp-sql interpreter over          | park/wake round trip through the  |
|       | dao.stream: deposit/reply        | FFI pair; the gated-machine       |
|       | records, flow control            | replay fixture passes             |
+-------+----------------------------------+-----------------------------------+
| W5    | pure-stdlib waves: the W0b       | per-wave oracle fixtures;         |
|       | worklist prioritized (strings,   | three-host golden agreement       |
|       | arrays, json, ...)               |                                   |
+-------+----------------------------------+-----------------------------------+
| W6    | mysqli surface over deposits     | wpdb unmodified: install, one     |
|       | (Path A)                         | post round trip, one prepared     |
|       |                                  | statement                         |
+-------+----------------------------------+-----------------------------------+
| W7    | wp-fs, http-fetch, loopback,     | filemtime cache-bust fixture; a   |
|       | entropy/clock request-record     | cron spawn schedules a task;     |
|       | fields                           | replay determinism holds          |
+-------+----------------------------------+-----------------------------------+
| W8    | the composition: front           | exit criteria 1 to 5 (section     |
|       | controller, $_SERVER contract,   | 12) run green                     |
|       | static assets, response assembly |                                   |
+-------+----------------------------------+-----------------------------------+
| W9    | PCRE corpus engine + the         | the corpus regex set equal to     |
|       | performance baseline report      | PCRE; a published baseline        |
|       | (measure only, corner C9)        | report, no optimizer commitments  |
+-------+----------------------------------+-----------------------------------+

## 12. Exit criteria

1. The install screen renders through the composition: mixed HTML in
   statement order, schema writes visible in dao.space, no MySQL in the
   loop.
2. Every frozen-corpus SQL query returns rows identical to the MySQL
   oracle (W1's gate re-run as a regression).
3. Two requests share nothing in task state; every store change the
   second observes arrived through an interpreter deposit.
4. Replay: the same request record and reply stream reproduce a
   byte-identical response.
5. The default theme's front page and the login page render with options
   and posts read from dao.space.

## 13. Corners

Named, in the `yang.php.md` section 17 style; each is a documented
departure or refusal, confirmed against the oracle before the slice that
touches it lands (its 17.3 blocking rule).

- **C1** rewrite rules: emitted as deposits, never interpreted; the front
  controller routes everything to index.php.
- **C2** SQL fragment boundary: the corpus is the spec; refusal beyond it
  is by name.
- **C3** collation: the pinned utf8mb4 ci comparison subset; other
  collations refused.
- **C4** InnoDB locking, isolation levels, deadlocks: deferred; the
  serialized transactor stands in.
- **C5** SQL error fidelity: numbers and texts for the subset wpdb
  inspects (duplicate key, missing table); the rest are shaped records.
- **C6** filesystem attributes: deterministic mtime/size policy (request
  clock or content-derived), pinned by the filemtime fixture.
- **C7** wp-cron timing: composition-scheduled, no real-time guarantee.
- **C8** plugin ecosystem: core corpus only at the pin; extension waves
  later; honest failure at every unsupported builtin, regex feature and
  SQL form.
- **C9** performance: measurement only (W9); no optimizer commitments in
  this document. The dao.space-backed object-cache drop-in is a later
  optimization.
- **C10** multisite and wp-cli: out of scope at the pin.
- **C11** charset conversion (`iconv`, `mb_convert_encoding`): corpus
  subset.
- **C12** unbuffered/streaming result sets: refused; buffered batches
  only.

## 14. Owner decisions (open)

1. The WordPress 6.x pin and the MySQL oracle pin (mysql:8.x vs MariaDB
   docker image on shiva).
2. Path A vs Path B (section 7); recommendation: A.
3. First-milestone admin surface: install, front page and login only
   (recommended), or more.
4. May W0a to W3 start before the yang.php slices land; recommendation:
   yes, they have no PHP dependency.
5. Placement: `dao.space.query/sql` entry point beside `q` with the engine
   in `dao.space.query.sql` (recommended), or entry and engine together.
6. `dao.fs.md` alignment: adopt, extend, or supersede for the namespace
   and attribute layer.

## 15. Design provenance

Designed 2026-10-25 in a conversation between the owner and the glm zcode
seat, from a question ("once yang.php is complete, could WordPress run on
datom.world?"). Three owner rulings shape it and are quoted in sections 4
and 5: builtins are host-provided cljc functions passed into yin.vm;
`dao.space.query/sql` is a parallel query language over the tuple-space,
not a Datalog lowering; and mid-task effect round trips are not a missing
protocol but the existing dao.stream + interpreters doctrine (ADR 0004),
in which the seat's earlier claim that a duplex protocol was undesigned
was retracted after reading `yin.vm.ffi` and `yin.vm.effect`. The
conversation also fixed the first milestone: the install screen rendering
from mixed HTML with the database answered by `sql`. No adversarial
review loop has run; this document is a first draft, weaker than the
`yang.php.md` nine-round record, and should not be cited as reviewed.
