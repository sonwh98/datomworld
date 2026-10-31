Role: Architect / Language Designer

Context:
The owner previously ruled that our canonical LISP AST should be linearized into tuples for fast VM execution and so `dao.space.query/q` can query it with datalog. 
Recently, the owner asked if we should use a "strict triple" because they thought Erlang, Ribbit, and Unison used triples. 
I pointed out that a strict triple forces us to pack a 4-arity `:if` node into `[:if test [then else]]`, which is awkward.

The owner just replied:
"its not necessary strict triples. i only mention triples because erlang, ribbit, and unison use triples but fact check me on that. if there is a better representation, i'm not stuck on strict tiples but it must be tuples so that dao.space.query/q can use datalog to query the code"

Also, the owner explicitly ruled that we MUST use De Bruijn indices for variables to achieve alpha-equivalent hashing and fast execution.

Task:
1. Fact check the owner: Do Erlang, Ribbit, and Unison actually use strict triples for their AST/bytecode?
2. Given that `dao.space.query/q` natively supports variable-arity n-tuples (it accepts mixed-dimensional tuple collections, though its current fast-path is optimized for 3-slot EAV), and given we are building a *dedicated AST indexer*, what is the absolute BEST tuple representation for our AST?
   Should we use variable-arity n-tuples (e.g., `[id :if test then else]`), strict 3-tuples (e.g., `[id :if test [then else]]`), or EAV datoms (`[id :ast/tag :if]`, `[id :if/test test]`)? 

Give a concise, authoritative recommendation that I can present back to the owner.
