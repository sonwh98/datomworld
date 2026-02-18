# LSP Implementation with Yin.vm AST Database

## Overview

Traditional Language Server Protocol (LSP) implementations are text‑based, per‑language, and request‑response oriented. Yin.vm’s Universal AST stored as datoms in a Datalog database enables a fundamentally different approach: the entire codebase becomes a queryable semantic database. This document outlines how to implement LSP‑like tooling using Yin.vm’s architecture, as described in the blog post [“Beyond LSP: Queryable AST as the Universal Language Server”](../public/chp/blog/yin-vm-lsp.blog).

## Core Architecture

### 1. AST‑Native Code Representation
- **Canonical form**: Universal AST stored as datoms `[e a v t m]` in DaoDB.
- **Text as view**: Source files are renderings of the AST, not the primary artifact.
- **Multi‑language**: All languages in the Yin.vm family compile to the same AST representation.

### 2. Query‑Driven IDE Features
IDE features become Datalog queries over the AST database:
- **Go to definition**: Find the AST node defining a symbol.
- **Find references**: Find all AST nodes referencing a symbol.
- **Rename**: Update `:ast/name` datoms for all related nodes.
- **Hover**: Retrieve type‑certainty metadata attached to nodes.

### 3. Single Database, No Per‑Language Servers
- **DaoDB** stores AST datoms for all languages.
- **Indexes**: EAVT, AEVT, AVET, VAET covering indexes enable O(log n) queries.
- **History**: Every edit is a transaction; full versioning is automatic.

## Implementation Phases

### Phase 1: AST Ingestion Pipeline
1. **Language parsers** that emit Universal AST datoms.
   - Start with Clojure (Yang compiler).
   - Add Python, JavaScript, etc., via external parsers + AST mapping.
2. **DaoDB schema** for AST nodes:
   - Entity types: `:ast/function-definition`, `:ast/variable-reference`, etc.
   - Attributes: `:ast/name`, `:ast/type`, `:ast/location`, `:ast/certainty`.
   - Relationships: `:ast/child`, `:ast/parent`, `:ast/refers-to`.
3. **Incremental update**:
   - Watch source files, parse changed files, emit datom transactions.
   - Use stream‑based processing to keep DaoDB synchronized.

### Phase 2: Query Engine for IDE Features
1. **Datalog query library** for common operations:
   - `find-definition(node-id)`
   - `find-references(symbol-name)`
   - `find-all-functions()`
   - `find-cross-language-calls(from-lang, to-lang)`
2. **AST location ↔ source mapping**:
   - Store `:ast/source-file` and `:ast/source-position` as datoms.
   - Render AST nodes to text positions for traditional editor integration.
3. **Live subscriptions**:
   - IDE subscribes to datom streams for real‑time updates.
   - Queries re‑run automatically when relevant datoms change.

### Phase 3: Editor Integration
1. **LSP compatibility layer** (optional):
   - Accept LSP JSON‑RPC requests.
   - Translate to Datalog queries.
   - Return results in LSP format (line/column positions).
   - Allows existing IDEs (VS Code, IntelliJ) to work without modification.
2. **Native Yin.vm IDE client**:
   - Direct Datalog query interface.
   - AST‑native editing (structural edits, not text).
   - Multi‑language syntax rendering (switch between Python/Java/Clojure views).
3. **Collaborative editing**:
   - Multiple editors subscribe to the same DaoDB stream.
   - Edits are datom transactions; merge conflicts are semantic, not textual.

### Phase 4: Advanced Tooling
1. **Cross‑language refactoring**:
   - Rename symbol across Python, Java, and Clojure code in one transaction.
   - Extract function that works in multiple syntaxes.
2. **Time‑travel debugging**:
   - Query AST as of any transaction ID.
   - See what a function looked like 3 hours ago.
3. **Architecture enforcement**:
   - Datalog rules that flag violations (“UI layer importing database layer”).
   - Real‑time linting as queries over the live AST.
4. **Semantic search**:
   - “Find all functions that read from network and write to file.”
   - “Find code where dynamic data enters static functions without validation.”

## Technical Challenges

### 1. Parser Performance
- Need fast, incremental parsers for each language.
- Cache parsed ASTs as datoms; only re‑parse changed files.

### 2. Query Performance at Scale
- DaoDB indexes must handle millions of AST datoms.
- Consider sharding by project/module.
- Use materialized views for common queries.

### 3. Editor Responsiveness
- Queries must complete in <100ms for interactive use.
- Incremental query updates (not full re‑execution).
- Client‑side caching of query results.

### 4. Migration Path
- Existing codebases need conversion to AST datoms.
- Hybrid approach: start with LSP compatibility layer, gradually migrate to native.

### 5. Multi‑Language Semantic Preservation
- Ensure AST semantics are identical across languages (no “false friends”).
- Type‑certainty metadata must be accurate and useful.

## Success Metrics

### Short‑term (Phase 1–2)
- [ ] Clojure code fully queryable via DaoDB.
- [ ] Basic “go to definition” and “find references” working via Datalog.
- [ ] AST updates incrementally as files change.

### Medium‑term (Phase 3)
- [ ] LSP compatibility layer working with VS Code.
- [ ] Native Yin.vm IDE prototype with AST‑native editing.
- [ ] Collaborative editing between two users.

### Long‑term (Phase 4)
- [ ] Cross‑language refactoring across 3+ languages.
- [ ] Time‑travel queries usable in production.
- [ ] Semantic search answering complex questions across 1M+ LOC.

## Related Work

- **LSP**: JSON‑RPC protocol for language‑specific servers.
- **CodeQL/Glean**: Queryable AST databases for static analysis.
- **GraalVM/Truffle**: Polyglot runtime with shared AST.
- **Unison**: Content‑addressed code, but not queryable across languages.

Yin.vm combines the queryability of CodeQL with the execution portability of GraalVM and adds immutable datom streams for versioning and collaboration.

## Next Steps

1. **Read the blog post** [“Beyond LSP: Queryable AST as the Universal Language Server”](../public/chp/blog/yin-vm-lsp.blog) for the full vision.
2. **Extend Yang compiler** to emit more complete AST datoms (with source positions).
3. **Create DaoDB schema** for AST nodes and relationships.
4. **Write query library** for basic IDE features.
5. **Build LSP compatibility layer** to demonstrate viability.

---

*Last updated: 2026‑02‑18*  
*Related: [vm‑todo.md](./vm-todo.md) (VM performance improvements), [stream‑design.md](./stream-design.md) (stream architecture)*