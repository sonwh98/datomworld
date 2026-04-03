# ===========================
# datom.world – Development Rules
# ===========================

# CORE PHILOSOPHY

Everything is data.
Streams are data. Code is data. Runtime state is data.
Everything is a stream.
State is represented as immutable datoms.
Interpretation > abstraction.
Explicit causality > implicit assumptions.
Restrictions are a feature.

# NON-NEGOTIABLE INVARIANTS

Do not introduce hidden global state.
Do not introduce implicit control flow.
Do not introduce callbacks without explicit stream representation.
Do not introduce shared mutable state.
Do not collapse interpretation and execution into the same layer.
Do not assume graphs: graphs must be constructed explicitly from tuples.

# DATOMS

Datoms are 5-tuples: (e a v t m). Immutable facts, not objects.
Canonical format for persistent facts: DaoDB, AST, schema, provenance.
Streams carry whatever values the consumer needs.

Full specification: @.claude/rules/datom-spec.md

# STREAMS

All IO is modeled as a stream. Functions consume streams and produce streams.
No direct function-to-function coupling without a stream boundary.
Side effects must appear as stream emissions.
Streams are values that can be sent through streams.

Functions can take anything and return anything.
Agents are functions, closures, or continuations in Yin.VM that communicate with the outside
world through DaoStream. Agent behavior is specified entirely through stream effects.

Architecture overview and advanced topics: @.claude/rules/architecture.md

# BUILD & TEST

Run tests:           clj -M:test
Lint a file:         clj -M:kondo --lint <path>
ClojureScript dev:   npx shadow-cljs watch app
ClojureScript build: npx shadow-cljs compile app

# DESIGN PRINCIPLES

Code quality is measured by malleability: how cheaply can changes adapt without breaking promises?

High Cohesion, Loose Coupling:
- Things that change together should live together.
- Things that change independently should be isolated.
- Measure coupling via dependency graphs: layered (good) vs. spaghetti (bad).

Bounded Complexity:
- Each module should be learnable without understanding the entire system.
- Changes stay local when complexity is strictly bounded.
- Visual inspection of dependency graphs reveals coupling health.

Evolution, Not Design:
- Start simple in one namespace; let structure emerge as code grows.
- Extract cohesive clusters into separate modules when they stabilize.
- Use REPL comment blocks to explore behavior before committing tests.

Testing Strategy:
- Test public contracts, not internal implementation.
- Preserve exploration knowledge in (comment ...) blocks; delete when it lies.
- Tests are selection pressure: code that breaks contracts doesn't survive.

Full design philosophy: @.claude/rules/malleability.md

# CODE STYLE

Prefer simple data over rich types.
Prefer total functions over partial ones.
Prefer declarative pipelines over imperative logic.
Avoid cleverness.
Name things after what they do to streams.

# WORKFLOW

Do not commit changes until explicitly asked by the user.
When asked to commit, only commit staged changes.
Do not invent abstractions that hide streams.
Do not suggest mainstream frameworks unless explicitly asked.
Do not optimize prematurely.
When uncertain, ask at the architectural level, not the implementation level.
Explanations should align with Plan 9 / Datomic / Lisp lineage.

If a problem appears complex:
  Decompose it into streams.
  Identify invariants.
  Restrict degrees of freedom.
  Make causality explicit.
  Let structure emerge from constraints.

# FILE FORMATS

.chp and .blog files are EDN files with Hiccup markup.

Structure for .blog files:
#:blog{:title "Title Here"
       :date #inst "YYYY-MM-DDTHH:MM:SS.000-00:00"
       :abstract [:p "Abstract with hiccup markup..."]
       :content [:section.blog-article
                 [:div.section-inner
                  [:article
                   :$blog-title
                   :$blog-date
                   ;; Hiccup content here
                   ]]]}

When creating .blog:
- Use EDN syntax with namespaced maps (#:blog{...})
- Use Hiccup vectors for all markup ([:p "text"], [:strong "bold"], [:a {:href "..."} "link"])
- Never use markdown syntax
- Include :$blog-title and :$blog-date where appropriate
- Never use em dashes (—). Use commas, colons, periods, or parentheses instead.

# DEBUGGING MALFORMED CLJ / EDN / CLJS

A single missing bracket can silently break an entire file. The compiler may
still succeed, with subsequent forms nesting inside the broken one.

1. Run clj-kondo first:
   clj -M:kondo --lint path/to/file.cljs

2. If clj-kondo reports a mismatch, write a bracket-tracing script.
   Do NOT try to eyeball-count brackets. Track a stack of (char, line, col)
   for every opener. Handle: string literals, escaped chars, ;; line comments.

3. Fix one bracket at a time, re-run checker after each fix.
   Common patterns:
   - Hiccup vector missing ] causes siblings to become extra arguments.
   - defn/let missing ) causes subsequent top-level forms to nest inside.
   - Both can coexist while total bracket count is zero-sum.

4. After brackets balance, rerun clj-kondo, then rebuild with shadow-cljs.

# End of rules
