# Task: AST Tuple Representation Opinion
Role: Architect / Language Designer

Context:
In datom.world, we are designing `yin.vm`, a semantic LISP VM. The owner has explicitly ruled:
1. The Map AST is the Universal AST's canonical semantic representation (a LISP using maps).
2. However, the Universal AST must be converted into a flat tuple projection that acts like bytecode, preserving everything so the VM can execute it quickly (linearization).
3. These tuples are indexed by an observer so they can be natively queried by `dao.space.query/q` with datalog.

The owner just raised a critical constraint: "but the AST tuple should not be an n-tuple. erlang and ribbit uses a triple. what should we use to represent our AST?"

We are considering options for a fixed-arity representation (like a triple) to replace the variable-arity n-tuples:
1. **The EAV Triple:** `[id attribute value]` (e.g., `[A :ast/tag :lambda]`, `[A :lambda/params [x]]`, `[A :lambda/body B]`). The database-native path.
2. **The Ribbit-style VM Triple:** `[opcode arg next-id]` (e.g., `[:const 1 B]`). The execution-native path.
3. **The Envelope Triple:** `[id tag payload-map]` (e.g., `[A :lambda {:params [x] :body B}]`). The LISP-map compromise.
4. **Something else?** (e.g., a pure cons-cell pair `[car cdr]`, or an Erlang-style Core AST representation).

Your Task:
Analyze the tradeoffs of these data structures given datom.world's constraints (datalog queryability, content-addressing, fast VM execution, LISP semantics). Give your strong, reasoned opinion on exactly which tuple/triple structure we should use to represent our AST. Write your response as a brief, punchy architectural recommendation.
